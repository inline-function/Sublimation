package sugared.functor.checker

import sugared.functor.ast.*

/** 覆盖律展开深度上限；超限把字段当不可展开类型，保守但保证终止 */
private const val COVER_DEPTH = 3

/** 覆盖律展开的安全阀：形状数超过此值即放弃（返回空 → 保守判非穷尽） */
private const val COVER_MAX_SHAPES = 64

// ============ 模式匹配（决策 29/43/46/64） ============

/** 覆盖律的一个"形状"：vars 是要 ∃ 封闭的变量，conjs 是合取项。 */
internal data class CoverShape(val vars: List<String>, val conjs: List<Prop>)

/**
 * 枚举覆盖律（决策 49/64）：按 when 主题的**具体类型**就地深展开，返回析取式。
 * 不用全局 `∀b. b:E→…`——泛型枚举的全局律会因类型实参不匹配（Optional vs Optional<Nat>）
 * 导致 MP 触发不了（工程规范 I-3）；就地生成天然带正确类型，无需 ∀/MP。
 * 与分支命题的 ∃ 变量名不同，靠 PropLogic.alphaEq 匹配（I-5）。
 */
internal fun Checker.enumCoverageDisj(subj: Expr, subjT: Type): Prop? {
    if (!syms.enums.containsKey(subjT.name)) return null
    covCounter = 0
    val shapes = coverShapes(subj, subjT, COVER_DEPTH)
    if (shapes.isEmpty()) return null
    val disjuncts = shapes.map { s ->
        val body = s.conjs.reduce { a, b -> PAnd(a, b) }
        if (s.vars.isEmpty()) body else PExists(s.vars, body)
    }
    return disjuncts.reduce { a, b -> POr(a, b) }
}

/**
 * 递归生成枚举类型的覆盖形状（决策 64：嵌套存在展开）。
 *
 * 关键设计（I-14）：内层形状若只给出一条 `v = term` 等式，就把 term **直接代入**外层项，
 * 而不是与外层等式合取。这样生成结果与 bindPattern 的 ∃ 形态**同构**，
 * 现有 alphaEq + disjSubset 就能匹配嵌套模式，无需给证明器加 ∃-消去规则（Prop.kt 零改动）。
 *
 * 例：Optional<Optional<Nat>> →
 *   ∃c. o=Some(Some(c))  ∨  o=Some(None)  ∨  o=None
 *
 * v1 限制：多字段构造子不深展开（整体一个 ∃）；字段类型不是枚举时停止展开；
 * 超过 COVER_DEPTH 层停止；形状数超过 COVER_MAX_SHAPES 放弃。
 * 以上都只会导致"保守判非穷尽"，绝不会误判穷尽（健全性方向正确）。
 */
internal fun Checker.coverShapes(subj: Expr, ty: Type, depth: Int): List<CoverShape> {
    val ed = findVisibleEnum(ty.name) ?: return emptyList()
    val sub = ed.theory.filterIsInstance<TypeParam>().map { it.name }.zip(ty.args).toMap()
    val out = ArrayList<CoverShape>()
    for (c in ed.ctors) {
        // 零元构造子：直接等式
        if (c.fields.isEmpty()) {
            out += CoverShape(emptyList(), listOf(PAtom("=", listOf(subj, NameRef(c.name)))))
            if (out.size > COVER_MAX_SHAPES) return emptyList()
            continue
        }
        val ft = c.fields[0].substT(sub)
        val v = "__cov${covCounter++}"
        val vRef = NameRef(v)
        // 多字段构造子（如 Pair(a,b)）：v1 不深展开，字段一律当作未展开变量。
        // 单字段构造子的深展开必须整体代入，故与多字段分支分开处理。
        if (c.fields.size != 1) {
            val vs = c.fields.indices.map { if (it == 0) v else "__cov${covCounter++}" }
            out += CoverShape(vs, listOf(PAtom("=", listOf(subj,
                CallExpr(NameRef(c.name), vs.map { NameRef(it) })))))
            if (out.size > COVER_MAX_SHAPES) return emptyList()
            continue
        }
        val wrap = { inner: Expr -> CallExpr(NameRef(c.name), listOf(inner)) }
        if (depth > 0 && findVisibleEnum(ft.name) != null) {
            val inner = coverShapes(vRef, ft, depth - 1)
            if (inner.isEmpty()) {
                out += CoverShape(listOf(v), listOf(PAtom("=", listOf(subj, wrap(vRef)))))
            } else {
                for (s in inner) {
                    val single = s.conjs.singleOrNull()
                    // 类型判定必须直接写在 if 条件里，Kotlin 才会把 single 智能转换成 PAtom。
                    // 先算 Boolean 再在分支里用 single!! 是编译不过的（Prop 上没有 terms 成员）。
                    if (single is PAtom && single.head == "=" && single.terms.size == 2 &&
                        single.terms[0] is NameRef && (single.terms[0] as NameRef).name == v
                    ) {
                        // 内层就是 `v = term`：代入外层，v 不再需要量化
                        out += CoverShape(s.vars, listOf(PAtom("=", listOf(subj, wrap(single.terms[1])))))
                    } else {
                        out += CoverShape(s.vars + v, s.conjs + PAtom("=", listOf(subj, wrap(vRef))))
                    }
                    if (out.size > COVER_MAX_SHAPES) return emptyList()
                }
            }
        } else {
            out += CoverShape(listOf(v), listOf(PAtom("=", listOf(subj, wrap(vRef)))))
        }
        if (out.size > COVER_MAX_SHAPES) return emptyList()
    }
    return out
}

/**
 * when 语义：
 *  - 每分支产生**分支命题**（指导§63）：字面/构造子 → `subj = pat`（∃ 封闭绑定变量），
 *    `is T` → `subj : T`，else/_ → ⊤
 *  - 顺序敏感：后分支 Γ 含前面所有分支命题的否定（指导§66）
 *  - 穷尽 = `Γ ⊢* p₁ ∨ … ∨ pₙ`（决策 43，析取恒真）
 *  - **穷尽性是提示不是要求**（决策 66）：判不出 → 值类型 unsealedT（默认按 Null 处理）；
 *    只有主题**有覆盖律**（名义枚举）却覆盖不全才发 W-NON-EXHAUSTIVE；无覆盖律的主题
 *    （元组/函数类型等）判不出不是用户写错 → 静默退化不警告。流向非 Null 仍报 E-NON-SEALED-MATCH（§49）
 *  - 返回类型 = 分支类型 join（决策 37）
 * 注入分支帧的等式保持 ground（体内可用绑定变量）；返回给穷尽析取的命题才 ∃ 封闭（I-14）。
 */
internal fun Checker.checkWhen(e: WhenExpr, f: Frame, pure: Boolean): Type {
    val subjT = checkExpr(e.subject, f, pure)
    val subjRef: Expr = e.subjectBinding?.let { NameRef(it) } ?: e.subject
    if (e.subjectBinding != null) {
        f.declareVar(e.subjectBinding, subjT)
        if (isPureValue(e.subject)) f.inject(PAtom("=", listOf(subjRef, e.subject)))
        f.inject(PAtom(":", listOf(subjRef, typeToExpr(subjT))))
    }
    val branchProps = ArrayList<Prop>()
    var lastT: Type? = null
    var sawCatchAll = false
    for (arm in e.arms) {
        val nf = Frame(f)
        branchProps.forEach { nf.inject(PNot(it)) }     // 顺序否定累积
        val bp0 = bindPattern(arm.pattern, subjRef, subjT, nf)
        val g = arm.guard
        val bp = if (g != null) {
            nf.inject(bp0)                              // 守卫内可见分支命题
            checkExpr(g, nf, pure)
            if (bp0 is PTop) PropLogic.fromExpr(g) else PAnd(bp0, PropLogic.fromExpr(g))
        } else bp0
        if (bp is PTop && g == null) sawCatchAll = true else branchProps += bp
        val bt = checkExpr(arm.body, nf, pure)
        lastT = if (lastT == null) bt else TypeInfer.unify(lastT, bt) ?: lastT
    }
    // 决策 66（用户 2026-10-06 拍板）：**穷尽性不是要求**——判不出穷尽的 when 就按默认值 Null 处理。
    // 分两种情形：
    //  ①主题有覆盖律（名义枚举）而仍判不出 → 发 W-NON-EXHAUSTIVE 警告，提示"可以补分支"；
    //  ②主题无覆盖律（元组/函数类型等结构化类型，判穷尽需等式合同推理，超 v1 片段）→ 静默退化，
    //    不打扰用户：这不是用户写错，而是编译器判不出。
    // 两种情形都退化为 unsealedT（Null）；流向非 Null 上下文仍由 E-NON-SEALED-MATCH 兜住（决策 49）。
    val cov = if (subjT is NamedType && !subjT.isSynthetic()) enumCoverageDisj(subjRef, subjT) else null
    var exhaustive = sawCatchAll || branchProps.isEmpty() || run {
        val goal = branchProps.reduce { a, b -> POr(a, b) }
        val premises = if (cov != null) f.allFacts() + cov else f.allFacts()
        proveOrDeep(premises, goal)
    }
    if (!exhaustive && cov != null && bindingCovered(e.arms, subjT)) exhaustive = true
    if (!exhaustive) {
        if (cov != null)
            d.warn("W-NON-EXHAUSTIVE", e.pos, "when 非穷尽（缺 else 或覆盖不全），作为表达式时值类型退化为 Null")
        return unsealedT()
    }
    return lastT ?: namedT("Null", emptyList())
}

/**
 * 兜底穷尽判定（假阳性修复）：绑定式构造子分支 `C(x1,…,xn)`（实参全为裸绑定/通配、无守卫）
 * 在值空间上**恒覆盖**构造子 C 的全部值——与字段类型无关，无需证明器。
 * 例：`when(o: Optional[List[Nat]]) { Some(xs) -> …  None -> … }`——coverShapes 深展开 List
 * （Cons 两字段）产出 `o=Some(Cons(..))` 等细分形状，而绑定式 `Some(xs)` 产生的 ∃ 形状
 * （∃xs. o=Some(xs)）与之不同构，证明器缺 ∃ 泛化桥接不了 → 原判假阳性警告。
 * 按构造子名逐一看则 Some/None 都有绑定式分支，穷尽成立。
 * 健全性：嵌套模式（`Some(Cons(h,t))`）实参不全为 PatBind → 不吸收该构造子；
 * 某构造子缺绑定式分支 → 判定不通过，仍走严格证明器路径——绝不会把 partial when 误判穷尽。
 */
private fun Checker.bindingCovered(arms: List<WhenArm>, subjT: Type): Boolean {
    val ed = findVisibleEnum(subjT.name) ?: return false
    val covered = HashSet<String>()
    for (arm in arms) {
        if (arm.guard != null) continue                     // 守卫缩小匹配集，吸收不成立
        val p = arm.pattern
        // 顶层必须是构造子模式；实参全为"吸收字段值"形态——
        //  PatBind（`_`/绑定）；或 parser 把单标识符解析成的 PatCtor(n,[])（879-892 行「与无参
        //  构造子同形，语义层判定」），当 n 不是已知构造子时 bindPattern 退化为绑定（210-211 行）。
        //  已知构造子（如 None/Nil）是真正匹配构造子值，不能算绑定。
        if (p is PatCtor && p.args.all { a ->
                a is PatBind || (a is PatCtor && a.args.isEmpty() && findVisibleCtor(a.name) == null)
            }) covered += p.name
    }
    return ed.ctors.all { it.name in covered }
}

/** 绑定模式：返回该分支命题（∃ 封闭），并把解构变量注入分支帧 */
internal fun Checker.bindPattern(p: Pattern, subj: Expr, subjT: Type, nf: Frame): Prop {
    // P9（决策 44，递归停机）：登记解构绑定变量的血缘——模式里绑定的名字是 subj（若为变量引用）的语法子项。
    // `when(xs) { Cons(_, t) -> f(t) }` ⇒ structSub[t] = xs，递归自检查由此放行真实的结构递归。
    // 注意：变量引用统一是 NameRef（VarExpr 仅 lambda 参数等场景），两形态都要认。
    val subjVarName: String? = when (subj) {
        is NameRef -> subj.name
        is VarExpr -> subj.name
        else -> null
    }
    val binds = collectPatBinds(p)
        if (subjVarName != null && curFunName != null) {
        for (b in binds) {
            structSub[b] = subjVarName
        }
    }
    return when (p) {
    is PatElse -> PTop
    is PatBind -> {
        if (p.name != "_") {
            nf.declareVar(p.name, subjT)
            nf.inject(PAtom("=", listOf(subj, NameRef(p.name))))
        }
        PTop                                     // 绑定/通配等价恒真分支
    }
    is PatLit -> {
        checkExpr(p.value, nf, pure = true)
        PAtom("=", listOf(subj, p.value))
    }
    is PatIs -> {
        checkTypeResolvable(p.type, emptyList())
        if (subjT.isNominal() && !typeLooseEq(subjT, p.type))
            d.error("E-TYPE-MISMATCH", p.pos, "is ${p.type.render()} 与主题类型 ${subjT.render()} 冲突")
        p.bind?.let { nf.declareVar(it, p.type) }
        PAtom(":", listOf(subj, typeToExpr(p.type)))
    }
    is PatTuple -> {
        // 元组解构（T3）：注入 subj = (p1,p2)；分量递归绑定
        val tup = subjT as? TupleType
        if (tup == null)
            d.error("E-TYPE-MISMATCH", p.pos, "对非元组类型 ${subjT.render()} 使用元组模式")
        else if (tup.items.size != p.items.size)
            d.error("E-TYPE-MISMATCH", p.pos, "元组模式有 ${p.items.size} 个分量，类型 ${subjT.render()} 要求 ${tup.items.size} 个")
        else bindTupleVars(p.items, tup, nf)
        val (term, vars) = patternTerm(p)
        val eq = PAtom("=", listOf(subj, term))
        nf.inject(eq)
        if (vars.isEmpty()) eq else PExists(vars, eq)
    }
    is PatCtor -> {
        val ci = findVisibleCtor(p.name)
        if (ci == null) {
            // 未知构造子：可能是绑定变量（无参形态），退化为具名绑定
            if (p.args.isEmpty()) nf.declareVar(p.name, subjT)
            else d.error("E-UNBOUND-NAME", p.pos, "未知构造子 ${p.name}")
            PTop
        } else {
            if (ci.fields.size != p.args.size)
                d.error("E-TYPE-MISMATCH", p.pos, "构造子 ${p.name} 期望 ${ci.fields.size} 个模式实参，实际 ${p.args.size}")
            val sub = ci.enum.theory.filterIsInstance<TypeParam>().map { it.name }
                .zip(subjT.args).toMap()
            val fieldTypes = ci.fields.map { it.substT(sub) }
            // 递归绑定嵌套模式的变量（Some(Some(y)) 的 y 也要按内层字段类型入帧）
            p.args.forEachIndexed { i, ap -> if (i < fieldTypes.size) bindVars(ap, fieldTypes[i], nf) }
            // ground 等式入分支帧：体内可用绑定变量，单射可推分量等式
            val (term, vars) = patternTerm(p)
            val eq = PAtom("=", listOf(subj, term))
            nf.inject(eq)
            nf.inject(PAtom(":", listOf(subj, typeToExpr(subjT.takeUnless { it.isSynthetic() }
                ?: namedT(ci.enum.name, emptyList())))))
            // 返回命题 ∃ 封闭全部量化位点（I-14），与覆盖律形状同构
            if (vars.isEmpty()) eq else PExists(vars, eq)
        }
    }
}
}

/**
 * P9（决策 44）：收集模式子树里所有会绑定变量的名字（PatBind 非 `_` + PatIs 的 bind）。
 * `_` 不登记（不构成可引用的子项链）。
 */
internal fun Checker.collectPatBinds(p: Pattern): List<String> = when (p) {
    is PatBind -> if (p.name == "_") emptyList() else listOf(p.name)
    is PatIs -> listOfNotNull(p.bind?.takeIf { it != "_" })
    is PatTuple -> p.items.flatMap { collectPatBinds(it) }
    is PatCtor -> {
        // 无参裸名模式（如 Cons(_, t) 的 t）解析为 PatCtor 空实参——与 bindPattern 同判据：
        // 已知零元构造子（Nil）非绑定；未知 → 绑定变量（Cons 的 t）
        if (p.args.isEmpty())
            if (p.name == "_" || findVisibleCtor(p.name) != null) emptyList()
            else listOf(p.name)
        else p.args.flatMap { collectPatBinds(it) }
    }
    else -> emptyList()
}

/**
 * 模式 → (项, 量化变量表)。`_` 也必须分配一个新鲜量化名：
 * 否则 `Some(_)` 的分支命题是 ground 的 `o = Some(_)`，与覆盖律 `∃c. o = Some(c)`
 * 不同构，alphaEq 匹配不上 → 误判非穷尽（I-14，第 1 轮回归修复）。
 * 未知裸名（绑定变量）同样进量化表；已知零元构造子无量化位点。
 */
internal fun Checker.patternTerm(p: Pattern): Pair<Expr, List<String>> = when (p) {
    is PatBind ->
        if (p.name == "_") { val v = "__w${covCounter++}"; NameRef(v) to listOf(v) }
        else NameRef(p.name) to listOf(p.name)
    is PatLit -> p.value to emptyList()
    is PatIs -> NameRef("_") to emptyList()
    is PatElse -> NameRef("_") to emptyList()
    is PatCtor -> {
        if (p.args.isEmpty()) {
            if (syms.ctors.containsKey(p.name)) NameRef(p.name) to emptyList()
            else NameRef(p.name) to listOf(p.name)        // 裸名绑定变量
        } else {
            val parts = p.args.map { patternTerm(it) }
            CallExpr(NameRef(p.name), parts.map { it.first }) to parts.flatMap { it.second }
        }
    }
    is PatTuple -> {
        val parts = p.items.map { patternTerm(it) }
        TupleExpr(parts.map { it.first }, p.pos) to parts.flatMap { it.second }
    }
}

/** 递归收集元组/构造子模式里的绑定变量并按分量类型入帧（T3） */
internal fun Checker.bindTupleVars(items: List<Pattern>, tup: TupleType, nf: Frame) {
    items.forEachIndexed { i, ap ->
        bindVars(ap, tup.items.getOrNull(i) ?: syntheticT("元组分量"), nf)
    }
}

/**
 * 递归把模式里的绑定变量按期望类型注入帧（支持嵌套 `Some(Some(y))`）。
 * 裸名（非构造子）与 PatBind 视为变量；带参 PatCtor 递归到字段类型。
 */
internal fun Checker.bindVars(ap: Pattern, expect: Type, nf: Frame) {
    when (ap) {
        is PatBind -> if (ap.name != "_") nf.declareVar(ap.name, expect)
        is PatIs -> ap.bind?.let { nf.declareVar(it, ap.type) }
        is PatTuple -> {
            val tup = expect as? TupleType
            ap.items.forEachIndexed { i, inner ->
                bindVars(inner, tup?.items?.getOrNull(i) ?: syntheticT("元组分量"), nf)
            }
        }
        is PatCtor -> {
            val ci = findVisibleCtor(ap.name)
            if (ci == null) { if (ap.args.isEmpty() && ap.name != "_") nf.declareVar(ap.name, expect); return }
            val sub = ci.enum.theory.filterIsInstance<TypeParam>().map { it.name }
                .zip(expect.args).toMap()
            val fieldTypes = ci.fields.map { it.substT(sub) }
            ap.args.forEachIndexed { i, inner -> if (i < fieldTypes.size) bindVars(inner, fieldTypes[i], nf) }
        }
        else -> {}
    }
}

/**
 * P5（决策 81）：跨可见模块解析构造子（本模块优先，其次按当前模块可见路径集合）。
 * 补完 P0 文档化的限制「跨模块 when 模式」——调用点解构 `Ok(x)`/`Err(e)`（stdlib 的 Result）需要。
 * 歧义（多个可见模块同名构造子）取可见集合中第一个命中的（文档记限制；most visible 语义留后续）。
 */
internal fun Checker.findVisibleCtor(name: String): CtorInfo? {
    syms.ctors[name]?.let { return it }
    for (path in visiblePaths) {
        if (path == modulePath) continue
        allSymbols[path]?.ctors?.get(name)?.let { return it }
    }
    return null
}

/** P5：跨可见模块解析枚举（coverShapes 按 subject 类型名找枚举，同理）。返回 null 走「保守判非穷尽」。 */
internal fun Checker.findVisibleEnum(name: String): EnumDecl? {
    syms.enums[name]?.let { return it }
    for (path in visiblePaths) {
        if (path == modulePath) continue
        allSymbols[path]?.enums?.get(name)?.let { return it }
    }
    return null
}
