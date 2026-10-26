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
import sugared.functor.codegen.Platform
import sugared.functor.codegen.Target
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
class MultiModule(
    root: File,
    /** 激进模式（idea doc/激进模式.md §6）：--prover=aggressive 透传给每个 Checker */
    aggressive: Boolean = false,
    proveVerbose: Boolean = false,
    proveIntervalMs: Long = 5_000L,
    proveDumpPath: String? = null,
    /** t35（SfW Phase 1）：编译目标平台（默认 node 向后兼容——-A/SfA 路线不传本参） */
    val target: Target = Target.NODE,
) {
    private val agg = aggressive
    private val pv = proveVerbose
    private val pim = proveIntervalMs
    private val pdp = proveDumpPath
    val tree: ModuleNode = scanModuleTree(root)
    val bag = DiagBag()
    val driver = ModuleTreeDriver(tree, bag)

    private val allSymbols = LinkedHashMap<String, Symbols>()
    private val checkers = LinkedHashMap<String, Checker>()
    private var asts: MutableMap<String, FileAst>
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
            val chk = Checker(file = ast, modulePath = p, allSymbols = allSymbols, moduleTree = driver,
                aggressive = agg, proveVerbose = pv, proveIntervalMs = pim, proveDumpPath = pdp)
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
        runPhases()
        return bag
    }

    /**
     * 注解处理器阶段（root doc/注解处理器.md 13：插在 checker 后、codegen 前）。
     *
     * processor 对每个模块跑一次：输入（模块路径 / 该模块合并 AST / 符号表 / 诊断汇入闭包），
     * 输出变换后的 AST（未变则返回原引用）。任一模块有变化 → 全树重建 Checker 并统一重跑
     * 阶段 B/C——处理器产出视为新代码，模块间交叉引用的重新检查只在模块层做才完整；
     * 重查产生的重复诊断经袋内去重吸收。返回处理器自身的诊断袋。
     */
    fun processModules(processor: (modulePath: String, ast: FileAst, symbols: Symbols, sink: (Diag) -> Unit) -> FileAst): DiagBag {
        // 阶段 A（init 已完成符号收集）后、B/C 前跑处理器——处理器产出（如 @allMut 引入的 @mut）
        // 是要被合法化的代码，先变换再统一检查；已检查过的情形也兼容（重跑 B/C）。
        val out = DiagBag()
        var anyChanged = false
        for (p in asts.keys.toList()) {
            val oldAst = asts.getValue(p)
            val newAst = processor(p, oldAst, checkers.getValue(p).syms) { d ->
                when (d.severity) {
                    Severity.ERROR -> out.error(d.code, d.pos, d.message)
                    Severity.WARN -> out.warn(d.code, d.pos, d.message)
                    Severity.HINT -> out.hint(d.code, d.pos, d.message)
                    Severity.SUPPLEMENT -> out.supplement(d.code, d.pos, d.message)
                }
            }
            if (newAst !== oldAst) { asts[p] = newAst; anyChanged = true }
        }
        if (anyChanged) {
            // 阶段 A 重跑：每模块重建 Checker（收集声明）后再统一跑 B/C
            for (p in asts.keys.toList()) {
                val chk = Checker(file = asts.getValue(p), modulePath = p, allSymbols = allSymbols, moduleTree = driver,
            aggressive = agg, proveVerbose = pv, proveIntervalMs = pim, proveDumpPath = pdp)
                chk.collectPrelude()
                chk.collectDecls(listOf(asts.getValue(p)))
                allSymbols[p] = chk.syms
                checkers[p] = chk
            }
            runPhases()
            if (checked) bag.dedupe() else checked = true
        } else if (!checked) {
            runPhases()
            checked = true
        }
        return out
    }

    /** 阶段 B/C + 入口唯一性 + 诊断吸收（checkAll 与 processModules 重查共用）。 */
    // 修复：函数体 return bag（DiagBag），签名须一致——并发 WIP 遗留的 Unit 签名
    private fun runPhases(): DiagBag {
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
        // t35（SfW Phase 1）：模块级 target 可用性——core 子包按 Platform 表核对（未列 = 全 target 可用）
        if (target != Target.NODE) for (p in asts.keys) {
            if (!Platform.moduleAvailable(p, target))
                bag.error("E-PLATFORM-UNAVAILABLE", "",
                    "模块 $p 在目标 ${target.cliName} 下不可用（模块级可用性见 Target.kt 的 Platform）。" +
                    "改用 --target=node，或从工程里移除该模块的挂载")
        }
        for (chk in checkers.values) bag.absorb(chk.d)
        // v4 诊断：只保留根模块的补充级诊断——core 标准库等挂载模块内部的推导/方法糖是噪声
        bag.dropSupplementsOutside("")
        return bag
    }

    /** 变换+检查后的模块 AST 合并视图（供 V2 编译后阶段/处理器使用）。 */
    fun finalAst(): FileAst = FileAst(asts.values.flatMap { it.entries })

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
        // t35：按目标平台分派平台调用（node 保持原样）
        return JsCodeGen(target).generateUnits(asts.toList(), dictHits, moduleHits, consHits, dictSubHits, namedArgHits, sugarHits, awaitHits, channelUsed, mutexUsed, atomicUsed, flowUsed, rangeHits, rangeUsed, receiveSmarts, flowEmitHits, hktKindHits, funRefTargets, funRefFree.keys, lambdaDestructKeys, emptyMap(), deepCopyHits, listMethodHits)
    }
}

/**
 * 单文件 JS 生成入口（M4 跨模块边界）：从已经 run() 过的 Checker 生成 JS。
 * Checker 的留痕字段是 internal——CLI/LSP 等外部模块不应逐个搬运字段，而应经由本入口。
 */
fun generateSingle(chk: Checker, ast: FileAst, target: Target = Target.NODE): String = JsCodeGen(target).generate(
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
    flowEmitHits = chk.flowEmitHits,
    hktKindPosHits = chk.hktKindPosHits,
    funRefTargets = chk.funRefTargets,
    funRefFree = chk.funRefFree.keys,
    lambdaDestructKeys = chk.lambdaDestructKeys,
    delegatedImpls = chk.delegatedImpls,
    deepCopyHits = chk.deepCopyHits,
    listMethodHits = chk.listMethodHits,
)