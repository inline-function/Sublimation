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
class Parser(private val toks: List<Token>, private val fileName: String) {
    private var pos = 0

    companion object {
        // 指导思路：`=` 命题等式、`:` 类型测（均可中缀）；`==` `!=` 是 Bool 函数，
        // v1.1 Kotlin 化：EQ/NEQ 允许中缀（a == b / a != b，值相等），前缀 ==(a,b) 仍保留。
        val binops = setOf(
            Kind.PLUS, Kind.MINUS, Kind.STAR, Kind.SLASH, Kind.PERCENT, Kind.ASSIGN, Kind.COLON,
            Kind.LE, Kind.GE, Kind.AMP, Kind.PIPE, Kind.ARROW, Kind.IFF,
            Kind.EQ, Kind.NEQ,
            Kind.LAND, Kind.LOR, Kind.IMPLIES, Kind.EQUIV,
            Kind.U_NEQ, Kind.U_IMPLIES, Kind.U_IFF, Kind.U_AND, Kind.U_OR,
        )
        // P0 补全（决策 88）：可作函数名前缀调用的运算符 token（`+(1,2)` 形态）。
        // 不含 MINUS（一元负优先）、symbolCallStart（`==(...)` 等已走 SymbolCallExpr 内建符号路径）。
        private val opPrefixKinds = setOf(
            Kind.PLUS, Kind.STAR, Kind.SLASH, Kind.PERCENT, Kind.AMP, Kind.PIPE, Kind.ARROW, Kind.IFF,
        )
        private val symbolCallStart = setOf(Kind.LT, Kind.GT, Kind.EQ, Kind.NEQ, Kind.LE, Kind.GE)
        private val asciiQuant = mapOf("forall" to "∀", "exists" to "∃", "exists1" to "∃!")
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

    fun parseFile(): FileAst {
        val entries = ArrayList<Entry>()
        while (true) {
            skipSemis()
            if (peek(Kind.EOF)) break
            entries += parseEntry()
        }
        return FileAst(entries)
    }

    private fun skipSemis() { while (peek(Kind.SEMI)) pos++ }

    /**
     * 决策 74（Kotlin 化收口）：**顶层只允许声明**，执行代码必须写进 `fun main()`。
     * 凝华脚本模式（类 kts，顶层即主序列）列为将来特性，当前语法层直接拒绝。
     */
    private fun parseEntry(): Entry = when (cur().kind) {
        Kind.FUN, Kind.CLASS, Kind.STRUCT, Kind.ENUM, Kind.IMPL, Kind.TYPE -> DeclEntry(parseDecl())
        Kind.AT -> {
            val save = pos
            val anns = parseAnnotations()
            if (cur().kind == Kind.FUN) DeclEntry(parseFun(anns))
            else { pos = save; throw noTopLevel() }
        }
        else -> throw noTopLevel()
    }

    private fun noTopLevel(): ParseFailure =
        ParseFailure(fileName, cur(), "顶层只能写声明，执行代码请放进 `fun main()`（脚本模式后续支持）")

    fun parseDecl(): Decl = when (cur().kind) {
        Kind.FUN -> parseFun(emptyList())
        Kind.CLASS -> parseClass()
        Kind.STRUCT -> parseStruct()
        Kind.ENUM -> parseEnum()
        Kind.IMPL -> parseImpl()
        Kind.TYPE -> parseTypeAlias()
        else -> throw ParseFailure(fileName, cur(), "声明")
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

    private fun parseAnnotations(): List<String> {
        val out = ArrayList<String>()
        while (peek(Kind.AT)) { pos++; out += at(Kind.IDENT).text }
        return out
    }

    private fun parseFun(anns: List<String>): FunDecl {
        at(Kind.FUN)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        val params = if (peek(Kind.LPAREN)) parseParams() else emptyList()
        // 用户拍板（v1.1）：`:` 是上下（前后置）上下文的分界——
        // 冒号**前**的 `<...>` = 上文（前置，调用需已证明）；返回类型**后**的 `<...>` = 下文（后置，调用产出）。
        // 返回类型可省略：冒号后直接 `<...>` / 无返回类型（此前后置须写 `: Null <p>`，现可写 `: <p>`）。
        val preps = if (peek(Kind.LT)) parseAngleList() else emptyList()
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
        return FunDecl(name, anns, theory, params, preps, ret, posts, body)
    }

    private fun parseTheory(): List<TheoryParam> {
        at(Kind.LBRACKET)
        val out = ArrayList<TheoryParam>()
        while (true) {
            if (peek(Kind.AT)) { pos++; out += PropParam(nameToken()) }
            else {
                val n = nameToken()
                val c = if (peek(Kind.COLON)) { pos++; nameToken() } else null
                out += TypeParam(n, c)
            }
            if (peek(Kind.COMMA)) { pos++; continue }
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
                // 形参注解（如 @tuple，决策 61）：@ 前缀出现在名字位即为注解列表
                val anns = ArrayList<String>()
                while (peek(Kind.AT)) { pos++; anns += at(Kind.IDENT).text }
                val n = nameToken()
                at(Kind.COLON)
                val ty = parseType()
                // O3（决策 69）：`= expr` 是字段默认值。解析层一律接受，合法性由 checker 判
                //（仅 struct 字段允许；函数形参带默认值报 E-DEFAULT-PARAM）
                val dft = if (peek(Kind.ASSIGN)) { pos++; parseExpr(angle = false) } else null
                out += Param(n, ty, anns, dft)
                if (peek(Kind.COMMA)) { pos++; continue }
                break
            }
        }
        at(Kind.RPAREN)
        return out
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
                if (peek(Kind.COMMA)) { pos++; continue }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    private fun parseType(): Type {
        // 类型位的 `(`：`(A,B)=>C` 是函数类型；无 `=>` 跟随则按元组类型 `(T,U)`（决策 57/61，T3）
        val base = if (peek(Kind.LPAREN)) parseFunOrTupleType() else parseNamedType()
        // v2.0 空安全（决策 88-89）：`T?` 后缀 = `Optional[T]` 语法糖；`T??` 嵌套 = Optional[Optional[T]]。
        // `?` 紧贴类型名（无空格）才是后缀；带空格的 `?` 是运行时类型测中缀（由表达式层处理）。
        var t = base
        while (peek(Kind.QUEST) && !cur().precededBySpace) {
            pos++
            t = namedT("Optional", listOf(t))
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
                if (peek(Kind.COMMA)) { pos++; continue }
                break
            }
        }
        at(Kind.RPAREN)
        return if (peek(Kind.DARROW)) { pos++; FunType(ps, parseType()) }   // ()=>C / (A,B)=>C
        else TupleType(ps)                                                   // (T,U)；()=> 已归函数
    }

    private fun parseTypeArgs(): List<Type> {
        val out = ArrayList<Type>()
        while (true) {
            out += parseType()
            if (peek(Kind.COMMA)) { pos++; continue }
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
            if (peek(Kind.COMMA)) { pos++; continue }
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

    private fun parseStmt(): Stmt {
        if (peek(Kind.UNCHECKED)) return parseUnchecked()
        if (peek(Kind.RET)) return parseReturn()
        if (peek(Kind.BY)) { pos++; return ByStmt(parseBracketList()) }
        if (peek(Kind.AT)) {
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
                    if (peek(Kind.COMMA)) { pos++; continue }
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
        at(Kind.VAR)
        val name = nameToken()
        val ty = if (peek(Kind.COLON)) { pos++; parseType() } else null
        at(Kind.ASSIGN)
        val v = parseExpr(angle = false)
        return VarStmt(name, anns, ty, v)
    }

    /** var 声明即表达式（A3）：值为初值，支持 `var n = var m = 0` 嵌套 */
    private fun parseVarExpr(anns: List<String>): VarExpr {
        at(Kind.VAR)
        val name = nameToken()
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

    private fun parseClass(): ClassDecl {
        at(Kind.CLASS)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        at(Kind.LBRACE)
        val members = ArrayList<FunDecl>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val anns = parseAnnotations()
            members += parseFun(anns)
        }
        at(Kind.RBRACE)
        return ClassDecl(name, theory, members)
    }

    private fun parseStruct(): StructDecl {
        at(Kind.STRUCT)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
        // O3（决策 69）：struct 体是圆括号参数列表；大括号明确拒绝并给出改写指引
        if (peek(Kind.LBRACE))
            throw ParseFailure(fileName, cur(), "struct 体是参数列表，请改写为 $name(字段: 类型 = 默认值)")
        val fields = parseParams()
        return StructDecl(name, theory, fields)
    }

    private fun parseEnum(): EnumDecl {
        at(Kind.ENUM)
        val name = nameToken()
        val theory = if (peek(Kind.LBRACKET)) parseTheory() else emptyList()
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
                if (peek(Kind.COMMA)) { pos++; continue }
                break
            }
        }
        at(Kind.RPAREN)
        return out
    }

    private fun parseImpl(): ImplDecl {
        at(Kind.IMPL)
        val trait = parseType()
        val self = if (peek(Kind.FOR)) { pos++; parseType() } else trait
        at(Kind.LBRACE)
        val members = ArrayList<FunDecl>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val anns = parseAnnotations()
            members += parseFun(anns)
        }
        at(Kind.RBRACE)
        return ImplDecl(trait, self, members)
    }

    // ---------- 表达式 ----------

    private fun parseExpr(angle: Boolean, stopArrow: Boolean = false): Expr {
        var left = parseUnary()
        while (true) {
            val t = cur()
            // when 守卫在首个顶层 `->` 处停（`->` 既是蕴含中缀又是分支箭头，蕴含需加括号）
            if (stopArrow && t.kind == Kind.ARROW) break
            if (t.kind == Kind.BY) { pos++; left = ByExpr(left, parseBracketList()); continue }
            if (t.kind == Kind.GT) {
                if (angle) break
                pos++; left = BinExpr(">", left, parseUnary()); continue
            }
            if (t.kind == Kind.LT) {
                if (!t.precededBySpace) break   // 紧贴的 < 属于 postfix 实例化
                pos++; left = BinExpr("<", left, parseUnary()); continue
            }
            if (t.kind in binops) {
                pos++; left = BinExpr(t.text, left, parseUnary()); continue
            }
            // 两参解糖：带空格的符号名中缀 `q ○ p` → BinExpr("○")，左结合、无优先级
            if (t.kind == Kind.SYMBOL && t.precededBySpace) {
                pos++; left = BinExpr(t.text, left, parseUnary()); continue
            }
            // P0 补全（决策 88）：标识符两参中缀调用 `a f b`（f 前后均有空格，等价 f(a, b)）。
            // 右操作数也须带空格前缀，避免误吞 `f(x)`（函数调用）与相邻表达式；且要求**同一行**
            // （换行也带 precededBySpace，否则 `1\nx = 2` 的换行 x 会被误当中缀）。
            if (t.kind == Kind.IDENT && t.precededBySpace && infixOperandAhead()) {
                val name = next().text
                left = CallExpr(NameRef(name), listOf(left, parseUnary()))
                continue
            }
            break
        }
        return left
    }

    /** P0 补全（决策 88）：标识符中缀要求右操作数也带空格前缀，且 f 与左右操作数同在一行（`a f b` 成立；`f(x)` 与跨行 `1\nx` 不是中缀）。
     *  右操作数还必须是合法表达式起始 token——排除 `->`（when 分支分隔/蕴含）、`,`、`}` 等，
     *  避免把 when 分支体的下一个模式名（`x None -> d`）误当中缀。 */
    private fun infixOperandAhead(): Boolean {
        val nx = toks.getOrNull(pos + 1) ?: return false
        if (!nx.precededBySpace) return false
        if (cur().line != nx.line) return false
        val prev = toks.getOrNull(pos - 1)
        if (prev != null && prev.line != cur().line) return false
        if (nx.kind == Kind.ARROW || nx.kind == Kind.COMMA || nx.kind == Kind.RBRACE ||
            nx.kind == Kind.RBRACKET || nx.kind == Kind.SEMI || nx.kind == Kind.RPAREN ||
            nx.kind == Kind.ASSIGN || nx.kind == Kind.RET) return false
        return true
    }

    private fun parseUnary(): Expr {
        val t = cur()
        if (t.kind == Kind.MINUS) { pos++; return UniExpr("-", parseUnary()) }
        if (t.kind == Kind.BANG) { pos++; return UniExpr("!", parseUnary()) }
        if (t.kind == Kind.LNOT) { pos++; return UniExpr("¬", parseUnary()) }
        if (t.kind == Kind.IDENT && t.text == "not") { pos++; return UniExpr("¬", parseUnary()) }
        return parsePostfix()
    }

    private fun parsePostfix(): Expr {
        var e = parsePrimary()
        while (true) {
            val t = cur()
            when {
                t.kind == Kind.DOT -> { pos++; e = FieldExpr(e, nameToken()) }
                t.kind == Kind.LPAREN -> {
                    // O3（决策 69）：大写名字 + `()`/`(IDENT = …)` 形态 → 命名字段构造；其余仍走普通调用
                    if (e is NameRef && e.name.first().isUpperCase() && looksLikeNamedCtor())
                        e = StructCtorExpr(e.name, parseStructCtorArgs())
                    else {
                        val (pa, na) = parseArgs()
                        e = CallExpr(e, pa, namedArgs = na)
                    }
                }
                t.kind == Kind.LT && !t.precededBySpace -> {
                    val save = pos
                    try { e = InstExpr(e, parseAngleList()) }
                    catch (f: ParseFailure) { pos = save; break }
                }
                t.kind == Kind.LBRACE && braceIsLambda(pos) -> {
                    // v1.1 尾随 lambda 语法糖（Kotlin）：`f(a) { x -> … }` 追加为**最后**位置实参
                    val lam = parseBraceLambda()
                    e = if (e is CallExpr) e.copy(args = e.args + lam) else CallExpr(e, listOf(lam))
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
                if (peek(Kind.COMMA)) { pos++; continue }
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

    private fun parsePrimary(): Expr {
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
                    while (peek(Kind.COMMA)) { pos++; items += parseExpr(angle = false) }
                    at(Kind.RPAREN)
                    TupleExpr(items, "${t.line}:${t.col}")
                } else { at(Kind.RPAREN); first }
            }
            t.kind == Kind.LBRACE && braceIsLambda(pos) -> parseBraceLambda()
            t.kind == Kind.VAR -> parseVarExpr(emptyList())
            t.kind == Kind.AT -> { val a = parseAnnotations(); if (peek(Kind.VAR)) parseVarExpr(a) else throw ParseFailure(fileName, cur(), "var") }
            t.kind == Kind.FUN -> parseAnonFunExpr()
            t.kind == Kind.IF -> parseIf()
            t.kind == Kind.WHEN -> parseWhen()
            t.kind == Kind.FORALL || t.kind == Kind.EXISTS || t.kind == Kind.EXISTS1 ->
                parseQuant(next().text)
            t.kind == Kind.IDENT && t.text in asciiQuant &&
                toks.getOrNull(pos + 1)?.kind == Kind.IDENT ->
                parseQuant(asciiQuant.getValue(next().text))
            t.kind == Kind.IDENT || t.kind == Kind.SYMBOL -> { pos++; NameRef(t.text) }
            t.kind in symbolCallStart && toks.getOrNull(pos + 1)?.kind == Kind.LPAREN -> {
                val op = next().text
                SymbolCallExpr(op, parseArgs().first)   // P8：符号调用只取位置实参（命名通道不适用）
            }
            // P0 补全（决策 88）：运算符前缀调用 `+(1,2)` / `○(a,b,c)`——当作普通函数名调用，
            // 交给 checkCall 按名解析（用户 `fun +` 命中；内置四则请继续用中缀）。
            t.kind in opPrefixKinds && toks.getOrNull(pos + 1)?.kind == Kind.LPAREN -> {
                val op = next().text
                val (pa, na) = parseArgs()
                CallExpr(NameRef(op), pa, namedArgs = na)
            }
            else -> throw ParseFailure(fileName, t, "表达式")
        }
    }

    private fun parseQuant(q: String): Expr {
        val names = ArrayList<String>()
        while (true) {
            names += nameToken()
            if (peek(Kind.COMMA)) { pos++; continue }
            break
        }
        at(Kind.DOT)
        return QuantExpr(q, names, parseExpr(angle = false))
    }

    private fun parseIf(): Expr {
        at(Kind.IF)
        // v1.1 Kotlin 化：if 条件必须用 ()；then/else 分支可单表达式（不带 {}）或块
        at(Kind.LPAREN)
        val cond = parseExpr(angle = false)
        at(Kind.RPAREN)
        val thenB = parseBranchBlock()
        val elseB = if (peek(Kind.ELSE)) { pos++; parseBranchBlock() } else null
        return IfExpr(cond, thenB, elseB)
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
        at(Kind.LPAREN)
        var binding: String? = null
        val subject = if (peek(Kind.VAR)) {
            pos++
            binding = nameToken()
            at(Kind.ASSIGN)
            parseExpr(angle = false)
        } else parseExpr(angle = false)
        at(Kind.RPAREN)
        at(Kind.LBRACE)
        val arms = ArrayList<WhenArm>()
        while (!peek(Kind.RBRACE)) {
            skipSemis()
            if (peek(Kind.RBRACE)) break
            val al = cur().line; val ac = cur().col
            val pat = parsePattern()
            val guard = if (peek(Kind.IF)) { pos++; parseExpr(angle = false, stopArrow = true) } else null
            at(Kind.ARROW)
            arms += WhenArm(pat, guard, parseBranchBody(), "$al:$ac")
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
            // 构造子模式：紧跟 `(` 且是已知大写构造子名 → 解构；否则是绑定变量
            if (peek(Kind.LPAREN)) {
                pos++
                val args = ArrayList<Pattern>()
                if (!peek(Kind.RPAREN)) {
                    while (true) {
                        args += parsePattern()
                        if (peek(Kind.COMMA)) { pos++; continue }
                        break
                    }
                }
                at(Kind.RPAREN)
                return PatCtor(nm, args, p)
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
                if (peek(Kind.COMMA)) { pos++; continue }
                break
            }
        }
        at(Kind.RBRACKET)
        return out
    }

    private fun parseBranchBody(): Expr =
        if (peek(Kind.LBRACE)) parseBlock() else parseExpr(angle = false)

    /*************************** v1.1：旧 `\(x) =>` lambda 已移除（BACKSLASH 不再解析） ***************************/

    /** v1.1 Kotlin 化 lambda：`{ a, b -> 语句…; 末表达式 }`。
     *  体支持语句（var/return/赋值/多句），末表达式为返回值；单表达式直接作为 lambda 体。 */
    private fun parseBraceLambda(): Expr {
        at(Kind.LBRACE)
        val ps = ArrayList<String>()
        while (!peek(Kind.DARROW) && !peek(Kind.ARROW)) {
            ps += nameToken()
            if (peek(Kind.COMMA)) pos++
        }
        if (peek(Kind.DARROW)) at(Kind.DARROW) else at(Kind.ARROW)
        val body = parseLambdaBody()
        at(Kind.RBRACE)
        return LambdaExpr(ps, body)
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
                depth == 1 && (k == Kind.IDENT || k == Kind.SYMBOL) -> {}
                depth == 1 && k == Kind.COMMA -> {}
                depth == 1 -> sawBad = true   // 混入任何其他 token（如 `{ 1 -> x }` 的数字）即非 lambda
                else -> {}
            }
            j++
        }
        return false
    }
}

fun parseSource(src: String, fileName: String = "<anon>"): FileAst =
    Parser(Lexer(src, fileName).tokenize(), fileName).parseFile()
