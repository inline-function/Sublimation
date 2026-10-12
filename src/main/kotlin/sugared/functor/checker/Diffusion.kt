package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 作用域帧：变量类型环境 + Γ 命题事实（弥散载体）。
 * 嵌套帧继承外层；块事实回溢父帧（函数体/语句块的弥散），lambda 帧隔离。
 * W-UNUSED（报错增强）：post（非空）的声明进入"未使用审计"；used 沿父链上溢（子作用域用到即算外层用过）。
 */
class Frame(val parent: Frame?) {
    val vars = LinkedHashMap<String, Type>()
    val facts = ArrayList<Prop>()
    /** v3.0 特性 7：局部定理表（块内局部函数定义=∀ 引入），作用域随帧；∴ 搜索时并入定理空间 */
    val localTheorems = LinkedHashMap<String, FunDecl>()
    private val muts = LinkedHashSet<String>()
    val children = ArrayList<Frame>()
    private val used = HashSet<String>()
    private val audited = LinkedHashMap<String, String>()   // 名字 → 声明点 pos（仅 W-UNUSED 审计目标）
    init { parent?.children?.add(this) }
    fun lookupVar(n: String): Type? = vars[n] ?: parent?.lookupVar(n)
    fun declareVar(n: String, t: Type) { vars[n] = t }
    /** 声明并纳入未使用审计（pos 非空才审计；when 模式绑定/lambda 形参等传 "" 跳过） */
    fun declareVar(n: String, t: Type, pos: String) { vars[n] = t; if (pos.isNotEmpty()) audited[n] = pos }
    fun declareMut(n: String) { muts.add(n) }
    fun isMut(n: String): Boolean = n in muts || (parent?.isMut(n) ?: false)
    fun markUsed(n: String) { used.add(n); parent?.markUsed(n) }
    fun isUsed(n: String): Boolean = n in used
    /** 本帧（含子树）所有审计声明：名字 + 声明点 */
    fun auditedDecls(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(fr: Frame) { fr.audited.forEach { (n, p) -> out.add(n to p) }; fr.children.forEach { walk(it) } }
        walk(this)
        return out
    }
    fun inject(p: Prop) { if (p != PTop && p !in facts) facts.add(p) }
    fun allFacts(): List<Prop> = (parent?.allFacts() ?: emptyList()) + facts
}

/**
 * 返回值指代 `$` 的解法（第四轮扩展）：
 *  ① 块里有**唯一一处带值的显式 `return e`** → `$` := e（Kotlin 风格 `{ return 7 }`）；
 *  ② 否则尾表达式 → `$` := 尾表达式（原语义）；
 *  ③ 都没有 → `null`。
 * 多路 return（if/else 各返回一个）v1 无法归一为单一项，保守取 null 让后件核对显式失败，
 * 不猜测——猜测会产生"看似通过实则错误"的证明。
 */
internal fun Checker.dollarOf(body: Expr): Expr = when (body) {
    is BlockExpr -> {
        val rets = body.stmts.filterIsInstance<ReturnStmt>().mapNotNull { it.expr }
        if (rets.size == 1) rets[0]
        else body.tailExpr ?: NameRef("null")
    }
    else -> body
}

/** v3.0 特性 1：常量原子命题真值判定。∈/in 右端是区间（BinExpr/PAtom("..")），须先分支，不能当 IntLit 判（修复：原来第 60 行先 as? IntLit 使区间分支不可达）。 */
internal fun constAtomValue(p: Prop): Boolean? {
    if (p !is PAtom || p.terms.size != 2) return null
    val a = (p.terms[0] as? IntLit)?.value?.toLongOrNull() ?: return null
    if (p.head == "in" || p.head == "∈") {
        val rg = p.terms[1]
        val lo: Long; val hi: Long
        if (rg is PAtom && rg.head == ".." && rg.terms.size == 2 && rg.terms[0] is IntLit && rg.terms[1] is IntLit) {
            lo = (rg.terms[0] as IntLit).value.toLongOrNull() ?: return null
            hi = (rg.terms[1] as IntLit).value.toLongOrNull() ?: return null
        } else if (rg is BinExpr && rg.op == ".." && rg.left is IntLit && rg.right is IntLit) {
            lo = (rg.left as IntLit).value.toLongOrNull() ?: return null
            hi = (rg.right as IntLit).value.toLongOrNull() ?: return null
        } else return null
        return a >= lo && a <= hi
    }
    val b = (p.terms[1] as? IntLit)?.value?.toLongOrNull() ?: return null
    return when (p.head) {
        "=", "==" -> a == b
        "!=", "≠" -> a != b
        "<" -> a < b
        ">" -> a > b
        "<=", "≤" -> a <= b
        ">=", "≥" -> a >= b
        else -> null
    }
}

/** v3.0 特性 1：把目标里可归约的名字替换为字面量（var m = 5 ⇒ 事实 m = 5），全常量则返回常量版；否则 null。 */
private fun reduceGoalToConstants(gamma: List<Prop>, goal: Prop): Prop? {
    if (goal !is PAtom) return null
    val constMap = LinkedHashMap<String, Long>()
    for (f in gamma) {
        if (f is PAtom && (f.head == "=" || f.head == "==") && f.terms.size == 2) {
            val n1 = (f.terms[0] as? NameRef)?.name
            val v1 = (f.terms[1] as? IntLit)?.value?.toLongOrNull()
            if (n1 != null && v1 != null) constMap[n1] = v1
            val n2 = (f.terms[1] as? NameRef)?.name
            val v2 = (f.terms[0] as? IntLit)?.value?.toLongOrNull()
            if (n2 != null && v2 != null) constMap[n2] = v2
        }
    }
    if (constMap.isEmpty()) return null
    val newTerms = goal.terms.map { t ->
        val n = (t as? NameRef)?.name
        if (n != null && constMap.containsKey(n)) IntLit(constMap[n].toString()) else t
    }
    if (newTerms == goal.terms) return null
    if (newTerms.any { it !is IntLit }) return null
    return PAtom(goal.head, newTerms)
}

/** 根文档：自定义命题展开——`def p<a,b> ↔ <等价>`：目标 PAtom(p, terms) 按定义代换展开（最多 5 层，
 *  展开结果与目标同构即停，视为原子需 Γ 事实）。仅有签名（无 ↔）的 def 是原子谓词，不展开。 */
private fun Checker.expandDefined(goal: Prop): Prop? {
    var cur = goal
    repeat(5) {
        if (cur !is PAtom) return null
        val def = syms.propDefs[cur.head] ?: return null
        if (def.equiv == null) return null
        if (def.params.size != cur.terms.size) return null
        val sub = def.params.zip(cur.terms).toMap()
        cur = substPropTerms(PropLogic.fromExpr(def.equiv), sub)
        if (PropLogic.render(cur) == PropLogic.render(goal)) return null   // 自引用回环：视为原子
        return cur     // 展开一层即可（proveOrDeep 递归自然再展开；render 比较防回环已保证终止）
    }
    return null
}

internal fun Checker.proveOrDeep(gamma: List<Prop>, goal: Prop): Boolean {
    // 根文档：自定义命题按定义展开后继续证明
    expandDefined(goal)?.let { return proveOrDeep(gamma, it) }
    // PNot 目标：内层常量可判 → 直接取反（¬<1=0> 真 ⟺ <1=0> 假）；非常量交给 prover
    if (goal is PNot) {
        val iv = constAtomValue(goal.p)
        if (iv == false) return true
        if (iv == true) return false
    }
    // PImp 目标：前提常量属实 → 只需证后件；前提常量假 → 蕴含恒真
    if (goal is PImp) {
        val av = constAtomValue(goal.a)
        if (av == false) return true
        if (av == true) return proveOrDeep(gamma, goal.b)
    }
    // v3.0 特性 1：常量原子真值直接判定（不动推理规则；构造位伴生命题字面量场景）
    constAtomValue(goal)?.let { return it }
    // v3.0 特性 1：目标含变量但等式事实可归约到常量（var m = 5 事实 → 目标 ≠<m,0> 归约为 ≠<5,0>）
    reduceGoalToConstants(gamma, goal)?.let { rg -> constAtomValue(rg)?.let { return it } }
    val res = prover.proves(globalFacts + gamma, goal)
    if (prover.deepAborted) d.error("E-DEEP-SATURATION", "", "命题 ${PropLogic.render(goal)} 超出饱和深度 $maxSatDepth")
    return res
}

/** 定理判据：**只有 `@theorem` 显式标注的函数才是定理函数**（登记进定理空间，编译器擦除其代码，只供证明位）。非 @theorem 函数即使纯+Null 也不进定理空间。 */
internal fun isTheoremFun(d: FunDecl): Boolean = "theorem" in d.annotations

/** 定理搜索（`∴` 用）：纯函数（无 @impure/@mut）+ 返回 Null = 定理函数，自动登记进 theorems。
 *  搜索深度默认两跳——超限报 E-THEOREM-SEARCH-DEPTH（by solve 未来加深）。
 *  命中判据：定理后件（post）与目标做**结构合一**——theorem 的 theory 参数（如 @p）为元变量，
 *  可绑定到目标对应位置的任意项（实例化后 post ≡ q）。 */
internal fun Checker.searchTheorems(q: Prop, depth: Int, local: Map<String, FunDecl> = emptyMap()): Boolean {
    if (depth > 2) {
        d.warn("E-THEOREM-SEARCH-DEPTH", "", "定理搜索深度超限（默认两跳）：目标 " + PropLogic.render(q))
        return false
    }
    val cross = if (moduleTree == null) emptyList() else
        visiblePaths.mapNotNull { allSymbols[it] }.flatMap { it.theorems.values }
    for (t in (local.values + syms.theorems.values + cross)) {
        // v3.0 特性 7：局部函数（∀ 引入）的形参是绑定变元——并入 theory 参数一起做合一
        val vars = (t.theory.mapNotNull { when (it) { is TypeParam -> it.name; is PropParam -> it.name; else -> null } } + t.params.map { it.name }).toSet()
        for (post in t.posts) {
            val pp = PropLogic.fromExpr(post)
            if (postUnifies(pp, q, vars, LinkedHashMap())) return true
            // 常量归约：后件里的名字若可由全局事实归为字面量，归约后再合一
            reduceGoalToConstants(globalFacts, pp)?.let { rg ->
                if (postUnifies(rg, q, vars, LinkedHashMap())) return true
            }
        }
    }
    return false
}

/** 结构合一：post 与 q 逐层匹配；post 中名字在 vars（定理 theory 参数）内的 NameRef 是元变量，可绑到 q 对应项。 */
private fun postUnifies(post: Prop, q: Prop, vars: Set<String>, env: MutableMap<String, Expr>): Boolean =
    when {
        // 命题元变量：定理后件里无参数的裸名原子（如 @p）可绑定到任意目标命题（排中律等形态）
        post is PAtom && post.terms.isEmpty() && post.head in vars -> true
        post is PAtom && q is PAtom ->
            post.head == q.head && post.terms.size == q.terms.size &&
                post.terms.zip(q.terms).all { (a, b) -> termUnifies(a, b, vars, env) }
        post is PNot && q is PNot -> postUnifies(post.p, q.p, vars, env)
        post is PAnd && q is PAnd -> postUnifies(post.a, q.a, vars, env) && postUnifies(post.b, q.b, vars, env)
        post is POr && q is POr -> postUnifies(post.a, q.a, vars, env) && postUnifies(post.b, q.b, vars, env)
        post is PImp && q is PImp -> postUnifies(post.a, q.a, vars, env) && postUnifies(post.b, q.b, vars, env)
        post == q -> true
        else -> false
    }

private fun termUnifies(a: Expr, b: Expr, vars: Set<String>, env: MutableMap<String, Expr>): Boolean {
    if (a is NameRef && a.name in vars) {
        val prev = env[a.name]
        if (prev == null) { env[a.name] = b; return true }
        return termUnifies(prev, b, vars, env)
    }
    if (a is NameRef && b is NameRef) return a.name == b.name
    if (a is IntLit && b is IntLit) return a.value == b.value
    if (a is FieldExpr && b is FieldExpr)
        return a.name == b.name && termUnifies(a.target, b.target, vars, env)
    return a.toStr() == b.toStr()
}

/** Expr 结构化渲染（termUnifies 兜底用；避免依赖 toString 的引用噪声） */
private fun Expr.toStr(): String = when (this) {
    is NameRef -> name
    is IntLit -> value
    else -> toString()
}

internal fun Checker.typeLooseEq(a0: Type, b0: Type): Boolean {
    val a = syms.expand(a0); val b = syms.expand(b0)   // 别名展开（决策 30）
    // v2.1（对齐 Rust 无限类型语义）：Nothing 是底类型——可被当成任何结构体/枚举/类型类
    if (a.isNothing() || b.isNothing()) return true
    if (a.isSynthetic() || b.isSynthetic()) return true
    if (a.isUnsealed() || b.isUnsealed()) {
        val other = if (a.isUnsealed()) b else a
        return other.isUnsealed() || other.name == "Null"   // §49：非密封 when 值为 Null，仅与 Null 相容
    }
    if (a.isNumeric() && b.isNumeric()) return true   // Nat/Int/Rat 数值提升，须先于 name 相等判定
    if (a.name != b.name || a.args.size != b.args.size) return false
    return a.args.zip(b.args).all { typeLooseEq(it.first, it.second) }
}

internal fun Type.isNumeric(): Boolean = name in setOf("Nat", "Int", "Rat") && args.isEmpty()

/** v2.1：Nothing 底类型（发散/不可达值；与任何类型相容） */
internal fun Type.isNothing(): Boolean = name == "Nothing" && args.isEmpty()
