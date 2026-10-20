package sugared.functor.parser

import sugared.functor.ast.*
import sugared.functor.lexer.Kind
import sugared.functor.lexer.Lexer
import sugared.functor.lexer.Token

class ParseFailure(val fileName: String, val tok: Token, message: String) :
    RuntimeException("[语法][$fileName:${tok.line}:${tok.col}] 期望$message，实际'${tok.text}'")

/**
 * 递归下降。文法见 doc/语言语法.md。
 * 邻接规则（核心消歧）：
 *  - 紧贴前 token 的 `<` = 实例化（命题项 p<v> / 显式供给），postfix 消化；
 *  - 带空格的 `<` = 比较中缀函数。
 * 声明结构位（形参表后、返回类型后）的 `<...>` 按位置识别为上文/下文。
 * 等式用 `==`；单 `=` 只作赋值。`unchecked.axiom[q]` 为命题逃逸。
 */
class Parser(
    private val toks: List<Token>,
    private val fileName: String,
    private val scriptMode: Boolean = false,
    /** 全局类型推导（idea doc/全局类型推导.md）：允许省略形参/字段类型（填 InferMarker，由解释器的
     *  GlobalInference 在 Checker 前替换）。仅脚本模式解析入口开启；编译模式（parseSource）保持强制标注。 */
    private val allowUntyped: Boolean = false,
) {
    private var pos = 0
    // HKT 标准库简写判定用：本文件已解析的类型类名集合（impl Trait[…] 头须是类型类才当简写，见 parseImpl）
    private val classNames = LinkedHashSet<String>()
    // 命题位 if-then-else 的停止词：`then`/`else` 是 IDENT 非关键词，解析子表达式时以它们作停止边界
    private var stopIdents: Set<String> = emptySet()

/** ifis 智能解构：解析 if 条件期间为真——条件位的 `a is Ctor(...)` 按构造子模式解析（哨兵 IsPatExpr），
 *  脱糖为 when（普通 if 条件的布尔语义不变；when 分支体里的 `is Ctor(...)` 仍按下一分支模式起始切分） */
private var ifIsCond: Boolean = false
    // P2：提示/补充留痕改为实例字段——消除 companion 全局可变状态（并发/嵌套解析互相污染）。
    private val hints = mutableListOf<Pair<String, String>>()
    private val supplements = mutableListOf<Triple<String, String, String>>()
    fun takeHints(): List<Pair<String, String>> {
        // distinct：语句级声明消歧可能回溯导致同一表达式被解析两次 → 去重
        val r = hints.distinct(); hints.clear(); return r
    }
    fun takeSupplements(): List<Triple<String, String, String>> {
        val r = supplements.distinct(); supplements.clear(); return r
    }

    companion object {
        // 指导思路：`=` 命题等式、`:` 类型测（均可中缀）；`==` `!=` 是 Bool 函数，
        // v1.1 Kotlin 化：EQ/NEQ 允许中缀（a == b / a != b，值相等），前缀 ==(a,b) 仍保留。
        val binops = setOf(
            Kind.PLUS, Kind.MINUS, Kind.STAR, Kind.SLASH, Kind.PERCENT, Kind.POW, Kind.ASSIGN, Kind.COLON,
            Kind.LE, Kind.GE, Kind.AMP, Kind.PIPE, Kind.ARROW, Kind.IFF,
            Kind.EQ, Kind.NEQ,
            Kind.LAND, Kind.LOR, Kind.IMPLIES, Kind.EQUIV,
        )
        // P0 补全（决策 88）：可作函数名前缀调用的运算符 token（`+(1,2)` 形态）。
        // 不含 MINUS（一元负优先）、symbolCallStart（`==(...)` 等已走 SymbolCallExpr 内建符号路径）。
        private val opPrefixKinds = setOf(
            Kind.PLUS, Kind.STAR, Kind.SLASH, Kind.PERCENT, Kind.AMP, Kind.PIPE, Kind.ARROW, Kind.IFF,
        )
        private val symbolCallStart = setOf(Kind.LT, Kind.GT, Kind.EQ, Kind.NEQ, Kind.LE, Kind.GE)
        private val asciiQuant = mapOf("forall" to "∀", "exists" to "∃", "exists1" to "∃!")
        // P23：def 声明里命题名的合法 token（标识符/符号/运算符；拒绝 INT/STR/字面量/括号/EOF 等）
        private val defNameKinds = setOf(
            Kind.IDENT, Kind.SYMBOL, Kind.ASSIGN, Kind.IN,
            Kind.PLUS, Kind.MINUS, Kind.STAR, Kind.SLASH, Kind.PERCENT,
            Kind.EQ, Kind.NEQ, Kind.LE, Kind.GE, Kind.AMP, Kind.PIPE,
            Kind.ARROW, Kind.IFF, Kind.LT, Kind.GT,
        )
    }

    private fun cur(): Token = toks[pos]
    private fun peek(kind: Kind): Boolean = cur().kind == kind
    private fun next(): Token = toks[pos++]
    private fun at(kind: Kind): Token {
        val t = cur()
        if (t.kind != kind) throw ParseFailure(fileName, t, kind.name)
        return next()
    }

    /** 名字位：ASCII 标识符、符号名（SYMBOL），以及运算符符号（仿 Scala：`fun +(a,b)`） */
    private fun nameToken(): String {
        val t = cur()
        return when (t.kind) {
            Kind.IDENT, Kind.SYMBOL,
            Kind.PLUS, Kind.MINUS, Kind.STAR, Kind.SLASH, Kind.PERCENT,
            Kind.EQ, Kind.NEQ, Kind.LE, Kind.GE, Kind.AMP, Kind.PIPE,
            Kind.ARROW, Kind.IFF, Kind.LT, Kind.GT,
            -> next().text
            else -> throw ParseFailure(fileName, t, "标识符")
        }
    }

    // ============ 片段解析（注解处理器等宿主用：单条声明 / 函数体 / 语句序列） ============

    /** 片段解析：单条声明（fun / struct / enum / class / impl / type 声明）。
     *  解析后要求整段耗尽（多余内容报语法错），供 parseDeclSnippet 复用。 */
    fun snippetDecl(): Decl {
        val d = parseDecl()
        requireSnippetEof("声明")
        return d
    }

    /** 片段解析：函数体——块体「{ 语句… }」、裸表达式（表达式体右值，等号可写可不写）。
     *  体分支与 parseFun 保持一致（「= {…}」混写禁止）。 */
    fun snippetFunBody(): Expr {
        val body = when {
            peek(Kind.ASSIGN) && toks.getOrNull(pos + 1)?.kind == Kind.LBRACE ->
                throw ParseFailure(fileName, toks[pos + 1],
                    "函数体不能 = 后接花括号混写：单表达式请去掉花括号，块体请去掉等号")
            peek(Kind.ASSIGN) -> { pos++; parseExpr(angle = false) }
            peek(Kind.LBRACE) -> parseBlock()
            else -> parseExpr(angle = false)
        }
        requireSnippetEof("函数体")
        return body
    }

    /** 片段解析：语句序列（零条或多条，分号/换行分隔）至 EOF。 */
    fun snippetStmts(): List<Stmt> {
        skipSemis()
        val out = ArrayList<Stmt>()
        while (!peek(Kind.EOF)) {
            out += parseStmt()
            skipSemis()
        }
        return out
    }

    /** 片段耗尽检查：跳过分号后必须 EOF，否则报「片段后面有多余内容」。 */
    private fun requireSnippetEof(what: String) {
        skipSemis()
        if (!peek(Kind.EOF)) throw ParseFailure(fileName, cur(), "$what 片段后面有多余内容")
    }

    fun parseFile(): FileAst {
        val entries = ArrayList<Entry>()
        while (true) {
            skipSemis()
            if (peek(Kind.EOF)) break
            val e = parseEntry()
            // 无类型类 impl（扩展方法，承担重载职责）：成员脱糖为带 self 首参的自由函数声明（AST 级展开，后续全链路零感知）
            if (e is DeclEntry && e.decl is ImplDecl) {
                val impl = e.decl as ImplDecl
                if (impl.trait == null) {
                    // self 里的 kind 占位（`List[a]` 的 a）提升为成员的自由函数类型参数注（尾部追加，保留成员自带理论参数）
                    val phs = collectImplPlaceholders(impl.self)
                    entries += impl.members.map { m ->
                        DeclEntry(m.copy(
                            theory = m.theory + phs.map { TypeParam(it, null) },
                            params = listOf(Param("self", impl.self)) + m.params))
                    }
                    continue
                }
            }
            entries += e
        }
        return FileAst(entries)
    }

    private fun skipSemis() { while (peek(Kind.SEMI)) pos++ }

    /** 无类型类 impl 脱糖：收集 self 类型里的 kind 占位（零实参小写裸名，如 `List[a]` 的 a），按出现序去重 */
    private fun collectImplPlaceholders(t: Type): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(tp: Type) {
            when (tp) {
                is NamedType ->
                    if (tp.args.isEmpty() && tp.name.firstOrNull()?.isLowerCase() == true) out.add(tp.name)
                    else tp.args.forEach { walk(it) }
                is TupleType -> tp.items.forEach { walk(it) }
                is FunType -> { tp.params.forEach { walk(it) }; walk(tp.ret) }
                is UnionType -> tp.variants.forEach { walk(it) }
                else -> {}
            }
        }
        walk(t)
        return out.toList()
    }

    /**
     * 决策 74（Kotlin 化收口）：**顶层只允许声明**，执行代码必须写进 `fun main()`。
     * 凝华脚本模式（类 kts，顶层即主序列）列为将来特性，当前语法层直接拒绝。
     */
    private fun parseEntry(): Entry = when (cur().kind) {
        Kind.FUN, Kind.CLASS, Kind.STRUCT, Kind.ENUM, Kind.IMPL, Kind.TYPE, Kind.DEF -> DeclEntry(parseDecl())
        Kind.AT -> {
            val save = pos
            val anns = parseAnnotations()
            when (cur().kind) {
                Kind.FUN -> DeclEntry(parseFun(anns))
                // 注解名字空间由宿主/注解处理器定义（root doc）：struct/class 上的注解一律放行，由后续处理器解释
                Kind.STRUCT -> DeclEntry(parseStruct(anns))
                Kind.CLASS -> DeclEntry(parseClass(anns))
                else -> if (scriptMode) { pos = save; StmtEntry(parseStmt()) } else { pos = save; throw noTopLevel() }
            }
        }
        else -> if (scriptMode) StmtEntry(parseStmt()) else throw noTopLevel()
    }

    private fun noTopLevel(): ParseFailure =
        ParseFailure(fileName, cur(), "顶层只能写声明，执行代码请放进 `fun main()`（脚本模式后续支持）")

    fun parseDecl(): Decl = when (cur().kind) {
        Kind.FUN -> parseFun(emptyList())
        Kind.CLASS -> parseClass(emptyList())
        Kind.STRUCT -> parseStruct(emptyList())
        Kind.ENUM -> parseEnum()
        Kind.IMPL -> parseImpl()
        Kind.TYPE -> parseTypeAlias()
        Kind.DEF -> parsePropDef()
        else -> throw ParseFailure(fileName, cur(), "声明（顶层只能写 fun/struct/enum/class/impl/type 声明；执行语句请放进 fun main() { … } 里）")
    }

    /** 类型别名（决策 30）：`type Name[T] = Type`，右值 v1 仅具名/应用类型 */
    private fun parseTypeAlias(): TypeAliasDecl {
        val sl = cur().line; val sc = cur().col
        at(Kind.TYPE)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        at(Kind.ASSIGN)
        val target = parseType()
        return TypeAliasDecl(name, theory, target, "$sl:$sc")
    }

    /** 自定义命题（根文档）：`def =<a,b>` / `def ≠<a,b> ↔ ¬<a = b>`。
     *  名字为任意符号/标识符 token 的原文；`<参数>` 是名字列表（不约束类型）；`↔` 后是等价命题表达式。 */
    private fun parsePropDef(): PropDefDecl {
        val sl = cur().line; val sc = cur().col
        at(Kind.DEF)
        if (cur().kind !in defNameKinds)
            throw ParseFailure(fileName, cur(), "命题名（标识符或符号，如 =、≠、∈）")
        val name = next().text
        val params = ArrayList<String>()
        if (peek(Kind.LT)) {
            next()
            while (peek(Kind.IDENT)) {
                params += next().text
                if (peek(Kind.COMMA)) next() else break
            }
            at(Kind.GT)
        }
        var equiv: Expr? = null
        if (peek(Kind.IFF) || peek(Kind.EQUIV)) {   // ↔ 词法是 EQUIV；<=> 用 IFF
            next()
            equiv = if (peek(Kind.LT)) parseAngleList().single() else parseExpr(angle = false)
        }
        return PropDefDecl(name, params, equiv, "$sl:$sc")
    }

    private fun parseAnnotations(): List<String> {
        val out = ArrayList<String>()
        while (peek(Kind.AT)) { pos++; out += at(Kind.IDENT).text }
        return out
    }

    private fun parseFun(anns: List<String>): FunDecl {
        at(Kind.FUN)
        // 报错增强：函数声明位置（W-UNUSED/位置诊断用）——取函数名 token 而非 fun 关键字：
        // rename/definition 靠 pos 定位声明，指向 fun 会误改 fun 关键字本身。
        val nameTok = cur()
        val name = nameToken()
        val sl = nameTok.line; val sc = nameTok.col
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        val params = if (peek(Kind.LPAREN)) parseParams() else emptyList()
        // v3.0：`fun f<score <= 100>`——约束形参表（IDENT 比较运算）与命题上下文 `<p>` 分派；
        // 约束表仅解析登记（V1 不校验），语义由主人拍板（依赖类型方向）。
        var constraints: List<Expr> = emptyList()
        val preps = if (peek(Kind.LT)) {
            if (constraintListAhead()) { constraints = parseConstraintList(); emptyList() }
            else parseAngleList()
        } else emptyList()
        // 用户拍板（v1.1）：`:` 是上下（前后置）上下文的分界——
        // 冒号**前**的 `<...>` = 上文（前置，调用需已证明）；返回类型**后**的 `<...>` = 下文（后置，调用产出）。
        // 返回类型可省略：冒号后直接 `<...>` / 无返回类型（此前后置须写 `: Null <p>`，现可写 `: <p>`）。
        var ret: Type? = null
        var posts: List<Expr> = emptyList()
        if (peek(Kind.COLON)) {
            pos++
            if (peek(Kind.LT)) posts = parseAngleList()        // `: <q>`：无返回类型的后置上下文
            else {
                ret = parseType()
                if (peek(Kind.LT)) posts = parseAngleList()    // `: T <q>`：旧形态
            }
        }
        // 决策 73（Kotlin 铁律）：函数体两形态互斥——`= 表达式` 或 `{ 语句… }`；
        // `=` 后直接跟 `{` 是混用，报错指向改法。
        val body = when {
            peek(Kind.ASSIGN) && toks.getOrNull(pos + 1)?.kind == Kind.LBRACE ->
                throw ParseFailure(fileName, toks[pos + 1],
                    "函数体不能 `= {…}` 混写：单表达式请去掉花括号写 `= 表达式`，块体请去掉等号写 `{ … }`")
            peek(Kind.ASSIGN) -> { pos++; parseExpr(angle = false) }
            peek(Kind.LBRACE) -> parseBlock()
            else -> null
        }
        return FunDecl(name, anns, theory, params, preps, ret, posts, body, constraints = constraints, pos = "$sl:$sc")
    }

    /** v3.0：`<score <= 100>` 约束表开头判定——角括内第一项为 IDENT 且其后是（带空格的）比较/种类运算符。
     *  紧贴 `<`（如 `valid<7>` 实例化）不属约束表。 */
    private fun constraintListAhead(): Boolean {
        val nm = toks.getOrNull(pos + 1)
        val op = toks.getOrNull(pos + 2)
        return nm?.kind == Kind.IDENT && op != null && op.precededBySpace &&
            op.kind in setOf(Kind.LE, Kind.GE, Kind.EQ, Kind.NEQ) || (op != null && op.kind == Kind.IDENT && op.text == "in")     // P51：in 去关键字化后按文本识别；v3.0：LT/GT 排除——约束表与 `<a < b>` 上文命题歧义
    }

    /** v3.0：解析 `score <= 100, n > 0` 约束表（`<...>` 内，逗号分隔）——只登记不校验 */
    private fun parseConstraintList(): List<Expr> {
        at(Kind.LT)
        val out = ArrayList<Expr>()
        while (true) {
            val cnameTok = cur()
            val cname = nameToken()   // 约束携带的（隐式）参数名
            val opTok = cur()
            pos++
            val rhs = parseExpr(angle = true)
            val cpos = "${cnameTok.line}:${cnameTok.col}"
            // P51：in 为普通函数——约束项也按调用形态登记（命题层 PAtom("in") 与旧 BinExpr 同构）
            out += if (opTok.text == "in") CallExpr(NameRef("in"), listOf(NameRef(cname, cpos), rhs))
            else BinExpr(opTok.text, NameRef(cname, cpos), rhs, "$cname$opTok")
            if (peek(Kind.COMMA)) { pos++; if (peek(Kind.GT)) break; continue }
            break
        }
        at(Kind.GT)
        return out
    }

    private fun parseTheory(): List<TheoryParam> {
        at(Kind.LBRACKET)
        val out = ArrayList<TheoryParam>()
        while (true) {
            if (peek(Kind.AT)) { pos++; out += PropParam(nameToken()) }
            else {
                val n = nameToken()
                // 高阶类型（主人拍板，Scala 风）：`[F[_]]` → arity=1；`[F[_, _]]` → arity=2。
                // `[]` 内只允许 `_`（占位）与 `,`；`F` 是类型构造子形参，`_` 是 kind `*` 实参占位。
                var arity = 0
                if (peek(Kind.LBRACKET)) {
                    pos++
                    arity = 0
                    while (true) {
                        if (peek(Kind.IDENT) && cur().text == "_") { pos++; arity++ }
                        else throw ParseFailure(fileName, cur(), "期望构造子占位 _")
                        if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                        break
                    }
                    at(Kind.RBRACKET)
                }
                // HKT-4（《高阶类型.md》§8 路线，解禁）：kind 形参可带类型类约束——`[F[_]: Foldable]`。
                // 约束 c 是类型类名字符串（与普通形参 `[T: Show]` 同形）；arity>0 与约束不再互斥。
                // 语义：函数体内 F 的类型类方法走约束字典槽 d_<约束>_<F>；调用点按 tsub[F] 解出
                // 具体构造子并注入字典实参（Checker P6 通道 + CallCheck 约束解析）。
                val c = if (peek(Kind.COLON)) { pos++; nameToken() } else null
                out += TypeParam(n, c, arity)
            }
            if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
            break
        }
        at(Kind.RBRACKET)
        return out
    }

    private fun parseParams(): List<Param> {
        at(Kind.LPAREN)
        val out = ArrayList<Param>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                // 形参注解（如 @list 变长实参，Kotlin vararg 语义）：@ 前缀出现在名字位即注解列表，透传原名
                val anns = ArrayList<String>()
                while (peek(Kind.AT)) {
                    pos++; anns += at(Kind.IDENT).text
                }
                // P4（v2.1）：`fun f((a, b): Point)` 元组参数解构——LPAREN 紧跟在注解后即解构分量表
                val destruct = if (peek(Kind.LPAREN)) parseParamDestruct() else null
                val n = if (destruct != null) destruct.first() else nameToken()
                val ty = if (peek(Kind.COLON)) { pos++; parseType() }
                    else if (allowUntyped) InferMarker
                    else throw ParseFailure(fileName, toks[pos], "期望COLON")
                // O3（决策 69）：`= expr` 是字段默认值。解析层一律接受，合法性由 checker 判
                //（仅 struct 字段允许；函数形参带默认值报 E-DEFAULT-PARAM）
                val dft = if (peek(Kind.ASSIGN)) { pos++; parseExpr(angle = false) } else null
                out += Param(n, ty, anns, dft, destruct)
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    /** P4（v2.1）：元组参数解构分量表 `(a, b)`——LPAREN 未消费，读扁平分量名列表 */
    private fun parseParamDestruct(): List<String> {
        at(Kind.LPAREN)
        val out = ArrayList<String>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                out += nameToken()
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    /** P4（v2.1）：lambda 解构参数 `(a, b)` → 编码特殊参数名 "(a,b)"（LPAREN 未消费） */
    private fun parseLambdaDestructName(): String {
        at(Kind.LPAREN)
        val parts = ArrayList<String>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                parts += nameToken()
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return "(" + parts.joinToString(",") + ")"
    }

    /** O3（决策 69）：当前位是否"命名字段构造"的左括号——`(IDENT =` 形态。
     *  `()` 空括号**不算**：零参调用要留给 `None()` 这类构造子（误判会破坏 prelude）。
     *  `A()` 取全默认值走位置式缺省尾部通道（checker 放行、JS 形参默认值补齐）。 */
    private fun looksLikeNamedCtor(): Boolean {
        if (!peek(Kind.LPAREN)) return false
        val a = toks.getOrNull(pos + 1) ?: return false
        val b = toks.getOrNull(pos + 2) ?: return false
        return a.kind == Kind.IDENT && b.kind == Kind.ASSIGN
    }

    /** 解析 `A(name = e, age = e2)`：LPAREN 未消费；产出 (字段名, 值) 序列 */
    private fun parseStructCtorArgs(): List<Pair<String, Expr>> {
        at(Kind.LPAREN)
        val out = ArrayList<Pair<String, Expr>>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                val fname = nameToken()
                at(Kind.ASSIGN)
                out += fname to parseExpr(angle = false)
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    /** v3.0（完美函数类型）：函数类型理论参数前缀 `[T](T)=>T`——[T] 是绑定者（多态函数类型）。
     *  非该形态（元组等）返回 null 并恢复游标。 */
    private fun parsePolyFunType(): FunType? {
        val save = pos
        return try {
            at(Kind.LBRACKET)
            val tps = ArrayList<TypeParam>()
            while (true) {
                tps += TypeParam(nameToken(), null)
                if (!peek(Kind.COMMA)) break
                pos++
                if (peek(Kind.RBRACKET)) break   // 尾随逗号（v3.0）
            }
            at(Kind.RBRACKET)
            if (!peek(Kind.LPAREN)) { pos = save; return null }
            val ft = parseFunOrTupleType()
            if (ft !is FunType)
                // 理论前缀仅服务函数类型——`[T](a, b)` 元组形态是用户笔误，直接报语法错误（不静默回退）
                throw ParseFailure(fileName, cur(), "类型理论参数前缀仅用于函数类型（[T](...) => ...），元组不能携带")
            ft.copy(theory = tps)
        } catch (_: ParseFailure) { pos = save; null }
    }

    private fun parseType(): Type {
        // 类型位注解前缀：@mut T 可变视图类型（root doc 54-61）；@atomic T 原子类型（root doc《异步》）
        if (peek(Kind.AT)) {
            pos++
            val an = at(Kind.IDENT).text
            if (an == "mut") return MutType(parseType())
            if (an == "atomic") return AtomicType(parseType())
            // HKT 标准库（Kotlin 风）：@impure 函数类型——f: @impure (A) => Null
            // （根文档《异步》withLock(f : @impure ()=>Null) 同形态）：注解只能修饰函数类型，
            // purity 字段记 "impure"，checker 按此把纯度随函数值传递（checkInlineFunCall）。
            if (an == "impure") {
                val t = parseType()
                return if (t is FunType) t.copy(purity = "impure")
                       else throw ParseFailure(fileName, cur(), "@impure 类型注解只能修饰函数类型（写法：f: @impure (A) => Null）")
            }
            throw ParseFailure(fileName, cur(), "类型位注解只支持 @mut / @atomic / @impure")
        }
        // v3.0（完美函数类型）：`[T]` 前缀先试多态函数类型（`[T](T)=>T`），否则回退普通类型
        val base = if (peek(Kind.LBRACKET)) {
            parsePolyFunType()
                ?: if (peek(Kind.LPAREN)) parseFunOrTupleType() else parseNamedType()
        } else if (peek(Kind.LPAREN)) parseFunOrTupleType() else parseNamedType()
        var t = base
        // v2.0 和类型：`A | B | C` 联合（枚举=和类型，此为内联无 tag 形式）
        if (peek(Kind.PIPE)) {
            val variants = ArrayList<Type>()
            variants += t
            while (peek(Kind.PIPE)) {
                pos++
                val v = if (peek(Kind.LPAREN)) parseFunOrTupleType() else parseNamedType()
                variants += v
            }
            t = UnionType(variants)
        }
        return t
    }

    /** 具名类型：标识符（可带点号链限定）与可选 `[]` 实参 */
    private fun parseNamedType(): Type {
        val first = nameToken()
        // P0：跨模块限定类型 `core.Point`（点号链，末段为类型名，前缀为模块路径）
        val segs = ArrayList<String>()
        segs += first
        while (peek(Kind.DOT)) { pos++; segs += nameToken() }
        // 类型实参声明用 `[]`（理论参数，v1.1）：`List[Nat]`、`Optional[Optional[Nat]]`。
        // `[]` 只表示类型代入，与声明位 `fun f[T]` 的 `[]` 同族（引入/代入理论参数）；
        // 尖括号 `<...>` 仍专用于上下文命题（下文 `<$ == 7>`）与显式供给 `f<q>()`，互不歧义。
        var args: List<Type> = emptyList()
        if (peek(Kind.LBRACKET)) {
            val save = pos
            try {
                pos++
                args = parseTypeArgs()
            } catch (_: ParseFailure) { pos = save }
        }
        return if (segs.size == 1) namedT(first, args)
               else sugared.functor.ast.QualifiedType(segs.dropLast(1), segs.last(), args)
    }

    /** 类型位的 `(`：有 `=>` 是函数类型 `(A,B)=>C`，否则是元组类型 `(T,U)`（决策 57/61） */
    private fun parseFunOrTupleType(): Type {
        at(Kind.LPAREN)
        val ps = ArrayList<Type>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                ps += parseType()
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        // 根文档·上下文系统：函数类型可携带上下文 `(T)<$1 ∈ xs> => Null`
        val ctx = if (peek(Kind.LT)) parseAngleList() else null
        return if (peek(Kind.DARROW)) { pos++; FunType(ps, parseType(), context = ctx) }   // ()=>C / (A,B)=>C
        else TupleType(ps)                                                   // (T,U)；()=> 已归函数
    }

    private fun parseTypeArgs(): List<Type> {
        val out = ArrayList<Type>()
        while (true) {
            out += parseType()
            if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
            break
        }
        at(Kind.RBRACKET)
        return out
    }

    /** 尖括号列表。angle 上下文内 GT 一律是终止符；带空格的 LT 仍是比较中缀 */
    private fun parseAngleList(): List<Expr> {
        at(Kind.LT)
        val out = ArrayList<Expr>()
        while (true) {
            out += parseExpr(angle = true)
            if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
            break
        }
        at(Kind.GT)
        return out
    }

    private fun parseBlock(): BlockExpr {
        at(Kind.LBRACE)
        val stmts = ArrayList<Stmt>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            stmts += parseStmt()
        }
        at(Kind.RBRACE)
        return BlockExpr(stmts)
    }

    /** 线性作用域（第十二卷 §3.1 产生式 LinearBlock → '%' BlockExpr）。
     *  词法已把 `%{` 归并为 LIN_LBRACE（§3.2 规则 L-LINBLOCK：紧贴无空白）；
     *  域体与普通块同文法，`%` 标记是纯编译期分析标记（§10.1 LV-ERASE-BLOCK：擦除后产物与普通块逐字节相同）。 */
    private fun parseLinearBlock(): LinearBlockExpr {
        val t = at(Kind.LIN_LBRACE)
        val stmts = ArrayList<Stmt>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            stmts += parseStmt()
        }
        at(Kind.RBRACE)
        return LinearBlockExpr(BlockExpr(stmts), "${t.line}:${t.col}")
    }

    private fun parseStmt(): Stmt {
        // 线性作用域（第十二卷 §3.1）：语句位的 `%{ … }` 是 ExprStmt(LinearBlock)
        if (peek(Kind.LIN_LBRACE)) return ExprStmt(parseLinearBlock())
        // v3.0 特性 7：`∃ x = t . P`（引入）与 `∃ x . P { }`（消去）——EXISTS 后跟名字再消歧
        if (peek(Kind.EXISTS)) return parseExistsStmt()
        // v3.0 特性 7：块内局部函数定义（∀ 引入）
        if (peek(Kind.FUN)) { val fn = parseFun(if (peek(Kind.AT)) parseAnnotations() else emptyList()); return FunStmt(fn, fn.pos) }
        // v3.0 特性 6：`if[p]` 命题层排中律（IF 后紧跟 [ 与值层 if ( 区分）
        if (peek(Kind.IF) && toks.getOrNull(pos + 1)?.kind == Kind.LBRACKET) return parseIfProp()
        // 根文档《手动证明》：分类讨论 when[p ∨ q] { p => {…} q => {…} }
        if (peek(Kind.WHEN) && toks.getOrNull(pos + 1)?.kind == Kind.LBRACKET) return parseWhenProp()
        // v3.0 特性 5 旧形兼容：裸 ∴ q（自动填入 ⊤ 作为形式）——等价于 ∵ ⊤, ∴ q
        // （根文档《手动证明》只保留复合 ∵ form, p₁…pₙ ∴ conclusion 形态，旧则移除。）
        if (peek(Kind.THEREFORE)) {
            val t = next(); val q = parseExpr(angle = false)
            return BTSStmt(TopExpr, emptyList(), q, "${t.line}:${t.col}")
        }
        // 根文档《手动证明》：因为-所以语句 ∵ form, p₁, p₂ ∴ conclusion
        if (peek(Kind.BECAUSE)) return parseBTS()
        if (peek(Kind.UNCHECKED)) return parseUnchecked()
        if (peek(Kind.RET)) return parseReturn()
        if (peek(Kind.BY)) { pos++; return ByStmt(parseBracketList()) }
        if (peek(Kind.AT)) {
            // 根文档《手动证明》：@策略名[命题] 策略调用
            if (toks.getOrNull(pos + 1)?.kind == Kind.IDENT && toks.getOrNull(pos + 2)?.kind == Kind.LBRACKET) {
                val t = next() // 消费 @
                val strategy = at(Kind.IDENT).text
                at(Kind.LBRACKET)
                val goal = parseExpr(angle = true)
                at(Kind.RBRACKET)
                return StrategyStmt(strategy, goal, "${t.line}:${t.col}")
            }
            val anns = parseAnnotations()
            if (peek(Kind.VAR)) return parseVar(anns)
            throw ParseFailure(fileName, cur(), "var")
        }
        if (peek(Kind.VAR)) return parseVar(emptyList())
        // 赋值探测：语句位置、左值是名字/字段访问的 `NAME = ...` 判为赋值；
        // 其余位置的 `=` 是命题等式中缀（指导文件：= 只涉及表达式，可中缀）。
        val save = pos
        try {
            val lhs = parsePostfix()
            if (peek(Kind.ASSIGN) && (lhs is NameRef || lhs is FieldExpr)) {
                pos++
                return AssignStmt(lhs, parseExpr(angle = false))
            }
            pos = save
        } catch (_: ParseFailure) { pos = save }
        return ExprStmt(parseExpr(angle = false))
    }

    /** 根文档《手动证明》：`∵ form, p₁, p₂ ∴ conclusion` —— 因为-所以语句 */
    private fun parseBTS(): Stmt {
        val t = next()   // ∵ 消费 BECAUSE
        val form = parseExpr(angle = false)
        // 裸 ∵ p（后面没有逗号也没有 ∴）→ 兼容旧形：反糖为 ∵ ⊤ ∴ p（先证 p 再注入 p）
        if (!peek(Kind.COMMA) && !peek(Kind.THEREFORE))
            return BTSStmt(TopExpr, emptyList(), form, "${t.line}:${t.col}")
        val premises = ArrayList<Expr>()
        while (peek(Kind.COMMA)) {
            pos++  // 消费逗号
            if (peek(Kind.THEREFORE)) break   // 尾随逗号
            premises += parseExpr(angle = false)
        }
        at(Kind.THEREFORE)  // ∴
        val conclusion = parseExpr(angle = false)
        return BTSStmt(form, premises, conclusion, "${t.line}:${t.col}")
    }

    /** 根文档《手动证明》：`when[p ∨ q] { p -> {…} q -> {…} }` —— 分类讨论语句 */
    private fun parseWhenProp(): Stmt {
        val t = next()   // when
        at(Kind.LBRACKET)
        val disjunction = parseExpr(angle = true)
        at(Kind.RBRACKET)
        at(Kind.LBRACE)
        val arms = ArrayList<WhenPropArm>()
        while (!peek(Kind.RBRACE)) {
            val cond = parseExpr(angle = false)
            // 允许可选的分支箭头 =>（v4 终版由 -> 改为 =>）
            if (peek(Kind.DARROW)) pos++
            val body = parseBlock().stmts
            arms += WhenPropArm(cond, body)
            if (peek(Kind.COMMA)) pos++
        }
        at(Kind.RBRACE)
        return WhenPropStmt(disjunction, arms, "${t.line}:${t.col}")
    }

    /** v3.0 特性 6：`if[p] { … } else { … }` —— 命题层排中律；块内是语句（常为 ∴） */
    private fun parseIfProp(): Stmt {
        val t = next()   // IF
        at(Kind.LBRACKET)
        val prop = parseExpr(angle = true)
        at(Kind.RBRACKET)
        val thenBlk = parseBlock()
        val elseBlk = if (peek(Kind.ELSE)) { pos++; parseBlock() } else null
        return IfPropStmt(prop, thenBlk.stmts, elseBlk?.stmts ?: emptyList(), "${t.line}:${t.col}")
    }

    /** `return e` / 裸 `return` / `return@label`（Kotlin 标签返回，v1.1）：
     *  裸形判据=下一 token 是块尾/分隔/文件尾；`@` 紧贴 `return` 后时消费标签名。 */
    private fun parseReturn(): Stmt {
        val t = next()   // 消费 return
        val atPos = "${t.line}:${t.col}"
        var label: String? = null
        if (peek(Kind.AT)) {
            val atTk = cur()
            if (!atTk.precededBySpace) { this.pos++; label = at(Kind.IDENT).text }
        }
        val nx = cur()
        val bare = nx.kind == Kind.RBRACE || nx.kind == Kind.SEMI || nx.kind == Kind.EOF
        return if (bare || label != null) ReturnStmt(null, atPos, label)
        else ReturnStmt(parseExpr(angle = false), atPos)
    }

    /** v3.0 特性 7：`∃ x = t . P(x)`（引入）或 `∃ x . P(x) { … }`（消去）——EXISTS 已消费 */
    private fun parseExistsStmt(): Stmt {
        val t = next()   // EXISTS
        val x = at(Kind.IDENT).text
        if (peek(Kind.ASSIGN)) {
            // 多见证引入：∃ a = t1, b = t2, … . P
            val names = ArrayList<String>()
            val witnesses = ArrayList<Expr>()
            names.add(x)
            pos++ // 消费 =
            witnesses.add(parseExpr(angle = false))
            while (peek(Kind.COMMA)) {
                pos++ // 消费 ,
                names.add(at(Kind.IDENT).text)
                at(Kind.ASSIGN)
                witnesses.add(parseExpr(angle = false))
            }
            at(Kind.DOT)
            val prop = parseExpr(angle = true)
            return ExistIntroStmt(names, witnesses, prop, "${t.line}:${t.col}")
        }
        at(Kind.DOT)
        val prop = parseExpr(angle = true)
        if (peek(Kind.LBRACE)) {
            val blk = parseBlock()
            return ExistsElimStmt(x, prop, blk.stmts, "${t.line}:${t.col}")
        }
        return ExistsElimStmt(x, prop, emptyList(), "${t.line}:${t.col}")
    }

    private fun parseUnchecked(): Stmt {
        val save = pos
        pos++
        if (peek(Kind.DOT)) {
            pos++
            val kw = at(Kind.IDENT)
            if (kw.text == "axiom") {
                at(Kind.LBRACKET)
                val props = ArrayList<Expr>()
                while (true) {
                    props += parseExpr(angle = false)
                    if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                    break
                }
                at(Kind.RBRACKET)
                return AxiomStmt(props)
            }
            throw ParseFailure(fileName, kw, "axiom")
        }
        pos = save
        pos++ // unchecked
        return UncheckedStmt(parseStmt())
    }

    private fun parseVar(anns: List<String>): VarStmt {
        val sl = cur().line; val sc = cur().col   // 报错增强：变量声明位置（W-UNUSED/位置诊断用）
        at(Kind.VAR)
        val destruct = parseVarDestruct()
        val name = if (destruct != null) destruct.first() else nameToken()
        val ty = if (peek(Kind.COLON)) { pos++; parseType() } else null
        at(Kind.ASSIGN)
        val v = parseExpr(angle = false)
        return VarStmt(name, anns, ty, v, destruct, "$sl:$sc")
    }

    /** 元组解构声明 `var (a, b) = e`：`var` 后紧跟 `(` 则读扁平分量名列表。 */
    private fun parseVarDestruct(): List<String>? {
        if (!peek(Kind.LPAREN)) return null
        pos++
        val names = ArrayList<String>()
        names += nameToken()
        while (peek(Kind.COMMA)) { if (peek(Kind.RPAREN)) break; pos++; names += nameToken() }   // v3.0：尾随逗号
        at(Kind.RPAREN)
        return names
    }

    /** var 声明即表达式（A3）：值为初值，支持 `var n = var m = 0` 嵌套 */
    private fun parseVarExpr(anns: List<String>): VarExpr {
        at(Kind.VAR)
        val destruct = parseVarDestruct()
        val name = if (destruct != null) destruct.first() else nameToken()
        val ty = if (peek(Kind.COLON)) { pos++; parseType() } else null
        at(Kind.ASSIGN)
        val v = parseExpr(angle = false)
        return VarExpr(name, anns, ty, v)
    }

    /** 匿名函数表达式（指导§52-56）：`fun _[T](a:T):T = a`，名为 `_` 或省略名 */
    private fun parseAnonFunExpr(): AnonFunExpr {
        at(Kind.FUN)
        // 名字可省略（直接跟 `[` 或 `(`）；写了则须是 `_`（不可引用）
        val name = if (peek(Kind.LBRACKET) || peek(Kind.LPAREN)) "_" else nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        val params = if (peek(Kind.LPAREN)) parseParams() else emptyList()
        val preps = if (peek(Kind.LT)) parseAngleList() else emptyList()
        val ret = if (peek(Kind.COLON)) { pos++; parseType() } else null
        val posts = if (peek(Kind.LT)) parseAngleList() else emptyList()
        // 决策 73：与具名函数同规则——`= 表达式` 或 `{ 语句… }` 互斥，`= {` 混写报错
        val body = when {
            peek(Kind.ASSIGN) && toks.getOrNull(pos + 1)?.kind == Kind.LBRACE ->
                throw ParseFailure(fileName, toks[pos + 1],
                    "函数体不能 `= {…}` 混写：单表达式请去掉花括号，块体请去掉等号")
            peek(Kind.ASSIGN) -> { pos++; parseExpr(angle = false) }
            peek(Kind.LBRACE) -> parseBlock()
            else -> null
        }
        return AnonFunExpr(name, theory, params, preps, ret, posts, body)
    }

    private fun parseClass(anns: List<String> = emptyList()): ClassDecl {
        at(Kind.CLASS)
        val name = nameToken()
        classNames += name   // 供 parseImpl 的 Trait[…] 简写判定
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        // v2.1 子类型类：`class Sub[T] : Base[T] , Ord[T]`（父类型类列表，逗号分隔）
        val parents = ArrayList<Type>()
        if (peek(Kind.COLON)) {
            pos++
            while (true) {
                parents += parseNamedType()
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE) || peek(Kind.LBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        // P3（v2.1）：无成员类型类可省略花括号 `class C`，等价 `class C {}`
        if (!peek(Kind.LBRACE)) return ClassDecl(name, theory, anns, emptyList(), parents)
        at(Kind.LBRACE)
        val members = ArrayList<FunDecl>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val anns = parseAnnotations()
            members += parseFun(anns)
        }
        at(Kind.RBRACE)
        return ClassDecl(name, theory, anns, members, parents)
    }

    private fun parseStruct(anns: List<String> = emptyList()): StructDecl {
        at(Kind.STRUCT)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        // O3（决策 69）：struct 体是圆括号参数列表；大括号明确拒绝并给出改写指引
        if (peek(Kind.LBRACE))
            throw ParseFailure(fileName, cur(), "struct 体是参数列表，请改写为 $name(字段: 类型 = 默认值)")
        // P3（v2.1）：无字段 struct 可省略参数括号 `struct P`，等价 `struct P()`
        if (!peek(Kind.LPAREN)) {
            // v3.0 特性 1：伴生命题 `struct P <p>`（无字段也可有命题）
            return StructDecl(name, theory, emptyList(), anns, if (peek(Kind.LT)) parseAngleList() else emptyList())
        }
        val fields = parseParams()
        // v3.0 特性 1：结构体伴生命题 `struct Test(n: Nat) <n ≠ 0>`——声明位 `<...>`（同 fun 下文位识别）
        val props = if (peek(Kind.LT)) parseAngleList() else emptyList()
        return StructDecl(name, theory, fields, anns, props)
    }

    private fun parseEnum(): EnumDecl {
        at(Kind.ENUM)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        // P3（v2.1）：无构造子枚举可省略花括号 `enum E`，等价 `enum E {}`
        if (!peek(Kind.LBRACE)) return EnumDecl(name, theory, emptyList())
        at(Kind.LBRACE)
        val ctors = ArrayList<Ctor>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val c = nameToken()
            val fs = if (peek(Kind.LPAREN)) parseCtorFields() else emptyList()
            ctors += Ctor(c, fs)
            if (peek(Kind.COMMA)) pos++
        }
        at(Kind.RBRACE)
        return EnumDecl(name, theory, ctors)
    }

    private fun parseCtorFields(): List<Type> {
        at(Kind.LPAREN)
        val out = ArrayList<Type>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                out += parseType()
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    private fun parseImpl(): ImplDecl {
        at(Kind.IMPL)
        val implTok = toks[pos - 1]   // impl 关键字 token（诊断/跳转定位锚点）
        val t0 = parseType()
        // impl 无 for（`impl T { … }`）= 单纯方法添加（用户拍板：承担重载职责）：trait 置 null，成员在 parseFile 展开为自由函数
        val trait: Type?
        val self: Type
        // HKT 标准库简写（无 for）：`impl Trait[Head[…]]`（Trait 是本文件已声明的类型类）≡ 经典
        // `impl Trait for Head[…]`——Head 零实参（impl Functor[List]）时补 kind 占位成 List[a]；
        // `_` 是 kind 应用槽（Result[_, E] = Result 对第二参数应用 Functor），E/K 等零实参大写名
        // 是**成员可引用的隐式类型变量**（并入各成员 theory 尾部，与 parseFile 扩展 impl 占位提升同套路）
        val shorthandVars: List<String>
        if (peek(Kind.FOR)) { pos++; trait = t0; self = parseType(); shorthandVars = emptyList() }
        else if (t0 is NamedType && t0.args.size == 1 && t0.name in classNames) {
            // trait 只取头名（Functor）；实参整体移作 self（Head[…]）
            trait = namedT(t0.name)
            val arg = t0.args[0]
            self = if (arg is NamedType && arg.args.isEmpty()) namedT(arg.name, listOf(namedT("a"))) else arg
            shorthandVars = collectShorthandVars(self)
        } else { trait = null; self = t0; shorthandVars = emptyList() }
        // v3.0 特性 2：`impl C for T by field` 委托——by 后必须跟字段名（IDENT）
        var delegate: String? = null
        if (peek(Kind.BY)) {
            pos++
            val dt = cur()
            if (dt.kind != Kind.IDENT)
                throw ParseFailure(fileName, dt, "impl 委托 by 后应跟字段名")
            else delegate = dt.text
            pos++
        }
        val members = ArrayList<FunDecl>()
        if (peek(Kind.LBRACE)) {
            at(Kind.LBRACE)
            while (!peek(Kind.RBRACE)) {
                skipSemis()
                if (peek(Kind.RBRACE)) break
                val anns = parseAnnotations()
                members += parseFun(anns)
            }
            at(Kind.RBRACE)
        }
        // 简写：self 里用户显式命名的类型变量（Result[_, E] 的 E、Map[K, _] 的 K）并入成员 theory，
        // 供成员签名引用（E 在 Result[B, E] 等处解析）
        val finalMembers = if (shorthandVars.isEmpty()) members
            else members.map { it.copy(theory = it.theory + shorthandVars.map { TypeParam(it, null) }) }
        return ImplDecl(trait, self, finalMembers, delegate, "${implTok.line}:${implTok.col}")
    }

    /** impl 简写 self[…] 里用户显式命名的类型变量：零实参大写名（`Result[_, E]` 的 E、`Map[K, _]` 的 K）。
     *  `_`（kind 应用槽）与小写占位（经典形态）不算——只收「成员可引用的绑定名」 */
    private fun collectShorthandVars(t: Type): List<String> {
        val out = LinkedHashSet<String>()
        fun walk(tp: Type) {
            when (tp) {
                is NamedType ->
                    if (tp.args.isEmpty() && tp.name != "_" && tp.name.firstOrNull()?.isUpperCase() == true) out.add(tp.name)
                    else tp.args.forEach { walk(it) }
                is TupleType -> tp.items.forEach { walk(it) }
                else -> {}
            }
        }
        walk(t)
        return out.toList()
    }

    // ---------- 表达式 ----------

    private fun parseExpr(angle: Boolean, stopArrow: Boolean = false): Expr {
        var left = parseUnary(stopArrow, angle)
        while (true) {
            val t = cur()
            // when 守卫在首个顶层 `->` 处停（`->` 既是蕴含中缀又是分支箭头，蕴含需加括号）
            // 模板 when：空格 LPAREN（紧跟 body 的下一 arm 模式 `(11..20)` 前导）也停——防 `print(1) (11..20)` 后缀调用吞 arm；
            // 无空格调用 `print(1)` / `f(x)` 不受影响。
            // HKT 标准库：if-then-else 命题形态的停止词边界（then/else 作 IDENT 时不得被 infix 糖吞）
            if (t.kind == Kind.IDENT && t.text in stopIdents) break
            if (stopArrow && (t.kind == Kind.ARROW || t.kind == Kind.DARROW)) break
            if (stopArrow && t.kind == Kind.LPAREN && t.precededBySpace) break
            if (t.kind == Kind.BY) { pos++; left = ByExpr(left, parseBracketList()); continue }
            if (t.kind == Kind.GT) {
                // 修复（#1）：angle 上下文中 `>` 作中缀须「前有空格且后有操作数」；
                // 紧贴的 `>`（valid<7> 终止符、>> 序列、T>() 调用）仍是终止符
                if (angle && !(t.precededBySpace && nextStartsOperand())) break
                // 修复（HKT 标准库）：中缀右操作数必须继承 angle 上下文——否则 `<p ∧ ∃a. a = x>` 里
                // ∃ 体以 angle=false 解析，收尾 `>` 被当成比较中缀，吞掉 ` = body` 后报「期望表达式」。
                pos++; left = BinExpr(">", left, parseUnary(stopArrow, angle)); continue
            }
            if (t.kind == Kind.LT) {
                if (!t.precededBySpace) break   // 紧贴的 < 属于 postfix 实例化
                pos++; left = BinExpr("<", left, parseUnary(stopArrow, angle)); continue
            }
            // v2.1（对齐 Rust）：运行时类型测是中缀 `a is T`（is 复用模式匹配关键词），
            // 右操作数是类型名（结构判定目标），非值表达式；类型类判定由 checkTypeTest 拦截。
            // 同行约束：is 与被测表达式必须同一行（否则会被 when 分支体的换行 is 误吞，
            // 如 `is Str -> 0 \n is Nat -> o`——0 之后的换行 is 属于下一个分支的模式）。
            if (t.kind == Kind.IS) {
                // is 前一个 token 的行即左操作数结束行；跨行的 is 不是中缀（when 分支体换行后的 is 属下一分支模式）
                val leftEnd = toks.getOrNull(pos - 1)?.line
                if (leftEnd != null && leftEnd != t.line) break
                // 消歧（构造子解构须 is）：`is Ctor(...)` / `is E::A(...)` 的右侧是构造子调用形态（圆括号），绝不可能是类型——
                // 视为 when 下一分支的模式起始而非中缀类型测（单行 when 内 `-> 0 is Some(x) ->` 必须这样切）
                if (isCtorForm(pos + 1)) {
                    // ifis 智能解构：if 条件位把它解成模式哨兵（宿主表达式由 parseIf 脱糖为 when）
                    if (ifIsCond) {
                        val p = "${t.line}:${t.col}"
                        left = IsPatExpr(left, parsePattern(), p)   // parsePattern 消费 is 前缀并解出构造子模式
                        continue
                    }
                    break
                }
                pos++
                left = TypeTestExpr(left, parseType())
                continue
            }
            // v3.0（区间）：`..` 中缀——先于通用 binops（in 的右操作数吃 `lo..hi`）
            if (t.kind == Kind.RANGE) {
                pos++; left = BinExpr("..", left, parseUnary(stopArrow, angle)); continue
            }
            // P51：in 去关键字化——中缀 a in b 解析为普通函数调用 in(a, b)（右操作数仍吃 lo..hi，见 parseRangeOperand）
            if (t.kind == Kind.IDENT && t.text == "in") {
                pos++; left = CallExpr(NameRef("in"), listOf(left, parseRangeOperand(stopArrow, angle))); continue
            }
            if (t.kind in binops) {
                maybeArithHint(left, t.text, t)
                pos++; left = BinExpr(t.text, left, parseUnary(stopArrow, angle)); continue
            }
            // 两参解糖：带空格的符号名中缀 `q ○ p` → BinExpr("○")，左结合、无优先级
            if (t.kind == Kind.SYMBOL && t.precededBySpace) {
                pos++; left = BinExpr(t.text, left, parseUnary(stopArrow, angle)); continue
            }
            // HKT 标准库（中文命题风格）：`e 的内部值`——Optional 内部值的命题位读法（后缀一元选择子），
            // 解糖为原子谓词 的内部值(e)（命题层结构保留；定义性事实由 @axiom 信任，见 Checker）。
            if (t.kind == Kind.IDENT && t.text == "的内部值" && t.precededBySpace) {
                pos++
                left = CallExpr(NameRef("的内部值"), listOf(left))
                continue
            }
            // P0 补全（决策 88）：标识符两参中缀调用 `a f b`（f 前后均有空格，等价 f(a, b)）。
            // 右操作数也须带空格前缀，避免误吞 `f(x)`（函数调用）与相邻表达式；且要求**同一行**
            // （换行也带 precededBySpace，否则 `1\nx = 2` 的换行 x 会被误当中缀）。
            if (t.kind == Kind.IDENT && t.precededBySpace && infixOperandAhead()) {
                val name = next().text
                supplements += Triple("D-INFIX", "${t.line}:${t.col}", "中缀 `a ${name} b` 展开为自由函数 `${name}(a, b)`（中缀糖）")
                left = CallExpr(NameRef(name, "${t.line}:${t.col}"), listOf(left, parseUnary(stopArrow, angle)))
                continue
            }
            break
        }
        return left
    }

    /** P0 补全（决策 88）：标识符中缀要求右操作数也带空格前缀，且 f 与左右操作数同在一行（`a f b` 成立；`f(x)` 与跨行 `1\nx` 不是中缀）。
     *  右操作数还必须是合法表达式起始 token——排除 `->`（蕴含）与 `=>`（when 分支分隔）、`,`、`}` 等，
     *  避免把 when 分支体的下一个模式名（`x None => d`）误当中缀。 */
    private fun infixOperandAhead(): Boolean {
        val nx = toks.getOrNull(pos + 1) ?: return false
        if (!nx.precededBySpace) return false
        if (cur().line != nx.line) return false
        val prev = toks.getOrNull(pos - 1)
        if (prev != null && prev.line != cur().line) return false
        if (nx.kind == Kind.ARROW || nx.kind == Kind.DARROW || nx.kind == Kind.COMMA || nx.kind == Kind.RBRACE ||
            nx.kind == Kind.RBRACKET || nx.kind == Kind.SEMI || nx.kind == Kind.RPAREN ||
            nx.kind == Kind.ASSIGN || nx.kind == Kind.RET) return false
        return true
    }

    /** 修复（#1）：`>` 是「比较命题符号」还是「尖括号终止符」的消歧——后一 token 能起表达式则视为中缀 `>`。
     *  紧邻的 `>>`（`valid<7>>`）里内层 `>` 的下一 token 仍是 `>`，不在起式集里，故仍正确终止。 */
    private fun nextStartsOperand(): Boolean {
        val t = toks.getOrNull(pos + 1) ?: return false
        return t.kind in setOf(
            Kind.INT, Kind.FLOAT, Kind.STR, Kind.DOLLAR, Kind.IDENT, Kind.SYMBOL,
            Kind.LPAREN, Kind.MINUS, Kind.BANG, Kind.LNOT, Kind.LT,
        )
    }

    /** 修复（#2，用户拍板「语言特性、不实现优先级」）：算术优先级提示——
     *  仅当右侧算子优先级高于左侧已存算子（如 `a + b * c` 左结合会算成 `(a+b)*c`）时提示加括号。 */
    private fun arithPrec(op: String): Int = when (op) {
        "^" -> 3
        "*", "/", "%" -> 2
        "+", "-" -> 1
        else -> 0
    }

    private fun maybeArithHint(left: Expr, op: String, tok: Token) {
        if (left is BinExpr) {
            val lo = arithPrec(left.op)
            val ro = arithPrec(op)
            if (lo > 0 && ro > lo) {
                hints += ("${tok.line}:${tok.col}" to
                    "算术表达式混用了不同优先级：右侧 `$op` 本应先于左侧 `${left.op}` 计算，但语言不做优先级（左结合），实际会算成 `(… ${left.op} …) $op …`。请手动加括号（如 `a + (b * c)`）以表达本意")
            }
        }
    }

    /** v3.0（区间 in）：`in` 的右操作数——`lo..hi`（括号 `/无括号`）*/ 
    private fun parseRangeOperand(stopArrow: Boolean = false, angle: Boolean = false): Expr {
        var e = parseUnary(stopArrow, angle)
        if (peek(Kind.RANGE)) { pos++; e = BinExpr("..", e, parseUnary(stopArrow, angle)) }
        return e
    }

    private fun parseUnary(stopArrow: Boolean = false, angle: Boolean = false): Expr {
        val t = cur()
        if (t.kind == Kind.MINUS) { pos++; return UniExpr("-", parseUnary(stopArrow, angle)) }
        if (t.kind == Kind.BANG) { pos++; return UniExpr("!", parseUnary(stopArrow, angle)) }
        if (t.kind == Kind.LNOT) { pos++; return UniExpr("¬", parseUnary(stopArrow, angle)) }
        if (t.kind == Kind.IDENT && t.text == "not") { pos++; return UniExpr("¬", parseUnary(stopArrow, angle)) }
        // 命题括号：`<e>`（根文档 def 等价右式/量词体的 `¬<a = b>`）——GT 由 parseAngleList 消费
        if (t.kind == Kind.LT) {
            val inner = parseAngleList()
            if (inner.size != 1) throw ParseFailure(fileName, cur(), "命题尖括号内只能有一个表达式")
            return inner[0]
        }
        return parsePostfix(stopArrow, angle)
    }

    private fun parsePostfix(stopArrow: Boolean = false, angle: Boolean = false): Expr {
        var e = parsePrimary(angle)
        while (true) {
            val t = cur()
            when {
                // 模板 when body：空格 LPAREN（下一 arm 模式前导）在 stopArrow 下不再作后缀调用
                stopArrow && t.kind == Kind.LPAREN && t.precededBySpace -> break
                t.kind == Kind.COLON2 -> {
                    // v2.1（对齐 Rust 关联项）：`E::A` 枚举成员访问 → 折叠为构造子复合名
                    // （语义层按 syms.ctors 复合键解析构造；后随 `(` 则为构造调用）
                    // P6（决策 95）：`o::f` 非大写名左侧为方法引用 → FunRefExpr
                    pos++
                    val member = nameToken()
                    e = if (e is NameRef && e.name.first().isUpperCase())
                        NameRef(e.name + "::" + member, e.pos)
                    else FunRefExpr(e, member, e.pos)
                }
                // v3.0 特性 7：`.` 字段访问 ⇔ 点前无空格（`a.b`），或点在自己行的行首（换行链 `\n    .map`）；`1 . x = 1` 同行带空格点是 ∃ 分隔符
                t.kind == Kind.DOT && (!t.precededBySpace || toks.getOrNull(pos - 1)?.line != t.line) -> {
                    pos++
                    val mt = cur()   // 成员 token（保留行列：rename/诊断定位靠它，而非接收者）
                    e = FieldExpr(e, nameToken(), "${mt.line}:${mt.col}")
                }
                t.kind == Kind.LPAREN -> {
                    // O3（决策 69）：大写名字 + `()`/`(IDENT = …)` 形态 → 命名字段构造；其余仍走普通调用
                    // v2.1：`E::A(...)` 枚举成员路径不走命名字段构造（复合名含 ::），保持普通调用 → codegen 归一化裸名
                    if (e is NameRef && !e.name.contains("::") && e.name.first().isUpperCase() && looksLikeNamedCtor())
                        e = StructCtorExpr(e.name, parseStructCtorArgs())
                    else {
                        val (pa, na) = parseArgs()
                        // 调用位置取 callee 位置（方法糖=成员名、普通函数=函数名）：诊断/快速修正据此定位
                        e = CallExpr(e, pa, namedArgs = na, pos = e.pos)
                    }
                }
                t.kind == Kind.LT && !t.precededBySpace -> {
                    val save = pos
                    try { e = InstExpr(e, parseAngleList()) }
                    catch (f: ParseFailure) { pos = save; break }
                }
                // 尾随 lambda（含隐式单参）；`Task { … }`（决策 93）是代码块非 lambda，交给下一分支
                // v3.0 特性 7：字面量/运算符结果后 `{` 不是尾随 lambda（`1 {` 是 ∃ 消去块的边界）；可调用结果才允许
                t.kind == Kind.LBRACE && (braceIsLambda(pos) ||
                    (braceHasImplicitBody(pos) && !(e is NameRef && (e.name == "Task" || e.name == "Flow")))) && !(e is IntLit || e is StrLit || e is BinExpr || e is UniExpr) -> {
                    // v1.1 尾随 lambda 语法糖（Kotlin）：`f(a) { x -> … }` 追加为**最后**位置实参
                    val lam = parseBraceLambda()
                    supplements += Triple("D-TRAILING", "${t.line}:${t.col}", "尾随 lambda 已作为最后一个实参传入（等价在括号里写 `{ … }`）")
                    e = if (e is CallExpr) e.copy(args = e.args + lam) else CallExpr(e, listOf(lam))
                }
                t.kind == Kind.LBRACE && e is NameRef && e.name == "Task" -> {
                    // v2.0 异步（决策 93）：`Task { … }` 创建任务（TASK-1）——体是代码块，非 lambda
                    val body = parseBlock()
                    e = TaskExpr(body)
                }
                t.kind == Kind.LBRACE && e is NameRef && e.name == "Flow" -> {
                    // v2.0 异步（root doc《异步》）：`Flow { … }` 创建惰性冷流——体是代码块（含 emit），非 lambda
                    val body = parseBlock()
                    e = FlowExpr(body)
                }
                else -> break
            }
        }
        return e
    }

    /** P8（决策 84）：实参列表——`IDENT =`（ASSIGN token，与 `==` 命题等价的 EQ 区分）形态是**命名参数**新通道
     *  （v1.0 计划 §10.2：小写开头 → 函数命名参数）。返回 (位置实参, 命名参数)。 */
    private fun parseArgs(): Pair<List<Expr>, Map<String, Expr>> {
        at(Kind.LPAREN)
        val posArgs = ArrayList<Expr>()
        val named = LinkedHashMap<String, Expr>()
        if (!peek(Kind.RPAREN)) {
            while (true) {
                if (cur().kind == Kind.IDENT && toks.getOrNull(pos + 1)?.kind == Kind.ASSIGN) {
                    val nm = cur().text
                    pos += 2
                    named[nm] = parseExpr(angle = false)
                } else {
                    posArgs += parseExpr(angle = false)
                }
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RPAREN)
        return posArgs to named
    }

    /** P8（决策 84）字符串插值 desugar：`"hello, $name"` / `"${1 + 2} is three"` → concat 链
     *  （v1.0 计划 §10.1——不引入新 AST 节点）。`$name`（标识符）与 `${expr}`（表达式，子解析器重 lex）
     *  均包 toStr（插值段类型任意，toStr 已泛型化）；`\$` 转义在 lexer 已还原为字面 `$`。
     *  限制：`${}` 内不支持可能吃掉 `}` 的文本（字符串字面量内 `}` 暂不处理；v1 实用够了）。 */
    private fun parseStringLit(): Expr {
        val t = cur(); pos++
        val raw = t.text
        if ('$' !in raw) return StrLit(raw, "${t.line}:${t.col}")
        val parts = ArrayList<Expr>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '$' && i + 1 < raw.length) {
                val nx = raw[i + 1]
                if (nx == '{') {
                    val j = matchInterpBrace(raw, i + 1)
                    if (j >= 0) {
                        if (sb.isNotEmpty()) { parts += StrLit(sb.toString()); sb.clear() }
                        val inner = raw.substring(i + 2, j)
                        if (inner.isNotBlank()) {
                            val e = Parser(Lexer(inner, fileName).tokenize(), fileName).parseExpr(angle = false)
                            parts += CallExpr(NameRef("toStr"), listOf(e))
                        }
                        i = j + 1; continue
                    }
                } else if (nx.isLetter()) {
                    var j = i + 1
                    while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                    if (sb.isNotEmpty()) { parts += StrLit(sb.toString()); sb.clear() }
                    parts += CallExpr(NameRef("toStr"), listOf(NameRef(raw.substring(i + 1, j))))
                    i = j; continue
                }
            }
            sb.append(c); i++
        }
        if (sb.isNotEmpty()) parts += StrLit(sb.toString())
        // 组装 concat 链：concat(a, concat(b, …))；单段直接返回
        var acc = parts.first()
        for (k in 1 until parts.size) acc = CallExpr(NameRef("concat"), listOf(acc, parts[k]))
        return acc
    }

    /** 从指向 `{` 的位置找匹配 `}`（嵌套计数），找不到返回 -1 */
    private fun matchInterpBrace(raw: String, bracePos: Int): Int {
        var depth = 0
        var k = bracePos
        while (k < raw.length) {
            when (raw[k]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return k }
            }
            k++
        }
        return -1
    }

    private fun parsePrimary(angle: Boolean = false): Expr {
        val t = cur()
        return when {
            t.kind == Kind.INT -> { pos++; IntLit(t.text) }
            t.kind == Kind.FLOAT -> { pos++; FloatLit(t.text) }   // P10（决策 86）：浮点字面量
            t.kind == Kind.STR -> parseStringLit()
            t.kind == Kind.DOLLAR -> {
                pos++
                // `$` 返回值；`$n` 参数引用（决策 65，T6）：词法已粘连为 "$n" 文本
                if (t.text.length > 1) ParamRefExpr(t.text.drop(1).toInt(), "${t.line}:${t.col}")
                else ReturnSym
            }
            t.kind == Kind.TOP -> { pos++; TopExpr }
            t.kind == Kind.BOT -> { pos++; BotExpr }
            t.kind == Kind.LPAREN -> {
                pos++
                val first = parseExpr(angle = false)
                if (peek(Kind.COMMA)) {
                    // 元组字面量：逗号是判据（决策 62）；`(a)` 仍是括号表达式
                    val items = ArrayList<Expr>()
                    items += first
                    while (peek(Kind.COMMA)) { pos++; if (peek(Kind.RPAREN)) break; items += parseExpr(angle = false) }   // v3.0：尾随逗号
                    at(Kind.RPAREN)
                    TupleExpr(items, "${t.line}:${t.col}")
                } else { at(Kind.RPAREN); first }
            }
            t.kind == Kind.LIN_LBRACE -> parseLinearBlock()
            t.kind == Kind.LBRACE && (braceIsLambda(pos) || braceHasImplicitBody(pos)) -> parseBraceLambda()
            t.kind == Kind.VAR -> parseVarExpr(emptyList())
            t.kind == Kind.AT -> { val a = parseAnnotations(); if (peek(Kind.VAR)) parseVarExpr(a) else throw ParseFailure(fileName, cur(), "var") }
            t.kind == Kind.FUN -> parseAnonFunExpr()
            t.kind == Kind.IF -> parseIf()
            t.kind == Kind.WHEN -> parseWhen()
            t.kind == Kind.FORALL || t.kind == Kind.EXISTS || t.kind == Kind.EXISTS1 ->
                parseQuant(next().text, angle)
            t.kind == Kind.IDENT && t.text in asciiQuant &&
                toks.getOrNull(pos + 1)?.kind == Kind.IDENT ->
                parseQuant(asciiQuant.getValue(next().text), angle)
            t.kind == Kind.IDENT || t.kind == Kind.SYMBOL -> { pos++; NameRef(t.text, "${t.line}:${t.col}") }
            t.kind in symbolCallStart && toks.getOrNull(pos + 1)?.kind == Kind.LPAREN -> {
                val op = next().text
                SymbolCallExpr(op, parseArgs().first)   // P8：符号调用只取位置实参（命名通道不适用）
            }
            // P0 补全（决策 88）：运算符前缀调用 `+(1,2)` / `○(a,b,c)`——当作普通函数名调用，
            // 交给 checkCall 按名解析（用户 `fun +` 命中；内置四则请继续用中缀）。
            t.kind in opPrefixKinds && toks.getOrNull(pos + 1)?.kind == Kind.LPAREN -> {
                val opTok = next()
                val (pa, na) = parseArgs()
                CallExpr(NameRef(opTok.text, "${opTok.line}:${opTok.col}"), pa, namedArgs = na)
            }
            else -> throw ParseFailure(fileName, t, "表达式")
        }
    }

    private fun parseQuant(q: String, angle: Boolean): Expr {
        // 根文档：多变量量词 `∀ a b c . body`（DOT 前都是名字；逗号分隔兼容 `∀ a, b .`）
        // 根文档《手动证明》：`∀@p` 全称命题量化——@ 前缀标注命题参数；`∀#A` 全称类型量化——# 前缀标注类型参数。
        // 名字存成 @p / #A 与普通值参数区分。
        val names = ArrayList<String>()
        while (cur().kind != Kind.DOT) {
            if (cur().kind == Kind.EOF) throw ParseFailure(fileName, cur(), "量词变量列表后的句点（如 ∀x. body）")
            val isProp = peek(Kind.AT)
            if (isProp) pos++
            val isType = peek(Kind.HASH)
            if (isType) pos++
            names += (if (isProp) "@" else if (isType) "#" else "") + nameToken()
            if (peek(Kind.COMMA)) {
                pos++
                if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
            }
        }
        at(Kind.DOT)
        return QuantExpr(q, names, parseExpr(angle = angle))
    }

    /** `is` 之后是否构成构造子模式形态：`Ctor(` 或枚举路径 `E::A(`（圆括号实参，与类型方括号区分） */
    private fun isCtorForm(i: Int): Boolean {
        if (toks.getOrNull(i)?.kind != Kind.IDENT) return false
        if (toks.getOrNull(i + 1)?.kind == Kind.LPAREN) return true
        if (toks.getOrNull(i + 1)?.kind == Kind.COLON2)
            return toks.getOrNull(i + 2)?.kind == Kind.IDENT && toks.getOrNull(i + 3)?.kind == Kind.LPAREN
        return false
    }

    private fun parseIf(): Expr {
        val ip = "${cur().line}:${cur().col}"   // if 关键词位——脱糖后 WhenExpr 的出错点
        at(Kind.IF)
        // v1.1 Kotlin 化：if 条件必须用 ()；then/else 分支可单表达式（不带 {}）或块
        if (peek(Kind.LPAREN)) {
            pos++
            // ifis 智能解构：条件解析期间放行 `a is Ctor(...)` → IsPatExpr 哨兵
            val saved = ifIsCond
            ifIsCond = true
            val cond = try { parseExpr(angle = false) } finally { ifIsCond = saved }
            at(Kind.RPAREN)
            // 组合条件里的模式哨兵（`a is Some(e) && e > 0` 之类）——模式绑定无法进入组合作用域，按解析错挡住
            if (cond !is IsPatExpr && containsIsPat(cond))
                throw ParseFailure(fileName, cur(),
                    "ifis 智能解构条件须单独为 `表达式 is 构造模式`（如 `o is Some(e)`）；组合条件请改用 when 表达式")
            val thenB = parseBranchBlock()
            val elseB = if (peek(Kind.ELSE)) { pos++; parseBranchBlock() } else null
            return if (cond is IsPatExpr) {
                // 脱糖（S-11）：`if (o is PAT) A else B` → `when (o) { is PAT -> A (else -> B) }`——
                // 复用 when 的模式匹配（枚举/结构体/元组/嵌套）、穷尽性提示与智能转换；else 缺省不补：
                // 与 if 无 else 同义（值类型退化），枚举主题按既有规则给 W-NON-EXHAUSTIVE 提示
                val arms = ArrayList<WhenArm>()
                arms += WhenArm(cond.pat, null, thenB, cond.pos)
                if (elseB != null) arms += WhenArm(PatElse(cond.pos), null, elseB, cond.pos)
                WhenExpr(cond.target, null, arms, ip)
            } else IfExpr(cond, thenB, elseB)
        }
        // HKT 标准库（命题位，数学风）：`if c then a else b`——then/else 是 IDENT 非关键词；
        // c 与分支均为裸表达式，以 then/else 作停止词（否则两参中缀糖会把 then/else 误当中缀函数名）。
        // 命题层条件式，体不运行；declarative 上下文专用（checkEval 不执行 IfExpr 命题位形态）。
        val saved = stopIdents
        stopIdents = setOf("then")
        val cond = parseExpr(angle = false)
        stopIdents = saved
        val tt = cur()
        if (tt.kind != Kind.IDENT || tt.text != "then")
            throw ParseFailure(fileName, tt, "then")
        pos++
        stopIdents = setOf("else")
        val thenE = parseExpr(angle = false)
        stopIdents = saved
        val et = cur()
        // else 是关键词（Kind.ELSE）；非关键词形态（IDENT "else"）也兼容
        if (et.kind != Kind.ELSE && (et.kind != Kind.IDENT || et.text != "else"))
            throw ParseFailure(fileName, et, "else")
        pos++
        val elseE = parseExpr(angle = false)
        return IfExpr(cond, BlockExpr(listOf(ExprStmt(thenE))), BlockExpr(listOf(ExprStmt(elseE))))
    }

    /** 表达式里是否藏着 IsPatExpr 哨兵——防止组合条件（`a is Some(e) && p` / 实参位等）吞掉模式绑定 */
    private fun containsIsPat(e: Expr): Boolean = when (e) {
        is IsPatExpr -> true
        is BinExpr -> containsIsPat(e.left) || containsIsPat(e.right)
        is UniExpr -> containsIsPat(e.operand)
        is CallExpr -> containsIsPat(e.callee) || e.args.any { containsIsPat(it) } || e.namedArgs.values.any { containsIsPat(it) }
        is FieldExpr -> containsIsPat(e.target)
        is InstExpr -> containsIsPat(e.target) || e.terms.any { containsIsPat(it) }
        is TupleExpr -> e.items.any { containsIsPat(it) }
        is ByExpr -> containsIsPat(e.target) || e.props.any { containsIsPat(it) }
        is TypeTestExpr -> containsIsPat(e.target)
        is IfExpr -> containsIsPat(e.cond) || containsIsPat(e.thenBlock) || (e.elseBlock?.let { containsIsPat(it) } ?: false)
        is WhenExpr -> (e.subject?.let { containsIsPat(it) } ?: false) ||
            e.arms.any { a -> (a.guard?.let { containsIsPat(it) } ?: false) || (a.cond?.let { containsIsPat(it) } ?: false) || containsIsPat(a.body) }
        is LambdaExpr -> containsIsPat(e.body)
        is BlockExpr -> e.stmts.any { containsIsPatStmt(it) }
        is StructCtorExpr -> e.assigns.any { containsIsPat(it.second) }
        is FunRefExpr -> containsIsPat(e.target)
        is SymbolCallExpr -> e.args.any { containsIsPat(it) }
        is TaskExpr -> containsIsPat(e.body)
        is FlowExpr -> containsIsPat(e.body)
        is QuantExpr -> containsIsPat(e.body)
        else -> false   // 字面量/名字引用等无子表达式节点
    }

    private fun containsIsPatStmt(s: Stmt): Boolean = when (s) {
        is ExprStmt -> containsIsPat(s.expr)
        is VarStmt -> containsIsPat(s.value)
        is AssignStmt -> containsIsPat(s.target) || containsIsPat(s.value)
        is ReturnStmt -> s.expr?.let { containsIsPat(it) } ?: false
        is UncheckedStmt -> containsIsPatStmt(s.inner)
        else -> false
    }

    /** if/when 分支体：`{ 语句… }` 块，或单表达式（自动包装成块，值语义由调用位决定） */
    private fun parseBranchBlock(): BlockExpr =
        if (peek(Kind.LBRACE)) parseBlock()
        else BlockExpr(listOf(ExprStmt(parseExpr(angle = false))))

    /**
     * when 表达式（决策 29/46：真模式匹配）。
     * 主题位允许 `when(var e = 0)` 绑定（指导§51，Kotlin 风格）；
     * 分支形态：`pat -> body`、`is T as x -> body`、`pat if guard -> body`、`_ -> body`、`else -> body`。
     */
    private fun parseWhen(): Expr {
        val sl = cur().line; val sc = cur().col
        at(Kind.WHEN)
        // v3.0：无主题 when——`when { cond -> ... }`（布尔条件分支）；主题可选 `when(x) { ... }`
        var binding: String? = null
        var subject: Expr? = null
        var templateBase: Expr? = null
        if (peek(Kind.LPAREN)) {
            pos++
            subject = if (peek(Kind.VAR)) {
                pos++
                binding = nameToken()
                at(Kind.ASSIGN)
                parseExpr(angle = false)
            } else parseExpr(angle = false)
            at(Kind.RPAREN)
            // v3.0 第三批 4.1 模板模式匹配：`when(t in _)` —— 主题为 `in _` 时脱糖为无主题 when，
            // 每个分支模式 p 自动包成条件 `t in p`（`_` 内联到判别表达式右操作数）。
            // P51：in 中缀已解为调用——主题是 CallExpr("in", [t, _]) 时脱糖为模板 when
            (subject as? CallExpr)?.let { ce ->
                val r = ce.args.getOrNull(1)
                if ((ce.callee as? NameRef)?.name == "in" && r is NameRef && r.name == "_") {
                    templateBase = ce.args[0]; subject = null
                }
            }
        }
        at(Kind.LBRACE)
        val arms = ArrayList<WhenArm>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val al = cur().line; val ac = cur().col
            val pat: Pattern; var guard: Expr? = null; var cond: Expr? = null
            if (templateBase != null) {
                // 模板 when 分支：表达式模式 p（范围/字面量/…），`else`/`_` 兜底
                if (peek(Kind.ELSE)) { pos++; pat = PatElse("$al:$ac") }
                else {
                    val pe = parseExpr(angle = false, stopArrow = true)
                    if (pe is NameRef && pe.name == "_") pat = PatElse("$al:$ac")
                    else { pat = PatCond; cond = CallExpr(NameRef("in"), listOf(templateBase, pe)) }
                }
                guard = if (peek(Kind.IF)) { pos++; parseExpr(angle = false, stopArrow = true) } else null
            } else if (subject == null && peek(Kind.ELSE)) {
                pos++
                pat = PatElse("$al:$ac")
            } else if (subject == null) {
                // 无主题分支位：布尔条件表达式，存 WhenArm.cond（pattern 用 PatCond 哨兵）
                cond = parseExpr(angle = false, stopArrow = true)
                pat = PatCond
            } else {
                pat = parsePattern()
                guard = if (peek(Kind.IF)) { pos++; parseExpr(angle = false, stopArrow = true) } else null
            }
            // v4 终版：when 分支分隔符 `=>`（原 `->` 改为 `=>`——与蕴含 infix `->` 彻底解耦，无需 stopArrow 消歧）
            at(Kind.DARROW)
            // 模板 when：body 在 stopArrow 停（防后缀括号调用吞掉后续 arm）
            arms += WhenArm(pat, guard, parseBranchBody(stopArrow = templateBase != null), "$al:$ac", cond = cond)
            skipSemis()
            if (peek(Kind.COMMA)) pos++
        }
        at(Kind.RBRACE)
        return WhenExpr(subject, binding, arms, "$sl:$sc")
    }

    /** 模式：`is T [as x]`、`_`、`else`、构造子解构 `Some(x)`、字面量/名字 */
    private fun parsePattern(): Pattern {
        val t = cur()
        val p = "${t.line}:${t.col}"
        if (t.kind == Kind.IS) {
            pos++
            // v2.1：`is E::A[(模式实参)]`—is 后跟枚举成员路径 = 构造解构分支（Kotlin 风格 is 类型分支）
            if (peek(Kind.IDENT) && toks.getOrNull(pos + 1)?.kind == Kind.COLON2) {
                val en = next().text
                at(Kind.COLON2)
                val ctor = nameToken()
                val full = "$en::$ctor"
                if (peek(Kind.LPAREN)) {
                    pos++
                    val args = ArrayList<Pattern>()
                    if (!peek(Kind.RPAREN)) {
                        while (true) {
                            args += parsePattern()
                            if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                            break
                        }
                    }
                    at(Kind.RPAREN)
                    return PatCtor(full, args, p)
                }
                return PatCtor(full, emptyList(), p)
            }
            // `is Ctor(args)`：非路径构造子解构（Kotlin 风格 is + Rust 结构模式）
            if (peek(Kind.IDENT) && toks.getOrNull(pos + 1)?.kind == Kind.LPAREN) {
                val ctor = next().text
                at(Kind.LPAREN)
                val args = ArrayList<Pattern>()
                if (!peek(Kind.RPAREN)) {
                    while (true) {
                        args += parsePattern()
                        if (peek(Kind.COMMA)) {
                            pos++
                            if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                            continue
                        }
                        break
                    }
                }
                at(Kind.RPAREN)
                return PatCtor(ctor, args, p)
            }
            val ty = parseType()
            val bind = if (peek(Kind.IDENT) && cur().text == "as") { pos++; nameToken() } else null
            return PatIs(ty, bind, p)
        }
        if (t.kind == Kind.ELSE || (t.kind == Kind.IDENT && t.text == "else")) {
            pos++
            return PatElse(p)
        }
        // 元组模式 `(p1, p2)`（T3，决策 61/62）：至少一个逗号才成立，`(p)` 退化为子模式
        if (t.kind == Kind.LPAREN) {
            pos++
            val items = ArrayList<Pattern>()
            items += parsePattern()
            while (peek(Kind.COMMA)) { pos++; items += parsePattern() }
            at(Kind.RPAREN)
            return if (items.size == 1) items[0] else PatTuple(items, p)
        }
        // 字面量模式
        if (t.kind == Kind.INT) { pos++; return PatLit(IntLit(t.text, p), p) }
        if (t.kind == Kind.STR) { pos++; return PatLit(StrLit(t.text, p), p) }
        if (t.kind == Kind.IDENT) {
            val nm = next().text
            if (nm == "_") return PatBind("_", p)
            // v2.1（对齐 Rust 关联项）：`E::A` 枚举成员路径 → 构造子复合名（可选 (模式实参)）
            if (peek(Kind.COLON2)) {
                pos++
                val ctor = nameToken()
                val full = "$nm::$ctor"
                if (peek(Kind.LPAREN)) {
                    pos++
                    if (!peek(Kind.RPAREN)) throw ParseFailure(fileName, cur(), "以 is 开头的构造子解构（类型解构须显式 is：`is E::A(...)`）")
                    at(Kind.RPAREN)
                    return PatCtor(full, emptyList(), p)
                }
                return PatCtor(full, emptyList(), p)
            }
            // 构造子模式：紧跟 `(` 且是已知大写构造子名 → 解构；否则是绑定变量
            // 规则（用户约定）：**解构（带绑定/嵌套）必须显式 `is`**；无 is 的括号模式仅允许空参值比较 `A()`
            if (peek(Kind.LPAREN)) {
                pos++
                if (!peek(Kind.RPAREN)) throw ParseFailure(fileName, cur(), "以 is 开头的构造子解构（类型解构须显式 is：`is Some(v)`）")
                at(Kind.RPAREN)
                return PatCtor(nm, emptyList(), p)
            }
            // 无参构造子（true/false/None）与绑定变量在此同形，由语义层按符号表判定
            return PatCtor(nm, emptyList(), p)
        }
        throw ParseFailure(fileName, t, "模式")
    }

    /** `[q₁, q₂]` 命题列表，供 by / axiom 复用 */
    private fun parseBracketList(): List<Expr> {
        at(Kind.LBRACKET)
        val out = ArrayList<Expr>()
        if (!peek(Kind.RBRACKET)) {
            while (true) {
                out += parseExpr(angle = false)
                if (peek(Kind.COMMA)) {
                    pos++
                    if (peek(Kind.RPAREN) || peek(Kind.RBRACKET) || peek(Kind.RBRACE)) break   // v3.0：尾随逗号
                    continue
                }
                break
            }
        }
        at(Kind.RBRACKET)
        return out
    }

    /** 分支体：`{ 语句 }` 块，或单表达式。stopArrow=true（模板 when 用）在首个顶层 `->` 停——
     *  防 `1 (11..20) -> 2` 的后缀括号调用把后续 arm 吞成一个蕴含链（template when Parser 修复）。 */
    private fun parseBranchBody(stopArrow: Boolean = false): Expr =
        if (peek(Kind.LBRACE)) parseBlock() else parseExpr(angle = false, stopArrow = stopArrow)

    /*************************** v1.1：旧 `\(x) =>` lambda 已移除（BACKSLASH 不再解析） ***************************/

    /** v1.1 Kotlin 化 lambda：`{ a, b -> 语句…; 末表达式 }`。
     *  体支持语句（var/return/赋值/多句），末表达式为返回值；单表达式直接作为 lambda 体。 */
    private fun parseBraceLambda(): Expr {
        val start = pos   // 指向 `{`（未消费）
        at(Kind.LBRACE)
        val ps = ArrayList<String>()
        var hasArrow = false
        if (braceIsLambda(start)) {
            // 显式参数带箭头（`{ a, b -> … }` / `{ -> 7 }`）：读参数表直到箭头
            while (!peek(Kind.DARROW) && !peek(Kind.ARROW)) {
                // P4（v2.1）：`{(a, b) => …}` 单个元组参数解构——编码分量名为 (a,b) 特殊参数名
                ps += if (peek(Kind.LPAREN)) parseLambdaDestructName() else nameToken()
                if (peek(Kind.COMMA)) pos++
            }
            hasArrow = true
            at(if (peek(Kind.DARROW)) Kind.DARROW else Kind.ARROW)
        }
        // 否则（braceHasImplicitBody 触达）：隐式单参，体里的 `_` 引用单参（语法糖）
        val body = parseLambdaBody()
        at(Kind.RBRACE)
        return if (hasArrow) LambdaExpr(ps, body) else LambdaExpr(listOf("_"), body)
    }

    /** lambda 体：语句序列（含 return/var/exprstmt）；仅单条语句且为表达式时退化为表达式体 */
    private fun parseLambdaBody(): Expr {
        val stmts = ArrayList<Stmt>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            stmts += parseStmt()
        }
        return if (stmts.size == 1 && stmts[0] is ExprStmt) (stmts[0] as ExprStmt).expr
        else BlockExpr(stmts)
    }

    /**
     * 从大括号起点判断是否 lambda：深度 1 内首个 `=>`/`->` 之前**只允许名字与逗号**，
     * 且至少有一个名字。防 when 分支体 `{ 1 -> x }` 的分支箭头被误判为尾随 lambda。
     */
    private fun braceIsLambda(start: Int): Boolean {
        var depth = 0
        var pd = 0   // P4（v2.1）：lambda 解构参数的括号配对深度 `{(a, b) => …}`
        var j = start
        var sawBad = false   // 名字表里出现非名字/非逗号 token（如数字）即非 lambda
        while (j < toks.size) {
            val k = toks[j].kind
            when {
                k == Kind.LBRACE -> depth++
                k == Kind.RBRACE -> { depth--; if (depth == 0) return false }
                // 遇箭头：depth 1 且参数表仅由名字/逗号组成（可为空 → 零参 lambda `{ -> 7 }`）
                k == Kind.DARROW || k == Kind.ARROW -> return if (depth == 1) !sawBad else false
                k == Kind.EOF -> return false
                depth == 1 && k == Kind.LPAREN -> pd++
                depth == 1 && k == Kind.RPAREN -> { if (pd > 0) pd-- else sawBad = true }
                depth == 1 && pd > 0 -> {}   // 解构括号内任意（含逗号/标识符）
                depth == 1 && (k == Kind.IDENT || k == Kind.SYMBOL) -> {}
                depth == 1 && k == Kind.COMMA -> {}
                depth == 1 -> sawBad = true   // 混入任何其他 token（如 `{ 1 -> x }` 的数字）即非 lambda
                else -> {}
            }
            j++
        }
        return false
    }

    /** 隐式单参 lambda 诗据：大括号深度 1 内**无箭头/无类型冒号**，即 `{ 表达式 }` 形式——
     *  体里的 `_` 引用隐式单参（语法糖，Kotlin `{ it + 1 }` 同款）。 */
    private fun braceHasImplicitBody(start: Int): Boolean {
        var depth = 0
        var j = start
        while (j < toks.size) {
            val k = toks[j].kind
            when {
                k == Kind.LBRACE -> depth++
                k == Kind.RBRACE -> { depth--; if (depth == 0) return true }
                k == Kind.ARROW || k == Kind.DARROW || k == Kind.COLON -> return false  // 显式参数带箭头→交给 braceIsLambda；`:` 是类型测/类型标注→非隐式体
                k == Kind.EOF -> return false
                else -> {}
            }
            j++
        }
        return false
    }
}

/** P2：一次解析的完整产物——AST + 提示 + 补充（实例化留痕，无全局可变状态）。 */
data class ParseResult(
    val ast: FileAst,
    val hints: List<Pair<String, String>>,
    val supplements: List<Triple<String, String, String>>,
)

/** P2：解析并返回留痕（提示/补充）。调用方需要诊断留痕时用此入口。 */
fun parseSourceWithNotes(src: String, fileName: String = "<anon>"): ParseResult {
    val p = Parser(Lexer(src, fileName).tokenize(), fileName)
    val ast = p.parseFile()
    return ParseResult(ast, p.takeHints(), p.takeSupplements())
}

fun parseSource(src: String, fileName: String = "<anon>"): FileAst = parseSourceWithNotes(src, fileName).ast

fun parseScriptSourceWithNotes(
    src: String,
    fileName: String = "<script>",
    /** 全局类型推导：允许省略形参/字段类型（InferMarker 占位，由解释器 GlobalInference 在 Checker 前替换）。 */
    allowUntyped: Boolean = false,
): ParseResult {
    val p = Parser(Lexer(src, fileName).tokenize(), fileName, scriptMode = true, allowUntyped = allowUntyped)
    val ast = p.parseFile()
    return ParseResult(ast, p.takeHints(), p.takeSupplements())
}

fun parseScriptSource(src: String, fileName: String = "<script>", allowUntyped: Boolean = false): FileAst =
    parseScriptSourceWithNotes(src, fileName, allowUntyped).ast

// ============ 片段解析顶层入口（root doc/注解处理器.md：处理器脚本以源码片段形式编辑 AST） ============

/** 解析单条声明片段（如 "fun f(): Nat = 1" / "struct P(x: Nat)"）。
 *  解析失败抛 ParseFailure；成功要求整段耗尽。 */
fun parseDeclSnippet(src: String, fileName: String = "<snippet>"): Decl {
    val p = Parser(Lexer(src, fileName).tokenize(), fileName, scriptMode = true)
    return p.snippetDecl()
}

/** 解析函数体片段：块体「{ 语句… }」（解析为 BlockExpr）或单个表达式（表达式体右值）。
 *  由注解处理器的 setFunBody 编辑调用。 */
fun parseFunBodySnippet(src: String, fileName: String = "<snippet>"): Expr {
    val p = Parser(Lexer(src, fileName).tokenize(), fileName, scriptMode = true)
    return p.snippetFunBody()
}

/** 解析语句片段（一条或多条，分号/换行分隔）。由注解处理器的 prependStmts 编辑调用。 */
fun parseStmtsSnippet(src: String, fileName: String = "<snippet>"): List<Stmt> {
    val p = Parser(Lexer(src, fileName).tokenize(), fileName, scriptMode = true)
    return p.snippetStmts()
}
