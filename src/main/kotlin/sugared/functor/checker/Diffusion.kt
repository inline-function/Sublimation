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
