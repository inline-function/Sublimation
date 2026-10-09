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
        is ClassDecl -> decl.members.forEach { m -> m.params.forEach { p ->
            if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "型类成员 ${m.name} 形参 ${p.name} 不允许默认值（决策 69）")
        } }
        is ImplDecl -> {
            decl.members.forEach { m -> m.params.forEach { p ->
                if (p.default != null) d.error("E-DEFAULT-PARAM", m.pos, "impl 方法 ${m.name} 形参 ${p.name} 不允许默认值（决策 69）")
            } }
            // P0：impl 的 trait/self 若为限定类型，按模块可见性校验
            checkTypeResolvable(decl.trait, emptyList())
            checkTypeResolvable(decl.self, emptyList())
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

internal fun Checker.checkTypeResolvable(t: Type, tps: List<String>) {
    when (t) {
        is FunType -> { t.params.forEach { checkTypeResolvable(it, tps) }; checkTypeResolvable(t.ret, tps) }
        is TupleType -> t.items.forEach { checkTypeResolvable(it, tps) }
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
            t.args.forEach { checkTypeResolvable(it, tps) }
        }
        is NamedType -> {
            if (t.name in tps) return
            // P6（决策 82）：跨模块类型引用——裸名 List/Result 在类型位置也可解析（可见模块兜底）
            if (syms.baseOf(t) == null && !typeVisible(t.name)) d.error("E-UNBOUND-NAME", "", "未知类型 ${t.render()}")
            t.args.forEach { checkTypeResolvable(it, tps) }
        }
    }
}
