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
    /** 全局事实（跨帧可见）：当前为 pure<name>（决策 34：未标 @impure 的函数自动纯） */
    internal val globalFacts = ArrayList<Prop>()
    /** 覆盖律展开的临时变量计数（每次 enumCoverageDisj 调用前重置） */
    internal var covCounter = 0
    /** 当前是否处于 unchecked 上下文（决策 59：Any 结构体准入判据） */
    internal var uncheckedDepth = 0
    /** 类型类方法调用的字典留痕（决策 60，T5；O2 改键）：**调用点实例** → 字典名。
     *  IdentityHashMap 而非按名键——同名方法对不同实参类型各有 impl 时，按名会互相覆盖；
     *  CallExpr 是 data class，普通 HashMap 的结构相等也会让"长得一样"的两处调用互相污染。 */
    internal val dictHits = java.util.IdentityHashMap<CallExpr, String>()
    /**
     * P6（决策 82）：当前函数的约束字典槽——类型参数名 → (类型类, 槽 JS 名)。
     * `fun f[T: Show]` 体内 `x.show()`（x: T）由槽解析：槽 d_Show_T 是 f 的隐藏首参。
     * checkFun 设置、体检查完清理（不跨函数泄漏）。
     */
    internal var funConsSlots: Map<String, List<Pair<String, String>>> = emptyMap()
    /** P6：约束槽方法调用留痕（调用点实例 → 槽名），codegen 生成 `$slot.m($slot, self, ...)` */
    internal val consHits = java.util.IdentityHashMap<CallExpr, String>()
    /** v3.0 特性 3：Range 内建方法调用点 → 方法名（codegen 成员访问生成） */
    internal val rangeHits = java.util.IdentityHashMap<CallExpr, String>()
    /** P6：带约束泛型函数的调用点字典实参留痕（调用点实例 → 按声明顺序的字典名），codegen 前插实参 */
    internal val dictSubHits = java.util.IdentityHashMap<CallExpr, List<String>>()
    /** HKT（《高阶类型.md》HKT-D2）：带参类型类方法（如 `Functor.map`）的字典分发——接收者是 **kind 应用位**
     *  （fa，非首参位）。记录调用点 → kind 应用位下标，供 codegen 把接收者表达式插到该位（点号式
     *  `xs.map(f)` → `map(f, xs)`；自由式实参已在 fa 位，不入此表）。⟨目标⟩ */
    internal val hktKindPosHits = java.util.IdentityHashMap<CallExpr, Int>()
    /** P8（决策 84）：命名参数规范化后的实参留痕（调用点实例 → 按形参声明顺序的实参列表），codegen 重排 */
    internal val namedArgOrder = java.util.IdentityHashMap<CallExpr, List<Expr>>()
    /** v1.1 集合方法糖留痕：`xs.map(f)` → 同名自由函数 `map(xs, f)`（接收者前置）。
     *  键 = 调用点实例，值 = 重排后的实参列表（接收者 + 原实参），codegen 依此生成。 */
    internal val methodSugarArgs = java.util.IdentityHashMap<CallExpr, List<Expr>>()
    /** P9（决策 44，用户授权 Max 倾向）：递归停机上下文——当前函数名/形参名/解构血缘 */
    internal var curFunName: String? = null
    internal var curFunParams: List<String> = emptyList()
    /** v3.0（完美函数类型）：当前函数的类型形参名集——供匿名函数体重用（`fun(x: A)` 里 A 是外层 compose 的形参） */
    internal var curFunTps: Set<String> = emptySet()
    /** v3.0（完美函数类型）：lambda/匿名函数纯度跟踪——体内发生 impure 调用（放行态）时标记，
     *  使函数值类型 FunType.purity = "impure"，纯上下文调用该函数值报 E-IMPURE-CALL */
    internal var lambdaDepth = 0
    internal var curLambdaImpure = false
    /** v2.0 异步（决策 93）：当前函数是否 @async 上下文——checkFun 设置；@async 函数体内可自由调用挂起函数 */
    internal var curFunAsync = false
    /** v2.0 异步：@async 函数体内对 @async 函数的**挂起点**调用留痕（调用点 → true），codegen 处生成 await */
    internal val asyncAwaitHits = java.util.IdentityHashMap<CallExpr, Boolean>()
    /** v2.0 异步（TASK-7）：当前函数体内已 start() 的任务变量名集合（checkFun 进出清空）——join 处比对 */
    internal var startedTasks: MutableSet<String> = LinkedHashSet()
    /** v2.0 异步（CH-5/CH-6）：当前函数体内**本地创建**（`var ch = Channel<…>()`）的通道名集合 */
    internal var localChannels: MutableSet<String> = LinkedHashSet()
    /** v2.0 异步（CH-6）：当前函数体内已 close(ch) 的通道名集合（顺序累积：close 在 send 前 → 报错） */
    internal var closedChannels: MutableSet<String> = LinkedHashSet()
    /** v2.0 异步（异步 §6 阶段5）：`if (!ch.isClosed())` 分支内已验证开放的通道名集合——
     *  分支内 receive 智能转换为 T（与 CH-5 本地未关闭同理），分支结束清空 */
    internal var openChannels: MutableSet<String> = LinkedHashSet()
    /** v2.0 异步（§5.4）：源码是否用到 Channel（决定 codegen 是否注入 Channel 运行时 class） */
    internal var channelUsed = false
    /** v3.0 特性 3：源码用到 `..` 值或 Range 内建方法 → 注入 __range 运行时（与 channelUsed 同机制） */
    internal var rangeUsed = false
    /** v2.0 异步（CH-5）：receive 智能转换留痕（CallExpr → 是否为本地未关闭通道），codegen 据此解包 */
    internal val receiveSmartHits = java.util.IdentityHashMap<CallExpr, Boolean>()
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
    /** v3.0 特性 2：委托 impl 展开方法表（ImplDecl → 含转发的成员），codegen 字典生成用 */
    internal val delegatedImpls: Map<ImplDecl, List<FunDecl>>
        get() = syms.delegatedImpls
    /** P6（决策 95）：函数引用 `o::f` → 方法 JS 名（codegen 生成闭包引用） */
    internal val funRefTargets = java.util.IdentityHashMap<Expr, String>()
    /** v3.0（完美函数类型）：自由函数引用（o::f 中 f 是全局函数，o 仅作语法载体）——codegen 不注入接收者 */
    internal val funRefFree = java.util.IdentityHashMap<Expr, Boolean>()
    /** P4（v2.1）：lambda 解构参数的字段名键（按参数下标；null=元组走索引）——codegen 解绑用 */
    internal val lambdaDestructKeys = java.util.IdentityHashMap<Expr, MutableList<List<String>?>>()
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
        checkSubTraitParents()          // v2.1 子类型类：父类型类强制（须先实现父类）
        registerPureFacts()
        registerImmutableFacts()   // v2.0 可变性（§4.2）：immutable<T> 自动判定
        registerUntouchFacts()     // v2.0 可变性（§6.2）：非 @mut 形参自动 untouch<param>
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
                // v3.0 特性 4：类型类成员允许带函数体——作为**默认实现**（impl 缺方法时补齐，见 ImplCheck）；
                // 默认实现的 body 不在此检查（self 声明期未定），补齐进具体 impl 后随 impl 一起过 checkFun。
            }
            // O2：impl 成员带隐式 self（决策 68，用户样本形态），方法体内可裸用自类型字段
            is ImplDecl -> {
                val selfT = syms.expand(decl.self)
                // v3.0 特性 2：委托 impl 用展开成员（含转发方法），转发体也过检查登记字典
                val mems = delegatedImpls[decl] ?: decl.members
                mems.forEach { if (it.body != null) checkFun(it, selfT) }
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
        val pure = "impure" !in fn.annotations
        val f = Frame(null)
        val tps = tpNamesOf(fn)
        // P9（决策 44）：设置递归停机上下文（函数名/形参/解构血缘按函数隔离）
        val savedFunName = curFunName; val savedFunParams = curFunParams; val savedFunTps = curFunTps; val savedStructSub = structSub
        val savedFunAsync = curFunAsync
        val savedStarted = startedTasks
        val savedLocals = localChannels
        val savedClosed = closedChannels
        curFunName = fn.name
        curFunParams = fn.params.map { it.name }
        curFunTps = tps.toSet()
        structSub = LinkedHashMap()
        startedTasks = LinkedHashSet()
        localChannels = LinkedHashSet()
        closedChannels = LinkedHashSet()
        // v2.0 异步（决策 93）：main 天然 @async（异步 §2/§5.2）——无需注解即可调 async 函数并 await
        curFunAsync = "async" in fn.annotations || fn.name == "main"
        // P6（决策 82）：设置约束字典槽——`fun f[T: Show]` 体内 `x.show()`（x: T）指向槽 d_Show_T
        val savedConsSlots = funConsSlots
        funConsSlots = fn.theory.filterIsInstance<TypeParam>()
            .filter { it.constraint != null && it.constraint != "Any" }   // v2.1：Any 恒满足零字典，不设槽
            .associate { tp -> tp.name to listOf(tp.constraint!! to "d_${tp.constraint}_${tp.name}") }
        // O2（决策 68，用户拍板隐式 self）：方法体内先注入 self 与结构体字段，
        // 再声明形参（参数名与字段同名时后者覆盖 = 参数遮蔽字段，发提示 H-FIELD-SHADOW）。
        if (selfT != null) injectSelfAndFields(f, selfT, fn)
        // $n 参数引用（决策 65，T6）：记录形参类型序列供体内部解析
        val savedParamTypes = paramTypes
        paramTypes = fn.params.map { syms.expand(it.type) }
        fn.params.forEach { p ->
            checkTypeResolvable(p.type, tps, tpArityOf(fn))
            // 决策 61：@tuple 形参在函数体内即元组本身（类型实参由调用处匹配，声明期无替换表）
            val pt = syms.expand(p.type)
            // P4（v2.1）：元组/结构体参数解构——分量按声明类型解绑，占位名不再声明为变量
            if (p.destruct != null) {
                val comps: List<Type> = when (pt) {
                    is TupleType -> pt.items
                    is NamedType -> syms.structs[pt.name]?.fields?.map { syms.expand(it.type) } ?: emptyList()
                    else -> emptyList()
                }
                if (comps.isEmpty())
                    d.error("E-DESTRUCT", fn.pos, "参数 ${p.name} 解构但类型 ${pt.render()} 不可解构（仅支持元组或结构体）")
                else if (comps.size != p.destruct.size)
                    d.error("E-DESTRUCT", fn.pos, "参数 ${p.name} 解构分量 ${p.destruct.size} ≠ 类型 ${pt.render()} 分量 ${comps.size}")
                else p.destruct.forEachIndexed { i, nm -> f.declareVar(nm, comps[i], fn.pos) }
            } else {
                // O2：参数名遮蔽了 self 字段 → 提示（遮蔽合法，但要让用户知道裸名从此指向参数）
                if (selfT != null && p.name != "self" && p.name in structFieldNames(selfT))
                    d.hint("H-FIELD-SHADOW", fn.body?.pos ?: "", "参数 ${p.name} 遮蔽了 ${selfT.render()} 的同名字段，体内裸用 ${p.name} 将指向参数")
                f.declareVar(p.name, pt, fn.pos)   // 参数警告锚定函数声明位置（fn.body.pos 对表达式体可能为空）
                f.inject(PAtom(":", listOf(NameRef(p.name), typeToExpr(pt))))
                // 深化上下文系统：自然数形参蕴含 n ≥ 0
                if (syms.expand(pt).name == "Nat")
                    f.inject(PAtom(">=", listOf(NameRef(p.name), IntLit("0", fn.pos))))
            }
        }
        // v3.0 特性 1：结构体形参伴生命题自动注入——`fun f(test: Test)` 体内等价于前件
        // `<test.n ≠ 0>`：字段引用（n）替换为 `test.n` 形态注入当前帧
        fn.params.forEach { p ->
            val st = syms.structs[p.type.name]
            if (st != null && st.props.isNotEmpty() && p.destruct == null) {
                val rcv: Expr = NameRef(p.name)
                val sub = LinkedHashMap<String, Expr>()
                st.fields.forEach { sub[it.name] = FieldExpr(rcv, it.name) }
                st.props.forEach { prop ->
                    f.inject(substPropTerms(PropLogic.fromExpr(prop), sub))
                }
            }
        }
        // 决策 65（T6）：上/下文里的 $n 替换为参数名，并发提示级诊断；越界报错。
        val preRewritten = fn.preps.map { rewriteDollarN(it, fn.params, "前件") }
        val postRewritten = fn.posts.map { rewriteDollarN(it, fn.params, "后件") }
        preRewritten.forEach { f.inject(PropLogic.fromExpr(it)) }
        if (fn.body == null) { paramTypes = savedParamTypes; return }
        // v1.1 返回类型：显式标注取之；省略时——块体 = Null（Kotlin Unit 语义），表达式体 = 推导。
        fn.retType?.let { checkTypeResolvable(it, tps, tpArityOf(fn)) }
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
        curFunTps = savedFunTps
        curFunAsync = savedFunAsync
        startedTasks = savedStarted
        localChannels = savedLocals
        // W-UNUSED（报错增强）：函数参数 / 语句与表达式 var（含解构分量）声明后从未使用
        emitWUnused(f, fn)
        closedChannels = savedClosed
    }

    /** W-UNUSED：`_` 前缀名字视为有意的未使用，不警告；用法沿父链上溢标记（子作用域用到即算用过） */
    private fun emitWUnused(f: Frame, fn: FunDecl) {
        val rootVars = f.vars.keys
        for ((name, posStr) in f.auditedDecls()) {
            if (f.isUsed(name) || name.startsWith("_")) continue
            val kind = if (name in rootVars) "参数 $name" else "变量 $name"
            d.warn("W-UNUSED", posStr, "$kind 声明后从未使用（可加 _ 前缀抑制）")
        }
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
                // 元组解构声明 `var (b, c) = e`：初值须为元组，逐分量声明变量（决策 91 声明版）
                if (s.destruct != null) {
                    val t0 = checkExpr(s.value, f, pure)
                    val tup = syms.expand(t0) as? TupleType
                    if (tup == null) {
                        d.error("E-TUPLE-DESTRUCT", s.pos, "元组解构初值须为元组，实际 ${t0.render()}")
                    } else if (s.destruct.size != tup.items.size) {
                        d.error("E-TUPLE-ARITY", s.pos, "解构分量 ${s.destruct.size} ≠ 元组元素 ${tup.items.size}")
                    } else {
                        val mut = "mut" in s.annotations
                        s.destruct.forEachIndexed { i, nm ->
                            f.declareVar(nm, tup.items[i], s.pos)
                            if (mut) f.declareMut(nm)
                            d.supplement("D-TYPE-INFERRED", s.pos, "var $nm 推导为 ${tup.items[i].render()}")
                        }
                    }
                    return namedT("Null", emptyList())   // 解构不产值（与语句级对齐）
                }
                // v3.0（完美函数类型）：递归函数值（letrec）——初值是函数值（lambda/匿名函数）时
                // **先占位声明**（体可自引用 `var fact = {n -> ... fact(n-1) ...}`），检查完再更新真实类型
                val fnInit = s.value is LambdaExpr || s.value is AnonFunExpr
                if (fnInit) f.declareVar(s.name, syntheticT("递归函数值${s.name}"))
                // 有类型标注且初值是 lambda 时，把标注类型作 expect 传入——
                // lambda 参数类型可从 `(Nat)=>Nat` 反推（T2 双向推导，决策 55/57）
                val t0 = if (s.type != null && s.value is LambdaExpr)
                    checkExpr(s.value, f, pure, expect = syms.expand(s.type))
                else checkExpr(s.value, f, pure)   // 初值是值位（flow 默认 false）
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
                f.declareVar(s.name, t, s.pos)
                // 深化上下文系统：自然数变量声明蕴含 x ≥ 0
                if (syms.expand(t).name == "Nat")
                    f.inject(PAtom(">=", listOf(NameRef(s.name), IntLit("0", s.pos))))
                // v3.0 特性 1：结构体构造成功注入——`var t = Test(m)` 注入 t.n = 实参 与伴生命题（t.n 形态）；
                // 命名参数构造（StructCtorExpr）在 checkStructCtor 内同做证明
                if (s.destruct == null) {
                    val ce = s.value as? CallExpr
                    val cname = (ce?.callee as? NameRef)?.name
                    val st0 = cname?.let { syms.structs[it] }
                    if (st0 != null && st0.props.isNotEmpty()) {
                        val rcv: Expr = NameRef(s.name)
                        st0.fields.zip(ce!!.args).forEach { (fp, a) ->
                            f.inject(PAtom("=", listOf(FieldExpr(rcv, fp.name), a)))
                        }
                        val sub = LinkedHashMap<String, Expr>()
                        st0.fields.forEach { sub[it.name] = FieldExpr(rcv, it.name) }
                        st0.props.forEach { prop ->
                            f.inject(substPropTerms(PropLogic.fromExpr(prop), sub))
                        }
                    }
                }
                // v2.0 异步（CH-5）：`var ch = Channel<…>()` 登记为本地通道——receive 智能转换据此
                // 返回 T（编译器确定通道未关闭）；参数位/未知一律 T?。
                if (t is NamedType && t.name == "Channel") localChannels += s.name
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
            // ---------- v3.0 特性 5：∵ / ∴ 语句（命题层，语句位） ----------
            is BecauseStmt -> {
                val p = PropLogic.fromExpr(s.prop)
                val proves = proveOrDeep(f.allFacts(), p) || searchTheorems(p, 0, f.localTheorems)
                d.supplement(if (proves) "D-BECAUSE-PROVED" else "D-BECAUSE-TRUSTED", s.pos,
                    "∵ " + PropLogic.render(p) + (if (proves) "（可证，注入 Γ）" else "（信任，注入 Γ——留痕可审计）"))
                f.inject(p)
                namedT("Null", emptyList())
            }
            is ThereforeStmt -> {
                val q = PropLogic.fromExpr(s.prop)
                if (proveOrDeep(f.allFacts(), q) || searchTheorems(q, 0, f.localTheorems)) {
                    f.inject(q)   // 连续 ∴ 自动累积上文（q 成为下文事实）
                    d.supplement("D-THEREFORE", s.pos, "∴ " + PropLogic.render(q) + " 成立")
                } else {
                    val tried = if (syms.theorems.isEmpty()) "（无定理函数）" else syms.theorems.keys.joinToString(", ")
                    d.error("E-CANNOT-PROVE", s.pos,
                        "∴ " + PropLogic.render(q) + " 证不出。尝试的定理：" + tried + "；当前 Γ：" + f.allFacts().joinToString("； ") { PropLogic.render(it) })
                }
                namedT("Null", emptyList())
            }
            is PlusBecauseStmt -> {
                f.inject(PropLogic.fromExpr(s.prop))
                d.supplement("D-PLUSBECAUSE", s.pos, "+∵ 注入 " + PropLogic.render(PropLogic.fromExpr(s.prop)))
                namedT("Null", emptyList())
            }
            // ---------- v3.0 特性 6：if[p] 排中律（语句位纯证明构造） ----------
            is IfPropStmt -> {
                val p = PropLogic.fromExpr(s.prop)
                if (!syms.classicMounted) {
                    d.error("E-NEED-CLASSIC", s.pos, "if[p] 需要挂载 core.classic（文件内任一声明加 @classic 注解即挂载）")
                } else {
                    val thenF = Frame(f); thenF.inject(p)
                    val elseF = Frame(f); elseF.inject(PNot(p))
                    s.thenStmts.forEach { checkStmt(it, thenF, pure, flow) }
                    s.elseStmts.forEach { checkStmt(it, elseF, pure, flow) }
                    // 两支 ∴ 结论集比对（渲染等价）；分支内证不出由 ThereforeStmt 分支报 E-CANNOT-PROVE
                    val thenC = LinkedHashMap<String, Prop>()
                    val elseC = LinkedHashMap<String, Prop>()
                    fun collect(ss: List<Stmt>, m: LinkedHashMap<String, Prop>) {
                        ss.filterIsInstance<ThereforeStmt>().forEach { t -> val pr = PropLogic.fromExpr(t.prop); m[PropLogic.render(pr)] = pr }
                    }
                    collect(s.thenStmts, thenC); collect(s.elseStmts, elseC)
                    if (thenC.keys != elseC.keys)
                        d.error("E-IF-CONCLUSION-MISMATCH", s.pos,
                            "if[p] 两支 ∴ 结论集不同：then=${thenC.keys}；else=${elseC.keys}（排中律要求两支结论相同）")
                    else thenC.values.forEach { f.inject(it) }   // 块外 Γ += q₁ ∧ … ∧ qₙ
                }
                namedT("Null", emptyList())
            }
            // ---------- v3.0 特性 7：量词（∃引入 / ∃消去 / ∀引入=局部函数） ----------
            is ExistIntroStmt -> {
                val sub = LinkedHashMap<String, Expr>()
                sub[s.name] = s.witness
                val inst = substPropTerms(PropLogic.fromExpr(s.prop), sub)   // P(x)[x→t]
                if (proveOrDeep(f.allFacts(), inst) || searchTheorems(inst, 0, f.localTheorems)) {
                    f.inject(PExists(listOf(s.name), PropLogic.fromExpr(s.prop)))
                    d.supplement("D-EXIST-INTRO", s.pos, "∃ 引入成立：∃${s.name}. " + PropLogic.render(PropLogic.fromExpr(s.prop)))
                } else
                    d.error("E-QUANT-UNPROVEN", s.pos, "∃ 引入实例 " + PropLogic.render(inst) + " 证不出")
                namedT("Null", emptyList())
            }
            is ExistsElimStmt -> {
                val goal = PExists(listOf(s.name), PropLogic.fromExpr(s.prop))
                val premise = f.allFacts().any { it is PExists && PropLogic.render(it) == PropLogic.render(goal) }
                if (!premise) {
                    d.error("E-PROP-UNBOUND", s.pos, "前提 " + PropLogic.render(goal) + " 不在 Γ 中（先用 ∃ 引入或 ∵ 注入）。当前 Γ：" + f.allFacts().joinToString("；") { PropLogic.render(it) })
                } else {
                    val subF = Frame(f)
                    subF.inject(PropLogic.fromExpr(s.prop))   // P(x) 注入块内（x 作用域限于块）
                    s.body.forEach { checkStmt(it, subF, pure, flow) }
                    s.body.filterIsInstance<ThereforeStmt>().forEach { t0 -> f.inject(PropLogic.fromExpr(t0.prop)) }   // 块外 Γ += 结论
                }
                namedT("Null", emptyList())
            }
            is FunStmt -> {
                // ∀ 引入（V1）：局部函数定义登记为局部定理（形参即绑定变元）；定义体非空（不是"签名即证明"）
                if (s.fn.body == null)
                    d.error("E-QUANT-UNPROVEN", s.pos, "局部函数 ${s.fn.name} 必须体（∀ 引入要求有体，不是签名即证明）")
                else {
                    f.localTheorems[s.fn.name] = s.fn
                    d.supplement("D-LOCAL-FUN", s.pos, "局部函数 ${s.fn.name} 登记为局部定理（∀ 引入）")
                }
                namedT("Null", emptyList())
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
        is BinExpr -> e.op in setOf("+", "-", "*", "/", "=", "==", "!=", "<", ">", "<=", "in", "..", ">=", "&", "|", "∧", "∨") &&
            isPureValue(e.left) && isPureValue(e.right)
        is UniExpr -> isPureValue(e.operand)
        // P4（决策 80）：纯函数调用也视为纯值——`var x = f(5)` 需注入 `x = f(5)` 供等式推理
        // （§6.2 目标程序第一例；@impure 函数结果不确定，仍不注入）。构造子/方法有独立注入通道，不进此分支。
        is CallExpr -> {
            val n = (e.callee as? NameRef)?.name ?: return false
            val fn = syms.findFun(n)
            if (fn != null) "impure" !in fn.annotations
            else builtinPure(n) != null && builtinimpure(n) == null
        }
        else -> false
    }

    // ============ 表达式 ============

    internal fun checkExpr(e: Expr, f: Frame, pure: Boolean, expect: Type? = null, flow: Boolean = false): Type = when (e) {
        is IntLit -> {
            // 深化上下文系统：每个自然数字面量蕴含自然命题 n ≥ 0
            f.inject(PAtom(">=", listOf(e, IntLit("0", e.pos))))
            namedT("Nat", emptyList())
        }
        is FloatLit -> namedT("Rat", emptyList())   // P10（决策 86）：浮点字面量 → Rat（IEEE double）
        is StrLit -> namedT("Str", emptyList())
        TopExpr, BotExpr, ReturnSym -> namedT("Bool", emptyList())
        is ParamRefExpr -> {
            // 决策 65（T6）：$n 只允许出现在前件/后件；函数体内出现即报错，请直接用参数名。
            d.error("E-PARAM-REF-IN-BODY", e.pos, "函数体内不能用 \$${e.index} 引用参数，请直接使用参数名")
            paramTypes.getOrNull(e.index - 1) ?: syntheticT("参数引用")
        }
        is NameRef -> {
            val vt = f.lookupVar(e.name)
            if (vt != null) { f.markUsed(e.name); vt }
            else run {
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
                        val fn = syms.funs[e.name]
                        if (fn != null)
                            // v3.0（完美函数类型）：函数名引用作值 = 其**函数类型**（含多态理论参数）
                            FunType(
                                fn.params.map { it.type },
                                fn.retType ?: syms.inferredRets[fn.name] ?: namedT("Null", emptyList()),
                                theory = fn.theory.filterIsInstance<TypeParam>(),
                            )
                        else syntheticT("函数${e.name}")
                    }
                    else -> { d.error("E-UNBOUND-NAME", e.pos, "未定义名字 ${e.name}"); syntheticT("未定义") }
                }
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
                    val bi = builtinPure(e.name) ?: builtinimpure(e.name)
                    val ret = if (bi != null && bi.params.isNotEmpty() && typeLooseEq(bi.params[0].type, t))
                        (bi.retType ?: namedT("Null", emptyList()))
                    else null
                    if (ret != null) ret
                    else {
                        // v3.0（完美函数类型）：方法路径作值（方法糖自由函数 / 匹配类方法）——
                        // `xs.map` / `x.m`（无括号）→ 绑定 self 的函数值 FunType（与 o::f 同构）；
                        // codegen 生成 `(...a) => f(o, ...a)` 闭包。
                        val m = findCollectionMethod(e.name, t)
                            ?: (syms.methods[e.name]?.firstOrNull { doesSelfMatch(it.self, t) }?.let { it.fn to it.modulePath })
                        if (m != null) {
                            val (fn, mmod) = m
                            funRefTargets[e] = jsOverloadName(mmod, fn)   // 重载组带首参标签（stdlib__map$List）
                            // 绑定 self：函数值形参 = 声明的**非首参**（首参即接收者，已在 target 上）；
                            // 多态方法糖函数保留理论参数（如 map[T,U] → [T,U](...)）
                            FunType(
                                fn.params.drop(1).map { it.type },
                                fn.retType ?: namedT("Null", emptyList()),
                                theory = fn.theory.filterIsInstance<TypeParam>(),
                            )
                        } else syntheticT("字段${e.name}")
                    }
                }
            }
        }
        // P6（决策 95）：`o::f` 方法引用——不加括号传递函数值。
        // 复杂（泛型参数）方法没有可值的"完美函数类型" → E-FUTURE-FUN（用户措辞）。
        is FunRefExpr -> {
            val tt = checkExpr(e.target, f, pure)
            val method = (syms.methods[e.name] ?: emptyList()).firstOrNull { doesSelfMatch(it.self, tt) }
            // P5（决策 81）：内建点号方法（strUpper/length…）不在 syms.methods，走 builtin 表按首参匹配
            var fn = method?.fn
            if (fn == null) {
                val bi = builtinPure(e.name) ?: builtinimpure(e.name)
                if (bi != null && bi.params.isNotEmpty() && typeLooseEq(bi.params[0].type, tt)) fn = bi
                else fn = syms.funs[e.name]
            }
            if (fn == null) {
                d.error("E-NO-METHOD", e.pos, "类型 ${tt.render()} 无方法 ${e.name}")
                syntheticT("函数引用")
            } else if (method != null && method.trait.isNotEmpty()) {
                // 字典方法（impl trait 需 dict 实参）仍无完美函数类型可供引用
                d.error("E-FUTURE-FUN", e.pos, "trait 方法引用请期待未来的完美函数类型")
                syntheticT("函数引用")
            } else {
                val jsName = method?.let { jsOverloadName(it.modulePath, it.fn) }
                    ?: jsOverloadName(modulePath, fn)   // 重载组带首参标签
                funRefTargets[e] = jsName
                // v3.0（完美函数类型）：自由函数引用不注入接收者（o::f 里 o 仅语法载体）
                val isBound = method != null || run {
                    val b = builtinPure(e.name) ?: builtinimpure(e.name)
                    b != null && b.params.isNotEmpty() && typeLooseEq(b.params[0].type, tt)
                }
                if (!isBound) funRefFree[e] = true
                // v3.0（完美函数类型）：泛型函数引用解锁——理论参数随函数类型携带
                FunType(
                    fn.params.map { it.type },
                    fn.retType ?: namedT("Null", emptyList()),
                    theory = fn.theory.filterIsInstance<TypeParam>(),
                )
            }
        }
        is InstExpr -> {
            // v3.0（完美函数类型）：实例化表达式**作值**（`id<Nat>` 传参/赋值）——返回实例化的函数类型，
            // 不再是旧占位 Bool；调用形态（`id<Nat>(7)`）走 CallExpr 的 checkCall，不经过本分支
            val tt = checkExpr(e.target, f, pure)
            e.terms.forEach { typeOfTerm(it) }   // terms 是类型实参（Nat / List[Nat]），类型名非值
            if (tt is FunType && tt.theory.size == e.terms.size) {
                val sub = LinkedHashMap<String, Type>()
                tt.theory.zip(e.terms).forEach { (tp, term) -> typeOfTerm(term)?.let { sub[tp.name] = it } }
                FunType(tt.params.map { it.substT(sub) }, tt.ret.substT(sub))
            } else tt
        }
        is CallExpr -> { if (uncheckedDepth > 0) checkCall(e, f, pure = false) else checkCall(e, f, pure) }
        is LambdaExpr -> {
            // T2 双向推导（决策 55/57）：期望 (A)=>B 时参数按 A 绑定、体核对 B；无期望则宽松 synthetic
            // v3.0（完美函数类型）：纯度跟踪——体内 impure 调用 → FunType.purity="impure"
            val savedImpure = curLambdaImpure
            curLambdaImpure = false
            lambdaDepth++
            val nf = Frame(f)
            val ft = expect as? FunType
            if (ft != null && ft.params.size != e.params.size)
                d.error("E-TYPE-MISMATCH", e.pos, "lambda 有 ${e.params.size} 个参数，期望类型要求 ${ft.params.size} 个")
            val dtKeys = ArrayList<List<String>?>()
            e.params.forEachIndexed { i, pname ->
                // P4（v2.1）：lambda 解构参数 `(a, b)`——分量按期望类型（元组 items/结构体字段）绑定，无期望则占位
                if (pname.startsWith("(") && pname.endsWith(")")) {
                    val parts = pname.substring(1, pname.length - 1).split(",")
                    val pt = ft?.params?.getOrNull(i)?.let { syms.expand(it) }
                    // 留痕解绑键：结构体 → 字段名序列；元组/未知 → null（codegen 走下标）
                    val keys: List<String>? = when (pt) {
                        is TupleType -> null
                        is NamedType -> syms.structs[pt.name]?.fields?.map { it.name }
                        else -> null
                    }
                    dtKeys += keys
                    val comps: List<Type> = when (pt) {
                        is TupleType -> pt.items
                        is NamedType -> syms.structs[pt.name]?.fields?.map { syms.expand(it.type) } ?: emptyList()
                        else -> emptyList()
                    }
                    parts.forEachIndexed { j, nm -> nf.declareVar(nm, comps.getOrNull(j) ?: syntheticT("参数分量")) }
                } else { dtKeys += null; nf.declareVar(pname, ft?.params?.getOrNull(i) ?: syntheticT("参数")) }
            }
            if (dtKeys.any { it != null }) lambdaDestructKeys[e] = dtKeys
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
            // v3.0（完美函数类型 统一刀）：lambda 满足多态函数类型位（如 `[T](T)=>T`）时
            // **继承期望类型的理论参数**——lambda 字面量保持多态、可实例化调用；
            // 泛型槽位（map 的 (T)=>U，T 是外围形参）expect.theory 为空，不受影响。
            val bodyImpure = curLambdaImpure
            lambdaDepth--
            curLambdaImpure = savedImpure
            val purity = if (bodyImpure) "impure" else ""
            if (ft != null) FunType(ft.params, bt, purity = purity, theory = ft.theory)
            else FunType(e.params.map { syntheticT("参数") }, bt, purity = purity)
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
            // v2.0 空安全智能转换（决策 88-92）：条件为 `x ? T` 时 then 分支帧注入 `x : T`——
            // 子帧遮蔽 x 的旧类型（Optional[T] → T），分支内 x 直接按 T 使用（Kotlin 智能转换扩展）。
            val thenF: Frame = if (e.cond is TypeTestExpr) {
                val tt = e.cond
                if (tt.target is NameRef) {
                    val nf = Frame(f)
                    nf.declareVar(tt.target.name, tt.type)
                    nf.inject(PAtom(":", listOf(tt.target, typeToExpr(tt.type))))
                    nf
                } else f
            } else f
            // v2.0 异步（异步 §6 阶段5）：`if (!ch.isClosed())` 分支——then 检查期间 ch 视为已验证开放，
            // 分支内 receive 智能转换为 T（与 CH-5 本地未关闭同理）。嵌套 if 时恢复外层登记。
            val openCn: String? = when (val cond = e.cond) {
                is UniExpr -> if (cond.op == "!") {
                    val call = cond.operand as? CallExpr
                    val fe = call?.callee as? FieldExpr
                    if (fe != null && fe.name == "isClosed" && fe.target is NameRef)
                        (fe.target as NameRef).name else null
                } else null
                else -> null
            }
            val a = if (openCn != null) {
                val wasOpen = openCn in openChannels
                openChannels += openCn
                try { checkExpr(e.thenBlock, thenF, up, flow = flow) }
                finally { if (!wasOpen) openChannels -= openCn }
            } else checkExpr(e.thenBlock, thenF, up, flow = flow)
            // v2.0 和类型（联合类型反收窄）：`if(x ? A)` 的 else 分支 x 收窄为「联合减 A」——
            // 仅当条件为类型测、目标为名字、原名称为联合且被测类型确为变体时成立；否则不收窄（保持原行为）。
            val elseF: Frame? = if (e.cond is TypeTestExpr) {
                val tt = e.cond
                val src = (tt.target as? NameRef)?.name?.let { f.lookupVar(it) }
                if (src is UnionType) {
                    val rest = src.variants.filterNot { typeEq(it, tt.type) }
                    if (rest.size != src.variants.size) {
                        val nf = Frame(f)
                        nf.declareVar((tt.target as NameRef).name, if (rest.size == 1) rest[0] else UnionType(rest))
                        nf
                    } else null
                } else null
            } else null
            val b = e.elseBlock?.let { checkExpr(it, elseF ?: f, up, flow = flow) }
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
        is TaskExpr -> {
            // v2.0 异步（决策 93）：`Task { … }` 创建任务（TASK-1，仅创建不启动）。
            // 体天然 @async（TASK-5）——临时置 async 上下文，体内可自由调用挂起函数（await）。
            val savedAsync = curFunAsync
            curFunAsync = true
            val bt = try { checkExpr(e.body, f, pure) }
            finally { curFunAsync = savedAsync }
            // Task[T]：T = 任务体尾表达式类型（块语义同 BlockExpr：尾表达式，无尾则 Null）
            namedT("Task", listOf(bt))
        }
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
            // v3.0（完美函数类型）：递归函数值（letrec）——初值是函数值（lambda/匿名函数）时
            // **先占位声明**（体可自引用 `var fact = {n -> ... fact(n-1) ...}`），检查完再更新真实类型
            val fnInit = e.value is LambdaExpr || e.value is AnonFunExpr
            if (fnInit) f.declareVar(e.name, syntheticT("递归函数值$e.name"))
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
            f.declareVar(e.name, t, e.pos)
            val mut = "mut" in e.annotations
            if (mut) f.declareMut(e.name)
            if (isPureValue(e.value) && !mut) f.inject(PAtom("=", listOf(NameRef(e.name), e.value)))
            if (isPureValue(e.value) || mut) f.inject(PAtom(":", listOf(NameRef(e.name), typeToExpr(t))))
            t
        }
        is AnonFunExpr -> {
            // 匿名函数表达式（A3）：独立帧检查体
            // v3.0（完美函数类型）：返回**真函数类型**——带理论参数（theory）的多态函数类型 `[T](T)=>T`，
            // 不再是 synthetic；值可存入局部变量、参与类型检查与实例化调用。
            val nf = Frame(f)
            // v3.0（完美函数类型）：纯度跟踪——体内 impure 调用 → FunType.purity="impure"
            val savedImpure = curLambdaImpure
            curLambdaImpure = false
            lambdaDepth++
            // v3.0（完美函数类型）：类型形参可解析集 = 自身 theory + 外层函数（compose 等）的类型形参
            val tps = e.theory.filterIsInstance<TypeParam>().map { it.name } + curFunTps.toList()
            e.params.forEach { p ->
                checkTypeResolvable(p.type, tps)
                nf.declareVar(p.name, p.type)
                nf.inject(PAtom(":", listOf(NameRef(p.name), typeToExpr(p.type))))
            }
            e.preps.forEach { nf.inject(PropLogic.fromExpr(it)) }
            val bt = if (e.body != null) {
                val bt0 = checkExpr(e.body, nf, pure)
                val rt = e.retType
                if (rt != null && !bt0.isSynthetic() && !typeLooseEq(bt0, rt))
                    d.error("E-TYPE-MISMATCH", e.pos, "匿名函数体类型 ${bt0.render()} 与声明 ${rt.render()} 不符")
                val tail = dollarOf(e.body)
                for (post in e.posts) {
                    val goal = PropLogic.substDollar(PropLogic.fromExpr(post), tail)
                    if (!proveOrDeep(nf.allFacts(), goal))
                        d.error("E-POST-UNPROVEN", e.pos, "匿名函数未证明下文 ${PropLogic.render(goal)}")
                }
                bt0
            } else null
            val ptypes = e.params.map { it.type }
            val bodyImpure = curLambdaImpure
            lambdaDepth--
            curLambdaImpure = savedImpure
            FunType(
                ptypes,
                bt ?: syntheticT("匿名函数返回"),
                purity = if (bodyImpure) "impure" else "",
                theory = e.theory.filterIsInstance<TypeParam>(),
            )
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
        // v3.0（区间 in）：`x in lo..hi` → Bool；右侧须是 `..` 区间（V1 只做数值区间）
        if (e.op == "in") {
            if (e.right is BinExpr && (e.right as BinExpr).op == "..") return namedT("Bool", emptyList())
            d.error("E-TYPE-MISMATCH", e.pos, "in 右侧须是区间 lo..hi（v3.0 V1；集合成员待 v3.1）")
            return namedT("Bool", emptyList())
        }
        return when (e.op) {
            // v3.0 特性 3：`..` 返回惰性 Range[Int]（修正：非 List）；`x in lo..hi` 的 O(1) 判定在上面处理
            ".." -> {
                rangeUsed = true
                if (!l.isNumeric() || !r.isNumeric())
                    d.error("E-TYPE-MISMATCH", e.pos, "区间 `..` 的端点须为数值，实际 ${l.render()} 与 ${r.render()}")
                namedT("Range", listOf(l))
            }
            "!" -> namedT("Bool", emptyList())
            else -> {
                if (e.op == "/" && e.right is IntLit && (e.right as IntLit).value == "0")
                    d.warn("W-DIV-ZERO", e.pos, "字面量除以零")
                val bt = TypeInfer.builtinOp(e.op, listOf(l, r))
                if (bt == null) {
                    // P2（决策 78）：synthetic 操作数（如未定型的 lambda 参数 x）时 builtinOp 无从定型——
                    // 返回 synthetic 而非兜底 Bool，否则 `x + 1` 会被定型为 Bool 污染 lambda 返回类型推断
                    if (l.isSynthetic() || r.isSynthetic()) return syntheticT("中缀${e.op}")
                    // 类型变量形操作数（如 lambda 参数期望类型是裸 `T`，见 compose`(B)=>C` 的 B）：
                    // 与 synthetic 同列——它由调用点合一反推（P7 决策 83），此刻无从定型，宽松放行而非误报 E-TYPE-MISMATCH。
                    if (isTypeVarForm(l) || isTypeVarForm(r)) return syntheticT("中缀${e.op}")
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

    /** 类型变量形：空参的大写 NamedType，且非内建/枚举/结构体/合成——即 lambda 参数期望类型里的裸类型变量（如 `(B)=>C` 的 B）。
     *  与 retIsTp 同判据（Checker.kt 543-545）：判为「待合一反推」而非具体类型，供 checkBin 等宽松放行。 */
    private fun isTypeVarForm(t: Type): Boolean =
        t is NamedType && t.args.isEmpty() && t.name !in BASE_TYPES &&
            t.name !in syms.enums && t.name !in syms.structs && !t.isSynthetic()

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

    /** `a is T`（v2.1 由 `a ? T` 迁移）——运行时结构判定 a 是否为 T；返回 Bool。校验 T 可解析。
     *  分支内智能转换由 IfExpr 处理。v2.1 规则：is 只能判断结构体/枚举（含基本类型），
     *  类型类是编译期约束、无运行时 tag，禁止（对齐 Rust 语义）。 */
    private fun checkTypeTest(e: TypeTestExpr): Type {
        checkTypeResolvable(e.type, emptyList())
        val tn = (e.type as? NamedType)?.name
        if (tn != null && syms.classes.containsKey(tn))
            d.error("E-TYPE-MISMATCH", e.pos, "is 不能判断类型类 ${e.type.render()}（类型类是编译期约束，无运行时 tag）")
        return namedT("Bool", emptyList())
    }
}

fun checkFile(file: FileAst): DiagBag = Checker(file).run()
