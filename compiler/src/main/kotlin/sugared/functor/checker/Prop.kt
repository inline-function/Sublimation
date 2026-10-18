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
            else -> PAtom(if (e.op == "∈") "in" else e.op, listOf(e.left, e.right))  // 比较、两参解糖 `q ○ p`=`○<q,p>`；∈ 归一 in
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

    /** 合取分解 + 双否消去 + 等价拆双蕴含：事实库归一化（定理表的可判定子集） */
    fun normalizeFacts(ps: List<Prop>): List<Prop> = ps.flatMap { expandIff(it) }.flatMap { flattenAnd(it) }.map { stripNN(it) }

    /** a ↔ b 拆成 (a→b) ∧ (b→a)——使 MP 能由 ¬irrational<power> 推出 rational<power> 等 */
    private fun expandIff(p: Prop): List<Prop> = when (p) {
        is PIff -> listOf(PImp(p.a, p.b), PImp(p.b, p.a))
        else -> listOf(p)
    }

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
            // 根文档·关键表达式：收集程序【所有】表达式，优先内其次外（后序遍历）。
            // 不再只收叶子——复合项（1+2、f(1+2)、a.x 等）也进实例化候选（用户指定：不要工程取舍）。
            when (e) {
                is BinExpr -> { collectTerm(e.left); collectTerm(e.right) }
                is UniExpr -> collectTerm(e.operand)
                is TypeTestExpr -> collectTerm(e.target)
                is TaskExpr -> collectTerm(e.body)
                is FlowExpr -> collectTerm(e.body)
                is CallExpr -> { collectTerm(e.callee); e.args.forEach { collectTerm(it) } }
                is InstExpr -> { collectTerm(e.target); e.terms.forEach { collectTerm(it) } }
                is FieldExpr -> collectTerm(e.target)
                is FunRefExpr -> collectTerm(e.target)
                is SymbolCallExpr -> e.args.forEach { collectTerm(it) }
                is LambdaExpr -> collectTerm(e.body)
                is TupleExpr -> e.items.forEach { collectTerm(it) }
                is StructCtorExpr -> e.assigns.forEach { collectTerm(it.second) }
                is ByExpr -> { collectTerm(e.target); e.props.forEach { collectTerm(it) } }
                is VarExpr -> collectTerm(e.value)
                is BlockExpr -> e.tailExpr?.let { collectTerm(it) }
                is IfExpr -> { collectTerm(e.cond); collectTerm(e.thenBlock); e.elseBlock?.let { collectTerm(it) } }
                is WhenExpr -> {
                    e.subject?.let { collectTerm(it) }
                    e.arms.forEach { arm ->
                        arm.guard?.let { collectTerm(it) }
                        arm.cond?.let { collectTerm(it) }
                        collectTerm(arm.body)
                    }
                }
                is QuantExpr -> collectTerm(e.body)
                is AnonFunExpr -> e.body?.let { collectTerm(it) }
                else -> {}
            }
            out.add(e)   // 后序：内层先入，外层后入（优先内其次外）
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

    /** 结构哈希（忽略 pos/位置）——供 BFS seen 集合 O(1) 去重；碰撞由 propStructEq 兜底 */
    fun termHash(e: Expr): Int = when (e) {
        is IntLit -> "I".hashCode() * 31 + e.value.hashCode()
        is FloatLit -> "F".hashCode() * 31 + e.value.hashCode()
        is StrLit -> "S".hashCode() * 31 + e.value.hashCode()
        is NameRef -> "N".hashCode() * 31 + e.name.hashCode()
        is BinExpr -> ("B" + e.op).hashCode() * 961 + termHash(e.left) * 31 + termHash(e.right)
        is UniExpr -> ("U" + e.op).hashCode() * 31 + termHash(e.operand)
        is CallExpr -> e.args.fold(termHash(e.callee) * 31 + "C".hashCode()) { acc, a -> acc * 31 + termHash(a) }
        is InstExpr -> e.terms.fold(termHash(e.target) * 31 + "T".hashCode()) { acc, t -> acc * 31 + termHash(t) }
        is FieldExpr -> termHash(e.target) * 31 + ("." + e.name).hashCode()
        is FunRefExpr -> termHash(e.target) * 31 + ("::" + e.name).hashCode()
        is SymbolCallExpr -> e.args.fold(("Y" + e.op).hashCode()) { acc, a -> acc * 31 + termHash(a) }
        is ParamRefExpr -> "P".hashCode() * 31 + e.index
        is TopExpr -> "Top".hashCode()
        is BotExpr -> "Bot".hashCode()
        else -> e.javaClass.name.hashCode()
    }

    /** 命题结构哈希（与 propStructEq 一致：忽略位置） */
    fun propHash(p: Prop): Int = when (p) {
        is PTop -> 1
        is PBot -> 2
        is PNot -> 3 * 31 + propHash(p.p)
        is PAnd -> 4 * 961 + propHash(p.a) * 31 + propHash(p.b)
        is POr -> 5 * 961 + propHash(p.a) * 31 + propHash(p.b)
        is PImp -> 6 * 961 + propHash(p.a) * 31 + propHash(p.b)
        is PIff -> 7 * 961 + propHash(p.a) * 31 + propHash(p.b)
        is PForall -> p.names.fold(8) { acc, n -> acc * 31 + n.hashCode() } * 31 + propHash(p.body)
        is PExists -> p.names.fold(9) { acc, n -> acc * 31 + n.hashCode() } * 31 + propHash(p.body)
        is PExists1 -> p.names.fold(10) { acc, n -> acc * 31 + n.hashCode() } * 31 + propHash(p.body)
        is PAtom -> p.terms.fold(p.head.hashCode()) { acc, t -> acc * 31 + termHash(t) }
    }
}

/**
 * 可判定片段上的 ⊢（形式化文档推理规则 + 用户点名的自动证明）：
 * 合取分解、双否、== 自反、∀ 合一特化（单向实例化）、∃ 见证、
 * 前向链接 modus ponens（`p` 与 `p->q` 可推出 `q`）、⊥ 爆炸。
 * 不做：算术推理、∨/¬/→ 待证侧引入、∃! 唯一性、多名字量词（v1 限制）。
 */
class Prover(
    private val maxRounds: Int = 114514,
    private val maxDepth: Int = 4,
    /** 激进模式会话：非空时启用三维池 / 不动点饱和 / 迭代加深 / ∨ 消去（普通模式全部关闭，行为不变） */
    internal val session: ProverSession? = null,
) {
    var deepAborted = false
        private set
    /** 当前深度预算（迭代加深每层调；普通模式恒为 maxDepth）与本路径已拆析取集 */
    private var curBudget: Int = maxDepth
    private var curUsedOr: MutableSet<String> = HashSet()

    /** 反证法：Γ ∪ {¬goal} 饱和出 ⊥ ⇒ Γ ⊢ goal（经典逻辑——调用方须确认 @classic 已挂载，§5.3） */
    fun provesByContradiction(gamma: List<Prop>, goal: Prop): Boolean {
        val cl = closure(gamma + PropLogic.normalizeFacts(listOf(PNot(goal))), emptyList(), gamma)
        return cl.contains(PBot)
    }

    /**
     * 饱和闭包：合取分解 + ∀ 单名实例化（候选项 = Γ 现有项 ∪ 目标项）+ MP，迭代至不动点。
     * extraGoals 的项只作实例化候选，不作为事实加入（否则目标平凡可证，破坏健全性）。
     */
    fun closure(gamma: List<Prop>, extraGoals: List<Prop> = emptyList(), termSource: List<Prop> = gamma): List<Prop> {
        val facts = PropLogic.normalizeFacts(gamma).toMutableSet()
        val extraTerms = extraGoals.flatMap { PropLogic.groundTerms(listOf(it)) }
        // 关键表达式：一次性从程序块（初始事实 + 目标）收集**一切**表达式，此后固定——
        // 不随新增事实扩张（根文档「命题中出现的表达式再收集仅关键表达式中」），
        // 否则复合项会自我增殖（(1+2)->(1+2)+(2+1)->…）导致笛卡尔爆炸、永不达不动点。
        // 激进模式：三维池 + 不动点饱和（无 maxRounds 截断）；普通模式沿用下方一维 keyTerms 与有界轮数
        if (session != null) return closureAggressive(gamma, extraGoals, termSource)
        val keyTerms = (PropLogic.groundTerms(termSource) + extraTerms).distinctBy { PropLogic.termTag(it) }
        // 实例化矩阵预计算：keyTerms 固定，各 ∀ 量词的候选替换跨轮不变——每轮重建是 Kⁿ 开销（show.subl 卡顿根因）。
        val forallSubs = facts.filterIsInstance<PForall>().mapNotNull { fa ->
            if (fa.names.isEmpty()) null else fa to when {
                fa.names.size == 1 -> keyTerms.map { listOf(it) }
                else -> {
                    var acc: List<List<Expr>> = listOf(emptyList())
                    repeat(fa.names.size) { acc = acc.flatMap { comb -> keyTerms.map { comb + it } } }
                    acc
                }
            }
        }
        val seenSubs = HashSet<String>()
        for (round in 0 until maxRounds) {
            var changed = false
            for ((fa, combs) in forallSubs) {
                for (comb in combs) {
                    val key = fa.names.joinToString("\u0001") + "\u0002" + comb.joinToString("\u0001") { PropLogic.termTag(it) }
                    if (!seenSubs.add(key)) continue
                    val sub = fa.names.zip(comb).toMap()
                    PropLogic.normalizeFacts(listOf(PropLogic.substProp(fa.body, sub)))
                        .forEach { if (facts.add(it)) changed = true }
                }
            }
            for (im in facts.filterIsInstance<PImp>().toList()) {
                // 前件按规范形拆解（合取拆诸支、等价已拆双蕴含、双否已消），各支都在 Γ 即触发 MP
                val ants = PropLogic.normalizeFacts(listOf(im.a))
                if (ants.isNotEmpty() && ants.all { facts.contains(it) }) {
                    PropLogic.normalizeFacts(listOf(im.b)).forEach { if (facts.add(it)) changed = true }
                }
            }
            // 加强：modus tollens（后件否定推前件否定）——p→q 与 ¬q 在 Γ ⇒ ¬p（经典逻辑健全）
            for (im in facts.filterIsInstance<PImp>().toList()) {
                if (facts.contains(PNot(im.b))) {
                    PropLogic.normalizeFacts(listOf(PNot(im.a))).forEach { if (facts.add(it)) changed = true }
                }
            }
            // 矛盾检测：p 与 ¬p 同在 Γ → ⊥（穷尽性/不可达分支判定的基础）；先收集 ¬ 集合转 O(N)
            if (!facts.contains(PBot)) {
                val negated = facts.filterIsInstance<PNot>().map { it.p }.toHashSet()
                if (facts.any { p -> p !is PNot && p in negated }) {
                    facts.add(PBot); changed = true
                }
            }
            if (!changed) break
        }
        return facts.toList()
    }

    /**
     * 激进饱和（§5.1）：轮数无上限——**靠不动点判据退出**（本轮无新事实）。
     * ∀ 实例化按维度分派（§4.6）：裸原子→命题池，':'右端→类型池，其余→表达式池。
     * 命题维替换用 substPropParam，项/类型维用 substProp（类型经 typeToExpr 再装为项）。
     */
    private fun closureAggressive(gamma: List<Prop>, extraGoals: List<Prop>, termSource: List<Prop>): List<Prop> {
        val sess = session!!
        val facts = PropLogic.normalizeFacts(gamma).toMutableSet()
        val pool = sess.prebuiltPool
            ?: buildKeyPool(PropLogic.normalizeFacts(termSource), extraGoals, sess.typeHints, sess)
        sess.prebuiltPool = null   // 一次性：防跨义务串用
        sess.step(pool.sizes())
        // 实例化矩阵：keyTerms 固定的老逻辑保留（表达式维 ∀ 仍走它），新维度 ∀ 走 instCombos
        val extraTerms = extraGoals.flatMap { PropLogic.groundTerms(listOf(it)) }
        val keyTerms = (PropLogic.groundTerms(termSource) + extraTerms).distinctBy { PropLogic.termTag(it) }
        val combosPerFa = facts.filterIsInstance<PForall>().map { fa -> fa to instCombos(fa, pool, sess) }
        // 表达式维的旧矩阵（普通模式同款，供 dimsOf 未含 TYPE/PROP 的 ∀ 命中）
        val forallSubs = facts.filterIsInstance<PForall>().mapNotNull { fa ->
            if (fa.names.isEmpty()) null else fa to when {
                fa.names.size == 1 -> keyTerms.map { listOf(it) }
                else -> {
                    var acc: List<List<Expr>> = listOf(emptyList())
                    repeat(fa.names.size) { acc = acc.flatMap { comb -> keyTerms.map { comb + it } } }
                    acc
                }
            }
        }
        val seenSubs = HashSet<String>()
        var round = 0
        while (true) {
            sess.checkAbort()
            sess.step(pool.sizes())
            var changed = false
            // 新维度 ∀（命题/类型）实例化
            for ((fa, combos) in combosPerFa) {
                for ((exprSub, propSub) in combos) {
                    if (propSub.isEmpty() && exprSub.isEmpty()) continue
                    val key = fa.names.joinToString("\u0001") + "\u0003" +
                        exprSub.entries.joinToString("\u0001") { it.key + "=" + PropLogic.termTag(it.value) } + "\u0004" +
                        propSub.entries.joinToString("\u0001") { it.key + "=" + PropLogic.render(it.value) }
                    if (!seenSubs.add(key)) continue
                    val inst = PropLogic.substProp(fa.body, exprSub)
                    val final = propSub.entries.fold(inst) { acc, (n, q) -> PropLogic.substPropParam(acc, n, q) }
                    PropLogic.normalizeFacts(listOf(final)).forEach { if (facts.add(it)) changed = true }
                }
            }
            // 表达式维 ∀（沿用普通模式矩阵）
            for ((fa, combs) in forallSubs) {
                for (comb in combs) {
                    val key = fa.names.joinToString("\u0001") + "\u0002" + comb.joinToString("\u0001") { PropLogic.termTag(it) }
                    if (!seenSubs.add(key)) continue
                    val sub = fa.names.zip(comb).toMap()
                    PropLogic.normalizeFacts(listOf(PropLogic.substProp(fa.body, sub)))
                        .forEach { if (facts.add(it)) changed = true }
                }
            }
            for (im in facts.filterIsInstance<PImp>().toList()) {
                // 前件按规范形拆解（合取拆诸支、等价已拆双蕴含、双否已消），各支都在 Γ 即触发 MP
                val ants = PropLogic.normalizeFacts(listOf(im.a))
                if (ants.isNotEmpty() && ants.all { facts.contains(it) }) {
                    PropLogic.normalizeFacts(listOf(im.b)).forEach { if (facts.add(it)) changed = true }
                }
            }
            // modus tollens（后件否定推前件否定）——p→q 与 ¬q 在 Γ ⇒ ¬p（经典逻辑健全）
            for (im in facts.filterIsInstance<PImp>().toList()) {
                if (facts.contains(PNot(im.b))) {
                    PropLogic.normalizeFacts(listOf(PNot(im.a))).forEach { if (facts.add(it)) changed = true }
                }
            }
            // 矛盾检测：p 与 ¬p 同在 Γ → ⊥（穷尽性/不可达分支判定的基础）
            if (!facts.contains(PBot)) {
                val negated = facts.filterIsInstance<PNot>().map { it.p }.toHashSet()
                if (facts.any { p -> p !is PNot && p in negated }) {
                    facts.add(PBot); changed = true
                }
            }
            if (!changed) break   // 不动点判据（§5.1）：本轮无新事实 → 停
            round++
        }
        return facts.toList()
    }

    fun proves(gamma: List<Prop>, goal: Prop, global: List<Prop> = emptyList()): Boolean {
        deepAborted = false
        if (session != null) return provesAggressive(gamma, goal, global)
        val cl = closure(gamma + global, listOf(goal), termSource = gamma)
        // 目标的 ground 项并入实例化候选：否则 ∀n.p<n> 永远举不出目标里的具体项（valid<7> 证不出）
        // P4：预提取等式事实（prove 递归共享，避免每个目标重扫全部 facts）
        val eqs = cl.filterIsInstance<PAtom>()
            .filter { (it.head == "=" || it.head == "==") && it.terms.size == 2 }
        return prove(cl, goal, 0, HashSet(), eqs)
    }

    /** 激进 prove：闭包只算一次（与深度无关）；迭代加深 4→8→16…（§5.1，无上限靠 visited 剪枝 + 中断） */
    private fun provesAggressive(gamma: List<Prop>, goal: Prop, global: List<Prop>): Boolean {
        val key = gamma.joinToString("\u0001") { PropLogic.render(it) } + "\u0002" + PropLogic.render(goal)
        if (session!!.memo.contains(key)) return true   // 正向记忆化（只记已证）
        val cl = closure(gamma + global, listOf(goal), termSource = gamma)
        val eqs = cl.filterIsInstance<PAtom>()
            .filter { (it.head == "=" || it.head == "==") && it.terms.size == 2 }
        var budget = 4
        while (true) {
            session.checkAbort()
            deepAborted = false
            curBudget = budget
            if (prove(cl, goal, 0, HashSet(), eqs)) {
                session.memo.add(key)
                return true
            }
            if (!deepAborted) return false   // 未被深度截断 → 真不可证
            budget *= 2
        }
    }

    private fun prove(
        facts: List<Prop>, goal: Prop, d: Int, visited: MutableSet<Prop>, eqs: List<PAtom>
    ): Boolean {
        if (d > curBudget) { deepAborted = true; return false }
        if (goal != PBot && facts.contains(PBot)) return true   // 矛盾爆炸
        // 复合目标已在事实库中（MP 产出的析取式等）：结构同一即成立
        if (goal != PBot && facts.any { it == goal }) return true
        // P4：同目标在证明路径上重现 = 环（X→…→X 只能继续循环），剪枝防震荡
        if (!visited.add(goal)) return false
        val direct = when (goal) {
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
            is PForall -> {
                // 加强：∀ 引入（概括规则）——绑定名替换为新鲜常量（合法标识符不含 `#`，故必新鲜）后证 body
                if (goal.names.isEmpty()) prove(facts, goal.body, d + 1, visited, eqs)
                else {
                    val fresh = HashMap<String, Expr>()
                    goal.names.forEachIndexed { i, n -> fresh[n] = NameRef("#vg${d}_${i}") }
                    prove(facts, PropLogic.substProp(goal.body, fresh), d + 1, visited, eqs)
                }
            }
            is PExists1 -> false  // v1 待证侧不支持 ∃! 引入
            is PNot -> facts.any { it == goal } || facts.contains(PBot) ||
                // 加强：双重否定引入 p ⊢ ¬¬p（直觉逻辑健全）；¬→ 载体已由 MP/closure 处理
                (goal.p is PNot && prove(facts, (goal.p as PNot).p, d + 1, visited, eqs))
            is PAtom -> membershipFilterPred(facts, goal) || matchAtom(facts, goal, d, visited, eqs) ||
                // P4（决策 80）：等式图可达（对称 + 传递，BFS 双向边即对称）
                (goal.head in setOf("=", "==", "<=", "≤", ">=", "≥") && goal.terms.size == 2 &&
                    (PropLogic.termEq(goal.terms[0], goal.terms[1]) ||
                        eqReachable(eqs, goal.terms[0], goal.terms[1]))) ||
                // P4：类型事实在等式下的传播 `e:T, e=e' ⊢ e':T`
                (goal.head == ":" && goal.terms.size == 2 && typeFactEq(facts, goal, eqs))
        }
        if (direct) return true
        // 激进模式专属：Γ 含 A∨B 时分情况证目标（∨ 消去，§5.3）——两个分支都证出才算成。
        // usedOr 记录本路径已拆过的析取式，防同一析取被反复拆（visited 剪枝之外的终止保证）。
        if (session != null) {
            for (or in facts.filterIsInstance<POr>()) {
                val k = PropLogic.render(or)
                if (k in curUsedOr) continue
                val fa = facts + PropLogic.normalizeFacts(listOf(or.a))
                val fb = facts + PropLogic.normalizeFacts(listOf(or.b))
                // 分支内 p 与 ¬p 并存 ⇒ 该分支已矛盾，任意目标成立（析取三段论 ¬a, a∨b ⊢ b 的落点）。
                // ⊥-消去在直觉主义亦成立，只是 ∨ 拆分本身只在激进/经典语境提供。
                val botA = fa.any { it is PNot && fa.contains((it as PNot).p) }
                val botB = fb.any { it is PNot && fb.contains((it as PNot).p) }
                val savedUsed = curUsedOr
                val u2 = HashSet(curUsedOr); u2.add(k); curUsedOr = u2
                try {
                    if ((prove(fa, goal, d + 1, visited, eqs) || botA) &&
                        (prove(fb, goal, d + 1, visited, eqs) || botB)) return true
                } finally { curUsedOr = savedUsed }
            }
        }
        return false
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
     * 容器/高阶函数的后验推理（加强证明器，窄根但覆盖面广）：
     * Γ 含 `e ∈ filter(xs, λp. body)` 时，filter 的语义保证该元素满足谓词 →
     * 直接把谓词 β-归约到 `body[p:=e]` 与目标原子比对。
     * 覆盖 `list.filter { _ != 0 }` 之类后置 `∀e. e ∈ $ -> e ≠ 0` 的证明；
     * 比较运算符做 ASCII/Unicode 等价（!= ↔ ≠、<= ↔ ≤、>= ↔ ≥、== ↔ =）。
     */
    private fun membershipFilterPred(facts: List<Prop>, goal: PAtom): Boolean {
        for (f in facts) {
            if (f !is PAtom || f.head != "in" || f.terms.size != 2) continue
            val container = f.terms[1]
            if (container !is CallExpr) continue
            val cal = container.callee
            val isFilter = (cal as? NameRef)?.name == "filter" || (cal as? FieldExpr)?.name == "filter"
            if (!isFilter) continue
            // 自由函数形态 filter(xs, λ)：实参 [xs, λ]；方法糖形态 xs.filter(λ)：实参 [λ]，接收者是 callee.target。
            val lam = when {
                cal is NameRef && container.args.size == 2 -> container.args[1] as? LambdaExpr
                cal is FieldExpr && container.args.size == 1 -> container.args[0] as? LambdaExpr
                else -> null
            } ?: continue
            if (lam.params.size != 1) continue
            val member = f.terms[0]
            val reduced = PropLogic.substExpr(lam.body, mapOf(lam.params[0] to member))
            val rp = PropLogic.fromExpr(reduced)
            if (rp is PAtom && atomsCmpEq(rp, goal)) return true
        }
        return false
    }

    /** 两个原子相等（比较运算允许 ASCII/Unicode 写法等价；其余要求 head 精确一致） */
    private fun atomsCmpEq(a: PAtom, b: PAtom): Boolean {
        if (a.terms.size != b.terms.size) return false
        if (!a.terms.zip(b.terms).all { PropLogic.termEq(it.first, it.second) }) return false
        if (a.head == b.head) return true
        val ca = cmpCanon(a.head) ?: return false
        return ca == cmpCanon(b.head)
    }

    private fun cmpCanon(h: String): String? = when (h) {
        "=", "==" -> "=="
        "!=", "≠" -> "!="
        "<", ">" -> h
        "<=", "≤" -> "<="
        ">=", "≥" -> ">="
        else -> null
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
}

