package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 符号收集与 prelude 注册（决策 45 的注入机制所在）。
 * 两遍收集：collectDecl 登记符号 → checkDeclTypes 检查可解析性（支持别名前向引用）。
 */

/**
 * 决策 34：每个未标 `@impure` 的具名函数，编译器自动给出 `pure<name>` 证明。
 * 在符号收集完成后统一注册，使互相调用的函数无论声明顺序都能查到对方的纯度。
 * 内建非纯函数（print）不在 syms.funs 中，天然不注册。
 * P0 补全：可见模块的非纯函数 pure<f> 亦注册（按裸名）；同名任一处 @impure 则整名不注册（见函数内注释）。
 */
internal fun Checker.registerPureFacts() {
    globalFacts.clear()
    for ((name, fn) in syms.funs) {
        // v2.0 @root：无体内建声明的纯度/副作用由**内建表**决定（builtinimpure/builtinPure），用户注解不参与——
        // 否则用户 `@root fun readFile(...)`（无 @impure）会被误注册 pure<readFile>，制造错误证明。
        // 安卓内建（@android）只在用户声明带 @android 注解时才接管注解——否则用户自定义
        // （如 @impure fun log(x: Nat)）仍按自己的注解注册（回归 E2E v30 函数值 purity 用例）。
        val androidBuiltin = if ("android" in fn.annotations) builtinAndroid(name) else null
        val builtin = builtinimpure(name) ?: builtinPure(name) ?: androidBuiltin
        val anns = if (builtin != null) builtin.annotations else fn.annotations
        if ("impure" !in anns) globalFacts += PAtom("pure", listOf(NameRef(name)))
        // v2.0 异步（决策 93）：@async 函数注册自然命题 async<name>——同步上下文调用须显式供给/放行
        if ("async" in anns) globalFacts += PAtom("async", listOf(NameRef(name)))
    }
    // P0 跨模块补全：可见模块的 pure<f> 一并注册（按裸名——E-IMPURE-CALL 检查用的就是裸名）。
    // 健全性：某名字在任一可见模块（含本模块）有 @impure 定义则**不注册**——
    // 调用解析无法区分同名异模块，否则对 impure 版本的裸名调用会被同名 pure 事实错误放行。
    if (moduleTree != null) {
        val impureNames = HashSet<String>()
        val foreignPure = LinkedHashSet<String>()
        for (p in visiblePaths) {
            val s = allSymbols[p] ?: continue
            for ((name, fn) in s.funs) {
                val androidBuiltin2 = if ("android" in fn.annotations) builtinAndroid(name) else null
                val builtin = builtinimpure(name) ?: builtinPure(name) ?: androidBuiltin2
                val anns = if (builtin != null) builtin.annotations else fn.annotations
                if ("impure" in anns) impureNames += name
                else if (name !in syms.funs) foreignPure += name   // 本模块已注册过的不重复
            }
        }
        for (name in foreignPure) if (name !in impureNames)
            globalFacts += PAtom("pure", listOf(NameRef(name)))
    }
    // impl 方法同样注册（按方法名）
    for ((name, entries) in syms.methods) {
        if (entries.any { "impure" !in it.fn.annotations }) globalFacts += PAtom("pure", listOf(NameRef(name)))
    }
}

@Deprecated("已废弃（2026-10-12 用户拍板）：不再调用生成 immutable<T> 事实；待 MutType 维度按《副作用与可变性控制.md》恢复")
internal fun Checker.registerImmutableFacts() {
    // ⚠️ 废弃保留：v2.0 可变性（副作用 §4.2，I-1..I-7）：immutable<T> 自动注入——不动点传播。
    // 基础类型天然 immutable（I-1）；基本类型名之外的 Bool/Null/Optional…由 prelude 注册，此处只算结构/枚举。
    // 规则：
    //  - I-2/I-3：struct/enum 无 @mut 字段 且 所有字段类型 immutable → 该类型 immutable
    //  - I-5：Any 默认可变；I-7：@mut T 不变（类型维度未引入，先以字段 @mut 近似）
    //  - 类型参数（NamedType 无实参且首字母小写/在 theory 中）与未知类型 → 保守可变
    val immutable = mutableSetOf<String>(*BASE_TYPES.filter { it != "Any" && it != "Task" && it != "Channel" }.toTypedArray())
    immutable += "Bool"; immutable += "Null"; immutable += "Nothing"
    // 函数类型恒 immutable（I-6）已由类型位处理；此处只算名义类型名。
    fun reprImm(t: Type): Boolean = when (t) {
        is NamedType -> if (t.args.isEmpty()) immutable.contains(t.name) else {
            // 容器类型：名字 immutable 且**全部实参** immutable（如 List[Nat] → 看 List 名；Optional[Test] → Test）
            // 保守：Args 当中含不可判定项则不可变失败
            t.name in immutable && t.args.all { reprImm(it) }
        }
        is QualifiedType -> immutable.contains(t.name)
        is TupleType -> t.items.all { reprImm(it) }
        is FunType -> true
        is UnionType -> t.variants.all { reprImm(it) }
        is MutType -> false   // I-7：@mut T 不是 immutable（明确可变视图）
        is AtomicType -> reprImm(t.inner)   // @atomic T：原子性不破坏不可变性（JS 单线程按值读写）
    }
    fun declImmutable(fields: List<Param>): Boolean =
        fields.none { "mut" in it.annotations } && fields.all { reprImm(it.type) }
    // 不动点：每次迭代把新判定的 struct/enum 加入 immutable，直到收敛（支持相互/多层引用）
    var changed = true
    val guard = 0
    while (changed) {
        changed = false
        for (st in syms.structs.values) {
            if (st.name in immutable) continue
            if (declImmutable(st.fields)) { immutable += st.name; changed = true }
        }
        for (en in syms.enums.values) {
            if (en.name in immutable) continue
            val fs = en.ctors.flatMap { it.fields }.map { Param("_", it) }
            if (declImmutable(fs)) { immutable += en.name; changed = true }
        }
    }
    // 用 guard 避免编译器报"赋值永不被使用"——实际是（不变式，可安全忽略）
    check(guard == 0)
    // 注入 immutable<T> 命题（仅名义类型名；参数化类型如 List[T] 以名注册——实参合规性由 reprImm 保守把关）
    for (name in immutable) if (name !in BASE_TYPES || name == "Bool" || name == "Null" || name == "Nothing")
        globalFacts += PAtom("immutable", listOf(NameRef(name)))
}

/** v2.0 可变性（副作用 §6.2）：非 @mut 形参自动获得 untouch<param>——函数不修改该参数。
 *  与 pure<f> 同机制，注册到 globalFacts 供调用点/函数体推理引用。 */
@Deprecated("已废弃（2026-10-12 用户拍板）：不再调用生成 untouch<param> 事实；待 MutType 维度按《副作用与可变性控制.md》恢复")
internal fun Checker.registerUntouchFacts() {
    // ⚠️ 废弃保留：v2.0 可变性（副作用 §6.2）：非 @mut 形参自动 untouch<param>。恢复条件同 immutable。
    for ((name, fn) in syms.funs) {
        fn.params.forEach { p ->
            // 非 @mut 参数 → untouch<p.name>；@mut 参数默认不获得（§6.2：调用者保守假设"会被改"）
            if ("mut" !in p.annotations) globalFacts += PAtom("untouch", listOf(NameRef(p.name)))
        }
    }
    for ((_, entries) in syms.methods) for (e in entries) {
        e.fn.params.forEach { p ->
            if ("mut" !in p.annotations && p.name != "self") globalFacts += PAtom("untouch", listOf(NameRef(p.name)))
        }
    }
}

internal fun Checker.registerEnum(decl: EnumDecl) {
    if (syms.enums.putIfAbsent(decl.name, decl) != null && decl.name !in setOf("Bool", "Null", "Optional"))
        d.error("E-DUP-DECL", decl.pos, "枚举 ${decl.name} 重复声明")
    // P3（v2.1）：无构造子枚举可省略花括号（`enum E`），发提示
    if (decl.ctors.isEmpty())
        d.hint("H-EMPTY-DECL", decl.pos, "枚举 ${decl.name} 无构造子，可省略花括号写成 `enum ${decl.name}`")
    syms.typeParamNames[decl.name] = tpNamesOf(decl)
    for (c in decl.ctors) {
        val builtinCtor = c.name in setOf("true", "false", "null", "None", "Some")
        val prev = syms.ctors[c.name]
        when {
            // 首注册（含 Optional 的 None/Some 等自声明枚举）
            prev == null -> syms.ctors[c.name] = CtorInfo(decl, c.fields)
            // v2.1（限制解开）：跨枚举同名构造子允许共存，裸名留首个，后者提示用 E::T 消歧
            prev.enum.name != decl.name && !builtinCtor ->
                d.hint("H-AMBIGUOUS-CTOR", decl.pos, "构造子 ${c.name} 已在枚举 ${prev.enum.name} 声明，裸名引用有歧义，请用 ${decl.name}::${c.name} 或 ${prev.enum.name}::${c.name}")
            // 同枚举内重复声明仍是错误；内建保留名（true/false/null/None/Some）重复静默（codegen 特判承接）
            prev.enum.name == decl.name && !builtinCtor ->
                d.error("E-DUP-DECL", decl.pos, "构造子 ${c.name} 重复")
            else -> {}
        }
        // v2.1（对齐 Rust 关联项）：同时注册复合键 `枚举::构造子`（E::A 成员访问用）——不覆盖裸名
        syms.ctors.putIfAbsent("${decl.name}::${c.name}", CtorInfo(decl, c.fields))
    }
}



internal fun Checker.collectDecl(decl: Decl) {
    when (decl) {
        is StructDecl -> {
            if (syms.structs.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "结构体 ${decl.name} 重复声明")
            // P3（v2.1）：无字段结构体可省略参数括号（`struct P`），发提示
            if (decl.fields.isEmpty())
                d.hint("H-EMPTY-DECL", decl.pos, "结构体 ${decl.name} 无字段，可省略参数括号写成 `struct ${decl.name}`")
            syms.typeParamNames[decl.name] = tpNamesOf(decl)
            // v3.0 特性 1：伴生命题禁止引用 @mut 字段（声明位检查）
            decl.fields.forEach { fp ->
                if ("mut" in fp.annotations && decl.props.any { propHasName(PropLogic.fromExpr(it), fp.name) })
                    d.error("E-STRUCT-MUT-PROP", decl.pos, "结构体 ${decl.name} 声明了普惠成立的性质，但其中引用了 @mut 字段 ${fp.name}（可变字段的值会变，无法保证恒成立）")
            }
        }
        is ClassDecl -> {
            if (syms.classes.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "类型类 ${decl.name} 重复声明")
            // P3（v2.1）：无成员类型类可省略花括号（`class C`），发提示
            if (decl.members.isEmpty())
                d.hint("H-EMPTY-DECL", decl.pos, "类型类 ${decl.name} 无成员，可省略花括号写成 `class ${decl.name}`")
            syms.typeParamNames[decl.name] = tpNamesOf(decl)
            // 决策 60（T5）：登记候选方法名，使"只有 class、没有 impl"的调用报 E-NO-INSTANCE
            decl.members.forEach { syms.traitMethods.add(it.name) }
            // v2.1 子类型类：登记父链（impl 强制/约束蕴含/覆盖优先级用）
            if (decl.parents.isNotEmpty()) syms.clsParents[decl.name] = decl.parents
        }
        is EnumDecl -> {
            if (decl.name !in setOf("Bool", "Null", "Optional")) registerEnum(decl)
        }
        is PropDefDecl -> {
            if (syms.propDefs.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "自定义命题 ${decl.name} 重复声明")
        }
        is FunDecl -> {
            // 定理函数（纯 + 返回 Null，无 @theorem 注解）登记进定理空间（∴ 搜索只搜这里）
            if (isTheoremFun(decl)) syms.theorems[decl.name] = decl
            // v3.0 特性 6：@classic 标注的函数 → core.classic 已挂载（if[p] 前置检查用）
            if ("classic" in decl.annotations) syms.classicMounted = true
            // `_` 是匿名占位名，不入符号表（指导§54：标注为 _ 的标识符无法被后续引用）
            if (decl.name == "_") return
            val prev = syms.funs.putIfAbsent(decl.name, decl)
            if (prev != null) {
                // v2.0 重载（决策 92 配套）：同名且**首参类型不同** → 登记为候选重载（方法糖按接收者分派）；
                // 首参相同仍报 E-DUP-DECL（含 0 参函数：null == null）。
                val p0 = prev.params.firstOrNull()?.type?.render()
                val n0 = decl.params.firstOrNull()?.type?.render()
                if (p0 != n0) syms.funOverloads.getOrPut(decl.name) { mutableListOf(prev) }.add(decl)
                else d.error("E-DUP-DECL", decl.pos, "函数 ${decl.name} 重复声明")
            }
        }
        is TypeAliasDecl -> {
            if (syms.aliases.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "类型别名 ${decl.name} 重复声明")
            syms.typeParamNames[decl.name] = tpNamesOf(decl)
        }
        is ImplDecl -> {}
    }
}

/** 第二遍：符号全部就位后检查字段/构造子/别名右值的类型可解析性（别名可前向引用） */
internal fun Checker.checkDeclTypes(decl: Decl) {
    when (decl) {
        is PropDefDecl -> {
            // P7：等价式此前完全漏检，未定义的名字/函数要到证明展开时才崩；参数是命题项自由变量，以 synthetic 声明避免误报。
            val eq = decl.equiv
            if (eq != null) {
                val fr = Frame(null)
                decl.params.forEach { fr.declareVar(it, syntheticT("命题参数"), decl.pos) }
                checkExpr(eq, fr, pure = true)
            }
        }
        is StructDecl -> decl.fields.forEach { fp ->
            checkTypeResolvable(fp.type, tpNamesOf(decl))
            // O3（决策 69）：字段默认值须与字段类型相符（声明期用空帧检查，引用未定义即报错）
            fp.default?.let { dv ->
                val vt = checkExpr(dv, Frame(null), pure = true)
                val want = syms.expand(fp.type)
                if (!vt.isSynthetic() && want.isNominal() && want.name != "Any" && !typeLooseEq(want, vt))
                    d.error("E-TYPE-MISMATCH", dv.pos, "字段 ${fp.name} 的默认值 ${vt.render()} 与 ${want.render()} 不符")
            }
        }
        // O3（决策 69）扩展：函数形参允许默认值（尾部连续，Kotlin 规则）；类型类成员/impl 方法仍禁止
        is FunDecl -> {
            // 函数默认值规则（决策 69 扩展）：尾部连续（首个默认值之后必须全有默认值）；默认值类型与形参一致
            val firstDef = decl.params.indexOfFirst { it.default != null }
            if (firstDef >= 0 && decl.params.drop(firstDef + 1).any { it.default == null })
                d.error("E-DEFAULT-PARAM", decl.pos, "函数 ${decl.name} 形参 ${decl.params[firstDef].name} 有默认值，其后必须全部有默认值")
            decl.params.forEach { p -> p.default?.let { dv ->
                val vt = checkExpr(dv, Frame(null), pure = true)
                val want = syms.expand(p.type)
                if (!vt.isSynthetic() && want.isNominal() && want.name != "Any" && !typeLooseEq(want, vt))
                    d.error("E-TYPE-MISMATCH", dv.pos, "形参 ${p.name} 的默认值 ${vt.render()} 与 ${want.render()} 不符")
            } }
            // O4（决策 70）：main 作为自动入口必须 0 形参 0 类型参（重复声明由 E-DUP-DECL 兜底）
            if (decl.name == "main" && (decl.params.isNotEmpty() || decl.theory.isNotEmpty()))
                d.error("E-MAIN-PARAMS", decl.pos, "入口函数 main 不能有形参或类型参数。写成 `fun main() { … }`")
            // v2.0 @root 机制 + 脚本模式 @host：无体函数必须标注 @root/@host 或为定理/公理
            if (decl.body == null) {
                when {
                    // 无体定理函数 = 公理（签名即定理；有体定理照常生成运行时代码）
                    isTheoremFun(decl) -> {}
                    "host" in decl.annotations -> {}  // 脚本模式 @host：函数体由宿主运行时注入（解释器侧 Interpreter.register）
                    // 安卓内建（android doc/安卓标准库.md）：@android 与 @root 同构——白名单门 + 无体豁免
                    "android" in decl.annotations -> {
                        when {
                            decl.name !in ANDROID_WHITELIST ->
                                d.error("E-ANDROID-NOT-ALLOWED", decl.pos, "函数 ${decl.name} 标了 @android，但这个名字不是编译器支持的安卓内建函数。安卓内建包括 log/toast/layout/onCreate/onStart/onResume/onPause/onStop/onDestroy/onRestart")
                            "impure" !in decl.annotations ->
                                d.hint("H-ANDROID-BUILTIN", decl.pos, "@android 函数 ${decl.name} 建议标 @impure（桥接原生，有副作用）")
                            else -> d.hint("H-ANDROID-BUILTIN", decl.pos, "@android 函数 ${decl.name} 由编译期内建 JS 桥接实现（KT 端经 webview 注入）")
                        }
                    }
                    "root" !in decl.annotations ->
                        d.error("E-FUN-NO-BODY", decl.pos, "函数 ${decl.name} 没有函数体。普通函数必须写出实现；内建函数请标注 @root（编译器对白名单开放）")
                    decl.name !in ROOT_WHITELIST ->
                        d.error("E-ROOT-NOT-ALLOWED", decl.pos, "函数 ${decl.name} 标了 @root，但这个名字不是编译器支持的内建函数。普通函数用普通实现即可，别加 @root")
                    else -> d.hint("H-ROOT-BUILTIN", decl.pos, "@root 函数 ${decl.name} 由编译器内建 JS 实现，调用点直接翻译为原生 JS（不生成函数定义）")
                }
            }
        }
        is ClassDecl -> decl.members.forEach { m ->
            // O3（决策 69）：类型类成员形参不允许默认值
            m.params.forEach { p -> if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "类型类成员 ${m.name} 形参 ${p.name} 不允许默认值") }
            // HKT：成员签名（形参/返回类型）走可解析性检查，并带 class 级 kind arity（《高阶类型.md》HKT-S5）
            val memberTps = tpNamesOf(decl) + tpNamesOf(m)
            val classArity = tpArityOf(decl)
            m.retType?.let { checkTypeResolvable(it, memberTps, classArity) }
            m.params.forEach { checkTypeResolvable(it.type, memberTps, classArity) }
        }
        is ImplDecl -> {
            decl.members.forEach { m -> m.params.forEach { p ->
                if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "impl 方法 ${m.name} 形参 ${p.name} 不允许默认值")
            } }
            // P0：impl 的 trait/self 若为限定类型，按模块可见性校验（无类型类扩展 impl 的 trait 为 null）
            decl.trait?.let { checkTypeResolvable(it, emptyList()) }
            // HKT（《高阶类型.md》HKT-S6）：impl self 的 kind 占位变量（`List[a]` 的 a）是隐式绑定，
            // 属合法名字——并入 tps 放行（self 其余实参仍按普通类型检查）
            // 内涵同 selfPlaceholders（ImplCheck）：经典小写占位、简写 _ 与用户变量（E/K）皆合法隐式绑定
            val selfTps = selfPlaceholders(decl.self)
            checkTypeResolvable(decl.self, selfTps)
        }
        is EnumDecl -> if (decl.name !in setOf("Bool", "Null", "Optional"))
            decl.ctors.forEach { c -> c.fields.forEach { checkTypeResolvable(it, tpNamesOf(decl)) } }
        is TypeAliasDecl -> {
            // 第二遍：struct/enum/class 均已登记，可双向查重名
            if (decl.name in BASE_TYPES || syms.structs.containsKey(decl.name) ||
                syms.enums.containsKey(decl.name) || syms.classes.containsKey(decl.name)
            ) d.error("E-DUP-DECL", decl.pos, "类型别名 ${decl.name} 与既有类型重名")
            // 别名右值以别名的参数集检查（Optional<Nat> 里 Nat 是实参不是形参引用）
            checkTypeResolvable(decl.target, tpNamesOf(decl))
        }
    }
}

internal fun Checker.tpNamesOf(decl: Decl): List<String> = when (decl) {
    is StructDecl -> decl.theory.filterIsInstance<TypeParam>().map { it.name }
    is EnumDecl -> decl.theory.filterIsInstance<TypeParam>().map { it.name }
    is ClassDecl -> decl.theory.filterIsInstance<TypeParam>().map { it.name }
    is FunDecl -> decl.theory.filterIsInstance<TypeParam>().map { it.name }
    // 泛型别名头（type F[a] = Optional[a]）：头参是右值合法的类型变量
    is TypeAliasDecl -> decl.theory.filterIsInstance<TypeParam>().map { it.name }
    else -> emptyList()
}

/** HKT（《高阶类型.md》HKT-S1）：声明名 → 类型参数名 → arity（kind `*ⁿ→*` 的 n；0 = 普通类型变量）。
 *  供 checkTypeResolvable 做 kind 形参的应用校验（单独出现 / 实参数不符 → E-KIND-MISMATCH）。 */
internal fun Checker.tpArityOf(decl: Decl): Map<String, Int> = when (decl) {
    is StructDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is EnumDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is ClassDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is FunDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is TypeAliasDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    else -> emptyMap()
}

internal fun Checker.checkTypeResolvable(t: Type, tps: List<String>, arity: Map<String, Int> = emptyMap()) {
    when (t) {
        is FunType -> {
            // v3.0（完美函数类型）：theory 名是绑定者（α 安全）——params/ret 里的同名引用可解析
            val bound = t.theory.map { it.name } + tps
            t.params.forEach { checkTypeResolvable(it, bound, arity) }
            checkTypeResolvable(t.ret, bound, arity)
        }
        is TupleType -> t.items.forEach { checkTypeResolvable(it, tps, arity) }
        is UnionType -> t.variants.forEach { checkTypeResolvable(it, tps, arity) }
        is MutType -> checkTypeResolvable(t.inner, tps, arity)
        is AtomicType -> checkTypeResolvable(t.inner, tps, arity)
        is QualifiedType -> {
            // P0：跨模块限定类型——目标模块须可见且含该类型名（struct/enum/class/别名/基类型）
            val target = resolveTypeModule(t)
            if (target == null) {
                d.error("E-UNBOUND-NAME", "", "未知模块限定类型 ${t.render()}（模块不可见或不存在）")
            } else {
                val ts = allSymbols[target.absPath]
                if (ts == null || ts.baseOf(namedT(t.name)) == null)
                    d.error("E-UNBOUND-NAME", "", "模块 ${target.absPath.ifEmpty { "/" }} 中无类型 ${t.name}")
            }
            t.args.forEach { checkTypeResolvable(it, tps, arity) }
        }
        is NamedType -> {
            // HKT（《高阶类型.md》）：构造子形参 F（arity>0）只能以应用形态出现——单独用作类型报错
            val tpArity = arity[t.name]
            if (tpArity != null && tpArity > 0) {
                when {
                    t.args.isEmpty() -> d.error("E-KIND-MISMATCH", "", "类型构造子 `${t.name}` 不能裸用，必须带上类型参数。写成 `${t.name}[某个具体类型]`")
                    t.args.size != tpArity ->
                        d.error("E-KIND-MISMATCH", "", "类型 `${t.name}` 想要 $tpArity 个类型参数，但你填了 ${t.args.size} 个（数一下对不对）")
                    else -> {
                        // HKT-R1（《高阶类型.md》§4.1，E-NON-PATTERN-HKT）：kind 形参应用实参必须是独立类型
                        // 变量/具体构造子——禁止嵌套 kind 变量（F[G[Nat]]，G 同为 arity>0 形参）。保 pattern 片段。
                        t.args.forEach { a ->
                            if (a is NamedType && (arity[a.name] ?: 0) > 0)
                                d.error("E-NON-PATTERN-HKT", "", "类型参数嵌套不支持：`${t.name}[${a.render()}]` 里 `${a.render()}` 自己也是类型构造子。类型构造子的实参必须是具体类型")
                        }
                    }
                }
            }
            if (t.name in tps) return
            // P6（决策 82）：跨模块类型引用——裸名 List/Result 在类型位置也可解析（可见模块兜底）
            if (syms.baseOf(t) == null && !typeVisible(t.name) && tpArity == null) d.error("E-UNBOUND-NAME", "", "未知类型 ${t.render()}")
            t.args.forEach { checkTypeResolvable(it, tps, arity) }
        }
    }
}
