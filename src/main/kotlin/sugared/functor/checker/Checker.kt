package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 语义分析器（M3）。两大子模块：类型推导器（TypeInfer）+ 自动定理证明器（Prover）。
 * 职责：本文件只保留"分派层"（run/checkExpr/checkStmt/checkBin）；
 * 具体模块见——
 *  Diffusion.kt（Frame/Γ 与证明入口）、Symbols.kt（收集与 prelude）、
 *  ImplCheck.kt（impl 完整性）、WhenCheck.kt（when 穷尽性）、
 *  CallCheck.kt（调用/供给/下文注入）、Purity.kt（纯度，占位）。
 * 宽松策略：推导不出确切类型（synthetic）时不误报，只对有证据的类型错误报错。
 */
class Checker(
    internal val file: FileAst,
    internal val maxSatDepth: Int = 6,
    /** P0（M1）：当前模块的绝对路径；单文件模式为空串 */
    internal val modulePath: String = "",
    /** P0（M1）：全模块符号表（键=绝对路径）；单文件模式为空 */
    internal val allSymbols: Map<String, Symbols> = emptyMap(),
    /** P0：模块树驱动（目录模式非空；负责 I-20 可见路径集合与挂载解析） */
    internal val moduleTree: ModuleTreeDriver? = null,
) {
    internal val d = DiagBag()
    internal val syms = Symbols()
    internal val prover = Prover(maxRounds = 3, maxDepth = maxSatDepth)
    /** 全局事实（跨帧可见）：当前为 pure<name>（决策 34：未标 @unpure 的函数自动纯） */
    internal val globalFacts = ArrayList<Prop>()
    /** 覆盖律展开的临时变量计数（每次 enumCoverageDisj 调用前重置） */
    internal var covCounter = 0
    /** 当前是否处于 unchecked 上下文（决策 59：Any 结构体准入判据） */
    internal var uncheckedDepth = 0
    /** 型类方法调用的字典留痕（决策 60，T5；O2 改键）：**调用点实例** → 字典名。
     *  IdentityHashMap 而非按名键——同名方法对不同实参类型各有 impl 时，按名会互相覆盖；
     *  CallExpr 是 data class，普通 HashMap 的结构相等也会让"长得一样"的两处调用互相污染。 */
    internal val dictHits = java.util.IdentityHashMap<CallExpr, String>()
    /**
     * P6（决策 82）：当前函数的约束字典槽——类型参数名 → (型类, 槽 JS 名)。
     * `fun f[T: Show]` 体内 `x.show()`（x: T）由槽解析：槽 d_Show_T 是 f 的隐藏首参。
     * checkFun 设置、体检查完清理（不跨函数泄漏）。
     */
    internal var funConsSlots: Map<String, List<Pair<String, String>>> = emptyMap()
    /** P6：约束槽方法调用留痕（调用点实例 → 槽名），codegen 生成 `$slot.m($slot, self, ...)` */
    internal val consHits = java.util.IdentityHashMap<CallExpr, String>()
    /** P6：带约束泛型函数的调用点字典实参留痕（调用点实例 → 按声明顺序的字典名），codegen 前插实参 */
    internal val dictSubHits = java.util.IdentityHashMap<CallExpr, List<String>>()
    /** P8（决策 84）：命名参数规范化后的实参留痕（调用点实例 → 按形参声明顺序的实参列表），codegen 重排 */
    internal val namedArgOrder = java.util.IdentityHashMap<CallExpr, List<Expr>>()
    /** v1.1 集合方法糖留痕：`xs.map(f)` → 同名自由函数 `map(xs, f)`（接收者前置）。
     *  键 = 调用点实例，值 = 重排后的实参列表（接收者 + 原实参），codegen 依此生成。 */
    internal val methodSugarArgs = java.util.IdentityHashMap<CallExpr, List<Expr>>()
    /** P9（决策 44，用户授权 Max 倾向）：递归停机上下文——当前函数名/形参名/解构血缘 */
    internal var curFunName: String? = null
    internal var curFunParams: List<String> = emptyList()
    /** P9：变量血缘（子 → 父）：when 解构绑定变量 → 主题变量（`when(xs){Cons(_,t)->...}` ⇒ t→xs）。checkFun 进出清空恢复。 */
    internal var structSub: MutableMap<String, String> = LinkedHashMap()
    /**
     * P0（M8）：全局符号引用的 JS 名留痕（**引用点实例** → mangle 后的完整名字）。
     * 不再依赖"codegen 猜测裸名=本模块符号"：Checker 解析时即知目标模块，
     * 从而同模块调用在非根模块也要加前缀、跨模块调用加目标模块前缀、局部变量不加。
     * 键 = 引用表达式节点（NameRef / FieldExpr 链），值 = moduleJsName(模块, 符号)；
     * 限定方法调用为 MODULE_METHOD_MARKER（字典分发的分支选择用）。
     */
    internal val moduleHits = java.util.IdentityHashMap<Expr, String>()
    /** I-20：当前模块的完整可见路径集合（目录模式计算；单文件模式空集不用过滤） */
    internal val visiblePaths: Set<String>
        get() = if (moduleTree == null) emptySet() else moduleTree.visiblePathsOf(modulePath)

    /** 当前函数的形参类型序列（$n 参数引用用，决策 65） */
    internal var paramTypes: List<Type> = emptyList()
    /** return 检查（决策 75，第四轮返工定稿=flow 传播模型）：
     *  funRetType = 当前是否在具名函数/方法的语句流内（checkFun 设置）。
     *  `return` 合法 ⟺ flow=true && funRetType≠null：
     *   - 合法（Kotlin 口径）：函数体块语句、**语句位** if/when 的分支块（可嵌套，codegen 展平成
     *     真正的 JS 控制语句，return 天然退出函数）；
     *   - 非法（E-RETURN-OUTSIDE）：值位（var 初值/实参/尾表达式/值位 if·when 分支）、lambda 体。
     *  flow 由 checkExpr/checkStmt 参数显式传播，调用点默认 flow=false（值位），
     *  仅 BlockExpr 语句列表、IfExpr 分支、WhenExpr 分支体三处继承上游 flow。 */
    internal var funRetType: Type? = null
    /** 体内是否出现过合法显式 return（出现则块类型核对让位给逐 return 核对） */
    internal var bodyHasReturn = false
    /** v1.1：lambda 返回类型栈——非空即"当前在 lambda 体内"（lambda 内 return 合法，返回该 lambda） */
    internal val lambdaRetTypes = ArrayList<Type>()

    /** P6（决策 82）：类型名在可见模块中可解析（跨模块类型引用——裸名 `List`/`Result` 在类型位置可用，
 *  不只是 `stdlib.List` 限定形态）。P5 只补了 when 模式的构造子/枚举，这里补类型位置本身。 */
    internal fun typeVisible(name: String): Boolean {
        if (syms.baseOf(namedT(name)) != null) return true
        if (moduleTree == null) return false
        return visiblePaths.any { allSymbols[it]?.baseOf(namedT(name)) != null }
    }

    fun run(): DiagBag {
        collect()
        checkAllBodies()
        return d
    }

    // ============ 收集（决策 45 prelude + M2 多文件合并） ============

    /** 单文件模式：收集即本文件 */
    internal fun collect() { collectFrom(listOf(file)) }

    /** M2：同模块内所有文件合并收集——共享一个 Symbols（重名跨文件由 putIfAbsent 报 E-DUP-DECL） */
    internal fun collectFrom(files: List<FileAst>) {
        collectPrelude()
        collectDecls(files)
        finishCollect(files)
    }

    /** prelude 注册（Bool/Null/Optional）——每模块一次 */
    internal fun collectPrelude() {
        registerEnum(EnumDecl("Bool", emptyList(), listOf(Ctor("true", emptyList()), Ctor("false", emptyList()))))
        registerEnum(EnumDecl("Null", emptyList(), listOf(Ctor("null", emptyList()))))
        registerEnum(EnumDecl("Optional", listOf(TypeParam("T", null)),
            listOf(Ctor("None", emptyList()), Ctor("Some", listOf(namedT("T", emptyList()))))))
    }

    /** 阶段 A：符号登记（多模块先全树收集，供交叉引用） */
    internal fun collectDecls(files: List<FileAst>) {
        for (fa in files) for (e in fa.entries) if (e is DeclEntry) collectDecl(e.decl)
    }

    /** 阶段 B：类型可解析性 + impl 完整性 + pure 事实（全树符号齐后） */
    internal fun finishCollect(files: List<FileAst>) {
        for (fa in files) for (e in fa.entries) if (e is DeclEntry) checkDeclTypes(e.decl)
        checkImplsFrom(files)
        registerPureFacts()
    }

    /** 阶段 C：全部声明体 + 顶层语句（多文件=本模块合并后的一份 FileAst） */
    internal fun checkAllBodies() {
        for (e in file.entries) if (e is DeclEntry) checkDeclBody(e.decl)
        val top = Frame(null)
        for (e in file.entries) if (e is StmtEntry) checkStmt(e.stmt, top, pure = true)
    }

    // ============ 声明体 ============

    internal fun checkDeclBody(decl: Decl) {
        when (decl) {
            is FunDecl -> if (decl.body != null) checkFun(decl)
            is ClassDecl -> decl.members.forEach {
                // O2（决策 68）：型类成员 v1 只允许无体签名——self 类型在声明期未定
                if (it.body != null)
                    d.error("E-SELF-UNKNOWN", it.pos, "型类 ${decl.name} 的成员 ${it.name} 不能有函数体，体属于 impl")
            }
            // O2：impl 成员带隐式 self（决策 68，用户样本形态），方法体内可裸用自类型字段
            is ImplDecl -> {
                val selfT = syms.expand(decl.self)
                decl.members.forEach { if (it.body != null) checkFun(it, selfT) }
            }
            else -> {}
        }
    }

    /** 决策 65（T6）：收集表达式里出现的 $n（含位置，供诊断） */
    private object DollarScan {
        fun collect(e: Expr): List<Pair<Int, String>> {
            val out = ArrayList<Pair<Int, String>>()
            fun go(x: Expr) {
                when (x) {
                    is ParamRefExpr -> out.add(x.index to x.pos)
                    is BinExpr -> { go(x.left); go(x.right) }
                    is UniExpr -> go(x.operand)
                    is CallExpr -> { go(x.callee); x.args.forEach { go(it) } }
                    is InstExpr -> { go(x.target); x.terms.forEach { go(it) } }
                    is TupleExpr -> x.items.forEach { go(it) }
                    else -> {}
                }
            }
            go(e)
            return out
        }
    }

    /** 决策 65（T6）：把命题表达式里的 $n 换成参数名，并给提示级诊断；越界报错 */
    private fun rewriteDollarN(e: Expr, params: List<Param>, where: String): Expr {
        val m = DollarScan.collect(e)
        if (m.isEmpty()) return e
        for ((idx, pos) in m) {
            if (idx > params.size) d.error("E-PARAM-REF", pos, "${where}里的 \\$${idx} 越界（函数只有 ${params.size} 个形参）")
            else d.hint("H-PARAM-REF", pos, "${where}里的 \\$${idx} 可以直接使用参数名 ${params[idx - 1].name}")
        }
        return substDollarNExpr(e, params)
    }

    private fun substDollarNExpr(e: Expr, params: List<Param>): Expr = when (e) {
        is ParamRefExpr -> params.getOrNull(e.index - 1)?.let { NameRef(it.name) } ?: e
        is BinExpr -> e.copy(left = substDollarNExpr(e.left, params), right = substDollarNExpr(e.right, params))
        is UniExpr -> e.copy(operand = substDollarNExpr(e.operand, params))
        is CallExpr -> e.copy(callee = substDollarNExpr(e.callee, params), args = e.args.map { substDollarNExpr(it, params) })
        is InstExpr -> e.copy(target = substDollarNExpr(e.target, params), terms = e.terms.map { substDollarNExpr(it, params) })
        is TupleExpr -> e.copy(items = e.items.map { substDollarNExpr(it, params) })
        else -> e
    }

    internal fun checkFun(fn: FunDecl, selfT: Type? = null) {
        val pure = "unpure" !in fn.annotations
        val f = Frame(null)
        val tps = tpNamesOf(fn)
        // P9（决策 44）：设置递归停机上下文（函数名/形参/解构血缘按函数隔离）
        val savedFunName = curFunName; val savedFunParams = curFunParams; val savedStructSub = structSub
        curFunName = fn.name
        curFunParams = fn.params.map { it.name }
        structSub = LinkedHashMap()
        // P6（决策 82）：设置约束字典槽——`fun f[T: Show]` 体内 `x.show()`（x: T）指向槽 d_Show_T
        val savedConsSlots = funConsSlots
        funConsSlots = fn.theory.filterIsInstance<TypeParam>()
            .filter { it.constraint != null }
            .associate { tp -> tp.name to listOf(tp.constraint!! to "d_${tp.constraint}_${tp.name}") }
        // O2（决策 68，用户拍板隐式 self）：方法体内先注入 self 与结构体字段，
        // 再声明形参（参数名与字段同名时后者覆盖 = 参数遮蔽字段，发提示 H-FIELD-SHADOW）。
        if (selfT != null) injectSelfAndFields(f, selfT, fn)
        // $n 参数引用（决策 65，T6）：记录形参类型序列供体内部解析
        val savedParamTypes = paramTypes
        paramTypes = fn.params.map { syms.expand(it.type) }
        fn.params.forEach { p ->
            checkTypeResolvable(p.type, tps)
            // 决策 61：@tuple 形参在函数体内即元组本身（类型实参由调用处匹配，声明期无替换表）
            val pt = syms.expand(p.type)
            // O2：参数名遮蔽了 self 字段 → 提示（遮蔽合法，但要让用户知道裸名从此指向参数）
            if (selfT != null && p.name != "self" && p.name in structFieldNames(selfT))
                d.hint("H-FIELD-SHADOW", fn.body?.pos ?: "", "参数 ${p.name} 遮蔽了 ${selfT.render()} 的同名字段，体内裸用 ${p.name} 将指向参数")
            f.declareVar(p.name, pt)
            f.inject(PAtom(":", listOf(NameRef(p.name), typeToExpr(pt))))
        }
        // 决策 65（T6）：上/下文里的 $n 替换为参数名，并发提示级诊断；越界报错。
        val preRewritten = fn.preps.map { rewriteDollarN(it, fn.params, "前件") }
        val postRewritten = fn.posts.map { rewriteDollarN(it, fn.params, "后件") }
        preRewritten.forEach { f.inject(PropLogic.fromExpr(it)) }
        if (fn.body == null) { paramTypes = savedParamTypes; return }
        // v1.1 返回类型：显式标注取之；省略时——块体 = Null（Kotlin Unit 语义），表达式体 = 推导。
        val rtDeclared = fn.retType?.let { syms.expand(it) }
        val isExprBody = fn.body !is BlockExpr
        val rt = rtDeclared ?: namedT("Null", emptyList())
        // 决策 75（flow 传播）：体是块 → 语句流 flow=true（体内及语句位 if/when 分支可 return）；
        // 单一表达式体无语句位，flow=false。
        val savedFunRet = funRetType; val savedHasRet = bodyHasReturn
        funRetType = rt; bodyHasReturn = false
        val t = checkExpr(fn.body, f, pure, flow = fn.body is BlockExpr)
        val sawRet = bodyHasReturn
        // v1.1：表达式体省略返回类型 → 推导生效（供调用点反推；递归自引用时保留 Null/synthetic）
        val effRt = if (fn.retType == null && isExprBody && !t.isSynthetic()) {
            syms.inferredRets[fn.name] = t; t
        } else rt
        funRetType = savedFunRet; bodyHasReturn = savedHasRet
        // v1.1 Kotlin 语义：显式非 Null 返回类型的**块式**函数必须所有路径 return（不再隐式返回尾表达式）
        if (fn.body is BlockExpr && rtDeclared != null && rtDeclared.name != "Null" && !diverges(fn.body))
            d.error("E-MISSING-RETURN", fn.body.pos,
                "函数 ${fn.name} 声明返回 ${rtDeclared.render()}，块体未在所有路径 return（Kotlin 语义：请写 return，或改用 `= 表达式` 表达式体）")
        else if (sawRet) {
            // 显式 return 路径：块尾类型核对让位，逐 return 已核对（E-RETURN-TYPE）；
            // 块本身按表达式用（如 `fun f(): Nat = { return 1 }` 的体）时值为 Null，不再要求与 rt 一致
        } else
        if (t.isUnsealed() && effRt.name != "Null")
            d.error("E-NON-SEALED-MATCH", fn.body.pos,
                "函数 ${fn.name} 作为表达式的模式匹配没有密封（声明返回 ${effRt.render()}，但非穷尽 when 值类型为 Null）；补全分支或加 else")
        else if (!t.isSynthetic() && !typeLooseEq(t, effRt) &&
            !(effRt.name == "Null" && fn.body is BlockExpr))
            d.error("E-TYPE-MISMATCH", fn.body.pos, "函数 ${fn.name} 体类型 ${t.render()} 与声明 ${effRt.render()} 不符")
        val tail = dollarOf(fn.body)
        for (post in postRewritten) {
            val goal = PropLogic.substDollar(PropLogic.fromExpr(post), tail)
            if (!proveOrDeep(f.allFacts(), goal))
                d.error("E-POST-UNPROVEN", fn.body.pos, "函数 ${fn.name} 未证明下文 ${PropLogic.render(goal)}（需 unchecked.axiom 或供给链）")
        }
        paramTypes = savedParamTypes
        funConsSlots = savedConsSlots
        curFunName = savedFunName; curFunParams = savedFunParams; structSub = savedStructSub
    }

    /** O2（决策 68）：结构体自类型的裸字段名集合（枚举/基类型无裸字段） */
    internal fun structFieldNames(t: Type): Set<String> =
        (t as? NamedType)?.let { syms.structs[it.name] }?.fields?.map { it.name }?.toSet() ?: emptySet()

    /** O2（决策 68，隐式 self）：方法体帧注入 self 与结构体字段（字段裸用 = self.字段） */
    private fun injectSelfAndFields(f: Frame, selfT: Type, fn: FunDecl) {
        f.declareVar("self", selfT)
        f.inject(PAtom(":", listOf(NameRef("self"), typeToExpr(selfT))))
        val st = (selfT as? NamedType)?.let { syms.structs[it.name] }
        st?.fields?.forEach { fp ->
            val ft = syms.expand(fp.type)
            f.declareVar(fp.name, ft)
            // 字段裸名 ⟝ self.field —— 供智能转换与等式推理使用
            f.inject(PAtom("=", listOf(NameRef(fp.name), FieldExpr(NameRef("self"), fp.name))))
            f.inject(PAtom(":", listOf(NameRef(fp.name), typeToExpr(ft))))
        }
    }

    // ============ 语句 ============

    /** 返回该语句的"值类型"，供块体做 Nothing 不可达追踪（A5）。flow=语句流（return 合法区）。 */
    internal fun checkStmt(s: Stmt, f: Frame, pure: Boolean, flow: Boolean = false): Type {
        return when (s) {
            is VarStmt -> {
                val t0 = checkExpr(s.value, f, pure)   // 初值是值位（flow 默认 false）
                var t = t0
                if (s.type != null) {
                    checkTypeResolvable(s.type, emptyList())
                    val want = syms.expand(s.type)   // 别名展开（决策 30）
                    // v1.1：标注类型即变量类型（Kotlin 语义）；unify 只做兼容性检查，
                    // 避免泛型初值（如 setEmpty(): Set[T]）把未绑定类型参数残留进变量类型
                    if (TypeInfer.unify(t, want) == null)
                        d.error("E-TYPE-MISMATCH", s.pos, "var ${s.name}: 标注 ${s.type.render()} 与初值 ${t.render()} 不可统一")
                    else t = want
                }
                f.declareVar(s.name, t)
                val mut = "mut" in s.annotations
                if (mut) f.declareMut(s.name)
                // 指导§42：var n = 0 注入 n = 0 与 n : T；
                // 决策35：@mut 变量仅保有自反相等，非自反相等论据（n = 初值）忽略不注入
                if (isPureValue(s.value) && !mut) {
                    f.inject(PAtom("=", listOf(NameRef(s.name), s.value)))
                }
                if (isPureValue(s.value) || mut) {
                    f.inject(PAtom(":", listOf(NameRef(s.name), typeToExpr(t))))
                }
                if (s.type == null) d.supplement("D-TYPE-INFERRED", s.pos, "var ${s.name} 推导为 ${t.render()}")
                t   // 声明即表达式：var 的值是初值（A3）
            }
            is AssignStmt -> {
                val lhs = (s.target as? NameRef)?.name
                if (lhs == null) d.error("E-ASSIGN-TARGET", s.pos, "赋值目标须为变量")
                else {
                    if (f.lookupVar(lhs) == null) d.error("E-UNBOUND-NAME", s.pos, "赋值目标 $lhs 未定义")
                    if (!f.isMut(lhs) && pure) d.error("E-IMMUT-ASSIGN", s.pos, "对不可变变量 $lhs 赋值（加 @mut 或 unchecked 逃逸）")
                }
                checkExpr(s.value, f, pure)
            }
            is AxiomStmt -> { s.props.forEach { f.inject(PropLogic.fromExpr(it)) }; namedT("Null", emptyList()) }
            is ByStmt -> {
                s.props.forEach { f.inject(PropLogic.fromExpr(it)) }
                d.supplement("D-BY", s.pos, "辅助策略注入 ${s.props.size} 条命题")
                namedT("Null", emptyList())
            }
            is UncheckedStmt -> { uncheckedDepth++; val r = checkStmt(s.inner, f, pure = false, flow); uncheckedDepth--; r }
            // 决策 75：return 合法 ⟺ 语句流（flow=true）且在具名函数/方法体内（funRetType≠null）；
            // 带值核对声明返回类型，裸形只许 Null 函数
            is ReturnStmt -> {
                val rt = funRetType
                // v1.1：lambda 体内 return 合法——返回当前 lambda（Kotlin 风格 `{ …; return v }` / `return@label`）
                if (lambdaRetTypes.isNotEmpty()) {
                    val lret = lambdaRetTypes.last()
                    if (s.expr != null) {
                        val t0 = checkExpr(s.expr, f, if (uncheckedDepth > 0) false else pure)
                        if (!t0.isSynthetic() && !lret.isSynthetic() && !typeLooseEq(lret, t0))
                            d.error("E-RETURN-TYPE", s.pos, "lambda 内 return 值类型 ${t0.render()} 与 lambda 返回 ${lret.render()} 不符")
                    }
                    return namedT("Nothing", emptyList())   // lambda 内 return 后语句不可达
                }
                if (rt == null || !flow)
                    d.error("E-RETURN-OUTSIDE", s.pos,
                        "return 只能出现在具名函数体或 lambda 体的语句流里（值位表达式非法）")
                else when {
                    s.expr == null && rt.name != "Null" ->
                        d.error("E-RETURN-TYPE", s.pos, "裸 return 要求函数返回 Null，实际声明 ${rt.render()}")
                    s.expr != null -> {
                        val t0 = checkExpr(s.expr, f, if (uncheckedDepth > 0) false else pure)   // 值是值位
                        if (!t0.isSynthetic() && !typeLooseEq(rt, t0))
                            d.error("E-RETURN-TYPE", s.pos, "return 值类型 ${t0.render()} 与声明 ${rt.render()} 不符")
                    }
                }
                bodyHasReturn = true
                namedT("Nothing", emptyList())   // return 不产生前流值——复用发散标记让块体判后续不可达
            }
            is ExprStmt -> checkExpr(s.expr, f, if (uncheckedDepth > 0) false else pure, flow = flow)
        }
    }

    internal fun isPureValue(e: Expr): Boolean = when (e) {
        is IntLit, is StrLit, is NameRef -> true
        is BinExpr -> e.op in setOf("+", "-", "*", "/", "=", "==", "!=", "<", ">", "<=", ">=", "&", "|", "∧", "∨",
            "u&", "u|", "u->", "u<->", "u!=") &&
            isPureValue(e.left) && isPureValue(e.right)
        is UniExpr -> isPureValue(e.operand)
        // P4（决策 80）：纯函数调用也视为纯值——`var x = f(5)` 需注入 `x = f(5)` 供等式推理
        // （§6.2 目标程序第一例；@unpure 函数结果不确定，仍不注入）。构造子/方法有独立注入通道，不进此分支。
        is CallExpr -> {
            val n = (e.callee as? NameRef)?.name ?: return false
            val fn = syms.findFun(n)
            if (fn != null) "unpure" !in fn.annotations
            else builtinPure(n) != null && builtinUnpure(n) == null
        }
        else -> false
    }

    // ============ 表达式 ============

    internal fun checkExpr(e: Expr, f: Frame, pure: Boolean, expect: Type? = null, flow: Boolean = false): Type = when (e) {
        is IntLit -> namedT("Nat", emptyList())
        is FloatLit -> namedT("Rat", emptyList())   // P10（决策 86）：浮点字面量 → Rat（IEEE double）
        is StrLit -> namedT("Str", emptyList())
        TopExpr, BotExpr, ReturnSym -> namedT("Bool", emptyList())
        is ParamRefExpr -> {
            // 决策 65（T6）：$n 只允许出现在前件/后件；函数体内出现即报错，请直接用参数名。
            d.error("E-PARAM-REF-IN-BODY", e.pos, "函数体内不能用 \$${e.index} 引用参数，请直接使用参数名")
            paramTypes.getOrNull(e.index - 1) ?: syntheticT("参数引用")
        }
        is NameRef -> f.lookupVar(e.name)
            ?: run {
                when {
                    syms.ctors.containsKey(e.name) -> {
                        // 零参构造子作值引用：带多态类型参数（None : Optional<T> 对任意 T）
                        moduleHits[e] = moduleJsName(modulePath, e.name)
                        val ed = syms.ctors.getValue(e.name).enum
                        namedT(ed.name, ed.theory.filterIsInstance<TypeParam>().map { syntheticT("类型参数${it.name}") })
                    }
                    syms.structs.containsKey(e.name) || syms.funs.containsKey(e.name) -> {
                        // P0：只对**本模块声明**的函数记录前缀（syms.funs 不含内建——内建是全局的，不 mangle，
                        // 否则非根模块里 `var f = concat` 会被当成 core__concat 产生 JS ReferenceError）
                        moduleHits[e] = moduleJsName(modulePath, e.name)
                        syntheticT("函数${e.name}")
                    }
                    else -> { d.error("E-UNBOUND-NAME", e.pos, "未定义名字 ${e.name}"); syntheticT("未定义") }
                }
            }
        is UniExpr -> {
            val ot = checkExpr(e.operand, f, pure)
            if (e.op == "-") ot else namedT("Bool", emptyList())   // 负号保数值类型，!/¬ 才是 Bool
        }
        is BinExpr -> checkBin(e, f, pure)
        is SymbolCallExpr -> { e.args.forEach { checkExpr(it, f, pure) }; namedT("Bool", emptyList()) }
        // v2.0 空安全（决策 88-92）
        is ElvisExpr -> { val l = checkExpr(e.left, f, pure); val r = checkExpr(e.right, f, pure); checkElvis(e, l, r) }
        is SafeCallExpr -> checkSafeCall(e, f, pure)
        is CastExpr -> { checkExpr(e.target, f, pure); checkCast(e) }
        is TypeTestExpr -> { checkExpr(e.target, f, pure); checkTypeTest(e) }
        is FieldExpr -> {
            // P0：跨模块限定引用 `core.gcd` / `c.Point`——纯名字链且首段非局部变量
            val qual = resolveQChain(e, f)
            if (qual != null) {
                if (qual.symbol.kind == QSymKind.MISSING) {
                    d.error("E-UNBOUND-NAME", e.pos, "模块 ${qual.node.absPath.ifEmpty { "/" }} 中无符号 ${qual.symbol.name}")
                    syntheticT("限定引用")
                } else {
                    moduleHits[e] = moduleJsName(qual.node.absPath, qual.symbol.name)
                    when (qual.symbol.kind) {
                        QSymKind.FUN -> syntheticT("函数${qual.node.absPath}.${qual.symbol.name}")
                        QSymKind.CTOR -> {
                            val ci = qual.symbol.syms.ctors.getValue(qual.symbol.name)
                            namedT(ci.enum.name, ci.enum.theory.filterIsInstance<TypeParam>().map { syntheticT("类型参数${it.name}") })
                        }
                        QSymKind.STRUCT, QSymKind.ENUM, QSymKind.CLASS -> syntheticT("类型${qual.symbol.name}")
                        QSymKind.METHOD -> syntheticT("方法${qual.symbol.name}")
                        QSymKind.MISSING -> syntheticT("限定引用")   // 已报错
                    }
                }
            } else {
                val t = checkExpr(e.target, f, pure)
                val st = syms.structs[t.name]
                if (st != null)
                    st.fields.firstOrNull { it.name == e.name }?.type?.substT(st.typeArgsSub(t))
                        ?: run { d.error("E-UNBOUND-NAME", e.pos, "结构体 ${t.name} 无字段 ${e.name}"); syntheticT("字段") }
                else {
                    // P5（决策 81）：内建类型的点号方法路径——`x.length` 按 `length(x)`（首实参即接收者）
                    // 定返回类型。此前一律回 synthetic「字段name」，使点号方法在泛型 lambda 里类型为占位
                    // （mapResult 的 U 反推失效、嵌套泛型调用泄漏类型变量）。首参类型不兼容则维持旧行为。
                    val bi = builtinPure(e.name) ?: builtinUnpure(e.name)
                    val ret = if (bi != null && bi.params.isNotEmpty() && typeLooseEq(bi.params[0].type, t))
                        (bi.retType ?: namedT("Null", emptyList()))
                    else null
                    ret ?: syntheticT("字段${e.name}")
                }
            }
        }
        is InstExpr -> { checkExpr(e.target, f, pure); e.terms.forEach { checkExpr(it, f, pure) }; namedT("Bool", emptyList()) }
        is CallExpr -> { if (uncheckedDepth > 0) checkCall(e, f, pure = false) else checkCall(e, f, pure) }
        is LambdaExpr -> {
            // T2 双向推导（决策 55/57）：期望 (A)=>B 时参数按 A 绑定、体核对 B；无期望则宽松 synthetic
            val nf = Frame(f)
            val ft = expect as? FunType
            if (ft != null && ft.params.size != e.params.size)
                d.error("E-TYPE-MISMATCH", e.pos, "lambda 有 ${e.params.size} 个参数，期望类型要求 ${ft.params.size} 个")
            e.params.forEachIndexed { i, pname ->
                nf.declareVar(pname, ft?.params?.getOrNull(i) ?: syntheticT("参数"))
            }
            val bt = run {
                lambdaRetTypes.add(ft?.ret ?: syntheticT("lambda返回"))
                val r = checkExpr(e.body, nf, if (uncheckedDepth > 0) false else pure)
                lambdaRetTypes.removeAt(lambdaRetTypes.size - 1)
                r
            }
            // P2（决策 78）：期望返回是**未解析的类型参数**（非 BASE_TYPES/非已声明类型/非 synthetic）时
            // 跳过核对——它由调用点反推（如 map 的 U 只能从 lambda 体推出），报了也是误报
            val ftRet = ft?.ret
            val retIsTp = ftRet is NamedType && ftRet.args.isEmpty() &&
                ftRet.name !in BASE_TYPES && ftRet.name !in syms.enums &&
                ftRet.name !in syms.structs && !ftRet.isSynthetic()
            if (ft != null && !retIsTp && !bt.isSynthetic() && !typeLooseEq(bt, ft.ret))
                d.error("E-TYPE-MISMATCH", e.pos, "lambda 体类型 ${bt.render()} 与期望返回 ${ft.ret.render()} 不符")
            // P2（决策 78）：带期望时返回**实际组合类型** FunType(期望参数, 实际体类型)，
            // 无期望时返回 FunType(合成参数, 实际体类型)——调用点据此反推类型参数
            //（如 map 的 U 只能由 lambda 体类型推出；fold 的 U 由 init 推出）
            if (ft != null) FunType(ft.params, bt)
            else FunType(e.params.map { syntheticT("参数") }, bt)
        }
        is BlockExpr -> {
            val nf = Frame(f)
            // 决策 75（flow 传播）：本块的语句继承进入时的 flow；块尾表达式是值位（flow=false）。
            // return 是否合法由 ReturnStmt 处 `funRetType≠null && flow` 判定，与 codegen 展平严格对齐。
            val tail = e.stmts.lastOrNull() as? ExprStmt
            // 尾表达式默认值位（flow=false）；唯一例外：**尾 if 且所有分支都 return**——
            // 它本质是控制流不是值（codegen 会把它展平成真正的 JS if），其中 return 合法。
            val tailFlow = flow && tail != null && diverges(tail.expr)   // 与 genBlockBody 同判据
            var dead = false   // A5：Nothing / 显式 return 之后不可达
            for (s in e.stmts) {
                if (s === tail) continue
                if (dead) d.warn("W-UNREACHABLE", s.pos, "Nothing/return 之后的语句不可达")
                val st = checkStmt(s, nf, if (uncheckedDepth > 0) false else pure, flow)
                if (st.name == "Nothing") dead = true   // return 分支同样返回 Nothing 标记
            }
            nf.facts.forEach { f.inject(it) }
            val tailT: Type = tail?.let {
                if (dead) d.warn("W-UNREACHABLE", it.pos, "Nothing/return 之后的尾表达式不可达")
                // 尾表达式默认值位；尾 if 全路径 return 时按语句流处理（与 codegen 展平一致）
                checkExpr(it.expr, nf, if (uncheckedDepth > 0) false else pure, flow = tailFlow)
            } ?: namedT("Null", emptyList())
            if (dead && tail == null) namedT("Null", emptyList())
            else if (tail != null && diverges(tail.expr)) namedT("Nothing", emptyList())
            else tailT
        }
        is IfExpr -> {
            // flow=true（语句位）时分支块也是语句流 → 允许提前 return（codegen 展平成真正的 JS if）；
            // flow=false（值位，如 var x = if…）分支仍是表达式（codegen 编 IIFE）。条件表达式恒值位。
            val up = if (uncheckedDepth > 0) false else pure
            checkExpr(e.cond, f, up)   // 条件恒值位
            val a = checkExpr(e.thenBlock, f, up, flow = flow)
            val b = e.elseBlock?.let { checkExpr(it, f, up, flow = flow) }
            // 分支以 return 收尾＝发散（Kotlin 的 Nothing 协变）：不参与类型 join，
            // 否则 `if c { return 1 } else { n }` 会误报"分支不一致"
            val aD = diverges(e.thenBlock)
            val bD = e.elseBlock?.let { diverges(it) } ?: false
            if (e.elseBlock != null && !aD && !bD && a.isNominal() && b!!.isNominal() && !typeLooseEq(a, b))
                d.error("E-TYPE-MISMATCH", e.pos, "if 分支 ${a.render()} 与 ${b.render()} 不一致")
            when {
                e.elseBlock == null -> namedT("Null", emptyList())   // 可落空，不产出值
                aD && bD -> namedT("Nothing", emptyList())
                aD -> b!!
                bD -> a
                else -> TypeInfer.unify(a, b!!) ?: a
            }
        }
        is WhenExpr -> checkWhen(e, f, pure)
        is ByExpr -> {
            val t = checkExpr(e.target, f, pure)
            e.props.forEach { f.inject(PropLogic.fromExpr(it)) }
            d.supplement("D-BY", e.pos, "辅助策略注入 ${e.props.size} 条命题")
            t
        }
        is QuantExpr -> { checkExpr(e.body, f, pure); namedT("Bool", emptyList()) }
        is TupleExpr -> {
            val ts = e.items.map { checkExpr(it, f, pure) }
            TupleType(ts)   // 元组字面量的类型即元素类型之积（决策 61，T3）
        }
        is StructCtorExpr -> checkStructCtor(e, f, pure)   // O3（决策 69）：命名字段构造
        is VarExpr -> {
            // 声明即表达式（A3）：值为初值类型，同时把绑定与命题注入当前帧
            val t0 = checkExpr(e.value, f, pure)
            var t = t0
            if (e.type != null) {
                checkTypeResolvable(e.type, emptyList())
                val want = syms.expand(e.type)   // 别名展开（决策 30）
                // v1.1 修复：标注类型即变量类型（Kotlin 语义）；unify 只做兼容性检查——
                // 过去用 unify 结果当类型，遇泛型初值（如 setEmpty(): Set[T]）会把未绑定的
                // 类型参数 T 残留进变量类型，导致后续方法调用实参失配
                if (TypeInfer.unify(t0, want) == null)
                    d.error("E-TYPE-MISMATCH", e.pos, "var ${e.name}: 标注 ${e.type.render()} 与初值 ${t.render()} 不可统一")
                else t = want
            }
            f.declareVar(e.name, t)
            val mut = "mut" in e.annotations
            if (mut) f.declareMut(e.name)
            if (isPureValue(e.value) && !mut) f.inject(PAtom("=", listOf(NameRef(e.name), e.value)))
            if (isPureValue(e.value) || mut) f.inject(PAtom(":", listOf(NameRef(e.name), typeToExpr(t))))
            t
        }
        is AnonFunExpr -> {
            // 匿名函数表达式（A3）：独立帧检查体，返回合成函数类型（v1 无函数字面类型）
            val nf = Frame(f)
            val tps = e.theory.filterIsInstance<TypeParam>().map { it.name }
            e.params.forEach { p ->
                checkTypeResolvable(p.type, tps)
                nf.declareVar(p.name, p.type)
                nf.inject(PAtom(":", listOf(NameRef(p.name), typeToExpr(p.type))))
            }
            e.preps.forEach { nf.inject(PropLogic.fromExpr(it)) }
            if (e.body != null) {
                val bt = checkExpr(e.body, nf, pure)
                val rt = e.retType
                if (rt != null && !bt.isSynthetic() && !typeLooseEq(bt, rt))
                    d.error("E-TYPE-MISMATCH", e.pos, "匿名函数体类型 ${bt.render()} 与声明 ${rt.render()} 不符")
                val tail = dollarOf(e.body)
                for (post in e.posts) {
                    val goal = PropLogic.substDollar(PropLogic.fromExpr(post), tail)
                    if (!proveOrDeep(nf.allFacts(), goal))
                        d.error("E-POST-UNPROVEN", e.pos, "匿名函数未证明下文 ${PropLogic.render(goal)}")
                }
            }
            syntheticT("匿名函数")
        }
    }

    internal fun checkBin(e: BinExpr, f: Frame, pure: Boolean): Type {
        if (e.op == ":") {
            // 类型测：右操作数是类型名，不作值求值（否则 Nat 会被查值表报未定义）
            checkExpr(e.left, f, pure)
            val target = (e.left as? NameRef)?.name
            val wantT = nominalOf(e.right)
            if (target != null && wantT != null) {
                val have = f.lookupVar(target)
                if (have != null && have.isNominal() && !typeLooseEq(have, wantT))
                    d.error("E-TYPE-MISMATCH", e.pos, "$target : ${wantT.render()} 与其类型 ${have.render()} 冲突")
                else f.inject(PAtom(":", listOf(e.left, e.right)))   // 智能转换：注入 n : T
            }
            return namedT("Bool", emptyList())
        }
        val l = checkExpr(e.left, f, pure)
        val r = checkExpr(e.right, f, pure)
        return when (e.op) {
            "!" -> namedT("Bool", emptyList())
            else -> {
                if (e.op == "/" && e.right is IntLit && (e.right as IntLit).value == "0")
                    d.warn("W-DIV-ZERO", e.pos, "字面量除以零")
                val bt = TypeInfer.builtinOp(e.op, listOf(l, r))
                if (bt == null) {
                    // P2（决策 78）：synthetic 操作数（如未定型的 lambda 参数 x）时 builtinOp 无从定型——
                    // 返回 synthetic 而非兜底 Bool，否则 `x + 1` 会被定型为 Bool 污染 lambda 返回类型推断
                    if (l.isSynthetic() || r.isSynthetic()) return syntheticT("中缀${e.op}")
                    // P6（决策 82）：字符串拼接——`+` 对 (Str, Str) 即 concat（JS 原生 `a + b` 对字符串天然拼接）。
                    // 此前 checkBin 对 builtinOp 不认识的合法组合兜底 Bool，使 `acc + x.show()` 这类拼接
                    // 类型为 Bool（print 宽松放行掩盖了它）；这里显式给 Str。
                    if (e.op == "+" && l is NamedType && r is NamedType && l.name == "Str" && r.name == "Str")
                        return namedT("Str", emptyList())
                    if (l.isNominal() && r.isNominal() && !typeLooseEq(l, r))
                        d.error("E-TYPE-MISMATCH", e.pos, "中缀 ${e.op}: ${l.render()} 与 ${r.render()} 不匹配")
                    namedT("Bool", emptyList())
                } else bt
            }
        }
    }

    internal fun nominalOf(e: Expr): Type? = when (e) {
        is NameRef -> if (syms.baseOf(namedT(e.name, emptyList())) != null) namedT(e.name, emptyList()) else null
        else -> null
    }

    // ============ v2.0 空安全运算符（决策 88-92） ============

    /** Optional 内层类型：Optional[T] → T；非 Optional 返回 null */
    private fun optionalInner(t: Type): Type? =
        if (t is NamedType && t.name == "Optional" && t.args.size == 1) t.args[0] else null

    /** `a ?: b`——a 为 Some(x) 时得 x，None 时得 b。返回 a 内层与 b 的 join（宽松升格）。 */
    private fun checkElvis(e: ElvisExpr, l: Type, r: Type): Type {
        val inner = optionalInner(l)
        if (inner == null && !l.isSynthetic()) {
            d.error("E-TYPE-MISMATCH", e.pos, "空替代左操作数应为 Optional[T]，实际 ${l.render()}")
            return syntheticT("空替代")
        }
        if (inner == null) return r
        // 内层与 b 的类型 join：数值升格（Nat⊔Int=Int），否则取内层（宽松）
        return TypeInfer.unify(inner, r) ?: inner
    }

    /** `a?.f(b)`——a 为 Optional[T]，解构 T 后调用 f，结果重新包装为 Optional[U]。
     *  依赖 findCollectionMethod（CallCheck.kt）找同名自由函数；类型由 f 返回类型 + Optional 包裹。 */
    private fun checkSafeCall(e: SafeCallExpr, f: Frame, pure: Boolean): Type {
        val selfT = checkExpr(e.target, f, pure)
        val inner = optionalInner(selfT)
        if (inner == null && !selfT.isSynthetic()) {
            d.error("E-TYPE-MISMATCH", e.pos, "安全调用接收者应为 Optional[T]，实际 ${selfT.render()}")
            return syntheticT("安全调用")
        }
        e.args.forEach { checkExpr(it, f, pure) }
        // 用解构后的 inner 作为接收者，借 methodSugar 机制找同名自由函数并检查参数。
        // 为复用 checkFnCall 的完整参数校验，构造一个 FieldExpr(target=NameRef(临时), name)。
        val base = inner ?: syntheticT("安全调用解构")
        val sugarFn = findCollectionMethod(e.name, base) ?: run {
            d.error("E-UNBOUND-NAME", e.pos, "安全调用目标 ${e.name} 对 ${base.render()} 无匹配方法")
            return syntheticT("安全调用")
        }
        // 登记 JS 调用名（与普通方法糖一致：模块前缀 + 重载标签）——codegen 生成 `<name>(inner, args…)`
        moduleHits[e] = jsOverloadName(sugarFn.second, sugarFn.first)
        // 返回类型 = f(inner, args…) 的返回类型包 Optional。
        val ret = sugarFn.first.retType ?: namedT("Null", emptyList())
        return namedT("Optional", listOf(ret))
    }

    /** `a >: T`——运行时结构判定 a 是否为 T；返回 Optional[T]。校验 T 可解析（不限定 a 类型，安全）。 */
    private fun checkCast(e: CastExpr): Type {
        checkTypeResolvable(e.type, emptyList())
        return namedT("Optional", listOf(e.type))
    }

    /** `a ? T`——运行时结构判定 a 是否为 T；返回 Bool。校验 T 可解析。分支内智能转换由 IfExpr 处理。 */
    private fun checkTypeTest(e: TypeTestExpr): Type {
        checkTypeResolvable(e.type, emptyList())
        return namedT("Bool", emptyList())
    }
}

fun checkFile(file: FileAst): DiagBag = Checker(file).run()
