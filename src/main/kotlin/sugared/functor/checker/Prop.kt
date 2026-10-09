package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 命题的规范化表示。`unchecked.axiom[∀n. valid<n>]`、上文、下文、两参解糖 `a ○ b`(=`○<a,b>`)
 * 统一归约为 Prop 树。PAtom 承载一切原子命题：0 元 (`p`)、参数化 (`valid<7>`)、
 * 比较/等式 (`$ == 7` → PAtom("==",[$,7]))。
 */
sealed interface Prop

data object PTop : Prop
data object PBot : Prop
data class PNot(val p: Prop) : Prop
data class PAnd(val a: Prop, val b: Prop) : Prop
data class POr(val a: Prop, val b: Prop) : Prop
data class PImp(val a: Prop, val b: Prop) : Prop
data class PIff(val a: Prop, val b: Prop) : Prop
data class PForall(val names: List<String>, val body: Prop) : Prop
data class PExists(val names: List<String>, val body: Prop) : Prop
data class PExists1(val names: List<String>, val body: Prop) : Prop
data class PAtom(val head: String, val terms: List<Expr>) : Prop

object PropLogic {
    val cmpHeads = setOf("=", "==", "!=", "<", ">", "<=", ">=")
    /** 自动供给候选时排除的结构性原子（等式/类型事实不算弥散命题候选） */
    val structuralHeads = cmpHeads + setOf("$", ":", "__call__", "__inst__", "__t__")

    fun fromExpr(e: Expr): Prop = when (e) {
        is TopExpr -> PTop
        is BotExpr -> PBot
        is ReturnSym -> PAtom("$", emptyList())
        // $n 参数引用（决策 65，T6）：与裸名 `$1` 同构，便于在 Γ 中与形参等式匹配
        is ParamRefExpr -> PAtom("\$${e.index}", emptyList())
        is UniExpr -> PNot(fromExpr(e.operand))
        is BinExpr -> when (e.op) {
            "->", "→" -> PImp(fromExpr(e.left), fromExpr(e.right))
            "<->", "↔" -> PIff(fromExpr(e.left), fromExpr(e.right))
            "&", "∧" -> PAnd(fromExpr(e.left), fromExpr(e.right))
            "|", "∨" -> POr(fromExpr(e.left), fromExpr(e.right))
            else -> PAtom(e.op, listOf(e.left, e.right))  // 比较、两参解糖 `q ○ p`=`○<q,p>`
        }
        is NameRef -> PAtom(e.name, emptyList())
        is InstExpr -> {
            val t = e.target
            if (t is NameRef) PAtom(t.name, e.terms) else PAtom("__inst__", listOf(e))
        }
        is SymbolCallExpr -> PAtom(e.op, e.args)
        is CallExpr -> {
            val c = e.callee
            if (c is NameRef) PAtom(c.name, e.args) else PAtom("__call__", listOf(e))
        }
        is QuantExpr -> when (e.q) {
            "∀" -> PForall(e.names, fromExpr(e.body))
            "∃" -> PExists(e.names, fromExpr(e.body))
            else -> PExists1(e.names, fromExpr(e.body))
        }
        else -> PAtom("__t__", listOf(e))
    }

    /** 合取分解 + 双否消去：事实库归一化（定理表的可判定子集） */
    fun normalizeFacts(ps: List<Prop>): List<Prop> = ps.flatMap { flattenAnd(it) }.map { stripNN(it) }

    private fun flattenAnd(p: Prop): List<Prop> = when (p) {
        is PAnd -> flattenAnd(p.a) + flattenAnd(p.b)
        else -> listOf(p)
    }

    private fun stripNN(p: Prop): Prop = when (p) {
        is PNot -> { val inner = stripNN(p.p); if (inner is PNot) inner.p else p }
        else -> p
    }

    fun render(p: Prop): String = when (p) {
        PTop -> "⊤"; PBot -> "⊥"
        is PNot -> "¬${render(p.p)}"
        is PAnd -> "(${render(p.a)} & ${render(p.b)})"
        is POr -> "(${render(p.a)} | ${render(p.b)})"
        is PImp -> "(${render(p.a)} -> ${render(p.b)})"
        is PIff -> "(${render(p.a)} <-> ${render(p.b)})"
        is PForall -> "∀${p.names.joinToString(",")}. ${render(p.body)}"
        is PExists -> "∃${p.names.joinToString(",")}. ${render(p.body)}"
        is PExists1 -> "∃!${p.names.joinToString(",")}. ${render(p.body)}"
        is PAtom -> if (p.terms.isEmpty()) p.head else "${p.head}<${p.terms.joinToString(", ") { termTag(it) }}>"
    }

    fun termTag(e: Expr): String = when (e) {
        is IntLit -> e.value; is StrLit -> "\"${e.value}\""; is NameRef -> e.name
        ReturnSym -> "$"; is ParamRefExpr -> "\$${e.index}"; is BinExpr -> "(${e.op} ${termTag(e.left)} ${termTag(e.right)})"
        is CallExpr -> "${termTag(e.callee)}(...)"; is UniExpr -> "(${e.op} ${termTag(e.operand)})"
        is InstExpr -> "${termTag(e.target)}<...>"; else -> "?"
    }

    /** 结构等（语法同一性：== 自反律与原子匹配的基础） */
    fun termEq(a: Expr, b: Expr): Boolean = when {
        a is IntLit && b is IntLit -> a.value == b.value
        a is StrLit && b is StrLit -> a.value == b.value
        a is NameRef && b is NameRef -> a.name == b.name
        a is ReturnSym && b is ReturnSym -> true
        a is ParamRefExpr && b is ParamRefExpr -> a.index == b.index   // $n 参数引用（决策 65，T6）
        a is TopExpr && b is TopExpr -> true
        a is BotExpr && b is BotExpr -> true
        a is BinExpr && b is BinExpr -> a.op == b.op && termEq(a.left, b.left) && termEq(a.right, b.right)
        a is UniExpr && b is UniExpr -> a.op == b.op && termEq(a.operand, b.operand)
        a is CallExpr && b is CallExpr -> a.args.size == b.args.size && termEq(a.callee, b.callee) &&
            a.args.zip(b.args).all { termEq(it.first, it.second) }
        a is InstExpr && b is InstExpr -> a.terms.size == b.terms.size &&
            termEq(a.target, b.target) && a.terms.zip(b.terms).all { termEq(it.first, it.second) }
        a is FieldExpr && b is FieldExpr -> a.name == b.name && termEq(a.target, b.target)
        a is SymbolCallExpr && b is SymbolCallExpr -> a.op == b.op &&
            a.args.size == b.args.size && a.args.zip(b.args).all { termEq(it.first, it.second) }
        else -> false
    }

    /**
     * 模式匹配（合一的单向特化）：`pattern` 中属于 `wilds`（量词约束名）的 NameRef 是通配，
     * 首次出现绑定、之后要求一致。
     */
    fun matchTerm(pattern: Expr, actual: Expr, wilds: Set<String>, binds: MutableMap<String, Expr>): Boolean =
        when {
            pattern is NameRef && pattern.name in wilds -> {
                val prev = binds[pattern.name]
                if (prev == null) { binds[pattern.name] = actual; true } else termEq(prev, actual)
            }
            pattern is BinExpr && actual is BinExpr -> pattern.op == actual.op &&
                matchTerm(pattern.left, actual.left, wilds, binds) &&
                matchTerm(pattern.right, actual.right, wilds, binds)
            pattern is CallExpr && actual is CallExpr -> pattern.args.size == actual.args.size &&
                matchTerm(pattern.callee, actual.callee, wilds, binds) &&
                pattern.args.zip(actual.args).all { matchTerm(it.first, it.second, wilds, binds) }
            pattern is InstExpr && actual is InstExpr -> pattern.terms.size == actual.terms.size &&
                matchTerm(pattern.target, actual.target, wilds, binds) &&
                pattern.terms.zip(actual.terms).all { matchTerm(it.first, it.second, wilds, binds) }
            pattern is FieldExpr && actual is FieldExpr ->
                pattern.name == actual.name && matchTerm(pattern.target, actual.target, wilds, binds)
            pattern is UniExpr && actual is UniExpr ->
                pattern.op == actual.op && matchTerm(pattern.operand, actual.operand, wilds, binds)
            pattern is SymbolCallExpr && actual is SymbolCallExpr -> pattern.op == actual.op &&
                pattern.args.size == actual.args.size &&
                pattern.args.zip(actual.args).all { matchTerm(it.first, it.second, wilds, binds) }
            else -> termEq(pattern, actual)
        }

    fun matchProp(pattern: Prop, goal: Prop, wilds: Set<String>, binds: MutableMap<String, Expr>): Boolean =
        when {
            pattern is PAtom && goal is PAtom -> pattern.head == goal.head &&
                pattern.terms.size == goal.terms.size &&
                pattern.terms.zip(goal.terms).all { matchTerm(it.first, it.second, wilds, binds) }
            pattern is PNot && goal is PNot -> matchProp(pattern.p, goal.p, wilds, binds)
            pattern is PAnd && goal is PAnd -> matchProp(pattern.a, goal.a, wilds, binds) &&
                matchProp(pattern.b, goal.b, wilds, binds)
            pattern is POr && goal is POr -> matchProp(pattern.a, goal.a, wilds, binds) &&
                matchProp(pattern.b, goal.b, wilds, binds)
            pattern is PImp && goal is PImp -> matchProp(pattern.a, goal.a, wilds, binds) &&
                matchProp(pattern.b, goal.b, wilds, binds)
            pattern is PIff && goal is PIff -> matchProp(pattern.a, goal.a, wilds, binds) &&
                matchProp(pattern.b, goal.b, wilds, binds)
            else -> false
        }

    fun substExpr(e: Expr, wilds: Map<String, Expr>): Expr = when (e) {
        is NameRef -> wilds[e.name] ?: e
        is BinExpr -> e.copy(left = substExpr(e.left, wilds), right = substExpr(e.right, wilds))
        is UniExpr -> e.copy(operand = substExpr(e.operand, wilds))
        is CallExpr -> e.copy(callee = substExpr(e.callee, wilds), args = e.args.map { substExpr(it, wilds) })
        is InstExpr -> e.copy(target = substExpr(e.target, wilds), terms = e.terms.map { substExpr(it, wilds) })
        is FieldExpr -> e.copy(target = substExpr(e.target, wilds))
        is SymbolCallExpr -> e.copy(args = e.args.map { substExpr(it, wilds) })
        else -> e
    }

    fun substProp(p: Prop, wilds: Map<String, Expr>): Prop = when (p) {
        is PAtom -> PAtom(p.head, p.terms.map { substExpr(it, wilds) })
        is PNot -> PNot(substProp(p.p, wilds))
        is PAnd -> PAnd(substProp(p.a, wilds), substProp(p.b, wilds))
        is POr -> POr(substProp(p.a, wilds), substProp(p.b, wilds))
        is PImp -> PImp(substProp(p.a, wilds), substProp(p.b, wilds))
        is PIff -> PIff(substProp(p.a, wilds), substProp(p.b, wilds))
        is PForall -> PForall(p.names, substProp(p.body, wilds.filterKeys { it !in p.names }))
        is PExists -> PExists(p.names, substProp(p.body, wilds.filterKeys { it !in p.names }))
        is PExists1 -> PExists1(p.names, substProp(p.body, wilds.filterKeys { it !in p.names }))
        else -> p
    }

    /** 命题参数替换：裸原子 `@q` → 实际命题 */
    fun substPropParam(p: Prop, name: String, q: Prop): Prop = when (p) {
        is PAtom -> if (p.terms.isEmpty() && p.head == name) q else p
        is PNot -> PNot(substPropParam(p.p, name, q))
        is PAnd -> PAnd(substPropParam(p.a, name, q), substPropParam(p.b, name, q))
        is POr -> POr(substPropParam(p.a, name, q), substPropParam(p.b, name, q))
        is PImp -> PImp(substPropParam(p.a, name, q), substPropParam(p.b, name, q))
        is PIff -> PIff(substPropParam(p.a, name, q), substPropParam(p.b, name, q))
        is PForall -> if (name in p.names) p else PForall(p.names, substPropParam(p.body, name, q))
        is PExists -> if (name in p.names) p else PExists(p.names, substPropParam(p.body, name, q))
        is PExists1 -> if (name in p.names) p else PExists1(p.names, substPropParam(p.body, name, q))
        else -> p
    }

    /** `$` 替换：体内 ≡ 末表达式；调用注入 ≡ 调用表达式本身 */
    /** `$` 替换：体内 ≡ 末表达式；调用注入 ≡ 调用表达式本身（$n 已在声明期改写为参数名，决策 65） */
    fun substDollar(p: Prop, ret: Expr): Prop = when (p) {
        is PAtom -> PAtom(p.head, p.terms.map { substExprDollar(it, ret) })
        is PNot -> PNot(substDollar(p.p, ret))
        is PAnd -> PAnd(substDollar(p.a, ret), substDollar(p.b, ret))
        is POr -> POr(substDollar(p.a, ret), substDollar(p.b, ret))
        is PImp -> PImp(substDollar(p.a, ret), substDollar(p.b, ret))
        is PIff -> PIff(substDollar(p.a, ret), substDollar(p.b, ret))
        is PForall -> PForall(p.names, substDollar(p.body, ret))
        is PExists -> PExists(p.names, substDollar(p.body, ret))
        is PExists1 -> PExists1(p.names, substDollar(p.body, ret))
        else -> p
    }

    private fun substExprDollar(e: Expr, ret: Expr): Expr = when (e) {
        is ReturnSym -> ret
        // $n 参数引用（决策 65，T6）：声明期已改写为参数名；若漏到此处说明上游未覆盖，原样透传。
        is ParamRefExpr -> e
        is BinExpr -> e.copy(left = substExprDollar(e.left, ret), right = substExprDollar(e.right, ret))
        is UniExpr -> e.copy(operand = substExprDollar(e.operand, ret))
        is CallExpr -> e.copy(callee = substExprDollar(e.callee, ret), args = e.args.map { substExprDollar(it, ret) })
        is InstExpr -> e.copy(target = substExprDollar(e.target, ret), terms = e.terms.map { substExprDollar(it, ret) })
        else -> e
    }

    /** Γ 中收集所有项（∀ 实例化 / ∃ 见证的候选，有限集保证饱和终止） */
    fun groundTerms(facts: List<Prop>): List<Expr> {
        val out = LinkedHashSet<Expr>()
        fun collectTerm(e: Expr) {
            when (e) {
                is IntLit, is StrLit, is NameRef -> out.add(e)
                is BinExpr -> { collectTerm(e.left); collectTerm(e.right) }
                is CallExpr -> e.args.forEach { collectTerm(it) }
                is InstExpr -> e.terms.forEach { collectTerm(it) }
                else -> {}
            }
        }
        fun collectProp(p: Prop) {
            when (p) {
                is PAtom -> p.terms.forEach { collectTerm(it) }
                is PNot -> collectProp(p.p)
                is PAnd -> { collectProp(p.a); collectProp(p.b) }
                is POr -> { collectProp(p.a); collectProp(p.b) }
                is PImp -> { collectProp(p.a); collectProp(p.b) }
                is PIff -> { collectProp(p.a); collectProp(p.b) }
                is PForall -> collectProp(p.body)
                is PExists -> collectProp(p.body)
                is PExists1 -> collectProp(p.body)
                else -> {}
            }
        }
        facts.forEach { collectProp(it) }
        return out.toList()
    }

    /** α-等价：绑定变量按出现序重命名为 #k 后做忽略 pos 的结构比较（∃x.P 与 ∃y.P 同真） */
    fun alphaEq(p: Prop, q: Prop): Boolean = propStructEq(canonicalize(p), canonicalize(q))

    fun canonicalize(p: Prop): Prop {
        var counter = 0
        fun go(x: Prop, env: Map<String, String>): Prop = when (x) {
            is PAtom -> PAtom(x.head, x.terms.map { canonTerm(it, env) })
            is PNot -> PNot(go(x.p, env))
            is PAnd -> PAnd(go(x.a, env), go(x.b, env))
            is POr -> POr(go(x.a, env), go(x.b, env))
            is PImp -> PImp(go(x.a, env), go(x.b, env))
            is PIff -> PIff(go(x.a, env), go(x.b, env))
            is PForall -> { val ns = x.names.map { "#${counter++}" }; PForall(ns, go(x.body, env + x.names.zip(ns))) }
            is PExists -> { val ns = x.names.map { "#${counter++}" }; PExists(ns, go(x.body, env + x.names.zip(ns))) }
            is PExists1 -> { val ns = x.names.map { "#${counter++}" }; PExists1(ns, go(x.body, env + x.names.zip(ns))) }
            else -> x
        }
        return go(p, emptyMap())
    }

    private fun canonTerm(e: Expr, env: Map<String, String>): Expr = when (e) {
        is NameRef -> if (e.name in env) NameRef(env.getValue(e.name)) else e
        is BinExpr -> e.copy(left = canonTerm(e.left, env), right = canonTerm(e.right, env))
        is UniExpr -> e.copy(operand = canonTerm(e.operand, env))
        is CallExpr -> e.copy(callee = canonTerm(e.callee, env), args = e.args.map { canonTerm(it, env) })
        is InstExpr -> e.copy(target = canonTerm(e.target, env), terms = e.terms.map { canonTerm(it, env) })
        is FieldExpr -> e.copy(target = canonTerm(e.target, env))
        is SymbolCallExpr -> e.copy(args = e.args.map { canonTerm(it, env) })
        else -> e
    }

    /** 忽略 Expr.pos 的命题结构相等（项比较复用 termEq） */
    fun propStructEq(p: Prop, q: Prop): Boolean = when {
        p === q -> true
        p is PTop && q is PTop -> true
        p is PBot && q is PBot -> true
        p is PAtom && q is PAtom -> p.head == q.head && p.terms.size == q.terms.size &&
            p.terms.zip(q.terms).all { termEq(it.first, it.second) }
        p is PNot && q is PNot -> propStructEq(p.p, q.p)
        p is PAnd && q is PAnd -> propStructEq(p.a, q.a) && propStructEq(p.b, q.b)
        p is POr && q is POr -> propStructEq(p.a, q.a) && propStructEq(p.b, q.b)
        p is PImp && q is PImp -> propStructEq(p.a, q.a) && propStructEq(p.b, q.b)
        p is PIff && q is PIff -> propStructEq(p.a, q.a) && propStructEq(p.b, q.b)
        p is PForall && q is PForall -> p.names.size == q.names.size && propStructEq(p.body, q.body)
        p is PExists && q is PExists -> p.names.size == q.names.size && propStructEq(p.body, q.body)
        p is PExists1 && q is PExists1 -> p.names.size == q.names.size && propStructEq(p.body, q.body)
        else -> false
    }
}

/**
 * 可判定片段上的 ⊢（形式化文档推理规则 + 用户点名的自动证明）：
 * 合取分解、双否、== 自反、∀ 合一特化（单向实例化）、∃ 见证、
 * 前向链接 modus ponens（`p` 与 `p->q` 可推出 `q`）、⊥ 爆炸。
 * 不做：算术推理、∨/¬/→ 待证侧引入、∃! 唯一性、多名字量词（v1 限制）。
 */
class Prover(private val maxRounds: Int = 3, private val maxDepth: Int = 4) {
    var deepAborted = false
        private set

    /**
     * 饱和闭包：合取分解 + ∀ 单名实例化（候选项 = Γ 现有项 ∪ 目标项）+ MP，迭代至不动点。
     * extraGoals 的项只作实例化候选，不作为事实加入（否则目标平凡可证，破坏健全性）。
     */
    fun closure(gamma: List<Prop>, extraGoals: List<Prop> = emptyList()): List<Prop> {
        val facts = PropLogic.normalizeFacts(gamma).toMutableSet()
        val extraTerms = extraGoals.flatMap { PropLogic.groundTerms(listOf(it)) }
        repeat(maxRounds) {
            val terms = (PropLogic.groundTerms(facts.toList()) + extraTerms).distinctBy { PropLogic.termTag(it) }
            var changed = false
            for (fa in facts.filterIsInstance<PForall>().toList()) {
                if (fa.names.size != 1) continue
                for (t in terms) {
                    PropLogic.normalizeFacts(listOf(PropLogic.substProp(fa.body, mapOf(fa.names[0] to t))))
                        .forEach { if (facts.add(it)) changed = true }
                }
            }
            for (im in facts.filterIsInstance<PImp>().toList()) {
                val a = im.a
                if (a is PAtom && facts.any { it is PAtom &&
                        PropLogic.matchProp(it as PAtom, a, emptySet(), mutableMapOf()) }) {
                    PropLogic.normalizeFacts(listOf(im.b)).forEach { if (facts.add(it)) changed = true }
                }
            }
            // 矛盾检测：p 与 ¬p 同在 Γ → ⊥（穷尽性/不可达分支判定的基础）
            if (!facts.contains(PBot) && facts.any { p -> p !is PNot && facts.any { it is PNot && it.p == p } }) {
                facts.add(PBot); changed = true
            }
            if (!changed) return@repeat
        }
        return facts.toList()
    }

    fun proves(gamma: List<Prop>, goal: Prop): Boolean {
        deepAborted = false
        val cl = closure(gamma, listOf(goal))
        // 目标的 ground 项并入实例化候选：否则 ∀n.p<n> 永远举不出目标里的具体项（valid<7> 证不出）
        // P4：预提取等式事实（prove 递归共享，避免每个目标重扫全部 facts）
        val eqs = cl.filterIsInstance<PAtom>()
            .filter { (it.head == "=" || it.head == "==") && it.terms.size == 2 }
        return prove(cl, goal, 0, HashSet(), eqs)
    }

    private fun prove(
        facts: List<Prop>, goal: Prop, d: Int, visited: MutableSet<Prop>, eqs: List<PAtom>
    ): Boolean {
        if (d > maxDepth) { deepAborted = true; return false }
        if (goal != PBot && facts.contains(PBot)) return true   // 矛盾爆炸
        // 复合目标已在事实库中（MP 产出的析取式等）：结构同一即成立
        if (goal != PBot && facts.any { it == goal }) return true
        // P4：同目标在证明路径上重现 = 环（X→…→X 只能继续循环），剪枝防震荡
        if (!visited.add(goal)) return false
        return when (goal) {
            PTop -> true
            PBot -> facts.contains(PBot)
            is PAnd -> prove(facts, goal.a, d, visited, eqs) && prove(facts, goal.b, d, visited, eqs)
            is POr -> prove(facts, goal.a, d, visited, eqs) || prove(facts, goal.b, d, visited, eqs) || disjSubset(facts, goal, d)
            is PImp -> prove(facts + PropLogic.normalizeFacts(listOf(goal.a)), goal.b, d + 1, visited, eqs)
            is PIff -> prove(facts, PImp(goal.a, goal.b), d, visited, eqs) && prove(facts, PImp(goal.b, goal.a), d, visited, eqs)
            is PExists -> {
                if (goal.names.size != 1) false
                else PropLogic.groundTerms(facts).any { c ->
                    prove(facts, PropLogic.substProp(goal.body, mapOf(goal.names[0] to c)), d + 1, visited, eqs)
                }
            }
            is PForall -> false   // v1 待证侧不支持 ∀ 引入
            is PExists1 -> false  // v1 待证侧不支持 ∃! 引入
            is PNot -> facts.any { it == goal } || facts.contains(PBot)
            is PAtom -> matchAtom(facts, goal, d, visited, eqs) ||
                // P4（决策 80）：等式图可达（对称 + 传递，BFS 双向边即对称）
                ((goal.head == "=" || goal.head == "==") && goal.terms.size == 2 &&
                    (PropLogic.termEq(goal.terms[0], goal.terms[1]) ||
                        eqReachable(eqs, goal.terms[0], goal.terms[1]))) ||
                // P4：类型事实在等式下的传播 `e:T, e=e' ⊢ e':T`
                (goal.head == ":" && goal.terms.size == 2 && typeFactEq(facts, goal, eqs)) ||
                // P4：浅层等量代换 `a=b, p<a> ⊢ p<b>`（一层，不递归、不动事实库）
                proveWithSubst(facts, goal, d, visited, eqs)
        }
    }

    /**
     * 析取子集规则：若 Γ 中某析取式的子项集合 ⊆ 目标子项集合，则目标成立。
     * 有效方向 `(A∨B) ⊢ (A∨B∨C)`（子集⊆超集）；反向 `(A∨B∨C) ⊢ (A∨B)` 不可判、不做。
     * 由此：覆盖律 `(R∨G∨B)` 证出穷尽目标 `(R∨G∨B)`，但证不出残缺目标 `(R∨G)`——
     * 恰是 partial when 应报 W-NON-EXHAUSTIVE 的语义。不递归、不随事实库膨胀。
     */
    private fun disjSubset(facts: List<Prop>, goal: Prop, d: Int): Boolean {
        val want = flattenOr(goal)
        if (want.size < 2) return false
        return facts.filterIsInstance<POr>().any { f ->
            val have = flattenOr(f)
            have.isNotEmpty() && have.all { h -> want.any { w -> PropLogic.alphaEq(h, w) } }
        }
    }

    private fun flattenOr(p: Prop): List<Prop> = when (p) {
        is POr -> flattenOr(p.a) + flattenOr(p.b)
        else -> listOf(p)
    }

    private fun matchAtom(facts: List<Prop>, goal: PAtom, d: Int, visited: MutableSet<Prop>, eqs: List<PAtom>): Boolean {
        if (injective(facts, goal)) return true
        for (f in facts) when (f) {
            is PAtom -> if (f.head == goal.head &&
                f.terms.size == goal.terms.size &&
                f.terms.zip(goal.terms).all { PropLogic.termEq(it.first, it.second) }) return true
            is PImp -> if (PropLogic.matchProp(f.b, goal, emptySet(), mutableMapOf()) &&
                prove(facts, f.a, d + 1, visited, eqs)) return true
            is PExists -> if (f.names.size == 1 &&
                PropLogic.matchProp(f.body, goal, f.names.toSet(), mutableMapOf())) return true
            else -> {}
        }
        return false
    }

    /**
     * 构造子单射（决策 49，v1 内建）：`C<a> = C<b>` → `a = b`。
     * 覆盖律公理由检查器注入 Γ，MP 通道可自动触发；此处补一层直接判定，
     * 免得深度不足时漏证（一元构造子如 Some 最常见）。
     */
    private fun injective(facts: List<Prop>, goal: PAtom): Boolean {
        if ((goal.head != "=" && goal.head != "==" ) || goal.terms.size != 2) return false
        val l = goal.terms[0]; val r = goal.terms[1]
        if (l !is CallExpr || r !is CallExpr) return false
        val ln = (l.callee as? NameRef)?.name ?: return false
        val rn = (r.callee as? NameRef)?.name ?: return false
        if (ln != rn || l.args.size != r.args.size) return false
        return l.args.zip(r.args).all { (a, b) ->
            val sub = PAtom("=", listOf(a, b))
            facts.contains(sub) || PropLogic.termEq(a, b)
        }
    }

    // ============ P4 等式推理（决策 80，v1.0 计划 §6.3） ============

    /**
     * 规则 2/1：等式传递 + 对称。以预提取的 `=`/`==` 事实建无向图，BFS 判 a、b 连通。
     * 无向边天然含对称；BFS 即传递闭包。有界 O(V+E)，不递归。总顶点数 <= 2×等式数，深度由等式数量决定。
     */
    private fun eqReachable(eqs: List<PAtom>, a: Expr, b: Expr): Boolean {
        if (PropLogic.termEq(a, b)) return true
        val adj = HashMap<Expr, MutableSet<Expr>>()
        for (f in eqs) {
            adj.getOrPut(f.terms[0]) { HashSet() }.add(f.terms[1])
            adj.getOrPut(f.terms[1]) { HashSet() }.add(f.terms[0])
        }
        if (a !in adj && b !in adj) return false   // 端点都不在场：无路可通
        val visited = HashSet<Expr>(); val queue = ArrayDeque<Expr>()
        queue.add(a); visited.add(a)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (PropLogic.termEq(cur, b)) return true
            adj[cur]?.forEach { if (visited.add(it)) queue.add(it) }
        }
        return false
    }

    /**
     * 规则 4：类型事实的等式推理。目标 `e' : T` 时，找 Γ 里某个 `e : T` 且 e ≍ e'（等式连通）。
     * `var m = n`（未声明类型）经 `m = n` 与 `n : Nat`（或字面量类型事实）连上。
     */
    private fun typeFactEq(facts: List<Prop>, goal: PAtom, eqs: List<PAtom>): Boolean {
        val t = goal.terms[1]
        return facts.any { f ->
            f is PAtom && f.head == ":" && f.terms.size == 2 &&
                PropLogic.termEq(f.terms[1], t) &&
                eqReachable(eqs, f.terms[0], goal.terms[0])
        }
    }

    /**
     * 规则 3：浅层等量代换。对预提取的每个等式（两个方向），把目标 PAtom 的顶层项整体替换，
     * 若替换后的目标可直接/递归证明则成立。
     * 约束（v1.0 §6.3）：只替换一层、不连锁代换；只在目标侧、不动事实库；d+1 参与 maxDepth 检查。
     * 预筛：目标项不落在任何等式端点上时直接失败（无代换可能，省掉无谓替换尝试）。
     */
    private fun proveWithSubst(facts: List<Prop>, goal: PAtom, d: Int, visited: MutableSet<Prop>, eqs: List<PAtom>): Boolean {
        if (eqs.isEmpty()) return false
        // 目标项必须与某个等式端点结构相等，代换才有意义
        val hits = goal.terms.any { gt ->
            eqs.any { PropLogic.termEq(gt, it.terms[0]) || PropLogic.termEq(gt, it.terms[1]) }
        }
        if (!hits) return false
        for (eq in eqs) {
            val (l, r) = eq.terms
            for ((from, to) in listOf(l to r, r to l)) {
                val t = goal.terms.map { if (PropLogic.termEq(it, from)) to else it }
                if (t == goal.terms) continue
                if (prove(facts, PAtom(goal.head, t), d + 1, visited, eqs)) return true
            }
        }
        return false
    }
}
