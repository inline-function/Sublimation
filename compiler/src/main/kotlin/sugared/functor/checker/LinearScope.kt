package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 线性作用域（boring doc《线性作用域 · 形式化版》第十二卷）：`%{ ... }` 块内 @mut 实体的版本化事实注入。
 *
 * 本文件实现第一波（核心机制）——版本映射 Ω、版本化改写 versionize、预扫描逃逸判定、
 * LV-SPILL 版本名判定。规则 LV-DECL / LV-ASSIGN 的注入钩在 Checker.checkStmt 内
 * （linearFamily 非空时生效），分支的保守失效见 checkLinearBlock。
 *
 * 版本名按 §3.3 采用「名字 + 序号」的字符串拼接 `name#k`：`#` 不是合法标识符字符（第一卷词法），
 * 用户无法书写、词法不会产出带 # 的 NameRef，故与用户名字不冲突（§3.3 蛇尾条款的实现选择）。
 * 版本名只在命题层流转，JsCodeGen 把 LinearBlockExpr 视作普通 BlockExpr（§10.1 逐字节擦除）。
 */

/** 线性域族状态（§4.2 LinearFrame 的流敏感部分）：版本计数器在整个域族内共享。 */
internal class LinearFamily {
    /** 域族共享版本计数器（§11.3：序号全局唯一，便于诊断定位程序点） */
    internal var versionCtr: Int = 0
    /** 实体 → 当前版本号（Ω：Lin → ℕ）。域族内所有线性帧共享同一张表——嵌套域版本连续递增（§5.7） */
    internal val omega = LinkedHashMap<String, Int>()
    /** 已失效的实体集合（§8.4 LV-INVALIDATE：不纯调用后该实体的版本化事实不再可用） */
    internal val invalidated = LinkedHashSet<String>()
    /** 预扫描确认为线性实体的名字集合（定义 2.3：域内声明/赋值、@mut、非 @ref、未被 lambda 修改性捕获） */
    internal val linVars = LinkedHashSet<String>()

    internal fun fresh(): Int {
        val k = versionCtr
        versionCtr = k + 1
        return k
    }

    /** 版本名渲染（§7.4：诊断输出时用 `x#k`；对用户消息应还原为 x） */
    internal fun vname(name: String, k: Int): String = name + "#" + k
}

/** 名字→版本的解析（§4.3 LV-RESOLVE-LIN / LV-RESOLVE-PLAIN）：非线性名字原样返回。 */
internal fun LinearFamily?.resolveName(name: String): String {
    if (this == null) return name
    val k = omega[name] ?: return name
    return vname(name, k)
}

/** 版本化改写（§4.4 定义 4.5）：对表达式树自底向上应用 resolve——纯语法替换，不改逻辑结构。
 *  仅替换 NameRef 与 FieldExpr 的根变量（LV-RESOLVE-FIELD：版本索引附着在根变量上）。 */
internal fun LinearFamily?.versionize(e: Expr): Expr = when (e) {
    is NameRef -> NameRef(resolveName(e.name), e.pos)
    is FieldExpr -> FieldExpr(versionize(e.target), e.name, e.pos)
    is BinExpr -> BinExpr(e.op, versionize(e.left), versionize(e.right), e.pos)
    is UniExpr -> UniExpr(e.op, versionize(e.operand), e.pos)
    is CallExpr -> CallExpr(versionize(e.callee), e.args.map { versionize(it) }, e.namedArgs.mapValues { versionize(it.value) }, e.pos)
    is InstExpr -> InstExpr(versionize(e.target), e.terms.map { versionize(it) }, e.pos)
    is TupleExpr -> TupleExpr(e.items.map { versionize(it) }, e.pos)
    is StructCtorExpr -> StructCtorExpr(e.struct, e.assigns.map { it.first to versionize(it.second) }, e.pos)
    is IfExpr -> IfExpr(versionize(e.cond), versionize(e.thenBlock) as BlockExpr,
        (e.elseBlock as? BlockExpr)?.let { versionize(it) as BlockExpr }, e.pos)
    is BlockExpr -> BlockExpr(e.stmts.map { versionizeStmt(it) }, e.pos)
    else -> e   // 字面量/量词体等不承载线性名字的节点原样返回
}

/** 语句的版本化改写（只处理承载表达式语句与赋值；其余语句不参与义务改写） */
private fun LinearFamily?.versionizeStmt(s: Stmt): Stmt = when (s) {
    is ExprStmt -> ExprStmt(versionize(s.expr), s.pos)
    is AssignStmt -> AssignStmt(s.target, versionize(s.value), s.pos)
    else -> s
}

// ---------- 预扫描（§11.1 步骤 2、§8） ----------

/** 块内全部 var/赋值候选名字（@mut 且非 @ref），供逃逸判定。 */
internal fun collectLinearCandidates(stmts: List<Stmt>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    fun walk(body: List<Stmt>) {
        for (s in body) when (s) {
            is VarStmt -> if ("mut" in s.annotations) out.add(s.name)
            is VarExpr -> if ("mut" in s.annotations) out.add(s.name)
            is AssignStmt -> (s.target as? NameRef)?.name?.let { out.add(it) }
            is UncheckedStmt -> walk(listOf(s.inner))
            is ExistsElimStmt -> walk(s.body)
            is ExprStmt -> walkExprCandidates(s.expr, out)
            else -> {}
        }
    }
    walk(stmts)
    return out
}

private fun walkExprCandidates(e: Expr, out: LinkedHashSet<String>) {
    when (e) {
        is VarExpr -> if ("mut" in e.annotations) out.add(e.name)
        is BlockExpr -> e.stmts.forEach { if (it is ExprStmt) walkExprCandidates(it.expr, out) }
        is LinearBlockExpr -> e.block.stmts.forEach { if (it is ExprStmt) walkExprCandidates(it.expr, out) }
        is IfExpr -> { walkExprCandidates(e.thenBlock, out); e.elseBlock?.let { walkExprCandidates(it, out) } }
        is CallExpr -> { walkExprCandidates(e.callee, out); e.args.forEach { walkExprCandidates(it, out) } }
        is BinExpr -> { walkExprCandidates(e.left, out); walkExprCandidates(e.right, out) }
        else -> {}
    }
}

/** 域内是否存在对候选名字的**赋值性 lambda 捕获**（定义 8.1 ①：lambda 捕获 x 且体内对 x 赋值）。
 *  遍历所有 lambda 体的 AssignStmt 目标，与候选集合交。保守判定（§8.3）。 */
internal fun lambdaAssignCaptured(stmts: List<Stmt>, candidates: Set<String>): LinkedHashSet<String> {
    val out = LinkedHashSet<String>()
    fun walk(body: List<Stmt>) {
        for (s in body) when (s) {
            is ExprStmt -> walkExprLambda(s.expr, candidates, out)
            is UncheckedStmt -> walk(listOf(s.inner))
            is ExistsElimStmt -> walk(s.body)
            else -> {}
        }
    }
    walk(stmts)
    return out
}

private fun walkExprLambda(e: Expr, candidates: Set<String>, out: LinkedHashSet<String>) {
    when (e) {
        is LambdaExpr -> walkLambdaBody(e.body, candidates, out)
        is AnonFunExpr -> e.body?.let { walkLambdaBody(it, candidates, out) }
        is CallExpr -> { walkExprLambda(e.callee, candidates, out); e.args.forEach { walkExprLambda(it, candidates, out) } }
        is BinExpr -> { walkExprLambda(e.left, candidates, out); walkExprLambda(e.right, candidates, out) }
        is UniExpr -> walkExprLambda(e.operand, candidates, out)
        is FieldExpr -> walkExprLambda(e.target, candidates, out)
        is IfExpr -> { walkExprLambda(e.cond, candidates, out); walkExprLambda(e.thenBlock, candidates, out); e.elseBlock?.let { walkExprLambda(it, candidates, out) } }
        is BlockExpr -> e.stmts.forEach { if (it is ExprStmt) walkExprLambda(it.expr, candidates, out) }
        is LinearBlockExpr -> e.block.stmts.forEach { if (it is ExprStmt) walkExprLambda(it.expr, candidates, out) }
        else -> {}
    }
}

private fun walkLambdaBody(body: Expr, candidates: Set<String>, out: LinkedHashSet<String>) {
    when (body) {
        is BlockExpr -> body.stmts.forEach {
            when (it) {
                is AssignStmt -> { val n = (it.target as? NameRef)?.name; if (n != null && n in candidates) out.add(n) }
                is ExprStmt -> walkExprLambda(it.expr, candidates, out)
                else -> {}
            }
        }
        else -> walkExprLambda(body, candidates, out)
    }
}

/** 预扫描：域内的 @ref 声明（§8.3 LV-NO-REF 判据）。 */
internal fun refDeclarations(stmts: List<Stmt>): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    fun walk(body: List<Stmt>) {
        for (s in body) when (s) {
            is VarStmt -> if ("ref" in s.annotations) out.add(s.name to s.pos)
            is VarExpr -> if ("ref" in s.annotations) out.add(s.name to s.pos)
            is UncheckedStmt -> walk(listOf(s.inner))
            is ExprStmt -> walkExprRef(s.expr, out)
            else -> {}
        }
    }
    walk(stmts)
    return out
}

private fun walkExprRef(e: Expr, out: ArrayList<Pair<String, String>>) {
    when (e) {
        is VarExpr -> if ("ref" in e.annotations) out.add(e.name to e.pos)
        is BlockExpr -> e.stmts.forEach { if (it is ExprStmt) walkExprRef(it.expr, out) }
        is LinearBlockExpr -> e.block.stmts.forEach { if (it is ExprStmt) walkExprRef(it.expr, out) }
        is IfExpr -> { walkExprRef(e.thenBlock, out); e.elseBlock?.let { walkExprRef(it, out) } }
        else -> {}
    }
}

// ---------- LV-SPILL 判据（§5.7） ----------

/** 渲染程序里是否出现版本名（`x#k`）——LV-SPILL 判据：只回溢不含版本名的事实。
 *  用户标识符不能含 `#`（第一卷词法），版本名一律由 vname 产出，故命中即版本化事实。 */
internal fun propHasVersionName(p: Prop): Boolean {
    var hit = false
    fun walk(q: Prop) {
        when (q) {
            is PAtom -> q.terms.forEach { if (exprHasVersionName(it)) hit = true }
            is PNot -> walk(q.p)
            is PAnd -> { walk(q.a); walk(q.b) }
            is POr -> { walk(q.a); walk(q.b) }
            is PImp -> { walk(q.a); walk(q.b) }
            is PIff -> { walk(q.a); walk(q.b) }
            is PForall -> walk(q.body)
            is PExists -> walk(q.body)
            is PExists1 -> walk(q.body)
            else -> {}
        }
    }
    walk(p)
    return hit
}

internal fun exprHasVersionName(e: Expr): Boolean = when (e) {
    is NameRef -> "#" in e.name
    is FieldExpr -> exprHasVersionName(e.target)
    is BinExpr -> exprHasVersionName(e.left) || exprHasVersionName(e.right)
    is UniExpr -> exprHasVersionName(e.operand)
    is CallExpr -> exprHasVersionName(e.callee) || e.args.any { exprHasVersionName(it) }
    is InstExpr -> exprHasVersionName(e.target) || e.terms.any { exprHasVersionName(it) }
    is TupleExpr -> e.items.any { exprHasVersionName(it) }
    is StructCtorExpr -> e.assigns.any { exprHasVersionName(it.second) }
    is IfExpr -> exprHasVersionName(e.cond) || exprHasVersionName(e.thenBlock) || (e.elseBlock?.let { exprHasVersionName(it) } ?: false)
    is BlockExpr -> e.stmts.any { (it as? ExprStmt)?.let { x -> exprHasVersionName(x.expr) } ?: false }
    is LinearBlockExpr -> e.block.stmts.any { (it as? ExprStmt)?.let { x -> exprHasVersionName(x.expr) } ?: false }
    else -> false
}