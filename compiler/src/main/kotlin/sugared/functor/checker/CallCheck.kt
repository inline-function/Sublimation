package sugared.functor.checker

import sugared.functor.ast.*

// ============ 调用 ============

/** v2.0 异步（决策 93）：Task 对象方法名（taskJs 生成的 JS 对象字段） */
internal val taskMethods = setOf("start", "join", "isAlive")

/** v2.0 异步（决策 93）：Channel 对象方法名（channel 运行时 class 的方法） */
internal val channelMethods = setOf("send", "receive", "close", "isClosed")
/** v2.0 异步（root doc《异步》）：Mutex 对象方法名（lock/unlock/withLock，均挂起） */
internal val mutexMethods = setOf("lock", "unlock", "withLock")
/** v2.0 异步（root doc《异步》）：Flow 冷流对象方法名（collect 消费 / map 组合） */
internal val flowMethods = setOf("collect", "map")

/** 任务调用的目标变量名：`t.start()` → "t"（NameRef 直接取；其他形态不做跨表达追踪） */
private fun taskTargetName(e: Expr): String? = when (e) {
    is NameRef -> e.name
    else -> null
}

/** O3（决策 69）：命名字段构造 `A(name = e, age = e2)`——按字段名核对，缺省字段须有声明默认值 */
internal fun Checker.checkStructCtor(e: StructCtorExpr, f: Frame, pure: Boolean): Type {
    val st = syms.structs[e.struct] ?: run {
        d.error("E-UNBOUND-NAME", e.pos, "构造未定义结构体 ${e.struct}"); return syntheticT("构造${e.struct}")
    }
    // HKT-2（《高阶类型.md》§7.2）：命名字段构造同样反推类型实参（Pair(first = 1, second = "a") → Pair[Nat, Str]）
    val tps = st.theory.filterIsInstance<TypeParam>().map { it.name }
    val inferred = LinkedHashMap<String, Type>()
    val seen = LinkedHashSet<String>()
    for ((fname, valExpr) in e.assigns) {
        if (!seen.add(fname)) d.error("E-DUP-FIELD", e.pos, "构造 ${e.struct} 重复字段 $fname")
        if (st.fields.none { it.name == fname })
            d.error("E-UNBOUND-NAME", e.pos, "结构体 ${e.struct} 无字段 $fname")
        val vt = checkExpr(valExpr, f, pure)
        val fp = st.fields.firstOrNull { it.name == fname } ?: continue
        if (!vt.isSynthetic())
            extractTpBinding(fp.type, vt, tps.toSet(), inferred)
        val want = syms.expand(fp.type).substT(inferred)
        if (!vt.isSynthetic() && want.isNominal() && !typeLooseEq(want, vt) &&
            !(want.name == "Any" && want.args.isEmpty()))
            d.error("E-TYPE-MISMATCH", e.pos, "构造 ${e.struct} 字段 $fname 的实参 ${vt.render()} 与 ${want.render()} 不符")
    }
    st.fields.forEach { fp ->
        if (fp.name !in seen && fp.default == null)
            d.error("E-FIELD-MISSING", e.pos, "构造 ${e.struct} 缺字段 ${fp.name} 且无默认值")
    }
    // v3.0 特性 1：命名式构造同样做伴生命题证明（修复 #7：缺省字段填默认值）
    checkStructProps(st, f, e.pos, st.fields.map { fp ->
        fp.name to (e.assigns.firstOrNull { it.first == fp.name }?.second ?: fp.default ?: NameRef("__missing"))
    }.toMap())
    return namedT(e.struct, tps.map { inferred[it] ?: syntheticT("类型参数$it") })
}

/** HKT-D1 调用核对补丁：实参检查位的 kind 形参应用（`F[A]`）经 unifyInto 绑定 F:=具体构造子（零实参头）后，
 *  substT 不替换应用形态——此处手动把头换成绑定构造子、实参位递归 substT（形参 F[A] → Set[T]）。 */
private fun applyKindHead(pt: Type, want0: Type, tsub: Map<String, Type>): Type {
    if (pt !is NamedType || pt.args.isEmpty()) return want0
    val bound = tsub[pt.name] as? NamedType ?: return want0
    if (bound.args.isNotEmpty()) return want0
    return NamedType(bound.name, pt.args.map { it.substT(tsub) })
}

/** 约束方法调用（consSlot）的签名-实参合一：把类成员形参里的类型变量解成调用实参类型。
 *  只解零实参类型变量（fold 的 R ← init 实参）与同名同结构容器（F[A] vs F[A'] 递归实参位）、
  函数类型（(R, A) => R vs 实参函数值）——kind 形参本体（F）保持约束位不实例化。 */
private fun unifySigType(sigT: Type, argT: Type, sub: MutableMap<String, Type>) {
    when {
        sigT is NamedType && sigT.args.isEmpty() ->
            if (sigT.name !in sub) sub[sigT.name] = argT
        sigT is NamedType && argT is NamedType && sigT.name == argT.name && sigT.args.size == argT.args.size ->
            sigT.args.zip(argT.args).forEach { (a, b) -> unifySigType(a, b, sub) }
        sigT is FunType && argT is FunType -> {
            sigT.params.zip(argT.params).forEach { (a, b) -> unifySigType(a, b, sub) }
            unifySigType(sigT.ret, argT.ret, sub)
        }
        sigT is TupleType && argT is TupleType && sigT.items.size == argT.items.size ->
            sigT.items.zip(argT.items).forEach { (a, b) -> unifySigType(a, b, sub) }
    }
}

internal fun Checker.checkCall(c: CallExpr, f: Frame, pure: Boolean): Type {
    val argTypes = c.args.map { checkExpr(it, f, pure) }
    // P0（M3/M5/M6）：跨模块限定调用 `core.gcd(...)` / `c.Point(...)` / `core.show(p)`
    if (c.callee is FieldExpr) {
        val q = resolveQChain(c.callee, f)
        if (q != null) return checkQualifiedCall(q, c, f, pure, argTypes)
    }
    var instTerms: List<Expr> = emptyList()
    // O2（决策 68，接收者调用糖）：`o.m(args)` 与 `m(o, args)` 双形态。
    // 方法路径上自由形态的首实参是 self，剩余实参才是形参表。
    // 自由形态仅在"是方法名且不是具名函数/构造子/结构体"时成立——具名函数优先（现行解析顺序）。
    // P0（M6，impl 也要挂载）：方法名判定跨可见模块（含兄弟/挂载模块的 impl），不再只看本模块。
    // v2.0 异步（决策 93）：Task 对象方法（start/join/isAlive）需纳入方法路径——它们不是类型类方法/自由函数，
    // 但必须是接收者形态（o.start()）走 Task 特判；裸名 start(...) 不会被误解析。
    val fieldCallee: FieldExpr? = (c.callee as? FieldExpr)?.takeIf { isMethodName(it.name) || hasFreeFun(it.name) || it.name in taskMethods || it.name in channelMethods || it.name in mutexMethods || it.name in flowMethods }
    val freeName = (c.callee as? NameRef)?.name
        ?: ((c.callee as? InstExpr)?.target as? NameRef)?.name
    // W-UNUSED（报错增强，v2.0）：callee 位置的参数引用也算使用。此前只在 checkExpr 的 NameRef 分支
    // markUsed（实参/主体位），callee 位被直接按名字解析、从不 markUsed —— 导致 `fun f(x:..)=f(x)` 里
    // 的函数型参数 f 被误报「从未使用」（core 标准库 Result.mapOk/flatMapOk 等全部中招）。
    if (freeName != null && f.lookupVar(freeName) != null) f.markUsed(freeName)
    // P12：findCallable 遍历可见模块较重——只算一次，isFreeFormMethod 与后续 fc 复用同一结果。
    var fcEarly: Pair<FunDecl, String>? = if (fieldCallee != null) null else freeName?.let { findCallable(it, argTypes) }
    // HKT 标准库：自由函数命中但首形参与首实参不匹配（Set 的自由 flatMap 遇 List 实参），而名字同时是
    // 类型类方法（impl 可按字典分发）→ 弃自由函数命中，转方法形态（isFreeFormMethod → resolveDict）。
    // 否则按 Set 版 flatMap 检查 List 实参，误报 E-TYPE-MISMATCH（impl Monad[List] 体内递归 flatMap）。
    var freeFunDropped = false
    if (fcEarly != null && freeName != null && isMethodName(freeName) && argTypes.isNotEmpty()) {
        val p0 = fcEarly.first.params.firstOrNull()?.type
        if (p0 != null && !paramMatchesRecv(syms.expand(p0), argTypes[0])) { fcEarly = null; freeFunDropped = true }
    }
    val isFreeFormMethod = fieldCallee == null && freeName != null &&
        (freeFunDropped || (isMethodName(freeName) &&
        syms.findFun(freeName) == null && !syms.ctors.containsKey(freeName) && !syms.structs.containsKey(freeName) &&
        fcEarly == null))   // 扩展调用法规则：普通调用法 f(x,..) 有可见自由函数时优先自由函数（类型类方法仅兜底）；
        // freeFunDropped：自由函数首形参不匹配而名字是类型类方法 → 亦按方法形态走字典分发
    // HKT（《高阶类型.md》§7.1，方案 A）：带参类型类方法（`map(f, fa)`）的字典键是 **kind 应用位**（fa，含
    // arity>0 形参应用的那一参），不是首实参；且 fn 需要全部实参（self 即 fa 位，不 drop）。
    val hktKindPos = when {
        fieldCallee != null -> hktKindParamIndex(fieldCallee.name)
        isFreeFormMethod -> hktKindParamIndex(freeName!!)   // isFreeFormMethod 蕴含 freeName != null
        else -> -1
    }
    val selfT: Type? = when {
        fieldCallee != null -> checkExpr(fieldCallee.target, f, pure)
        isFreeFormMethod && argTypes.isNotEmpty() ->
            if (hktKindPos >= 0) argTypes[hktKindPos.coerceAtMost(argTypes.size - 1)] else argTypes[0]   // 首实参已检查过，复用类型避免诊断翻倍
        else -> null
    }
    val effArgs: List<Expr> = when {
        isFreeFormMethod -> if (hktKindPos >= 0) c.args else c.args.drop(1)
        // HKT 点号式 `xs.map(f)`：kind 应用位（fa）前没有该实参——把接收者插到 fa 位（其余照序）
        fieldCallee != null && hktKindPos >= 0 -> c.args.toMutableList().apply { add(hktKindPos, fieldCallee.target) }
        else -> c.args
    }
    val effTypes: List<Type> = when {
        // v2.1（限制解开）：free 形态首实参即接收者（self 隐式填 __self），自 fn.params 配对中剔除；
        // field 形态接收者单独成 __self，显式实参按 fn.params（不含 self）配对。
        isFreeFormMethod -> if (hktKindPos >= 0) argTypes else argTypes.drop(1)
        fieldCallee != null && hktKindPos >= 0 && selfT != null -> argTypes.toMutableList().apply { add(hktKindPos, selfT) }
        else -> argTypes
    }
    val isMethodPath = fieldCallee != null || isFreeFormMethod
    // P8（决策 84）：方法路径不支持命名参数——在方法入口显式拦截（方法参数管道不消费 namedArgs，否则静默丢弃）
    if (isMethodPath && c.namedArgs.isNotEmpty())
        d.error("E-NAMED-ARG", c.pos, "方法 `.f(x: 1)` 不支持命名参数。命名实参只用于自由函数：`f(x: 1)`")
    val name: String = when {
        fieldCallee != null -> fieldCallee.name
        else -> when (val callee = c.callee) {
            is NameRef -> callee.name
            is InstExpr -> when (val t = callee.target) {
                is NameRef -> { instTerms = callee.terms; t.name }
                else -> { checkExpr(t, f, pure); return syntheticT("调用") }
            }
            else -> { checkExpr(callee, f, pure); return syntheticT("调用") }
        }
    }

    // v3.0 特性 3：Range 惰性值的内建方法特判（contains/length/toList/map/fold）——成员访问生成
    if (selfT is NamedType && selfT.name == "Range") {
        rangeMethodType(name, selfT, argTypes, c)?.let { rt ->
            rangeHits[c] = name
            rangeUsed = true
            return rt
        }
    }

    if (selfT == null) {
        // O2：方法路径（self 已知）不参与构造子/结构体判定，避免同名截胡
        syms.ctors[name]?.let { ci -> return checkCtorCall(ci, name, c, argTypes, modulePath) }
        syms.structs[name]?.let { st -> return checkStructCall(st, name, c, argTypes, modulePath, f) }
        // 修复（#4）：构造子/结构体跨模块可见——裸名 Ok/Err/… 也查可见模块（含 core 标准库）
        if (moduleTree != null) {
            for (p in visiblePaths) {
                val s = allSymbols[p] ?: continue
                s.ctors[name]?.let { ci -> return checkCtorCall(ci, name, c, argTypes, p) }
                s.structs[name]?.let { st -> return checkStructCall(st, name, c, argTypes, p, f) }
            }
        }
    }

    // v2.0 异步（root doc《异步》）：Flow 体内 `emit(v)` 发送数据——仅 Flow 体上下文（flowElemStack 非空）合法。
    if (name == "emit" && flowElemStack.isNotEmpty()) {
        val vt = checkExpr(c.args.firstOrNull() ?: return namedT("Null", emptyList()), f, pure)
        flowElemStack.last().add(vt)   // 收集元素类型（join 出 Flow[T] 的 T）
        flowEmitHits[c] = true         // codegen：emit(v) → yield v
        return namedT("Null", emptyList())
    }

    // v2.0 异步（决策 93，异步 §3.1/§5.4）：Channel 创建 `Channel[T]()` / `Channel[T](cap)`。
    // 形态是 `callee = InstExpr(NameRef("Channel"))`，于构造子判定之后特判；创建点返回 Channel[T]。
    // localChannels（本地通道登记）在 VarStmt 层做（`var ch = Channel<…>()` 才能拿到变量名）。
    if (name == "Channel") {
        val tArg = (c.callee as? InstExpr)?.terms?.firstOrNull()
        val tT = when {
            tArg is NameRef -> namedT(tArg.name, emptyList())
            else -> { d.error("E-TYPE-MISSING", c.pos, "Channel 创建必须写明类型参数。写成 Channel[类型]()，例如 Channel[Nat]()"); syntheticT("Channel实参") }
        }
        if (c.args.size == 1 && argTypes[0].isNominal() && !typeLooseEq(argTypes[0], namedT("Nat", emptyList())))
            d.error("E-TYPE-MISMATCH", c.pos, "Channel 缓冲容量须为 Nat：Channel[${tT.render()}](${argTypes[0].render()})")
        if (c.args.size > 1) d.error("E-ARITY", c.pos, "Channel 创建最多一个容量实参")
        channelUsed = true   // 告诉 codegen 注入 Channel 运行时 class
        return namedT("Channel", listOf(tT))
    }

    // v2.0 异步（root doc《异步》）：Mutex 创建 `Mutex()`（无类型参数）。形态是 `callee = NameRef("Mutex")`。
    if (name == "Mutex" && c.args.isEmpty()) {
        for (a in c.args) checkExpr(a, f, pure)
        mutexUsed = true   // 告诉 codegen 注入 Mutex 运行时 class
        return namedT("Mutex", emptyList())
    }

    // v1.1 集合方法糖提前路径：`o.m(args)` 且 m 是「同名自由函数」（非 typeclass 方法名）→ 脱糖为 m(o, args)。
    // 自由函数名（map/filter/forEach…）不进 isMethodName 分支，须在 fn 解析链（含 E-UNBOUND 回退）之前截获。
    // v2.0 异步（决策 93）：Task 对象方法（start/join/isAlive）与 Channel 对象方法（send/receive/close/isClosed）
    // 在方法糖**之前**特判——它们是 codegen 生成的 JS 对象/class 字段（天然生成 `t.start`），
    // 不登记 moduleHits/methodSugarArgs（避免覆盖字段访问形态）。
    if (fieldCallee != null && selfT is NamedType && selfT.args.size == 1) {
        val inner = selfT.args[0]
        when {
            name in taskMethods && selfT.name == "Task" -> {
                // E-NOT-STARTED 检查（TASK-7，异步 §1.6）：函数体内无 t.start() 而 t.join() → 报错。
                when (name) {
                    "start" -> { taskTargetName(fieldCallee.target)?.let { startedTasks += it }; return namedT("Null", emptyList()) }
                    "isAlive" -> return namedT("Bool", emptyList())
                    "join" -> {
                        val tn = taskTargetName(fieldCallee.target)
                        if (tn != null && tn !in startedTasks)
                            d.error("E-NOT-STARTED", c.pos, "任务 `$tn` 还没 start() 就 join() 了。先调 `$tn.start()` 再 `$tn.join()`")
                        if (curFunAsync) asyncAwaitHits[c] = true
                        else d.error("E-NEED-ASYNC", c.pos, "t.join() 是挂起操作，需 @async 上下文")
                        return inner
                    }
                }
            }
            name in channelMethods && selfT.name == "Channel" -> {
                // CH-6（异步 §3.6）：send 报 E-CHANNEL-CLOSED——当且仅当同一函数体内**顺序在前**存在 close(ch)；
                // CH-5（§3.5）：receive 智能转换——本地创建的通道（无 close）→ 返回 T；否则 T?。
                val cn = taskTargetName(fieldCallee.target)
                when (name) {
                    "send" -> {
                        checkExpr(c.args.firstOrNull() ?: return syntheticT("send"), f, pure)
                        if (cn != null && cn in closedChannels)
                            d.error("E-CHANNEL-CLOSED", c.pos, "通道 `$cn` 已经 close() 了，不能再 send。把 send 移到 close 之前")
                        if (curFunAsync) asyncAwaitHits[c] = true
                        else d.error("E-NEED-ASYNC", c.pos, "ch.send() 是挂起操作，需 @async 上下文")
                        return namedT("Bool", emptyList())
                    }
                    "receive" -> {
                        if (curFunAsync) asyncAwaitHits[c] = true
                        else d.error("E-NEED-ASYNC", c.pos, "ch.receive() 是挂起操作，需 @async 上下文")
                        // CH-5：本地创建（var ch = Channel<…>()）且未 close → 返回 T（编译器确定通道未关闭）；
                        // 阶段5：`if (!ch.isClosed())` 分支内（openChannels）同理 → T。
                        // 此时 codegen 需解包（receiveSmartHits 留痕）。参数位/未知一律 T?。
                        val smart = cn != null && ((cn in localChannels && cn !in closedChannels) || cn in openChannels)
                        receiveSmartHits[c] = smart
                        return if (smart) inner else namedT("Optional", listOf(inner))
                    }
                    "close" -> { cn?.let { closedChannels += it }; return namedT("Null", emptyList()) }
                    "isClosed" -> return namedT("Bool", emptyList())
                }
            }
        }
    }
    // v2.0 异步（root doc《异步》）：Mutex 对象方法（lock/unlock/withLock）——Mutex 无类型参数（0 个），
    // 与 Task/Channel 的单类型参数不同，单独 when 分支。均挂起：@async 上下文才允许。
    if (fieldCallee != null && selfT is NamedType && selfT.name == "Mutex" && selfT.args.isEmpty()) {
        when (name) {
            "lock" -> {
                if (curFunAsync) asyncAwaitHits[c] = true
                else d.error("E-NEED-ASYNC", c.pos, "m.lock() 是挂起操作，需 @async 上下文")
                return namedT("Null", emptyList())
            }
            "unlock" -> {
                if (curFunAsync) asyncAwaitHits[c] = true
                else d.error("E-NEED-ASYNC", c.pos, "m.unlock() 是挂起操作，需 @async 上下文")
                return namedT("Null", emptyList())
            }
            "withLock" -> {
                // 参数 0 是函数值（@impure ()=>Null）：仅做表达式检查，类型宽容（函数值形态）
                checkExpr(c.args.firstOrNull() ?: return syntheticT("withLock"), f, pure)
                if (curFunAsync) asyncAwaitHits[c] = true
                else d.error("E-NEED-ASYNC", c.pos, "m.withLock(f) 是挂起操作，需 @async 上下文")
                return namedT("Null", emptyList())
            }
            else -> { /* 其他方法名交给常规链 */ }
        }
    }
    // v2.0 异步（root doc《异步》）：Flow 冷流对象方法（collect/map）——Flow 有 1 个类型参数（元素类型），
    // 与 Task/Channel 同形。collect 挂起（消费异步流）；map 纯转换返回新 Flow。
    if (fieldCallee != null && selfT is NamedType && selfT.name == "Flow" && selfT.args.size == 1) {
        val elemT = selfT.args[0]
        when (name) {
            "collect" -> {
                checkExpr(c.args.firstOrNull() ?: return syntheticT("collect"), f, pure)
                if (curFunAsync) asyncAwaitHits[c] = true
                else d.error("E-NEED-ASYNC", c.pos, "fl.collect(f) 是挂起操作，需 @async 上下文")
                return namedT("Null", emptyList())
            }
            "map" -> {
                // map 实参是函数值 f：(T) => U；返回 Flow[U]（新冷流）。
                // 简单保守：函数值类型检查原样，返回 Flow 元素类型取函数返回类型（无法确定则保留 elemT）
                val farg = c.args.firstOrNull()
                val ft = farg?.let { checkExpr(it, f, pure) } ?: syntheticT("map实参")
                val elemU = if (ft is FunType) ft.ret else elemT
                return namedT("Flow", listOf(elemU))
            }
            else -> { /* 交给常规链 */ }
        }
    }
    if (fieldCallee != null && !isMethodName(fieldCallee.name)) {
        val sugarFn = methodSugar(fieldCallee.name, selfT, fieldCallee, c)
        if (sugarFn != null)
            return checkFnCall(sugarFn, fieldCallee.name, c, f, pure,
                methodSugarArgs[c]!!, listOfNotNull(selfT) + argTypes, isMethodPath = true)
        // 接收者类型未知（lambda 未定类型首查，arg 先于期望类型检查）→ 方法糖无法按接收者分派，
        // 静默推迟到「带期望类型的二次检查」（checkFnCall lambda 反查）再定，避免首查误报 E-UNBOUND-NAME。
        if (selfT != null && selfT.isSynthetic()) return syntheticT("方法糖推迟")
    }

    val fc = fcEarly   // 已在 checkCall 开头计算（P12）；v3.0 跨可见模块（含 core 标准库）
    val fn: FunDecl = fc?.first
        ?: run {
            // 类型类方法调用（决策 60，T5；O2 双形态）：按 self 类型解析字典，按调用点留痕给 codegen
            if (isMethodName(name)) {
                // O2：自由式 `m()` 无实参 → 缺接收者，专属码（区别于"有类型但无实例"）
                if (selfT == null) {
                    d.error("E-NO-SELF", c.pos, "方法 `$name` 缺接收者。写成 `接收者.$name(…)` 或 `$name(接收者, …)`")
                    return syntheticT("无自参$name")
                }
                // P0（M6）：单文件模式走本模块 impl 表；目录模式按可见路径跨模块收集
                // P6（决策 82）：self 类型是当前函数约束的类型参数（如 `x.show()` 中 x: T 且 T: Show）
                // 优先走约束字典槽——不查全局 impl（T 未实例化，查了必错 E-NO-INSTANCE）
                val consSlot = if (selfT is NamedType) funConsSlots[selfT.name]?.firstOrNull { (trait, _) ->
                    syms.classes[trait]?.members?.any { it.name == name } == true
                } else null
                if (consSlot != null) {
                    consHits[c] = consSlot.second
                    val sig = syms.classes[consSlot.first]?.members?.firstOrNull { it.name == name }
                    checkMutMethodView(name, selfT, sig?.annotations ?: emptyList(), c)
                    // HKT 标准库：返回类型按调用实参合一——类成员签名含类型变量（fold 的 R / A、
                    // fa 的 F[A]），裸返会与外层声明的返回类型不一致（size: Nat 体内 fold 返 R）。
                    // R ← init 实参类型；A ← fa 应用实参；kind 形参 F 保持（约束位，不再实例化）。
                    if (sig != null) {
                        val sub = LinkedHashMap<String, Type>()
                        sig.params.zip(effTypes).forEach { (p, at) -> unifySigType(p.type, at, sub) }
                        return sig.retType?.substT(sub) ?: syntheticT("约束方法${consSlot.first}.$name")
                    }
                    return syntheticT("约束方法${consSlot.first}.$name")
                }
                // v3.0：优先自由函数方法糖（含 ext 扩展方法；typeclass 声明的同名成员不再劫持——只有 class 无 impl 时报 E-NO-INSTANCE），
                // 未命中再走类型类字典（dict）。两者都无 → 报告无实例。
                val sugarFn = methodSugar(name, selfT, fieldCallee, c)
                if (sugarFn != null) sugarFn else run {
                    val res = resolveDict(name, selfT, if (moduleTree == null) null else visiblePaths)
                    if (res != null) {
                        checkMutMethodView(name, selfT, res.entry.fn.annotations, c)
                        dictHits[c] = res.dictName
                        // HKT 点号式 `xs.map(f)`：接收者 xs 在 kind 应用位（fa），codegen 按此重排（非首参位）
                        if (hktKindPos >= 0) hktKindPosHits[c] = hktKindPos
                        res.entry.fn
                    } else return syntheticT("无实例$name")
                }
            } else null
        }
        ?: run {
            // 回退：调用持有函数值的局部变量（如 `var id = \(x)=>x; id(1)`）
            // v3.0（完美函数类型）：变量类型是函数类型（含多态 `fun[T](x: T): T = x`）→ 实例化调用检查
            val vt = f.lookupVar(name)
            if (vt is FunType) return checkInlineFunCall(vt, c, f, pure, instTerms, argTypes)
            if (vt != null) return syntheticT("变量函数调用$name")
            d.error("E-UNBOUND-NAME", c.pos, "没找到函数 `$name`——拼写错了？还是没 mount 它所在的模块？（explain E-UNBOUND-NAME 看详细）"); return syntheticT("调用")
        }

    if (!isMethodPath && syms.funs.containsKey(name))
        moduleHits[c.callee] = jsOverloadName(modulePath, fn)   // P0：同模块全局调用按模块前缀留痕；v2.0 重载带首参标签
    else if (!isMethodPath && fc != null && fc.second != modulePath)
        moduleHits[c.callee] = jsOverloadName(fc.second, fn)   // v3.0：裸名跨模块（std）调用按来源模块留痕
    if (!isMethodPath && fc != null) noteListMethod(c, fn, fc.second)   // P9：core 标准库 List 方法留痕
    // v1.1 方法糖：接收者前置重排 `xs.map(f)` → `map(xs, f)`（实参类型首项为接收者类型）
    val sugar = methodSugarArgs[c]
    if (sugar != null)
        return checkFnCall(fn, name, c, f, pure, sugar, listOfNotNull(selfT) + argTypes, isMethodPath)
    // v2.1（限制解开）：非糖方法路径（类型类方法）——free 形态首实参即接收者，已在 effTypes 剔除；
    // field 形态接收者由 codegen 填 __self，显式实参（effArgs/effTypes）按 fn.params（不含 self）直接配对。
    return checkFnCall(fn, name, c, f, pure, effArgs, effTypes, isMethodPath)
}

/** root doc《副作用与可变性控制》可变类型（67-79 行）：@mut 函数只能在类型可变时调用——接收者须持有 @mut 视图。 */
private fun Checker.checkMutMethodView(name: String, selfT: Type, fnAnnos: List<String>, c: CallExpr) {
    if ("mut" in fnAnnos && selfT !is MutType)
        d.error("E-NOT-MUT", c.pos, "可变方法 " + name + " 只能在类型可变时调用——接收者须持有 @mut 视图（如 var o : @mut T）")
}

// ============ v1.1 集合方法糖：`o.m(args)` → 同名自由函数 m(o, args) ============

/** 方法糖命中：找「同名自由函数」（本模块 / 内置 / 可见模块含 core 标准库），且其**第一个形参**类型与接收者匹配。
 *  命中后登记实参重排（methodSugarArgs）与跨模块 JS 名（moduleHits），返回该函数供正常调用检查。 */
private fun Checker.methodSugar(name: String, selfT: Type?, fieldCallee: FieldExpr?, c: CallExpr): FunDecl? {
    if (fieldCallee == null || selfT == null) return null
    val (fn, mod) = findCollectionMethod(name, selfT) ?: return null
    methodSugarArgs[c] = listOf(fieldCallee.target) + c.args
    moduleHits[c.callee] = jsOverloadName(mod, fn)
    noteListMethod(c, fn, mod)
    d.supplement("D-METHOD-SUGAR", c.pos, "`.${name}()` 展开为自由函数 `${fn.name}(...)`（方法糖）")
    return fn
}

/** v2.0 重载：JS 名带首参标签后缀（与 JsCodeGen 声明侧规则一致）——仅当相应模块存在同名重载组 */
internal fun Checker.jsOverloadName(mod: String, fn: FunDecl): String {
    val hasOver = if (mod.isEmpty()) syms.funOverloads.containsKey(fn.name)
                  else allSymbols[mod]?.funOverloads?.containsKey(fn.name) == true
    return if (hasOver) "${moduleJsName(mod, fn.name)}\$${fnTag(fn)}" else moduleJsName(mod, fn.name)
}

/** P9：标记 core 标准库数组化 List 方法调用（调用点 → 方法名）。codegen 据此把 map/size/… 映射成 JS 原生数组方法，
 *  免去在 codegen 里硬编码模块前缀字符串比较；仅 core 标准库 collection 模块 + 首形参为 List 才算（用户自定义 List 函数不误中）。 */
internal fun Checker.noteListMethod(c: CallExpr, fn: FunDecl, mod: String) {
    if (mod != "core/collection") return
    val p0 = fn.params.firstOrNull() ?: return
    if (syms.expand(p0.type).name == "List") listMethodHits[c] = fn.name
}

/** 在「本模块 + 内置 + 可见模块（含 core 标准库）」中找同名自由函数且首参类型匹配接收者；返回 (函数, 模块路径)。
 *  v2.0：候选含同名的**重载**（不同首参类型）——按接收者类型挑选正确分派。 */
internal fun Checker.findCollectionMethod(name: String, selfT: Type): Pair<FunDecl, String>? {
    val cands = ArrayList<Pair<FunDecl, String>>()
    syms.findFun(name)?.let { cands += it to "" }
    syms.funOverloads[name]?.forEach { cands += it to "" }
    if (moduleTree != null) visiblePaths.forEach { p ->
        val s = allSymbols[p] ?: return@forEach
        s.findFun(name)?.let { cands += it to p }
        s.funOverloads[name]?.forEach { cands += it to p }
    }
    if (cands.isEmpty()) return null
    return cands.firstOrNull { (fn, _) ->
        val p0 = fn.params.firstOrNull()?.type ?: return@firstOrNull false
        paramMatchesRecv(syms.expand(p0), selfT)
    }
}

/** v3.0（完美函数类型）：多态函数值调用——`var id = fun[T](x: T): T = x` 后 `id(3)` / `id[Nat](3)`。
 *  实例化：显式 `f[X](…)`（InstExpr terms）优先；否则实参合一反推；返回实例化后的返回类型。 */
private fun Checker.checkInlineFunCall(
    ft: FunType, c: CallExpr, f: Frame, pure: Boolean, instTerms: List<Expr>, argTypes: List<Type>,
): Type {
    val tnames = ft.theory.map { it.name }
    val tsub = LinkedHashMap<String, Type>()
    if (instTerms.isNotEmpty()) {
        if (instTerms.size != tnames.size) {
            d.error("E-TYPE-MISMATCH", c.pos, "函数类型 ${ft.render()} 有 ${tnames.size} 个类型参数，显式给了 ${instTerms.size} 个")
            return syntheticT("内联函数调用")
        }
        tnames.forEachIndexed { i, tn ->
            val tt = typeOfTerm(instTerms[i])
            if (tt != null && !tt.isSynthetic()) tsub[tn] = tt
        }
        // v3.0（完美函数类型 soundness）：显式实例化后逐位核对实参与实例化形参——
        // `id<Nat>("a")`（实例化为 Nat 却传 Str）应报 E-TYPE-MISMATCH
        ft.params.zip(argTypes).forEachIndexed { i, (p, at) ->
            if (!at.isSynthetic()) {
                val want = p.substT(tsub)
                if (!want.isSynthetic() && !typeLooseEq(want, at))
                    d.error("E-TYPE-MISMATCH", c.pos, "实例化调用实参 ${i + 1} 类型 ${at.render()} 与实例化形参 ${want.render()} 不符")
            }
        }
    } else if (tnames.isNotEmpty()) {
        ft.params.zip(argTypes).forEach { (p, at) ->
            if (!at.isSynthetic()) unifyInto(p, at, tnames.toSet(), tsub, emptyMap())
        }
    }
    // V1（本轮第一刀）不做 arity/参数核对——保留 v2 对变量函数调用的宽容（`f(v)` 单元组实参形态等）；
    // 参数配对与诊断后续轮次收紧（与 checkFnCall 的宽严对齐另行设计）。
    // v3.0（完美函数类型）：函数值纯度随类型传递——体内含 impure 调用的 lambda 作值，纯上下文调用报 E-IMPURE-CALL
    // HKT 标准库（借用效应，forEach 的 f: @impure (A)=>Null）：callee 是本函数**声明的 @impure 函数值形参**时放行——
    // 形参即显式效应能力（能写出 impure lambda 的调用方本身必非纯，纯洁性不破）；lambda 层 impure 标记仍传递。
    if (ft.purity == "impure") {
        if (lambdaDepth > 0) curLambdaImpure = true
        val calleeName = (c.callee as? NameRef)?.name
        val pIdx = curFunParams.indexOf(calleeName)
        val borrowed = pIdx >= 0 && (paramTypes.getOrNull(pIdx) as? FunType)?.purity == "impure" &&
            (calleeName == null || f.lookupVar(calleeName) != null)
        if (pure && uncheckedDepth == 0 && !borrowed)
            d.error("E-IMPURE-CALL", c.pos, "纯作用域内调用非纯函数值 ${ft.render()}（该函数体含 impure 调用）")
    }
    // 根文档·上下文系统：函数值调用前须证明其类型携带的上下文（(T)<$1 in xs> => Null 之 $1 in xs）
    ft.context?.forEach { ctxExpr ->
        val rewritten = substParamRefToArg(ctxExpr, c.args)
        val goal = PropLogic.fromExpr(rewritten)
        if (!proveOrDeep(f.allFacts(), goal))
            d.error("E-CTX-UNPROVEN", c.pos, "调用函数值前须证明上下文 " + PropLogic.render(goal) + "，但编译器没证出（Γ：" +
                f.allFacts().joinToString("; ") { PropLogic.render(it) } + "）")
    }
    return ft.ret.substT(tsub)
}

/** \$n -> 第 n 个实参表达式（函数值调用点上下文的 \$1 in xs 内联为 arg1 in xs） */
internal fun Checker.substParamRefToArg(e: Expr, args: List<Expr>): Expr = when (e) {
    is ParamRefExpr -> args.getOrNull(e.index - 1) ?: e
    is BinExpr -> e.copy(left = substParamRefToArg(e.left, args), right = substParamRefToArg(e.right, args))
    is UniExpr -> e.copy(operand = substParamRefToArg(e.operand, args))
    is CallExpr -> e.copy(callee = substParamRefToArg(e.callee, args), args = e.args.map { substParamRefToArg(it, args) })
    is InstExpr -> e.copy(target = substParamRefToArg(e.target, args), terms = e.terms.map { substParamRefToArg(it, args) })
    is TupleExpr -> e.copy(items = e.items.map { substParamRefToArg(it, args) })
    else -> e
}

/** 类型实参表达式 → 类型（V1：具名类型与实例化应用，如 `Nat` / `List[Nat]`；`(T)=>U` 形态后续） */
internal fun Checker.typeOfTerm(e: Expr): Type? = when (e) {
    is NameRef -> namedT(e.name, emptyList())
    is InstExpr -> {
        val tn = (e.target as? NameRef)?.name ?: return null
        namedT(tn, e.terms.mapNotNull { typeOfTerm(it) })
    }
    else -> null
}

/** v2.0：普通（自由函数）调用按**实参首类型**从重载里挑选匹配项；无重载则退回主声明（保留原诊断路径）。
 *  v3.0：先查本模块；未命中再按可见路径（含 core 标准库挂载）跨模块找——Kotlin 默认导入风格，裸名可直接调用 core 函数。
 *  返回 (函数, 模块路径)；mod 供调用点 moduleHits 留痕。 */
private fun Checker.findCallable(name: String, argTypes: List<Type>): Pair<FunDecl, String>? {
    findCallableIn(syms, name, argTypes)?.let { return it to modulePath }
    if (moduleTree != null) for (p in moduleTree.bareVisibleOf(modulePath)) {
        if (p == modulePath) continue
        val s = allSymbols[p] ?: continue
        findCallableIn(s, name, argTypes)?.let { return it to p }
    }
    return null
}

/** 按目标模块符号表 + 实参首类型挑重载（限定调用 `core.collection.filter(xs, f)` 也须路由到 List 版，
 *  否则主声明先注册（如 Set 版）导致检查阶段选错版本报「List[Nat] 与 Set[T] 不符」）。 */
private fun Checker.findCallableIn(s: Symbols, name: String, argTypes: List<Type>): FunDecl? {
    val main = s.findFun(name)
    val ovs = s.funOverloads[name]
    if (ovs == null) return main
    val cands = ArrayList<FunDecl>(); main?.let { cands += it }; cands += ovs
    val arity = cands.filter { it.params.size == argTypes.size }
    val pool = if (arity.isNotEmpty()) arity else cands
    pool.firstOrNull { fn ->
        val p0 = fn.params.firstOrNull()?.type ?: return@firstOrNull true   // 0 参：匹配
        argTypes.isNotEmpty() && paramMatchesRecv(syms.expand(p0), argTypes[0])
    }?.let { return it }
    return main
}

/** HKT（《高阶类型.md》§7.1，方案 A）：带参类型类方法（`map(f, fa)`）的 kind 应用位——
 *  类声明签名里含 arity>0 形参应用的那一参下标（如 Functor[F[_]] 的 map 里 `fa: F[A]` 是第 1 位）。
 *  无 kind 形参（普通类型类如 Show）→ -1，调用方按旧「首参即 self」逻辑。 */
private fun Checker.hktKindParamIndex(name: String): Int {
    // v4（core 标准库 HKT 类型类）：类型类方法常声明在可见模块（core/collection 等挂载）——
    // 本模块 methods 表没有时，跨可见模块查 entries 与类声明，否则用户侧 `xs.map(f)` 取不到 kind 位、
    // 接收者不入参，误报 E-ARITY/E-TYPE-MISMATCH。
    val entries = syms.methods[name] ?: visibleMethodEntries(name) ?: return -1
    if (entries.isEmpty()) return -1
    // 取任一 impl 条目的类型类，读类声明的成员签名（impl 方法形参已实例化如 List[A]，看不出 kind 位）
    val trait = entries.first().trait
    val cls = syms.classes[trait] ?: visibleClass(trait) ?: return -1
    val arity = tpArityOf(cls)
    if (arity.isEmpty() || arity.values.none { it > 0 }) return -1
    val msig = cls.members.firstOrNull { it.name == name } ?: return -1
    return msig.params.indexOfFirst { p -> containsKindApp(syms.expand(p.type), arity) }
}

/** 跨可见模块按名字找类型类方法 impl 条目（hktKindParamIndex / 方法路径作值用；任一声明模块的表即可）。 */
internal fun Checker.visibleMethodEntries(name: String): List<MethodEntry>? {
    if (moduleTree == null) return null
    for (p in visiblePaths) {
        val s = allSymbols[p] ?: continue
        s.methods[name]?.let { return it }
    }
    return null
}

/** 跨可见模块按名字找类型类声明（hktKindParamIndex 用）。 */
internal fun Checker.visibleClass(trait: String): ClassDecl? {
    if (moduleTree == null) return null
    for (p in visiblePaths) {
        val s = allSymbols[p] ?: continue
        s.classes[trait]?.let { return it }
    }
    return null
}

/** 类型里是否含 kind 形参应用（NamedType 名是 arity>0 的形参且带实参，如 `F[A]`） */
private fun containsKindApp(t: Type, arity: Map<String, Int>): Boolean = when (t) {
    is NamedType -> (arity[t.name] ?: 0) > 0 && t.args.isNotEmpty() || t.args.any { containsKindApp(it, arity) }
    is FunType -> t.params.any { containsKindApp(it, arity) } || containsKindApp(t.ret, arity)
    is TupleType -> t.items.any { containsKindApp(it, arity) }
    else -> false
}

/** 首参（want）与接收者（self）类型匹配：同基名；类型实参递归（List[T] vs List[Nat]：T 是类型参数则通配）
 *  v1.1：别名先展开（Set[T]/Map[K,V] 都是 List 别名，方法路由按展开后的结构匹配）；
 *  want 侧未解析裸名（类型参数，非声明类型/内建/synthetic）视为通配 */
private fun Checker.paramMatchesRecv(want0: Type, self0: Type): Boolean {
    val want = syms.expand(want0); val self = syms.expand(self0)
    if (want is FunType || self is FunType) return false
    if (want is NamedType && want.args.isEmpty() && want.name !in BASE_TYPES && want.name !in syms.enums &&
        want.name !in syms.structs && !want.isSynthetic()) return true
    fun base(t: Type): String = (t as? NamedType)?.name ?: t.render()
    if (base(want) != base(self)) return false
    val wa = (want as? NamedType)?.args ?: emptyList()
    val sa = (self as? NamedType)?.args ?: emptyList()
    if (wa.isEmpty()) return true
    if (sa.size != wa.size) return false
    return sa.zip(wa).all { (s, w) -> w.isSynthetic() || paramMatchesRecv(w, s) }
}

// ============ P0：跨模块限定调用与公共检查段 ============

/** 方法名判定（决策 60，T5）：本模块 impl/类型类候选，或可见模块（M6：impl 也要挂载）里的。 */
private fun Checker.isMethodName(n: String): Boolean =
    n in syms.methods || n in syms.traitMethods ||
        (moduleTree != null && visiblePaths.any { p ->
            val s = allSymbols[p]
            s != null && (s.methods.containsKey(n) || n in s.traitMethods)
        })

/** v1.1：是否存在同名自由函数（本模块 / 内置 / 可见模块含 core 标准库）——集合方法糖候选（v2.0：含重载） */
private fun Checker.hasFreeFun(n: String): Boolean =
    syms.findFun(n) != null || syms.funOverloads.containsKey(n) ||
        (moduleTree != null && visiblePaths.any {
            val s = allSymbols[it]
            s != null && (s.findFun(n) != null || s.funOverloads.containsKey(n))
        })

/** 构造子调用公共段（决策 46/60，T5）：类型参数由实参推断；限定/普通共用。 */
internal fun Checker.checkCtorCall(ci: CtorInfo, name: String, c: CallExpr, argTypes: List<Type>, modPath: String): Type {
    // 内置 prelude 构造子（Some/None/null/true/false）在 JS 里是根作用域函数/字面量，无模块前缀——
    // 否则 core 标准库模块内 `Some(x)` 会被错生成成 `core_collection__Some`（P0 限制：多模块同名构造子取末者）
    if (name !in setOf("Some", "None", "null", "true", "false"))
        moduleHits[c.callee] = moduleJsName(modPath, name)
    // 先算类型参数名与实参推断：字段类型是类型参数（T）时由实参推断，不参与相等检查
    val tps = ci.enum.theory.filterIsInstance<TypeParam>().map { it.name }
    val inferred = LinkedHashMap<String, Type>()
    ci.fields.forEachIndexed { i, ft ->
        if (i < argTypes.size && !argTypes[i].isSynthetic())
            extractTpBinding(ft, argTypes[i], tps.toSet(), inferred)   // P2：含嵌套（Cons(T, List<T>) 的 List<T>）
    }
    if (ci.fields.size != argTypes.size)
        d.error("E-TYPE-MISMATCH", c.pos, "构造子 $name 期望 ${ci.fields.size} 实参，实际 ${argTypes.size}")
    else ci.fields.zip(argTypes).forEach { (rft, at) ->
        val ft = syms.expand(rft).substT(inferred)   // P2：字段比较用解出后的类型（与 checkFnCall 一致）
        val isTP = rft.name in tps && rft.args.isEmpty()   // 类型参数字段：跳过比较
        if (!isTP && !at.isSynthetic() && ft.isNominal() && !typeLooseEq(ft, at))
            d.error("E-TYPE-MISMATCH", c.pos, "构造子 $name 实参 ${at.render()} 与字段 ${ft.render()} 不符")
    }
    return namedT(ci.enum.name, tps.map { inferred[it] ?: syntheticT("类型参数$it") })
}

/** 结构体构造调用公共段（决策 69，O3）：位置式缺省尾部规则不变；限定/普通共用。 */
internal fun Checker.checkStructCall(st: StructDecl, name: String, c: CallExpr, argTypes: List<Type>, modPath: String, f: Frame): Type {
    moduleHits[c.callee] = moduleJsName(modPath, name)
    // HKT-2（《高阶类型.md》§7.2）：struct 泛型实参推断——与 enum 构造同逻辑（字段实参反推类型参数，
    // 如 Pair(1, "a") → Pair[Nat, Str]），HKT 构造子应用（Bifunctor for Pair[a,b]）依赖它拿到命名类型
    val tps = st.theory.filterIsInstance<TypeParam>().map { it.name }
    val inferred = LinkedHashMap<String, Type>()
    st.fields.forEachIndexed { i, fp ->
        if (i < argTypes.size && !argTypes[i].isSynthetic())
            extractTpBinding(fp.type, argTypes[i], tps.toSet(), inferred)
    }
    // O3（决策 69）：位置式允许缺省尾部——前提是尾部字段全有声明默认值
    // （命名字段构造 `A(name = e)` 由解析器产出独立节点 StructCtorExpr，走独立检查）
    if (st.fields.size < argTypes.size)
        d.error("E-TYPE-MISMATCH", c.pos, "构造 $name 期望 ${st.fields.size} 实参，实际 ${argTypes.size}")
    else if (st.fields.size > argTypes.size &&
        st.fields.drop(argTypes.size).any { it.default == null })
        d.error("E-FIELD-MISSING", c.pos, "构造 $name 缺尾部字段且无默认值")
    else st.fields.zip(argTypes).forEach { (fp, at) ->
        val ft = syms.expand(fp.type).substT(inferred)   // P2：字段比较用解出后的类型（与 checkCtorCall 一致）
        val isTP = fp.type.name in tps && fp.type.args.isEmpty()   // 类型参数字段：跳过比较
        if (!isTP && !at.isSynthetic() && ft.isNominal() && !typeLooseEq(ft, at)) {
            // 决策 59：Any 形参接受任意实参（受限顶类型）；其余仍须名义相等
            if (!(fp.type.name == "Any" && fp.type.args.isEmpty()))
                d.error("E-TYPE-MISMATCH", c.pos, "构造 $name 实参 ${at.render()} 与字段 ${ft.render()} 不符")
        }
    }
    // v3.0 特性 1：结构体伴生命题——构造前证明（E-STRUCT-PROP-UNPROVEN / E-STRUCT-MUT-PROP）
    // 修复（#7）：位置式缺省字段以声明默认值替换进命题（否则默认值不会参与伴生命题证明）
    checkStructProps(st, f, c.pos, st.fields.mapIndexed { i, fp ->
        fp.name to (c.args.getOrNull(i) ?: fp.default ?: NameRef("__missing"))
    }.toMap())
    return namedT(name, tps.map { inferred[it] ?: syntheticT("类型参数$it") })
}

/** 函数调用公共检查尾段（决策 54/59/60/65，O2）：纯度/参数核对/显式供给/前后文；限定/普通共用。 */
internal fun Checker.checkFnCall(
    fn: FunDecl, name: String, c: CallExpr, f: Frame, pure: Boolean,
    effArgsIn: List<Expr>, effTypesIn: List<Type>, isMethodPath: Boolean,
): Type {
    // core.async 内建运行时层：调用 Channel/Mutex/Flow 的配套 @root 内建即用到对应运行时——
    // 置位通知 codegen 注入小运行时 class（Channel/Mutex/__flow），与 Channel<T>()/Mutex()/Flow{} 创建点同机制。
    when (name) {
        in CHANNEL_BUILTINS -> channelUsed = true
        in MUTEX_BUILTINS -> mutexUsed = true
        in FLOW_BUILTINS -> flowUsed = true
    }
    val tps = tpNamesOf(fn)
    // @list 形参 = Kotlin vararg（吸收剩余位置实参打包成 List）；函数级作用域，供下方 arity 核对复用
    val listIdx = fn.params.indexOfFirst { "list" in it.annotations }
    // P8（决策 84）：命名参数规范化——与位置实参合并为按形参声明顺序的实参列表（Kotlin 风格：
    // 位置实参填前 N 位，命名实参填指定位；命名覆盖已占位 → E-NAMED-ARG；缺参 → E-TYPE-MISMATCH）。
    // codegen 侧用 namedArgOrder 留痕取规范化实参（按声明顺序重排）。
    var effArgs = effArgsIn
    var effTypes = effTypesIn
    if (c.namedArgs.isNotEmpty()) {
        if (isMethodPath) {
            d.error("E-NAMED-ARG", c.pos, "方法 `.f(x: 1)` 不支持命名参数。命名实参只用于自由函数：`f(x: 1)`")
        } else if (fn.params.any { it.destruct != null || it.name == "_" }) {
            // P4（v2.1）：解构或 _ 参数 → 禁用指名参数语法糖（用户第 4 项）
            d.error("E-NAMED-ARG", c.pos, "函数 $name 含解构或 _ 参数，不支持命名参数")
        } else {
            val names = fn.params.map { it.name }
            c.namedArgs.keys.forEach { n ->
                if (n !in names) d.error("E-NAMED-ARG", c.pos, "函数 `$name` 没有叫 `$n` 的参数（拼写错了？可用参数：" + names.joinToString(", ") + "）")
            }
            val taken = BooleanArray(fn.params.size)
            val merged = arrayOfNulls<Expr>(fn.params.size)
            val mergedT = arrayOfNulls<Type>(fn.params.size)
            // @list 形参 = Kotlin vararg——listIdx 已在函数级声明
            if (listIdx < 0 && effArgsIn.size > fn.params.size)
                d.error("E-ARITY", c.pos, "函数 `$name` 只想要 ${fn.params.size} 个实参，但你传了 ${effArgsIn.size} 个（多了）")
            effArgsIn.forEachIndexed { i, a ->
                if (i < fn.params.size) { merged[i] = a; mergedT[i] = effTypesIn[i]; taken[i] = true }
            }
            fn.params.forEachIndexed { i, p ->
                val nv = c.namedArgs[p.name] ?: return@forEachIndexed
                if (taken[i]) d.error("E-NAMED-ARG", c.pos, "函数 $name 的参数 ${p.name} 已被位置实参占用")
                merged[i] = nv; mergedT[i] = checkExpr(nv, f, pure); taken[i] = true
            }
            val missing = fn.params.indices.filter { !taken[it] && it != listIdx && fn.params[it].default == null }
            if (missing.isNotEmpty())
                d.error("E-TYPE-MISMATCH", c.pos, "函数 $name 缺实参：${missing.joinToString(", ") { fn.params[it].name }}")
            effArgs = merged.mapIndexed { i, x -> x ?: fn.params[i].default ?: NameRef("__missingArg") }     // 默认值填空；其余缺参已报 E-TYPE-MISMATCH
            effTypes = mergedT.mapIndexed { i, t -> t ?: fn.params[i].default?.let { checkExpr(it, f, pure) } ?: syntheticT("缺实参") }
            namedArgOrder[c] = effArgs
        }
    }

    // 修复（#3）：@theorem 定理函数解封——值级调用正常（此处不警告；后件事实照常注入，运行时擦除为 null）
    // P9（决策 44，用户授权 Max 倾向：报错 + 判定含 when 解构变量）：纯函数自递归要求**至少一个**实参是某形参的语法子项。
    // 多参数递归（map(f, t)）允许透传参数（f 原样）——停止性由递减者（t）保证；全参数都不递减才算违规。
    // unchecked 放行；@impure 不受约束；方法路径（字典分发）暂不查。
    if (!isMethodPath && "impure" !in fn.annotations && uncheckedDepth == 0 && name == curFunName && curFunName != null) {
        if (effArgs.none { isStructuralArg(it) || isNumericDescent(it) })
            d.error("E-NON-STRUCTURAL-REC", c.pos,
                "纯函数 `$name` 递归时，必须有一个参数在「变小」（比如对列表子部分递归，或数值严格递减）。如果不需要编译器检查停机性，给函数加 `@impure`")
    }

    // v3.0（完美函数类型）：impure 函数被调用（无论是否放行）——若发生在 lambda/匿名函数体内则标记其纯度
    if ("impure" in fn.annotations && lambdaDepth > 0) curLambdaImpure = true
    if ("impure" in fn.annotations && pure) {
        // 决策 54：pure 是上下文的单参命题，可改变代码行为——
        // 上下文持有 pure<被调函数> 时，编译器视该调用为纯，无需 unchecked 逃逸
        // O2：方法路径（点号/自由式）的纯度原子统一按方法名（registerPureFacts 同名注册）
        // P0：跨模块非纯调用在 P0 无跨模块 pure 事实（每模块只注册本模块函数），须 unchecked 逃逸（见《模块系统.md》）
        val calleeRef: Expr = if (isMethodPath) NameRef(name)
            else (c.callee as? InstExpr)?.target ?: c.callee
        if (!proveOrDeep(f.allFacts(), PAtom("pure", listOf(calleeRef))))
            d.error("E-IMPURE-CALL", c.pos, "纯作用域内调用非纯函数 $name（用 unchecked 前缀，或在上下文供给 pure<$name>）")
    }
    // v2.0 异步传染（决策 93，异步 §2）：被调函数是 @async → 调用点必须是 @async 上下文（curFunAsync）。
    // async<f> 自然命题只标记「f 是关键子函数」的传播事实，不构成调用放行——同步上下文调用即报
    // E-NEED-ASYNC（给调用方函数加 @async）；@async 函数体内的调用是**挂起点**——codegen 自动插入 await
    // （异步阶段 1：main/Task 体的天然 @async 见阶段 2）。
    if ("async" in fn.annotations && !curFunAsync && uncheckedDepth == 0) {
        d.error("E-NEED-ASYNC", c.pos, "调用 @async 函数 $name 需 @async 上下文（给调用方函数 ${curFunName ?: "main"} 加 @async 注解）")
    } else if ("async" in fn.annotations && curFunAsync) {
        asyncAwaitHits[c] = true   // 挂起点：codegen 生成 await
        d.supplement("D-AUTO-AWAIT", c.pos, "`$name()` 是异步调用，自动 await（无需写 await 关键字）")
    }
    // 决策 69 扩展：尾部默认参数可省略——required = 首个带默认值形参的下标（无默认值则等于形参数）
    if (listIdx < 0) {
        val required = fn.params.indexOfFirst { it.default != null }.let { if (it < 0) fn.params.size else it }
        if (effTypes.size < required || effTypes.size > fn.params.size) {
            val who = if (isMethodPath) "方法 `$name`" else "函数 `$name`"
            val hint = if (required < fn.params.size) "（尾部 ${fn.params.size - required} 个参数有默认值，可省略）" else ""
            d.error("E-ARITY", c.pos, "$who 想要 $required~${fn.params.size} 个实参，但你传了 ${effTypes.size} 个$hint")
        }
    }

    val tsub = LinkedHashMap<String, Type>()
    // v3.0（完美函数类型 soundness）：显式类型实例化（f<Nat>(...)）**优先绑定**理论参数——
    // 实参反推（extractTpBinding）不再覆盖（unifyInto 已绑定即一致性检查不覆盖），
    // 故 `id<Nat>("a")` 显式 T:=Nat 后，实参 "a" 与实例化形参 Nat 在下方核对处报 E-TYPE-MISMATCH
    instTermsOf(c).forEachIndexed { i, iterm ->
        if (i < fn.theory.count { tp -> tp is TypeParam }) {
            val tn = fn.theory.filterIsInstance<TypeParam>()[i].name
            typeOfTerm(iterm)?.let { tt -> if (!tt.isSynthetic()) tsub[tn] = tt }
        }
    }
    fn.params.forEachIndexed { i, p ->
        if (i < effTypes.size) {
            val at = effTypes[i]
            if (!at.isSynthetic()) extractTpBinding(p.type, at, tps.toSet(), tsub, tpArityOf(fn))
        }
    }
    // P2 定点反推（compose 等链式泛型）：lambda 返回类型反推可能依赖**另一个 lambda 先解出的类型参数**——
    // `compose[A,B,C](f:(B)=>C, g:(A)=>B, x:A): C` 中 f 的返回 C 要等 g 解出 B 才可知。按声明序检查 f 先于 g，
    // 首轮 f 拿不到 B → lt.ret synthetic → C 解不出。故把 lambda 检查包进迭代环：每轮按当前 tsub 解参数，
    // 直到 tsub 无新解（上限 8 防环）。重复检查 lambda 体可能重发诊断，循环后 diag.dedupe() 收敛。
    var rounds = 0
    while (rounds < 8) {
        val beforeTsub = tsub.size
        fn.params.forEachIndexed { i, p ->
            if (i >= effTypes.size) return@forEachIndexed
            val want0 = syms.expand(p.type).substT(tsub)   // 别名展开（决策 30）
            // 根文档·上下文系统：函数类型上下文里的自由名（如 forEach 的 $1 in xs 之 xs）在调用点
            // 按形参名 -> 实参内联（envSub 同款），使 lambda 体拿到的是调用点真实表达式。
            val want = if (want0 is FunType) {
                val ctx = want0.context
                if (ctx != null) {
                    val paramEnv = fn.params.map { it.name }.zip(effArgs).toMap()
                    want0.copy(context = ctx.map { PropLogic.substExpr(it, paramEnv) })
                } else want0
            } else want0
            if (want is FunType) {
                // T2：lambda 实参带期望类型二次检查（双向推导）；非 lambda 实参宽松放行
                val a = effArgs[i]
                if (a is LambdaExpr) {
                    val lt = checkExpr(a, f, pure, want)
                    // P2（决策 78）：lambda 实际返回类型反推类型参数（map 的 U 只能由 lambda 体推出）。
                    // v4（core 类型类应用位）：flatMap 的 want.ret 是 List[B]（应用形态）——lambda 体 List[Nat]
                    // 反推 B:=Nat 需走深位合一（extractTpBinding 只绑 vars 内类型变量，与旧 putIfAbsent 同语义）。
                    // 仅当 lambda 体类型无未解析变量时提取——否则会把 listOf[T] 泄漏的 T 绑进 tsub 污染后续轮次。
                    if (lt is FunType && !lt.ret.isSynthetic() && !containsUnresolvedTp(lt.ret))
                        extractTpBinding(want.ret, lt.ret, tps.toSet(), tsub)
                }
            }
        }
        rounds++
        if (tsub.size == beforeTsub) break
    }
    if (rounds > 1) d.dedupe()
    // 非 lambda 实参核对（仅一次，避免迭代重复报错）
    fn.params.forEachIndexed { i, p ->
        // @list 形参 = Kotlin vararg——尾部位置实参打包成 List[元素]；逐个与元素类型 unify（异构报 E-TYPE-MISMATCH）
        if ("list" in p.annotations && i < effArgsIn.size) {
            val elem = syms.expand(p.type)   // @list 声明类型即元素类型
            var unified = true
            for (j in i until effArgsIn.size) {
                val at = effTypesIn[j]
                if (!at.isSynthetic() && !unifyInto(elem, at, tps.toSet(), tsub, tpArityOf(fn))) unified = false
            }
            if (!unified)
                d.error("E-TYPE-MISMATCH", c.pos, "@list 形参 ${p.name}：变长实参须为同一类型")
            return@forEachIndexed
        }
        if (i >= effTypes.size) return@forEachIndexed
        // HKT（《高阶类型.md》HKT-D1）：kind 形参应用位（`F[A]`）经 unifyInto 已绑 F:=具体构造子（零实参头），
        // 但 substT 不替换**应用形态**——在此手动套一次头替换（size(s) 的 s: Set[T] 与 Set[T] 核对），
        // 否则形参保持 F[A] 与实参名义不等，误报 E-TYPE-MISMATCH。
        val want = applyKindHead(p.type, syms.expand(p.type).substT(tsub), tsub)
        // v2.0 和类型：联合形参（name=(union) 以 ( 开头）也要核对——实参须命中某个变体（成员匹配）。
        if (want !is FunType && !effTypes[i].isSynthetic() && (!p.type.name.startsWith("(") || p.type is UnionType)) {
            // 形参在 substT(tsub) 后仍是**裸类型变量**（泛型形参位，未解出）→ 实参任意放行（与 checkBin 的 isTypeVarForm 一致）
            val wantIsTv = want is NamedType && want.args.isEmpty() &&
                want.name !in BASE_TYPES && want.name !in syms.enums &&
                want.name !in syms.structs && !want.isSynthetic()
            val wantOk = if (want is UnionType)
                effTypes[i].isSynthetic() || want.variants.any { typeLooseEq(it, effTypes[i]) }
            else if (wantIsTv) true
            else typeLooseEq(want, effTypes[i])
            if ((want.isNominal() || want is UnionType) && !wantOk) {
                // 决策 59：Any 形参接受任意实参（受限顶类型）；其余仍须名义相等
                if (!(want.name == "Any" && want.args.isEmpty()))
                    d.error("E-TYPE-MISMATCH", c.pos, "实参 $i ${effTypes[i].render()} 与形参 ${want.render()} 不符")
            }
        }
    }

    // O2：方法路径的实参替换表只含真实形参（self 不上表）；伴生记录仍用全量 c.args（含 self，作调用痕迹）。
    // 根文档·内联标识：方法路径的接收者内联为 self（a.f() 的 p<self> 内联为 p<a>）。
    val envSub = fn.params.map { it.name }.zip(effArgs).toMap().toMutableMap()
    if (isMethodPath) {
        val recv = (c.callee as? FieldExpr)?.target ?: c.args.firstOrNull()
        if (recv != null) envSub["self"] = recv
    }
    val propNames = fn.theory.filterIsInstance<PropParam>().map { it.name }
    val binding = LinkedHashMap<String, Prop>()
    val explicitProps = instTermsOf(c).mapNotNull { propFromSupply(it) }
    for ((i, q) in propNames.withIndex()) {
        val usesQ = fn.preps.any { mentionsPropExpr(it, q) } || fn.posts.any { mentionsPropExpr(it, q) }
        val sup = explicitProps.getOrNull(i)
        if (sup != null) {
            binding[q] = sup
            if (!proveOrDeep(f.allFacts(), sup))
                d.warn("E-PROP-UNBOUND", c.pos, "显式供给 ${PropLogic.render(sup)} 在作用域不成立")
        } else if (usesQ) {
            // 自动供给：方括号命题参数是"名字"，只与作用域中的 0 元原子匹配
            val cands = f.allFacts()
                .filter { it is PAtom && it.terms.isEmpty() && it.head !in PropLogic.structuralHeads }
                .distinctBy { PropLogic.render(it) }
            when {
                cands.isEmpty() -> d.warn("E-PROP-UNBOUND", c.pos, "命题参数 @$q 作用域无 0 元命题供给（用 <...> 显式传入）")
                cands.size > 1 -> d.error("E-PROP-AMBIGUOUS", c.pos, "命题参数 @$q 有 ${cands.size} 个 0 元候选，请显式供给")
                else -> binding[q] = cands.first()
            }
        }
    }

    for (pre in fn.preps) {
        val goal = substAll(PropLogic.fromExpr(substThisExpr(substDollarNExpr(pre, fn.params), fn.name)), binding, tsub, envSub)
        if (!proveOrDeep(f.allFacts(), goal))
            d.error("E-PRE-UNPROVEN", c.pos, "调用 `$name` 前必须先证明它的上文 `${PropLogic.render(goal)}`，但编译器没证出。可换参数让编译器自动证出，或用 `witness<命题>()` 显式供给前置事实")
    }

    // 约束表（v3.0 登记未校验，现落地）：`fun f()<score <= 100>` 的形参比较条件由调用点实参承担。
    // 确凿违反（¬goal 可证）报 ERROR；静态证不出（值运行时才确定）降级 WARNING，不误杀未知实参。
    for (cons in fn.constraints) {
        val goal = substAll(PropLogic.fromExpr(substThisExpr(substDollarNExpr(PropLogic.substExpr(cons, envSub), fn.params), fn.name)), binding, tsub, envSub)
        if (!proveOrDeep(f.allFacts(), goal)) {
            if (proveOrDeep(f.allFacts(), PNot(goal)))
                d.error("E-CONSTRAINT-VIOLATED", c.pos, "调用 `$name` 违反约束 `${PropLogic.render(goal)}`：编译器已反证该条件不成立。")
            else
                d.warn("W-CONSTRAINT-UNPROVEN", c.pos, "调用 `$name` 的约束 `${PropLogic.render(goal)}` 编译器没证出（值可能运行时才确定）。")
        }
    }

    f.inject(PAtom(name, c.args))   // 伴生记录
    for (post in fn.posts) {
        val goal = PropLogic.substDollar(substAll(PropLogic.fromExpr(substThisExpr(substDollarNExpr(post, fn.params), fn.name)), binding, tsub, envSub), c)
        f.inject(goal)
    }
    // P6（决策 82）：带约束泛型函数的调用点——逐个约束解出具体类型并解析字典，codegen 前插字典实参。
    // I-22：与命题参数同走供给机制；可见性过滤同 resolveDict（目录模式按当前模块可见路径）。
    val vis = if (moduleTree == null) null else visiblePaths
    val constraintTps = fn.theory.filterIsInstance<TypeParam>()
        .filter { it.constraint != null && it.constraint != "Any" }   // v2.1：Any 恒满足零字典（constraint 是类型类名字符串）
    val dictArgs = constraintTps.mapNotNull { tp -> (tsub[tp.name] ?: return@mapNotNull null)
        .let { resolveConstraint(tp.constraint!!, it, vis) } }
    if (dictArgs.isNotEmpty() && dictArgs.size == constraintTps.size) {
        dictSubHits[c] = dictArgs
    } else if (constraintTps.isNotEmpty() && (c.callee as? NameRef)?.name == fn.name) {
        // P11 压测（决策 87 补充）：约束泛型自递归调用点——递归调用的类型实参仍是当前函数的元类型
        // （T 未具体化，tsub 解不出具体字典），直接把递归栈内当前函数的约束槽透传给递归调用。
        dictSubHits[c] = constraintTps.map { "d_${it.constraint}_${it.name}" }
        // ^ constraintTps 已过滤 Any；此处沿用同一来源，Any 不会进入透传槽
    }
    return (fn.retType ?: syms.inferredRets[fn.name] ?: namedT("Null", emptyList())).substT(tsub)
}

/** 显式供给实参（实例化项，限定/普通共用） */
private fun Checker.instTermsOf(c: CallExpr): List<Expr> = when (val callee = c.callee) {
    is InstExpr -> callee.terms
    else -> emptyList()
}

/**
 * 从实参类型递归解出形参里的类型参数（P2：`Array<T>` 这类**嵌套**类型参数，P6 之前不做真合一，只做同构解出）。
 * P7（决策 83）：内部换装 TypeInfer.unifyInto 标准合一——同构递归（NamedType 逐实参 / FunType 参数+返回 / TupleType 逐项）
 * 之上增加 occurs check（拒绝 `T → List<T>` 环）与已绑定一致性（静默，typeLooseEq 宽容，语义同 P2 putIfAbsent）。
 * 合成类型不绑定（unifyInto 宽容 true）。解不出就留空（报错走 E-TYPE-MISMATCH 既有通道）。
 */
private fun extractTpBinding(want: Type, got: Type, tps: Set<String>, tsub: LinkedHashMap<String, Type>, arity: Map<String, Int> = emptyMap()) {
    unifyInto(want, got, tps, tsub, arity)   // 同包顶层函数（TypeInfer.kt）
}

/** root doc《副作用与可变性控制》「停机」第二法（数值递归）：实参是否为某形参的**数值严格递减**变换。
 *  只认 `p - k`（k>0）与 `p + k`（k<0）——参数向某个数值靠拢（Nat 向 0）并停在那里。
 *  真实参（形参自身）、持平（p + 0）、递增（p + k, k>0）、非变量字面量（countdown(3)）都不认。 */
private fun Checker.isNumericDescent(a: Expr): Boolean {
    val bin = a as? BinExpr ?: return false
    if (bin.op != "-" && bin.op != "+") return false
    val vname = (bin.left as? NameRef)?.name ?: (bin.left as? VarExpr)?.name ?: return false
    if (vname !in curFunParams) return false
    val k = (bin.right as? IntLit)?.value?.toLongOrNull() ?: return false
    return if (bin.op == "-") k > 0 else k < 0
}

/** P9（决策 44，递归停机）：实参是否为某形参的语法子项。
 *  变量实参：形参自身（链长 0）→ 否（无进展）；解构血缘链（structSub，when 解构登记的 子→父）向上可到形参 → 是。
 *  非变量实参（`n - 1`、调用、字面量）→ 否。unchecked/@impure 的放行在调用侧判断。 */
private fun Checker.isStructuralArg(a: Expr): Boolean {
    val vname: String? = when (a) {
        is NameRef -> a.name
        is VarExpr -> a.name
        else -> null
    }
    if (vname == null) return false                       // 非变量实参（n - 1、调用、字面量）不是语法子项
    if (vname in curFunParams) return false               // f(x)：形参自身不是子项
    var cur = vname
    var steps = 0
    while (steps < 32 && structSub.containsKey(cur)) {
        cur = structSub[cur]!!
        if (cur in curFunParams) return true              // 血缘链到形参 → 语法子项
        steps++
    }
    return false
}

/**
 * 跨模块限定调用（P0，M3/M5/M6）：符号按目标模块定性后，走与普通调用完全相同的公共检查段。
 * FUN/CTOR/STRUCT → 模块前缀 mangle 名（moduleHits 留痕）；METHOD → 字典分发（首实参即接收者）。
 */
private fun Checker.checkQualifiedCall(q: QChain, c: CallExpr, f: Frame, pure: Boolean, argTypes: List<Type>): Type {
    val sym = q.symbol
    val node = q.node
    if (sym.kind == QSymKind.MISSING) {
        d.error("E-UNBOUND-NAME", c.pos, "调用模块 ${node.absPath.ifEmpty { "/" }} 中未定义符号 ${sym.name}")
        return syntheticT("限定调用")
    }
    return when (sym.kind) {
        QSymKind.FUN -> {
            // 跨模块限定调用：按目标模块符号表 + 实参首类型挑重载（`core.collection.filter(xs, f)` 路由到 List 版，而非先注册的 Set/Optional 版）
            val fn = findCallableIn(sym.syms, sym.name, argTypes)
                ?: run { d.error("E-UNBOUND-NAME", c.pos, "调用模块 ${node.absPath.ifEmpty { "/" }} 中无函数 ${sym.name}"); return syntheticT("限定调用") }
            moduleHits[c.callee] = jsOverloadName(node.absPath, fn)
            checkFnCall(fn, sym.name, c, f, pure, c.args, argTypes, isMethodPath = false)
        }
        QSymKind.CTOR -> checkCtorCall(sym.syms.ctors.getValue(sym.name), sym.name, c, argTypes, node.absPath)
        QSymKind.STRUCT -> checkStructCall(sym.syms.structs.getValue(sym.name), sym.name, c, argTypes, node.absPath, f)
        QSymKind.METHOD -> {
            // 限定方法 `core.show(p)`：首实参即接收者（自由形态语义）
            if (argTypes.isEmpty()) {
                d.error("E-NO-SELF", c.pos, "方法 `${sym.name}` 缺接收者。写成 `接收者.${sym.name}(…)` 或 `${sym.name}(接收者, …)`")
                return syntheticT("无自参${sym.name}")
            }
            val res = resolveDict(sym.name, argTypes[0], visiblePaths) ?: return syntheticT("无实例${sym.name}")
            dictHits[c] = res.dictName
            moduleHits[c.callee] = MODULE_METHOD_MARKER
            checkFnCall(res.entry.fn, sym.name, c, f, pure, c.args.drop(1), argTypes.drop(1), isMethodPath = true)
        }
        else -> {
            d.error("E-UNBOUND-NAME", c.pos, "模块 ${node.absPath.ifEmpty { "/" }} 的符号 ${sym.name} 不能作为函数调用")
            syntheticT("限定调用")
        }
    }
}

internal fun Checker.mentionsPropExpr(e: Expr, q: String): Boolean = mentionsProp(PropLogic.fromExpr(e), q)

internal fun Checker.mentionsProp(p: Prop, q: String): Boolean = when (p) {
    is PAtom -> p.head == q && p.terms.isEmpty()
    is PNot -> mentionsProp(p.p, q)
    is PAnd -> mentionsProp(p.a, q) || mentionsProp(p.b, q)
    is POr -> mentionsProp(p.a, q) || mentionsProp(p.b, q)
    is PImp -> mentionsProp(p.a, q) || mentionsProp(p.b, q)
    is PIff -> mentionsProp(p.a, q) || mentionsProp(p.b, q)
    else -> false
}

internal fun Checker.propFromSupply(e: Expr): Prop? {
    val p = PropLogic.fromExpr(e)
    return if (p is PAtom && p.head !in PropLogic.structuralHeads) p else null
}

internal fun Checker.substAll(
    p0: Prop, binding: Map<String, Prop>, tsub: Map<String, Type>, envSub: Map<String, Expr>
): Prop {
    var p = p0
    for ((k, v) in binding) p = PropLogic.substPropParam(p, k, v)
    p = substPropType(p, tsub)
    p = mapTerms(p) { PropLogic.substExpr(it, envSub) }
    return p
}

internal fun Checker.mapTerms(p: Prop, fn: (Expr) -> Expr): Prop = when (p) {
    is PAtom -> PAtom(p.head, p.terms.map(fn))
    is PNot -> PNot(mapTerms(p.p, fn))
    is PAnd -> PAnd(mapTerms(p.a, fn), mapTerms(p.b, fn))
    is POr -> POr(mapTerms(p.a, fn), mapTerms(p.b, fn))
    is PImp -> PImp(mapTerms(p.a, fn), mapTerms(p.b, fn))
    is PIff -> PIff(mapTerms(p.a, fn), mapTerms(p.b, fn))
    is PForall -> PForall(p.names, mapTerms(p.body, fn))
    is PExists -> PExists(p.names, mapTerms(p.body, fn))
    is PExists1 -> PExists1(p.names, mapTerms(p.body, fn))
    else -> p
}

/** ---- v3.0 特性 1：结构体伴生命题 ---- */

/** 在 Prop 的原子项里，把 NameRef(name) 替换为 sub[name]，递归整棵 Prop 树。 */
internal fun substPropTerms(p: Prop, sub: Map<String, Expr>): Prop = when (p) {
    is PAtom -> p.copy(terms = p.terms.map { subExprTerm(it, sub) })
    is PNot -> PNot(substPropTerms(p.p, sub))
    is PAnd -> PAnd(substPropTerms(p.a, sub), substPropTerms(p.b, sub))
    is POr -> POr(substPropTerms(p.a, sub), substPropTerms(p.b, sub))
    is PImp -> PImp(substPropTerms(p.a, sub), substPropTerms(p.b, sub))
    is PIff -> PIff(substPropTerms(p.a, sub), substPropTerms(p.b, sub))
    is PForall -> PForall(p.names, substPropTerms(p.body, sub))
    is PExists -> PExists(p.names, substPropTerms(p.body, sub))
    is PExists1 -> PExists1(p.names, substPropTerms(p.body, sub))
    else -> p
}

/** 原子项里的名字深替换（NameRef 直接换；复合表达式递归穿透内层）。 */
private fun subExprTerm(t: Expr, sub: Map<String, Expr>): Expr = when (t) {
    is NameRef -> sub[t.name] ?: t
    is BinExpr -> t.copy(left = subExprTerm(t.left, sub), right = subExprTerm(t.right, sub))
    is UniExpr -> t.copy(operand = subExprTerm(t.operand, sub))
    is CallExpr -> t.copy(callee = subExprTerm(t.callee, sub), args = t.args.map { subExprTerm(it, sub) })
    is InstExpr -> t.copy(target = subExprTerm(t.target, sub), terms = t.terms.map { subExprTerm(it, sub) })
    is FieldExpr -> t.copy(target = subExprTerm(t.target, sub))
    is SymbolCallExpr -> t.copy(args = t.args.map { subExprTerm(it, sub) })
    else -> t
}

/** 命题树是否引用字段名 name（原子项里出现同名 NameRef，含复合表达式递归）。 */
internal fun propHasName(p: Prop, name: String): Boolean = when (p) {
    is PAtom -> p.terms.any { exprHasName(it, name) }
    is PNot -> propHasName(p.p, name)
    is PAnd -> propHasName(p.a, name) || propHasName(p.b, name)
    is POr -> propHasName(p.a, name) || propHasName(p.b, name)
    is PImp -> propHasName(p.a, name) || propHasName(p.b, name)
    is PIff -> propHasName(p.a, name) || propHasName(p.b, name)
    is PForall -> propHasName(p.body, name)
    is PExists -> propHasName(p.body, name)
    is PExists1 -> propHasName(p.body, name)
    else -> false
}

private fun exprHasName(t: Expr, name: String): Boolean = when (t) {
    is NameRef -> t.name == name
    is FieldExpr -> t.name == name || exprHasName(t.target, name)
    is BinExpr -> exprHasName(t.left, name) || exprHasName(t.right, name)
    is UniExpr -> exprHasName(t.operand, name)
    is CallExpr -> exprHasName(t.callee, name) || t.args.any { exprHasName(it, name) }
    else -> false
}

/** checkStructCall 尾段调用：@mut 检查 + 构造前证明 + 成功注入。 */
private fun Checker.checkStructProps(st: StructDecl, f: Frame, pos: String, args: Map<String, Expr>) {
    if (st.props.isEmpty()) return
    // 硬约束：伴生命题禁止引用 @mut 字段——声明位检查已在 collectDecl（此处防御保留）
    st.fields.forEach { fp ->
        if ("mut" in fp.annotations && st.props.any { propHasName(PropLogic.fromExpr(it), fp.name) }) {
            d.error("E-STRUCT-MUT-PROP", pos, "结构体 " + st.name + " 伴生命题引用了 @mut 字段 " + fp.name + "（可变字段无法保证恒成立）")
            return
        }
    }
    // 构造位证明：字段名 → 对应实参表达式（位置式取 c.args 映射；命名式取 e.assigns）
    st.props.forEach { prop ->
        val g = substPropTerms(PropLogic.fromExpr(prop), args)
        if (proveOrDeep(f.allFacts(), g)) {
            f.inject(g)
        } else {
            d.error("E-STRUCT-PROP-UNPROVEN", pos, "构造 " + st.name + " 时要保证它的性质 " + PropLogic.render(g) + " 成立，但从当前已知事实推不出来。检查构造实参是否满足该性质")
        }
    }
}

/** v3.0 特性 3：Range 内建方法签名（contains/length/toList/map/fold）；返回 null 表示非 Range 方法。 */
internal fun Checker.rangeMethodType(name: String, selfT: Type, argTypes: List<Type>, c: CallExpr): Type? {
    val elem = (selfT as? NamedType)?.args?.getOrNull(0) ?: namedT("Int")
    return when (name) {
        "contains" -> { if (argTypes.size != 1) d.error("E-TYPE-MISMATCH", c.pos, "contains 期望 1 个实参"); namedT("Bool", emptyList()) }
        "length" -> { if (argTypes.isNotEmpty()) d.error("E-TYPE-MISMATCH", c.pos, "length 无参"); namedT("Nat", emptyList()) }
        "toList" -> namedT("List", listOf(elem))
        "map" -> {
            val rt = (argTypes.getOrNull(0) as? FunType)?.ret ?: syntheticT("map返回")
            namedT("List", listOf(rt))
        }
        "fold" -> argTypes.getOrNull(0) ?: syntheticT("fold返回")
        else -> null
    }
}

