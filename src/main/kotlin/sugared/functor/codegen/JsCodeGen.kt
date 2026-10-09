package sugared.functor.codegen

import sugared.functor.ast.*
import sugared.functor.checker.MODULE_METHOD_MARKER
import sugared.functor.checker.dictNameOf
import sugared.functor.checker.jsMangle
import sugared.functor.checker.fnTag
import sugared.functor.checker.moduleJsName

class CodegenError(message: String) : RuntimeException("[代码生成] $message")

/**
 * JS 代码生成器（M4）：源到源、零运行时库、零成本擦除（决策 22）。
 * 命题层（类型/理论参数/上下文/axiom/by/命题算符）全部擦除；值层映射到原生 JS。
 * 纯语法变换，不依赖符号表——struct/enum 工厂由声明生成，prelude 的 Bool(true/false) 按名字特判为字面量。
 *
 * v1 限制（文档标注）：
 *  - when 的 `is T` 模式：基本类型用 typeof 近似，其余保守生成 true
 *  - impl 方法按函数名生成，跨 trait 同名会冲突（单态化留待后续）
 *  - `==`/`!=` 生成结构相等调用 `__eq`（递归比较 tag 与全字段），仅在源码用到时注入
 *
 * P0（M8 合并编译）：顶层名字一律带模块前缀（根模块无前缀，用户拍板 Q4a）；
 * 全局引用的 mangle 结果由 Checker 按引用点实例留痕（moduleHits）传入——
 * codegen 不再猜测"裸名 = 本模块符号"，跨模块/同模块非根调用都能拿到正确前缀。
 */
class JsCodeGen {
    private val sb = StringBuilder()
    private var indent = 0
    /** 已知构造子名（含 prelude），用于区分模式里的裸名是构造子还是绑定变量 */
    private val ctorNames = LinkedHashSet<String>()
    /** when 主题临时名计数，避免嵌套 when 的 __s 变量冲突 */
    private var subjCounter = 0
    /** 零参构造子名：值引用时生成工厂调用 Name() */
    private val nullaryCtors = LinkedHashSet<String>()
    /** 预扫描命中位：源码含 `==`/`!=` 时注入结构相等助手，否则产物零运行时（决策 22） */
    private var eqHit = false

    /** 带 @tuple 形参的函数名（键 = 模块前缀全名）→ 该形参的位置（决策 61；P0 键带前缀防跨模块同名） */
    private val tupleParamPos = HashMap<String, Int>()

    /** 型类方法调用的字典留痕（决策 60，T5；O2 改键）：调用点实例 → 字典名，由语义层传入。
     *  IdentityHashMap 语义：同构但不同实例的调用点各自独立。 */
    private var dicts: Map<CallExpr, String> = emptyMap()
    /** P6（决策 82）：约束槽方法调用留痕（调用点实例 → 槽名）——`d_Show_T.m(d_Show_T, self, ...)` */
    private var consDicts: Map<CallExpr, String> = emptyMap()
    /** P6：带约束泛型函数的调用点字典实参（按声明顺序的字典名），调用前插 */
    private var dictSubArgs: Map<CallExpr, List<String>> = emptyMap()
    /** P8（决策 84）：命名参数规范化后的实参留痕（调用点实例 → 按形参声明顺序的实参列表） */
    private var namedArgOrders: Map<CallExpr, List<Expr>> = emptyMap()
    /** v1.1 集合方法糖留痕：调用点实例 → 重排实参（接收者前置），codegen 直接按此生成 */
    private var sugarArgs: Map<CallExpr, List<Expr>> = emptyMap()
    /** P0（M8）：全局引用的 JS 名留痕（键 = 引用表达式节点；限定方法调用 = MODULE_METHOD_MARKER） */
    private var moduleHits: Map<Expr, String> = emptyMap()
    /** P0（M8）：当前生成单元的模块 absPath（根 = ""） */
    private var currentPrefix = ""
    /** O2（决策 68）：当前正在生成的字典方法所属结构体的字段名（裸用→ __self.字段） */
    private var methodFields: Set<String> = emptySet()
    /** O2：当前方法体内局部声明过的名字（遮蔽字段者不重写；v1 粗粒度=体内全收集） */
    private var methodLocals: Set<String> = emptySet()
    /** P6（决策 82）：当前是否在生成 impl 字典方法体（`self` → `__self` 的映射开关；基类型 impl 无字段，methodFields 判据不可靠） */
    private var dictMethod = false
    /** 结构体字段名索引（从 AST 声明收集，codegen 不读符号表；P0：同名跨模块取最后者，见《模块系统.md》限制） */
    private val structFields = HashMap<String, List<String>>()
    /** v2.0：重载函数名集合（同名 >1 声明）——这些名字在 JS 里需按首参标签唯一化，避免同名覆盖 */
    private val overloadedFns = HashSet<String>()
    /** v2.0：每模块的重载函数名集合（模块路径 → 名），声明侧按此加后缀（与 Checker 侧 moduleHits 判定一致） */
    private val overloadsByUnit = HashMap<String, Set<String>>()
    /** v2.0：空安全 SafeCall 调用点的 JS 方法名（Checker 登记；SafeCallExpr → 函数名） */
    private var safeCallExprNames: Map<Expr, String> = emptyMap()
    /** v2.0 异步（决策 93）：@async 函数体内的挂起点调用（CallExpr → true），生成 `await fn(...)` */
    private var awaitHits: Map<CallExpr, Boolean> = emptyMap()

    fun generate(
        file: FileAst,
        dictHits: Map<CallExpr, String> = emptyMap(),
        moduleHits: Map<Expr, String> = emptyMap(),
        consHits: Map<CallExpr, String> = emptyMap(),
        dictSubHits: Map<CallExpr, List<String>> = emptyMap(),
        namedArgHits: Map<CallExpr, List<Expr>> = emptyMap(),
        sugarHits: Map<CallExpr, List<Expr>> = emptyMap(),
        awaitHits: Map<CallExpr, Boolean> = emptyMap(),
    ): String = generateUnits(listOf("" to file), dictHits, moduleHits, consHits, dictSubHits, namedArgHits, sugarHits, awaitHits)

    /** M8：全部模块合成一个 JS（顶层名字带模块前缀，根无前缀） */
    fun generateUnits(
        units: List<Pair<String, FileAst>>,
        dictHits: Map<CallExpr, String> = emptyMap(),
        moduleHits: Map<Expr, String> = emptyMap(),
        consHits: Map<CallExpr, String> = emptyMap(),
        dictSubHits: Map<CallExpr, List<String>> = emptyMap(),
        namedArgHits: Map<CallExpr, List<Expr>> = emptyMap(),
        sugarHits: Map<CallExpr, List<Expr>> = emptyMap(),
        awaitHits: Map<CallExpr, Boolean> = emptyMap(),
    ): String {
        dicts = dictHits
        this.moduleHits = moduleHits
        consDicts = consHits
        dictSubArgs = dictSubHits
        namedArgOrders = namedArgHits
        sugarArgs = sugarHits
        this.awaitHits = awaitHits
        currentPrefix = ""
        structFields.clear()
        overloadsByUnit.clear()
        for ((_, fa) in units) for (e in fa.entries) if (e is DeclEntry) {
            val s = e.decl as? StructDecl ?: continue
            structFields[s.name] = s.fields.map { it.name }
        }
        for ((p, fa) in units) overloadsByUnit[p] = collectOverloads(fa)
        // 收集构造子名：prelude + 全部模块 enum；tuple 形参位置键带模块前缀
        ctorNames.clear()
        ctorNames += listOf("true", "false", "null", "None", "Some")
        nullaryCtors.clear()
        nullaryCtors += listOf("None")   // prelude 零参构造子（Some 有参不含；true/false/null 走字面量）
        tupleParamPos.clear()
        for ((p, fa) in units) for (e in fa.entries) if (e is DeclEntry) {
            when (val ed = e.decl) {
                is EnumDecl -> ed.ctors.forEach { ctorNames += it.name; if (it.fields.isEmpty()) nullaryCtors += it.name }
                is FunDecl -> ed.params.forEachIndexed { i, pp ->
                    if ("tuple" in pp.annotations) tupleParamPos[moduleJsName(p, ed.name)] = i
                }
                else -> {}
            }
        }

        // 先生成主体到缓冲；是否注入结构相等助手由 needEqPreScan 预扫描决定
        sb.setLength(0); indent = 0
        line("(() => {")
        indent++
        // prelude 枚举工厂（Optional/Null 不在用户源码里声明，需内置生成）
        line("const None = () => ({ tag: \"None\" });")
        line("const Some = (a0) => ({ tag: \"Some\", 0: a0 });")
        // v2.0 空安全结构判定（决策 88-92，§7）：基本类型用 typeof，枚举/具名用 tag，函数 typeof===
        // JS 不记录类型信息 → 结构判定（与 == 结构相等同源）；v1 纯结构、零 tag 名义区分。
        line("const __isa = (v, t) => {")
        line("  if (t === \"Nat\" || t === \"Int\" || t === \"Rat\") return typeof v === \"number\";")
        line("  if (t === \"Str\") return typeof v === \"string\";")
        line("  if (t === \"Bool\") return typeof v === \"boolean\";")
        line("  if (t === \"Null\") return v === null || (v && v.tag === \"Null\");")
        line("  if (t === \"(fn)\") return typeof v === \"function\";")
        line("  return v !== null && typeof v === \"object\" && v.tag === t;")
        line("};")
        // 结构相等助手（决策 32/53）：仅在源码用到 ==/!= 时注入，保持"未用则零运行时"
        if (units.any { (_, fa) -> needEqPreScan(fa) }) {
            line("const __eq = (a, b) => {")
            line("  if (a === b) return true;")
            line("  if (typeof a !== \"object\" || typeof b !== \"object\" || a === null || b === null) return false;")
            line("  if (a.tag !== undefined && a.tag !== b.tag) return false;")
            line("  const ka = Object.keys(a), kb = Object.keys(b);")
            line("  if (ka.length !== kb.length) return false;")
            line("  return ka.every(k => __eq(a[k], b[k]));")
            line("};")
        }
        for ((p, fa) in units) {
            currentPrefix = p
            for (e in fa.entries) if (e is DeclEntry) genDecl(e.decl)
        }
        val mains = units.flatMap { (_, fa) -> fa.entries.filterIsInstance<StmtEntry>() }
        if (mains.isNotEmpty()) {
            blank(); comment("主序列")
            for (e in mains) genStmt(e.stmt)
        }
        // O4（决策 70）：具名 `fun main()`（0 形参）自动作为入口调用；P0：main 可位于任意模块，全局唯一由 MultiModule 检查
        val mainUnit = units.firstOrNull { (_, fa) ->
            fa.entries.filterIsInstance<DeclEntry>().any {
                val fn = it.decl as? FunDecl; fn != null && fn.name == "main" && fn.params.isEmpty() && fn.body != null
            }
        }
        if (mainUnit != null) line("${moduleJsName(mainUnit.first, "main")}();")
        indent--
        line("})();")
        val body = sb.toString()
        sb.setLength(0)
        line("\"use strict\";")
        sb.append(body)
        return sb.toString()
    }

    /**
     * 预扫描：源码里是否出现 `==`/`!=`（决定是否注入结构相等助手）。
     * 用类成员函数而非局部函数——局部函数不能互相前向引用（表达式遍历需调语句遍历，反之亦然）。
     */
    private fun needEqPreScan(file: FileAst): Boolean {
        eqHit = false
        for (e in file.entries) when (e) {
            is DeclEntry -> walkDeclEq(e.decl)
            is StmtEntry -> walkStmtEq(e.stmt)
        }
        return eqHit
    }

    private fun walkExprEq(e: Expr?) {
        if (e == null || eqHit) return
        when (e) {
            is BinExpr -> { if (e.op == "==" || e.op == "!=") eqHit = true; walkExprEq(e.left); walkExprEq(e.right) }
            is UniExpr -> walkExprEq(e.operand)
            is CallExpr -> { walkExprEq(e.callee); e.args.forEach { walkExprEq(it) } }
            is InstExpr -> { walkExprEq(e.target); e.terms.forEach { walkExprEq(it) } }
            is FieldExpr -> walkExprEq(e.target)
            is SymbolCallExpr -> {
                if (e.op == "==" || e.op == "!=") eqHit = true   // 前缀形式也要触发助手注入
                e.args.forEach { walkExprEq(it) }
            }
            is LambdaExpr -> walkExprEq(e.body)
            is BlockExpr -> e.stmts.forEach { walkStmtEq(it) }
            is IfExpr -> { walkExprEq(e.cond); walkExprEq(e.thenBlock); e.elseBlock?.let { walkExprEq(it) } }
            is WhenExpr -> { walkExprEq(e.subject); e.arms.forEach { walkExprEq(it.body); walkExprEq(it.guard) } }
            is TupleExpr -> e.items.forEach { walkExprEq(it) }
            is QuantExpr -> walkExprEq(e.body)
            is VarExpr -> walkExprEq(e.value)
            is AnonFunExpr -> walkExprEq(e.body)
            is ByExpr -> { walkExprEq(e.target); e.props.forEach { walkExprEq(it) } }
            else -> {}
        }
    }

    private fun walkStmtEq(s: Stmt) {
        when (s) {
            is VarStmt -> walkExprEq(s.value)
            is AssignStmt -> { walkExprEq(s.target); walkExprEq(s.value) }
            is ExprStmt -> walkExprEq(s.expr)
            is UncheckedStmt -> walkStmtEq(s.inner)
            is ReturnStmt -> walkExprEq(s.expr)
            else -> {}
        }
    }

    private fun walkDeclEq(d: Decl) {
        when (d) {
            is FunDecl -> walkExprEq(d.body)
            is ClassDecl -> d.members.forEach { walkExprEq(it.body) }
            is ImplDecl -> d.members.forEach { walkExprEq(it.body) }
            else -> {}
        }
    }

    // ---------- 声明 ----------

    private fun genDecl(decl: Decl) {
        when (decl) {
            is FunDecl -> genFun(decl)
            is StructDecl -> {
                // O3（决策 69）：字段带声明默认值时，JS 工厂用参数默认值补齐——省略实参即取默认
                val ps = decl.fields.joinToString(", ") { f ->
                    if (f.default != null) "${mangle(f.name)} = ${expr(f.default)}" else mangle(f.name)
                }
                val obj = decl.fields.joinToString(", ") { "${mangle(it.name)}: ${mangle(it.name)}" }
                line("const ${moduleJsName(currentPrefix, decl.name)} = ($ps) => ({ tag: ${q(decl.name)}, $obj });")
            }
            is EnumDecl -> for (c in decl.ctors) {
                if (c.name == "true" || c.name == "false") continue   // Bool 用 JS 字面量
                if (c.fields.isEmpty()) line("const ${moduleJsName(currentPrefix, c.name)} = () => ({ tag: ${q(c.name)} });")
                else {
                    val ps = c.fields.indices.joinToString(", ") { "a$it" }
                    val obj = c.fields.indices.joinToString(", ") { "$it: a$it" }
                    line("const ${moduleJsName(currentPrefix, c.name)} = ($ps) => ({ tag: ${q(c.name)}, $obj });")
                }
            }
            is ClassDecl -> decl.members.forEach { if (it.body != null) genFun(it) }
            // 决策 60（T5）：impl 成员生成字典对象，方法体收隐藏字典参数 d
            is ImplDecl -> genDict(decl)
            is TypeAliasDecl -> {}   // 纯类型层，零输出（别名在 checker 展开）
        }
    }

    /** impl → 型类字典对象：`const dict_<Trait>_<Type> = { m: function (d, __self, args…) { … } };`
     *  （O2，决策 68；第四轮：块体方法用 `function` 形式展平语句，让 return 真正退出方法）
     *  P0（M6）：字典名带声明模块前缀（与 Checker resolveDict 的 dictNameOf 同参数） */
    private fun genDict(impl: ImplDecl) {
        val dn = dictNameOf(impl.trait.name, impl.self, currentPrefix)
        line("const $dn = {")
        indent++
        val fields = structFields[(impl.self as? NamedType)?.name ?: ""] ?: emptyList()
        for ((i, m) in impl.members.withIndex()) {
            val body = m.body ?: continue
            // 隐藏首参 d（字典自身，供体内递归分发）+ 隐式接收者 __self（决策 68）
            val ps = (listOf("d", "__self") + m.params.map { mangle(it.name) }).joinToString(", ")
            val comma = if (i == impl.members.lastIndex) "" else ","
            // 字段裸用重写上下文：字段名 − 体内一切绑定名（保守：存在同名局部绑定时整体不重写，
            // 宁可 JS 报未定义也不产出静默错值）
            val savedFields = methodFields; val savedLocals = methodLocals
            methodFields = fields.toSet()
            methodLocals = m.params.map { it.name }.toSet() + collectBinders(body)
            val savedDict = dictMethod
            dictMethod = true
            if (body is BlockExpr) {
                // 块体方法展平成真正的函数：语句位 return 直接退出方法（第四轮）
                line("${m.name}: function ($ps) {")
                indent++
                genBlockBody(body)
                indent--
                line("}$comma")
            } else {
                line("${m.name}: ($ps) => ${expr(body)}$comma")
            }
            dictMethod = savedDict
            methodFields = savedFields; methodLocals = savedLocals
        }
        indent--
        line("};")
    }

    /** O2：收集子树里所有会绑定名字的位置（var 语句/表达式、lambda 形参、when 绑定与模式变量）。
     *  类成员函数而非局部函数——互递归不能前向引用（工程规范 I-13）。 */
    private fun collectBinders(e: Expr): Set<String> {
        val out = LinkedHashSet<String>()
        binderExpr(e, out)
        return out
    }

    private fun binderPat(p: Pattern, out: MutableSet<String>) {
        when (p) {
            is PatBind -> out.add(p.name)
            is PatIs -> p.bind?.let { out.add(it) }
            is PatCtor -> p.args.forEach { binderPat(it, out) }
            is PatTuple -> p.items.forEach { binderPat(it, out) }
            else -> {}
        }
    }

    private fun binderStmt(s: Stmt, out: MutableSet<String>) {
        when (s) {
            is VarStmt -> { out.add(s.name); binderExpr(s.value, out) }
            is AssignStmt -> binderExpr(s.value, out)
            is ExprStmt -> binderExpr(s.expr, out)
            is UncheckedStmt -> binderStmt(s.inner, out)
            else -> {}
        }
    }

    private fun binderExpr(x: Expr, out: MutableSet<String>) {
        when (x) {
            is BlockExpr -> x.stmts.forEach { binderStmt(it, out) }
            is IfExpr -> { binderExpr(x.cond, out); x.thenBlock.stmts.forEach { binderStmt(it, out) }; x.elseBlock?.stmts?.forEach { binderStmt(it, out) } }
            is WhenExpr -> {
                x.subjectBinding?.let { out.add(it) }
                binderExpr(x.subject, out)
                x.arms.forEach { a -> binderPat(a.pattern, out); a.guard?.let { binderExpr(it, out) }; binderExpr(a.body, out) }
            }
            is LambdaExpr -> { out.addAll(x.params); binderExpr(x.body, out) }
            is VarExpr -> { out.add(x.name); binderExpr(x.value, out) }
            is TupleExpr -> x.items.forEach { binderExpr(it, out) }
            is StructCtorExpr -> x.assigns.forEach { binderExpr(it.second, out) }
            is BinExpr -> { binderExpr(x.left, out); binderExpr(x.right, out) }
            is UniExpr -> binderExpr(x.operand, out)
            is CallExpr -> { binderExpr(x.callee, out); x.args.forEach { binderExpr(it, out) } }
            is InstExpr -> { binderExpr(x.target, out); x.terms.forEach { binderExpr(it, out) } }
            else -> {}
        }
    }

    private fun genFun(fn: FunDecl) {
        val body = fn.body ?: return   // 纯签名不生成
        // P6（决策 82）：带约束泛型函数的隐藏字典槽参数——`fun f[T: Show]` → f(d_Show_T, ...)
        val slots = fn.theory.filterIsInstance<TypeParam>().filter { it.constraint != null }
            .map { "d_${it.constraint}_${it.name}" }
        val ps = (slots + fn.params.map { mangle(it.name) }).joinToString(", ")
        // v2.0 重载：本模块内同名 >1 的函数按首参标签唯一化（`stdlib__map$List`），与 Checker 侧 moduleHits 同名规则一致
        val base = moduleJsName(currentPrefix, fn.name)
        val jsName = if (overloadsByUnit[currentPrefix]?.contains(fn.name) == true) "$base\$${fnTag(fn)}" else base
        // v2.0 异步（决策 93）：@async 函数与 main（天然 @async，异步 §5.2）生成 `async function`——体内挂起点（awaitHits）生成 await
        val asyncKw = if ("async" in fn.annotations || fn.name == "main") "async " else ""
        line("${asyncKw}function $jsName($ps) {")
        indent++
        when (body) {
            is BlockExpr -> genBlockBody(body)
            else -> line("return ${expr(body)};")
        }
        indent--
        line("}")
    }

    /** v2.0：收集模块内「同名且首参类型不同」的函数名（重载组）——与 Checker 的 E-DUP-DECL 放行判据一致 */
    private fun collectOverloads(fa: FileAst): Set<String> {
        val seen = HashMap<String, String>()
        val out = HashSet<String>()
        for (e in fa.entries) if (e is DeclEntry) {
            val fd = e.decl as? FunDecl ?: continue
            val tag = fd.params.firstOrNull()?.type?.render() ?: ""
            val prev = seen.putIfAbsent(fd.name, tag)
            if (prev != null && prev != tag) out += fd.name
        }
        return out
    }

    /** 块体展平进真正的 JS 函数/对象方法：语句逐条发射。
     *  v1.1 Kotlin 化：块式函数**不隐式返回尾表达式**——返回值只来自显式 return；
     *  无 return（且省略/Null 返回类型）时补 `return null`。具名函数显式返回类型时，
     *  checker 已强制全路径 return（diverges），故非发散尾表达式只按语句发射、值丢弃。 */
    private fun genBlockBody(body: BlockExpr) {
        val tail = body.stmts.lastOrNull() as? ExprStmt
        for (s in body.stmts) if (s !== tail) genStmt(s)
        when {
            tail == null -> if (body.stmts.lastOrNull() !is ReturnStmt) line("return null;")
            diverges(tail.expr) -> genStmt(tail)   // 尾 if 全路径 return：展平，各分支已 return
            else -> { genStmt(tail); line("return null;") }   // 尾表达式按语句发射（值丢弃）
        }
    }

    /** 全路径 return 的 if 展平成真正的 JS 语句（决策 75：提前 return 直接退出函数）。
     *  分支内语句统一交 genStmt——其 ExprStmt 分派会递归处理嵌套的同型 if。 */
    private fun ifStmtFlatten(e: IfExpr) {
        line("if (${expr(e.cond)}) {")
        indent++
        e.thenBlock.stmts.forEach { genStmt(it) }
        indent--
        if (e.elseBlock != null) {
            line("} else {")
            indent++
            e.elseBlock.stmts.forEach { genStmt(it) }
            indent--
        }
        line("}")
    }

    // ---------- 语句 ----------

    private fun genStmt(s: Stmt) {
        when (s) {
            is VarStmt -> {
                val kw = if ("mut" in s.annotations) "let" else "const"
                line("$kw ${mangle(s.name)} = ${expr(s.value)};")
            }
            is AssignStmt -> line("${expr(s.target)} = ${expr(s.value)};")
            // 命题逃逸：擦除为注释，可 grep 审计
            is AxiomStmt -> s.props.forEach { comment("axiom: ${tag(it)}") }
            is ByStmt -> s.props.forEach { comment("by: ${tag(it)}") }
            is UncheckedStmt -> { comment("unchecked"); genStmt(s.inner) }
            is ExprStmt -> {
                // 决策 75：语句位 if 一律展平成真正的 JS 控制语句（与检查器给它的 flow=true 对齐）；
                // 分支里的 return 因此能真正退出函数。值位 if（var 初值/尾表达式）仍走 expr→IIFE。
                val x = s.expr
                if (x is IfExpr) ifStmtFlatten(x)
                else line("${expr(x)};")
            }
            // 第四轮：显式 return 语句发射为真 return（仅在函数体/块体方法的展平语境出现）
            is ReturnStmt -> line(if (s.expr != null) "return ${expr(s.expr)};" else "return;")
        }
    }

    // ---------- 表达式 ----------

    private fun expr(e: Expr): String = when (e) {
        is IntLit -> e.value
        is FloatLit -> e.value   // P10（决策 86）：浮点字面量原文直出（JS Number，IEEE double）
        is StrLit -> q(e.value)
        is NameRef -> when {
            e.name == "true" || e.name == "false" || e.name == "null" -> e.name   // JS 字面量
            e.name in nullaryCtors -> "${moduleHits[e] ?: mangle(e.name)}()"       // 零参构造子值引用→调用
            // P0（M8）：全局引用按 Checker 留痕的 mangle 名；局部变量/字段不留痕
            moduleHits.containsKey(e) -> moduleHits.getValue(e)
            // O2（决策 68）：方法体内裸用字段名 → __self.字段（被局部绑定遮蔽者除外）
            e.name in methodFields && e.name !in methodLocals -> "__self.${mangle(e.name)}"
            // P6（决策 82）：字典方法体内显式 `self`（隐式接收者，决策 68）→ __self 参数
            e.name == "self" && dictMethod && e.name !in methodLocals -> "__self"
            else -> mangle(e.name)
        }
        ReturnSym -> "__ret"
        is ParamRefExpr -> "/* $" + "$" + e.index + " */"   // $n 参数引用（决策 65）：命题层专用，产物以注释占位
        TopExpr -> "true"
        BotExpr -> "false"
        is UniExpr -> "(${unop(e.op)}${expr(e.operand)})"
        is BinExpr -> bin(e)
        // v2.0 空安全（决策 88-92）
        is ElvisExpr -> { val t = expr(e.left); "(() => { const _v = $t; return _v.tag === \"None\" ? ${expr(e.right)} : _v[0]; })()" }
        is SafeCallExpr -> safeCallJs(e)
        is CastExpr -> { val t = expr(e.target); "(() => { const _v = $t; return __isa(_v, \"${e.type.name}\") ? Some(_v) : None(); })()" }
        is TypeTestExpr -> "(__isa(${expr(e.target)}, \"${e.type.name}\"))"
        is SymbolCallExpr -> symbolCall(e)
        is FieldExpr -> moduleHits[e] ?: "${expr(e.target)}.${mangle(e.name)}"   // P0：跨模块限定引用走留痕名
        is CallExpr -> call(e)
        is InstExpr -> expr(e.target)   // 实例化/供给整体擦除，只留被调名
        is LambdaExpr -> {
            val ps = e.params.joinToString(", ") { mangle(it) }
            // v1.1：lambda 体可为语句块（含 return/var/if）；块体默认返回最后表达式
            if (e.body is BlockExpr) "($ps) => ${lambdaBodyStr(e.body)}"
            else "($ps) => ${expr(e.body)}"
        }
        is TupleExpr -> "[" + e.items.joinToString(", ") { expr(it) } + "]"   // 元组→JS 数组（决策 61）
        is StructCtorExpr -> structCtorJs(e)   // O3（决策 69）：命名字段构造→位置实参工厂调用
        is BlockExpr -> blockIIFE(e)
        is IfExpr -> ifExpr(e)
        is WhenExpr -> whenExpr(e)
        is QuantExpr -> expr(e.body)    // 量词命题擦除
        is VarExpr -> varExprJs(e)
        is AnonFunExpr -> anonFun(e)
        is ByExpr -> expr(e.target)     // by 后缀擦除，只留目标值
    }

    /** var 表达式：擦除为 IIFE，内部 const 绑定并返回初值（声明即表达式） */
    private fun varExprJs(e: VarExpr): String {
        val kw = if ("mut" in e.annotations) "let" else "const"
        return "(() => { $kw ${mangle(e.name)} = ${expr(e.value)}; return ${mangle(e.name)}; })()"
    }

    private fun bin(e: BinExpr): String {
        // 命题层中缀（= : u系 逻辑）在值上下文擦除为 true（它们只在命题位出现，不进 JS 值）
        val op = when (e.op) {
            "+", "-", "*", "/", "%", "<", ">", "<=", ">=" -> e.op
            // `==` 是结构相等（决策 32/53）：struct 全字段相等即相等，禁重写
            "==" -> return "__eq(${expr(e.left)}, ${expr(e.right)})"
            "!=" -> return "(!__eq(${expr(e.left)}, ${expr(e.right)}))"
            "!", "¬" -> "!"
            "&", "∧", "u&" -> "&&"
            "|", "∨", "u|" -> "||"
            "=", ":", "->", "→", "u->", "<->", "↔", "u<->", "u!=" -> return "true"  // 纯命题，擦除
            else -> return "true"   // 自定义符号中缀（两参解糖）：命题，擦除
        }
        return "(${expr(e.left)} $op ${expr(e.right)})"
    }

    /**
     * 前缀符号调用 `==(a,b)`、`<(1,2)`（决策 32：`==`/`!=` 是函数，禁中缀，只能前缀调用）。
     * `==`/`!=` 走结构相等助手；比较符生成中缀运算。
     */
    private fun symbolCall(e: SymbolCallExpr): String {
        val a = e.args.map { expr(it) }
        return when (e.op) {
            "==" -> if (a.size == 2) "__eq(${a[0]}, ${a[1]})" else "false"
            "!=" -> if (a.size == 2) "(!__eq(${a[0]}, ${a[1]}))" else "false"
            "<", ">", "<=", ">=" -> if (a.size == 2) "(${a[0]} ${e.op} ${a[1]})" else "false"
            else -> "undefined"   // 未收录的前缀符号：不应出现（解析器 symbolCallStart 限定）
        }
    }

    /** v2.0 空安全 SafeCall：`a?.f(b)` → a 为 Some 时调 f(a0, b…) 并包 Some；None 短路返回 None。
     *  方法名从 moduleHits[e] 取（Checker 已按接收者解构后的类型路由）；`
     *  非 Optional 是语义已报错，genExpr 不会走到——防御性返回 None()。 */
    private fun safeCallJs(e: SafeCallExpr): String {
        val t = expr(e.target)
        val inner = "_v[0]"
        // 内建零参值方法（Str.length 等）：JS 原生 `.property` 后缀形态（无参）；解构后直接访问。
        // 其余（stdlib 自由函数/重载方法）走函数调用 `fn(inner, args…)`。
        if (e.name == "length" && e.args.isEmpty()) {
            return "(() => { const _v = $t; return _v.tag === \"None\" ? None() : Some($inner.length); })()"
        }
        val fn = moduleHits[e] ?: jsMangle(e.name)
        val argJs = e.args.joinToString(", ") { expr(it) }
        val callJs = "$fn($inner${if (argJs.isNotEmpty()) ", $argJs" else ""})"
        return "(() => { const _v = $t; return _v.tag === \"None\" ? None() : Some($callJs); })()"
    }

    private fun unop(op: String): String = when (op) {
        "-", "!", "¬" -> op
        else -> "!"
    }

    private fun call(e: CallExpr): String {
        // 供给尖括号已在 InstExpr 擦除；此处 callee 可能是 NameRef 或已擦除的 InstExpr
        val callee = e.callee
        val name = when (callee) {
            is NameRef -> callee.name
            is InstExpr -> (callee.target as? NameRef)?.name ?: return "undefined"
            is FieldExpr -> callee.name   // O2（决策 68）：接收者调用 o.m(...) 的方法名
            else -> null
        }
        // 构造子/枚举名：Bool 的 true/false 特判
        if (name == "true") return "true"
        if (name == "false") return "false"
        if (name == "println" || name == "print") {
            val a = e.args.joinToString(", ") { expr(it) }
            return "console.log($a)"
        }
        // P3 IO（v1.0 计划 §5）：node 原语，内联 require('fs')（node 模块缓存，无性能问题）
        // P5（决策 81）：readFile/writeFile/readLine 返回 Result[_, Str]——try/catch 包 Ok/Err（stdlib/result.subl）
        if (name == "readLine" && e.args.isEmpty())
            return "(() => { try { const b = Buffer.alloc(4096); const n = require('fs').readSync(0, b, 0, 4096, null); if (n <= 0) return stdlib__Err(\"EOF\"); return stdlib__Ok(b.slice(0, n).toString('utf8').trim()); } catch (x) { return stdlib__Err(x.message); } })()"
        if (name == "readFile" && e.args.size == 1)
            return "(() => { try { return stdlib__Ok(require('fs').readFileSync(${expr(e.args[0])}, 'utf8')); } catch (x) { return stdlib__Err(x.message); } })()"
        if (name == "writeFile" && e.args.size == 2)
            return "(() => { try { require('fs').writeFileSync(${expr(e.args[0])}, ${expr(e.args[1])}); return stdlib__Ok(null); } catch (x) { return stdlib__Err(x.message); } })()"
        if (name == "getArgs" && e.args.isEmpty())
            return "(() => { const a = process.argv.slice(2); let r = stdlib__Nil(); for (let i = a.length - 1; i >= 0; i--) r = stdlib__Cons(a[i], r); return r; })()"
        // P1 字符串内建（v1.0 计划 §3）：JS 原生映射，与 print 同款特判（在内建之上、模块前缀留痕之前）
        if (name == "concat" && e.args.size == 2)
            return "(${expr(e.args[0])} + ${expr(e.args[1])})"
        if (name == "length" && e.args.size == 1)
            return "(${expr(e.args[0])}).length"
        if (name == "charAt" && e.args.size == 2)
            return "((${expr(e.args[0])})[${expr(e.args[1])}] ?? \"\")"
        if (name == "substring" && e.args.size == 3)
            return "(${expr(e.args[0])}).slice(${expr(e.args[1])}, ${expr(e.args[2])})"
        if (name == "strCmp" && e.args.size == 2) {
            val x = expr(e.args[0]); val y = expr(e.args[1])
            return "($x < $y ? -1 : ($x > $y ? 1 : 0))"
        }
        if (name == "toStr" && e.args.size == 1)
            return "String(${expr(e.args[0])})"
        if (name == "parseNat" && e.args.size == 1)
            return "(() => { const m = String(${expr(e.args[0])}).match(/^(0|[1-9][0-9]*)$/); return m ? Some(Number(m[0])) : None(); })()"
        // P10 浮点数 Rat（决策 86，候选 A：IEEE double via JS Number）：JS 原生映射
        if (name == "toRat" && e.args.size == 1)
            return "Number(${expr(e.args[0])})"
        if (name == "parseRat" && e.args.size == 1)
            return "(() => { const v = Number(String(${expr(e.args[0])})); return Number.isNaN(v) ? None() : Some(v); })()"
        if (name == "abs" && e.args.size == 1) return "Math.abs(${expr(e.args[0])})"
        if (name == "sqrt" && e.args.size == 1) return "Math.sqrt(${expr(e.args[0])})"
        if (name == "floor" && e.args.size == 1) return "Math.floor(${expr(e.args[0])})"
        if (name == "ceil" && e.args.size == 1) return "Math.ceil(${expr(e.args[0])})"
        if (name == "pow" && e.args.size == 2) return "Math.pow(${expr(e.args[0])}, ${expr(e.args[1])})"
        // P2 数组原语（v1.0 计划 §4.2 步骤 A）：JS 原生映射
        if (name == "arrayOf") return "[${e.args.joinToString(", ") { expr(it) }}]"
        if (name == "arrayLength" && e.args.size == 1) return "(${expr(e.args[0])}).length"
        if (name == "arrayGet" && e.args.size == 2) {
            val arr = expr(e.args[0]); val idx = expr(e.args[1])
            return "(() => { const e = ($arr)[$idx]; return (e === undefined ? None() : Some(e)); })()"
        }
        if (name == "arraySet" && e.args.size == 3) {
            val arr = expr(e.args[0]); val idx = expr(e.args[1]); val v = expr(e.args[2])
            return "(() => { const n = ($arr).slice(); n[$idx] = $v; return n; })()"
        }
        // 元组构造（决策 62）：prelude 内建，无用户声明对应
        if (name == "emptyTuple") return "[]"
        if (name == "singleTuple") return "[${e.args.joinToString(", ") { expr(it) }}]"
        // @tuple 形参调用：从该位起的实参在 JS 里打包成一个数组参数（决策 61；P0 键带模块前缀）
        val tp = if (name != null) (tupleParamPos[moduleHits[callee] ?: moduleJsName(currentPrefix, name)] ?: -1) else -1
        if (tp >= 0 && e.args.size > tp) {
            val fixed = e.args.take(tp).joinToString(", ") { expr(it) }
            val packed = "[" + e.args.drop(tp).joinToString(", ") { expr(it) } + "]"
            return listOf(fixed, packed).filter { it.isNotEmpty() }.joinToString(", ")
        }
        // 决策 60/68（O2）：型类方法调用按调用点查字典分发，方法体签名为 (d, __self, args…)。
        // 点号形态 o.m(args) 与自由式 m(o, args) 统一补齐隐藏字典 d 与接收者 __self。
        // P0：限定方法 `core.show(p)` 用 MODULE_METHOD_MARKER 标记——首实参即接收者（自由形态语义）。
        // P6（决策 82）：约束槽方法调用 `x.show()`（x: T, T: Show）→ `d_Show_T.show(d_Show_T, x)`
        val consSlot = consDicts[e]
        if (consSlot != null && name != null) {
            val selfJs = if (callee is FieldExpr) expr(callee.target)
                else if (e.args.isEmpty()) "undefined" else expr(e.args[0])
            val argsJs = if (callee is FieldExpr) e.args.drop(1) else e.args
            val ra2 = argsJs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
            val all = listOf(consSlot, selfJs) + (if (ra2.isEmpty()) emptyList() else listOf(ra2))
            return "$consSlot.${mangle(name)}(${all.joinToString(", ")})"
        }
        val dn = dicts[e]
        if (dn != null && name != null) {
            val selfJs: String; val realArgs: List<Expr>
            if (moduleHits[callee] == MODULE_METHOD_MARKER) { selfJs = expr(e.args[0]); realArgs = e.args.drop(1) }
            else if (callee is FieldExpr) { selfJs = expr(callee.target); realArgs = e.args }
            else {
                if (e.args.isEmpty()) return "$dn.${mangle(name)}($dn, undefined)"
                selfJs = expr(e.args[0]); realArgs = e.args.drop(1)
            }
            val ra = realArgs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
            val all = listOf(dn, selfJs) + if (ra.isEmpty()) emptyList() else listOf(ra)
            return "$dn.${mangle(name)}(${all.joinToString(", ")})"
        }
        // P8（决策 84）：命名参数调用点用检查后规范化的实参列表（按形参声明顺序重排）
        val genArgs: List<Expr> = sugarArgs[e] ?: (namedArgOrders[e] ?: e.args)
        val args = genArgs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
        val calleeJs = when (callee) {
            is NameRef -> moduleHits[callee] ?: mangle(callee.name)
            is FieldExpr -> moduleHits[callee] ?: "${expr(callee.target)}.${mangle(callee.name)}"   // 非方法字段（函数值字段）调用
            is InstExpr -> expr(callee.target)
            else -> expr(callee)
        }
        // P6（决策 82）：带约束泛型函数调用——前插实际字典实参（f(dict_Show_Nat, xs)）。
        // 仅 NameRef/InstExpr 直接定位（FieldExpr 字段值是函数值，无约束函数概念）。
        val preDicts = if (callee is FieldExpr) "" else (dictSubArgs[e] ?: emptyList()).joinToString(", ")
        val allArgs = listOf(preDicts, args).filter { it.isNotEmpty() }.joinToString(", ")
        val callJs = "$calleeJs($allArgs)"
        // v2.0 异步（决策 93）：挂起点调用（@async 体内调 @async 函数）生成 `await fn(...)`
        return if (awaitHits[e] == true) "await $callJs" else callJs
    }

    /** O3（决策 69）：命名字段构造 `A(name = e)` → 位置实参工厂调用，缺省字段传 undefined 取 JS 参数默认值；
     *  工厂名带当前模块前缀（StructCtorExpr 恒为同模块裸名引用，P0） */
    private fun structCtorJs(e: StructCtorExpr): String {
        val fields = structFields[e.struct] ?: emptyList()
        val given = e.assigns.associate { (n, v) -> n to expr(v) }
        val positional = fields.map { given[it] ?: "undefined" }
        return "${moduleJsName(currentPrefix, e.struct)}(${positional.joinToString(", ")})"
    }

    private fun blockIIFE(e: BlockExpr): String = blockStr(e)

    /** 语句转单行字符串（供 IIFE 内联；var/assign/expr 足够，axiom/by 擦除为空） */
    private fun stmtStr(s: Stmt): String = when (s) {
        is VarStmt -> "${if ("mut" in s.annotations) "let" else "const"} ${mangle(s.name)} = ${expr(s.value)};"
        is AssignStmt -> "${expr(s.target)} = ${expr(s.value)};"
        is ExprStmt -> "${expr(s.expr)};"
        is UncheckedStmt -> stmtStr(s.inner)
        is AxiomStmt, is ByStmt -> ""
        // 第四轮：值位块的语句串化——语义层已禁止值位 return（E-RETURN-OUTSIDE），
        // 此分支是防御性正确语义（IIFE 内 return 即该块的值）
        is ReturnStmt -> if (s.expr != null) "return ${expr(s.expr)};" else "return null;"
    }

    private fun ifExpr(e: IfExpr): String {
        val cond = expr(e.cond)
        val th = blockStr(e.thenBlock)
        val el = e.elseBlock?.let { blockStr(it) }
        return if (el != null) "($cond ? $th : $el)" else "($cond ? $th : null)"
    }

    /** 块 → JS 表达式（IIFE 字符串） */
    private fun blockStr(e: BlockExpr): String {
        val tail = e.stmts.lastOrNull() as? ExprStmt
        val lines = ArrayList<String>()
        for (s in e.stmts) if (s !== tail) { val t = stmtStr(s); if (t.isNotEmpty()) lines += t }
        val ret = if (tail != null) "return ${expr(tail.expr)};" else "return null;"
        return "(() => {\n" + (lines + ret).joinToString("\n") { "  $it" } + "\n})()"
    }

    // ---------- v1.1：lambda 语句体（Kotlin 风格：块内可 return / var / 嵌套 if） ----------

    /** lambda 块体 → JS 箭头函数体 `{ … }`：尾表达式转 return（lambda 默认返回最后表达式） */
    private fun lambdaBodyStr(body: BlockExpr): String {
        val lines = ArrayList<String>()
        for (i in body.stmts.indices) {
            val s = body.stmts[i]
            val isTail = i == body.stmts.lastIndex
            when {
                isTail && s is ExprStmt && !diverges(s.expr) -> lines += "  return ${expr(s.expr)};"
                isTail && s is ExprStmt && diverges(s.expr) -> lambdaStmtToLines(s, lines, "  ")
                isTail && s is ReturnStmt -> lambdaStmtToLines(s, lines, "  ")
                else -> lambdaStmtToLines(s, lines, "  ")
            }
        }
        return if (lines.isEmpty()) "{}" else "{\n" + lines.joinToString("\n") + "\n}"
    }

    /** lambda 体内单条语句 → 缩进行（if 展平为真语句，return 原样发射） */
    private fun lambdaStmtToLines(s: Stmt, out: MutableList<String>, ind: String) {
        when (s) {
            is VarStmt -> out += "$ind${if ("mut" in s.annotations) "let" else "const"} ${mangle(s.name)} = ${expr(s.value)};"
            is AssignStmt -> out += "$ind${expr(s.target)} = ${expr(s.value)};"
            is ExprStmt -> if (s.expr is IfExpr) lambdaIfLines(s.expr as IfExpr, out, ind) else out += "$ind${expr(s.expr)};"
            is ReturnStmt -> out += "$ind${if (s.expr != null) "return ${expr(s.expr)};" else "return;"}"
            is UncheckedStmt -> lambdaStmtToLines(s.inner, out, ind)
            is AxiomStmt, is ByStmt -> {}
        }
    }

    /** lambda 体内全路径 return 的 if：展平为真 JS if（分支 return 穿透 lambda） */
    private fun lambdaIfLines(e: IfExpr, out: MutableList<String>, ind: String) {
        out += "$ind if (${expr(e.cond)}) {"
        e.thenBlock.stmts.forEach { lambdaStmtToLines(it, out, "$ind  ") }
        if (e.elseBlock != null) {
            out += "$ind} else {"
            e.elseBlock.stmts.forEach { lambdaStmtToLines(it, out, "$ind  ") }
        }
        out += "$ind}"
    }

    private fun whenExpr(e: WhenExpr): String {
        val sname = "__s${subjCounter++}"
        val subjJs = if (e.subjectBinding != null) mangle(e.subjectBinding) else sname
        val lines = ArrayList<String>()
        lines += if (e.subjectBinding != null) "const ${mangle(e.subjectBinding)} = ${expr(e.subject)};"
                 else "const $sname = ${expr(e.subject)};"
        var closed = false
        for (arm in e.arms) {
            val conds = ArrayList<String>()
            val binds = ArrayList<Pair<String, String>>()
            matchJs(arm.pattern, subjJs, conds, binds)
            val bodyJs = if (arm.body is BlockExpr) blockStr(arm.body) else expr(arm.body)
            val bindJs = binds.joinToString("") { "const ${mangle(it.first)} = ${it.second}; " }
            val condStr = if (conds.isEmpty()) null else conds.joinToString(" && ") { "($it)" }
            if (condStr == null && arm.guard == null) {   // 无守卫的兜底分支：直接 return
                lines += "$bindJs return $bodyJs;"
                closed = true
                break
            }
            // 有守卫：守卫可能引用模式绑定变量（如 `m if !=(m,0)`），故必须**先绑定、再判守卫**——
            // 结构 `if (cond) { binds; if (guard) return body; }`（旧版把守卫并进外层条件会用到未绑定的 m）。
            when {
                arm.guard != null && bindJs.isNotEmpty() ->
                    lines += "if (${condStr ?: "true"}) { $bindJs if (${expr(arm.guard)}) return $bodyJs; }"
                arm.guard != null ->
                    lines += "if (${condStr ?: "true"} && (${expr(arm.guard)})) { return $bodyJs; }"
                else ->
                    lines += "if ($condStr) { $bindJs return $bodyJs; }"
            }
        }
        if (!closed) lines += "return null;"   // 穷尽时不可达，语义层已保证
        return "(() => {\n" + lines.joinToString("\n") { "  $it" } + "\n})()"
    }

    /**
     * 模式 → JS 条件与字段绑定（路径式，支持嵌套）：
     * `Some(Some(x))` → cond `s.tag==="Some" && s[0].tag==="Some"`，bind `x = s[0][0]`。
     * 裸名若是已知构造子按标签比较，否则按绑定变量（与语义层 patternBoundNames 同规则）。
     */
    private fun matchJs(p: Pattern, path: String, conds: MutableList<String>, binds: MutableList<Pair<String, String>>) {
        when (p) {
            is PatElse -> {}
            is PatBind -> if (p.name != "_") binds += p.name to path
            is PatLit -> conds += "$path === ${expr(p.value)}"
            is PatIs -> {
                conds += isCond(p.type, path)
                p.bind?.let { binds += it to path }
            }
            is PatTuple -> {
                // 元组值即 JS 数组：长度相等 + 分量逐个匹配（决策 61）
                conds += "(Array.isArray($path) && $path.length === ${p.items.size})"
                p.items.forEachIndexed { i, ip -> matchJs(ip, "$path[$i]", conds, binds) }
            }
            is PatCtor -> {
                if (p.args.isEmpty()) {
                    if (p.name in ctorNames) when (p.name) {
                        "true", "false" -> conds += "$path === ${p.name}"
                        "null" -> conds += "$path === null"
                        else -> conds += "$path.tag === ${q(p.name)}"
                    } else binds += p.name to path   // 绑定变量
                } else {
                    conds += "$path.tag === ${q(p.name)}"
                    p.args.forEachIndexed { i, ap -> matchJs(ap, "$path[$i]", conds, binds) }
                }
            }
        }
    }

    private fun isCond(t: Type, subjJs: String): String = when (t.name) {
        "Nat", "Int", "Rat" -> "(typeof $subjJs === \"number\")"
        "Str" -> "(typeof $subjJs === \"string\")"
        "Bool" -> "(typeof $subjJs === \"boolean\")"
        else -> "true"   // 名义类型无法运行时判定，保守 true（v1 限制）
    }

    private fun anonFun(e: AnonFunExpr): String {
        val ps = e.params.joinToString(", ") { mangle(it.name) }
        val body = e.body
        return if (body is BlockExpr) {
            val tail = body.stmts.lastOrNull() as? ExprStmt
            val lines = ArrayList<String>()
            for (s in body.stmts) if (s !== tail) { val t = stmtStr(s); if (t.isNotEmpty()) lines += t }
            val ret = if (tail != null) "return ${expr(tail.expr)};" else "return null;"
            "($ps) => {\n" + (lines + ret).joinToString("\n") { "  $it" } + "\n}"
        } else "($ps) => ${expr(body ?: NameRef("null"))}"
    }

    // ---------- 工具 ----------

    /** 命题项的可读标签（axiom/by 审计注释用） */
    private fun tag(e: Expr): String = when (e) {
        is NameRef -> e.name
        is IntLit -> e.value
        is FloatLit -> e.value
        is StrLit -> q(e.value)
        ReturnSym -> "$"
        is InstExpr -> "${tag(e.target)}<${e.terms.joinToString(", ") { tag(it) }}>"
        is BinExpr -> "${tag(e.left)} ${e.op} ${tag(e.right)}"
        is UniExpr -> "${e.op}${tag(e.operand)}"
        is CallExpr -> "${tag(e.callee)}(${e.args.joinToString(", ") { tag(it) }})"
        is QuantExpr -> "${e.q} ${e.names.joinToString(",")}. ${tag(e.body)}"
        else -> "?"
    }

    /** 名字改写：避开 JS 关键字/保留字，符号名转合法标识符 */
    /** 名字 → 合法 JS 标识符（保留字加 $ 前缀；符号名转 op_xxxx）。与 checker 共用同一实现。 */
    private fun mangle(n: String): String = jsMangle(n)

    private fun q(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private fun line(s: String) { sb.append("  ".repeat(indent)).append(s).append("\n") }
    private fun blank() { sb.append("\n") }
    private fun comment(s: String) { sb.append("  ".repeat(indent)).append("// ").append(s).append("\n") }
}
