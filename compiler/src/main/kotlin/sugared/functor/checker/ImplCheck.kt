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
            // 无类型类 impl（`impl T { }`，承担重载职责）已在 Parser 阶段脱糖为自由函数声明，此处防御性跳过
            val tr = impl.trait ?: continue
            val cls = syms.classes[tr.name]
            if (cls == null) {
                d.error("E-UNBOUND-NAME", impl.pos, "impl 类型类 ${tr.render()} 未声明")
                continue
            }
            // 登记类型类的候选方法名（即使尚无实例）；实例匹配仍只认 impl 里的条目
            cls.members.forEach { m -> syms.traitMethods.add(m.name) }
            // 形参绑定表：class 类型形参 → impl.self 的实参。
            // 普通类型类（Show[T] ↔ impl for Int）按旧逻辑 T↔Int（self.args 空则 self 本体）。
            // HKT（《高阶类型.md》HKT-S6，方案 A）：kind 形参 F[_] ↔ `impl for List[a]` 时
            // F 应绑到**构造子名** List（零实参），而非占位变量 a（self.args 是隐式占位，HKT-S6）。
            val clsArity = tpArityOf(cls)
            val sub: Map<String, Type> = tpNamesOf(cls).mapIndexed { i, pname ->
                val ar = clsArity[pname] ?: 0
                // HKT 简写（impl Functor[Result[_, E]]）：kind 形参绑定到**完整 self**（含槽位），
                // 由 kindExpand 按槽位做部分应用（F[X] → Result[X, E]）；零实参本体（impl Functor[List] → List[a]）同路。
                if (ar > 0) pname to impl.self
                else pname to (impl.self.args.getOrNull(i) ?: impl.self)
            }.toMap()
            // v3.0 特性 2：`impl C for T by field` 委托展开——转发字段方法补齐缺省方法
            var expanded = impl.members
            if (impl.delegate != null) {
                expanded = expandDelegate(impl, cls)
                if (expanded === impl.members && impl.members.isEmpty()) continue   // 委托字段无效（已报 E-BY-UNBOUND）不再级联
            }
            val classMethods = cls.members.associateBy { it.name }
            val implNames = expanded.map { it.name }.toMutableSet()
            for ((mname, msig) in classMethods) {
                if (mname !in implNames) {
                    // v3.0 特性 4：缺方法且类声明该方法有默认实现（body 非空）→ 用默认实现补齐，不报 E-IMPL-INCOMPLETE
                    val dft = classMethods[mname]
                    if (dft?.body != null) {
                        expanded = expanded + dft
                        implNames += mname
                        continue
                    }
                    d.error("E-IMPL-INCOMPLETE", impl.pos, "impl ${tr.render()} for ${impl.self.render()} 少了方法 `$mname`（类型类声明了它）. 补齐实现，且签名要一致")
                    continue
                }
                val mfn = expanded.first { it.name == mname }
                if (!signatureMatch(msig, mfn, sub))
                    d.error("E-IMPL-MISMATCH", mfn.pos, "方法 $mname 签名与类声明不一致")
            }
            for (mfn in expanded) {
                if (mfn.name !in classMethods && "new" !in mfn.annotations)
                    d.error("E-IMPL-EXTRA", mfn.pos, "方法 ${mfn.name} 不属于类 ${cls.name}，需标 @new")
                syms.methods.getOrPut(mfn.name) { ArrayList() }
                    .add(MethodEntry(impl.self, mfn, mfn.annotations, tr.name, modulePath))
            }
            // P6（决策 82）：登记「类型类 → impl 条目」（约束解析用；含声明模块供跨模块可见性过滤）
            syms.implsByTrait.getOrPut(cls.name) { ArrayList() }
                .add(ImplEntry(cls.name, impl.self, tr.name, modulePath))
            // v3.0 特性 2/4：委托展开或默认实现补齐后的成员集持久化，供 codegen 编码字典（genDecl 取 delegatedImpls ?: members）
            if (expanded !== impl.members) syms.delegatedImpls[impl] = expanded
        }
    }
}

/** 字典名（codegen 用）：dict_<模块前缀>_<类型类>_<自类型>（P0 文档钉死单下划线分隔），非字母数字统一压成下划线。
 *  无模块前缀（单文件/根模块）时保持旧名 dict_<Trait>_<Type>，向后兼容既有产物。 */
internal fun dictNameOf(trait: String, self: Type, modulePath: String = ""): String {
    val p = mangleModule(modulePath)
    // HKT-D1（《高阶类型.md》§6.1）+ 标准库简写：kind 类型类 self 是构造子应用占位（`List[a]` 的 a、
    // `Result[_, E]` 的 _ / E）时，字典名只取**构造子名**（dict_Functor_List / dict_Functor_Result）。
    // 判据为语法级（code 生成期无符号表）：_ / 单个大写字母（类型参数约定，根文档）/ 小写裸名。
    val selfName = if (self is NamedType && self.args.any { it is NamedType && isPlaceholderName(it.name) }) self.name
                   else self.render()
    return if (p.isEmpty()) "dict_${trait}_${sanitize(selfName)}"
           else "dict_${p}_${trait}_${sanitize(selfName)}"
}

internal fun sanitize(s: String): String = s.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

/** 类型类字典解析（决策 60，T5）：按方法名 + 实参类型在 impl 表中找唯一匹配实例。
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
            d.error("E-NO-INSTANCE", "", "类型 ${argT.render()} 没有实现 `$name()`. 写一个 `impl <类型类> for ${argT.render()} { … }` 来实现它")
            return null
        }
        return null
    }
    // HKT（《高阶类型.md》HKT-S6）：self 是构造子应用（`List[a]`，占位 a 为小写裸名）时，
    // 占位与任意同位置实参通配匹配（typeLooseEq 逐实参比较会让 a vs Nat 失败）。
    val cands = raw.filter { doesSelfMatch(it.self, argT) }
    return when {
        cands.isEmpty() -> { d.error("E-NO-INSTANCE", "", "类型 ${argT.render()} 没有实现 `$name()`. 写一个 `impl <类型类> for ${argT.render()} { … }` 来实现它"); null }
        // v2.1（限制解开）：多实例时仅当存在父子类型类关系才覆盖——最具体（不被更具体者覆盖）者胜出；
        // 无关实例（无子类型类关系）仍报 E-AMBIGUOUS-INSTANCE。
        cands.size > 1 -> {
            val related = cands.any { a -> cands.any { b -> a !== b && (isSubTrait(a.trait, b.trait) || isSubTrait(b.trait, a.trait)) } }
            val winner = if (related) cands.firstOrNull { a -> cands.none { b -> a !== b && isSubTrait(b.trait, a.trait) } } else null
            if (winner != null)
                DictResolution(winner, dictNameOf(winner.trait, winner.self, winner.modulePath))
            else { d.error("E-AMBIGUOUS-INSTANCE", "", "类型 ${argT.render()} 对 `$name` 有 ${cands.size} 个实现，编译器无法确定用哪个. 让它们之间存在父子类型类关系以分派"); null }
        }
        else -> {
            // 字典名以 **impl 声明的自类型** 为准（codegen 按它生成 const），
            // 实参类型可能因数值提升而不同（如 Nat→Int），不能拿它拼名字。
            DictResolution(cands.first(), dictNameOf(cands.first().trait, cands.first().self, cands.first().modulePath))
        }
    }
}

/** P6（决策 82）：约束解析 `T: Trait` → 具体类型 t 的字典名（I-22：与命题参数同构、复用可见性过滤）。
 *  零匹配 → E-NO-INSTANCE；多匹配 → E-AMBIGUOUS-INSTANCE。返回 null 表示已报错。
 *  HKT（HKT-D2）：匹配走 doesSelfMatch——约束 t 若是构造子名（`[F[_]: Functor]` 解出 List），
 *  `List[a]` 占位 self 通配命中（typeLooseEq 逐实参比较会让 a vs 空失败）。 */
internal fun Checker.resolveConstraint(trait: String, t: Type, visible: Set<String>?): String? {
    // v2.1（对齐 Rust）/v3.0 特性 4：自动类型类（当前 Any）对所有结构体/枚举/基本类型**自动实例化**——恒满足、零方法无字典；
    // Nothing 是底类型——任何约束对它都跳过字典（发散值不会真正执行调用）。
    if (trait in autoTraits) return null
    if (t.isNothing()) return null
    val entries = if (visible == null) syms.implsByTrait[trait].orEmpty()
        else visible.mapNotNull { allSymbols[it]?.implsByTrait?.get(trait) }.flatten()
    val cands = entries.filter { doesSelfMatch(it.self, t) }
    return when {
        cands.isEmpty() -> { d.error("E-NO-INSTANCE", "", "类型 ${t.render()} 没有实现约束 `$trait`. 写一个 `impl $trait for ${t.render()} { … }`"); null }
        // v2.1（限制解开）：仅父子类型类关系时子类实例优先；无关多实例仍报歧义
        cands.size > 1 -> {
            val related = cands.any { a -> cands.any { b -> a !== b && (isSubTrait(a.traitName, b.traitName) || isSubTrait(b.traitName, a.traitName)) } }
            val winner = if (related) cands.firstOrNull { a -> cands.none { b -> a !== b && isSubTrait(b.traitName, a.traitName) } } else null
            if (winner != null) dictNameOf(winner.traitName, winner.self, winner.modulePath)
            else { d.error("E-AMBIGUOUS-INSTANCE", "", "约束 $trait 对类型 ${t.render()} 有 ${cands.size} 个实例"); null }
        }
        else -> dictNameOf(cands.first().traitName, cands.first().self, cands.first().modulePath)
    }
}

/** v2.1（限制解开）：子类型类覆盖判定——sub 是否是 sup 的（传递）子类型类（sup ∈ sub 的父链）。
 *  父子类型类对同一类型均实现时，子类实例优先（Rust 子特质语义）。 */
internal fun Checker.isSubTrait(sub: String, sup: String): Boolean {
    if (sub == sup) return false
    val seen = HashSet<String>()
    var frontier = syms.clsParents[sub].orEmpty()
    while (frontier.isNotEmpty()) {
        val next = ArrayList<Type>()
        for (p in frontier) {
            val pn = (p as? NamedType)?.name ?: continue
            if (pn == sup) return true
            if (seen.add(pn)) next += syms.clsParents[pn].orEmpty()
        }
        frontier = next
    }
    return false
}

internal fun Checker.signatureMatch(sig: FunDecl, impl: FunDecl, sub: Map<String, Type>): Boolean {
    if (sig.params.size != impl.params.size) return false
    val sret = sig.retType ?: return impl.retType == null
    val iret = impl.retType ?: return false
    if (!typeEq(kindExpand(sret, sub), iret)) return false
    return sig.params.zip(impl.params).all { typeEq(kindExpand(it.first.type, sub), it.second.type) }
}

/** HKT（《高阶类型.md》HKT-S6 方案 A + 标准库简写）：把类签名里的 kind 形参应用展开为构造子应用。
 *  绑定（impl self）三种形态：
 *  - 部分应用（简写 `Result[_, E]`）：槽位 = `_`（按序对应 F 的应用位） → `F[X]` → `Result[X, E]`（E 保留）；
 *  - 经典小写占位（`impl Functor for List[a]`）：槽位 = a → `F[X]` → `List[X]`；
 *  - 无槽位（self 退化为头名）→ 旧径：实参逐个展开。普通形参仍走 substT。 */
private fun Checker.kindExpand(t: Type, sub: Map<String, Type>): Type = when (t) {
    is NamedType ->
        if (t.args.isNotEmpty()) {
            val binding = sub[t.name]
            if (binding is NamedType) {
                // 槽位（F 的应用位）：`_`（简写，Map[K, _] 的 _）或经典小写占位（List[a] 的 a）。
                // 用户显式命名的大写变量（K/E）是**保留绑定**（成员可引用），不是应用槽。
                val slots = binding.args.map { it is NamedType && isSlotOf(it) }
                if (slots.any { it }) {
                    val xs = t.args.iterator()
                    NamedType(binding.name, binding.args.mapIndexed { i, arg ->
                        if (i < slots.size && slots[i] && xs.hasNext()) kindExpand(xs.next(), sub) else arg
                    })
                } else NamedType(binding.name, t.args.map { kindExpand(it, sub) })
            } else NamedType(t.name, t.args.map { kindExpand(it, sub).substTRec(sub) })
        } else t.substT(sub)
    is FunType -> FunType(t.params.map { kindExpand(it, sub) }, kindExpand(t.ret, sub), t.purity, context = t.context)
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

/** v2.1 子类型类强制（对齐 Rust 子特质）：`impl Sub for X` 时，父类型类 Super 必须已实现于 X。
 *  在全部 impl 收集完成后调用（父类 impl 可能声明在后）；违反 → E-NO-INSTANCE 并提示先实现父类。 */
internal fun Checker.checkSubTraitParents() {
    for ((name, entries) in syms.implsByTrait) {
        val cls = syms.classes[name] ?: continue
        for (parent in cls.parents) {
            val pName = syms.expand(parent).name
            val parentImpls = syms.implsByTrait[pName].orEmpty()
            for (entry in entries)
                if (parentImpls.none { typeLooseEq(it.self, entry.self) })
                    d.error("E-NO-INSTANCE", "", "实现 `${entry.traitName}` 前，必须先实现它的父类型类 `${pName}`（因为 `${entry.traitName} : ${pName}`）. 先写 `impl ${pName} for ${entry.self.render()}`")
        }
    }
}

/** HKT（《高阶类型.md》HKT-S6，方案 A）：impl self 是否匹配实参类型。
 *  普通 self（`Show[Int]` for Int）走 typeLooseEq；self 是**构造子应用占位**（`List[a]` 的 a 为小写裸名）
 *  时按 kind 通配——同构造子同 arity 即匹配，占位变量接受任何同位置实参（含嵌套，如 `List[a]` vs `List[Nat]`）。 */
internal fun Checker.doesSelfMatch(self: Type, argT: Type): Boolean {
    // root doc 可变视图：接收者 @mut T 与 impl.self T 匹配同一结构
    val eff = if (argT is MutType) argT.inner else argT
    if (typeLooseEq(self, eff)) return true
    return kindWildcardMatch(self, eff)
}

/** 语法级占位名（dictNameOf / codegen 等无符号表场景用）：`_`、单大写字母（类型参数约定）、小写裸名 */
internal fun isPlaceholderName(name: String): Boolean =
    name == "_" || (name.length == 1 && name[0].isUpperCase()) || name.firstOrNull()?.isLowerCase() == true

/** kind 应用槽判据（kindExpand）：`_`（简写形态）或经典小写占位（未声明类型的零实参名）。
 *  大写用户变量（Result[_, E] 的 E、Map[K, _] 的 K）是保留绑定而非应用槽。 */
private fun Checker.isSlotOf(t: NamedType): Boolean =
    t.args.isEmpty() && (t.name == "_" || (t.name.firstOrNull()?.isLowerCase() == true && isTypePlaceholder(t)))

private fun Checker.kindWildcardMatch(self: Type, argT: Type): Boolean = when {
    // v3.0 第三批 4.3：Tuple 类型族 impl——`impl Tuple for (a,b)` 的 self 元素占位匹配具体元组（逐项容器匹配）
    self is TupleType && argT is TupleType ->
        self.items.size == argT.items.size && self.items.zip(argT.items).all { (s, a) -> kindWildcardMatch(s, a) || (s is NamedType && isTypePlaceholder(s)) }
    // 构造子本体通配（HKT-D2）：约束/合一解出的裸构造子名 `List`（零实参）匹配任何同类型类占位
    // 应用 self `List[a]`（占位数任意）——字典 self 是构造子，不是具体类型。size 不等则归此分支。
    self is NamedType && argT is NamedType && self.args.isNotEmpty() && argT.args.isEmpty() &&
        self.name == argT.name && self.args.all { it is NamedType && isTypePlaceholder(it) } -> true
    self is NamedType && argT is NamedType && self.name == argT.name && self.args.size == argT.args.size ->
        self.args.zip(argT.args).all { (s, a) -> kindWildcardMatch(s, a) || (s is NamedType && isTypePlaceholder(s)) }
    else -> false
}

/** 占位变量判据（HKT-S6 + 标准库简写）：零实参且**未声明为具体类型**（本模块符号表与跨模块可见集合都查不到）。
 *  覆盖经典小写占位（`List[a]` 的 a）、简写槽位 `_`、用户显式命名变量（`Result[_, E]` 的 E、`Map[K, _]` 的 K）。
 *  用符号表而非「首字母小写」判据，使简写形态的占位同样通配匹配与字典命名。 */
private fun Checker.isTypePlaceholder(t: NamedType): Boolean =
    t.args.isEmpty() && syms.baseOf(t) == null && !typeVisible(t.name)

/** impl self 的 kind 占位变量集合（HKT-S6；经典与简写同构）：self 里所有零实参未声明名，按出现序去重。
 *  供成员签名的类型解析（checkFun 的 tps 并入）与 self 声明检查（Symbols 的 selfTps）。 */
internal fun Checker.selfPlaceholders(t: Type): List<String> {
    val out = LinkedHashSet<String>()
    fun walk(tp: Type) {
        when (tp) {
            is NamedType ->
                if (tp.args.isEmpty() && isTypePlaceholder(tp)) out.add(tp.name)
                else tp.args.forEach { walk(it) }
            is TupleType -> tp.items.forEach { walk(it) }
            is FunType -> { tp.params.forEach { walk(it) }; walk(tp.ret) }
            is UnionType -> tp.variants.forEach { walk(it) }
            is MutType -> walk(tp.inner)
            is AtomicType -> walk(tp.inner)
            else -> {}
        }
    }
    walk(t)
    return out.toList()
}

/** v3.0 特性 2：impl C for T by field 委托展开——为缺的方法生成转发 m(args) = field.m(args)。
 *  字段不存在（或 self 非结构体）报 E-BY-UNBOUND；E-BY-TYPE 为可选检查，v1 不做。
 *  转发方法签名照抄类声明（参数/返回一致），codegen 按普通方法调用生成。 */
internal fun Checker.expandDelegate(impl: ImplDecl, cls: ClassDecl): List<FunDecl> {
    val byName = impl.delegate!!
    val struct = (impl.self as? NamedType)?.let { st -> syms.structs[st.name] }
    val fields = struct?.fields ?: emptyList()
    if (fields.none { it.name == byName }) {
        d.error("E-BY-UNBOUND", impl.pos, "impl " + (impl.trait?.render() ?: "") + " for " + impl.self.render() + " 委托字段 " + byName + " 不存在（" + (struct?.name ?: impl.self.render()) + " 非结构体或无此字段）")
        return impl.members
    }
    val have = impl.members.map { it.name }.toSet()
    val forwards = cls.members.filter { it.name !in have }.map { msig ->
        val args = msig.params.map { NameRef(it.name) as Expr }
        FunDecl(
            name = msig.name,
            annotations = emptyList(),
            theory = msig.theory,
            params = msig.params.map { it.copy() },
            preps = msig.preps,
            retType = msig.retType,
            posts = msig.posts,
            body = CallExpr(FieldExpr(NameRef(byName), msig.name), args),
            constraints = msig.constraints,
            pos = impl.pos,
        )
    }
    return impl.members + forwards
}


