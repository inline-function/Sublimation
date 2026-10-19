package sugared.functor.lsp

import sugared.functor.ast.*
import sugared.functor.checker.*
import sugared.functor.interpreter.GlobalInference
import sugared.functor.interpreter.InferNote
import sugared.functor.lexer.*
import sugared.functor.parser.*

/**
 * LSP 分析桥接层 —— 封装 lexer/parser/checker 的产物，向 LSP 提供：
 *   - 诊断（publishDiagnostics）
 *   - 悬停（hover：类型 / 签名）
 *   - 跳转（definition / 声明位置）
 *   - 补全（completion：关键字 / 已声明符号）
 *   - 文档符号（documentSymbol）
 *
 * 核心策略：每次 didOpen/didChange 重新 tokenize + parse + check，
 * 缓存 AST 与诊断结果，后续 hover/definition/completion 直接查缓存。
 */
class LspAnalysis {

    private data class DocState(
        val uri: String,
        val source: String,
        val ast: FileAst?,
        val bag: DiagBag?,
        val tokens: List<Token>?,
        val syms: Symbols? = null,
        val inferredVars: Map<String, Type>? = null,
        val decls: List<SymbolRef> = emptyList(),
        val refs: List<Pair<String, String>> = emptyList(),
        val lineStarts: IntArray = intArrayOf(),
    )

    private val docs = mutableMapOf<String, DocState>()

    // prelude：内建类型/函数/stdlib 符号（跨文档共享，惰性加载）
    private val preludeItems = mutableListOf<CompletionItem>()
    private val preludeHoverDocs = mutableMapOf<String, String>()
    private var preludeLoaded = false

    // 脚本模式（.subs）：标准库 entries（core 目录下的 .subl，供 Checker 与全局推导做符号源）
    // stdlibDir 默认 File("core)，与 Interpreter(stdlibDir) 一致；loadPrelude 时同步覆盖
    private var stdlibDir: java.io.File? = java.io.File("core")
    private var scriptStdlibCache: List<Entry>? = null
    // 脚本模式：全局类型推导（省略标注时填充类型/可变性/效应，见 wiki/全局类型推导.md）
    private val scriptGlobalInference = true

    fun openDocument(uri: String, source: String) {
        val state = analyze(uri, source)
        docs[uri] = state
    }

    fun closeDocument(uri: String) {
        docs.remove(uri)
    }

    fun updateDocument(uri: String, source: String) {
        val state = analyze(uri, source)
        docs[uri] = state
    }

    /**
     * 载入 prelude（幂等）：内建类型 + 内建函数 + 可选 stdlib 顶层符号。
     * 供补全与悬停使用（不参与 definition/references，避免虚假位置）。
     */
    fun loadPrelude(stdlibDir: java.io.File? = null) {
        if (preludeLoaded) return
        preludeLoaded = true
        if (stdlibDir != null) this.stdlibDir = stdlibDir   // 同步脚本模式的 stdlib 目录

        fun add(name: String, variety: String, detail: String, doc: String?) {
            preludeItems += CompletionItem(
                label = name,
                kind = kindOf(variety),
                detail = detail,
                sortText = "2_" + name,
            )
            if (doc != null) preludeHoverDocs[name] = doc
        }

        // 基本类型（编译器内建）
        for (t in BASE_TYPES.sorted()) add(t, "struct", t, "内建类型 " + t)

        // stdlib 容器/代数数据类型（core 统一标准库 core/collection/*.subl）
        for (t in STDLIB_TYPES.sorted()) add(t, "struct", t, "标准库类型 " + t)

        // 构造子（enum / 容器）
        for (c in STDLIB_CTORS.sorted()) add(c, "ctor", c, "构造子 " + c)

        // 内建函数（编译器原语，复用 FunDecl 拿真实签名）
        // P7：名字集直接用 checker 的 ROOT_WHITELIST（= builtinPure + builtinimpure 键集，与 codegen 同步），不再手写
        for (name in ROOT_WHITELIST.sorted()) {
            val fn = builtinPure(name) ?: builtinimpure(name) ?: continue
            add(name, "fun", "fun " + name + funSignature(fn), BUILTIN_FUN_DOCS[name])
        }

        // 可选 core 标准库文件（包目录，递归收集；core 统一标准库位于仓库根 core/）：解析后灌入顶层声明签名
        if (stdlibDir != null && stdlibDir.isDirectory) {
            for (f in stdlibDir.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }.sortedBy { it.path }) {
                try {
                    val src = f.readText()
                    val ast = parseSource(src, f.name)
                    val (decls, _) = collectAst(ast, src)
                    for (d in decls.filter { it.topLevel }) {
                        add(d.name, d.variety, d.detail, null)
                    }
                } catch (e: Exception) {
                    // P9：不再静默吞掉——记录到 stderr（不污染 LSP 帧流）
                    System.err.println("[sublimation-ls] stdlib 解析失败 " + f.name + ": " + (e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    /**
     * 脚本标准库 entries（core 目录下的 .subl 文件，惰性缓存）：供 Checker 与全局类型推导做符号源。
     * 与 Interpreter.loadStdlibEntries 同构：过滤 @root 内建声明（脚本模式下内建由 checker 内建表提供，
     * 留着会与调用撞 E-DUP-DECL）；解析失败记录 stderr 不中断。
     */
    private fun scriptStdlib(): List<Entry> {
        scriptStdlibCache?.let { return it }
        val dir = stdlibDir
        val raw: List<Entry> = if (dir != null && dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }
                .sortedBy { it.path }.mapNotNull { f ->
                    try {
                        parseSource(f.readText(), f.name).entries
                    } catch (e: Exception) {
                        System.err.println("[sublimation-ls] 脚本 stdlib 解析失败 " + f.name + ": " + (e.message ?: e.javaClass.simpleName))
                        null
                    }
                }.flatten().toList()
        } else emptyList()
        val entries = raw.filter { e ->
            val fd = (e as? DeclEntry)?.decl as? FunDecl
            fd == null || "root" !in fd.annotations
        }
        scriptStdlibCache = entries
        return entries
    }

    /** 分析单个文档：tokenize -> parse -> check（容错：任何阶段失败都回退到已有部分结果） */
    private fun analyze(uri: String, source: String): DocState {
        var ast: FileAst? = null
        var bag: DiagBag? = null
        var tokens: List<Token>? = null
        var parseNotes: ParseResult? = null
        val fileName = uri.substringAfterLast('/').substringAfterLast('\\')

        // tokenize (tolerant: lexical failures yield diagnostics)
        tokens = try {
            Lexer(source, fileName).tokenize()
        } catch (e: LexFailure) {
            bag = DiagBag()
            bag.error("E-LEX", e.line.toString() + ":" + e.col.toString(), e.message ?: "lexical error")
            null
        }

        // parse：脚本模式（.subs）用 scriptMode 解析（允许顶层语句；开启全局推导时放行省略标注）
        val isScript = fileName.endsWith(".subs")
        if (bag == null) {
            try {
                parseNotes = if (isScript)
                    parseScriptSourceWithNotes(source, fileName, allowUntyped = scriptGlobalInference)
                else
                    parseSourceWithNotes(source, fileName)
                ast = parseNotes!!.ast
            } catch (e: ParseFailure) {
                bag = DiagBag()
                val pos = e.tok.line.toString() + ":" + e.tok.col.toString()
                bag.error("E-PARSE", pos, e.message ?: "syntax error")
            } catch (e: Exception) {
                bag = DiagBag()
                bag.error("E-PARSE", "1:1", e.message ?: "syntax error")
            }
        }

        // check：脚本模式注入 stdlib + 全局类型推导，Checker 走 scriptMode（允许顶层语句）
        var syms: Symbols? = null
        var inferredVars: Map<String, Type>? = null
        if (bag == null && ast != null) {
            try {
                // 脚本模式：全局类型推导填充省略标注（推导后 AST 也供符号收集，让 hover/补全显示推导类型）
                val userFinal: FileAst
                val inferNotes: List<InferNote>
                if (isScript) {
                    val (inferred, notes) = composeScriptAst(ast!!)
                    userFinal = inferred
                    inferNotes = notes
                } else {
                    userFinal = ast!!
                    inferNotes = emptyList()
                }
                ast = userFinal
                // 脚本 Checker 输入 = stdlib（core 目录 .subl）+ 用户条目；工程模式即用户文件本身
                val checkedAst = if (isScript) FileAst(scriptStdlib() + userFinal.entries) else userFinal
                val checker = Checker(checkedAst, scriptMode = isScript)
                bag = checker.run()
                syms = checker.syms
                inferredVars = checker.inferredVars
                val notes = parseNotes
                if (notes != null) {
                    for ((p0, m0) in notes.hints) bag.hint("H-ARITH-PRECEDENCE", p0, m0)
                    for ((c0, p1, m1) in notes.supplements) bag.supplement(c0, p1, m1)
                }
                // 全局类型推导诊断：E-TYPE-CONFLICT（错误）/ D-AUTO-IMPURE（补充）—— IDE 不中止分析，原样展示
                for (n in inferNotes) {
                    if (n.severity == "ERROR") bag.error(n.code, n.pos, n.message)
                    else bag.supplement(n.code, n.pos, n.message)
                }
            } catch (e: Exception) {
                bag = DiagBag()
                bag.error("E-CHECK", "1:1", "analysis failed: " + (e.message ?: e.javaClass.simpleName))
            }
        }

        if (bag == null) bag = DiagBag()
        // P3：符号 + 引用一次性收集并缓存（后续查询纯查表，不再每次全遍历 AST）
        val (decls, refs) = if (ast != null) collectAst(ast, source) else emptyList<SymbolRef>() to emptyList()
        // P6：预建行首 offset 表（offset→Position 二分用）
        val lineStarts = buildLineStarts(source)
        return DocState(uri, source, ast, bag, tokens, syms, inferredVars, decls, refs, lineStarts)
    }

    /** 脚本模式：全局类型推导填充省略标注。返回推导后的用户 AST + 推导诊断。
     *  未开启推导或推导抛异常时原样返回 + 空诊断（stdlib 由 analyze 组装）。 */
    private fun composeScriptAst(userAst: FileAst): Pair<FileAst, List<InferNote>> {
        if (!scriptGlobalInference) return userAst to emptyList()
        val notes = mutableListOf<InferNote>()
        val inferred = try {
            GlobalInference.infer(userAst, scriptStdlib(), notes = notes)
        } catch (e: Exception) {
            System.err.println("[sublimation-ls] 全局类型推导失败: " + (e.message ?: e.javaClass.simpleName))
            return userAst to emptyList()
        }
        return inferred to notes
    }

    /** 生成 LSP 诊断 */
    fun diagnosticsFor(uri: String): List<Diagnostic> {
        val state = docs[uri] ?: return emptyList()
        val bag = state.bag ?: return emptyList()
        return bag.diags.map { d ->
            Diagnostic(
                range = diagPosToRange(d.pos, state.source),
                severity = d.severity.toDiagnosticSeverity(),
                code = d.code,
                source = "sublimation",
                message = d.message,
            )
        }
    }

    // ───────── 符号表索引（从 AST 收集，跨功能共用） ─────────

    /** 声明位置记录 */
    data class SymbolRef(
        val name: String,
        val variety: String,      // fun / struct / enum / class / impl / type / prop / var / param / field / ctor
        val pos: String,          // line:col
        val detail: String,       // 展示用（如 "fun add(a: Nat, b: Nat): Nat"）
        val lineStart: Int,       // 1-based 起始行
        val lineEnd: Int,         // 1-based 结束行
        val colStart: Int,        // 1-based 起始列
        val topLevel: Boolean = false,   // true = 顶层声明（大纲视图/导航用）
        val parent: String? = null,      // 所属顶层声明名（层级大纲用；顶层为 null）
    )

    /** AST 遍历上下文：同时采集局部声明与名字引用 */
    private class Walk(
        val decls: MutableList<SymbolRef> = mutableListOf(),
        val refs: MutableList<Pair<String, String>> = mutableListOf(),
    )

    /** 符号 + 引用（缓存当日：analyze 时已算好，这里纯查表） */
    private fun collect(uri: String): Pair<List<SymbolRef>, List<Pair<String, String>>> {
        val state = docs[uri] ?: return emptyList<SymbolRef>() to emptyList()
        return state.decls to state.refs
    }

    /** 从裸 AST + 源码收集符号（供 prelude / stdlib 载入复用），不依赖文档缓存 */
    private fun collectAst(ast: FileAst, source: String): Pair<List<SymbolRef>, List<Pair<String, String>>> {
        val walk = Walk()

        // parser 对 struct/enum/class/impl 未填 pos（FunDecl/TypeAlias/PropDef 有），
        // 按源码顺序用关键字回填位置，供导航/折叠/层级使用。
        var topCursor = 0
        fun locate(pattern: String, fallback: String): String {
            if (fallback.isNotBlank()) return fallback
            var from = topCursor
            while (true) {
                val idx = source.indexOf(pattern, from)
                if (idx < 0) return fallback
                val after = idx + pattern.length
                // P5：词边界检查——pattern 后不能是标识符字符（防 struct Point 误匹配 struct Point2）
                if (after >= source.length || !(source[after].isLetterOrDigit() || source[after] == '_')) {
                    topCursor = after
                    return offsetToLineCol(source, idx)
                }
                from = idx + 1
            }
        }

        // 当前所属顶层声明名（遍历时维护；局部符号挂到它下面）
        var currentParent: String? = null

        val addTop: (String, String, String, String) -> Unit = { name, variety, pos, detail ->
            val parts = pos.split(":")
            val l = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val c = parts.getOrNull(1)?.toIntOrNull() ?: 1
            walk.decls += SymbolRef(name, variety, pos, detail, l, l, c, topLevel = true, parent = null)
        }
        val add: (String, String, String, String) -> Unit = { name, variety, pos, detail ->
            val parts = pos.split(":")
            val l = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val c = parts.getOrNull(1)?.toIntOrNull() ?: 1
            walk.decls += SymbolRef(name, variety, pos, detail, l, l, c, topLevel = false, parent = currentParent)
        }

        for (entry in ast.entries) {
            when (val e = entry) {
                is DeclEntry -> {
                    val decl = e.decl
                    when (decl) {
                        is FunDecl -> {
                            addTop(decl.name, "fun", decl.pos, "fun " + decl.name + funSignature(decl))
                            currentParent = decl.name
                            walkFunDecl(decl, walk, add)
                            currentParent = null
                        }
                        is StructDecl -> {
                            val sp = locate("struct " + decl.name, decl.pos)
                            addTop(decl.name, "struct", sp,
                                "struct " + decl.name + structSignature(decl))
                            currentParent = decl.name
                            decl.fields.forEach { p ->
                                add(p.name, "field", sp,
                                    "field " + p.name + ": " + p.type.render())
                            }
                            currentParent = null
                        }
                        is EnumDecl -> {
                            val sp = locate("enum " + decl.name, decl.pos)
                            addTop(decl.name, "enum", sp,
                                "enum " + decl.name + enumSignature(decl))
                            currentParent = decl.name
                            decl.ctors.forEach { c ->
                                add(c.name, "ctor", sp,
                                    "ctor " + c.name + "(" + c.fields.joinToString(", ") { it.render() } + ")")
                            }
                            currentParent = null
                        }
                        is ClassDecl -> {
                            val sp = locate("class " + decl.name, decl.pos)
                            addTop(decl.name, "class", sp,
                                "class " + decl.name + classSignature(decl))
                            currentParent = decl.name
                            decl.members.forEach { m ->
                                add(m.name, "fun", m.pos, "fun " + m.name + funSignature(m))
                                walkFunDecl(m, walk, add)
                            }
                            currentParent = null
                        }
                        is ImplDecl -> {
                            val ip = locate("impl", decl.pos)
                            val iname = implName(decl)
                            addTop(iname, "impl", ip,
                                "impl " + (decl.trait?.render() ?: "..") + " for " + decl.self.render())
                            currentParent = iname
                            decl.members.forEach { m ->
                                add(m.name, "fun", m.pos, "fun " + m.name + funSignature(m))
                                walkFunDecl(m, walk, add)
                            }
                            currentParent = null
                        }
                        is TypeAliasDecl -> addTop(decl.name, "type", decl.pos,
                            "type " + decl.name + " = " + decl.target.render())
                        is PropDefDecl -> addTop(decl.name, "prop", decl.pos,
                            "def " + decl.name)
                    }
                }
                is StmtEntry -> walkStmt(e.stmt, walk, add)
            }
        }
        return walk.decls to walk.refs
    }

    /** 收集全文件符号（含局部声明） */
    fun collectSymbols(uri: String): List<SymbolRef> = collect(uri).first

    // ───────── AST 遍历（声明 + 引用采集） ─────────

    private fun walkFunDecl(decl: FunDecl, w: Walk, add: (String, String, String, String) -> Unit) {
        decl.params.forEach { p ->
            val ann = if (p.annotations.isEmpty()) "" else p.annotations.joinToString(" ", "@", " ") + " "
            add(p.name, "param", decl.pos, "param " + ann + p.name + ": " + p.type.render())
        }
        decl.preps.forEach { walkExpr(it, w, add) }
        decl.constraints.forEach { walkExpr(it, w, add) }
        decl.posts.forEach { walkExpr(it, w, add) }
        decl.body?.let { walkExpr(it, w, add) }
    }

    private fun walkStmt(s: Stmt, w: Walk, add: (String, String, String, String) -> Unit) {
        when (s) {
            is VarStmt -> {
                add(s.name, "var", s.pos, "var " + s.name + (s.type?.let { ": " + it.render() } ?: ""))
                s.destruct?.forEach { d -> add(d, "var", s.pos, "var " + d) }
                walkExpr(s.value, w, add)
            }
            is AssignStmt -> { walkExpr(s.target, w, add); walkExpr(s.value, w, add) }
            is AxiomStmt -> s.props.forEach { walkExpr(it, w, add) }
            is ByStmt -> s.props.forEach { walkExpr(it, w, add) }
            is UncheckedStmt -> walkStmt(s.inner, w, add)
            is ExprStmt -> walkExpr(s.expr, w, add)
            is BTSStmt -> { walkExpr(s.form, w, add); s.premises.forEach { walkExpr(it, w, add) }; walkExpr(s.conclusion, w, add) }
            is WhenPropStmt -> { walkExpr(s.disjunction, w, add); s.arms.forEach { a -> walkExpr(a.condition, w, add); a.body.forEach { walkStmt(it, w, add) } } }
            is StrategyStmt -> walkExpr(s.goal, w, add)
            is ExistIntroStmt -> { s.witnesses.forEach { walkExpr(it, w, add) }; walkExpr(s.prop, w, add) }
            is ExistsElimStmt -> {
                add(s.name, "var", s.pos, "var " + s.name)
                walkExpr(s.prop, w, add)
                s.body.forEach { walkStmt(it, w, add) }
            }
            is FunStmt -> {
                add(s.fn.name, "fun", s.fn.pos, "fun " + s.fn.name + funSignature(s.fn))
                walkFunDecl(s.fn, w, add)
            }
            is IfPropStmt -> {
                walkExpr(s.prop, w, add)
                s.thenStmts.forEach { walkStmt(it, w, add) }
                s.elseStmts.forEach { walkStmt(it, w, add) }
            }
            is ReturnStmt -> s.expr?.let { walkExpr(it, w, add) }
        }
    }

    private fun walkExpr(e: Expr, w: Walk, add: (String, String, String, String) -> Unit) {
        when (e) {
            is IntLit, is FloatLit, is StrLit, is ReturnSym, is TopExpr, is BotExpr, is ParamRefExpr -> {}
            is NameRef -> w.refs += (e.name to e.pos)
            is BinExpr -> { walkExpr(e.left, w, add); walkExpr(e.right, w, add) }
            is UniExpr -> walkExpr(e.operand, w, add)
            is TypeTestExpr -> walkExpr(e.target, w, add)
        is IsPatExpr -> walkExpr(e.target, w, add)   // ifis 哨兵（正常经 parseIf 脱糖为 when，此处保守遍历）
            is TaskExpr -> walkExpr(e.body, w, add)
            is FlowExpr -> walkExpr(e.body, w, add)
            is CallExpr -> {
                walkExpr(e.callee, w, add)
                e.args.forEach { walkExpr(it, w, add) }
                e.namedArgs.values.forEach { walkExpr(it, w, add) }
            }
            is InstExpr -> { walkExpr(e.target, w, add); e.terms.forEach { walkExpr(it, w, add) } }
            is FieldExpr -> { w.refs += (e.name to e.pos); walkExpr(e.target, w, add) }
            is FunRefExpr -> { w.refs += (e.name to e.pos); walkExpr(e.target, w, add) }
            is SymbolCallExpr -> e.args.forEach { walkExpr(it, w, add) }
            is LambdaExpr -> {
                e.params.forEach { add(it, "var", e.pos, "var " + it) }
                walkExpr(e.body, w, add)
            }
            is TupleExpr -> e.items.forEach { walkExpr(it, w, add) }
            is StructCtorExpr -> {
                w.refs += (e.struct to e.pos)
                e.assigns.forEach { walkExpr(it.second, w, add) }
            }
            is ByExpr -> {
                walkExpr(e.target, w, add)
                e.props.forEach { walkExpr(it, w, add) }
            }
            is VarExpr -> {
                add(e.name, "var", e.pos, "var " + e.name + (e.type?.let { ": " + it.render() } ?: ""))
                walkExpr(e.value, w, add)
            }
            is AnonFunExpr -> {
                e.params.forEach { p ->
                    val ann = if (p.annotations.isEmpty()) "" else p.annotations.joinToString(" ", "@", " ") + " "
                    add(p.name, "param", e.pos, "param " + ann + p.name + ": " + p.type.render())
                }
                e.preps.forEach { walkExpr(it, w, add) }
                e.posts.forEach { walkExpr(it, w, add) }
                e.body?.let { walkExpr(it, w, add) }
            }
            is BlockExpr -> e.stmts.forEach { walkStmt(it, w, add) }
            is IfExpr -> {
                walkExpr(e.cond, w, add)
                walkExpr(e.thenBlock, w, add)
                e.elseBlock?.let { walkExpr(it, w, add) }
            }
            is WhenExpr -> {
                e.subject?.let { walkExpr(it, w, add) }
                e.subjectBinding?.let { add(it, "var", e.pos, "var " + it) }
                e.arms.forEach { arm ->
                    arm.guard?.let { walkExpr(it, w, add) }
                    arm.cond?.let { walkExpr(it, w, add) }
                    walkPattern(arm.pattern, w, add)
                    walkExpr(arm.body, w, add)
                }
            }
            is QuantExpr -> walkExpr(e.body, w, add)
        }
    }

    private fun walkPattern(p: Pattern, w: Walk, add: (String, String, String, String) -> Unit) {
        when (p) {
            is PatLit -> walkExpr(p.value, w, add)
            is PatCtor -> {
                w.refs += (p.name to p.pos)
                p.args.forEach { walkPattern(it, w, add) }
            }
            is PatTuple -> p.items.forEach { walkPattern(it, w, add) }
            is PatIs -> p.bind?.let { add(it, "var", p.pos, "var " + it + ": " + p.type.render()) }
            is PatBind -> if (p.name != "_") add(p.name, "var", p.pos, "var " + p.name)
            is PatElse, is PatCond -> {}
        }
    }

    private fun funSignature(decl: FunDecl): String {
        val theory = if (decl.theory.isEmpty()) "" else
            decl.theory.joinToString(", ", "<", ">") {
                when (it) {
                    is TypeParam -> it.name + (it.constraint?.let { c -> ": " + c } ?: "")
                    is PropParam -> "@" + it.name
                }
            }
        val params = decl.params.joinToString(", ") { p ->
            (if (p.annotations.isNotEmpty()) p.annotations.joinToString(" ", "@", " ") else "") +
                p.name + ": " + p.type.render()
        }
        val ret = decl.retType?.render() ?: "Null"
        return theory + "(" + params + "): " + ret
    }

    private fun structSignature(decl: StructDecl): String {
        val fields = decl.fields.joinToString(", ") { p -> p.name + ": " + p.type.render() }
        return if (fields.isEmpty()) "" else "(" + fields + ")"
    }

    private fun enumSignature(decl: EnumDecl): String {
        val ctors = decl.ctors.joinToString(", ") { c ->
            c.name + "(" + c.fields.joinToString(", ") { it.render() } + ")"
        }
        return if (ctors.isEmpty()) "" else "(" + ctors + ")"
    }

    private fun classSignature(decl: ClassDecl): String {
        val members = decl.members.joinToString(", ") { m -> m.name }
        return if (members.isEmpty()) "" else "(" + members + ")"
    }

    private fun implName(decl: ImplDecl): String = "impl " + decl.self.render()

    // ───────── hover：悬停类型/签名 ─────────

    /** 判断 offset 是否落在字符串字面量或行注释里（L3：hover 语义查询应跳过这些位置） */
    private fun inStringOrComment(source: String, offset: Int): Boolean {
        var i = 0
        val n = source.length
        while (i < n && i < offset) {
            val c = source[i]
            when {
                c == '"' -> {
                    val end = skipString(source, i)
                    if (offset > i && offset < end) return true
                    i = end
                }
                c == '/' && i + 1 < n && source[i + 1] == '/' -> {
                    val end = source.indexOf('\n', i).let { if (it < 0) n else it }
                    if (offset > i && offset < end) return true
                    i = end
                }
                else -> i++
            }
        }
        return false
    }

    fun hoverFor(uri: String, position: Position): Hover? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null
        if (inStringOrComment(source, offset)) return null  // L3：字符串/注释里的标识符不命中

        // 找当前位置的标识符
        val word = wordAtOffset(source, offset) ?: return null

        // 查符号表
        val symbols = collectSymbols(uri)
        // P4：hover 只做精确匹配，不做子串 fallback（避免 hover add 误命中 address/addOne）
        val match = symbols.find { it.name == word }

        if (match != null) {
            return Hover(
                contents = MarkupContent(
                    kind = "markdown",
                    value = "```subl\n" + match.detail + "\n```",
                ),
            )
        }

        // 关键字 hover
        val keywordDoc = KEYWORD_DOCS[word]
        if (keywordDoc != null) {
            return Hover(contents = MarkupContent(kind = "markdown", value = keywordDoc))
        }

        // prelude hover（内建类型/函数文档）
        val preludeDoc = preludeHoverDocs[word]
        if (preludeDoc != null) {
            return Hover(contents = MarkupContent(kind = "markdown", value = preludeDoc))
        }

        return null
    }

    // ───────── definition：跳转到声明 ─────────

    fun definitionFor(uri: String, position: Position): Location? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null
        val word = wordAtOffset(source, offset) ?: return null

        val symbol = collectSymbols(uri).find { it.name == word } ?: return null
        val range = diagPosToRange(symbol.pos, source)
        return Location(uri, range)
    }

    // ───────── typeDefinition：跳到类型声明 ─────────

    /** 光标处在类型名（struct/enum/class/type 别名）上时，跳到其声明 */
    fun typeDefinitionFor(uri: String, position: Position): Location? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null
        val word = wordAtOffset(source, offset) ?: return null

        val symbol = collectSymbols(uri).find {
            it.name == word && it.variety in TYPE_VARIETIES
        } ?: return null
        return Location(uri, diagPosToRange(symbol.pos, source))
    }

    // ───────── signatureHelp：函数调用签名提示 ─────────

    /**
     * 光标处在函数调用实参内时返回签名。基于文本扫描：向左找最近的未闭合 '('，
     * 再向前取函数名，随后在 AST 中查该函数的 FunDecl，构建 SignatureHelp。
     * 已知限制：字符串字面量里的 '(' 暂不特殊处理（罕见，后续可精化）。
     */
    fun signatureHelpFor(uri: String, position: Position): SignatureHelp? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null

        // 1. 向左扫描，找当前调用最内层的左括号（处理嵌套 ()）
        var depth = 0
        var parenStart = -1
        var i = offset - 1
        while (i >= 0) {
            when (source[i]) {
                ')' -> depth++
                '(' -> {
                    if (depth == 0) { parenStart = i; break }
                    depth--
                }
            }
            i--
        }
        if (parenStart < 0) return null

        // 2. 左括号前（跳过空白）取函数名
        var nameEnd = parenStart - 1
        while (nameEnd >= 0 && source[nameEnd].isWhitespace()) nameEnd--
        var nameStart = nameEnd
        while (nameStart >= 0 && (source[nameStart].isLetterOrDigit() || source[nameStart] == '_')) nameStart--
        nameStart++
        val callee = if (nameStart <= nameEnd) source.substring(nameStart, nameEnd + 1) else null
        if (callee.isNullOrEmpty()) return null

        // 3. 在 AST 中查函数声明（顶层函数 / 类型类方法 / impl 方法 / 局部函数）
        val fn = findFunction(state.ast, callee) ?: return null

        // 4. 当前实参下标（按顶层逗号计数；lambda/嵌套括号内的逗号不计）
        val activeParam = countTopLevelCommas(source, parenStart, offset)

        // 5. 组装签名
        val params = fn.params.map { p ->
            val ann = if (p.annotations.isEmpty()) "" else p.annotations.joinToString(" ", "@", " ") + " "
            ParameterInformation(ann + p.name + ": " + p.type.render())
        }
        val label = callee + "(" + params.joinToString(", ") { it.label } + ")" +
            (fn.retType?.let { ": " + it.render() } ?: "")

        return SignatureHelp(
            signatures = listOf(SignatureInformation(label = label, parameters = params)),
            activeSignature = 0,
            activeParameter = activeParam.coerceIn(0, (params.size - 1).coerceAtLeast(0)),
        )
    }

    private fun findFunction(ast: FileAst?, name: String): FunDecl? {
        val a = ast ?: return null
        for (entry in a.entries) {
            if (entry is DeclEntry) {
                when (val d = entry.decl) {
                    is FunDecl -> if (d.name == name) return d
                    is ClassDecl -> for (m in d.members) if (m.name == name) return m
                    is ImplDecl -> for (m in d.members) if (m.name == name) return m
                    else -> {}
                }
            }
        }
        return null
    }

    private fun countTopLevelCommas(source: String, parenStart: Int, cursor: Int): Int {
        var count = 0
        var parenDepth = 0
        var braceDepth = 0
        for (j in parenStart + 1 until cursor) {
            when (source[j]) {
                '(' -> parenDepth++
                ')' -> if (parenDepth > 0) parenDepth--
                '{' -> braceDepth++
                '}' -> if (braceDepth > 0) braceDepth--
                ',' -> if (parenDepth == 0 && braceDepth == 0) count++
            }
        }
        return count
    }

    // ───────── foldingRange：代码折叠 ─────────

    /** 基于花括号配对的折叠区间（跳过字符串字面量与 // 行注释） */
    fun foldingRanges(uri: String): List<FoldingRange> {
        val state = docs[uri] ?: return emptyList()
        val src = state.source
        val out = mutableListOf<FoldingRange>()
        val stack = mutableListOf<Int>()   // 左括号所在行号（0-based）
        var line = 0
        var inStr = false
        var inLineComment = false
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                inLineComment -> {
                    if (c == '\n') { inLineComment = false; line++ }
                }
                inStr -> {
                    when {
                        c == '\\' -> i++          // 转义：跳过下一字符
                        c == '"' -> inStr = false
                        c == '\n' -> line++
                    }
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    inLineComment = true
                    i++
                }
                c == '"' -> inStr = true
                c == '{' -> stack.add(line)
                c == '}' -> {
                    if (stack.isNotEmpty()) {
                        val startLine = stack.removeAt(stack.size - 1)
                        if (line > startLine) out += FoldingRange(startLine, line)
                    }
                }
                c == '\n' -> line++
            }
            i++
        }
        return out
    }

    // ───────── references：查找所有引用 ─────────

    fun referencesFor(uri: String, position: Position): List<Location> {
        val state = docs[uri] ?: return emptyList()
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return emptyList()
        val word = wordAtOffset(source, offset) ?: return emptyList()

        val (decls, refs) = collect(uri)
        val positions = LinkedHashSet<String>()
        decls.find { it.name == word }?.pos?.let { positions += it }
        for ((name, pos) in refs) if (name == word) positions += pos

        return positions.map { Location(uri, diagPosToRange(it, source)) }
    }

    // ───────── highlight：文档高亮（光标处标识符的全部出现） ─────────

    fun highlightsFor(uri: String, position: Position): List<DocumentHighlight> {
        val state = docs[uri] ?: return emptyList()
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return emptyList()
        val word = wordAtOffset(source, offset) ?: return emptyList()

        val (decls, refs) = collect(uri)
        val positions = LinkedHashSet<String>()
        decls.find { it.name == word }?.pos?.let { positions += it }
        for ((name, pos) in refs) if (name == word) positions += pos

        return positions.map {
            DocumentHighlight(range = diagPosToRange(it, source), kind = DocumentHighlightKind.Text)
        }
    }

    // ───────── rename：重命名预处理 + 重命名 ─────────

    /** prepareRename：返回光标处标识符的 Range（声明或引用均可） */
    fun prepareRenameFor(uri: String, position: Position): Range? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null
        val word = wordAtOffset(source, offset) ?: return null
        val (decls, refs) = collect(uri)

        val pos = decls.find { it.name == word }?.pos
            ?: refs.find { it.first == word }?.second
            ?: return null
        return diagPosToRange(pos, source)
    }

    /** rename：返回 { uri -> List<TextEdit> }（即 WorkspaceEdit.changes） */
    fun renameFor(uri: String, position: Position, newName: String): Map<String, List<TextEdit>>? {
        val state = docs[uri] ?: return null
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return null
        val word = wordAtOffset(source, offset) ?: return null
        val (decls, refs) = collect(uri)

        val positions = LinkedHashSet<String>()
        decls.find { it.name == word }?.pos?.let { positions += it }
        for ((name, pos) in refs) if (name == word) positions += pos
        if (positions.isEmpty()) return null

        val edits = positions.map { pos -> TextEdit(diagPosToRange(pos, source), newName) }
        return mapOf(uri to edits)
    }

    // ───────── workspace/symbol：跨打开文档符号搜索 ─────────

    /** 在所有已打开文档中搜索符号（按名字前缀/子串匹配） */
    fun workspaceSymbols(query: String): List<SymbolInformation> {
        val result = mutableListOf<SymbolInformation>()
        for ((uri, state) in docs) {
            val symbols = collect(uri).first
            for (s in symbols) {
                if (query.isBlank() || s.name.contains(query, ignoreCase = true)) {
                    result += SymbolInformation(
                        name = s.name,
                        kind = symbolKindOf(s.variety),
                        location = Location(uri, diagPosToRange(s.pos, state.source)),
                    )
                }
            }
        }
        return result
    }

    // ───────── semanticTokens：语义高亮 ─────────

    /**
     * 全量语义 token（delta 编码：每 5 个 int 一组 [deltaLine, deltaStart, length, typeIdx, modifiers]）。
     * 基于词法 token 流 + 符号 variety（IDENT 归类为 type/function/parameter/...），标点跳过。
     */
    fun semanticTokens(uri: String): List<Int>? = semanticTokensInRange(uri, null, null)

    /** 增量语义 token：仅返回与 [start, end] 相交的 token */
    fun semanticTokensRange(uri: String, start: Position, end: Position): List<Int>? =
        semanticTokensInRange(uri, start, end)

    private fun semanticTokensInRange(uri: String, start: Position?, end: Position?): List<Int>? {
        val state = docs[uri] ?: return null
        val tokens = state.tokens ?: return null

        // 符号名 → variety（IDENT 语义归类）
        val varietyByName = HashMap<String, String>()
        for (s in collectSymbols(uri)) varietyByName[s.name] = s.variety
        for (name in STDLIB_TYPES) varietyByName[name] = "struct"
        for (name in BASE_TYPES) varietyByName[name] = "struct"

        val data = ArrayList<Int>()
        var prevLine = 0
        var prevStart = 0
        var prevKind: Kind? = null
        for (t in tokens) {
            val typeIdx = semanticTypeOf(t, prevKind, varietyByName)
            prevKind = t.kind
            if (typeIdx == null) continue
            val line = t.line - 1
            val startCol = t.col - 1
            val endCol = startCol + t.text.length

            // range 过滤：token 与 [start, end) 相交
            if (start != null && end != null) {
                val before = (line < start.line) || (line == start.line && endCol <= start.character)
                val after = (line > end.line) || (line == end.line && startCol >= end.character)
                if (before || after) continue
            }

            data += line - prevLine
            data += if (line == prevLine) startCol - prevStart else startCol
            data += t.text.length
            data += typeIdx
            data += 0
            prevLine = line
            prevStart = startCol
        }
        return data
    }

    /** token -> 语义类型索引；null 表示不产出 token（标点/EOST）。prevKind 用于辨认注解名 */
    private fun semanticTypeOf(t: Token, prevKind: Kind?, variety: Map<String, String>): Int? = when (t.kind) {
        Kind.INT, Kind.FLOAT -> 7
        Kind.STR -> 8
        Kind.AT -> 13   // 注解符号 @
        Kind.FUN, Kind.CLASS, Kind.STRUCT, Kind.ENUM, Kind.VAR, Kind.IMPL,
        Kind.FOR, Kind.IF, Kind.ELSE, Kind.UNCHECKED, Kind.WHEN, Kind.IS,
        Kind.BY, Kind.TYPE, Kind.RET, Kind.IN, Kind.DEF -> 0
        Kind.IDENT -> when {
            prevKind == Kind.AT -> 13                      // 注解名（@mut / @impure）
            else -> when (variety[t.text]) {
                "fun" -> 2
                "struct" -> 12
                "enum" -> 11
                "class" -> 10
                "type" -> 1                                // 类型别名
                "param" -> 3
                "field" -> 5
                "ctor" -> 6
                "var" -> 4
                else -> 4
            }
        }
        Kind.SYMBOL -> 9
        Kind.PLUS, Kind.MINUS, Kind.STAR, Kind.SLASH, Kind.PERCENT,
        Kind.ASSIGN, Kind.EQ, Kind.NEQ, Kind.LE, Kind.GE, Kind.RANGE,
        Kind.ARROW, Kind.DARROW, Kind.IFF, Kind.AMP, Kind.PIPE, Kind.BANG,
        Kind.LT, Kind.GT,
        Kind.FORALL, Kind.EXISTS, Kind.EXISTS1, Kind.LAND, Kind.LOR,
        Kind.LNOT, Kind.IMPLIES, Kind.EQUIV, Kind.TOP, Kind.BOT,
        Kind.BECAUSE, Kind.THEREFORE, Kind.PLUS_BECAUSE -> 9
        else -> null
    }

    // ───────── formatting：格式化 ─────────

    /** 注解后须换行的声明关键字（注解独占一行）；H4：不含 VAR——@mut/@atomic var 与变量声明同行 */
    private val FORMAT_DECL_KINDS: Set<Kind> = setOf(
        Kind.FUN, Kind.STRUCT, Kind.ENUM, Kind.CLASS, Kind.IMPL, Kind.TYPE, Kind.DEF,
    )

    /** 全文档格式化：返回覆盖整篇的单个 TextEdit；无改动时返回空列表 */
    fun formatDocument(uri: String): List<TextEdit>? {
        val state = docs[uri] ?: return null
        val source = state.source
        if (source.isEmpty()) return emptyList()
        val formatted = formatSource(source)
        if (formatted == source) return emptyList()
        return listOf(TextEdit(Range(Position(0, 0), offsetToPosition(source, source.length)), formatted))
    }

    // ───────── onTypeFormatting：按 Enter 时的智能缩进 ─────────

    /** 按 Enter 换行后的格式化编辑；仅处理触发字符 \n */
    fun onTypeFormattingEdits(uri: String, position: Position, ch: String): List<TextEdit> {
        if (ch != "\n") return emptyList()
        val state = docs[uri] ?: return emptyList()
        val src = state.source
        val line = position.line
        if (line < 0) return emptyList()
        val lineText = lineTextOf(src, line) ?: return emptyList()
        val firstNonWs = lineText.indexOfFirst { !it.isWhitespace() }
        // 情况 1：行首紧邻 }——用户刚在 {} 之间按 Enter（} 被留在本行）：空行缩进本级，} 下移一行并少一级
        if (firstNonWs >= 0 && lineText[firstNonWs] == '}') {
            val n = indentLevelForLine(src, line)
            val closeN = (n - 1).coerceAtLeast(0)
            val newText = "    ".repeat(n) + "\n" + "    ".repeat(closeN) + "}"
            return listOf(TextEdit(Range(Position(line, 0), Position(line, firstNonWs + 1)), newText))
        }
        // 情况 2：普通换行——行首插入缩进；已有前导空白则替换为正确缩进
        val n = indentLevelForLine(src, line)
        if (n <= 0) return emptyList()
        val indent = "    ".repeat(n)
        val wsEnd = if (firstNonWs < 0) lineText.length else firstNonWs
        if (wsEnd == 0) return listOf(TextEdit(Range(Position(line, 0), Position(line, 0)), indent))
        if (lineText.substring(0, wsEnd) == indent) return emptyList()
        return listOf(TextEdit(Range(Position(line, 0), Position(line, wsEnd)), indent))
    }

    /** 取某行（0-based）文本（不含换行符）；越界 null */
    private fun lineTextOf(src: String, line: Int): String? {
        val starts = buildLineStarts(src)
        val s = starts.getOrNull(line) ?: return null
        val e = starts.getOrNull(line + 1) ?: src.length
        return src.substring(s, e).replace("\n", "").replace("\r", "")
    }

    /**
     * 按 Enter 后目标行（0-based）应有的缩进级别。token 级计算（lexer 无 NEWLINE，按行号切分）：
     *  大括号配平（{ 后非同行 } → +1，} → -1）；续行：上行尾为 = / -> / . / 未闭合括号 → +1；
     *  无大括号 if（行尾是配对 if 条件的 )）→ +1；目标行以 . 开头（链式续行）→ +1
     */
    private fun indentLevelForLine(src: String, targetLine: Int): Int {
        val tokens = try {
            Lexer(src, "indent").tokenize().filter { it.kind != Kind.EOF }
        } catch (e: Exception) {
            return 0
        }
        if (tokens.isEmpty() || targetLine <= 0) return 0
        val prevLine = targetLine - 1
        // 大括号配平：只统计严格在目标行之前的 token；跨行 {} 正常计数，同行空块 {} 不算
        var brace = 0
        var i = 0
        while (i < tokens.size && tokens[i].line - 1 < targetLine) {
            if (tokens[i].kind == Kind.LBRACE) {
                val nx = tokens.getOrNull(i + 1)
                if (nx != null && nx.kind == Kind.RBRACE && nx.line == tokens[i].line) i++
                else brace++
            } else if (tokens[i].kind == Kind.RBRACE) {
                if (brace > 0) brace--
            }
            i++
        }
        // 上一行末 token + 括号深度
        var paren = 0
        var lastTok: Token? = null
        for (t in tokens) {
            if (t.line - 1 > prevLine) break
            when (t.kind) {
                Kind.LPAREN, Kind.LBRACKET -> paren++
                Kind.RPAREN, Kind.RBRACKET -> if (paren > 0) paren--
                else -> {}
            }
            if (t.line - 1 == prevLine) lastTok = t
        }
        var ind = brace
        val lt = lastTok
        if (lt != null) {
            val k = lt.kind
            when {
                k == Kind.LBRACE -> { /* 行尾 { 已由 brace++ 覆盖 */ }
                paren > 0 -> ind++
                k == Kind.ASSIGN -> ind++
                k == Kind.ARROW -> ind++
                k == Kind.DOT -> ind++
                k == Kind.RPAREN && ifCondParen(tokens, lt) -> ind++
            }
        }
        val firstTok = tokens.firstOrNull { it.line - 1 == targetLine }
        if (firstTok?.kind == Kind.DOT) ind++
        return ind.coerceAtLeast(0)
    }

    /** 该 ) 是否为无大括号 if 的条件括号（回溯配对 (，其前是 IF 关键字） */
    private fun ifCondParen(tokens: List<Token>, closeParen: Token): Boolean {
        val idx = tokens.indexOf(closeParen)
        if (idx < 0) return false
        var depth = 0
        var j = idx
        while (j >= 0) {
            when (tokens[j].kind) {
                Kind.RPAREN -> depth++
                Kind.LPAREN -> {
                    depth--
                    if (depth == 0) {
                        val before = tokens.getOrNull(j - 1)
                        return before != null && before.kind == Kind.IF
                    }
                }
                else -> {}
            }
            j--
        }
        return false
    }

    // ───────── codeAction：快速修复（灯泡） ─────────

    /** 扫描全角符号，提供一个「替换全角为半角」的快速修复 */
    fun codeActions(uri: String): List<CodeAction> {
        val state = docs[uri] ?: return emptyList()
        val source = state.source
        val edits = mutableListOf<TextEdit>()
        var line = 0
        var col = 0
        for (ch in source) {
            val rep = FULLWIDTH_TO_HALF[ch]
            if (rep != null) {
                val start = Position(line, col)
                val end = Position(line, col + 1)
                edits += TextEdit(Range(start, end), rep)
            }
            if (ch == '\n') { line++; col = 0 } else col++
        }
        if (edits.isEmpty()) return emptyList()
        return listOf(
            CodeAction(
                title = "替换全角符号为半角",
                kind = "quickfix",
                edit = WorkspaceEdit(changes = mapOf(uri to edits)),
            )
        )
    }

    /** codeAction：诊断驱动的快速修正（合并原有全角符号修正）；diags 为 null 时取当前诊断 */
    fun codeActionsFor(uri: String, range: Range?, diags: List<Diagnostic>? = null): List<CodeAction> {
        val acts = codeActions(uri).toMutableList()

        val state = docs[uri] ?: return acts
        val tokens = state.tokens ?: return acts
        val ds = diags ?: diagnosticsFor(uri)
        // 光标区间过滤：只处理与请求 range 相交的诊断
        val inRange = if (range == null) ds else ds.filter { d ->
            val before = d.range.end.line < range.start.line ||
                (d.range.end.line == range.start.line && d.range.end.character <= range.start.character)
            val after = d.range.start.line > range.end.line ||
                (d.range.start.line == range.end.line && d.range.start.character >= range.end.character)
            !(before || after)
        }
        val edits = mutableListOf<TextEdit>()
        for (d in inRange) {
            when (d.code) {
                "E-NEED-ASYNC" -> fixNeedAsync(state, tokens, d)?.let { edits += it }
                else -> {}
            }
        }
        if (edits.isNotEmpty()) {
            acts += CodeAction(
                title = "给所在函数加 @async",
                kind = "quickfix",
                edit = WorkspaceEdit(changes = mapOf(uri to edits)),
            )
        }
        return acts
    }

    /** E-NEED-ASYNC：挂起调用需 @async 上下文——在最近的 fun 关键字（含局部/匿名函数）前插入注解 */
    private fun fixNeedAsync(state: DocState, tokens: List<Token>, diag: Diagnostic): TextEdit? {
        val source = state.source
        val lineStarts = state.lineStarts ?: return null
        val diagOff = positionToOffset(source, diag.range.start, lineStarts) ?: return null
        var best: Token? = null
        var bestOff = -1
        for (tok in tokens) {
            if (tok.kind != Kind.FUN) continue
            val ls = lineStarts.getOrNull(tok.line - 1) ?: continue
            val off = ls + tok.col - 1
            if (off <= diagOff && off > bestOff) { bestOff = off; best = tok }
        }
        val funTok = best ?: return null
        val insertOff = lineStarts.getOrNull(funTok.line - 1)?.let { it + funTok.col - 1 } ?: return null
        val pos = offsetToPosition(source, insertOff, lineStarts)
        return TextEdit(Range(pos, pos), "@async ")
    }

    // ───────── 成员上下文补全 + implementation 跳转 ─────────

    /** 提取类型 base 名（去掉泛型参数与 @mut/@atomic 等前缀） */
    private fun typeBase(rendered: String): String {
        var t = rendered.trim()
        while (t.startsWith("@")) t = t.substringAfter(' ').trim()
        return t.substringBefore('[').trim()
    }

    /** impl detail "impl <trait> for <self>" 里提取 trait base（无 trait 返回 null） */
    private fun implTraitBase(detail: String): String? {
        val trait = detail.removePrefix("impl ").substringBefore(" for ").trim()
        return if (trait == ".." || trait.isEmpty()) null else typeBase(trait)
    }

    /** 检测成员访问上下文：光标在 . 或 :: 之后，返回 (receiver 名, 是否::) */
    private fun memberContextAt(source: String, offset: Int): Pair<String, Boolean>? {
        if (offset <= 0) return null
        val afterColon2 = when {
            source[offset - 1] == '.' -> {
                if (offset >= 2 && source[offset - 2] == '.') return null  // .. 区间
                false
            }
            source[offset - 1] == ':' && offset >= 2 && source[offset - 2] == ':' -> true
            else -> return null
        }
        var p = offset - (if (afterColon2) 3 else 2)
        while (p >= 0 && (source[p] == ' ' || source[p] == '\t' || source[p] == '\r' || source[p] == '\n')) p--
        val end = p + 1
        while (p >= 0 && (source[p].isLetterOrDigit() || source[p] == '_')) p--
        val name = source.substring(p + 1, end)
        return if (name.isEmpty()) null else name to afterColon2
    }

    /** checker 推导的 Type → base 名 */
    private fun typeBaseOf(t: Type): String {
        val n = (t as? NamedType)?.name ?: t.render()
        return typeBase(n)
    }

    /** receiver 类型推断：checker 推导类型 → 类型名 → 显式注解（param/var/field） */
    private fun receiverType(uri: String, receiver: String): String? {
        val state = docs[uri] ?: return null
        // 1. checker 类型推导器导出的局部变量类型（var x = User(1) → User）
        state.inferredVars?.get(receiver)?.let { return typeBaseOf(it) }
        val symbols = collectSymbols(uri)
        // 2. 类型名（struct/enum/class/type）
        if (symbols.any { it.name == receiver && it.variety in TYPE_VARIETIES }) return receiver
        // 3. 显式类型（param/var/field 注解）
        val s = symbols.find { it.name == receiver && it.variety in setOf("var", "param", "field") } ?: return null
        val idx = s.detail.lastIndexOf(':')
        if (idx < 0) return null
        val base = typeBase(s.detail.substring(idx + 1))
        // L4：tuple/复杂类型 render 成 (Nat, Nat) 之类非标识符——不是合法类型名，视为推断失败
        return if (base.isNotEmpty() && base.all { it.isLetterOrDigit() || it == '_' }) base else null
    }

    /** 成员补全：. 后字段/方法，:: 后构造子/方法 */
    fun memberCompletion(uri: String, receiver: String, afterColon2: Boolean): List<CompletionItem> {
        val symbols = collectSymbols(uri)
        val recvType = receiverType(uri, receiver)

        val fields = HashMap<String, MutableList<SymbolRef>>()
        val ctors = HashMap<String, MutableList<SymbolRef>>()
        val implMethods = HashMap<String, MutableList<SymbolRef>>()   // self base -> 方法
        val classMethods = HashMap<String, MutableList<SymbolRef>>()  // class -> 方法
        val implTraits = HashMap<String, MutableList<String>>()       // self base -> trait base

        for (s in symbols) {
            when (s.variety) {
                "field" -> { val p = s.parent ?: continue; fields.getOrPut(p) { mutableListOf() }.add(s) }
                "ctor" -> { val p = s.parent ?: continue; ctors.getOrPut(p) { mutableListOf() }.add(s) }
                "fun" -> {
                    val p = s.parent ?: continue
                    if (p.startsWith("impl ")) {
                        implMethods.getOrPut(typeBase(p.removePrefix("impl "))) { mutableListOf() }.add(s)
                    } else {
                        classMethods.getOrPut(p) { mutableListOf() }.add(s)
                    }
                }
                "impl" -> {
                    val self = typeBase(s.name.removePrefix("impl "))
                    val trait = implTraitBase(s.detail)
                    if (trait != null) implTraits.getOrPut(self) { mutableListOf() }.add(trait)
                }
            }
        }

        val items = mutableListOf<CompletionItem>()
        fun addM(s: SymbolRef, kind: CompletionItemKind, order: Int) {
            items += CompletionItem(label = s.name, kind = kind, detail = s.detail, sortText = order.toString() + "_" + s.name)
        }
        fun addMethods(type: String) {
            implMethods[type]?.forEach { addM(it, CompletionItemKind.Method, 2) }
            implTraits[type]?.forEach { t -> classMethods[t]?.forEach { addM(it, CompletionItemKind.Method, 2) } }
        }

        if (recvType == null) {
            // 类型无法确定（无注解变量等）：回退返回全部成员
            if (afterColon2) ctors.values.flatten().forEach { addM(it, CompletionItemKind.EnumMember, 1) }
            else fields.values.flatten().forEach { addM(it, CompletionItemKind.Field, 1) }
            implMethods.values.flatten().forEach { addM(it, CompletionItemKind.Method, 2) }
            return items
        }

        if (afterColon2) {
            ctors[recvType]?.forEach { addM(it, CompletionItemKind.EnumMember, 1) }
        } else {
            fields[recvType]?.forEach { addM(it, CompletionItemKind.Field, 1) }
        }
        addMethods(recvType)

        return items
    }

    /** implementation 跳转：光标在类型名上 → 所有 impl ... for 该类型 */
    fun implementationsFor(uri: String, position: Position): List<Location> {
        val state = docs[uri] ?: return emptyList()
        val source = state.source
        val offset = positionToOffset(source, position, state.lineStarts) ?: return emptyList()
        val word = wordAtOffset(source, offset) ?: return emptyList()
        val out = mutableListOf<Location>()
        for (s in collectSymbols(uri)) {
            if (s.variety == "impl" && typeBase(s.name.removePrefix("impl ")) == word) {
                out += Location(uri, diagPosToRange(s.pos, source))
            }
        }
        return out
    }

    // ───────── inlayHint：内联提示（参数名）─────────

    /** 调用点参数名提示：对已知签名的函数调用，在每个位置实参前提示参数名 */
    fun inlayHints(uri: String): List<InlayHint> {
        val state = docs[uri] ?: return emptyList()
        val ast = state.ast ?: return emptyList()
        val source = state.source
        if (source.isEmpty()) return emptyList()

        val paramNames = HashMap<String, List<String>>()
        for (entry in ast.entries) {
            if (entry is DeclEntry) {
                when (val d = entry.decl) {
                    is FunDecl -> paramNames[d.name] = d.params.map { it.name }
                    is ClassDecl -> for (m in d.members) paramNames[m.name] = m.params.map { it.name }
                    is ImplDecl -> for (m in d.members) paramNames[m.name] = m.params.map { it.name }
                    else -> {}
                }
            }
        }
        if (paramNames.isEmpty()) return emptyList()

        val out = mutableListOf<InlayHint>()
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '"' -> i = skipString(source, i)
                c == '/' && i + 1 < n && source[i + 1] == '/' ->
                    while (i < n && source[i] != '\n') i++
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < n && (source[i].isLetterOrDigit() || source[i] == '_')) i++
                    val name = source.substring(start, i)
                    var j = i
                    while (j < n && source[j] == ' ') j++
                    if (j < n && source[j] == '(' && !isDeclContext(source, start)) {
                        val params = paramNames[name]
                        if (params != null && params.isNotEmpty()) {
                            collectArgHints(source, j + 1, params, out, state.lineStarts)
                        }
                    }
                }
                else -> i++
            }
        }

        // 变量类型提示：无注解 var（var x = ...）→ checker 推导类型
        state.inferredVars?.let { iv -> collectVarTypeHints(source, iv, out, state.lineStarts) }

        return out
    }

    /** 从开括号后扫描实参，对每个位置实参生成参数名 hint */
    private fun collectArgHints(source: String, start: Int, paramNames: List<String>, out: MutableList<InlayHint>, lineStarts: IntArray = intArrayOf()) {
        var argIdx = 0
        var pos = start
        val n = source.length
        while (pos < n && argIdx < paramNames.size) {
            while (pos < n && (source[pos] == ' ' || source[pos] == '\t')) pos++
            if (pos >= n) break
            val ch = source[pos]
            if (ch == ')' || ch == ';') break
            if (!isNamedArg(source, pos)) {
                out += InlayHint(
                    position = offsetToPosition(source, pos, lineStarts),
                    label = paramNames[argIdx] + ":",
                    kind = 2,
                    paddingRight = true,
                )
            }
            pos = argEnd(source, pos)
            argIdx++
        }
    }

    /** 实参是否命名参数（name = ...），须排除 ==（命题比较） */
    private fun isNamedArg(source: String, start: Int): Boolean {
        var i = start
        while (i < source.length && (source[i].isLetterOrDigit() || source[i] == '_')) i++
        while (i < source.length && (source[i] == ' ' || source[i] == '\t')) i++
        // H2：= 后面不能再是 =（区分 name = 与 name ==）
        return i < source.length && source[i] == '=' &&
            (i + 1 >= source.length || source[i + 1] != '=')
    }

    /** 实参结束位置：顶层逗号之后或 ) 之前（跟踪 () [] {} 与字符串/注释） */
    private fun argEnd(source: String, start: Int): Int {
        var i = start
        var paren = 0
        var bracket = 0
        var brace = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '"') { i = skipString(source, i); continue }
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                while (i < source.length && source[i] != '\n') i++
                continue
            }
            when (c) {
                '(' -> paren++
                ')' -> if (paren > 0) paren-- else return i
                '[' -> bracket++
                ']' -> if (bracket > 0) bracket--
                '{' -> brace++
                '}' -> if (brace > 0) brace-- else return i
                ',' -> if (paren == 0 && bracket == 0 && brace == 0) return i + 1
            }
            i++
        }
        return i
    }

    /** 跳过字符串字面量（含转义），返回引号后位置 */
    private fun skipString(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length && source[i] != '"') {
            if (source[i] == '\\' && i + 1 < source.length) i += 2 else i++
        }
        return if (i < source.length) i + 1 else i
    }

    /** 无类型注解的 var 声明 → 在变量名后提示 checker 推导类型 */
    private fun collectVarTypeHints(source: String, inferredVars: Map<String, Type>, out: MutableList<InlayHint>, lineStarts: IntArray = intArrayOf()) {
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                c == '"' -> i = skipString(source, i)
                c == '/' && i + 1 < n && source[i + 1] == '/' ->
                    while (i < n && source[i] != '\n') i++
                c == 'v' && source.startsWith("var ", i) &&
                    (i == 0 || (!source[i - 1].isLetterOrDigit() && source[i - 1] != '_')) -> {
                    var p = i + 4
                    while (p < n && source[p] == ' ') p++
                    val start = p
                    while (p < n && (source[p].isLetterOrDigit() || source[p] == '_')) p++
                    val name = source.substring(start, p)
                    var q = p
                    while (q < n && source[q] == ' ') q++
                    if (q < n && source[q] == '=') {
                        inferredVars[name]?.let { t ->
                            out += InlayHint(
                                position = offsetToPosition(source, p, lineStarts),
                                label = ": " + t.render(),
                                kind = 1,
                            )
                        }
                    }
                    i = p
                }
                else -> i++
            }
        }
    }

    /** 名字前是否紧跟声明关键字（fun/struct/...）——用于区分声明与调用 */
    private fun isDeclContext(source: String, nameStart: Int): Boolean {
        var p = nameStart - 1
        while (p >= 0 && (source[p] == ' ' || source[p] == '\t')) p--
        val end = p + 1
        while (p >= 0 && source[p].isLetter()) p--
        return source.substring(p + 1, end) in DECL_CONTEXT_KEYWORDS
    }

    private data class CommentSpan(val line: Int, val col: Int, val text: String, val ownLine: Boolean)

    /** 采集 // 行注释（字符串字面量内不采集），记录是否独占一行 */
    private fun collectComments(src: String): List<CommentSpan> {
        val out = ArrayList<CommentSpan>()
        var i = 0
        var line = 1
        var col = 1
        var lineStart = true
        while (i < src.length) {
            val c = src[i]
            when {
                c == '"' -> {
                    i++; col++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\' && i + 1 < src.length) { i += 2; col += 2 }
                        else { i++; col++ }
                    }
                    if (i < src.length) { i++; col++ }
                    lineStart = false
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    val cl = line; val cc = col
                    val owner = lineStart
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != '\n') { sb.append(src[i]); i++; col++ }
                    out += CommentSpan(cl, cc, sb.toString(), owner)
                    lineStart = false
                }
                c == '\n' -> { i++; line++; col = 1; lineStart = true }
                c == ' ' || c == '\t' -> { i++; col++ }
                else -> { lineStart = false; i++; col++ }
            }
        }
        return out
    }

    /** 核心格式化：token 流 + 注释合并，重排缩进/换行/注解异行 */
    private fun formatSource(src: String): String {
        val tokens = Lexer(src, "fmt").tokenize().filter { it.kind != Kind.EOF }
        val comments = collectComments(src)

        val out = StringBuilder()
        var indent = 0
        var prev: Token? = null
        var needIndent = false

        fun newline() {
            while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
            out.append('\n')
            needIndent = true
        }

        fun emit(text: String, spaceBefore: Boolean) {
            if (needIndent) {
                repeat(indent) { out.append("    ") }
                needIndent = false
            } else if (spaceBefore && out.isNotEmpty() && out.last() != ' ' && out.last() != '\n') {
                out.append(' ')
            }
            out.append(text)
        }

        var ti = 0
        var ci = 0
        while (ti < tokens.size || ci < comments.size) {
            val t = if (ti < tokens.size) tokens[ti] else null
            val c = if (ci < comments.size) comments[ci] else null
            val takeComment = when {
                c == null -> false
                t == null -> true
                c.line < t.line -> true
                c.line == t.line && c.col < t.col -> true
                else -> false
            }

            if (takeComment) {
                val cm = c!!
                if (cm.ownLine) {
                    if (out.isNotEmpty() && out.last() != '\n') newline()
                    emit(cm.text, false)
                    newline()
                } else {
                    emit(cm.text, true)
                    newline()
                }
                ci++
                continue
            }

            val tk = t!!
            when (tk.kind) {
                Kind.LBRACE -> {
                    emit("{", prev != null && spaceBetween(prev, tk))
                    if (ti + 1 < tokens.size && tokens[ti + 1].kind == Kind.RBRACE) {
                        out.append('}')
                        prev = tokens[ti + 1]
                        ti++
                    } else {
                        indent++
                        newline()
                        prev = tk
                    }
                }
                Kind.RBRACE -> {
                    if (indent > 0) indent--
                    if (out.isNotEmpty() && out.last() != '\n') newline()
                    emit("}", false)
                    newline()
                    prev = tk
                }
                Kind.SEMI -> {
                    emit(";", false)
                    newline()
                    prev = tk
                }
                Kind.AT -> {
                    emit("@", prev != null && spaceBetween(prev, tk))
                    if (ti + 1 < tokens.size && tokens[ti + 1].kind == Kind.IDENT) {
                        out.append(tokens[ti + 1].text)
                        prev = tokens[ti + 1]
                        ti++
                    } else {
                        prev = tk
                    }
                    if (ti + 1 < tokens.size && (tokens[ti + 1].kind in FORMAT_DECL_KINDS || tokens[ti + 1].kind == Kind.AT)) {
                        newline()
                    }
                }
                else -> {
                    emit(tk.text, prev != null && spaceBetween(prev, tk))
                    prev = tk
                }
            }
            ti++
        }

        while (out.isNotEmpty() && (out.last() == ' ' || out.last() == '\n')) out.setLength(out.length - 1)
        out.append('\n')
        return out.toString()
    }

    /** 两 token 之间是否需要空格 */
    private fun spaceBetween(a: Token, b: Token): Boolean {
        val ak = a.kind
        val bk = b.kind
        // M1：区间 .. 两侧不加空格（保持 0..3 风格）
        if (ak == Kind.RANGE || bk == Kind.RANGE) return false
        if (bk == Kind.RPAREN || bk == Kind.RBRACKET || bk == Kind.COMMA || bk == Kind.SEMI ||
            bk == Kind.DOT || bk == Kind.COLON || bk == Kind.COLON2) return false
        if (ak == Kind.LPAREN || ak == Kind.LBRACKET || ak == Kind.AT || ak == Kind.DOT || ak == Kind.COLON2) return false
        if (bk == Kind.LPAREN) {
            return !(ak == Kind.IDENT || ak == Kind.RPAREN || ak == Kind.RBRACKET)
        }
        return true
    }

    // ───────── completion：补全 ─────────

    fun completionFor(uri: String, position: Position): CompletionList {
        val state = docs[uri] ?: return CompletionList(true, emptyList())
        val offset = positionToOffset(state.source, position, state.lineStarts) ?: return CompletionList(true, emptyList())

        // Unicode 符号补全：\xxx 触发（如 \forall、\!=、\->）
        val unicode = unicodeTriggerAtOffset(state.source, offset)
        if (unicode != null) {
            val (uprefix, bsOffset) = unicode
            val editRange = offsetToRange(state.source, bsOffset, offset)
            val symItems = UNICODE_SYMBOLS
                .filter { uprefix.isEmpty() || it.first.startsWith(uprefix) }
                .map { (trig, sym) ->
                    CompletionItem(
                        label = "\\" + trig,
                        kind = CompletionItemKind.Operator,
                        detail = sym,
                        insertText = sym,
                        sortText = "u_" + trig,
                        filterText = trig,
                        textEdit = TextEdit(editRange, sym),
                    )
                }
            // L8：\xxx 匹配到符号才独占补全；否则继续普通补全（不屏蔽关键字/符号）
            if (symItems.isNotEmpty()) return CompletionList(isIncomplete = false, items = symItems)
        }

        // 成员上下文补全：. 或 :: 之后按 receiver 类型过滤（字段/方法/构造子）
        val memberCtx = memberContextAt(state.source, offset)
        if (memberCtx != null) {
            val members = memberCompletion(uri, memberCtx.first, memberCtx.second)
            if (members.isNotEmpty()) return CompletionList(isIncomplete = false, items = members)
            // H3：. 之后即使 receiver 类型推不出，也不再 fall through 到全局补全（避免补全 fun/Nat 等）
            return CompletionList(isIncomplete = false, items = emptyList())
        }

        val prefix = wordPrefixAtOffset(state.source, offset)
        val items = mutableListOf<CompletionItem>()
        // 有前缀时：用户在续写已有名字，已声明符号应排在关键字之前；空行则先给声明类关键字
        val symbolFirst = prefix.isNotEmpty()

        // 关键字补全
        items += buildKeywordCompletions(prefix, if (symbolFirst) "1" else "0")

        // 已声明符号补全
        val symbols = collectSymbols(uri)
        for (s in symbols) {
            if (prefix.isEmpty() || s.name.startsWith(prefix, ignoreCase = true)) {
                items += CompletionItem(
                    label = s.name,
                    kind = kindOf(s.variety),
                    detail = s.detail,
                    sortText = (if (s.name == prefix) "0" else "1") + (if (symbolFirst) "0" else "1") + "_" + s.name,
                )
            }
        }

        // prelude 补全（内建类型/函数/stdlib 符号）
        for (item in preludeItems) {
            if (prefix.isEmpty() || item.label.startsWith(prefix, ignoreCase = true)) {
                val mrank = if (item.label == prefix) "0" else "1"
                items += item.copy(sortText = mrank + "2_" + item.label)
            }
        }

        return CompletionList(isIncomplete = false, items = items)
    }

    // ───────── documentSymbol ─────────

    fun documentSymbols(uri: String): List<DocumentSymbol> {
        if (uri !in docs) return emptyList()
        val all = collectSymbols(uri)

        fun rangeOf(s: SymbolRef): Range = Range(
            Position(s.lineStart - 1, s.colStart - 1),
            Position(s.lineEnd - 1, (s.colStart + s.name.length - 1).coerceAtLeast(s.colStart)),
        )
        fun nodeOf(s: SymbolRef): DocumentSymbol = DocumentSymbol(
            name = s.name,
            kind = symbolKindOf(s.variety),
            range = rangeOf(s),
            selectionRange = rangeOf(s),
            detail = s.detail,
        )

        // 按 parent 字段构建层级：顶层节点（parent == null）直接返回，子节点挂到对应父节点下
        val kidsByParent = LinkedHashMap<String, MutableList<DocumentSymbol>>()
        for (s in all) {
            if (s.parent != null) {
                kidsByParent.getOrPut(s.parent) { mutableListOf() }.add(nodeOf(s))
            }
        }

        return all.filter { it.topLevel }.map { s ->
            val kids = kidsByParent[s.name]
            if (kids == null || kids.isEmpty()) nodeOf(s)
            else nodeOf(s).copy(children = kids)
        }
    }

    // ───────── 工具函数 ─────────

    private fun positionToOffset(source: String, position: Position, lineStarts: IntArray? = null): Int? {
        // H1/N1：用 lineStarts 定位（DocState 已缓存，直接复用，避免每次 O(n) 重建）
        if (lineStarts != null && lineStarts.isNotEmpty()) {
            if (position.line < 0 || position.line >= lineStarts.size) return null
            return (lineStarts[position.line] + position.character).coerceIn(0, source.length)
        }
        val starts = buildLineStarts(source)
        if (position.line < 0 || position.line >= starts.size) return null
        return (starts[position.line] + position.character).coerceIn(0, source.length)
    }

    private fun offsetToLineCol(source: String, offset: Int): String {
        val o = offset.coerceIn(0, source.length)
        var line = 1
        var col = 1
        for (i in 0 until o) {
            if (source[i] == '\n') { line++; col = 1 } else col++
        }
        return "$line:$col"
    }

    /** offset 转 0-based Position；给定 lineStarts 时二分（O(log n)），否则线性扫描（O(offset) 兜底） */
    private fun offsetToPosition(source: String, offset: Int, lineStarts: IntArray? = null): Position {
        val o = offset.coerceIn(0, source.length)
        if (lineStarts != null && lineStarts.isNotEmpty()) {
            var line = lineStarts.binarySearch(o)
            if (line < 0) line = -line - 2
            return Position(line, o - lineStarts[line])
        }
        var line = 0
        var col = 0
        for (i in 0 until o) {
            if (source[i] == '\n') { line++; col = 0 } else col++
        }
        return Position(line, col)
    }

    /** 每行首字符 offset 表（P6：供 offset→Position 二分） */
    private fun buildLineStarts(source: String): IntArray {
        val starts = mutableListOf(0)
        for (i in source.indices) if (source[i] == '\n') starts += i + 1
        return starts.toIntArray()
    }

    /** [start, end) 转 Range（0-based） */
    private fun offsetToRange(source: String, start: Int, end: Int): Range =
        Range(offsetToPosition(source, start), offsetToPosition(source, end))

    /**
     * 检测 Unicode 符号补全触发：返回 \ 之后的前缀文本 + \ 的 offset；
     * 无触发返回 null。允许 \ 与光标之间出现标识符及符号字符 ! < > - =。
     */
    private fun unicodeTriggerAtOffset(source: String, offset: Int): Pair<String, Int>? {
        if (offset <= 0) return null
        var p = offset - 1
        while (p >= 0 && (source[p] == '_' || source[p].isLetterOrDigit() ||
                source[p] == '!' || source[p] == '<' || source[p] == '>' ||
                source[p] == '-' || source[p] == '=')) p--
        if (p < 0 || source[p] != '\\') return null
        return source.substring(p + 1, offset) to p
    }

    private fun wordAtOffset(source: String, offset: Int): String? {
        if (offset < 0 || offset >= source.length) return null
        var start = offset
        while (start >= 0 && (source[start].isLetterOrDigit() || source[start] == '_')) start--
        start++
        var end = offset
        while (end < source.length && (source[end].isLetterOrDigit() || source[end] == '_')) end++
        return if (start >= end) null else source.substring(start, end)
    }

    private fun wordPrefixAtOffset(source: String, offset: Int): String {
        var start = offset - 1
        while (start >= 0 && (source[start].isLetterOrDigit() || source[start] == '_')) start--
        return source.substring(start + 1, offset)
    }

    private fun kindOf(variety: String): CompletionItemKind = when (variety) {
        "fun" -> CompletionItemKind.Function
        "struct" -> CompletionItemKind.Struct
        "enum" -> CompletionItemKind.Enum
        "class" -> CompletionItemKind.Interface
        "impl" -> CompletionItemKind.Interface
        "type" -> CompletionItemKind.Reference
        "prop" -> CompletionItemKind.Function
        "field" -> CompletionItemKind.Field
        "ctor" -> CompletionItemKind.Constructor
        else -> CompletionItemKind.Variable
    }

    private fun symbolKindOf(variety: String): SymbolKind = when (variety) {
        "fun" -> SymbolKind.Function
        "struct" -> SymbolKind.Struct
        "enum" -> SymbolKind.Enum
        "class" -> SymbolKind.Class
        "impl" -> SymbolKind.Class
        "type" -> SymbolKind.TypeParameter
        "prop" -> SymbolKind.Function
        "field" -> SymbolKind.Field
        "ctor" -> SymbolKind.Constructor
        else -> SymbolKind.Variable
    }

    private fun buildKeywordCompletions(prefix: String, prio: String = "0"): List<CompletionItem> {
        return KEYWORDS
            .filter { prefix.isEmpty() || it.first.startsWith(prefix, ignoreCase = true) }
            .map { (kw, snippet) ->
                val mrank = if (kw == prefix) "0" else "1"   // 精确匹配排在所有前缀匹配之前
                CompletionItem(
                    label = kw,
                    kind = CompletionItemKind.Keyword,
                    detail = "keyword",
                    insertText = snippet,
                    insertTextFormat = 2,   // Snippet：${n} 占位、$0 最终光标——长按可展开为完整语句骨架
                    sortText = mrank + prio + "_" + kw,
                )
            }
    }

    companion object {
        /** 语言关键字 + 补全插入片段（$ 是 LSP snippet 占位） */
        val KEYWORDS: List<Pair<String, String>> = listOf(
            "fun" to "fun \${1:name}(): \${2:Null} { \${0} }",
            "struct" to "struct \${1:Name}(\${2:field}: \${3:Nat})",
            "enum" to "enum \${1:Name} { \${0} }",
            "class" to "class \${1:Name} { \${0} }",
            "impl" to "impl \${1:Type} for \${2:Self} { \${0} }",
            "var" to "var \${1:name} = \${0}",
            "type" to "type \${1:Name} = \${0}",
            "def" to "def \${1:name}<a, b>",
            "if" to "if (\${1:condition}) { \${0} }",
            "else" to "else { \${0} }",
            "when" to "when (\${1:x}) { \${0} }",
            "return" to "return \${0}",
            "unchecked" to "unchecked.\${0}",
            "is" to "is \${0}",
            "by" to "by \${0}",
        )
        // ───────── prelude 静态数据 ─────────

        /** typeDefinition 目标成员类型（类型声明 varieties） */
        val TYPE_VARIETIES: Set<String> = setOf("struct", "enum", "class", "type")

        // ───────── Unicode 符号补全（\xxx 触发）─────────
        /** 触发串（不含反斜杠）→ 目标 Unicode 符号 */
        val UNICODE_SYMBOLS: List<Pair<String, String>> = listOf(
            // 单词系：量词 / 属于 / 逻辑
            "forall" to "∀",      // 全称量词
            "exists" to "∃",      // 存在量词
            "exists1" to "∃!",    // 唯一存在
            "exists!" to "∃!",    // 唯一存在（别名）
            "in" to "∈",          // 属于
            "land" to "∧",        // 与
            "lor" to "∨",         // 或
            "lnot" to "¬",        // 否定
            "not" to "¬",         // 否定（别名）
            "implies" to "→",     // 蕴含
            "to" to "→",          // 蕴含（别名）
            "iff" to "↔",         // 等价
            "equiv" to "↔",       // 等价（别名）
            "ne" to "≠",          // 不等于
            "neq" to "≠",         // 不等于（别名）
            "le" to "≤",          // 小于等于
            "ge" to "≥",          // 大于等于
            "top" to "⊤",         // 真
            "bot" to "⊥",         // 假
            "because" to "∵",     // 因为
            "therefore" to "∴",   // 所以
            // 符号系：蕴含 / 等价 / 否定 / 不等于 / 比较
            "->" to "→",
            "<->" to "↔",
            "!" to "¬",
            "!=" to "≠",
            ">=" to "≥",
            "<=" to "≤",
        )

        // ───────── codeAction：全角 → 半角 ─────────
        /** 中文全角符号 → 英文半角（与 Lexer 报错表一致） */
        val FULLWIDTH_TO_HALF: Map<Char, String> = mapOf(
            '（' to "(", '）' to ")", '【' to "[", '】' to "]",
            '＜' to "<", '＞' to ">", '＝' to "=", '，' to ",",
            '；' to ";", '：' to ":", '＂' to "\"", '＇' to "'",
            '！' to "!", '＆' to "&", '｜' to "|",
        )

        /** 声明关键字：inlayHint 据此区分声明头与调用点 */
        val DECL_CONTEXT_KEYWORDS: Set<String> = setOf(
            "fun", "struct", "enum", "class", "impl", "type", "def", "var",
        )

        // ───────── semanticTokens legend ─────────
        /** 语义 token 类型 legend（顺序即索引） */
        val SEMANTIC_TOKEN_TYPES: List<String> = listOf(
            "keyword",     // 0
            "type",        // 1
            "function",    // 2
            "parameter",   // 3
            "variable",    // 4
            "property",    // 5
            "enumMember",  // 6
            "number",      // 7
            "string",      // 8
            "operator",    // 9
            "class",       // 10 ← 类型类（class）
            "enum",        // 11 ← 枚举（enum）
            "struct",      // 12 ← 结构体（struct）
            "decorator",   // 13 ← 注解（@mut / @impure）
        )

        /** 语义 token 修饰符 legend（当前为空） */
        val SEMANTIC_TOKEN_MODIFIERS: List<String> = emptyList()

        /** stdlib 容器 / 代数数据类型名（不含 BASE_TYPES 已含的） */
        val STDLIB_TYPES: List<String> = listOf("List", "Optional", "Result", "Set", "Tuple")

        /** 枚举 / 容器构造子名 */
        val STDLIB_CTORS: List<String> = listOf(
            "Some", "None", "Ok", "Err", "Cons", "Nil",
            "True", "False",
        )

        /** 内建函数 hover 文档（中文） */
        val BUILTIN_FUN_DOCS: Map<String, String> = mapOf(
            "print" to "输出任意值到标准输出（@impure）",
            "toStr" to "将任意值转换为字符串 Str",
            "concat" to "拼接两个字符串",
            "length" to "返回字符串或列表长度",
            "charAt" to "取字符串第 i 个字符（0-based）",
            "substring" to "取子串 [a, b)",
            "strCmp" to "字符串字典序比较，返回 Int（负/零/正）",
            "parseNat" to "从字符串解析 Nat，失败返回 None",
            "parseRat" to "从字符串解析 Rat，失败返回 None",
            "toRat" to "将 Int/Nat 转为 Rat",
            "abs" to "绝对值",
            "sqrt" to "平方根",
            "floor" to "向下取整",
            "ceil" to "向上取整",
            "pow" to "幂运算 x^y",
            "strUpper" to "字符串转大写",
            "strLower" to "字符串转小写",
            "trim" to "去除两端空白",
            "startsWith" to "是否以给定前缀开头",
            "endsWith" to "是否以给定后缀结尾",
            "readLine" to "读取 stdin 一行（@impure @async，返回 Result）",
            "readFile" to "读取文件（@impure @async，返回 Result）",
            "writeFile" to "写入文件（@impure @async，返回 Result）",
            "getArgs" to "获取命令行参数，返回 List[Str]（@impure）",
            "sleep" to "挂起当前 Task 指定毫秒数（@impure @async）",
            "appendFile" to "追加内容到文件（@impure @async）",
            "fileExists" to "检查文件是否存在（@impure）",
            "readDir" to "读取目录内容（@impure @async）",
            "timeNow" to "当前时间戳（Nat，毫秒，@impure）",
            "rand" to "返回 [0,1) 的伪随机浮点数（@impure）",
            "emptyTuple" to "空元组 EmptyTuple",
            "singleTuple" to "单元素元组 SingleTuple[T]",
        )

        /** 关键字 hover 文档 */
        val KEYWORD_DOCS: Map<String, String> = mapOf(
            "fun" to "**fun** \u2014 declare a function. Pure by default.\n\n```subl\nfun add(a: Nat, b: Nat): Nat = a + b\n```",
            "struct" to "**struct** \u2014 declare an immutable data structure.\n\n```subl\nstruct User(name: Str, age: Nat)\n```",
            "enum" to "**enum** \u2014 declare an algebraic data type.\n\n```subl\nenum Color { Red, Green, Blue }\n```",
            "class" to "**class** \u2014 declare a type class (like Rust trait).\n\n```subl\nclass Show { fun show(): Str }\n```",
            "impl" to "**impl** \u2014 implement a type class or extension method.\n\n```subl\nimpl Show for User { fun show(): Str = \"…\" }\n```",
            "var" to "**var** \u2014 declare a variable. Mutable requires `@mut`.",
            "when" to "**when** \u2014 pattern matching with exhaustiveness checking.",
            "type" to "**type** \u2014 type alias.\n\n```subl\ntype Name[T] = T\n```",
            "def" to "**def** \u2014 custom proposition (atomic predicate).",
        )
    }
}