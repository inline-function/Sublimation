package sugared.functor.annotationproc

import sugared.functor.ast.*

/**
 * 安卓视图注解处理器（android doc/安卓标准库.md）的框架支撑：
 *
 * - [collectViewCtorSites]：遍历 AST，找出视图结构体构造点 X(...) { 子视图 } ，
 *   （CallExpr(StructCtorExpr(X, assigns), [bodyLambda])，见 Parser 尾随 lambda 脱糖），
 *   按源码序为每个视图名分配递增次序，返回 [ViewCtorSite] 清单（供 .subs 脚本生成
 *   recomposion$X$N 单参函数）。
 * - [rewriteViewCtors]：把构造点改写为 X(..., children = listOf(子视图),
 *   recomposion = recomposion$X$N) ——body 被擦除，body 中构造的子视图成为
 *   children 列表成员。
 *
 * 为什么放框架而非脚本：构造点是表达式位的 AST 遍历+重建，.subs 脚本 API
 * （声明级查询/编辑）表达不了；框架提供原语，编排在脚本里完成。
 */

data class ViewCtorSite(
    val funName: String,
    val viewName: String,
    val ordinal: Int,
)

internal fun StructDecl.isView(): Boolean = "view" in annotations

internal fun FunDecl.isComposable(): Boolean = "composable" in annotations

internal fun FunDecl.isAndroidBuiltin(): Boolean = "android" in annotations

internal fun collectViewCtorSites(ast: FileAst, viewNames: Set<String>): List<ViewCtorSite> {
    if (viewNames.isEmpty()) return emptyList()
    val out = ArrayList<ViewCtorSite>()
    val ordinals = LinkedHashMap<String, Int>()
    for (entry in ast.entries) {
        val fn = (entry as? DeclEntry)?.decl as? FunDecl ?: continue
        val body = fn.body ?: continue
        collectSitesInExpr(body, fn.name, viewNames, ordinals, out)
    }
    return out
}

private fun collectSitesInExpr(
    e: Expr, funName: String, viewNames: Set<String>,
    ordinals: MutableMap<String, Int>, out: MutableList<ViewCtorSite>,
) {
    when (e) {
        is CallExpr -> {
            val ctor = e.callee as? StructCtorExpr
            if (ctor != null && ctor.struct in viewNames && e.args.size == 1 && e.args[0] is LambdaExpr) {
                val n = ordinals.getOrDefault(ctor.struct, 0) + 1
                ordinals[ctor.struct] = n
                out += ViewCtorSite(funName, ctor.struct, n)
                collectSitesInExpr(e.args[0], funName, viewNames, ordinals, out)
            } else {
                collectSitesInExpr(e.callee, funName, viewNames, ordinals, out)
                e.args.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
                e.namedArgs.values.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
            }
        }
        is BinExpr -> { collectSitesInExpr(e.left, funName, viewNames, ordinals, out); collectSitesInExpr(e.right, funName, viewNames, ordinals, out) }
        is UniExpr -> collectSitesInExpr(e.operand, funName, viewNames, ordinals, out)
        is FieldExpr -> collectSitesInExpr(e.target, funName, viewNames, ordinals, out)
        is FunRefExpr -> collectSitesInExpr(e.target, funName, viewNames, ordinals, out)
        is SymbolCallExpr -> e.args.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
        is InstExpr -> { collectSitesInExpr(e.target, funName, viewNames, ordinals, out); e.terms.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) } }
        is LambdaExpr -> collectSitesInExpr(e.body, funName, viewNames, ordinals, out)
        is TupleExpr -> e.items.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
        is StructCtorExpr -> {
            // 叶子视图构造（无尾随 body）：同样登记为构造点，分配次序、生成 recomposion$X$N。
            // 注：容器形态 CallExpr(StructCtorExpr, [lambda]) 的 CallExpr 分支已先登记并下钻 lambda，
            // 此处只在单调达时命中（叶子 / 出现在表达式位内的构造）——两分支共同保证源码序。
            if (e.struct in viewNames) {
                val n = ordinals.getOrDefault(e.struct, 0) + 1
                ordinals[e.struct] = n
                out += ViewCtorSite(funName, e.struct, n)
            }
            e.assigns.forEach { collectSitesInExpr(it.second, funName, viewNames, ordinals, out) }
        }
        is ByExpr -> { collectSitesInExpr(e.target, funName, viewNames, ordinals, out); e.props.forEach { collectSitesInExpr(it, funName, viewNames, ordinals, out) } }
        is VarExpr -> collectSitesInExpr(e.value, funName, viewNames, ordinals, out)
        is AnonFunExpr -> e.body?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
        is BlockExpr -> e.stmts.forEach { collectSitesInStmt(it, funName, viewNames, ordinals, out) }
        is IfExpr -> {
            collectSitesInExpr(e.cond, funName, viewNames, ordinals, out)
            collectSitesInExpr(e.thenBlock, funName, viewNames, ordinals, out)
            e.elseBlock?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
        }
        is WhenExpr -> {
            e.subject?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
            e.arms.forEach { arm ->
                arm.guard?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
                arm.cond?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
                collectSitesInExpr(arm.body, funName, viewNames, ordinals, out)
            }
        }
        is TypeTestExpr -> collectSitesInExpr(e.target, funName, viewNames, ordinals, out)
        is IsPatExpr -> collectSitesInExpr(e.target, funName, viewNames, ordinals, out)
        is TaskExpr -> collectSitesInExpr(e.body, funName, viewNames, ordinals, out)
        is FlowExpr -> collectSitesInExpr(e.body, funName, viewNames, ordinals, out)
        is QuantExpr -> collectSitesInExpr(e.body, funName, viewNames, ordinals, out)
        else -> {}
    }
}

private fun collectSitesInStmt(
    s: Stmt, funName: String, viewNames: Set<String>,
    ordinals: MutableMap<String, Int>, out: MutableList<ViewCtorSite>,
) {
    when (s) {
        is VarStmt -> collectSitesInExpr(s.value, funName, viewNames, ordinals, out)
        is AssignStmt -> { collectSitesInExpr(s.target, funName, viewNames, ordinals, out); collectSitesInExpr(s.value, funName, viewNames, ordinals, out) }
        is ExprStmt -> collectSitesInExpr(s.expr, funName, viewNames, ordinals, out)
        is UncheckedStmt -> collectSitesInStmt(s.inner, funName, viewNames, ordinals, out)
        is FunStmt -> {}
        is ReturnStmt -> s.expr?.let { collectSitesInExpr(it, funName, viewNames, ordinals, out) }
        else -> {}
    }
}

/** 改写所有视图构造点：X(args) { 子视图 } → X(args, children = listOf(子视图), recomposion = recomposion$X$N)。
 *  body 被擦除；body 中构造的子视图成为 children 列表成员（android doc §3.2）。
 *  次序分配与 collectViewCtorSites 同一算法，保证脚本生成的 recomposion$X$N 名字一致；
 *  已改写过的构造点（无尾随 lambda 体）不匹配 → 幂等，多轮引擎自然收敛。
 *  无构造点时原样返回（引用不变，引擎据此判定本轮无变化停机）。
 */
internal fun rewriteViewCtors(ast: FileAst, viewNames: Set<String>): FileAst {
    if (viewNames.isEmpty()) return ast
    val ordinals = LinkedHashMap<String, Int>()
    var changed = false
    val out = ArrayList<Entry>(ast.entries.size)
    for (entry in ast.entries) {
        val fn = (entry as? DeclEntry)?.decl as? FunDecl
        if (fn == null || fn.body == null) { out += entry; continue }
        val pair = rewriteExpr(fn.body!!, viewNames, ordinals)
        if (pair.second) { changed = true; out += DeclEntry(fn.copy(body = pair.first)) } else out += entry
    }
    return if (changed) FileAst(out) else ast
}

private fun rewriteExpr(
    e: Expr, viewNames: Set<String>, ordinals: MutableMap<String, Int>,
): Pair<Expr, Boolean> {
    when (e) {
        is CallExpr -> {
            val ctor = e.callee as? StructCtorExpr
            if (ctor != null && ctor.struct in viewNames && e.args.size == 1 && e.args[0] is LambdaExpr) {
                val lam = e.args[0] as LambdaExpr
                val n = ordinals.getOrDefault(ctor.struct, 0) + 1
                ordinals[ctor.struct] = n
                // 子视图先递归改写（叶子补 children/recomposion）再审入 children 列表。
                var kch = false
                val kids = childViewExprs(lam.body).map { applyTo(it, viewNames, ordinals) { kch = true } }
                val newAssigns = ArrayList(ctor.assigns)
                if (kids.isNotEmpty()) newAssigns += CHILDREN to makeListOf(kids)
                newAssigns += RECOMPOSION to NameRef(recomposionName(ctor.struct, n))
                newAssigns += ORD to IntLit(n.toString())
                return StructCtorExpr(ctor.struct, newAssigns, ctor.pos) to true
            }
            var ch = false
            val callee = applyTo(e.callee, viewNames, ordinals) { ch = true }
            val args = e.args.map { applyTo(it, viewNames, ordinals) { ch = true } }
            val na = e.namedArgs.mapValues { applyTo(it.value, viewNames, ordinals) { ch = true } }
            return (if (ch) CallExpr(callee, args, na, e.pos) else e) to ch
        }
        is BinExpr -> {
            var ch = false
            val l = applyTo(e.left, viewNames, ordinals) { ch = true }
            val r = applyTo(e.right, viewNames, ordinals) { ch = true }
            return (if (ch) BinExpr(e.op, l, r, e.pos) else e) to ch
        }
        is UniExpr -> { var ch = false; val o = applyTo(e.operand, viewNames, ordinals) { ch = true }; return (if (ch) UniExpr(e.op, o, e.pos) else e) to ch }
        is FieldExpr -> { var ch = false; val t = applyTo(e.target, viewNames, ordinals) { ch = true }; return (if (ch) FieldExpr(t, e.name, e.pos) else e) to ch }
        is LambdaExpr -> { var ch = false; val b = applyTo(e.body, viewNames, ordinals) { ch = true }; return (if (ch) LambdaExpr(e.params, b, e.pos) else e) to ch }
        is TupleExpr -> {
            var ch = false
            val items = e.items.map { applyTo(it, viewNames, ordinals) { ch = true } }
            return (if (ch) TupleExpr(items, e.pos) else e) to ch
        }
        is StructCtorExpr -> {
            var ch = false
            val assigns = e.assigns.map { p -> p.first to applyTo(p.second, viewNames, ordinals) { ch = true } }
            // 叶子视图构造（无 body）：补上 children = Nil 与 recomposion = recomposion$X$N。
            // 已补全的构造点（children/recomposion 已在 assigns 中）不再匹配 → 幂等。
            if (e.struct in viewNames && assigns.none { it.first == CHILDREN || it.first == RECOMPOSION || it.first == ORD }) {
                val n = ordinals.getOrDefault(e.struct, 0) + 1
                ordinals[e.struct] = n
                val na = ArrayList(assigns)
                na += CHILDREN to CallExpr(NameRef("Nil"), listOf<Expr>(), emptyMap())   // Nil() 调用——NameRef 无法解析
                na += RECOMPOSION to NameRef(recomposionName(e.struct, n))
                na += ORD to IntLit(n.toString())
                return StructCtorExpr(e.struct, na, e.pos) to true
            }
            return (if (ch) StructCtorExpr(e.struct, assigns, e.pos) else e) to ch
        }
        is BlockExpr -> {
            var ch = false
            val stmts = e.stmts.map { rewriteStmt(it, viewNames, ordinals) { ch = true } }
            return (if (ch) BlockExpr(stmts) else e) to ch
        }
        is IfExpr -> {
            var ch = false
            val c = applyTo(e.cond, viewNames, ordinals) { ch = true }
            val t = applyToBlock(e.thenBlock, viewNames, ordinals) { ch = true }
            val el = e.elseBlock?.let { applyToBlock(it, viewNames, ordinals) { ch = true } }
            return (if (ch) IfExpr(c, t, el, e.pos) else e) to ch
        }
        is VarExpr -> { var ch = false; val v = applyTo(e.value, viewNames, ordinals) { ch = true }; return (if (ch) VarExpr(e.name, e.annotations, e.type, v, e.pos) else e) to ch }
        else -> return e to false
    }
}

private fun rewriteStmt(
    s: Stmt, viewNames: Set<String>, ordinals: MutableMap<String, Int>, mark: () -> Unit,
): Stmt = when (s) {
    is VarStmt -> { val p = rewriteExpr(s.value, viewNames, ordinals); if (p.second) { mark(); s.copy(value = p.first) } else s }
    is AssignStmt -> { val p1 = rewriteExpr(s.target, viewNames, ordinals); val p2 = rewriteExpr(s.value, viewNames, ordinals); if (p1.second || p2.second) { mark(); AssignStmt(p1.first, p2.first) } else s }
    is ExprStmt -> { val p = rewriteExpr(s.expr, viewNames, ordinals); if (p.second) { mark(); ExprStmt(p.first) } else s }
    is UncheckedStmt -> { val ns = rewriteStmt(s.inner, viewNames, ordinals, mark); ns }
    is ReturnStmt -> s.expr?.let { ex -> val p = rewriteExpr(ex, viewNames, ordinals); if (p.second) { mark(); ReturnStmt(p.first, s.pos, s.label) } else s } ?: s
    else -> s
}


private inline fun applyToBlock(
    b: BlockExpr, viewNames: Set<String>, ordinals: MutableMap<String, Int>, mark: () -> Unit,
): BlockExpr {
    var ch = false
    val stmts = b.stmts.map { rewriteStmt(it, viewNames, ordinals) { ch = true } }
    if (ch) mark()
    return if (ch) BlockExpr(stmts) else b
}
private inline fun applyTo(
    e: Expr, viewNames: Set<String>, ordinals: MutableMap<String, Int>, mark: () -> Unit,
): Expr {
    val p = rewriteExpr(e, viewNames, ordinals)
    if (p.second) mark()
    return p.first
}

/** body 体内构造的子视图：body 只写视图结构体的构造（android doc §3.2）。 */
private fun childViewExprs(body: Expr): List<Expr> {
    val out = ArrayList<Expr>()
    when (body) {
        is BlockExpr -> body.stmts.forEach { s ->
            val ex = (s as? ExprStmt)?.expr
            if (ex != null) out += ex
        }
        else -> out += body
    }
    return out
}

private fun makeListOf(items: List<Expr>): Expr = CallExpr(NameRef("listOf"), items)

internal const val CHILDREN = "children"
internal const val RECOMPOSION = "recomposion"
internal const val ORD = "__ord"


/** 视图结构体字段注入（android doc/安卓标准库.md §视图结构体）：
 *  幂等地给结构体重组字段——先删去既有的 children/recomposion，
 *  再追加编译期属性 children: List[Any] 与 recomposion: (Any)=>Null。
 *
 * @return 变换后的声明；若已是目标形态（无需变动）返回原声明（引用不变 → 引擎判定无变化停机）
*/
internal fun injectViewFields(decl: StructDecl): StructDecl {
    val kept = decl.fields.filterNot { it.name == CHILDREN || it.name == RECOMPOSION || it.name == ORD }
    // children 字段用 Any（自动类型类）而非 List[Any]：List 不变（invariant），异构子视图
    // listOf(Button(), Text()) 会推成联合 List 与 List[Any] 不符；Any 接受任意具体类型
    // （List[Button] 本身即一具体类型），JS 侧 children 仍是数组。
    val childrenT = NamedType("Any", emptyList())
    val recomT = FunType(listOf(NamedType("Any", emptyList())), NamedType("Null", emptyList()))
    // 视图结构体全部字段标 @mut（android doc/安卓标准库.md §视图结构体的可变性函数：
    //  实例不可变语义下字段可被可变性函数修改）——不然 v.modifier = x 会被 E-NOT-MUT 拦截。
    // 注入字段带默认值：构造可省略（E-FIELD-MISSING 兜底）。
    // children 默认空串占位（Any 自动类型类：Str 是其实例；JS 侧被子视图列表覆盖）、
    // recomposion 默认空操作 lambda。
    val emptyChildren = StrLit("", "0:0")
    val noOpRecompose = LambdaExpr(listOf("_"), BlockExpr(emptyList()), "0:0")
    val mutKept = kept.map { if ("mut" in it.annotations) it else it.copy(annotations = it.annotations + "mut") }
    val newFields = mutKept +
        Param(CHILDREN, childrenT, annotations = listOf("mut"), default = emptyChildren) +
        Param(RECOMPOSION, recomT, annotations = listOf("mut"), default = noOpRecompose) +
        // __ord: Nat = 0——构造点编号（与 recomposion$X$N 的 N 同源），序列化进 JSON 后
        // 供 KT 端 androidRecompose 按 (tag, __ord) 精确定位节点（源码序≠树 DFS 序，见 doc）。
        Param(ORD, NamedType("Nat", emptyList()), annotations = listOf("mut"), default = IntLit("0"))
    return if (newFields == decl.fields) decl else decl.copy(fields = newFields)
}
internal fun recomposionName(viewName: String, ordinal: Int): String =
    "recomposion" + 36.toChar() + viewName + 36.toChar() + ordinal
