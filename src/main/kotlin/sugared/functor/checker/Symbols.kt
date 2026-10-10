package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 符号收集与 prelude 注册（决策 45 的注入机制所在）。
 * 两遍收集：collectDecl 登记符号 → checkDeclTypes 检查可解析性（支持别名前向引用）。
 */

/**
 * 决策 34：每个未标 `@unpure` 的具名函数，编译器自动给出 `pure<name>` 证明。
 * 在符号收集完成后统一注册，使互相调用的函数无论声明顺序都能查到对方的纯度。
 * 内建非纯函数（print）不在 syms.funs 中，天然不注册。
 * P0：pure 事实仍是每模块本名注册（跨模块 @unpure 调用需 unchecked 逃逸，见《模块系统.md》限制）。
 */
internal fun Checker.registerPureFacts() {
    globalFacts.clear()
    for ((name, fn) in syms.funs) {
        if ("unpure" !in fn.annotations) globalFacts += PAtom("pure", listOf(NameRef(name)))
        // v2.0 异步（决策 93）：@async 函数注册自然命题 async<name>——同步上下文调用须显式供给/放行
        if ("async" in fn.annotations) globalFacts += PAtom("async", listOf(NameRef(name)))
    }
    // impl 方法同样注册（按方法名）
    for ((name, entries) in syms.methods) {
        if (entries.any { "unpure" !in it.fn.annotations }) globalFacts += PAtom("pure", listOf(NameRef(name)))
    }
}

internal fun Checker.registerImmutableFacts() {
    // v2.0 可变性（副作用 §4.2，I-1..I-7）：immutable<T> 自动注入——不动点传播。
    // 基础类型天然 immutable（I-1）；基本类型名之外的 Bool/Null/Optional…由 prelude 注册，此处只算结构/枚举。
    // 规则：
    //  - I-2/I-3：struct/enum 无 @mut 字段 且 所有字段类型 immutable → 该类型 immutable
    //  - I-4：Array 默认可变；I-5：Any 默认可变；I-7：@mut T 不变（类型维度未引入，先以字段 @mut 近似）
    //  - 类型参数（NamedType 无实参且首字母小写/在 theory 中）与未知类型 → 保守可变
    val immutable = mutableSetOf<String>(*BASE_TYPES.filter { it != "Array" && it != "Any" && it != "Task" && it != "Channel" }.toTypedArray())
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
internal fun Checker.registerUntouchFacts() {
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
    syms.typeParamNames[decl.name] = tpNamesOf(decl)
    for (c in decl.ctors) {
        val builtinCtor = c.name in setOf("true", "false", "null", "None", "Some")
        if (syms.ctors.containsKey(c.name) && !builtinCtor)
            d.error("E-DUP-DECL", decl.pos, "构造子 ${c.name} 重复")
        else if (!syms.ctors.containsKey(c.name)) syms.ctors[c.name] = CtorInfo(decl, c.fields)
    }
}

internal fun Checker.collectDecl(decl: Decl) {
    when (decl) {
        is StructDecl -> {
            if (syms.structs.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "结构体 ${decl.name} 重复声明")
            syms.typeParamNames[decl.name] = tpNamesOf(decl)
        }
        is EnumDecl -> {
            if (decl.name !in setOf("Bool", "Null", "Optional")) registerEnum(decl)
        }
        is ClassDecl -> {
            if (syms.classes.putIfAbsent(decl.name, decl) != null)
                d.error("E-DUP-DECL", decl.pos, "型类 ${decl.name} 重复声明")
            syms.typeParamNames[decl.name] = tpNamesOf(decl)
            // 决策 60（T5）：登记候选方法名，使"只有 class、没有 impl"的调用报 E-NO-INSTANCE
            decl.members.forEach { syms.traitMethods.add(it.name) }
        }
        is FunDecl -> {
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
        is StructDecl -> decl.fields.forEach { fp ->
            checkTypeResolvable(fp.type, tpNamesOf(decl))
            // O3（决策 69）：字段默认值须与字段类型相符（声明期用空帧检查，引用未定义即报错）
            fp.default?.let { dv ->
                val vt = checkExpr(dv, Frame(null), pure = true)
                val want = syms.expand(fp.type)
                if (!vt.isSynthetic() && want.isNominal() && !typeLooseEq(want, vt))
                    d.error("E-TYPE-MISMATCH", dv.pos, "字段 ${fp.name} 的默认值 ${vt.render()} 与 ${want.render()} 不符")
            }
        }
        // O3（决策 69）：默认值只属于 struct 字段——函数形参（含类/impl 成员）带默认值报错
        is FunDecl -> {
            decl.params.forEach { p ->
                if (p.default != null) d.error("E-DEFAULT-PARAM", decl.pos, "函数 ${decl.name} 形参 ${p.name} 不允许默认值（仅 struct 字段支持，决策 69）")
            }
            // O4（决策 70）：main 作为自动入口必须 0 形参 0 类型参（重复声明由 E-DUP-DECL 兜底）
            if (decl.name == "main" && (decl.params.isNotEmpty() || decl.theory.isNotEmpty()))
                d.error("E-MAIN-PARAMS", decl.pos, "入口函数 main 不能有形参或类型参数（决策 70）")
        }
        is ClassDecl -> decl.members.forEach { m ->
            // O3（决策 69）：型类成员形参不允许默认值
            m.params.forEach { p -> if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "型类成员 ${m.name} 形参 ${p.name} 不允许默认值（决策 69）") }
            // HKT：成员签名（形参/返回类型）走可解析性检查，并带 class 级 kind arity（《高阶类型.md》HKT-S5）
            val memberTps = tpNamesOf(decl) + tpNamesOf(m)
            val classArity = tpArityOf(decl)
            m.retType?.let { checkTypeResolvable(it, memberTps, classArity) }
            m.params.forEach { checkTypeResolvable(it.type, memberTps, classArity) }
        }
        is ImplDecl -> {
            decl.members.forEach { m -> m.params.forEach { p ->
                if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "impl 方法 ${m.name} 形参 ${p.name} 不允许默认值（决策 69）")
            } }
            // P0：impl 的 trait/self 若为限定类型，按模块可见性校验
            checkTypeResolvable(decl.trait, emptyList())
            // HKT（《高阶类型.md》HKT-S6）：impl self 的 kind 占位变量（`List[a]` 的 a）是隐式绑定，
            // 属合法名字——并入 tps 放行（self 其余实参仍按普通类型检查）
            val selfTps = decl.self.args.filterIsInstance<NamedType>().filter { it.args.isEmpty() }.map { it.name }
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
    else -> emptyList()
}

/** HKT（《高阶类型.md》HKT-S1）：声明名 → 类型参数名 → arity（kind `*ⁿ→*` 的 n；0 = 普通类型变量）。
 *  供 checkTypeResolvable 做 kind 形参的应用校验（单独出现 / 实参数不符 → E-KIND-MISMATCH）。 */
internal fun Checker.tpArityOf(decl: Decl): Map<String, Int> = when (decl) {
    is StructDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is EnumDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is ClassDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    is FunDecl -> decl.theory.filterIsInstance<TypeParam>().associate { it.name to it.arity }
    else -> emptyMap()
}

internal fun Checker.checkTypeResolvable(t: Type, tps: List<String>, arity: Map<String, Int> = emptyMap()) {
    when (t) {
        is FunType -> { t.params.forEach { checkTypeResolvable(it, tps, arity) }; checkTypeResolvable(t.ret, tps, arity) }
        is TupleType -> t.items.forEach { checkTypeResolvable(it, tps, arity) }
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
                    t.args.isEmpty() -> d.error("E-KIND-MISMATCH", "", "类型构造子 ${t.name} 须应用（如 ${t.name}[_]，arity=$tpArity）——HKT-S5")
                    t.args.size != tpArity ->
                        d.error("E-KIND-MISMATCH", "", "构造子 ${t.name} 实参数 ${t.args.size} 与声明 arity $tpArity 不符——HKT-S1/S2")
                    else -> {}
                }
            }
            if (t.name in tps) return
            // P6（决策 82）：跨模块类型引用——裸名 List/Result 在类型位置也可解析（可见模块兜底）
            if (syms.baseOf(t) == null && !typeVisible(t.name) && tpArity == null) d.error("E-UNBOUND-NAME", "", "未知类型 ${t.render()}")
            t.args.forEach { checkTypeResolvable(it, tps, arity) }
        }
    }
}
