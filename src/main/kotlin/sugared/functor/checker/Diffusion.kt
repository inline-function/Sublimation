package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 作用域帧：变量类型环境 + Γ 命题事实（弥散载体）。
 * 嵌套帧继承外层；块事实回溢父帧（函数体/语句块的弥散），lambda 帧隔离。
 */
class Frame(val parent: Frame?) {
    val vars = LinkedHashMap<String, Type>()
    val facts = ArrayList<Prop>()
    private val muts = LinkedHashSet<String>()
    fun lookupVar(n: String): Type? = vars[n] ?: parent?.lookupVar(n)
    fun declareVar(n: String, t: Type) { vars[n] = t }
    fun declareMut(n: String) { muts.add(n) }
    fun isMut(n: String): Boolean = n in muts || (parent?.isMut(n) ?: false)
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

internal fun Checker.proveOrDeep(gamma: List<Prop>, goal: Prop): Boolean {
    val res = prover.proves(globalFacts + gamma, goal)
    if (prover.deepAborted) d.error("E-DEEP-SATURATION", "", "命题 ${PropLogic.render(goal)} 超出饱和深度 $maxSatDepth")
    return res
}

internal fun Checker.typeLooseEq(a0: Type, b0: Type): Boolean {
    val a = syms.expand(a0); val b = syms.expand(b0)   // 别名展开（决策 30）
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
