package sugared.functor.checker

import sugared.functor.ast.*

// ============ 调用 ============

/** v2.0 异步（决策 93）：Task 对象方法名（taskJs 生成的 JS 对象字段） */
internal val taskMethods = setOf("start", "join", "isAlive")

/** v2.0 异步（决策 93）：Channel 对象方法名（channel 运行时 class 的方法） */
internal val channelMethods = setOf("send", "receive", "close", "isClosed")

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
    if (st.fields.any { it.type.containsAny() } && uncheckedDepth == 0)
        d.error("E-ANY-STRUCT", e.pos, "结构体 ${e.struct} 含 Any 成员，创建需 unchecked 逃逸（决策 59）")
    val seen = LinkedHashSet<String>()
    for ((fname, valExpr) in e.assigns) {
        if (!seen.add(fname)) d.error("E-DUP-FIELD", e.pos, "构造 ${e.struct} 重复字段 $fname")
        if (st.fields.none { it.name == fname })
            d.error("E-UNBOUND-NAME", e.pos, "结构体 ${e.struct} 无字段 $fname")
        val vt = checkExpr(valExpr, f, pure)
        val fp = st.fields.firstOrNull { it.name == fname } ?: continue
        val want = syms.expand(fp.type)
        if (!vt.isSynthetic() && want.isNominal() && !typeLooseEq(want, vt) &&
            !(want.name == "Any" && want.args.isEmpty()))
            d.error("E-TYPE-MISMATCH", e.pos, "构造 ${e.struct} 字段 $fname 的实参 ${vt.render()} 与 ${want.render()} 不符")
    }
    st.fields.forEach { fp ->
        if (fp.name !in seen && fp.default == null)
            d.error("E-FIELD-MISSING", e.pos, "构造 ${e.struct} 缺字段 ${fp.name} 且无默认值")
    }
    return namedT(e.struct, emptyList())
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
    // v2.0 异步（决策 93）：Task 对象方法（start/join/isAlive）需纳入方法路径——它们不是型类方法/自由函数，
    // 但必须是接收者形态（o.start()）走 Task 特判；裸名 start(...) 不会被误解析。
    val fieldCallee: FieldExpr? = (c.callee as? FieldExpr)?.takeIf { isMethodName(it.name) || hasFreeFun(it.name) || it.name in taskMethods || it.name in channelMethods }
    val freeName = (c.callee as? NameRef)?.name
        ?: ((c.callee as? InstExpr)?.target as? NameRef)?.name
    val isFreeFormMethod = fieldCallee == null && freeName != null &&
        isMethodName(freeName) &&
        syms.findFun(freeName) == null && !syms.ctors.containsKey(freeName) && !syms.structs.containsKey(freeName)
    val selfT: Type? = when {
        fieldCallee != null -> checkExpr(fieldCallee.target, f, pure)
        isFreeFormMethod && argTypes.isNotEmpty() -> argTypes[0]   // 首实参已检查过，复用类型避免诊断翻倍
        else -> null
    }
    val effArgs: List<Expr> = if (isFreeFormMethod) c.args.drop(1) else c.args
    val effTypes: List<Type> = if (isFreeFormMethod) argTypes.drop(1) else argTypes
    val isMethodPath = fieldCallee != null || isFreeFormMethod
    // P8（决策 84）：方法路径不支持命名参数——在方法入口显式拦截（方法参数管道不消费 namedArgs，否则静默丢弃）
    if (isMethodPath && c.namedArgs.isNotEmpty())
        d.error("E-NAMED-ARG", c.pos, "方法调用不支持命名参数（v1 限制：命名实参仅自由函数调用）")
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

    if (selfT == null) {
        // O2：方法路径（self 已知）不参与构造子/结构体判定，避免同名截胡
    syms.ctors[name]?.let { ci -> return checkCtorCall(ci, name, c, argTypes, modulePath) }
    syms.structs[name]?.let { st -> return checkStructCall(st, name, c, argTypes, modulePath) }
    }

    // v2.0 异步（决策 93，异步 §3.1/§5.4）：Channel 创建 `Channel[T]()` / `Channel[T](cap)`。
    // 形态是 `callee = InstExpr(NameRef("Channel"))`，于构造子判定之后特判；创建点返回 Channel[T]。
    // localChannels（本地通道登记）在 VarStmt 层做（`var ch = Channel<…>()` 才能拿到变量名）。
    if (name == "Channel") {
        val tArg = (c.callee as? InstExpr)?.terms?.firstOrNull()
        val tT = when {
            tArg is NameRef -> namedT(tArg.name, emptyList())
            else -> { d.error("E-TYPE-MISSING", c.pos, "Channel 创建需显式类型参数：Channel[T]()（文档 CH-1/CH-2）"); syntheticT("Channel实参") }
        }
        if (c.args.size == 1 && argTypes[0].isNominal() && !typeLooseEq(argTypes[0], namedT("Nat", emptyList())))
            d.error("E-TYPE-MISMATCH", c.pos, "Channel 缓冲容量须为 Nat：Channel[${tT.render()}](${argTypes[0].render()})")
        if (c.args.size > 1) d.error("E-ARITY", c.pos, "Channel 创建最多一个容量实参")
        channelUsed = true   // 告诉 codegen 注入 Channel 运行时 class
        return namedT("Channel", listOf(tT))
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
                            d.error("E-NOT-STARTED", c.pos, "任务 $tn 未 start() 即 join()（TASK-7：函数体内先 t.start() 再 t.join()）")
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
                            d.error("E-CHANNEL-CLOSED", c.pos, "通道 $cn 已 close() 后 send()（CH-6：同一函数体内 close 在 send 前）")
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
    if (fieldCallee != null && !isMethodName(fieldCallee.name)) {
        val sugarFn = methodSugar(fieldCallee.name, selfT, fieldCallee, c)
        if (sugarFn != null)
            return checkFnCall(sugarFn, fieldCallee.name, c, f, pure,
                methodSugarArgs[c]!!, listOfNotNull(selfT) + argTypes, isMethodPath = true)
    }

    val fn: FunDecl = (if (fieldCallee != null) null else findCallable(name, argTypes))   // O2：点号形态直指型类方法，不被同名自由函数截胡；v2.0 按实参挑重载
        ?: run {
            // 型类方法调用（决策 60，T5；O2 双形态）：按 self 类型解析字典，按调用点留痕给 codegen
            if (isMethodName(name)) {
                // O2：自由式 `m()` 无实参 → 缺接收者，专属码（区别于"有类型但无实例"）
                if (selfT == null) {
                    d.error("E-NO-SELF", c.pos, "方法 $name 需要接收者：写成 o.$name(...) 或 $name(o, ...)（决策 68）")
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
                    // 返回类型取类成员签名（约束型类方法通常返回具体类型，如 show → Str）
                    val sig = syms.classes[consSlot.first]?.members?.firstOrNull { it.name == name }
                    return sig?.retType ?: syntheticT("约束方法${consSlot.first}.$name")
                }
                val res = resolveDict(name, selfT, if (moduleTree == null) null else visiblePaths)
                if (res != null) {
                    dictHits[c] = res.dictName
                    res.entry.fn
                } else {
                    // v1.1 集合方法糖：`o.m(args)` 且 m 是同名自由函数（首参类型匹配接收者）→ 脱糖为 m(o, args)
                    val sugarFn = methodSugar(name, selfT, fieldCallee, c)
                    if (sugarFn != null) sugarFn else return syntheticT("无实例$name")
                }
            } else null
        }
        ?: run {
            // 回退：调用持有函数值的局部变量（如 `var id = \(x)=>x; id(1)`）
            if (f.lookupVar(name) != null) return syntheticT("变量函数调用$name")
            d.error("E-UNBOUND-NAME", c.pos, "调用未定义函数 $name"); return syntheticT("调用")
        }

    if (!isMethodPath && syms.funs.containsKey(name))
        moduleHits[c.callee] = jsOverloadName(modulePath, fn)   // P0：同模块全局调用按模块前缀留痕；v2.0 重载带首参标签
    // v1.1 方法糖：接收者前置重排 `xs.map(f)` → `map(xs, f)`（实参类型首项为接收者类型）
    val sugar = methodSugarArgs[c]
    if (sugar != null)
        return checkFnCall(fn, name, c, f, pure, sugar, listOfNotNull(selfT) + argTypes, isMethodPath)
    return checkFnCall(fn, name, c, f, pure, effArgs, effTypes, isMethodPath)
}

// ============ v1.1 集合方法糖：`o.m(args)` → 同名自由函数 m(o, args) ============

/** 方法糖命中：找「同名自由函数」（本模块 / 内置 / 可见模块含 stdlib），且其**第一个形参**类型与接收者匹配。
 *  命中后登记实参重排（methodSugarArgs）与跨模块 JS 名（moduleHits），返回该函数供正常调用检查。 */
private fun Checker.methodSugar(name: String, selfT: Type?, fieldCallee: FieldExpr?, c: CallExpr): FunDecl? {
    if (fieldCallee == null || selfT == null) return null
    val (fn, mod) = findCollectionMethod(name, selfT) ?: return null
    methodSugarArgs[c] = listOf(fieldCallee.target) + c.args
    moduleHits[c.callee] = jsOverloadName(mod, fn)
    return fn
}

/** v2.0 重载：JS 名带首参标签后缀（与 JsCodeGen 声明侧规则一致）——仅当相应模块存在同名重载组 */
internal fun Checker.jsOverloadName(mod: String, fn: FunDecl): String {
    val hasOver = if (mod.isEmpty()) syms.funOverloads.containsKey(fn.name)
                  else allSymbols[mod]?.funOverloads?.containsKey(fn.name) == true
    return if (hasOver) "${moduleJsName(mod, fn.name)}\$${fnTag(fn)}" else moduleJsName(mod, fn.name)
}

/** 在「本模块 + 内置 + 可见模块（含 stdlib）」中找同名自由函数且首参类型匹配接收者；返回 (函数, 模块路径)。
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

/** v2.0：普通（自由函数）调用按**实参首类型**从重载里挑选匹配项；无重载则退回主声明（保留原诊断路径）。
 *  只查本模块（重载目前仅用于 stdlib 同模块方法名；跨模块走 findCollectionMethod）。 */
private fun Checker.findCallable(name: String, argTypes: List<Type>): FunDecl? {
    val main = syms.findFun(name)
    val ovs = syms.funOverloads[name]
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

/** 方法名判定（决策 60，T5）：本模块 impl/型类候选，或可见模块（M6：impl 也要挂载）里的。 */
private fun Checker.isMethodName(n: String): Boolean =
    n in syms.methods || n in syms.traitMethods ||
        (moduleTree != null && visiblePaths.any { p ->
            val s = allSymbols[p]
            s != null && (s.methods.containsKey(n) || n in s.traitMethods)
        })

/** v1.1：是否存在同名自由函数（本模块 / 内置 / 可见模块含 stdlib）——集合方法糖候选（v2.0：含重载） */
private fun Checker.hasFreeFun(n: String): Boolean =
    syms.findFun(n) != null || syms.funOverloads.containsKey(n) ||
        (moduleTree != null && visiblePaths.any {
            val s = allSymbols[it]
            s != null && (s.findFun(n) != null || s.funOverloads.containsKey(n))
        })

/** 构造子调用公共段（决策 46/60，T5）：类型参数由实参推断；限定/普通共用。 */
internal fun Checker.checkCtorCall(ci: CtorInfo, name: String, c: CallExpr, argTypes: List<Type>, modPath: String): Type {
    // 内置 prelude 构造子（Some/None/null/true/false）在 JS 里是根作用域函数/字面量，无模块前缀——
    // 否则 stdlib 模块内 `Some(x)` 会被错生成成 `stdlib__Some`（P0 限制：多模块同名构造子取末者）
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

/** 结构体构造调用公共段（决策 59/69，O3）：Any 准入、位置式缺省尾部规则不变；限定/普通共用。 */
internal fun Checker.checkStructCall(st: StructDecl, name: String, c: CallExpr, argTypes: List<Type>, modPath: String): Type {
    moduleHits[c.callee] = moduleJsName(modPath, name)
    // 决策 59：含 Any 成员的结构体禁止直接创建（unchecked 逃逸）
    if (st.fields.any { it.type.containsAny() } && uncheckedDepth == 0)
        d.error("E-ANY-STRUCT", c.pos, "结构体 $name 含 Any 成员，创建需 unchecked 逃逸（决策 59）")
    // O3（决策 69）：位置式允许缺省尾部——前提是尾部字段全有声明默认值
    // （命名字段构造 `A(name = e)` 由解析器产出独立节点 StructCtorExpr，走独立检查）
    if (st.fields.size < argTypes.size)
        d.error("E-TYPE-MISMATCH", c.pos, "构造 $name 期望 ${st.fields.size} 实参，实际 ${argTypes.size}")
    else if (st.fields.size > argTypes.size &&
        st.fields.drop(argTypes.size).any { it.default == null })
        d.error("E-FIELD-MISSING", c.pos, "构造 $name 缺尾部字段且无默认值")
    else st.fields.zip(argTypes).forEach { (fp, at) ->
        if (!at.isSynthetic() && fp.type.isNominal() && !typeLooseEq(fp.type, at)) {
            // 决策 59：Any 形参接受任意实参（受限顶类型）；其余仍须名义相等
            if (!(fp.type.name == "Any" && fp.type.args.isEmpty()))
                d.error("E-TYPE-MISMATCH", c.pos, "构造 $name 实参 ${at.render()} 与字段 ${fp.type.render()} 不符")
        }
    }
    return namedT(name, emptyList())
}

/** 函数调用公共检查尾段（决策 54/59/60/65，O2）：纯度/参数核对/显式供给/前后文；限定/普通共用。 */
internal fun Checker.checkFnCall(
    fn: FunDecl, name: String, c: CallExpr, f: Frame, pure: Boolean,
    effArgsIn: List<Expr>, effTypesIn: List<Type>, isMethodPath: Boolean,
): Type {
    val tps = tpNamesOf(fn)
    // P8（决策 84）：命名参数规范化——与位置实参合并为按形参声明顺序的实参列表（Kotlin 风格：
    // 位置实参填前 N 位，命名实参填指定位；命名覆盖已占位 → E-NAMED-ARG；缺参 → E-TYPE-MISMATCH）。
    // codegen 侧用 namedArgOrder 留痕取规范化实参（按声明顺序重排）。
    var effArgs = effArgsIn
    var effTypes = effTypesIn
    if (c.namedArgs.isNotEmpty()) {
        if (isMethodPath) {
            d.error("E-NAMED-ARG", c.pos, "方法调用不支持命名参数（v1 限制：命名实参仅自由函数调用）")
        } else {
            val names = fn.params.map { it.name }
            c.namedArgs.keys.forEach { n ->
                if (n !in names) d.error("E-NAMED-ARG", c.pos, "函数 $name 无参数名 $n")
            }
            val taken = BooleanArray(fn.params.size)
            val merged = arrayOfNulls<Expr>(fn.params.size)
            val mergedT = arrayOfNulls<Type>(fn.params.size)
            if ("vararg" !in fn.annotations && effArgsIn.size > fn.params.size)
                d.error("E-TYPE-MISMATCH", c.pos, "函数 $name 期望 ${fn.params.size} 实参，实际 ${effArgsIn.size}")
            effArgsIn.forEachIndexed { i, a ->
                if (i < fn.params.size) { merged[i] = a; mergedT[i] = effTypesIn[i]; taken[i] = true }
            }
            fn.params.forEachIndexed { i, p ->
                val nv = c.namedArgs[p.name] ?: return@forEachIndexed
                if (taken[i]) d.error("E-NAMED-ARG", c.pos, "函数 $name 的参数 ${p.name} 已被位置实参占用")
                merged[i] = nv; mergedT[i] = checkExpr(nv, f, pure); taken[i] = true
            }
            if ("vararg" !in fn.annotations) {
                val missing = fn.params.indices.filter { !taken[it] }
                if (missing.isNotEmpty())
                    d.error("E-TYPE-MISMATCH", c.pos, "函数 $name 缺实参：${missing.joinToString(", ") { fn.params[it].name }}")
            }
            effArgs = merged.map { it ?: NameRef("__missingArg") }     // 已报 E-TYPE-MISMATCH 缺实参/未知名；兜底值不参与核对
            effTypes = mergedT.map { it ?: syntheticT("缺实参") }
            namedArgOrder[c] = effArgs
        }
    }

    // 决策 59：Any 严苛准入——实参含 Any 则调用受限；@tuple 形参只收元组字面量（裸名实参拒）
    if (fn.params.any { syms.expand(it.type).containsAny() } && effTypes.any { it.containsAny() }) {
        d.error("E-ANY-CALL", c.pos, "函数 $name 的调用涉及 Any，推导失效，请手动标注或改用具体类型（决策 59）")
        return syntheticT("Any调用$name")
    }
    // P9（决策 44，用户授权 Max 倾向：报错 + 判定含 when 解构变量）：纯函数自递归要求**至少一个**实参是某形参的语法子项。
    // 多参数递归（map(f, t)）允许透传参数（f 原样）——停止性由递减者（t）保证；全参数都不递减才算违规。
    // unchecked 放行；@unpure 不受约束；方法路径（字典分发）暂不查。
    if (!isMethodPath && "unpure" !in fn.annotations && uncheckedDepth == 0 && name == curFunName && curFunName != null) {
        if (effArgs.none { isStructuralArg(it) })
            d.error("E-NON-STRUCTURAL-REC", c.pos,
                "函数 $name 的递归须有至少一个实参是某形参的语法子项（when 解构链）——用 unchecked 放行或改写为结构递归")
    }
    fn.params.forEachIndexed { i, p ->
        if ("tuple" in p.annotations && i < effArgs.size && effArgs[i] !is TupleExpr)
            d.error("E-TUPLE-VARARG", c.pos, "@tuple 形参 ${p.name} 只接受元组字面量，裸变量请先用 (x,) 打包（决策 61）")
    }

    if ("unpure" in fn.annotations && pure) {
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
    }
    if (fn.params.size != effTypes.size && "vararg" !in fn.annotations) {
        // O2：方法路径参数数用专属码（期望数不含隐式 self，与自由函数区分开便于定位）
        if (isMethodPath) d.error("E-METHOD-ARGS", c.pos,
            "方法 $name 期望 ${fn.params.size} 实参（不含接收者），实际 ${effTypes.size}")
        else d.error("E-TYPE-MISMATCH", c.pos, "函数 $name 期望 ${fn.params.size} 实参，实际 ${effTypes.size}")
    }

    val tsub = LinkedHashMap<String, Type>()
    fn.params.forEachIndexed { i, p ->
        if (i < effTypes.size) {
            val at = effTypes[i]
            if (!at.isSynthetic()) extractTpBinding(p.type, at, tps.toSet(), tsub)
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
            val want = syms.expand(p.type).substT(tsub)   // 别名展开（决策 30）
            if (want is FunType) {
                // T2：lambda 实参带期望类型二次检查（双向推导）；非 lambda 实参宽松放行
                val a = effArgs[i]
                if (a is LambdaExpr) {
                    val lt = checkExpr(a, f, pure, want)
                    // P2（决策 78）：lambda 实际返回类型反推类型参数（map 的 U 只能由 lambda 体推出）
                    val wr = want.ret
                    if (lt is FunType && wr is NamedType && wr.name in tps && wr.args.isEmpty() && !lt.ret.isSynthetic())
                        tsub.putIfAbsent(wr.name, lt.ret)
                }
            }
        }
        rounds++
        if (tsub.size == beforeTsub) break
    }
    if (rounds > 1) d.dedupe()
    // 非 lambda 实参核对（仅一次，避免迭代重复报错）
    fn.params.forEachIndexed { i, p ->
        if (i >= effTypes.size) return@forEachIndexed
        val want = syms.expand(p.type).substT(tsub)
        if (want !is FunType && !effTypes[i].isSynthetic() && !p.type.name.startsWith("(")) {
            if (want.isNominal() && !typeLooseEq(want, effTypes[i])) {
                // 决策 59：Any 形参接受任意实参（受限顶类型）；其余仍须名义相等
                if (!(want.name == "Any" && want.args.isEmpty())) {
                    // v2.0 空安全自动解构（AD-1..4）：类型检查**失败**时，若实参是**单字段构造子应用**
                    // （Some(x)/Ok(x)/Err(x)），解包内部值再匹配——代替子类型；不改表达式类型、单向。
                    val unwrapped = autoUnwrapCtor(effArgs[i], want)
                    if (unwrapped != null) {
                        val newArgs = effArgs.toMutableList()
                        newArgs[i] = unwrapped
                        methodSugarArgs[c] = newArgs   // codegen 侧用解包后实参生成 JS（sugarArgs 留痕复用）
                    } else {
                        d.error("E-TYPE-MISMATCH", c.pos, "实参 $i ${effTypes[i].render()} 与形参 ${want.render()} 不符")
                    }
                }
            }
        }
    }

    // O2：方法路径的实参替换表只含真实形参（self 不上表）；伴生记录仍用全量 c.args（含 self，作调用痕迹）
    val envSub = fn.params.map { it.name }.zip(effArgs).toMap()
    val propNames = fn.theory.filterIsInstance<PropParam>().map { it.name }
    val binding = LinkedHashMap<String, Prop>()
    val explicitProps = instTermsOf(c).mapNotNull { propFromSupply(it) }
    for ((i, q) in propNames.withIndex()) {
        val usesQ = fn.preps.any { mentionsPropExpr(it, q) } || fn.posts.any { mentionsPropExpr(it, q) }
        val sup = explicitProps.getOrNull(i)
        if (sup != null) {
            binding[q] = sup
            if (!proveOrDeep(f.allFacts(), sup))
                d.error("E-PROP-UNBOUND", c.pos, "显式供给 ${PropLogic.render(sup)} 在作用域不成立")
        } else if (usesQ) {
            // 自动供给：方括号命题参数是"名字"，只与作用域中的 0 元原子匹配
            val cands = f.allFacts()
                .filter { it is PAtom && it.terms.isEmpty() && it.head !in PropLogic.structuralHeads }
                .distinctBy { PropLogic.render(it) }
            when {
                cands.isEmpty() -> d.error("E-PROP-UNBOUND", c.pos, "命题参数 @$q 作用域无 0 元命题供给（用 <...> 显式传入）")
                cands.size > 1 -> d.error("E-PROP-AMBIGUOUS", c.pos, "命题参数 @$q 有 ${cands.size} 个 0 元候选，请显式供给")
                else -> binding[q] = cands.first()
            }
        }
    }

    for (pre in fn.preps) {
        val goal = substAll(PropLogic.fromExpr(pre), binding, tsub, envSub)
        if (!proveOrDeep(f.allFacts(), goal))
            d.error("E-PRE-UNPROVEN", c.pos, "调用 $name 前置 ${PropLogic.render(goal)} 未证明")
    }

    f.inject(PAtom(name, c.args))   // 伴生记录
    for (post in fn.posts) {
        val goal = PropLogic.substDollar(substAll(PropLogic.fromExpr(post), binding, tsub, envSub), c)
        f.inject(goal)
    }
    // P6（决策 82）：带约束泛型函数的调用点——逐个约束解出具体类型并解析字典，codegen 前插字典实参。
    // I-22：与命题参数同走供给机制；可见性过滤同 resolveDict（目录模式按当前模块可见路径）。
    val vis = if (moduleTree == null) null else visiblePaths
    val constraintTps = fn.theory.filterIsInstance<TypeParam>().filter { it.constraint != null }
    val dictArgs = constraintTps.mapNotNull { tp -> (tsub[tp.name] ?: return@mapNotNull null)
        .let { resolveConstraint(tp.constraint!!, it, vis) } }
    if (dictArgs.isNotEmpty() && dictArgs.size == constraintTps.size) {
        dictSubHits[c] = dictArgs
    } else if (constraintTps.isNotEmpty() && (c.callee as? NameRef)?.name == fn.name) {
        // P11 压测（决策 87 补充）：约束泛型自递归调用点——递归调用的类型实参仍是当前函数的元类型
        // （T 未具体化，tsub 解不出具体字典），直接把递归栈内当前函数的约束槽透传给递归调用。
        dictSubHits[c] = constraintTps.map { "d_${it.constraint}_${it.name}" }
    }
    return (fn.retType ?: syms.inferredRets[fn.name] ?: namedT("Null", emptyList())).substT(tsub)
}

/** 显式供给实参（实例化项，限定/普通共用） */
private fun Checker.instTermsOf(c: CallExpr): List<Expr> = when (val callee = c.callee) {
    is InstExpr -> callee.terms
    else -> emptyList()
}

/**
 * v2.0 空安全自动解构（AD-1..4）：实参类型检查**失败**时尝试。
 * 实参是**单字段构造子应用**（Some(x)/Ok(x)/Err(x)，构造子 fields.size==1）且解构后
 * 内部值类型与形参 want 松弛匹配 → 返回解包的内部表达式；否则 null（保持原错误）。
 * 仅作用于**字面量构造子**调用（不含变量引用——AD-4 单向、不穿透边界，变量形态后续迭代）。
 */
private fun Checker.autoUnwrapCtor(arg: Expr, want: Type): Expr? {
    val ce = arg as? CallExpr ?: return null
    val cname = (ce.callee as? NameRef)?.name ?: return null
    val info = syms.ctors[cname] ?: return null
    if (info.fields.size != 1) return null           // AD-2：仅单字段构造子
    if (ce.args.size != 1) return null
    val inner = ce.args[0]
    // 规避：名字位是局部变量构造（如 `var Some = ...`），仅构造函数名字引用才解构；且不穿透函数边界
    // （构造子全局登记，无局部遮蔽概念，这里直接按登记判定）。
    // 解构后内部值类型与 want 松弛匹配（数值升格/同名义）才放行——避免 `Some(Str)` 传给 Rat 形参。
    val innerT = typeOf(inner)
    if (!innerT.isSynthetic() && want.isNominal() && !typeLooseEq(innerT, want)) return null
    return inner
}

/** 表达式类型的最小求值：仅用于自动解构的内层类型预判。 */
private fun Checker.typeOf(e: Expr): Type = when (e) {
    is IntLit -> namedT("Nat", emptyList())
    is FloatLit -> namedT("Rat", emptyList())
    is StrLit -> namedT("Str", emptyList())
    else -> syntheticT("解构内层")
}

/**
 * 从实参类型递归解出形参里的类型参数（P2：`Array<T>` 这类**嵌套**类型参数，P6 之前不做真合一，只做同构解出）。
 * P7（决策 83）：内部换装 TypeInfer.unifyInto 标准合一——同构递归（NamedType 逐实参 / FunType 参数+返回 / TupleType 逐项）
 * 之上增加 occurs check（拒绝 `T → List<T>` 环）与已绑定一致性（静默，typeLooseEq 宽容，语义同 P2 putIfAbsent）。
 * 合成类型不绑定（unifyInto 宽容 true）。解不出就留空（报错走 E-TYPE-MISMATCH 既有通道）。
 */
private fun extractTpBinding(want: Type, got: Type, tps: Set<String>, tsub: LinkedHashMap<String, Type>) {
    unifyInto(want, got, tps, tsub)   // 同包顶层函数（TypeInfer.kt）
}

/** P9（决策 44，递归停机）：实参是否为某形参的语法子项。
 *  变量实参：形参自身（链长 0）→ 否（无进展）；解构血缘链（structSub，when 解构登记的 子→父）向上可到形参 → 是。
 *  非变量实参（`n - 1`、调用、字面量）→ 否。unchecked/@unpure 的放行在调用侧判断。 */
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
            val fn = sym.syms.funs[sym.name] ?: sym.syms.findFun(sym.name)!!
            moduleHits[c.callee] = moduleJsName(node.absPath, sym.name)
            checkFnCall(fn, sym.name, c, f, pure, c.args, argTypes, isMethodPath = false)
        }
        QSymKind.CTOR -> checkCtorCall(sym.syms.ctors.getValue(sym.name), sym.name, c, argTypes, node.absPath)
        QSymKind.STRUCT -> checkStructCall(sym.syms.structs.getValue(sym.name), sym.name, c, argTypes, node.absPath)
        QSymKind.METHOD -> {
            // 限定方法 `core.show(p)`：首实参即接收者（自由形态语义）
            if (argTypes.isEmpty()) {
                d.error("E-NO-SELF", c.pos, "方法 ${sym.name} 需要接收者：写成 core.${sym.name}(接收者, ...)")
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
