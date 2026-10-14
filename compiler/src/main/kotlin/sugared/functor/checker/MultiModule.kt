package sugared.functor.checker

import sugared.functor.ast.CallExpr
import sugared.functor.ast.DeclEntry
import sugared.functor.ast.Entry
import sugared.functor.ast.Expr
import sugared.functor.ast.FileAst
import sugared.functor.ast.FunDecl
import sugared.functor.ast.Node
import sugared.functor.ast.VarStmt
import sugared.functor.codegen.JsCodeGen
import sugared.functor.parser.parseSourceWithNotes
import java.io.File
import java.util.IdentityHashMap

/**
 * 多模块编译驱动（P0 模块系统）：目录即模块 + 挂载。
 *
 * 流程：扫描树 → 校验（settings/挂载目标/命名冲突/环）→ 阶段 A 全模块收集（建 allSymbols）
 * → 阶段 B 类型可解析性+impl+pure → 阶段 C 声明体检查 → 合并诊断。
 * 单文件模式（现有 `Checker(file)`）不受影响——本驱动只处理目录。
 */
class MultiModule(root: File) {
    val tree: ModuleNode = scanModuleTree(root)
    val bag = DiagBag()
    val driver = ModuleTreeDriver(tree, bag)

    private val allSymbols = LinkedHashMap<String, Symbols>()
    private val checkers = LinkedHashMap<String, Checker>()
    private val asts: Map<String, FileAst>
    private var checked = false

    init {
        // 解析：每个 .subl → AST，同模块文件按序合并为一个 FileAst（同模块共享一个 Symbols）
        val m = LinkedHashMap<String, FileAst>()
        for (node in tree.allModules()) {
            val entries = ArrayList<Entry>()
            for (f in node.files) {
                val pr = parseSourceWithNotes(f.readText(), f.name)
                entries += pr.ast.entries
                for ((p0, m0) in pr.hints) bag.hint("H-ARITH-PRECEDENCE", p0, m0)
                for ((c0, p1, m1) in pr.supplements) bag.supplement(c0, p1, m1)
            }
            m[node.absPath] = FileAst(entries)
        }
        asts = m
        // 阶段 A：prelude + 符号收集（全部模块先落 allSymbols，供交叉引用/类型可解析性）
        for ((p, ast) in asts) {
            val chk = Checker(file = ast, modulePath = p, allSymbols = allSymbols, moduleTree = driver)
            chk.collectPrelude()
            chk.collectDecls(listOf(ast))
            allSymbols[p] = chk.syms
            checkers[p] = chk
        }
    }

    /** 运行全部语义检查（幂等：只执行一次），返回合并后的诊断袋。 */
    fun checkAll(): DiagBag {
        if (checked) return bag
        checked = true
        // 阶段 B：类型可解析性 + impl 完整性 + pure 事实（此时全树符号已齐）
        for ((p, chk) in checkers) chk.finishCollect(listOf(asts.getValue(p)))
        // 阶段 C：声明体与顶层语句
        for (chk in checkers.values) chk.checkAllBodies()
        // 入口唯一性（决策 70：整个项目有且仅有一个 main）
        val mains = asts.mapNotNull { (p, ast) ->
            val hasMain = ast.entries.any { e ->
                if (e !is DeclEntry) false
                else {
                    val d = e.decl
                    d is FunDecl && d.name == "main" && d.params.isEmpty() && d.body != null
                }
            }
            if (hasMain) p else null
        }
        if (mains.size > 1)
            bag.error("E-DUP-DECL", "", "多个模块声明了入口 main：${mains.joinToString(", ")}")
        for (chk in checkers.values) bag.absorb(chk.d)
        // v4 诊断：只保留根模块的补充级诊断——stdlib 等挂载模块内部的推导/方法糖是噪声
        bag.dropSupplementsOutside("")
        return bag
    }

    /** 合并编译（M8）：全部模块合成一个 JS 文件，顶层名字带模块前缀。 */
    fun generateJs(): String {
        val dictHits = IdentityHashMap<CallExpr, String>()
        val moduleHits = IdentityHashMap<Expr, String>()
        val consHits = IdentityHashMap<CallExpr, String>()
        val dictSubHits = IdentityHashMap<CallExpr, List<String>>()
        val namedArgHits = IdentityHashMap<CallExpr, List<Expr>>()
        val sugarHits = IdentityHashMap<CallExpr, List<Expr>>()
        val awaitHits = IdentityHashMap<CallExpr, Boolean>()
        val receiveSmarts = IdentityHashMap<CallExpr, Boolean>()
        val varUnwrapHits = IdentityHashMap<VarStmt, Expr>()
        val funBodyUnwrapHits = IdentityHashMap<Expr, Expr>()
        val flowEmitHits = IdentityHashMap<CallExpr, Boolean>()
        val hktKindHits = IdentityHashMap<CallExpr, Int>()
        val funRefTargets = IdentityHashMap<Expr, String>()
        val funRefFree = IdentityHashMap<Expr, Boolean>()
        val lambdaDestructKeys = IdentityHashMap<Expr, List<List<String>?>>()
        var channelUsed = false
        var mutexUsed = false
        var atomicUsed = false
        var flowUsed = false
        val rangeHits = IdentityHashMap<CallExpr, String>()
        var rangeUsed = false
        val deepCopyHits = IdentityHashMap<Node, Boolean>()
        val listMethodHits = IdentityHashMap<CallExpr, String>()
        for (chk in checkers.values) {
            dictHits.putAll(chk.dictHits)
            funRefTargets.putAll(chk.funRefTargets)
            funRefFree.putAll(chk.funRefFree)
            lambdaDestructKeys.putAll(chk.lambdaDestructKeys)
            moduleHits.putAll(chk.moduleHits)
            consHits.putAll(chk.consHits)
            dictSubHits.putAll(chk.dictSubHits)
            namedArgHits.putAll(chk.namedArgOrder)
            sugarHits.putAll(chk.methodSugarArgs)
            awaitHits.putAll(chk.asyncAwaitHits)
            receiveSmarts.putAll(chk.receiveSmartHits)
            varUnwrapHits.putAll(chk.varUnwrapHits)
            funBodyUnwrapHits.putAll(chk.funBodyUnwrapHits)
            flowEmitHits.putAll(chk.flowEmitHits)
            hktKindHits.putAll(chk.hktKindPosHits)
            if (chk.channelUsed) channelUsed = true
            if (chk.mutexUsed) mutexUsed = true
            if (chk.atomicUsed) atomicUsed = true
            if (chk.flowUsed) flowUsed = true
            rangeHits.putAll(chk.rangeHits)
            if (chk.rangeUsed) rangeUsed = true
            deepCopyHits.putAll(chk.deepCopyHits)
            listMethodHits.putAll(chk.listMethodHits)
        }
        return JsCodeGen().generateUnits(asts.toList(), dictHits, moduleHits, consHits, dictSubHits, namedArgHits, sugarHits, awaitHits, channelUsed, mutexUsed, atomicUsed, flowUsed, rangeHits, rangeUsed, receiveSmarts, varUnwrapHits, funBodyUnwrapHits, flowEmitHits, hktKindHits, funRefTargets, funRefFree.keys, lambdaDestructKeys, emptyMap(), deepCopyHits, listMethodHits)
    }
}

/**
 * 单文件 JS 生成入口（M4 跨模块边界）：从已经 run() 过的 Checker 生成 JS。
 * Checker 的留痕字段是 internal——CLI/LSP 等外部模块不应逐个搬运字段，而应经由本入口。
 */
fun generateSingle(chk: Checker, ast: FileAst): String = JsCodeGen().generate(
    file = ast,
    dictHits = chk.dictHits,
    moduleHits = chk.moduleHits,
    consHits = chk.consHits,
    dictSubHits = chk.dictSubHits,
    namedArgHits = chk.namedArgOrder,
    sugarHits = chk.methodSugarArgs,
    awaitHits = chk.asyncAwaitHits,
    channelUsed = chk.channelUsed,
    mutexUsed = chk.mutexUsed,
    atomicUsed = chk.atomicUsed,
    flowUsed = chk.flowUsed,
    rangeHits = chk.rangeHits,
    rangeUsed = chk.rangeUsed,
    receiveSmartHits = chk.receiveSmartHits,
    varUnwrapHits = chk.varUnwrapHits,
    funBodyUnwrapHits = chk.funBodyUnwrapHits,
    flowEmitHits = chk.flowEmitHits,
    hktKindPosHits = chk.hktKindPosHits,
    funRefTargets = chk.funRefTargets,
    funRefFree = chk.funRefFree.keys,
    lambdaDestructKeys = chk.lambdaDestructKeys,
    delegatedImpls = chk.delegatedImpls,
    deepCopyHits = chk.deepCopyHits,
    listMethodHits = chk.listMethodHits,
)