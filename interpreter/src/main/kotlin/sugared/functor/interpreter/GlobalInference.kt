/**
 * 全局类型推导（idea doc/全局类型推导.md，V2）——**脚本模式专属**，由 Interpreter(globalInference = true) 开启。
 *
 * 定位：用户省略标注时填一个「能过检查、符合所想」的标注；不追求 principal type。
 * 优先级（doc §三）：显式标注 > 构造点 > 使用点 > 默认。
 *
 * V2 范围（在 V1 之上）：
 * - 泛型化（doc §六）：参数仅有宽约束（算术使用 → Num）而无构造点 → [T: Num]（共享一个 T）；
 *   无约束参数 → [T: Any]；struct 无构造点字段 → 结构体泛型化 T: Any
 * - 使用点成员约束：x.length() → 参数填 Str（doc §四）
 * - SCC（doc §七）：Tarjan 分组、被调者先解；递归函数返回类型走「假设签名-验证」3 轮迭代
 *   （自递归/互递归均支持；收敛不了不报错，交回 checker）
 * - 上下文自动填充（doc §十）：前件从调用点反推（命题参数 @q 一并提升，fixpoint 至稳定）；
 *   后件取所有 return 处可证命题的交集（∵ 注入参与）；@theorem 不参与
 * - 冲突诊断 E-TYPE-CONFLICT（doc §十一）：多个构造点/使用点约束不一致 → 报错并指出两个来源
 * - 停机失败降级 D-AUTO-IMPURE（doc §7.5）：自递归无递减实参 → 自动 @impure + 补充级提示
 *
 * V1 基础（保留）：构造点填充参数/字段类型、效应固定点（非纯调用冒泡）、
 * 可变性赋值链（@mut var / @mut 字段 / @mut struct）、显式标注最高优先（只填不盖）。
 *
 * 不变量：显式标注最高优先（非 InferMarker 的类型与已有注解/前后件一律保留）；产物仍整体过 Checker。
 */
package sugared.functor.interpreter

import sugared.functor.ast.*
import sugared.functor.checker.builtinimpure

/** 推导过程产生的诊断：E-TYPE-CONFLICT（错误，解释器中止）与 D-AUTO-IMPURE（补充级提示）。 */
data class InferNote(val severity: String, val code: String, val pos: String, val message: String)

object GlobalInference {

    fun infer(
        ast: FileAst,
        stdlibEntries: List<Entry>,
        hostRets: Map<String, Type> = emptyMap(),
        notes: MutableList<InferNote> = mutableListOf(),
    ): FileAst {
        val userFuns = LinkedHashMap<String, FunDecl>()
        val userStructs = LinkedHashMap<String, StructDecl>()
        val userClasses = LinkedHashMap<String, ClassDecl>()
        val implMembers = LinkedHashMap<String, LinkedHashMap<String, FunDecl>>()
        for (e in ast.entries) {
            when (val d = (e as? DeclEntry)?.decl) {
                is FunDecl -> userFuns[d.name] = d
                is StructDecl -> userStructs[d.name] = d
                is ClassDecl -> userClasses[d.name] = d
                is ImplDecl -> {
                    val trait = (d.trait as? NamedType)?.name
                    if (trait != null) {
                        val map = implMembers.getOrPut(trait) { linkedMapOf() }
                        for (m in d.members) { map[m.name] = m; userFuns[m.name] = m }
                    } else for (m in d.members) userFuns[m.name] = m
                }
                else -> {}
            }
        }

        // A. 事实收集
        val facts = analyze(ast, userStructs)

        // B. 参数/字段类型（构造点 → 使用点 → 泛型化/默认），含冲突诊断
        val varGen = TypeVarGen()
        val paramTypes = LinkedHashMap<Pair<String, String>, Type>()
        val funTheory = LinkedHashMap<String, MutableList<TypeParam>>()
        for ((_, fn) in userFuns) solveFunParams(fn, facts, paramTypes, userStructs, varGen, funTheory, notes)
        val fieldTypes = LinkedHashMap<Pair<String, String>, Type>()
        val structTheory = LinkedHashMap<String, MutableList<TypeParam>>()
        for ((_, st) in userStructs) solveStructFields(st, facts, fieldTypes, userStructs, varGen, structTheory, notes)

        // C. 返回类型：Tarjan SCC（被调者先解）+ 递归 SCC 假设-验证 3 轮（doc §七）
        val retTypes = LinkedHashMap<String, Type>()
        solveReturnTypes(userFuns, facts, paramTypes, retTypes, hostRets, stdlibEntries, userStructs)

        // D. class 成员签名回填（doc §4.5：impl 已解出的签名回填到 class 声明）
        val classBackfill = buildClassBackfill(userClasses, implMembers, paramTypes, retTypes)

        // E. 效应（含停机失败 → D-AUTO-IMPURE，doc §7.5）
        val impureFuns = solveImpurity(userFuns, facts, notes)

        // F. 上下文：前件反推（fixpoint，doc §10.1）+ 后件交集（doc §10.2）——@theorem 不参与
        val prepLifts = LinkedHashMap<String, MutableSet<String>>()
        val propLifts = LinkedHashMap<String, MutableSet<String>>()
        solvePreps(userFuns, facts, stdlibEntries, prepLifts, propLifts)
        val postLifts = LinkedHashMap<String, Set<String>>()
        for ((name, fn) in userFuns) if (!isTheorem(fn)) postLifts[name] = postFacts(fn)

        // G. 可变性：赋值目标链 → @mut var / @mut 字段 / @mut struct（doc §八）
        val mutVars = LinkedHashSet<String>()
        val mutFields = LinkedHashSet<Pair<String, String>>()
        for (target in facts.assignTargets) walkTarget(target, facts, userStructs, mutVars, mutFields)

        // H. 重建
        val out = ArrayList<Entry>(ast.entries.size)
        for (e in ast.entries) {
            val old = (e as? DeclEntry)?.decl
            val newDecl: Decl? = when (old) {
                is FunDecl -> rebuildFun(old, paramTypes, retTypes, impureFuns, mutVars, funTheory, prepLifts, propLifts, postLifts)
                is StructDecl -> rebuildStruct(old, fieldTypes, mutFields, structTheory)
                is ClassDecl -> rebuildClass(old, classBackfill)
                is ImplDecl -> old.copy(members = old.members.map { rebuildFun(it, paramTypes, retTypes, impureFuns, mutVars, funTheory, prepLifts, propLifts, postLifts) })
                else -> null
            }
            if (newDecl != null && newDecl !== old) { out += DeclEntry(newDecl); continue }
            val se = e as? StmtEntry
            if (se != null) {
                val s2 = Rebuilder(mutVars).stmt(se.stmt)
                out += if (s2 !== se.stmt) StmtEntry(s2) else e
            } else out += e
        }
        return FileAst(out)
    }

    private fun isTheorem(fn: FunDecl): Boolean = "theorem" in fn.annotations

    // ==================== A. 事实 ====================

    private class Facts(
        val funCallSites: MutableMap<String, MutableList<CallSite>> = LinkedHashMap(),
        val ctorCallSites: MutableMap<String, MutableList<CallSite>> = LinkedHashMap(),
        val structCtorSites: MutableMap<String, MutableList<List<Pair<String, Expr>>>> = LinkedHashMap(),
        val varLitTypes: MutableMap<String, Type?> = LinkedHashMap(),
        val varStructs: MutableMap<String, String> = LinkedHashMap(),
        val assignTargets: MutableList<Expr> = ArrayList(),
        val bodyCalls: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
        val arithParams: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
        val memberUseParams: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
        val selfRecShrinking: MutableMap<String, Boolean> = LinkedHashMap(),
    )

    private class CallSite(val args: List<Expr>, val named: Map<String, Expr>, val pos: String)

    private fun analyze(ast: FileAst, userStructs: Map<String, StructDecl>): Facts {
        val facts = Facts()
        val c = Collector(facts, userStructs)
        for (e in ast.entries) {
            when (e) {
                is DeclEntry -> c.decl(e.decl)
                is StmtEntry -> c.stmt(e.stmt)
            }
        }
        return facts
    }

    private class Collector(private val facts: Facts, private val userStructs: Map<String, StructDecl>) {
        private val paramStack = ArrayDeque<Set<String>>()
        private val curFun = ArrayDeque<String>()

        fun decl(d: Decl) {
            when (d) {
                is FunDecl -> {
                    paramStack.addLast(d.params.map { it.name }.toSet())
                    curFun.addLast(d.name)
                    d.body?.let { expr(it) }
                    curFun.removeLast()
                    paramStack.removeLast()
                }
                is ImplDecl -> for (m in d.members) decl(m)
                is StructDecl -> for (f in d.fields) f.default?.let { expr(it) }
                else -> {}
            }
        }

        fun stmt(s: Stmt) {
            when (s) {
                is VarStmt -> { recordVar(s.name, s.value); expr(s.value) }
                is AssignStmt -> { facts.assignTargets += s.target; expr(s.value) }
                is ExprStmt -> expr(s.expr)
                is ReturnStmt -> s.expr?.let { expr(it) }
                is UncheckedStmt -> stmt(s.inner)
                is FunStmt -> decl(s.fn)
                else -> {}
            }
        }

        fun expr(e: Expr) {
            when (e) {
                is CallExpr -> {
                    val n = (e.callee as? NameRef)?.name
                    if (n != null) {
                        val fn = curFun.lastOrNull()
                        if (fn != null && paramStack.isNotEmpty()) {
                            facts.bodyCalls.getOrPut(fn) { linkedSetOf() } += n
                            if (n == fn) facts.selfRecShrinking[n] = e.args.any { it is BinExpr && it.op == "-" }
                        }
                        facts.funCallSites.getOrPut(n) { arrayListOf() } += CallSite(e.args, e.namedArgs, e.pos)
                        if (n in userStructs) facts.ctorCallSites.getOrPut(n) { arrayListOf() } += CallSite(e.args, e.namedArgs, e.pos)
                    }
                    // 使用点成员约束：x.length() → x: Str（方法糖：callee 是 FieldExpr（接收者.方法），doc §四）
                    val fld = e.callee as? FieldExpr
                    if (fld != null && fld.name == "length") recordMemberUse(fld.target)
                    if (n == "length") recordMemberUse(e.args.getOrNull(0))
                    expr(e.callee)
                    e.args.forEach { expr(it) }
                    e.namedArgs.values.forEach { expr(it) }
                }
                is StructCtorExpr -> {
                    facts.structCtorSites.getOrPut(e.struct) { arrayListOf() } += e.assigns
                    e.assigns.forEach { expr(it.second) }
                }
                is BinExpr -> { recordArithUse(e.left); recordArithUse(e.right); expr(e.left); expr(e.right) }
                is UniExpr -> expr(e.operand)
                is FieldExpr -> expr(e.target)
                is FunRefExpr -> expr(e.target)
                is InstExpr -> { expr(e.target); e.terms.forEach { expr(it) } }
                is SymbolCallExpr -> e.args.forEach { expr(it) }
                is TupleExpr -> e.items.forEach { expr(it) }
                is ByExpr -> { expr(e.target); e.props.forEach { expr(it) } }
                is VarExpr -> { recordVar(e.name, e.value); expr(e.value) }
                is LambdaExpr -> expr(e.body)
                is AnonFunExpr -> {
                    paramStack.addLast(e.params.map { it.name }.toSet())
                    curFun.addLast(e.name)
                    e.body?.let { expr(it) }
                    curFun.removeLast(); paramStack.removeLast()
                }
                is BlockExpr -> e.stmts.forEach { stmt(it) }
                is IfExpr -> { expr(e.cond); e.thenBlock.stmts.forEach { stmt(it) }; e.elseBlock?.stmts?.forEach { stmt(it) } }
                is WhenExpr -> {
                    e.subject?.let { expr(it) }
                    e.arms.forEach { arm -> arm.guard?.let { expr(it) }; arm.cond?.let { expr(it) }; expr(arm.body) }
                }
                is TaskExpr -> e.body.stmts.forEach { stmt(it) }
                is FlowExpr -> e.body.stmts.forEach { stmt(it) }
                is TypeTestExpr -> expr(e.target)
                is IsPatExpr -> expr(e.target)
                is QuantExpr -> expr(e.body)
                else -> {}
            }
        }

        private fun recordArithUse(e: Expr?) {
            val params = paramStack.lastOrNull() ?: return
            val fn = curFun.lastOrNull() ?: return
            val name = paramNameOf(e, params) ?: return
            facts.arithParams.getOrPut(fn) { linkedSetOf() } += name
        }

        private fun recordMemberUse(e: Expr?) {
            val params = paramStack.lastOrNull() ?: return
            val fn = curFun.lastOrNull() ?: return
            val name = paramNameOf(e, params) ?: return
            facts.memberUseParams.getOrPut(fn) { linkedSetOf() } += name
        }

        private fun paramNameOf(e: Expr?, params: Set<String>): String? = when (e) {
            is ParamRefExpr -> params.elementAtOrNull(e.index)
            is NameRef -> if (e.name in params) e.name else null
            else -> null
        }

        private fun literalType(e: Expr): Type? = when (e) {
            is IntLit -> NamedType("Nat")
            is FloatLit -> NamedType("Rat")
            is StrLit -> NamedType("Str")
            is NameRef -> when (e.name) {
                "true", "false" -> NamedType("Bool")
                "null" -> NamedType("Null")
                else -> facts.varLitTypes[e.name]
            }
            is CallExpr -> {
                val n = (e.callee as? NameRef)?.name
                if (n != null && n in userStructs) NamedType(n) else null
            }
            is StructCtorExpr -> NamedType(e.struct)
            else -> null
        }

        private fun recordVar(name: String, value: Expr) {
            val t = literalType(value)
            if (t != null) {
                val prev = facts.varLitTypes.putIfAbsent(name, t)
                if (prev != null && prev != t) facts.varLitTypes[name] = null
            }
            val st = ctorStructOf(value)
            if (st != null) facts.varStructs.putIfAbsent(name, st)
        }

        private fun ctorStructOf(e: Expr): String? = when (e) {
            is CallExpr -> {
                val n = (e.callee as? NameRef)?.name
                if (n != null && n in userStructs) n else null
            }
            is StructCtorExpr -> e.struct
            else -> null
        }
    }

    // ==================== B. 参数/字段求解（含泛型化与冲突） ====================

    /** 类型变量名生成：作用域内唯一（避开用户已写的 theory 参数名）。 */
    private class TypeVarGen {
        private val names = LinkedHashMap<String, String>()
        fun varOf(scope: String, existing: List<TheoryParam>): String = names.getOrPut(scope) {
            val taken = (existing.mapNotNull { (it as? TypeParam)?.name } + names.values).toMutableSet()
            var name = "T"
            var suffix = 0
            while (name in taken) { suffix++; name = "T" + suffix }
            name
        }
    }

    private fun solveFunParams(
        fn: FunDecl,
        facts: Facts,
        out: MutableMap<Pair<String, String>, Type>,
        userStructs: Map<String, StructDecl>,
        varGen: TypeVarGen,
        funTheory: MutableMap<String, MutableList<TypeParam>>,
        notes: MutableList<InferNote>,
    ) {
        val sites = facts.funCallSites[fn.name] ?: emptyList()
        val arith = facts.arithParams[fn.name] ?: emptySet()
        val memberUse = facts.memberUseParams[fn.name] ?: emptySet()
        for ((idx, p) in fn.params.withIndex()) {
            if (!p.type.isInferMarker()) continue
            val cands = ArrayList<Pair<Type, String>>()
            for (site in sites) {
                site.args.getOrNull(idx)?.let { addCandidate(cands, it, userStructs, site.pos) }
                site.named[p.name]?.let { addCandidate(cands, it, userStructs, site.pos) }
            }
            val types = cands.map { it.first }.toSet()
            when {
                types.size > 1 -> {
                    // 多构造点：函数参数**每次调用独立实例化**——无使用点 → 泛型化 [T: Any]；
                    // 与使用点约束不相容（算术混入非数值 / 成员约束混入非 Str）才是冲突（doc §4.2、§十一）
                    val mixed = (p.name in memberUse && types.any { nameOf(it) != "Str" }) ||
                        (p.name in arith && types.any { !isNumericName(nameOf(it)) })
                    if (mixed) {
                        val (t1, p1) = cands[0]
                        val other = cands.first { it.first != t1 }
                        notes += InferNote("ERROR", "E-TYPE-CONFLICT", posOf(p1, fn.pos),
                            "参数 " + fn.name + "." + p.name + " 的构造点与使用点冲突：" + nameOf(t1) + "（在 " + posOf(p1, fn.pos) + "）与 " + nameOf(other.first) + "（在 " + posOf(other.second, fn.pos) + "），而使用点另有约束。两个约束无法同时满足——检查是否一致，或显式标注参数类型")
                        out[fn.name to p.name] = NamedType("Any")
                    } else if (p.name in arith) {
                        val tv = varGen.varOf(fn.name + "#Num", fn.theory)
                        funTheory.getOrPut(fn.name) { arrayListOf() } += TypeParam(tv, "Num")
                        out[fn.name to p.name] = NamedType(tv)
                    } else if (p.name in memberUse) {
                        out[fn.name to p.name] = NamedType("Str")
                    } else {
                        val tv = varGen.varOf(fn.name + "#Any", fn.theory)
                        funTheory.getOrPut(fn.name) { arrayListOf() } += TypeParam(tv, "Any")
                        out[fn.name to p.name] = NamedType(tv)
                    }
                }
                types.size == 1 -> out[fn.name to p.name] = types.first()
                p.name in memberUse && p.name in arith -> {
                    notes += InferNote("ERROR", "E-TYPE-CONFLICT", fn.pos,
                        "参数 " + fn.name + "." + p.name + " 的使用点约束冲突（在 " + fn.pos + "）：算术使用（+ - * 比较等）要数值类型，.length() 要 Str。两个约束无法同时满足——检查使用是否一致，或显式标注参数类型（doc §7.4）")
                    out[fn.name to p.name] = NamedType("Any")
                }
                p.name in memberUse -> out[fn.name to p.name] = NamedType("Str")
                p.name in arith -> {
                    val tv = varGen.varOf(fn.name + "#Num", fn.theory)
                    funTheory.getOrPut(fn.name) { arrayListOf() } += TypeParam(tv, "Num")
                    out[fn.name to p.name] = NamedType(tv)
                }
                else -> {
                    val tv = varGen.varOf(fn.name + "#Any", fn.theory)
                    funTheory.getOrPut(fn.name) { arrayListOf() } += TypeParam(tv, "Any")
                    out[fn.name to p.name] = NamedType(tv)
                }
            }
        }
    }

    private fun solveStructFields(
        st: StructDecl,
        facts: Facts,
        out: MutableMap<Pair<String, String>, Type>,
        userStructs: Map<String, StructDecl>,
        varGen: TypeVarGen,
        structTheory: MutableMap<String, MutableList<TypeParam>>,
        notes: MutableList<InferNote>,
    ) {
        for ((idx, f) in st.fields.withIndex()) {
            if (!f.type.isInferMarker()) continue
            val cands = ArrayList<Pair<Type, String>>()
            for (site in facts.ctorCallSites[st.name] ?: emptyList())
                site.args.getOrNull(idx)?.let { addCandidate(cands, it, userStructs, site.pos) }
            for (assigns in facts.structCtorSites[st.name] ?: emptyList())
                assigns.firstOrNull { it.first == f.name }?.let { addCandidate(cands, it.second, userStructs, st.pos) }
            f.default?.let { addCandidate(cands, it, userStructs, st.pos) }
            val types = cands.map { it.first }.toSet()
            when {
                types.size > 1 -> {
                    val (t1, p1) = cands[0]
                    val other = cands.first { it.first != t1 }
                    notes += InferNote("ERROR", "E-TYPE-CONFLICT", posOf(p1, st.pos),
                        "字段 " + st.name + "." + f.name + " 的构造点类型冲突：" + nameOf(t1) + "（在 " + posOf(p1, st.pos) + "）与 " + nameOf(other.first) + "（在 " + posOf(other.second, st.pos) + "）。多个构造点必须给出同一个类型——检查构造处是否一致，或显式标注字段类型")
                    out[st.name to f.name] = NamedType("Any")
                }
                types.size == 1 -> out[st.name to f.name] = types.first()
                else -> {
                    val tv = varGen.varOf(st.name + "#" + f.name, st.theory)
                    structTheory.getOrPut(st.name) { arrayListOf() } += TypeParam(tv, "Any")
                    out[st.name to f.name] = NamedType(tv)
                }
            }
        }
    }

    private fun addCandidate(candidates: MutableList<Pair<Type, String>>, arg: Expr, userStructs: Map<String, StructDecl>, pos: String) {
        val t: Type? = when (arg) {
            is IntLit -> NamedType("Nat")
            is FloatLit -> NamedType("Rat")
            is StrLit -> NamedType("Str")
            is NameRef -> when (arg.name) {
                "true", "false" -> NamedType("Bool")
                "null" -> NamedType("Null")
                else -> null
            }
            is StructCtorExpr -> NamedType(arg.struct)
            is CallExpr -> {
                val n = (arg.callee as? NameRef)?.name
                if (n != null && n in userStructs) NamedType(n) else null
            }
            else -> null
        }
        if (t != null) candidates += t to pos
    }

    private fun nameOf(t: Type): String = (t as? NamedType)?.name ?: "?"

    private fun isNumericName(name: String): Boolean = name == "Nat" || name == "Int" || name == "Rat"

    /** 冲突来源的位置显示：CallExpr.pos 常为空（解析器未填），空则退回声明处。 */
    private fun posOf(pos: String, fallback: String): String = if (pos.isEmpty()) fallback else pos

    // ==================== C. 返回类型（SCC + 假设-验证） ====================

    private fun solveReturnTypes(
        userFuns: Map<String, FunDecl>,
        facts: Facts,
        paramTypes: Map<Pair<String, String>, Type>,
        retTypes: MutableMap<String, Type>,
        hostRets: Map<String, Type>,
        stdlibEntries: List<Entry>,
        userStructs: Map<String, StructDecl>,
    ) {
        val stdlibRets = LinkedHashMap<String, Type>()
        for (e in stdlibEntries) {
            val fn = (e as? DeclEntry)?.decl as? FunDecl ?: continue
            val r = fn.retType
            if (r != null) stdlibRets[fn.name] = r
        }
        val explicit = linkedMapOf<String, Type>()
        for ((name, fn) in userFuns) {
            val r = fn.retType
            if (r != null) explicit[name] = r
        }
        for (scc in tarjanSCC(userFuns.keys, facts.bodyCalls)) {
            val cyclic = scc.size > 1 || (scc.size == 1 && facts.bodyCalls[scc[0]]?.contains(scc[0]) == true)
            if (!cyclic) {
                val fn = userFuns[scc[0]] ?: continue
                if (fn.retType != null || isTheorem(fn) || fn.body == null) continue
                val eff = explicit + nonTypeVar(retTypes)
                val t = BodyTyper(fn, paramTypes, facts.varLitTypes, eff, hostRets, stdlibRets, userStructs, emptySet()).bodyType()
                if (t != null && !isVoidType(t)) retTypes[fn.name] = t
            } else {
                // 假设-验证：3 轮迭代直到约束不再变化（doc §7.2/7.3）
                val guesses = LinkedHashMap<String, Type?>()
                for (name in scc) guesses[name] = null
                for (round in 1..3) {
                    val eff = explicit + nonTypeVar(retTypes) + nonTypeVarNullable(guesses)
                    val next = LinkedHashMap<String, Type?>()
                    var stable = true
                    for (name in scc) {
                        val fn = userFuns[name] ?: continue
                        val t = if (fn.retType != null || isTheorem(fn) || fn.body == null) fn.retType
                            else BodyTyper(fn, paramTypes, facts.varLitTypes, eff, hostRets, stdlibRets, userStructs, scc.toSet()).bodyType()
                        if (t != guesses[name]) stable = false
                        next[name] = t
                    }
                    guesses.clear(); guesses.putAll(next)
                    if (stable) break
                }
                for ((name, t) in guesses) {
                    val fn = userFuns[name] ?: continue
                    if (t != null && fn.retType == null) retTypes[name] = t
                }
            }
        }
    }

    private fun nonTypeVar(m: Map<String, Type>): Map<String, Type> = m.filterValues { !isTypeVarForm(it) }
    private fun nonTypeVarNullable(m: Map<String, Type?>): Map<String, Type> =
        m.mapNotNull { (k, v) -> if (v != null && !isTypeVarForm(v)) k to v else null }.toMap()

    /** Tarjan SCC：弹出顺序 = 被调者（依赖）在前。 */
    private fun tarjanSCC(nodes: Set<String>, edges: Map<String, Set<String>>): List<List<String>> {
        val index = HashMap<String, Int>()
        val low = HashMap<String, Int>()
        val onStack = HashSet<String>()
        val stack = ArrayDeque<String>()
        val out = ArrayList<List<String>>()
        var seq = 0
        fun strong(v: String) {
            index[v] = seq; low[v] = seq; seq++
            stack.addLast(v); onStack += v
            for (w in edges[v].orEmpty().filter { it in nodes }) {
                if (w !in index) { strong(w); low[v] = minOf(low[v]!!, low[w]!!) }
                else if (w in onStack) low[v] = minOf(low[v]!!, index[w]!!)
            }
            if (low[v] == index[v]) {
                val comp = ArrayList<String>()
                do { val u = stack.removeLast(); onStack -= u; comp += u } while (u != v)
                out += comp
            }
        }
        for (n in nodes) if (n !in index) strong(n)
        return out
    }

    private fun isTypeVarForm(t: Type?): Boolean =
        t is NamedType && t.args.isEmpty() && t.name.length == 1 && t.name[0].isUpperCase()

    /** 返回类型不填的兜底情形：Null（无 return，checker 自给）与 Any（多半来自宿主函数的宽松签名，
     *  如 print：块体无 return 时填 Any 会让 checker 报缺 return）。 */
    private fun isVoidType(t: Type?): Boolean =
        t is NamedType && (t.name == "Null" || t.name == "Any") && t.args.isEmpty()

    /** 体类型近似求值：只填确信的类型（字面量/参数/算术/common-type/已知返回的调用）；未知则 null。 */
    private class BodyTyper(
        private val fn: FunDecl,
        private val paramTypes: Map<Pair<String, String>, Type>,
        private val varLitTypes: Map<String, Type?>,
        private val rets: Map<String, Type>,
        private val hostRets: Map<String, Type>,
        private val stdlibRets: Map<String, Type>,
        private val userStructs: Map<String, StructDecl>,
        private val scc: Set<String>,
    ) {
        private val paramNames: Set<String> = fn.params.map { it.name }.toSet()

        fun bodyType(): Type? = fn.body?.let { typeOf(it) }

        private fun typeOf(e: Expr): Type? = when (e) {
            is IntLit -> NamedType("Nat")
            is FloatLit -> NamedType("Rat")
            is StrLit -> NamedType("Str")
            is NameRef -> when (e.name) {
                "true", "false" -> NamedType("Bool")
                "null" -> NamedType("Null")
                in paramNames -> paramTypes[fn.name to e.name]
                else -> varLitTypes[e.name]
            }
            is UniExpr -> if (e.op == "-" && e.operand is IntLit) NamedType("Int") else typeOf(e.operand)
            is ParamRefExpr -> fn.params.getOrNull(e.index)?.name?.let { paramTypes[fn.name to it] }
            is BinExpr -> { val l = typeOf(e.left); val r = typeOf(e.right); if (l != null && r != null) binType(e.op, l, r) else null }
            is IfExpr -> commonArms(listOf(Pair(armEnd(e.thenBlock), e.thenBlock)) + (e.elseBlock?.let { listOf(Pair(armEnd(it), it)) } ?: emptyList()))
            is WhenExpr -> commonArms(e.arms.map { Pair(typeOf(it.body), it.body) })
            is CallExpr -> calleeRet(e)
            is StructCtorExpr -> NamedType(e.struct)
            is BlockExpr -> tailType(e.stmts)
            is LambdaExpr -> null
            is AnonFunExpr -> null
            else -> null
        }

        private fun armEnd(b: BlockExpr): Type? = tailType(b.stmts)

        private fun tailType(stmts: List<Stmt>): Type? {
            val last = stmts.lastOrNull() ?: return null
            return when (last) {
                is ReturnStmt -> last.expr?.let { typeOf(it) }
                is ExprStmt -> typeOf(last.expr)
                else -> null
            }
        }

        private fun calleeRet(e: CallExpr): Type? {
            val fld = e.callee as? FieldExpr
            if (fld != null && fld.name == "length") return NamedType("Nat")   // x.length(): Str → Nat
            val n = (e.callee as? NameRef)?.name ?: return null
            return when {
                n in userStructs -> NamedType(n)
                n == "length" -> NamedType("Nat")
                else -> rets[n] ?: hostRets[n] ?: stdlibRets[n]
            }
        }

        private fun binType(op: String, l: Type, r: Type): Type? {
            if (op in listOf("==", "!=", "<", "<=", ">", ">=", "&&", "||")) return NamedType("Bool")
            val ln = nameOf(l); val rn = nameOf(r)
            if (op == "+") {
                if (ln == "Str" || rn == "Str") return NamedType("Str")
                return numericJoin(l, r)
            }
            if (op in listOf("-", "*")) return numericJoin(l, r)
            return null   // 除法/区间等结果类型不直觉，不填（checker 自推）
        }

        /** 数值连接：参数是本函数的类型变量时结果沿用 T（让 [T: Num](n: T): T 按调用点实例化，
         *  与 checker 的 T15 行为一致）；两个具体类型取宽者（Nat < Int < Rat）。 */
        private fun numericJoin(l: Type, r: Type): Type {
            val rank = mapOf("Nat" to 0, "Int" to 1, "Rat" to 2)
            if (isTypeVarForm(l)) return l
            if (isTypeVarForm(r)) return r
            val a = rank[nameOf(l)] ?: return l
            val b = rank[nameOf(r)] ?: return l
            return if (a >= b) l else r
        }

        /** 公共类型：已解分支全部一致才填；未知分支若含本 SCC 的递归调用则宽容跳过，否则放弃。 */
        private fun commonArms(arms: List<Pair<Type?, Expr>>): Type? {
            val parts = ArrayList<Type>()
            for ((t, expr) in arms) {
                if (t != null) { parts += t; continue }
                if (!containsSccCall(expr)) return null
            }
            val set = parts.toSet()
            return if (set.size == 1) set.first() else null
        }

        private fun containsSccCall(e: Expr): Boolean = when (e) {
            is CallExpr -> ((e.callee as? NameRef)?.name in scc) || e.args.any { containsSccCall(it) }
            is BinExpr -> containsSccCall(e.left) || containsSccCall(e.right)
            is UniExpr -> containsSccCall(e.operand)
            is IfExpr -> containsSccCallBody(e.thenBlock) || (e.elseBlock?.let { containsSccCallBody(it) } ?: false)
            is BlockExpr -> e.stmts.any { containsSccCallStmt(it) }
            is WhenExpr -> e.arms.any { containsSccCall(it.body) }
            else -> false
        }

        private fun containsSccCallBody(b: BlockExpr): Boolean = b.stmts.any { containsSccCallStmt(it) }
        private fun containsSccCallStmt(s: Stmt): Boolean = when (s) {
            is ExprStmt -> containsSccCall(s.expr)
            is ReturnStmt -> s.expr?.let { containsSccCall(it) } ?: false
            else -> false
        }
    }

    // ==================== D. class 成员签名回填（doc §4.5） ====================

    private fun buildClassBackfill(
        userClasses: Map<String, ClassDecl>,
        implMembers: Map<String, LinkedHashMap<String, FunDecl>>,
        paramTypes: Map<Pair<String, String>, Type>,
        retTypes: Map<String, Type>,
    ): Map<String, Map<String, FunDecl>> {
        val out = LinkedHashMap<String, Map<String, FunDecl>>()
        for ((trait, members) in implMembers) {
            val cls = userClasses[trait] ?: continue
            val filled = LinkedHashMap<String, FunDecl>()
            for ((mname, im) in members) {
                val member = cls.members.firstOrNull { it.name == mname } ?: continue
                val ret = member.retType ?: im.retType ?: retTypes[im.name]
                val params = member.params.mapIndexed { idx, p ->
                    if (!p.type.isInferMarker()) p
                    else im.params.getOrNull(idx)?.let { ip -> paramTypes[im.name to ip.name] }?.let { p.copy(type = it) } ?: p
                }
                filled[mname] = if (ret != member.retType || params !== member.params) member.copy(retType = ret, params = params) else member
            }
            out[trait] = filled
        }
        return out
    }

    // ==================== E. 效应（含 D-AUTO-IMPURE） ====================

    private fun solveImpurity(userFuns: Map<String, FunDecl>, facts: Facts, notes: MutableList<InferNote>): Set<String> {
        val impure = LinkedHashSet<String>()
        for ((name, fn) in userFuns) {
            val calls = facts.bodyCalls[name] ?: emptySet()
            if ("impure" in fn.annotations || calls.any { builtinimpure(it) != null }) impure += name
            if (name in calls && facts.selfRecShrinking[name] != true) {
                if (impure.add(name))
                    notes += InferNote("SUPPLEMENT", "D-AUTO-IMPURE", fn.pos,
                        "自递归未证停机（无递减实参），已自动标注 @impure 放行（doc §7.5）；其效应会向调用者传播。想精确控制请显式标注返回类型（doc §7.6 逃生舱）")
            }
        }
        var changed = true
        while (changed) {
            changed = false
            for ((name, _) in userFuns) {
                if (name in impure) continue
                val calls = facts.bodyCalls[name] ?: continue
                if (calls.any { it in impure }) { impure += name; changed = true }
            }
        }
        return impure
    }

    // ==================== F. 上下文：前件反推 + 后件交集（doc §十） ====================

    private fun solvePreps(
        userFuns: Map<String, FunDecl>,
        facts: Facts,
        stdlibEntries: List<Entry>,
        prepLifts: MutableMap<String, MutableSet<String>>,
        propLifts: MutableMap<String, MutableSet<String>>,
    ) {
        // 静态：被调者自己声明的前件/命题参数（@theorem 不参与）
        val declared = LinkedHashMap<String, Pair<Set<String>, Set<String>>>()
        for ((name, fn) in userFuns) if (!isTheorem(fn)) declared[name] = prepPropsOf(fn)
        for (e in stdlibEntries) {
            val fn = (e as? DeclEntry)?.decl as? FunDecl ?: continue
            if (!isTheorem(fn)) declared[fn.name] = prepPropsOf(fn)
        }
        for (name in userFuns.keys) { prepLifts[name] = linkedSetOf(); propLifts[name] = linkedSetOf() }
        var changed = true
        var rounds = 0
        while (changed && rounds < 8) {
            changed = false; rounds++
            for ((name, fn) in userFuns) {
                if (isTheorem(fn)) continue
                val callees = facts.bodyCalls[name] ?: continue
                for (c in callees) {
                    val (preps, props) = declared[c]
                        ?: (prepLifts[c]?.let { it.toSet() to (propLifts[c] ?: emptySet()).toSet() } ?: continue)
                    val myPreps = prepLifts[name]!!
                    val myProps = propLifts[name]!!
                    val newPreps = preps - fn.prepNames()
                    val newProps = props - (fn.theory.props() + myProps)
                    if (newPreps.isNotEmpty() || newProps.isNotEmpty()) {
                        myPreps += newPreps; myProps += newProps; changed = true
                    }
                }
            }
        }
    }

    private fun prepPropsOf(fn: FunDecl): Pair<Set<String>, Set<String>> {
        val preps = linkedSetOf<String>()
        for (e in fn.preps) (e as? NameRef)?.name?.let { preps += it }
        return preps to fn.theory.props()
    }

    private fun List<TheoryParam>.props(): Set<String> =
        mapNotNull { (it as? PropParam)?.name }.toSet()

    private fun FunDecl.prepNames(): Set<String> =
        preps.mapNotNull { (it as? NameRef)?.name }.toSet()

    /** 后件反推：所有 return 处都可用（交集）的 ∵ 注入命题（doc §10.2）。 */
    private fun postFacts(fn: FunDecl): Set<String> {
        val body = fn.body as? BlockExpr ?: return emptySet()
        val snapshots = ArrayList<Set<String>>()
        walkForPosts(body.stmts, linkedSetOf(), snapshots)
        if (snapshots.isEmpty()) return emptySet()
        return snapshots.reduce { acc, set -> acc intersect set }
    }

    private fun walkForPosts(stmts: List<Stmt>, avail: MutableSet<String>, out: MutableList<Set<String>>) {
        for (s in stmts) {
            when (s) {
                is AxiomStmt -> s.props.forEach { (it as? NameRef)?.name?.let { n -> avail += n } }
                is ReturnStmt -> out += avail.toSet()
                is UncheckedStmt -> walkForPosts(listOf(s.inner), avail, out)
                is ExprStmt -> walkExprForPosts(s.expr, avail, out)
                else -> {}
            }
        }
    }

    private fun walkExprForPosts(e: Expr, avail: MutableSet<String>, out: MutableList<Set<String>>) {
        when (e) {
            is IfExpr -> {
                walkForPosts(e.thenBlock.stmts, avail.toMutableSet(), out)
                e.elseBlock?.let { walkForPosts(it.stmts, avail.toMutableSet(), out) }
            }
            is WhenExpr -> e.arms.forEach { arm ->
                (arm.body as? BlockExpr)?.let { walkForPosts(it.stmts, avail.toMutableSet(), out) }
            }
            is BlockExpr -> walkForPosts(e.stmts, avail.toMutableSet(), out)
            else -> {}
        }
    }

    // ==================== G. 可变性 ====================

    private fun walkTarget(
        target: Expr,
        facts: Facts,
        userStructs: Map<String, StructDecl>,
        mutVars: MutableSet<String>,
        mutFields: MutableSet<Pair<String, String>>,
    ) {
        when (target) {
            is NameRef -> mutVars += target.name
            is FieldExpr -> {
                rootOf(target.target)?.let { root ->
                    val structName = facts.varStructs[root]
                    if (structName != null && structName in userStructs &&
                        userStructs[structName]!!.fields.any { it.name == target.name })
                        mutFields += structName to target.name
                    mutVars += root
                }
                walkTarget(target.target, facts, userStructs, mutVars, mutFields)
            }
            else -> {}
        }
    }

    private fun rootOf(e: Expr): String? = when (e) {
        is NameRef -> e.name
        is FieldExpr -> rootOf(e.target)
        else -> null
    }

    // ==================== H. 重建 ====================

    private fun rebuildFun(
        d: FunDecl,
        paramTypes: Map<Pair<String, String>, Type>,
        retTypes: Map<String, Type>,
        impureFuns: Set<String>,
        mutVars: Set<String>,
        funTheory: Map<String, List<TypeParam>>,
        prepLifts: Map<String, Set<String>>,
        propLifts: Map<String, Set<String>>,
        postLifts: Map<String, Set<String>>,
    ): FunDecl {
        val params = d.params.map { p ->
            val t = paramTypes[d.name to p.name]
            if (t != null && p.type.isInferMarker()) p.copy(type = t) else p
        }
        val extraTheory = (funTheory[d.name] ?: emptyList()).filter { tp ->
            d.theory.none { (it as? TypeParam)?.name == tp.name }
        }
        val propTheory = (propLifts[d.name] ?: emptySet()).map { PropParam(it) }.filter { pp ->
            d.theory.none { (it as? PropParam)?.name == pp.name }
        }
        val theory = if (extraTheory.isEmpty() && propTheory.isEmpty()) d.theory else d.theory + extraTheory + propTheory
        val retType = d.retType ?: retTypes[d.name]
        val preps = mergeRefs(d.preps, prepLifts[d.name] ?: emptySet())
        val posts = mergeRefs(d.posts, postLifts[d.name] ?: emptySet())
        val anns = if (d.name in impureFuns && "impure" !in d.annotations) d.annotations + "impure" else d.annotations
        val body = d.body?.let { Rebuilder(mutVars).expr(it) }
        val changed = params !== d.params || theory !== d.theory || retType !== d.retType ||
            preps !== d.preps || posts !== d.posts || anns !== d.annotations || body !== d.body
        return if (!changed) d else d.copy(params = params, theory = theory, retType = retType, preps = preps, posts = posts, annotations = anns, body = body)
    }

    private fun mergeRefs(base: List<Expr>, lifted: Set<String>): List<Expr> {
        if (lifted.isEmpty()) return base
        val known = base.mapNotNull { (it as? NameRef)?.name }.toSet()
        val adding = lifted.filter { it !in known }
        return if (adding.isEmpty()) base else base + adding.map { NameRef(it) }
    }

    private fun rebuildStruct(
        st: StructDecl,
        fieldTypes: Map<Pair<String, String>, Type>,
        mutFields: Set<Pair<String, String>>,
        structTheory: Map<String, List<TypeParam>>,
    ): StructDecl {
        var changed = false
        val fields = st.fields.map { f ->
            val t = fieldTypes[st.name to f.name]
            val typeOk = t != null && f.type.isInferMarker()
            val fanns = if ((st.name to f.name) in mutFields && "mut" !in f.annotations) f.annotations + "mut" else f.annotations
            changed = changed || typeOk || (fanns !== f.annotations)
            if (typeOk || fanns !== f.annotations) f.copy(type = if (typeOk) t!! else f.type, annotations = fanns) else f
        }
        val extraTheory = (structTheory[st.name] ?: emptyList()).filter { tp ->
            st.theory.none { (it as? TypeParam)?.name == tp.name }
        }
        val theory = if (extraTheory.isEmpty()) st.theory else st.theory + extraTheory
        val anns = if (mutFields.any { it.first == st.name } && "mut" !in st.annotations) st.annotations + "mut" else st.annotations
        changed = changed || (theory !== st.theory) || (anns !== st.annotations)
        return if (!changed) st else st.copy(fields = fields, theory = theory, annotations = anns)
    }

    private fun rebuildClass(cls: ClassDecl, classBackfill: Map<String, Map<String, FunDecl>>): ClassDecl {
        val filled = classBackfill[cls.name] ?: return cls
        var changed = false
        val members = cls.members.map { m ->
            val f = filled[m.name]
            if (f != null && f !== m) { changed = true; f } else m
        }
        return if (!changed) cls else cls.copy(members = members)
    }

    private class Rebuilder(private val mutVars: Set<String>) {
        fun stmt(s: Stmt): Stmt = when (s) {
            is VarStmt -> s.copy(
                annotations = if (s.name in mutVars && "mut" !in s.annotations) s.annotations + "mut" else s.annotations,
                value = expr(s.value),
            )
            is AssignStmt -> s.copy(target = expr(s.target), value = expr(s.value))
            is ExprStmt -> s.copy(expr = expr(s.expr))
            is ReturnStmt -> s.copy(expr = s.expr?.let { expr(it) })
            is UncheckedStmt -> s.copy(inner = stmt(s.inner))
            is FunStmt -> s.copy(fn = s.fn.copy(body = s.fn.body?.let { expr(it) }))
            else -> s
        }

        fun expr(e: Expr): Expr = when (e) {
            is BinExpr -> e.copy(left = expr(e.left), right = expr(e.right))
            is UniExpr -> e.copy(operand = expr(e.operand))
            is TypeTestExpr -> e.copy(target = expr(e.target))
            is IsPatExpr -> e.copy(target = expr(e.target))
            is TaskExpr -> e.copy(body = block(e.body))
            is FlowExpr -> e.copy(body = block(e.body))
            is CallExpr -> e.copy(
                callee = expr(e.callee),
                args = e.args.map { expr(it) },
                namedArgs = e.namedArgs.mapValues { expr(it.value) },
            )
            is InstExpr -> e.copy(target = expr(e.target), terms = e.terms.map { expr(it) })
            is FieldExpr -> e.copy(target = expr(e.target))
            is FunRefExpr -> e.copy(target = expr(e.target))
            is LambdaExpr -> e.copy(body = expr(e.body))
            is TupleExpr -> e.copy(items = e.items.map { expr(it) })
            is StructCtorExpr -> e.copy(assigns = e.assigns.map { it.first to expr(it.second) })
            is ByExpr -> e.copy(target = expr(e.target), props = e.props.map { expr(it) })
            is VarExpr -> e.copy(
                annotations = if (e.name in mutVars && "mut" !in e.annotations) e.annotations + "mut" else e.annotations,
                value = expr(e.value),
            )
            is AnonFunExpr -> e.copy(
                preps = e.preps.map { expr(it) },
                posts = e.posts.map { expr(it) },
                body = e.body?.let { expr(it) },
            )
            is BlockExpr -> e.copy(stmts = e.stmts.map { stmt(it) })
            is IfExpr -> e.copy(
                cond = expr(e.cond),
                thenBlock = block(e.thenBlock),
                elseBlock = e.elseBlock?.let { block(it) },
            )
            is WhenExpr -> e.copy(
                subject = e.subject?.let { expr(it) },
                arms = e.arms.map { arm ->
                    WhenArm(arm.pattern, arm.guard?.let { expr(it) }, expr(arm.body), arm.pos, arm.cond?.let { expr(it) })
                },
            )
            is QuantExpr -> e.copy(body = expr(e.body))
            else -> e
        }

        private fun block(b: BlockExpr): BlockExpr = BlockExpr(b.stmts.map { stmt(it) }, b.pos)
    }
}
