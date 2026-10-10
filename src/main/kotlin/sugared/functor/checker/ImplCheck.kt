package sugared.functor.checker

import sugared.functor.ast.*

/** impl 完整性检查（决策 7/25）。**注意**：方法名只有在存在 `impl` 时才进入 syms.methods——
 *  仅有 class 声明而无任何实例时，调用它应报 E-NO-INSTANCE（决策 60），而不是未定义函数。
 *  P0（M2）：多文件收集；MethodEntry 记录所属模块（字典名模块前缀用）。 */
internal fun Checker.checkImpls() = checkImplsFrom(listOf(file))

internal fun Checker.checkImplsFrom(files: List<FileAst>) {
    for (fa in files) {
        for (e in fa.entries) {
            if (e !is DeclEntry) continue
            val impl = e.decl as? ImplDecl ?: continue
            val cls = syms.classes[impl.trait.name]
            if (cls == null) {
                d.error("E-UNBOUND-NAME", impl.pos, "impl 型类 ${impl.trait.render()} 未声明")
                continue
            }
            // 登记型类的候选方法名（即使尚无实例）；实例匹配仍只认 impl 里的条目
            cls.members.forEach { m -> syms.traitMethods.add(m.name) }
            // 形参绑定表：class 类型形参 → impl.self 的实参。
            // 普通型类（Show[T] ↔ impl for Int）按旧逻辑 T↔Int（self.args 空则 self 本体）。
            // HKT（《高阶类型.md》HKT-S6，方案 A）：kind 形参 F[_] ↔ `impl for List[a]` 时
            // F 应绑到**构造子名** List（零实参），而非占位变量 a（self.args 是隐式占位，HKT-S6）。
            val clsArity = tpArityOf(cls)
            val sub: Map<String, Type> = tpNamesOf(cls).mapIndexed { i, pname ->
                val ar = clsArity[pname] ?: 0
                if (ar > 0) pname to (when (val s = impl.self) { is NamedType -> NamedType(s.name); else -> s })
                else pname to (impl.self.args.getOrNull(i) ?: impl.self)
            }.toMap()
            val classMethods = cls.members.associateBy { it.name }
            val implNames = impl.members.map { it.name }.toSet()
            for ((mname, msig) in classMethods) {
                if (mname !in implNames) {
                    d.error("E-IMPL-INCOMPLETE", impl.pos, "impl ${impl.trait.render()} for ${impl.self.render()} 缺方法 $mname")
                    continue
                }
                val mfn = impl.members.first { it.name == mname }
                if (!signatureMatch(msig, mfn, sub))
                    d.error("E-IMPL-MISMATCH", mfn.pos, "方法 $mname 签名与类声明不一致")
            }
            for (mfn in impl.members) {
                if (mfn.name !in classMethods && "new" !in mfn.annotations)
                    d.error("E-IMPL-EXTRA", mfn.pos, "方法 ${mfn.name} 不属于类 ${cls.name}，需标 @new")
                syms.methods.getOrPut(mfn.name) { ArrayList() }
                    .add(MethodEntry(impl.self, mfn, mfn.annotations, impl.trait.name, modulePath))
            }
            // P6（决策 82）：登记「型类 → impl 条目」（约束解析用；含声明模块供跨模块可见性过滤）
            syms.implsByTrait.getOrPut(cls.name) { ArrayList() }
                .add(ImplEntry(cls.name, impl.self, impl.trait.name, modulePath))
        }
    }
}

/** 字典名（codegen 用）：dict_<模块前缀>_<型类>_<自类型>（P0 文档钉死单下划线分隔），非字母数字统一压成下划线。
 *  无模块前缀（单文件/根模块）时保持旧名 dict_<Trait>_<Type>，向后兼容既有产物。 */
internal fun dictNameOf(trait: String, self: Type, modulePath: String = ""): String {
    val p = mangleModule(modulePath)
    // HKT-D1（《高阶类型.md》§6.1）：kind 型类 self 是构造子应用占位（`List[a]` 的 a 为小写裸名）时，
    // 字典名只取**构造子名**（dict_Functor_List），不含 `[a]`（列表/可选同型类区分保持）。
    val selfName = if (self is NamedType && self.args.any { it is NamedType && isKindPlaceholder(it) }) self.name
                   else self.render()
    return if (p.isEmpty()) "dict_${trait}_${sanitize(selfName)}"
           else "dict_${p}_${trait}_${sanitize(selfName)}"
}

internal fun sanitize(s: String): String = s.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** 型类字典解析（决策 60，T5）：按方法名 + 实参类型在 impl 表中找唯一匹配实例。
 *  v1 的 Int/Nat 等数值类型经 TypeInfer.join 提升，故用 typeLooseEq 做宽松匹配（Nat↔Int 放行）。
 *  零匹配 → E-NO-INSTANCE；多匹配 → E-AMBIGUOUS-INSTANCE。返回 null 表示已报错。
 *  P0（M6，impl 也要挂载）：visible 非空时按可见路径集合跨模块收集 impl；
 *  单文件模式（visible=null）保持旧行为：只看本模块 impl 表。
 *  多父挂载天然去重：同一 impl 只在其所属模块出现一次，visible 是集合。 */
internal fun Checker.resolveDict(name: String, argT: Type, visible: Set<String>? = null): DictResolution? {
    val entries = if (visible == null) syms.methods[name]
        else visible.mapNotNull { allSymbols[it]?.methods?.get(name) }.flatten().toMutableList()
    val raw = entries ?: emptyList()
    // P6（决策 82）：合成目标（如 lambda 首检的未定型参数）不参与实例匹配——
    // 无任何 impl 条目时仍报 E-NO-INSTANCE（保持既有诊断，M6：不挂载即无实例）；
    // 有条目时静默放行（调用方返回 synthetic，二次检查带真实类型重查，多实例不会误报歧义）。
    if (argT.isSynthetic()) {
        if (raw.isEmpty()) {
            d.error("E-NO-INSTANCE", "", "方法 $name 对类型 ${argT.render()} 无 impl 实例")
            return null
        }
        return null
    }
    // HKT（《高阶类型.md》HKT-S6）：self 是构造子应用（`List[a]`，占位 a 为小写裸名）时，
    // 占位与任意同位置实参通配匹配（typeLooseEq 逐实参比较会让 a vs Nat 失败）。
    val cands = raw.filter { doesSelfMatch(it.self, argT) }
    return when {
        cands.isEmpty() -> { d.error("E-NO-INSTANCE", "", "方法 $name 对类型 ${argT.render()} 无 impl 实例"); null }
        cands.size > 1 -> { d.error("E-AMBIGUOUS-INSTANCE", "", "方法 $name 对类型 ${argT.render()} 有 ${cands.size} 个实例"); null }
        else -> {
            // 字典名以 **impl 声明的自类型** 为准（codegen 按它生成 const），
            // 实参类型可能因数值提升而不同（如 Nat→Int），不能拿它拼名字。
            DictResolution(cands.first(), dictNameOf(cands.first().trait, cands.first().self, cands.first().modulePath))
        }
    }
}

/** P6（决策 82）：约束解析 `T: Trait` → 具体类型 t 的字典名（I-22：与命题参数同构、复用可见性过滤）。
 *  零匹配 → E-NO-INSTANCE；多匹配 → E-AMBIGUOUS-INSTANCE。返回 null 表示已报错。 */
internal fun Checker.resolveConstraint(trait: String, t: Type, visible: Set<String>?): String? {
    val entries = if (visible == null) syms.implsByTrait[trait].orEmpty()
        else visible.mapNotNull { allSymbols[it]?.implsByTrait?.get(trait) }.flatten()
    val cands = entries.filter { typeLooseEq(it.self, t) }
    return when {
        cands.isEmpty() -> { d.error("E-NO-INSTANCE", "", "约束 $trait 对类型 ${t.render()} 无 impl 实例"); null }
        cands.size > 1 -> { d.error("E-AMBIGUOUS-INSTANCE", "", "约束 $trait 对类型 ${t.render()} 有 ${cands.size} 个实例"); null }
        else -> dictNameOf(cands.first().traitName, cands.first().self, cands.first().modulePath)
    }
}

internal fun Checker.signatureMatch(sig: FunDecl, impl: FunDecl, sub: Map<String, Type>): Boolean {
    if (sig.params.size != impl.params.size) return false
    val sret = sig.retType ?: return impl.retType == null
    val iret = impl.retType ?: return false
    if (!typeEq(kindExpand(sret, sub), iret)) return false
    return sig.params.zip(impl.params).all { typeEq(kindExpand(it.first.type, sub), it.second.type) }
}

/** HKT（《高阶类型.md》HKT-S6，方案 A）：把类签名里的 kind 形参应用展开为构造子应用——
 *  `F[B]`（arity>0 的形参在 sub 里绑到构造子名 List）→ `List[B]`；普通形参仍走 substT-substT。 */
private fun kindExpand(t: Type, sub: Map<String, Type>): Type = when (t) {
    is NamedType ->
        if (t.args.isNotEmpty()) {
            // 形参是 kind 形参（sub 里有绑定且是零实参构造子名）→ 应用展开为构造子应用
            val binding = sub[t.name]
            if (binding is NamedType && binding.args.isEmpty())
                NamedType(binding.name, t.args.map { kindExpand(it, sub) })
            else NamedType(t.name, t.args.map { kindExpand(it, sub).substTRec(sub) })
        } else t.substT(sub)
    is FunType -> FunType(t.params.map { kindExpand(it, sub) }, kindExpand(t.ret, sub), t.purity)
    is TupleType -> TupleType(t.items.map { kindExpand(it, sub) })
    else -> t
}

/** 递归 substT（NamedType.substT 只替换零实参形参；kindExpand 已处理 kind 应用，此处补普通形参深替换） */
private fun Type.substTRec(sub: Map<String, Type>): Type = when (this) {
    is NamedType -> if (name in sub && args.isEmpty()) sub.getValue(name) else NamedType(name, args.map { it.substTRec(sub) })
    is FunType -> FunType(params.map { it.substTRec(sub) }, ret.substTRec(sub), purity)
    is TupleType -> TupleType(items.map { it.substTRec(sub) })
    else -> this
}

internal fun StructDecl.typeArgsSub(t: Type): Map<String, Type> {
    val tps = theory.filterIsInstance<TypeParam>().map { it.name }
    return tps.zip(t.args).toMap()
}

/** HKT（《高阶类型.md》HKT-S6，方案 A）：impl self 是否匹配实参类型。
 *  普通 self（`Show[Int]` for Int）走 typeLooseEq；self 是**构造子应用占位**（`List[a]` 的 a 为小写裸名）
 *  时按 kind 通配——同构造子同 arity 即匹配，占位变量接受任何同位置实参（含嵌套，如 `List[a]` vs `List[Nat]`）。 */
private fun Checker.doesSelfMatch(self: Type, argT: Type): Boolean {
    if (typeLooseEq(self, argT)) return true
    return kindWildcardMatch(self, argT)
}

private fun kindWildcardMatch(self: Type, argT: Type): Boolean = when {
    self is NamedType && argT is NamedType && self.name == argT.name && self.args.size == argT.args.size ->
        self.args.zip(argT.args).all { (s, a) -> kindWildcardMatch(s, a) || (s is NamedType && isKindPlaceholder(s)) }
    else -> false
}

/** 占位变量判据（HKT-S6）：零实参、名字首字母小写、未声明为类型别名/基类型——即 `List[a]` 里的 a */
private fun isKindPlaceholder(t: NamedType): Boolean =
    t.name.firstOrNull()?.isLowerCase() == true && t.args.isEmpty()

