/**
 * 全局类型推导（idea doc/全局类型推导.md，V1）——**脚本模式专属**，由 Interpreter(globalInference = true) 开启。
 *
 * 定位：用户省略标注时填一个「能过检查、符合所想」的标注；不追求 principal type。
 * 优先级（doc §三）：显式标注 > 构造点 > 使用点 > 默认。
 *
 * V1 范围：
 * - 函数形参 / struct 字段类型：构造点（调用点、命名构造、默认值）→ 使用点（算术偏好 Nat）→ 默认 Any
 * - 效应：体内调用非纯符号（内建/stdlib @impure/已推 impure 的脚本函数）→ @impure；
 *   自递归且无递减实参（停机失败）→ @impure（doc §7.5 降级，替代 E-NON-STRUCTURAL-REC 报错）
 * - 可变性：赋值目标链 → @mut var / @mut 字段 / @mut struct（doc §八）
 * - 返回类型：Checker 本地推断已具备
 *
 * V1 暂不做：泛型化 [T: Num]、SCC 互递归迭代、上下文（前件/后件）填充、D-AUTO-IMPURE、
 * E-TYPE-CONFLICT（冲突交由 Checker 既有类型检查报错）。
 *
 * 不变量：显式标注最高优先（非 InferMarker 的类型与已有注解一律保留）；产物仍整体过 Checker。
 */
package sugared.functor.interpreter

import sugared.functor.ast.*
import sugared.functor.checker.builtinimpure

object GlobalInference {

    fun infer(ast: FileAst, stdlibEntries: List<Entry>): FileAst {
        val userFuns = LinkedHashMap<String, FunDecl>()
        val userStructs = LinkedHashMap<String, StructDecl>()
        for (e in ast.entries) {
            when (val d = (e as? DeclEntry)?.decl) {
                is FunDecl -> userFuns[d.name] = d
                is StructDecl -> userStructs[d.name] = d
                is ImplDecl -> for (m in d.members) userFuns[m.name] = m
                else -> {}
            }
        }

        val facts = analyze(ast, userStructs)
        val paramTypes = LinkedHashMap<Pair<String, String>, Type>()
        for ((_, fn) in userFuns) fillFunParams(fn, facts, paramTypes, userStructs)
        val fieldTypes = LinkedHashMap<Pair<String, String>, Type>()
        for ((_, st) in userStructs) fillStructFields(st, facts, fieldTypes, userStructs)
        val impureFuns = solveImpurity(userFuns, facts)
        val mutVars = LinkedHashSet<String>()
        val mutFields = LinkedHashSet<Pair<String, String>>()
        for (target in facts.assignTargets) walkTarget(target, facts, userStructs, mutVars, mutFields)

        val out = ArrayList<Entry>(ast.entries.size)
        for (e in ast.entries) {
            val old = (e as? DeclEntry)?.decl
            val newDecl: Decl? = when (old) {
                is FunDecl -> rebuildFun(d = old, paramTypes, impureFuns, mutVars)
                is StructDecl -> rebuildStruct(old, fieldTypes, mutFields)
                is ImplDecl -> old.copy(members = old.members.map { rebuildFun(it, paramTypes, impureFuns, mutVars) })
                else -> null
            }
            if (newDecl != null && newDecl !== old) { out += DeclEntry(newDecl); continue }
            // 顶层语句：可变性注解打在 VarStmt/VarExpr 上（@mut var）
            val se = e as? StmtEntry
            if (se != null) {
                val s2 = Rebuilder(mutVars).stmt(se.stmt)
                out += if (s2 !== se.stmt) StmtEntry(s2) else e
            } else out += e
        }
        return FileAst(out)
    }

    // ==================== 分析 ====================

    private class Facts(
        val funCallSites: MutableMap<String, MutableList<CallSite>> = LinkedHashMap(),
        val ctorCallSites: MutableMap<String, MutableList<CallSite>> = LinkedHashMap(),
        val structCtorSites: MutableMap<String, MutableList<List<Pair<String, Expr>>>> = LinkedHashMap(),
        val varLitTypes: MutableMap<String, Type?> = LinkedHashMap(),
        val varStructs: MutableMap<String, String> = LinkedHashMap(),
        val assignTargets: MutableList<Expr> = ArrayList(),
        val bodyCalls: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
        val arithParams: MutableMap<String, MutableSet<String>> = LinkedHashMap(),
        val selfRecShrinking: MutableMap<String, Boolean> = LinkedHashMap(),
    )

    private class CallSite(val args: List<Expr>, val named: Map<String, Expr>)

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

        private fun recordVar(name: String, value: Expr) {
            val t = literalType(value)
            if (t != null) {
                val prev = facts.varLitTypes.putIfAbsent(name, t)
                if (prev != null && prev != t) facts.varLitTypes[name] = null
            }
            val st = ctorStructOf(value)
            if (st != null) facts.varStructs.putIfAbsent(name, st)
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
                        facts.funCallSites.getOrPut(n) { arrayListOf() } += CallSite(e.args, e.namedArgs)
                        if (n in userStructs) facts.ctorCallSites.getOrPut(n) { arrayListOf() } += CallSite(e.args, e.namedArgs)
                    }
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

        private fun recordArithUse(e: Expr) {
            val params = paramStack.lastOrNull() ?: return
            val fn = curFun.lastOrNull() ?: return
            val name = when (e) {
                is ParamRefExpr -> params.elementAtOrNull(e.index)
                is NameRef -> if (e.name in params) e.name else null
                else -> null
            } ?: return
            facts.arithParams.getOrPut(fn) { linkedSetOf() } += name
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

        private fun ctorStructOf(e: Expr): String? = when (e) {
            is CallExpr -> {
                val n = (e.callee as? NameRef)?.name
                if (n != null && n in userStructs) n else null
            }
            is StructCtorExpr -> e.struct
            else -> null
        }
    }

    // ==================== 求解 ====================

    private fun fillFunParams(
        fn: FunDecl,
        facts: Facts,
        out: MutableMap<Pair<String, String>, Type>,
        userStructs: Map<String, StructDecl>,
    ) {
        val sites = facts.funCallSites[fn.name] ?: emptyList()
        val arith = facts.arithParams[fn.name] ?: emptySet()
        for ((idx, p) in fn.params.withIndex()) {
            if (!p.type.isInferMarker()) continue
            val candidates = LinkedHashSet<Type>()
            for (site in sites) {
                site.args.getOrNull(idx)?.let { addCandidate(candidates, it, userStructs) }
                site.named[p.name]?.let { addCandidate(candidates, it, userStructs) }
            }
            val type = candidates.singleOrNull()
                ?: if (p.name in arith) NamedType("Nat") else null
                ?: NamedType("Any")
            out[fn.name to p.name] = type
        }
    }

    private fun fillStructFields(
        st: StructDecl,
        facts: Facts,
        out: MutableMap<Pair<String, String>, Type>,
        userStructs: Map<String, StructDecl>,
    ) {
        for ((idx, f) in st.fields.withIndex()) {
            if (!f.type.isInferMarker()) continue
            val candidates = LinkedHashSet<Type>()
            for (site in facts.ctorCallSites[st.name] ?: emptyList())
                site.args.getOrNull(idx)?.let { addCandidate(candidates, it, userStructs) }
            for (site in facts.structCtorSites[st.name] ?: emptyList())
                site.firstOrNull { it.first == f.name }?.let { addCandidate(candidates, it.second, userStructs) }
            f.default?.let { addCandidate(candidates, it, userStructs) }
            out[st.name to f.name] = candidates.singleOrNull() ?: NamedType("Any")
        }
    }

    private fun addCandidate(candidates: MutableSet<Type>, arg: Expr, userStructs: Map<String, StructDecl>) {
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
        if (t != null) candidates += t
    }

    private fun solveImpurity(userFuns: Map<String, FunDecl>, facts: Facts): Set<String> {
        val impure = LinkedHashSet<String>()
        for ((name, fn) in userFuns) {
            val calls = facts.bodyCalls[name] ?: emptySet()
            if ("impure" in fn.annotations || calls.any { builtinimpure(it) != null }) impure += name
            if (name in calls && facts.selfRecShrinking[name] != true) impure += name
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

    // ==================== 重建 ====================

    private fun rebuildFun(
        d: FunDecl,
        paramTypes: Map<Pair<String, String>, Type>,
        impureFuns: Set<String>,
        mutVars: Set<String>,
    ): FunDecl {
        val fn = d
        val params = fn.params.map { p ->
            val t = paramTypes[fn.name to p.name]
            if (t != null && p.type.isInferMarker()) p.copy(type = t) else p
        }
        val anns = if (fn.name in impureFuns && "impure" !in fn.annotations) fn.annotations + "impure" else fn.annotations
        val body = fn.body?.let { Rebuilder(mutVars).expr(it) }
        return if (params === fn.params && anns === fn.annotations && body === fn.body) fn
            else fn.copy(params = params, annotations = anns, body = body)
    }

    private fun rebuildStruct(
        st: StructDecl,
        fieldTypes: Map<Pair<String, String>, Type>,
        mutFields: Set<Pair<String, String>>,
    ): StructDecl {
        var changed = false
        val fields = st.fields.map { f ->
            val t = fieldTypes[st.name to f.name]
            val typeOk = t != null && f.type.isInferMarker()
            val fanns = if ((st.name to f.name) in mutFields && "mut" !in f.annotations) f.annotations + "mut" else f.annotations
            changed = changed || typeOk || (fanns !== f.annotations)
            if (typeOk || fanns !== f.annotations) f.copy(type = if (typeOk) t!! else f.type, annotations = fanns) else f
        }
        val anns = if (mutFields.any { it.first == st.name } && "mut" !in st.annotations) st.annotations + "mut" else st.annotations
        changed = changed || (anns !== st.annotations)
        return if (!changed) st else st.copy(fields = fields, annotations = anns)
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
