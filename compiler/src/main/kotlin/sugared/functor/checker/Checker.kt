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
    /** 脚本模式：顶层语句即入口（类 @impure main），纯作用域检查放宽——允许顶层调用非纯函数 */
    internal val scriptMode: Boolean = false,
    /** 激进模式（idea doc/激进模式.md）：--prover=aggressive。默认 false——普通模式行为完全不变（§11 其四） */
    internal val aggressive: Boolean = false,
    /** 激进模式可观测性：进度频率/详细程度/诊断报告路径（§6、§7、§8） */
    internal val proveVerbose: Boolean = false,
    internal val proveIntervalMs: Long = 5_000L,
    internal val proveDumpPath: String? = null,
) {
    internal val d = DiagBag().also { it.modulePath = modulePath }
    val syms = Symbols()
    /** LSP/IDE 类型导出：声明名 → 推导类型（局部 var / 解构 / 模式绑定）。
     *  check 过程填充、run 后可用（best-effort：仅已过检部分有值）。 */
    val inferredVars = LinkedHashMap<String, Type>()
    /** 激进会话：aggressive=false 时为 null，证明器所有激进分支全部关闭 */
    internal val session: ProverSession? =
        if (aggressive) ProverSession(proveVerbose, proveIntervalMs, proveDumpPath).also { AggressiveRuntime.active = it }
        else null
    internal val prover = Prover(maxRounds = if (aggressive) Int.MAX_VALUE else 3, maxDepth = maxSatDepth, session = session)
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
    /** root doc《副作用与可变性控制》：可变视图绑定深拷贝留痕（绑定语句 → 需拷贝）。codegen 依此包 __dc */
    internal val deepCopyHits = java.util.IdentityHashMap<Node, Boolean>()
    /** P9：core 标准库数组化 List 方法调用留痕（调用点 → 方法名）。codegen 据此把 map/size/… 映射成 JS 原生数组方法，
     *  不再在 codegen 里靠模块前缀字符串比较猜测。 */
    internal val listMethodHits = java.util.IdentityHashMap<CallExpr, String>()
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
    /** v2.0 异步（root doc《异步》）：源码用到 Mutex → 注入 Mutex 运行时（与 channelUsed 同机制） */
    internal var mutexUsed = false
    /** v2.0 异步（root doc《异步》）：源码用到 @atomic（原子类型）→ 注入 atomic 运行时 */
    internal var atomicUsed = false
    /** v2.0 异步（root doc《异步》）：源码用到 Flow → 注入 Flow 冷流运行时 */
    internal var flowUsed = false
    /** v3.0 特性 3：源码用到 `..` 值或 Range 内建方法 → 注入 __range 运行时（与 channelUsed 同机制） */
    internal var rangeUsed = false
    /** v2.0 异步（CH-5）：receive 智能转换留痕（CallExpr → 是否为本地未关闭通道），codegen 据此解包 */
    internal val receiveSmartHits = java.util.IdentityHashMap<CallExpr, Boolean>()
    /** v2.0 异步（root doc《异步》）：Flow 冷流 -- 当前 Flow 体的 emit 值类型栈（嵌套 Flow 用栈） */
    internal val flowElemStack = ArrayDeque<MutableList<Type>>()
    /** v2.0 异步（root doc《异步》）：Flow 体的 emit 调用点（CallExpr -> true），codegen 据此生成 yield */
    internal val flowEmitHits = java.util.IdentityHashMap<CallExpr, Boolean>()
    /** P9：变量血缘（子 → 父）：when 解构绑定变量 → 主题变量（`when(xs){Cons(_,t)->...}` ⇒ t→xs）。checkFun 进出清空恢复。 */
    internal var structSub: MutableMap<String, String> = LinkedHashMap()
    /** t30（用户决策「by 只有委托」）：待核 by 断言队列——ByExpr 把 (props, pos) 挂队，
     * 由 flushByAsserts 在包围语句/函数体检查完成（名字绑定与事实注入就绪）后统一核对；不跨语句残留。 */
    internal val pendingByAsserts = ArrayList<Pair<List<Expr>, String>>()
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
 *  不只是 `core.collection.List` 限定形态）。P5 只补了 when 模式的构造子/枚举，这里补类型位置本身。 */
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
        // ⚠️ 2026-10-12 用户拍板：untouch<param> / immutable<T> 两个自然命题**先废弃**（不再生成事实），
        // 实现保留在 Symbols.kt（标注废弃），待 MutType 类型维度落地后按《副作用与可变性控制.md》再启用。
    }

    /** 阶段 C：全部声明体 + 顶层语句（多文件=本模块合并后的一份 FileAst） */
    internal fun checkAllBodies() {
        for (e in file.entries) if (e is DeclEntry) checkDeclBody(e.decl)
        val top = Frame(null)
        // 脚本模式顶层即入口（类 @impure main），允许调用非纯函数；工程模式顶层纯
        val topPure = !scriptMode
        for (e in file.entries) if (e is StmtEntry) checkStmt(e.stmt, top, pure = topPure)
    }

    // ============ 声明体 ============

    internal fun checkDeclBody(decl: Decl) {
        when (decl) {
            is FunDecl -> if (decl.body != null) checkFun(decl)
            is ClassDecl -> decl.members.forEach {
                // v3.0 特性 4：类型类成员允许带函数体——作为**默认实现**（impl 缺方法时补齐，见 ImplCheck）。
                // P6：默认实现体即便从未被任何 impl 补齐也必须检查（否则漏网）；它是模板，self 无具体绑定制以 selfT = null 检查；
                // 被具体 impl 补齐后还会随 impl 再过一次 checkFun（selfT = 具体 self 类型）。
                if (it.body != null) checkFun(it, null)
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

    internal fun substDollarNExpr(e: Expr, params: List<Param>): Expr = when (e) {
        is ParamRefExpr -> params.getOrNull(e.index - 1)?.let { NameRef(it.name) } ?: e
        is BinExpr -> e.copy(left = substDollarNExpr(e.left, params), right = substDollarNExpr(e.right, params))
        is UniExpr -> e.copy(operand = substDollarNExpr(e.operand, params))
        is CallExpr -> e.copy(callee = substDollarNExpr(e.callee, params), args = e.args.map { substDollarNExpr(it, params) })
        is InstExpr -> e.copy(target = substDollarNExpr(e.target, params), terms = e.terms.map { substDollarNExpr(it, params) })
        is TupleExpr -> e.copy(items = e.items.map { substDollarNExpr(it, params) })
        else -> e
    }

    /** 根文档·this：上下文中 `this` 指代函数本体——内联替换为函数名（`p<this>` → `p<f>`）。 */
    internal fun substThisExpr(e: Expr, fnName: String): Expr = when (e) {
        is NameRef -> if (e.name == "this") NameRef(fnName) else e
        is BinExpr -> e.copy(left = substThisExpr(e.left, fnName), right = substThisExpr(e.right, fnName))
        is UniExpr -> e.copy(operand = substThisExpr(e.operand, fnName))
        is CallExpr -> e.copy(callee = substThisExpr(e.callee, fnName), args = e.args.map { substThisExpr(it, fnName) })
        is InstExpr -> e.copy(target = substThisExpr(e.target, fnName), terms = e.terms.map { substThisExpr(it, fnName) })
        is TupleExpr -> e.copy(items = e.items.map { substThisExpr(it, fnName) })
        is FieldExpr -> e.copy(target = substThisExpr(e.target, fnName))
        is StructCtorExpr -> e.copy(assigns = e.assigns.map { it.first to substThisExpr(it.second, fnName) })
        is LambdaExpr -> e.copy(body = substThisExpr(e.body, fnName))
        else -> e
    }

    internal fun checkFun(fn: FunDecl, selfT: Type? = null) {
        val pure = "impure" !in fn.annotations
        // root doc《副作用与可变性控制》第 39 行：函数参数类型可变（@mut T）时必须标注 @impure
        if (pure && fn.params.any { syms.expand(it.type) is MutType })
            d.error("E-IMPURE-REQUIRED", fn.pos, "函数 " + fn.name + " 的参数是 @mut 可变类型，必须给函数标上 @impure") 
        val f = Frame(null)
        // HKT-S6 + 标准库简写：impl 成员的签名可引用 self 的 kind 占位（经典 `List[a]` 的 a、简写 `Result[_, E]` 的 E）——
        // 并入成员 theory 名字集合，供 checkTypeResolvable 放行（成员自带 theory 在前，占位追加）。
        val tps = tpNamesOf(fn) + (if (selfT != null) selfPlaceholders(selfT) else emptyList())
        // P9（决策 44）：设置递归停机上下文（函数名/形参/解构血缘按函数隔离）
        val savedFunName = curFunName; val savedFunParams = curFunParams; val savedFunTps = curFunTps; val savedStructSub = structSub
        val savedFunAsync = curFunAsync
        val savedStarted = startedTasks
        val savedLocals = localChannels
        val savedClosed = closedChannels
        val savedLambdaRets = lambdaRetTypes.toList()
        val savedFunRet = funRetType; val savedHasRet = bodyHasReturn
        val savedParamTypes = paramTypes
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
        try {
        // O2（决策 68，用户拍板隐式 self）：方法体内先注入 self 与结构体字段，
        // 再声明形参（参数名与字段同名时后者覆盖 = 参数遮蔽字段，发提示 H-FIELD-SHADOW）。
        if (selfT != null) {
            // root doc《副作用与可变性控制》第 69 行：@mut 可变函数体内 self 持有可变视图（可修改实现结构体的可变属性）
            val view = if ("mut" in fn.annotations) MutType(selfT) else selfT
            injectSelfAndFields(f, view, fn)
        }
        // $n 参数引用（决策 65，T6）：记录形参类型序列供体内部解析
        paramTypes = fn.params.map { if ("list" in it.annotations) namedT("List", listOf(syms.expand(it.type))) else syms.expand(it.type) }
        fn.params.forEach { p ->
            checkTypeResolvable(p.type, tps, tpArityOf(fn))
            // @list 形参在函数体内即 List[元素类型]（Kotlin vararg；元素类型实参由调用处匹配）
            val pt = if ("list" in p.annotations) namedT("List", listOf(syms.expand(p.type))) else syms.expand(p.type)
            // P4（v2.1）：元组/结构体参数解构——分量按声明类型解绑，占位名不再声明为变量
            val destruct = p.destruct
            if (destruct != null) {
                val comps: List<Type> = when (pt) {
                    is TupleType -> pt.items
                    is NamedType -> syms.structs[pt.name]?.fields?.map { syms.expand(it.type) } ?: emptyList()
                    else -> emptyList()
                }
                if (comps.isEmpty())
                    d.error("E-DESTRUCT", fn.pos, "参数 ${p.name} 解构但类型 ${pt.render()} 不可解构（仅支持元组或结构体）")
                else if (comps.size != destruct.size)
                    d.error("E-DESTRUCT", fn.pos, "参数 ${p.name} 解构分量 ${destruct.size} ≠ 类型 ${pt.render()} 分量 ${comps.size}")
                else destruct.forEachIndexed { i, nm -> f.declareVar(nm, comps[i], fn.pos) }
            } else {
                // O2：参数名遮蔽了 self 字段 → 提示（遮蔽合法，但要让用户知道裸名从此指向参数）
                if (selfT != null && p.name != "self" && p.name in structFieldNames(selfT))
                    d.hint("H-FIELD-SHADOW", fn.body?.pos ?: "", "参数名 `${p.name}` 和结构体字段重名了；函数体里再写 `${p.name}` 指的是参数，不是字段")
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
        // 根文档·this：`p<this>` 中 this 指代函数本体（内联为函数名）；随后继 $n 重写
        val preRewritten = fn.preps.map { substThisExpr(rewriteDollarN(it, fn.params, "前件"), fn.name) }
        val postRewritten = fn.posts.map { substThisExpr(rewriteDollarN(it, fn.params, "后件"), fn.name) }
        preRewritten.forEach { f.inject(PropLogic.fromExpr(it)) }
        val body = fn.body ?: return
        // v1.1 返回类型：显式标注取之；省略时——块体 = Null（Kotlin Unit 语义），表达式体 = 推导。
        fn.retType?.let { checkTypeResolvable(it, tps, tpArityOf(fn)) }
        val rtDeclared = fn.retType?.let { syms.expand(it) }
        val isExprBody = fn.body !is BlockExpr
        val rt = rtDeclared ?: namedT("Null", emptyList())
        // 决策 75（flow 传播）：体是块 → 语句流 flow=true（体内及语句位 if/when 分支可 return）；
        // 单一表达式体无语句位，flow=false。
        funRetType = rt; bodyHasReturn = false
        val t = checkExpr(body, f, pure, flow = body is BlockExpr)
        flushByAsserts(f)   // t30：表达式体的 by 断言在函数体检查完、参数事实注入后核对
        val sawRet = bodyHasReturn
        // v1.1：表达式体省略返回类型 → 推导生效（供调用点反推；递归自引用时保留 Null/synthetic）
        val effRt = if (fn.retType == null && isExprBody && !t.isSynthetic()) {
            syms.inferredRets[fn.name] = t; t
        } else rt
        // v1.1 Kotlin 语义：显式非 Null 返回类型的**块式**函数必须所有路径 return（不再隐式返回尾表达式）
        if (body is BlockExpr && rtDeclared != null && rtDeclared.name != "Null" && !diverges(body))
            d.error("E-MISSING-RETURN", body.pos,
                "函数 ${fn.name} 声明返回 ${rtDeclared.render()}，块体未在所有路径 return（Kotlin 语义：请写 return，或改用 `= 表达式` 表达式体）")
        else if (sawRet) {
            // 显式 return 路径：块尾类型核对让位，逐 return 已核对（E-RETURN-TYPE）；
            // 块本身按表达式用（如 `fun f(): Nat = { return 1 }` 的体）时值为 Null，不再要求与 rt 一致
        } else
        if (t.isUnsealed() && effRt.name != "Null")
            d.error("E-NON-SEALED-MATCH", body.pos,
                "函数 ${fn.name} 的返回值来自 when 表达式，但分支不穷尽（值可能为空 `Null`）。补全所有构造子分支，或加 `else` 兜底")
        else if (!t.isSynthetic() && !effRt.isAnyExpect() && !typeLooseEq(t, effRt) &&
            !(effRt.name == "Null" && body is BlockExpr))
            d.error("E-TYPE-MISMATCH", body.pos, "函数 ${fn.name} 声明返回 ${effRt.render()}，但函数体是 ${t.render()}，两边不一致")
        val tail = dollarOf(body)
        // @axiom（标准库用，《基本库实现文档》的 @axiom 语义）：签名即公理——下文由作者声明、编译器信任，
        // 不做自动证明义务检查（函数体仍正常类型检查与代码生成；上文仍在调用点强制证明）。
        if ("axiom" in fn.annotations) {
            if (postRewritten.isNotEmpty())
                d.supplement("D-AXIOM-POST", body.pos, "函数 ${fn.name} 标了 @axiom：下文签名即公理（编译器信任，不做自动证明）")
        } else
        for (post in postRewritten) {
            val goal = PropLogic.substDollar(PropLogic.fromExpr(post), tail)
            if (!proveOrDeep(f.allFacts(), goal))
                d.error("E-POST-UNPROVEN", body.pos, "函数 ${fn.name} 声明了返回后的保证（下文 `${PropLogic.render(goal)}`），但函数体没证出。检查 return 的值是否满足它，或用 `∵` 前提供给可推出的事实、`axiom` 语句声明公理")
        }
        // W-UNUSED（报错增强）：函数参数 / 语句与表达式 var（含解构分量）声明后从未使用
        emitWUnused(f, fn)
        } finally {
            paramTypes = savedParamTypes
            funConsSlots = savedConsSlots
            curFunName = savedFunName; curFunParams = savedFunParams; structSub = savedStructSub
            curFunTps = savedFunTps
            curFunAsync = savedFunAsync
            startedTasks = savedStarted
            localChannels = savedLocals
            closedChannels = savedClosed
            funRetType = savedFunRet; bodyHasReturn = savedHasRet
            // P14：异常路径也要弹空 lambda 返回栈，避免后续检查误判在 lambda 内
            lambdaRetTypes.clear(); lambdaRetTypes.addAll(savedLambdaRets)
        }
    }

    /** W-UNUSED：`_` 前缀名字视为有意的未使用，不警告；用法沿父链上溢标记（子作用域用到即算用过） */
    private fun emitWUnused(f: Frame, fn: FunDecl) {
        val rootVars = f.vars.keys
        // 命题（证明义务/前提/结论/∃ 见证/@subst 目标）中出现的变量也算使用：
        // 收集本函数帧子树全部事实里的自由名，与 isUsed 同等待遇。
        val propNames = HashSet<String>()
        fun collectE(e: Expr) { when (e) {
            is NameRef -> propNames.add(e.name)
            is BinExpr -> { collectE(e.left); collectE(e.right) }
            is UniExpr -> collectE(e.operand)
            is CallExpr -> { collectE(e.callee); e.args.forEach { collectE(it) } }
            is InstExpr -> { collectE(e.target); e.terms.forEach { collectE(it) } }
            is FieldExpr -> collectE(e.target)
            is FunRefExpr -> collectE(e.target)
            is SymbolCallExpr -> e.args.forEach { collectE(it) }
            else -> {}
        } }
        fun collectP(p: Prop) { when (p) {
            // 声明形（x = 初值 / x : T / x ≥ 0）首项是裸名——那是声明自己，不算使用，跳过
            is PAtom -> if (p.head in setOf("=", "==", ":", ">=") && p.terms.firstOrNull() is NameRef)
                p.terms.drop(1).forEach { collectE(it) }
            else p.terms.forEach { collectE(it) }
            is PNot -> collectP(p.p)
            is PAnd -> { collectP(p.a); collectP(p.b) }
            is POr -> { collectP(p.a); collectP(p.b) }
            is PImp -> { collectP(p.a); collectP(p.b) }
            is PIff -> { collectP(p.a); collectP(p.b) }
            is PForall -> collectP(p.body)
            is PExists -> collectP(p.body)
            is PExists1 -> collectP(p.body)
            else -> {}
        } }
        fun walkFrame(fr: Frame) { fr.facts.forEach { collectP(it) }; fr.children.forEach { walkFrame(it) } }
        walkFrame(f)
        for ((name, posStr) in f.auditedDecls()) {
            if (f.isUsed(name) || name.startsWith("_") || name in propNames) continue
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
        val selfInner = if (selfT is MutType) selfT.inner else selfT
        val st = (selfInner as? NamedType)?.let { syms.structs[it.name] }
        // H2：泛型结构体方法体内字段类型须代入 self 的实际类型实参——Pair[Nat, Str] 的 first 是 Nat 而非未解析的 A。
        val fieldSub = st?.typeArgsSub(selfInner) ?: emptyMap()
        st?.fields?.forEach { fp ->
            val ft = syms.expand(fp.type).substT(fieldSub)
            f.declareVar(fp.name, ft)
            // 字段裸名 ⟝ self.field —— 供智能转换与等式推理使用
            f.inject(PAtom("=", listOf(NameRef(fp.name), FieldExpr(NameRef("self"), fp.name))))
            f.inject(PAtom(":", listOf(NameRef(fp.name), typeToExpr(ft))))
            // 深化上下文系统：Nat 字段蕴含 f ≥ 0（自然命题；无主题 when 区间穷尽的域下界）
            if (ft.name == "Nat")
                f.inject(PAtom(">=", listOf(NameRef(fp.name), IntLit("0", ""))))
        }
        // 深化上下文系统：方法体内注入 self 结构体的伴生命题（同结构体形参语义：
        // `struct Student<score ≤ 100>` 的方法体内有 score ≤ 100——无主题 when 区间穷尽判定据此建域）
        st?.props?.forEach { prop ->
            // 方法体内字段以裸名出现（score 而非 self.score）：占位符替换为裸字段名
            val sub = LinkedHashMap<String, Expr>()
            st.fields.forEach { sub[it.name] = NameRef(it.name) }
            f.inject(substPropTerms(PropLogic.fromExpr(prop), sub))
        }
    }

    // ============ 语句 ============

    /** 返回该语句的"值类型"，供块体做 Nothing 不可达追踪（A5）。flow=语句流（return 合法区）。 */

    internal fun checkStmt(s: Stmt, f: Frame, pure: Boolean, flow: Boolean = false): Type {
        val t = checkStmtInner(s, f, pure, flow)
        flushByAsserts(f)   // t30：by 断言延迟核对点——语句内表达式（含 var 绑定）检查完、事实注入后统一核对
        return t
    }

    private fun checkStmtInner(s: Stmt, f: Frame, pure: Boolean, flow: Boolean = false): Type {
        return when (s) {
            is VarStmt -> {
                // 元组解构声明 `var (b, c) = e`：初值须为元组，逐分量声明变量（决策 91 声明版）
                val destruct = s.destruct
                if (destruct != null) {
                    val t0 = checkExpr(s.value, f, pure)
                    val tup = syms.expand(t0) as? TupleType
                    if (tup == null) {
                        d.error("E-TUPLE-DESTRUCT", s.pos, "想解构成元组，但初值是 `${t0.render()}`（不是元组）。解构语法 `(a, b)` 的初值必须是元组字面量")
                    } else if (destruct.size != tup.items.size) {
                        d.error("E-TUPLE-ARITY", s.pos, "解构分量 ${destruct.size} ≠ 元组元素 ${tup.items.size}")
                    } else {
                        val mut = "mut" in s.annotations
                        destruct.forEachIndexed { i, nm ->
                            f.declareVar(nm, tup.items[i], s.pos)
                            inferredVars[nm] = tup.items[i]
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
                val declType = s.type
                val t0 = if (declType != null && s.value is LambdaExpr)
                    checkExpr(s.value, f, pure, expect = syms.expand(declType))
                else checkExpr(s.value, f, pure)   // 初值是值位（flow 默认 false）
                var t = t0
                if (declType != null) {
                    checkTypeResolvable(declType, emptyList())
                    val want = syms.expand(declType)   // 别名展开（决策 30）
                    // v1.1：标注类型即变量类型（Kotlin 语义）；unify 只做兼容性检查，
                    // 避免泛型初值（如 setEmpty(): Set[T]）把未绑定类型参数残留进变量类型
                    if (TypeInfer.unify(t, want) == null && !compatibleView(t, want))
                        d.error("E-TYPE-MISMATCH", s.pos, "var ${s.name} 标注了 ${declType.render()}，但初值是 ${t.render()}，两边类型不一致")
                    else t = want
                }
                f.declareVar(s.name, t, s.pos)
                inferredVars[s.name] = t
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
                // root doc《副作用与可变性控制》@ref：登记引用别名变量
                if ("ref" in s.annotations) f.declareRef(s.name)
                // 可变视图绑定深拷贝留痕（root doc 85-144）：@mut T 任一侧或 @ref 可变变量绑定 → 拷贝；双侧 @ref 别名
                if (s.value is NameRef && binderNeedsCopy(t, t0, "ref" in s.annotations, f.isRef((s.value as NameRef).name), mut, f.isMut((s.value as NameRef).name))) {
                    deepCopyHits[s] = true
                    d.supplement("D-DEEP-COPY", s.pos, "绑定涉及 @mut 类型，发生深拷贝：`${s.name}` 是独立副本，改它不影响源") }
                // 指导§42：var n = 0 注入 n = 0 与 n : T；
                // 决策35：@mut 变量仅保有自反相等，非自反相等论据（n = 初值）忽略不注入；
                // root doc 第 9 行：初值引用 @mut 变量时绑定等式不可靠（会变）——同样不注入
                // root doc《副作用与可变性控制》@ref：上下文对引用变量态度与可变变量一致——不注入绑定等式
                if (isPureValue(s.value) && !mut && "ref" !in s.annotations && !refsMut(s.value, f) && !isMutStructType(t)) {
                    f.inject(PAtom("=", listOf(NameRef(s.name), s.value)))
                }
                if (isPureValue(s.value) || mut) {
                    f.inject(PAtom(":", listOf(NameRef(s.name), typeToExpr(t))))
                }
                // 深化上下文系统：Nat 局部变量蕴含 n ≥ 0（自然命题；区间穷尽/下界推理）
                if (syms.expand(t).name == "Nat") {
                    f.inject(PAtom(">=", listOf(NameRef(s.name), IntLit("0", s.pos))))
                    d.supplement("D-NAT-PROP", s.pos, "注入自然命题：`${s.name} ≥ 0`（Nat 变量自动成立）") }
                if (s.type == null) d.supplement("D-TYPE-INFERRED", s.pos, "var ${s.name} 推导为 ${t.render()}")
                t   // 声明即表达式：var 的值是初值（A3）
            }
            is AssignStmt -> {
                when (val tgt = s.target) {
                    is NameRef -> {
                        val lhs = tgt.name
                        if (f.lookupVar(lhs) == null) d.error("E-UNBOUND-NAME", s.pos, "赋值目标 $lhs 未定义")
                        if (!f.isMut(lhs) && uncheckedDepth == 0) d.error("E-IMMUT-ASSIGN", s.pos, "`$lhs` 是不可变的，不能赋值。要改它？声明时写成 `var $lhs : @mut T`")
                        // root doc（纯函数 P-3）：lambda 体内修改外部捕获的可变变量 → 推导该 lambda 不纯（同体内不纯表达式）。
                        // 当前帧未声明它 + isMut 沿父链成立 → 捕获自外层可变变量（块/分支会再叠帧，不查单层 parent）
                        else if (f.isMut(lhs) && lambdaDepth > 0 && !f.vars.containsKey(lhs))
                            curLambdaImpure = true
                    }
                    is FieldExpr -> checkMutFieldWrite(tgt, f, pure)
                    else -> d.error("E-ASSIGN-TARGET", s.pos, "等号左边必须是个变量或字段，不能对表达式赋值")
                }
                val vt = checkExpr(s.value, f, pure)
                val lhs = (s.target as? NameRef)?.name
                if (lhs != null && s.value is NameRef) {
                    val lt = f.lookupVar(lhs) ?: return syntheticT("赋值")
                    // 可变视图赋值深拷贝（root doc 85-144）：b = a 涉及 @mut T/@ref 任一侧 → 拷贝；双侧 @ref 别名
                    if (binderNeedsCopy(lt, vt, f.isRef(lhs), f.isRef((s.value as NameRef).name), f.isMut(lhs), f.isMut((s.value as NameRef).name))) {
                        deepCopyHits[s] = true
                        d.supplement("D-DEEP-COPY", s.pos, "赋值涉及 @mut/@ref 类型，发生深拷贝：`$lhs` 是独立副本") }
                }
                vt
            }
            // ---------- 根文档《手动证明》：因为-所以语句 ∵ form, p₁, p₂ ∴ conclusion ----------
            is BTSStmt -> {
                val premises = s.premises.map { PropLogic.fromExpr(it) }
                // 前提自动证明验证（文档：编译器帮你自动证明前提是否成立）
                var premisesOk = true
                for (pm in premises) {
                    if (!(proveOrDeep(f.allFacts(), pm) || searchTheorems(pm, 0, f.localTheorems))) {
                        premisesOk = false
                        d.error("E-PREMISE-UNPROVEN", s.pos,
                            "因为-所以的前提 " + PropLogic.render(pm) + " 没证出。当前已知事实 Γ：" + f.allFacts().joinToString("； ") { PropLogic.render(it) })
                    }
                }
                val conclusion = PropLogic.fromExpr(s.conclusion)
                // 结论由定理空间（含 form 匹配）+ 前提推出
                if (premisesOk && (proveOrDeep(f.allFacts() + premises, conclusion) || searchTheorems(conclusion, 0, f.localTheorems))) {
                    f.inject(conclusion)
                    d.supplement("D-BTS", s.pos, "因为-所以：∵…∴ " + PropLogic.render(conclusion) + " 成立")
                } else if (premisesOk) {
                    val tried = if (syms.theorems.isEmpty()) "（无定理函数）" else syms.theorems.keys.joinToString(", ")
                    d.error("E-CANNOT-PROVE", s.pos,
                        "因为-所以的结论 " + PropLogic.render(conclusion) + " 证不出。已试的定理：" + tried + "；当前已知事实 Γ：" + f.allFacts().joinToString("； ") { PropLogic.render(it) })
                }
                namedT("Null", emptyList())
            }
            // ---------- 根文档《手动证明》：分类讨论 when[p ∨ q] ----------
            is WhenPropStmt -> {
                val disj = PropLogic.fromExpr(s.disjunction)
                // 必须先有析取前提（Γ 有该析取式）——否则分类讨论无据
                val hasDisj = f.allFacts().any { PropLogic.render(it) == PropLogic.render(disj) }
                if (!hasDisj)
                    d.warn("W-PROP-UNBOUND", s.pos, "分类讨论的析取前提 " + PropLogic.render(disj) + " 不在 Γ 中")
                // 每个分支假设一个析取支成立，收集各支的结论
                val armConcl = ArrayList<LinkedHashMap<String, Prop>>()
                for (arm in s.arms) {
                    val af = Frame(f)
                    af.inject(PropLogic.fromExpr(arm.condition))
                    arm.body.forEach { checkStmt(it, af, pure, flow) }
                    val m = LinkedHashMap<String, Prop>()
                    arm.body.filterIsInstance<BTSStmt>().forEach { b -> val pr = PropLogic.fromExpr(b.conclusion); m[PropLogic.render(pr)] = pr }
                    armConcl += m
                }
                // 所有分支的公共结论注入 Γ；无公共结论报警告（文档：无共同命题报警告）
                val common = if (armConcl.isEmpty()) LinkedHashMap<String, Prop>()
                    else armConcl.reduce { a, b -> LinkedHashMap(a).apply { keys.retainAll(b.keys) } }
                if (armConcl.isEmpty() || common.isEmpty()) {
                    d.warn("W-WHEN-NO-COMMON", s.pos, "分类讨论各分支没有共同的结论")
                } else {
                    common.values.forEach { f.inject(it) }
                    d.supplement("D-WHEN-PROP", s.pos, "分类讨论得出共同结论：" + common.keys.joinToString("; "))
                }
                namedT("Null", emptyList())
            }
            // ---------- 根文档《手动证明》：策略调用 @simp[命题] ----------
            is StrategyStmt -> {
                val goal = PropLogic.fromExpr(s.goal)
                val proved = when (s.strategy) {
                    "simp" -> simpProve(goal, f)
                    "subst" -> substProve(goal, f)
                    else -> false
                }
                if (proved) {
                    f.inject(goal)
                    d.supplement("D-STRATEGY", s.pos, "@" + s.strategy + " 证出 " + PropLogic.render(goal))
                } else {
                    d.error("E-STRATEGY-FAILED", s.pos, "@" + s.strategy + " 无法证明 " + PropLogic.render(goal))
                }
                namedT("Null", emptyList())
            }
            // ---------- v3.0 特性 6：if[p] 排中律（语句位纯证明构造） ----------
            is IfPropStmt -> {
                val p = PropLogic.fromExpr(s.prop)
                if (!syms.classicMounted) {
                    d.warn("E-NEED-CLASSIC", s.pos, "if[p] 需要挂载 core.classic（文件内任一声明加 @classic 注解即挂载）")
                } else {
                    val thenF = Frame(f); thenF.inject(p)
                    val elseF = Frame(f); elseF.inject(PNot(p))
                    s.thenStmts.forEach { checkStmt(it, thenF, pure, flow) }
                    s.elseStmts.forEach { checkStmt(it, elseF, pure, flow) }
                    // 排中律：把两支各自新注入的事实（含 ∵∴ 结论、∃ 引入结果、@subst 结果）
                    // 取结构相等的交，作为「与 p 无关、两支都成立」的公共结论注入块外。
                    val thenNew = thenF.facts
                    val elseNew = elseF.facts
                    val common = LinkedHashMap<String, Prop>()
                    for (q in elseNew) {
                        val m = thenNew.find { PropLogic.propStructEq(it, q) }
                        if (m != null) common[PropLogic.render(q)] = m
                    }
                    if (common.isEmpty())
                        d.error("E-IF-CONCLUSION-MISMATCH", s.pos,
                            "if[p] 两支没有共同结论（排中律要求两支证出同一个结论）")
                    else common.values.forEach { f.inject(it) }   // 块外 Γ += 公共结论
                }
                namedT("Null", emptyList())
            }
// ---------- v3.0 特性 7：量词（∃引入 / ∃消去 / ∀引入=局部函数） ----------
            is ExistIntroStmt -> {
                val sub = LinkedHashMap<String, Expr>()
                s.names.zip(s.witnesses).forEach { (n, w) -> sub[n] = w }
                val inst = substPropTerms(PropLogic.fromExpr(s.prop), sub)   // P(x₁,x₂,…)[xᵢ→tᵢ]
                if (proveOrDeep(f.allFacts(), inst) || searchTheorems(inst, 0, f.localTheorems)) {
                    f.inject(PExists(s.names, PropLogic.fromExpr(s.prop)))
                    d.supplement("D-EXIST-INTRO", s.pos, "∃ 引入成立：∃${s.names.joinToString(",")}. " + PropLogic.render(PropLogic.fromExpr(s.prop)))
                } else
                    d.error("E-QUANT-UNPROVEN", s.pos, "∃ 引入失败：想证明存在某个值满足性质，但给出的实例 `${PropLogic.render(inst)}` 满足不了。检查实例能否推出目标性质")
                namedT("Null", emptyList())
            }
            is ExistsElimStmt -> {
                val goal = PExists(listOf(s.name), PropLogic.fromExpr(s.prop))
                val premise = f.allFacts().any { it is PExists && PropLogic.render(it) == PropLogic.render(goal) }
                if (!premise) {
                    d.warn("E-PROP-UNBOUND", s.pos, "前提 " + PropLogic.render(goal) + " 不在已知事实里（先用 ∃ 引入或 ∵ 注入）。当前已知事实：" + f.allFacts().joinToString("；") { PropLogic.render(it) })
                } else {
                    val subF = Frame(f)
                    subF.inject(PropLogic.fromExpr(s.prop))   // P(x) 注入块内（x 作用域限于块）
                    s.body.forEach { checkStmt(it, subF, pure, flow) }
                    s.body.filterIsInstance<BTSStmt>().forEach { t0 -> f.inject(PropLogic.fromExpr(t0.conclusion)) }   // 块外 Γ += 结论
                }
                namedT("Null", emptyList())
            }
            is FunStmt -> {
                // ∀ 引入（V1）：局部函数定义登记为局部定理（形参即绑定变元）；定义体非空（不是"签名即证明"）
                if (s.fn.body == null)
                    d.warn("E-QUANT-UNPROVEN", s.pos, "局部函数 ${s.fn.name} 必须体（∀ 引入要求有体，不是签名即证明）")
                else {
                    f.localTheorems[s.fn.name] = s.fn
                    d.supplement("D-LOCAL-FUN", s.pos, "局部函数 ${s.fn.name} 登记为局部定理（∀ 引入）")
                }
                namedT("Null", emptyList())
            }
            is AxiomStmt -> { s.props.forEach { f.inject(PropLogic.fromExpr(it)) }; namedT("Null", emptyList()) }
            is ByStmt -> {
                // t30（用户决策「by 只有委托」）：语句式 by 的免验证命题注入已删除——编译期错误并给迁移指引
                // （Parser 仍解析 ByStmt，以便在此给出带指引的诊断而非裸解析失败）
                d.error("E-BY-REMOVED", s.pos,
                    "语句式 `by [q]` 的免验证命题注入已移除（by 只保留委托语义）。供给命题改用 `axiom q` 语句（顶层/函数内通用）；断言表达式成立改用后缀式 `expr by [q]`（经证明器核对）")
                namedT("Null", emptyList())
            }
            is UncheckedStmt -> { uncheckedDepth++; val r = checkStmt(s.inner, f, pure = false, flow); uncheckedDepth--; r }
            // 决策 75：return 合法 ⟺ 语句流（flow=true）且在具名函数/方法体内（funRetType≠null）；
            // 带值核对声明返回类型，裸形只许 Null 函数
            is ReturnStmt -> {
                val rt = funRetType
                val rexpr = s.expr
                // v1.1：lambda 体内 return 合法——返回当前 lambda（Kotlin 风格 `{ …; return v }` / `return@label`）
                if (lambdaRetTypes.isNotEmpty()) {
                    val lret = lambdaRetTypes.last()
                    if (rexpr != null) {
                        val t0 = checkExpr(rexpr, f, if (uncheckedDepth > 0) false else pure)
                        if (!t0.isSynthetic() && !lret.isSynthetic() && !typeLooseEq(lret, t0))
                            d.error("E-RETURN-TYPE", s.pos, "lambda 内 return 值类型 ${t0.render()} 与 lambda 返回 ${lret.render()} 不符")
                    }
                    return namedT("Nothing", emptyList())   // lambda 内 return 后语句不可达
                }
                if (rt == null || !flow)
                    d.error("E-RETURN-OUTSIDE", s.pos,
                        "return 只能写在函数体或 lambda 体的语句里。不能写在表达式里（比如 `var x = return 1` 这种就不行）")
                else when {
                    rexpr == null && rt.name != "Null" ->
                        d.error("E-RETURN-TYPE", s.pos, "裸 return 要求函数返回 Null，实际声明 ${rt.render()}")
                    rexpr != null -> {
                        val t0 = checkExpr(rexpr, f, if (uncheckedDepth > 0) false else pure)   // 值是值位
                        if (!t0.isSynthetic() && !rt.isAnyExpect() && !typeLooseEq(rt, t0))
                            d.error("E-RETURN-TYPE", s.pos, "return 值类型 ${t0.render()} 与声明 ${rt.render()} 不符")
                    }
                }
                bodyHasReturn = true
                namedT("Nothing", emptyList())   // return 不产生前流值——复用发散标记让块体判后续不可达
            }
            is ExprStmt -> checkExpr(s.expr, f, if (uncheckedDepth > 0) false else pure, flow = flow)
        }
    }

    /** t30：清空待核 by 断言队列——逐条 proveOrDeep 核对，证不出报 E-CANNOT-PROVE；断言不注入 Γ
     *  （能证出的命题本就可由 Γ 推出，无需注入；免验证供给一律走 axiom 语句）。 */
    private fun flushByAsserts(f: Frame) {
        if (pendingByAsserts.isEmpty()) return
        for ((props, pos) in pendingByAsserts) {
            for (q in props) {
                val goal = PropLogic.fromExpr(q)
                if (!proveOrDeep(f.allFacts(), goal))
                    d.error("E-CANNOT-PROVE", pos,
                        "by 断言「${PropLogic.render(goal)}」在当前上下文证不出。by 断言须由现有事实推出；要声明免验证的公理请改用 `axiom` 语句")
            }
        }
        pendingByAsserts.clear()
    }

    /** 按名字跨模块找函数（本模块 → 可见模块含 core 标准库）——isPureValue 判定 core 纯函数用 */
    private fun findFunAnywhere(name: String): FunDecl? {
        syms.findFun(name)?.let { return it }
        if (moduleTree != null) for (p in moduleTree.bareVisibleOf(modulePath)) {
            if (p == modulePath) continue
            allSymbols[p]?.findFun(name)?.let { return it }
        }
        return null
    }

    internal fun isPureValue(e: Expr): Boolean = when (e) {
        is IntLit, is StrLit, is NameRef -> true
        is BinExpr -> e.op in setOf("+", "-", "*", "/", "%", "^", "=", "==", "!=", "<", ">", "<=", "in", "..", ">=", "&", "|", "∧", "∨") &&
            isPureValue(e.left) && isPureValue(e.right)
        is UniExpr -> isPureValue(e.operand)
        // P4（决策 80）：纯函数调用也视为纯值——`var x = f(5)` 需注入 `x = f(5)` 供等式推理
        // （§6.2 目标程序第一例；@impure 函数结果不确定，仍不注入）。构造子/方法有独立注入通道，不进此分支。
        is CallExpr -> {
            val n = (e.callee as? NameRef)?.name ?: return false
            val fn = findFunAnywhere(n)
            if (fn != null) "impure" !in fn.annotations
            else builtinPure(n) != null && builtinimpure(n) == null
        }
        else -> false
    }

    /** root doc《副作用与可变性控制》第 9 行配套：表达式是否引用 @mut 变量。
     *  不可变变量以 @mut 变量为初值时，绑定等式不可靠（初值会变）——不注入相等命题。 */
    /** M-2（root doc 可变视图）：@mut T 与 T 兼容——@mut 视图可隐式转不可变视图（转换点=拷贝点，codegen 落地）。 */
    /** root doc《副作用与可变性控制》§可变拷贝（修正案四条款）：
     *  ① 目标含 @mut 视图且源是纯 T（不可变、无引用）→ 拷贝（向纯不可变对象引入可变视图，须快照）；
     *  ② 目标带 @mut 且不带 @ref → 拷贝（每个 @mut 绑定持独立对象）；
     *  ③ 源含 @mut 视图且目标是纯 T → 拷贝（割除可变性，须快照）；
     *  ④ 源带 @ref 且目标不带 @ref → 拷贝（冻结引用快照）。
     *  其余（不可变值互传、把可变值绑定到 @ref 引用、引用互相别名）共享同一个对象，不拷贝。
     *  与脚本模式 Evaluator.bindCopy 同一判据。 */
    private fun binderNeedsCopy(tgt: Type, src: Type, tgtRef: Boolean, srcRef: Boolean, tgtMut: Boolean = false, srcMut: Boolean = false): Boolean {
        val tgtHasMut = tgt is MutType || tgtMut
        val srcHasMut = src is MutType || srcMut
        val tgtPure = !tgtRef && !tgtHasMut
        val srcPure = !srcRef && !srcHasMut
        return (tgtHasMut && (!tgtRef || srcPure))
            || (srcHasMut && tgtPure)
            || (srcRef && !tgtRef)
    }

    private fun compatibleView(got: Type, want: Type): Boolean {
        if (typeEq(got, want)) return true
        return when {
            got is MutType -> typeEq(got.inner, want)   // M-2：@mut T → T（可变视图转不可变）
            want is MutType -> typeEq(got, want.inner)  // root doc 54 行：var a : @mut T = T(0)（建立可变视图）
            else -> false
        }
    }

    /** root doc《副作用与可变性控制》可变结构体（54-61 行）：字段写入需满足
     *  ① 字段是 @mut 字段；② 接收者持有 @mut 视图（变量类型 @mut T 或 @mut 变量）。诊断码 E-NOT-MUT。 */
    private fun Checker.checkMutFieldWrite(tgt: FieldExpr, f: Frame, pure: Boolean) {
        val rcvName = (tgt.target as? NameRef)?.name
        val rcvType = rcvName?.let { f.lookupVar(it) }
        if (rcvType == null) { d.error("E-UNBOUND-NAME", tgt.pos, "字段赋值目标 " + tgt.target + " 未定义"); return }
        val innerT = if (rcvType is MutType) rcvType.inner else if (rcvType is AtomicType) rcvType.inner else rcvType
        val stName = (innerT as? NamedType)?.name
            ?: run { d.error("E-ASSIGN-TARGET", tgt.pos, "字段赋值须作用于具名结构体"); return }
        val st = syms.structs[stName]
            ?: run { d.error("E-ASSIGN-TARGET", tgt.pos, "类型 " + stName + " 不是结构体，不能字段赋值"); return }
        val fld = st.fields.firstOrNull { it.name == tgt.name }
            ?: run { d.error("E-UNBOUND-NAME", tgt.pos, "结构体 " + stName + " 无字段 " + tgt.name); return }
        if ("mut" !in fld.annotations) { d.error("E-NOT-MUT", tgt.pos, "字段 " + tgt.name + " 不可变（非 @mut 字段），不能赋值——把它标 @mut 或改用局部变量"); return }
        val hasView = rcvType is MutType || f.isMut(rcvName!!)
        if (!hasView) d.error("E-NOT-MUT", tgt.pos, "@mut 字段 " + tgt.name + " 赋值须持有 @mut 视图（变量类型 @mut " + stName + " 或 @mut 变量）")
    }

    /** root doc 43/65：可变结构体/可变类型不承认与其他表达式的相等命题——其绑定事实不注入上下文。 */
    private fun isMutStructType(t: Type): Boolean {
        val nt = if (t is MutType) t.inner else t
        val n = (nt as? NamedType)?.name ?: return false
        return syms.structs[n]?.let { "mut" in it.annotations || it.fields.any { "mut" in it.annotations } } == true
    }

    private fun Checker.refsMut(e: Expr, f: Frame): Boolean = when (e) {
        // root doc《副作用与可变性控制》：@ref 引用变量与 @mut 同等——绑定等式不可靠（值可经别名变更）
        is NameRef -> f.isMut(e.name) || f.isRef(e.name)
        is BinExpr -> refsMut(e.left, f) || refsMut(e.right, f)
        is UniExpr -> refsMut(e.operand, f)
        is CallExpr -> e.args.any { refsMut(it, f) }
        is FieldExpr -> refsMut(e.target, f)
        is InstExpr -> refsMut(e.target, f)
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
            if (vt != null) {
                f.markUsed(e.name)
                // P-3（紧急修正）：lambda 体内**读取**外部捕获的可变变量，同样推导该 lambda 不纯——
                // 可变状态读取即隐式状态依赖，与写入同等处理（原规则只覆盖赋值写入）。
                // 判据与赋值分支一致：当前帧未声明 + isMut 沿父链成立 + 处于 lambda 体内。
                if (lambdaDepth > 0 && !f.vars.containsKey(e.name) && f.isMut(e.name)) curLambdaImpure = true
                vt
            }
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
                    else -> { d.error("E-UNBOUND-NAME", e.pos, "没找到 `${e.name}`——拼写错了？还是忘了声明，或其所在模块没 mount？"); syntheticT("未定义") }
                }
            }
        }
        is UniExpr -> {
            val ot = checkExpr(e.operand, f, pure)
            if (e.op == "-") ot else namedT("Bool", emptyList())   // 负号保数值类型，!/¬ 才是 Bool
        }
        is BinExpr -> checkBin(e, f, pure)
        is SymbolCallExpr -> { e.args.forEach { checkExpr(it, f, pure) }; namedT("Bool", emptyList()) }
        is TypeTestExpr -> { checkExpr(e.target, f, pure); checkTypeTest(e) }
        is IsPatExpr -> { d.error("E-PARSE", e.pos, "ifis 智能解构条件 `表达式 is 构造模式` 只能作为 if 的整个条件（解析期已脱糖为 when；此节点出现在这说明 AST 状态不合法）"); namedT("Bool", emptyList()) }
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
                        // v4（跨模块 core 标准库）：类型类 impl 方法（List.map 等）跨可见模块查找；
                        // impl 方法作值生成**字典闭包**名 `dict_X.m`（codegen 据此生成 `dict.m(dict, o, ...a)`，
                        // 普通跨模块 JS 名不含点号）。
                        val sugarM = findCollectionMethod(e.name, t)
                        val implM = syms.methods[e.name]?.firstOrNull { doesSelfMatch(it.self, t) }
                            ?: visibleMethodEntries(e.name)?.firstOrNull { doesSelfMatch(it.self, t) }
                        if (sugarM != null || implM != null) {
                            val fn = sugarM?.first ?: implM!!.fn
                            val mmod = sugarM?.second ?: implM!!.modulePath
                            funRefTargets[e] = if (sugarM == null && implM!!.trait.isNotEmpty())
                                "${dictNameOf(implM.trait, implM.self, implM.modulePath)}.${jsMangle(fn.name)}"
                            else jsOverloadName(mmod, fn)   // 重载组带首参标签（core_collection__map$List）
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
        is CallExpr -> {
            // P51：in 为普通函数——中缀 a in b 解析为 in(a, b) 调用；内建签名（任意, Range[T]）→ Bool
            // 保持 v3.0 V1 区间语义与诊断（内建优先，同 print 等所有内建；集合成员待 v3.1）
            if ((e.callee as? NameRef)?.name == "in" && e.args.size == 2) {
                checkExpr(e.args[0], f, pure)
                val r = checkExpr(e.args[1], f, pure)
                val rt = syms.expand(r)
                val isLiteralRange = e.args[1] is BinExpr && (e.args[1] as BinExpr).op == ".."
                val isRangeVar = rt is NamedType && rt.name == "Range"
                if (isLiteralRange || isRangeVar) namedT("Bool", emptyList())
                else {
                    d.error("E-TYPE-MISMATCH", e.pos, "in 右侧须是区间 lo..hi 或 Range[T] 值（v3.0 V1；集合成员待 v3.1）")
                    namedT("Bool", emptyList())
                }
            } else if (uncheckedDepth > 0) checkCall(e, f, pure = false) else checkCall(e, f, pure)
        }
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
            // 根文档·上下文系统：函数类型携带的上下文 `(T)<$1 ∈ xs> => Null` 注入 lambda 体作前提事实
            ft?.context?.forEach { ctxExpr ->
                val rewritten = substDollarNExpr(ctxExpr, e.params.map { Param(it, syntheticT("上下文参数")) })
                nf.inject(PropLogic.fromExpr(rewritten))
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
            if (ft != null && !retIsTp && !containsUnresolvedTp(ft.ret) && !bt.isSynthetic() && !ft.ret.isAnyExpect() && !typeLooseEq(bt, ft.ret))
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
            // 根文档：纯 lambda 亦可被证明纯（`pure<{ 1 + 1 }>`）——体无 impure 调用时登记自然命题
            if (!bodyImpure) f.inject(PAtom("pure", listOf(e)))
            val purity = if (bodyImpure) "impure" else ""
            if (ft != null) FunType(ft.params, bt, purity = purity, theory = ft.theory, context = ft.context)
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
            // v2.1 智能转换（由 `x ? T` 迁移为 `x is T`）：条件为 `x is T` 时 then 分支帧注入 `x : T`——
            // 子帧遮蔽 x 的旧类型（Optional[T] → T），分支内 x 直接按 T 使用（Kotlin 智能转换扩展）。
            val cond0 = e.cond
            val thenF: Frame = if (cond0 is TypeTestExpr) {
                val tt = cond0
                val ttTarget = tt.target
                if (ttTarget is NameRef) {
                    val nf = Frame(f)
                    nf.declareVar(ttTarget.name, tt.type)
                    nf.inject(PAtom(":", listOf(ttTarget, typeToExpr(tt.type))))
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
            // v2.0 和类型（联合类型反收窄）：`if(x is A)` 的 else 分支 x 收窄为「联合减 A」——
            // 仅当条件为类型测、目标为名字、原名称为联合且被测类型确为变体时成立；否则不收窄（保持原行为）。
            val elseF: Frame? = if (cond0 is TypeTestExpr) {
                val tt = cond0
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
        is WhenExpr -> checkWhen(e, f, pure, flow)
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
        is FlowExpr -> {
            // v2.0 异步（root doc《异步》）：`Flow { … }` 创建惰性冷流——冷流（collect 才执行），
            // 体天然 @async（可 await 挂起源）。体内 emit(v) 登记进 flowElemStack 收集元素类型；
            // Flow[T]：T = 全部 emit 值类型 join（无 emit 则 Null）。
            flowUsed = true
            val savedAsync = curFunAsync
            curFunAsync = true
            val elemTypes = ArrayList<Type>()
            flowElemStack.addLast(elemTypes)
            try { checkExpr(e.body, f, pure) }
            finally {
                flowElemStack.removeLast()
                curFunAsync = savedAsync
            }
            val elemT = if (elemTypes.isEmpty()) namedT("Null", emptyList())
                else elemTypes.reduce { a, b -> TypeInfer.unify(a, b) ?: UnionType(listOf(a, b)) }
            namedT("Flow", listOf(elemT))
        }
        is ByExpr -> {
            // t30：props 不再免验证注入 Γ，改为**断言**——值/类型同 target，断言延迟到包围
            // 语句/函数体检查完（名字绑定与事实注入就绪）后经 flushByAsserts 用 proveOrDeep 核对，
            // 证不出报 E-CANNOT-PROVE（编译期核对、运行期擦除，JsCodeGen 只留 target）
            val t = checkExpr(e.target, f, pure)
            if (e.props.isNotEmpty()) pendingByAsserts += e.props to e.pos
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
            val declType = e.type
            if (declType != null) {
                checkTypeResolvable(declType, emptyList())
                val want = syms.expand(declType)   // 别名展开（决策 30）
                // v1.1 修复：标注类型即变量类型（Kotlin 语义）；unify 只做兼容性检查——
                // 过去用 unify 结果当类型，遇泛型初值（如 setEmpty(): Set[T]）会把未绑定的
                // 类型参数 T 残留进变量类型，导致后续方法调用实参失配
                if (TypeInfer.unify(t0, want) == null && !compatibleView(t0, want))
                    d.error("E-TYPE-MISMATCH", e.pos, "var ${e.name} 标注了 ${declType.render()}，但初值是 ${t.render()}，两边类型不一致")
                else t = want
            }
            f.declareVar(e.name, t, e.pos)
            val mut = "mut" in e.annotations
            if (mut) f.declareMut(e.name)
            if ("ref" in e.annotations) f.declareRef(e.name)
            if (e.value is NameRef && binderNeedsCopy(t, t0, "ref" in e.annotations, f.isRef((e.value as NameRef).name), "mut" in e.annotations, f.isMut((e.value as NameRef).name)))
                deepCopyHits[e] = true
            // root doc @ref：上下文对引用变量态度与可变变量一致——不注入绑定等式
            if (isPureValue(e.value) && !mut && "ref" !in e.annotations && !refsMut(e.value, f) && !isMutStructType(t)) f.inject(PAtom("=", listOf(NameRef(e.name), e.value)))
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
            val abody = e.body
            val bt = if (abody != null) {
                val bt0 = checkExpr(abody, nf, pure)
                val rt = e.retType
                if (rt != null && !bt0.isSynthetic() && !rt.isAnyExpect() && !typeLooseEq(bt0, rt))
                    d.error("E-TYPE-MISMATCH", e.pos, "匿名函数体类型 ${bt0.render()} 与声明 ${rt.render()} 不符")
                val tail = dollarOf(abody)
                for (post in e.posts) {
                    val goal = PropLogic.substDollar(PropLogic.fromExpr(post), tail)
                    if (!proveOrDeep(nf.allFacts(), goal))
                        d.error("E-POST-UNPROVEN", e.pos, "匿名函数声明了保证 `${PropLogic.render(goal)}`，但函数体没证出")
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
        is IsPatExpr -> namedT("Bool", emptyList())
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
            // v3.0（区间 in）：字面量区间 x in lo..hi 或 Range 变量 x in r（r : Range[T]）都合法
            val isLiteralRange = e.right is BinExpr && (e.right as BinExpr).op == ".."
            val rt = syms.expand(r)
            val isRangeVar = rt is NamedType && rt.name == "Range"
            if (isLiteralRange || isRangeVar) return namedT("Bool", emptyList())
            d.error("E-TYPE-MISMATCH", e.pos, "in 右侧须是区间 lo..hi 或 Range[T] 值（v3.0 V1；集合成员待 v3.1）")
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
                    d.warn("W-DIV-ZERO", e.pos, "除数是 0，运行时会导致错误。检查除数是否可能为 0")
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

/** 激进模式重载（--prover=aggressive；普通模式重载行为完全一致） */
fun checkFile(
    file: FileAst, aggressive: Boolean, proveVerbose: Boolean = false,
    proveIntervalMs: Long = 5_000L, proveDumpPath: String? = null,
): DiagBag = Checker(file, aggressive = aggressive, proveVerbose = proveVerbose,
    proveIntervalMs = proveIntervalMs, proveDumpPath = proveDumpPath).run()

    // ---------- 根文档《手动证明》：@simp 化简器 ----------

    /** @simp 化简器：委托给 ArithProve 的线性算术证明器（区间+常量折叠+不等式）。 */
    private fun simpProve(goal: Prop, f: Frame): Boolean =
        ArithProve.proves(f.allFacts(), goal) == true

    /**
     * @subst 莱布尼茨代换。
     * ① 项级：用 `=`/`==` 等式事实单向代入 PAtom 的任意 term，检查代入后的 PAtom 是否已在 Γ。
     * ② 命题级：用 \u2194/iff 等价事实单向代入目标中的子命题，检查代入后的目标是否已在 Γ。
     */
    /** 项节点数——给 @subst BFS 设体积上限，防膨胀型改写（2→√2·√2）指数爆炸 */
    private fun termSize(e: Expr): Int = when (e) {
        is BinExpr -> 1 + termSize(e.left) + termSize(e.right)
        is UniExpr -> 1 + termSize(e.operand)
        is CallExpr -> 1 + termSize(e.callee) + e.args.sumOf { termSize(it) }
        is InstExpr -> 1 + termSize(e.target) + e.terms.sumOf { termSize(it) }
        is FieldExpr -> 1 + termSize(e.target)
        is FunRefExpr -> 1 + termSize(e.target)
        is SymbolCallExpr -> 1 + e.args.sumOf { termSize(it) }
        else -> 1
    }
    private fun propSize(p: Prop): Int = when (p) {
        is PNot -> 1 + propSize(p.p)
        is PAnd -> 1 + propSize(p.a) + propSize(p.b)
        is POr -> 1 + propSize(p.a) + propSize(p.b)
        is PImp -> 1 + propSize(p.a) + propSize(p.b)
        is PIff -> 1 + propSize(p.a) + propSize(p.b)
        is PForall -> 1 + propSize(p.body)
        is PExists -> 1 + propSize(p.body)
        is PExists1 -> 1 + propSize(p.body)
        is PAtom -> 1 + p.terms.sumOf { termSize(it) }
        else -> 1
    }

    private fun substProve(goal: Prop, f: Frame): Boolean {
        val facts = f.allFacts()
        val eqs = facts.filterIsInstance<PAtom>().filter { (it.head == "=" || it.head == "==") && it.terms.size == 2 }
        val iffs = facts.filterIsInstance<PIff>()
        if (eqs.isEmpty() && iffs.isEmpty()) return false
        // 深度替换 + 多步传递闭包（BFS）：@subst 目标经等式/等价图逐次改写，直至命中事实库
        val seen = HashMap<Int, MutableList<Prop>>()
        val queue = ArrayDeque<Prop>()
        queue.addLast(goal)
        var steps = 0
        val maxSteps = 400      // BFS 步数上限
        val maxNodes = 400      // 项体积上限：禁止改写出超大命题（膨胀型等式会指数爆炸）
        while (queue.isNotEmpty() && ++steps <= maxSteps) {
            val cur = queue.removeFirst()
            if (existsIn(cur, facts)) return true
            val bucket = seen.getOrPut(PropLogic.propHash(cur)) { ArrayList() }
            if (bucket.any { PropLogic.propStructEq(it, cur) }) continue
            bucket.add(cur)
            for (eq in eqs) {
                val a = eq.terms[0]; val b = eq.terms[1]
                for ((from, to) in listOf(a to b, b to a)) {
                    val rw = deepReplaceProp(cur, from, to)
                    if (!PropLogic.propStructEq(rw, cur) && propSize(rw) <= maxNodes) queue.addLast(rw)
                }
            }
            for (iff in iffs) {
                for ((from, to) in listOf(iff.a to iff.b, iff.b to iff.a)) {
                    val sub = replaceProp(cur, from, to)
                    if (!PropLogic.propStructEq(sub, cur) && propSize(sub) <= maxNodes) queue.addLast(sub)
                }
            }
        }
        return false
    }

    /** 在 allFacts 中找 p（忽略位置的结构相等）；另承认平凡自反等式 t = t */
    private fun existsIn(p: Prop, facts: List<Prop>): Boolean =
        facts.any { PropLogic.propStructEq(it, p) } || reflexiveEq(p)

    private fun reflexiveEq(p: Prop): Boolean =
        p is PAtom && p.head in setOf("=", "==") && p.terms.size == 2 && PropLogic.termEq(p.terms[0], p.terms[1])

    /** 命题内项级深度替换：PAtom 每个 term 递归穿透替换，其余命题连接词递归 */
    private fun deepReplaceProp(p: Prop, from: Expr, to: Expr): Prop = when (p) {
        is PAtom -> PAtom(p.head, p.terms.map { deepReplaceTerm(it, from, to) })
        is PNot -> PNot(deepReplaceProp(p.p, from, to))
        is PAnd -> PAnd(deepReplaceProp(p.a, from, to), deepReplaceProp(p.b, from, to))
        is POr -> POr(deepReplaceProp(p.a, from, to), deepReplaceProp(p.b, from, to))
        is PImp -> PImp(deepReplaceProp(p.a, from, to), deepReplaceProp(p.b, from, to))
        is PIff -> PIff(deepReplaceProp(p.a, from, to), deepReplaceProp(p.b, from, to))
        is PForall -> PForall(p.names, deepReplaceProp(p.body, from, to))
        is PExists -> PExists(p.names, deepReplaceProp(p.body, from, to))
        is PExists1 -> PExists1(p.names, deepReplaceProp(p.body, from, to))
        else -> p
    }

    /** 项级深度替换：递归穿透复合项内层，替换所有等于 from 的子项 */
    private fun deepReplaceTerm(e: Expr, from: Expr, to: Expr): Expr {
        if (PropLogic.termEq(e, from)) return to
        return when (e) {
            is BinExpr -> e.copy(left = deepReplaceTerm(e.left, from, to), right = deepReplaceTerm(e.right, from, to))
            is UniExpr -> e.copy(operand = deepReplaceTerm(e.operand, from, to))
            is CallExpr -> e.copy(callee = deepReplaceTerm(e.callee, from, to), args = e.args.map { deepReplaceTerm(it, from, to) })
            is InstExpr -> e.copy(target = deepReplaceTerm(e.target, from, to), terms = e.terms.map { deepReplaceTerm(it, from, to) })
            is FieldExpr -> e.copy(target = deepReplaceTerm(e.target, from, to))
            is FunRefExpr -> e.copy(target = deepReplaceTerm(e.target, from, to))
            else -> e
        }
    }

    /** 子命题代换：\u2194 等价代入的目标不以 term 身份出现，而是整个子命题替换 */
    private fun replaceProp(p: Prop, from: Prop, to: Prop): Prop = when {
        PropLogic.propStructEq(p, from) -> to
        p is PNot    -> PNot(replaceProp(p.p, from, to))
        p is PAnd    -> PAnd(replaceProp(p.a, from, to), replaceProp(p.b, from, to))
        p is POr     -> POr (replaceProp(p.a, from, to), replaceProp(p.b, from, to))
        p is PImp    -> PImp(replaceProp(p.a, from, to), replaceProp(p.b, from, to))
        p is PIff    -> PIff(replaceProp(p.a, from, to), replaceProp(p.b, from, to))
        p is PForall -> PForall(p.names, replaceProp(p.body, from, to))
        p is PExists -> PExists(p.names, replaceProp(p.body, from, to))
        p is PExists1-> PExists1(p.names, replaceProp(p.body, from, to))
        else -> p
    }
