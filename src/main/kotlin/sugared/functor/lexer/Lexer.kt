package sugared.functor.lexer

enum class Kind {
    INT, FLOAT, STR, IDENT, SYMBOL,
    // 硬关键字
    FUN, CLASS, STRUCT, ENUM, VAR, IMPL, FOR, IF, ELSE, UNCHECKED, WHEN, IS, BY, TYPE, RET,
    // 标点
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    COMMA, SEMI, COLON, DOT, AT, BACKSLASH, DOLLAR,
    // 运算/比较/赋值
    PLUS, MINUS, STAR, SLASH, PERCENT, ASSIGN, EQ, NEQ, LE, GE,
    ARROW, DARROW, IFF, AMP, PIPE, BANG,
    // u-系纯命题算符（粘连书写，可中缀；决策 49/A4）
    U_NEQ, U_IMPLIES, U_IFF, U_AND, U_OR,
    // v2.0 空安全运算符（多字符优先：`?.`/`?:` 在 `?` 前）
    // CAST `>:` **不在词法层粘连**——`<valid<7>>: Null` 的 `>>` 终止符序列会误粘连成 `>:`，
    // 破坏上下文命题解析。`a >: T` 由 parser 表达式层合成（见 parseExpr 的 GT 分支）。
    QUEST,        // `?` —— 运行时类型测 `a ? T`（前后均须有空格）或类型后缀 `T?`
    SAFECALL,     // `?.` —— 安全调用 `a?.f(b)` / `a ?.f b`
    ELVIS,        // `?:` —— 空替代 `a ?: b`
    // Unicode 逻辑符号（固定单 token，不做标识符）
    FORALL, EXISTS, EXISTS1, LAND, LOR, LNOT, IMPLIES, EQUIV, TOP, BOT,
    LT, GT, EOF,
}

/**
 * precededBySpace 是邻接规则的基础：紧贴名字的 `<` 是实例化（命题项/供给），
 * 带空格的 `<` `>` 是比较函数。词法层只记录事实，消歧在语法层。
 */
data class Token(
    val kind: Kind,
    val text: String,
    val line: Int,
    val col: Int,
    val precededBySpace: Boolean,
)

class LexFailure(val fileName: String, val line: Int, val col: Int, message: String) :
    RuntimeException("[词法][$fileName:$line:$col] $message")

class Lexer(private val src: String, private val fileName: String) {
    private var i = 0
    private var line = 1
    private var col = 1

    private val keywords = mapOf(
        "fun" to Kind.FUN, "class" to Kind.CLASS, "struct" to Kind.STRUCT,
        "enum" to Kind.ENUM, "var" to Kind.VAR, "impl" to Kind.IMPL,
        "for" to Kind.FOR, "if" to Kind.IF, "else" to Kind.ELSE,
        "unchecked" to Kind.UNCHECKED, "when" to Kind.WHEN, "is" to Kind.IS, "by" to Kind.BY,
        "type" to Kind.TYPE, "return" to Kind.RET,
    )

    companion object {
        /** 逻辑符号是词法 token，禁止并入符号标识符 */
        val logicalSingletons = setOf('∀', '∃', '∧', '∨', '¬', '→', '↔', '⊤', '⊥')

        /** u-系纯命题算符（粘连识别；长者优先） */
        private val uOps = listOf(
            "u<->" to Kind.U_IFF, "u->" to Kind.U_IMPLIES, "u!=" to Kind.U_NEQ,
            "u&" to Kind.U_AND, "u|" to Kind.U_OR,
        )
    }

    fun tokenize(): List<Token> {
        val out = ArrayList<Token>()
        while (true) {
            val hadSpace = skipTrivial()
            if (i >= src.length) break
            val sl = line
            val sc = col
            val (kind, text) = readToken()
            out += Token(kind, text, sl, sc, hadSpace)
        }
        out += Token(Kind.EOF, "<eof>", line, col, true)
        return out
    }

    /** 跳过空白与 `//` 注释；返回是否跳过了任何内容（决定后继 token 的邻接标记）。 */
    private fun skipTrivial(): Boolean {
        var skipped = false
        while (i < src.length) {
            val c = src[i]
            when {
                c == '\uFEFF' -> { adv(); skipped = true }
                c == ' ' || c == '\t' || c == '\r' || c == '\n' -> { adv(); skipped = true }
                c == '/' && i + 1 < src.length && src[i + 1] == '/' -> {
                    while (i < src.length && src[i] != '\n') adv()
                    skipped = true
                }
                else -> return skipped
            }
        }
        return skipped
    }

    private fun adv() {
        if (src[i] == '\n') { line++; col = 1 } else col++
        i++
    }

    private fun peekAt(j: Int): Char? = if (j < src.length) src[j] else null

    private fun readToken(): Pair<Kind, String> {
        val c = src[i]
        if (c in '0'..'9') return readInt()
        if (c == '"') return readString()
        // u-系命题算符：'u' 紧跟符号字符且该符号串是已知算符（`u&` 命中，`unit` 不命中）
        if (c == 'u') uOps.firstOrNull { src.startsWith(it.first, i) }?.let { (op, k) ->
            adv(op.length); return k to op
        }
        if (isAsciiLetter(c) || c == '_') return readIdent()
        if (isSymbolStart(c)) return readSymbol()

        // 多字符优先
        if (c == '<' && peekAt(i + 1) == '-' && peekAt(i + 2) == '>') { adv(3); return Kind.IFF to "<->" }
        if (c == '=' && peekAt(i + 1) == '>') { adv(2); return Kind.DARROW to "=>" }
        if (c == '=' && peekAt(i + 1) == '=') { adv(2); return Kind.EQ to "==" }
        if (c == '!' && peekAt(i + 1) == '=') { adv(2); return Kind.NEQ to "!=" }
        if (c == '<' && peekAt(i + 1) == '=') { adv(2); return Kind.LE to "<=" }
        if (c == '>' && peekAt(i + 1) == '=') { adv(2); return Kind.GE to ">=" }
        if (c == '-' && peekAt(i + 1) == '>') { adv(2); return Kind.ARROW to "->" }
        if (c == '∃' && peekAt(i + 1) == '!') { adv(2); return Kind.EXISTS1 to "∃!" }
        // v2.0 空安全运算符（多字符优先：`?.`/`?:` 在 `?` 前）
        if (c == '?' && peekAt(i + 1) == '.') { adv(2); return Kind.SAFECALL to "?." }
        if (c == '?' && peekAt(i + 1) == ':') { adv(2); return Kind.ELVIS to "?:" }
        if (c == '?') { adv(1); return Kind.QUEST to "?" }

        val one = when (c) {
            '(' -> Kind.LPAREN; ')' -> Kind.RPAREN
            '[' -> Kind.LBRACKET; ']' -> Kind.RBRACKET
            '{' -> Kind.LBRACE; '}' -> Kind.RBRACE
            ',' -> Kind.COMMA; ';' -> Kind.SEMI; ':' -> Kind.COLON; '.' -> Kind.DOT
            '@' -> Kind.AT; '\\' -> Kind.BACKSLASH; '$' -> Kind.DOLLAR
            '+' -> Kind.PLUS; '-' -> Kind.MINUS; '*' -> Kind.STAR; '/' -> Kind.SLASH; '%' -> Kind.PERCENT
            '=' -> Kind.ASSIGN; '<' -> Kind.LT; '>' -> Kind.GT
            '&' -> Kind.AMP; '|' -> Kind.PIPE; '!' -> Kind.BANG
            '∀' -> Kind.FORALL; '∃' -> Kind.EXISTS
            '∧' -> Kind.LAND; '∨' -> Kind.LOR; '¬' -> Kind.LNOT
            '→' -> Kind.IMPLIES; '↔' -> Kind.EQUIV
            '⊤' -> Kind.TOP; '⊥' -> Kind.BOT
            else -> throw LexFailure(fileName, line, col, "非法字符 '$c'")
        }
        adv()
        // `$n` 参数引用（决策 65，T6）：$ 紧贴数字即粘连为一个 token，文本含 $
        if (c == '$' && i < src.length && src[i] in '0'..'9') {
            val sb = StringBuilder("$")
            while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
            return Kind.DOLLAR to sb.toString()
        }
        return one to c.toString()
    }

    private fun adv(n: Int) { repeat(n) { adv() } }

    private fun readInt(): Pair<Kind, String> {
        val sb = StringBuilder()
        while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
        // P10（决策 86）：浮点字面量——数字 + `.` + 数字（`1.0`）→ FLOAT token（`1.` 后无数字、`1.x` 保持 INT+DOT）
        if (i < src.length && src[i] == '.' && peekAt(i + 1) != null && peekAt(i + 1)!! in '0'..'9') {
            sb.append('.'); adv()
            while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
            return Kind.FLOAT to sb.toString()
        }
        return Kind.INT to sb.toString()
    }

    private fun readString(): Pair<Kind, String> {
        val sl = line; val sc = col
        adv() // 开引号
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw LexFailure(fileName, sl, sc, "字符串未闭合")
            val c = src[i]
            when {
                c == '"' -> { adv(); return Kind.STR to sb.toString() }
                c == '\\' -> {
                    adv()
                    if (i >= src.length) throw LexFailure(fileName, sl, sc, "字符串未闭合")
                    val e = when (src[i]) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'
                        '"' -> '"'; '\\' -> '\\'; else -> src[i]
                    }
                    sb.append(e); adv()
                }
                else -> { sb.append(c); adv() }
            }
        }
    }

    private fun readIdent(): Pair<Kind, String> {
        val sb = StringBuilder()
        while (i < src.length && (isAsciiLetter(src[i]) || src[i] in '0'..'9' || src[i] == '_')) {
            sb.append(src[i]); adv()
        }
        val text = sb.toString()
        return (keywords[text] ?: Kind.IDENT) to text
    }

    /** 非 ASCII 的符号字符（数学符号等），但逻辑单 token 除外 */
    private fun isSymbolStart(c: Char): Boolean =
        c.code > 127 && !c.isWhitespace() && !c.isLetterOrDigit() && c !in logicalSingletons

    /** 读连续符号字符为一个 SYMBOL 标识符，遇字母/数字/空白/逻辑单 token 停止 */
    private fun readSymbol(): Pair<Kind, String> {
        val sb = StringBuilder()
        while (i < src.length) {
            val c = src[i]
            if (isSymbolStart(c)) { sb.append(c); adv() } else break
        }
        return Kind.SYMBOL to sb.toString()
    }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
}
