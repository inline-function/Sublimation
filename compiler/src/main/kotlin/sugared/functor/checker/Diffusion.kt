package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 作用域帧：变量类型环境 + Γ 命题事实（弥散载体）。
 * 嵌套帧继承外层；块事实回溢父帧（函数体/语句块的弥散），lambda 帧隔离。
 * W-UNUSED（报错增强）：post（非空）的声明进入"未使用审计"；used 沿父链上溢（子作用域用到即算外层用过）。
 */
class Frame(val parent: Frame?) {
    val vars = LinkedHashMap<String, Type>()
    // P8：LinkedHashSet 去重 O(1) 且保持插入序（此前 ArrayList 的 `p !in facts` 是 O(n)，深帧/饱和场景 O(n²)）
    val facts = LinkedHashSet<Prop>()
    /** v3.0 特性 7：局部定理表（块内局部函数定义=∀ 引入），作用域随帧；∴ 搜索时并入定理空间 */
    val localTheorems = LinkedHashMap<String, FunDecl>()
    private val muts = LinkedHashSet<String>()
    /** root doc《副作用与可变性控制》@ref：引用别名变量（语义同裸引用；与 muts 并列登记） */
    private val refs = LinkedHashSet<String>()
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
    fun declareRef(n: String) { refs.add(n) }
    fun isRef(n: String): Boolean = n in refs || (parent?.isRef(n) ?: false)
    fun markUsed(n: String) { used.add(n); parent?.markUsed(n) }
    fun isUsed(n: String): Boolean = n in used
    /** 本帧（含子树）所有审计声明：名字 + 声明点 */
    fun auditedDecls(): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun walk(fr: Frame) { fr.audited.forEach { (n, p) -> out.add(n to p) }; fr.children.forEach { walk(it) } }
        walk(this)
        return out
    }
    fun inject(p: Prop) { if (p != PTop) facts.add(p) }
    fun allFacts(): List<Prop> = (parent?.allFacts() ?: emptyList()) + facts.toList()
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

/** 根文档·为命题 `e : T`：字面量类型判定（`1 : Nat`/`"s" : Str`）。
 *  变量/参数的类型归属靠注入的 `x : T` 事实（proveOrDeep 的 gamma 匹配），不在此处理。 */
internal fun constTypeAtom(p: Prop): Boolean? {
    if (p !is PAtom || p.head != ":" || p.terms.size != 2) return null
    val t = (p.terms[1] as? NameRef)?.name ?: return null
    return when (p.terms[0]) {
        is IntLit -> t in setOf("Nat", "Int", "Rat")
        is StrLit -> t == "Str"
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
        val equiv = def.equiv ?: return null
        if (def.params.size != cur.terms.size) return null
        val sub = def.params.zip(cur.terms).toMap()
        cur = substPropTerms(PropLogic.fromExpr(equiv), sub)
        if (PropLogic.render(cur) == PropLogic.render(goal)) return null   // 自引用回环：视为原子
        return cur     // 展开一层即可（proveOrDeep 递归自然再展开；render 比较防回环已保证终止）
    }
    return null
}

/** 根文档·纯度结构相等：表达式是否含 @impure 函数调用（值不确定，不参与结构相等/自反）。
 *  方法/字典分发（FieldExpr 上无法定符号）保守视为不纯——匹配根文档「不上价值的调用不参与」。 */
private fun Checker.containsImpureCall(e: Expr): Boolean = when (e) {
    is CallExpr -> {
        val calleeImpure = when (val c = e.callee) {
            is NameRef -> syms.findFun(c.name)?.annotations?.contains("impure") == true
            is FieldExpr -> syms.findFun(c.name)?.annotations?.contains("impure") == true   // x.f() 方法下沉为自由函数
            else -> true   // 动态计算 callee（λ 槽位等）：无法静态判定 → 保守不纯
        }
        calleeImpure || e.args.any { containsImpureCall(it) } || e.namedArgs.values.any { containsImpureCall(it) }
    }
    is BinExpr -> containsImpureCall(e.left) || containsImpureCall(e.right)
    is UniExpr -> containsImpureCall(e.operand)
    is FieldExpr -> containsImpureCall(e.target)
    is InstExpr -> containsImpureCall(e.target) || e.terms.any { containsImpureCall(it) }
    is TupleExpr -> e.items.any { containsImpureCall(it) }
    is StructCtorExpr -> e.assigns.any { containsImpureCall(it.second) }
    is ByExpr -> containsImpureCall(e.target)   // t30：props 是证明层断言、求值期擦除，不参与纯性判定
    is VarExpr -> containsImpureCall(e.value)
    is LambdaExpr -> containsImpureCall(e.body)
    is AnonFunExpr -> e.body?.let { containsImpureCall(it) } == true
    is BlockExpr -> e.stmts.any { s -> (s as? ExprStmt)?.let { containsImpureCall(it.expr) } == true }
    is IfExpr -> containsImpureCall(e.cond) || containsImpureCall(e.thenBlock) || e.elseBlock?.let { containsImpureCall(it) } == true
    is WhenExpr -> e.subject?.let { containsImpureCall(it) } == true ||
        e.arms.any { a -> a.guard?.let { containsImpureCall(it) } == true || containsImpureCall(a.body) }
    is QuantExpr -> containsImpureCall(e.body)
    else -> false
}

/** `e : T` 类型归属判定（根文档·为命题）：
 *  ① gamma 有同结构 `x : T` 事实（变量/参数注入）；② 构造表达式 → 其结构体类型与 T 同名义。 */
private fun Checker.typeJudgement(gamma: List<Prop>, goal: PAtom): Boolean {
    val e = goal.terms[0]; val t = goal.terms[1]
    // ① 注入的事实 `x : T`（结构相等）
    if (gamma.any { f ->
            f is PAtom && f.head == ":" && f.terms.size == 2 && f.terms[0] == e && f.terms[1] == t
        }) return true
    // ② 构造表达式 A(...)：结果类型为结构体 A（命名字段 StructCtorExpr / 位置构造 CallExpr）
    val ctorName = when (e) {
        is StructCtorExpr -> e.struct
        is CallExpr -> (e.callee as? NameRef)?.name
        else -> null
    }
    if (ctorName != null && syms.structs.containsKey(ctorName)) {
        val want = when (t) {
            is NameRef -> NamedType(t.name)
            is InstExpr -> NamedType((t.target as? NameRef)?.name ?: "", exprArgsToTypes(t))
            else -> null
        }
        if (want != null) return typeEq(NamedType(ctorName), want)
    }
    return false
}

private fun exprArgsToTypes(ie: InstExpr): List<Type> = ie.terms.map { t1 ->
    when (t1) {
        is NameRef -> NamedType(t1.name)
        is InstExpr -> NamedType((t1.target as? NameRef)?.name ?: "", exprArgsToTypes(t1))
        else -> NamedType("?")
    }
}

/**
 * 证明义务入口（激进模式套壳）：计数义务（§7.3）、预建三维池、结节流进度；
 * 内部递归（expandDefined 展开）不重复计数。Ctrl-C 时抛 [ProverInterrupted] 一路退到 CLI。
 */
internal fun Checker.proveOrDeep(gamma0: List<Prop>, goal0: Prop): Boolean {
    // 证明层位置盲（P4 等式图）：LSP 在 NameRef 上携带源位置（高亮/rename 定位），
    // 但命题项的同一性只认名字——等式图/算术通道/事实成员判定都在此统一盲化。
    val gamma = gamma0.map { PropLogic.blindProp(it) }
    val goal = PropLogic.blindProp(goal0)
    val s = prover.session
    if (s != null) {
        s.enter(goal, gamma)
        buildContextPool(gamma, goal)
    }
    val r = try {
        proveOrDeepInner(gamma, goal)
    } catch (e: ProverInterrupted) {
        s?.exit(false)
        throw e
    }
    s?.exit(r)
    return r
}

/** 激进模式：类型维池的符号表提示（结构体/枚举/类型类名经 typeToExpr 再装为项候选） */
private fun Checker.collectTypeHints(): List<Expr> {
    val hints = LinkedHashSet<Expr>()
    fun put(name: String) { hints += typeToExpr(NamedType(name)) }
    syms.structs.keys.forEach { put(it) }
    syms.enums.keys.forEach { put(it) }
    syms.classes.keys.forEach { put(it) }
    allSymbols.values.forEach { sy2 ->
        sy2.structs.keys.forEach { put(it) }
        sy2.enums.keys.forEach { put(it) }
        sy2.classes.keys.forEach { put(it) }
    }
    return hints.take(200)
}

/** 预建本义务的三维池（闭包直接复用，免重复收集）；lemAugment 亦取命题维候选 */
private fun Checker.buildContextPool(gamma: List<Prop>, goal: Prop) {
    val s = prover.session ?: return
    s.typeHints = collectTypeHints()
    s.prebuiltPool = buildKeyPool(PropLogic.normalizeFacts(gamma), listOf(goal), s.typeHints, s)
}

/** 排中律前向实例化（§5.3 item 6）：可见定理里形状为 `p ∨ ¬p` 的（如 core.classic tertiumNonDatur），
 *  对命题池每个候选 A 注入 `A ∨ ¬A` 进 Γ——配合 prove 的 ∨ 消去完成分情况证明。
 *  仅 @classic 挂载时启用（§5.3）。 */
private fun Checker.lemAugment(gamma: List<Prop>, goal: Prop): List<Prop> {
    val s = prover.session ?: return gamma
    if (!syms.classicMounted) return gamma
    val lems = lemPosts()
    if (lems.isEmpty()) return gamma
    val props = s.prebuiltPool?.props ?: return gamma
    val add = LinkedHashSet<Prop>()
    for (a in props) for (t in lems) add.add(POr(a, PNot(a)))
    return if (add.isEmpty()) gamma else gamma + add
}

/** 定理后件形状 `p ∨ ¬p`（p 为无参数裸原子）= 排中律形态定理 */
private fun Checker.lemPosts(): List<Prop> {
    val cross = if (moduleTree == null) emptyList() else
        visiblePaths.mapNotNull { allSymbols[it] }.flatMap { it.theorems.values }
    val out = ArrayList<Prop>()
    for (t in (syms.theorems.values + cross)) for (post in t.posts) {
        val pp = PropLogic.fromExpr(post)
        if (pp is POr && pp.a is PAtom && pp.a.terms.isEmpty() &&
            pp.b is PNot && pp.b.p is PAtom && (pp.b.p as PAtom).terms.isEmpty() &&
            (pp.b.p as PAtom).head == pp.a.head) out.add(pp)
    }
    return out
}

internal fun Checker.proveOrDeepInner(gamma0: List<Prop>, goal: Prop): Boolean {
    val gamma = lemAugment(gamma0, goal)
    // P27（通道顺序说明）：以下通道按序尝试，**命中即短路 return**，未命中才继续下一通道：
    //  自定义命题展开 → PNot/PImp 常量拆解 → 常量原子真值 → 常量类型 → 纯度结构相等（不纯只查显式事实）
    //  → 类型判定 → 常量归约 → 线性算术（仅 ==true 短路，false/不支持继续走）→ 通用证明器 → 定理搜索。
    //  顺序敏感：类型/常量/算术通道先于通用证明器，用更精确的判定率先定谳，避免被后续宽通道误吞。
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
    // PAnd 目标（合取引入）：证 A ∧ B ⟺ 分别证 A 与 B。
    // wiki/上下文系统曾载「存在语句的见证部分不支持类型断言（x : Nat）混在性质里」——
    // 缺此通道时 ∃ x = 1 . (x : Nat) ∧ (x > 0) 的替换实例是合取目标，逐通道都吃不下。
    // 单调性保证：成分比整体小，递归必终止。
    if (goal is PAnd) return proveOrDeep(gamma, goal.a) && proveOrDeep(gamma, goal.b)
    // v3.0 特性 1：常量原子真值直接判定（不动推理规则；构造位伴生命题字面量场景）
    constAtomValue(goal)?.let { return it }
    // 根文档·为命题 `e : T`：字面量类型判定
    constTypeAtom(goal)?.let { return it }
    // 根文档·纯度结构相等：不纯表达式（含 @impure 调用）不参与结构相等/自反——仅显式注入的事实可证
    if (goal is PAtom && goal.head in setOf("=", "==", "!=", "≠", "<", ">", "<=", "≤", ">=", "≥") && goal.terms.size == 2 &&
        (containsImpureCall(goal.terms[0]) || containsImpureCall(goal.terms[1]))) {
        return gamma.any { it == goal }
    }
    // 根文档·为命题 `e : T`：变量/参数注入的 `x : T` 事实匹配，或构造表达式结果类型归属
    if (goal is PAtom && goal.head == ":" && goal.terms.size == 2 && typeJudgement(gamma, goal)) return true
    // v3.0 特性 1：目标含变量但等式事实可归约到常量（var m = 5 事实 → 目标 ≠<m,0> 归约为 ≠<5,0>）
    reduceGoalToConstants(gamma, goal)?.let { rg -> constAtomValue(rg)?.let { return it } }
    // 通道 11：Real 代数重写 — 等式两边能转成 Real → normalize + structuralEqual
    // （草案 §9.2）：仅判 "="/"=="，两边 normalization 后结构相等即证得。
    if (goal is PAtom && goal.head in setOf("=", "==") && goal.terms.size == 2) {
        val a = realFromExpr(goal.terms[0])
        val b = realFromExpr(goal.terms[1])
        if (a != null && b != null && structuralEqual(normalize(a), normalize(b))) return true
    }
    // 通用线性算术通道（整数域）：不等式/等式/in 区间的蕴含与析取反证——when 穷尽、前件、∴ 一律受益。
    // 只有判 **成立** 才短路；判不成立（false）或形态不支持（null）都继续走通用证明器，
    // 避免掐断等式图/等量代换等其它推理（P4 链式代换回归教训）。
    if (ArithProve.proves(gamma, goal) == true) return true
    val res = prover.proves(gamma, goal, globalFacts)
    if (prover.deepAborted) d.error("E-DEEP-SATURATION", "", "证明义务太复杂，自动证明器走到最深也没证出。把大结论拆成几个小步骤，一步一步用 `+…` 和 `∴ …` 来推")
    if (res) return true
    // 激进模式专属：反证法（§5.3，需 @classic）——Γ ∪ {¬goal} 饱和出 ⊥ ⇒ Γ ⊢ goal。
    // 经典逻辑独有（直觉主义只得 ¬¬goal），未挂 core.classic 绝不启用。
    if (prover.session != null && !prover.session.aborted && syms.classicMounted &&
        prover.provesByContradiction(gamma + globalFacts, goal)) return true
    // 加强（自动证明全流程）：把定理空间（core 标准库跨模块 + 用户 @theorem）纳入任意目标的判定——
    // 目标与某定理后件结构合一即成立。原只 ∴/∵/∃/if[p] 位查询，现前件/后件/伴生命题/构造位一律受益。
    return searchTheorems(goal, 0)
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
        prover.session?.noteTheorem(t.name)
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

/** 结构项合一（t39 加终止保护：occurs/自引用 + 绑定链成环均不再递归）。*/
private fun termUnifies(
    a: Expr, b: Expr, vars: Set<String>, env: MutableMap<String, Expr>,
    seen: Set<String> = emptySet(),
): Boolean {
    if (a is NameRef && a.name in vars) {
        val name = a.name
        // t39（P0 崩溃修复）：定理 theory 参数与用户变量同名（如 stdlib divides 的 n 对目标 divides<n, n>）
        // 时，b 就是同名元变量——视为同一元变量直接成功，不写入 env 造成自引用。
        if (b is NameRef && b.name == name) return true
        val prev = env[name]
        if (prev == null) { env[name] = b; return true }
        // t39：prev 已与 b 同项，或绑定链回到本名（含 env 链成环 x→y→x）→ 终止，不递归。
        if (prev === b) return true
        if (prev is NameRef && prev.name == name) return true
        if (name in seen) return true
        return termUnifies(prev, b, vars, env, seen + name)
    }
    if (a is NameRef && b is NameRef) return a.name == b.name
    if (a is IntLit && b is IntLit) return a.value == b.value
    if (a is FieldExpr && b is FieldExpr)
        return a.name == b.name && termUnifies(a.target, b.target, vars, env, seen)
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
    // 泛型默认值对齐：未实例化的类型形参（emptyList[T]() 的 T）与任何实参相容——
    // 默认值位不携带类型实参信息，严格比对会把 List[Rat] 字段写成 List[T] 误报 E-TYPE-MISMATCH
    if (isUnboundTypeParam(a) || isUnboundTypeParam(b)) return true
    // v2.1（对齐 Rust 无限类型语义）：Nothing 是底类型——可被当成任何结构体/枚举/类型类
    if (a.isNothing() || b.isNothing()) return true
    if (a.isSynthetic() || b.isSynthetic()) return true
    if (a.isUnsealed() || b.isUnsealed()) {
        val other = if (a.isUnsealed()) b else a
        return other.isUnsealed() || other.name == "Null"   // §49：非密封 when 值为 Null，仅与 Null 相容
    }
    if (a.isNumeric() && b.isNumeric()) return true   // Nat/Int/Rat 数值提升，须先于 name 相等判定
    // M-2/M-3（root doc《副作用与可变性控制》）：@mut T 可隐式转 T（转换点=拷贝点）；
    // 反方向 T → @mut T 不合法（M-3）。两侧都是 @mut 时按内层比对。
    if (a is MutType) {
        if (b is MutType) return typeLooseEq(a.inner, b.inner)
        return typeLooseEq(a.inner, b)
    }
    if (b is MutType) return false
    if (a.name != b.name || a.args.size != b.args.size) return false
    return a.args.zip(b.args).all { typeLooseEq(it.first, it.second) }
}

/** 未实例化的类型形参：单字母名（T/U/V/A…）、无类型实参、既非内建类型也非用户声明类型。
 * 仅用于默认值位的宽松比对，避免 emptyList[T]() 这类泛型零参构造误报类型不符。 */
internal fun Checker.isUnboundTypeParam(t: Type): Boolean {
    val n = (t as? NamedType)?.name ?: return false
    return n.length == 1 && t.args.isEmpty() && n !in BASE_TYPES &&
        syms.structs[n] == null && syms.enums[n] == null && syms.aliases[n] == null
}

internal fun Type.isNumeric(): Boolean = name in setOf("Nat", "Int", "Rat") && args.isEmpty()

/** v2.1：Nothing 底类型（发散/不可达值；与任何类型相容） */
internal fun Type.isNothing(): Boolean = name == "Nothing" && args.isEmpty()
