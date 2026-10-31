package sugared.functor.subs

import kotlin.math.pow

// =====================================================================================
// SubsInterpreter —— Sublimation 单文件解释器（t84）
// =====================================================================================
// 用途：foresight《深谋远虑》模组加载器的桌面端运行时；以 @root 内建（sublEval/sublRun）
// 挂进 Sublimation 标准库，让 SUBL 程序在运行时求值 .subl 源码（模组热装载）；宿主可注入
// 游戏状态对象，模组逻辑按名字直接访问（无白名单 op）。
//
// 约束与对齐（2026-10 与编译器核心3 逐条对齐，以编译器 codegen 运行时语义为准）：
//   * 值表示对齐 JsCodeGen 产物：Nat/Rat 统一 float64（IEEE double，JS Number 同构）；
//     枚举 ctor = {tag, 位置参数}；struct = {tag, 命名字段}；List（枚举名恰为 List）与
//     元组 = JS 数组；Optional 的 Some/None、Range 为内置小运行时。
//   * when 模式匹配：PatLit 严格相等；PatCtor 按 tag（List ctor 走数组分支，struct ctor
//     按字段名解构）；PatIs 纯结构判定（__isa 语义：Nat/Int/Rat=number、Str=string、
//     Bool=boolean、Null、(fn)、List=Array.isArray，其余按 tag 名，无名义区分）。
//   * @mut 拷贝四规则（根文档《副作用与可变性控制》修正案）与编译侧 binderNeedsCopy/__dc
//     同构：深拷数组/枚举/结构体，函数值透传（编译模式 JS 闭包不透明，不做闭包捕获深拷——
//     与脚本旧解释器的 ClosureFun.copyCapture 分歧，此处遵循编译侧）。
//   * 算术：+ - * % 数值合一（float64）；/ 恒浮点除（Rat）；^ 幂；无溢出检查；除零得
//     Infinity/NaN（对齐 JS）。
//   * 字符串插值脱糖为 concat(toStr(...)...) 链；toStr/print 按 __dshtoStr 展示
//     （数组→"[a,b]" 逗号无空格；单键 tag 对象→tag 名；其余按 JS String()）。
//   * 错误传播：无自动传播运算符；Result/Optional 是普通枚举，一律显式 when 解构。
//
// 覆盖范围（v1）：编译器当前支持范围的可求值子集——三公理基础（函数/构造器/类型/when
// 守卫/列表/map/fold/纯函数与 @mut 局部可变）、字符串/数值/Bool/Rat、错误传播。
// 不覆盖（对齐编译侧运行时同样行进的边界，后续按需扩展）：
//   * 异步（Task/Flow/Channel/Mutex）——有独立 JS 运行时，本解释器抛运行时不支持；
//   * 命题位证明链（axiom/∵∴/策略/量词）——零运行时效果，解析为 NoopStmt；
//   * 模块系统（目录即模块）——宿主侧 loadModule 显式喂源码。
//
// 文件分区：
//   §0 值域与公共工具    §1 词法    §2 语法（AST + Parser）    §3 求值器
//   §4 内建 @root 表     §5 宿主互操作    §6 对外 API
//
// 纯 Kotlin，零平台依赖：JVM 可测；可被 Kotlin/JS 编译进浏览器壳（仅依赖 Kotlin 标准库）。
// =====================================================================================

// =====================================================================================
// §0 值域与公共工具（对齐 compiled JS 运行时表示）
// =====================================================================================

/** 运行时值。表示与 JsCodeGen 产物一一对应，保证解释==编译的交叉验证成立。 */
sealed class V {
    /** Nat/Rat 统一：JS Number = IEEE 754 double（编译侧同构，2^53 以内精确；见对齐注）。 */
    class Num(val v: Double) : V() {
        override fun toString(): String = jsNum(v)
    }

    class Str(val v: String) : V() {
        override fun toString(): String = v
    }

    class Bool(val v: Boolean) : V() {
        override fun toString(): String = if (v) "true" else "false"
    }

    object Null : V() {
        override fun toString(): String = "null"
    }

    /** 数组化 List（枚举名 List 的 Nil/Cons）与元组：编译侧均为 JS 数组，运行时不可区分。 */
    class Arr(val items: MutableList<V>) : V() {
        override fun toString(): String = "[" + items.joinToString(",") { dshStr(it) } + "]"
    }

    /** 枚举构造子：编译侧 {tag: Name, 0: a0, 1: a1, ...}，值按位置存。 */
    class TagCtor(val name: String, val args: List<V>) : V() {
        override fun toString(): String = if (args.isEmpty()) name else
            name + "(" + args.joinToString(",") { dshStr(it) } + ")"
    }

    /** 结构体实例：编译侧 {tag: Name, 字段按名: ...}（与枚举的位置存参相区分）。 */
    class Obj(val typeName: String, val fields: MutableMap<String, V>) : V() {
        override fun toString(): String = "[object $typeName]"
    }

    /** 闭包/函数值。JS 闭包不透明：== 按身份；__dc 深拷透传。 */
    class Fun(val fn: (List<V>) -> V) : V() {
        override fun toString(): String = "[object Function]"
    }

    /** 宿主注册的函数（游戏侧 @root 实现等）。 */
    class HostFn(val name: String, val fn: (List<V>) -> V) : V() {
        override fun toString(): String = "[object Function]"
    }

    /** 宿主注入的游戏状态对象（模组逻辑直接访问字段/调方法）。 */
    class HostObj(val name: String, val obj: SubsHostObject) : V() {
        override fun toString(): String = "[object $name]"
    }

    /** 惰性区间：编译侧 __range 小运行时（in/contains O(1)，map/fold/toList 按需展开）。 */
    class Range(val lo: Double, val hi: Double) : V() {
        override fun toString(): String = "[object Object]"
    }
}

/** 宿主注入对象协议：字段读写 + 方法调用（JVM/JS/Android 宿主各自实现）。 */
interface SubsHostObject {
    /** 字段读（world.player）；不存在返回 null。 */
    fun getField(field: String): V?
    /** 字段写（world.score = 3）；不可写宿主可忽略。 */
    fun setField(field: String, v: V)
    /** 方法调用（world.spawn(...)））；不存在返回 null。 */
    fun callMethod(name: String, args: List<V>): V?
}

// ---- 数字格式化：复刻 JS String(number)（__dshtoStr/print 的展示口径） ----
// 整数部分为整且 |v| < 1e21 → 无小数点打印；否则最短往返十进制，指数式 e±n。
internal fun jsNum(v: Double): String = when {
    v.isNaN() -> "NaN"
    v.isInfinite() -> if (v < 0) "-Infinity" else "Infinity"
    else -> {
        val integral = v == kotlin.math.floor(v) && kotlin.math.abs(v) < 1e21
        if (integral) kotlin.math.round(v).toLong().toString()      // < 1e21 整数值，Long 精确
        else {
            val s = v.toString()                                    // Kotlin 最短往返
            if ('E' !in s) s
            else {
                val e = s.indexOf('E')
                val mantissa = s.substring(0, e).let { if (it.endsWith(".0")) it.dropLast(2) else it }
                val exp = s.substring(e + 1)
                mantissa + "e" + (if (exp.startsWith('-')) exp else "+$exp")
            }
        }
    }
}

/** __dshtoStr：null→"null"；数组→"[a,b]"（逗号无空格，递归）；单键 tag 对象→tag 名；
 *  其余按 JS String()（结构体/Range/函数→"[object ...]"，数字按 jsNum）。 */
internal fun dshStr(v: V): String = when (v) {
    is V.Num -> jsNum(v.v)
    is V.Str -> v.v
    is V.Bool -> if (v.v) "true" else "false"
    V.Null -> "null"
    is V.Arr -> "[" + v.items.joinToString(",") { dshStr(it) } + "]"
    is V.TagCtor -> if (v.args.isEmpty()) v.name else v.name + "(" + v.args.joinToString(",") { dshStr(it) } + ")"
    is V.Obj -> "[object Object]"
    is V.Fun -> "[object Function]"
    is V.HostFn -> "[object Function]"
    is V.HostObj -> "[object Object]"
    is V.Range -> "[object Object]"
}

/** 运行时结构相等（编译侧 __eq / == 运算符）：数值按 double，容器/结构递归，函数按身份。 */
internal fun valEq(a: V, b: V): Boolean = when {
    a is V.Num && b is V.Num -> a.v == b.v
    a is V.Str && b is V.Str -> a.v == b.v
    a is V.Bool && b is V.Bool -> a.v == b.v
    a === V.Null && b === V.Null -> true
    a is V.Arr && b is V.Arr ->
        a.items.size == b.items.size && a.items.zip(b.items).all { (x, y) -> valEq(x, y) }
    a is V.TagCtor && b is V.TagCtor ->
        a.name == b.name && a.args.size == b.args.size && a.args.zip(b.args).all { (x, y) -> valEq(x, y) }
    a is V.Obj && b is V.Obj ->
        a.typeName == b.typeName && a.fields.size == b.fields.size &&
            a.fields.all { (k, x) -> b.fields[k] != null && valEq(x, b.fields[k]!!) }
    a is V.Range && b is V.Range -> a.lo == b.lo && a.hi == b.hi
    else -> a === b
}

/** 值的运行时类型名（is 判定与方法字典分派的接收者 tag）。 */
internal fun typeTag(v: V): String = when (v) {
    is V.Num -> "Nat"          // Nat/Rat 运行时同为 number；__isa 三者同判（无名义区分）
    is V.Str -> "Str"
    is V.Bool -> "Bool"
    V.Null -> "Null"
    is V.Arr -> "List"
    is V.TagCtor -> v.name
    is V.Obj -> v.typeName
    is V.Fun, is V.HostFn -> "Fun"
    is V.HostObj -> v.name
    is V.Range -> "Range"
}

/** __isa 结构判定（编译侧 JsCodeGen:300-308 同构）：基本类型 typeof，枚举/具名按 tag。 */
internal fun isa(v: V, t: String): Boolean = when (t) {
    "Nat", "Int", "Rat" -> v is V.Num
    "Str" -> v is V.Str
    "Bool" -> v is V.Bool
    "Null" -> v === V.Null
    "(fn)" -> v is V.Fun || v is V.HostFn
    "List" -> v is V.Arr
    "Any" -> true
    else -> when (v) {
        is V.TagCtor -> v.name == t
        is V.Obj -> v.typeName == t
        else -> false
    }
}

// =====================================================================================
// §1 词法（对齐 frontend Lexer.kt：单遍最长匹配 + precededBySpace 邻接标记）
// =====================================================================================

internal enum class K {
    INT, FLOAT, STR, IDENT, SYMBOL,
    FUN, CLASS, STRUCT, ENUM, VAR, IMPL, FOR, IF, ELSE, UNCHECKED, WHEN, IS, BY, TYPE, RET, DEF,
    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE,
    COMMA, SEMI, COLON, COLON2, DOT, AT, BACKSLASH, DOLLAR, HASH,
    PLUS, MINUS, STAR, SLASH, PERCENT, POW, ASSIGN, EQ, NEQ, LE, GE, RANGE,
    ARROW, DARROW, IFF, AMP, PIPE, BANG,
    FORALL, EXISTS, EXISTS1, LAND, LOR, LNOT, IMPLIES, EQUIV, TOP, BOT,
    BECAUSE, THEREFORE, PLUS_BECAUSE,
    LT, GT, EOF,
}

internal class LexFailure(fileName: String, line: Int, col: Int, message: String) :
    RuntimeException("[词法][$fileName:$line:$col] $message")

internal data class Tk(val kind: K, val text: String, val line: Int, val col: Int, val space: Boolean)

internal class Lexer(private val src: String, private val fileName: String) {
    private var i = 0
    private var line = 1
    private var col = 1

    private val keywords = mapOf(
        "fun" to K.FUN, "class" to K.CLASS, "struct" to K.STRUCT,
        "enum" to K.ENUM, "var" to K.VAR, "impl" to K.IMPL,
        "for" to K.FOR, "if" to K.IF, "else" to K.ELSE,
        "unchecked" to K.UNCHECKED, "when" to K.WHEN, "is" to K.IS, "by" to K.BY,
        "type" to K.TYPE, "return" to K.RET, "def" to K.DEF,
    )

    fun tokenize(): List<Tk> {
        val out = ArrayList<Tk>()
        while (true) {
            val hadSpace = skipTrivial()
            if (i >= src.length) break
            val sl = line; val sc = col
            val (kind, text) = readToken()
            out += Tk(kind, text, sl, sc, hadSpace)
        }
        out += Tk(K.EOF, "<eof>", line, col, true)
        return out
    }

    private fun skipTrivial(): Boolean {
        var skipped = false
        while (i < src.length) {
            when (val c = src[i]) {
                '\uFEFF' -> { adv(); skipped = true }
                ' ', '\t', '\r', '\n' -> { adv(); skipped = true }
                '/' -> if (i + 1 < src.length && src[i + 1] == '/') {
                    while (i < src.length && src[i] != '\n') adv(); skipped = true
                } else return skipped
                else -> return skipped
            }
        }
        return skipped
    }

    private fun adv() {
        if (src[i] == '\n') { line++; col = 1 } else col++
        i++
    }

    private fun adv(n: Int) = repeat(n) { adv() }

    private fun peekAt(j: Int): Char? = if (j < src.length) src[j] else null

    private fun readToken(): Pair<K, String> {
        val c = src[i]
        if (c in '0'..'9') return readInt()
        if (c == '"') return readString()
        if (c == '`') return readBacktickIdent()
        val isBecauseSym = c == '∵' || c == '∴'
        if (!isBecauseSym && (c == '_' || c.isLetter())) return readIdent()
        if (!isBecauseSym && isSymbolStart(c)) return readSymbol()
        if (c == '<' && peekAt(i + 1) == '-' && peekAt(i + 2) == '>') { adv(3); return K.IFF to "<->" }
        if (c == '=' && peekAt(i + 1) == '>') { adv(2); return K.DARROW to "=>" }
        if (c == '=' && peekAt(i + 1) == '=') { adv(2); return K.EQ to "==" }
        if (c == '!' && peekAt(i + 1) == '=') { adv(2); return K.NEQ to "!=" }
        if (c == '<' && peekAt(i + 1) == '=') { adv(2); return K.LE to "<=" }
        if (c == '>' && peekAt(i + 1) == '=') { adv(2); return K.GE to ">=" }
        if (c == '-' && peekAt(i + 1) == '>') { adv(2); return K.ARROW to "->" }
        if (c == '∃' && peekAt(i + 1) == '!') { adv(2); return K.EXISTS1 to "∃!" }
        if (c == '+' && peekAt(i + 1) == '∵') { adv(2); return K.PLUS_BECAUSE to "+∵" }
        if (c == '.' && peekAt(i + 1) == '.') { adv(2); return K.RANGE to ".." }
        if (c == ':' && peekAt(i + 1) == ':') { adv(2); return K.COLON2 to "::" }
        if (c == '&' && peekAt(i + 1) == '&') { adv(2); return K.LAND to "∧" }
        if (c == '|' && peekAt(i + 1) == '|') { adv(2); return K.LOR to "∨" }
        val one = when (c) {
            '(' -> K.LPAREN; ')' -> K.RPAREN
            '[' -> K.LBRACKET; ']' -> K.RBRACKET
            '{' -> K.LBRACE; '}' -> K.RBRACE
            ',' -> K.COMMA; ';' -> K.SEMI; ':' -> K.COLON; '.' -> K.DOT
            '@' -> K.AT; '\\' -> K.BACKSLASH; '$' -> K.DOLLAR; '#' -> K.HASH
            '+' -> K.PLUS; '-' -> K.MINUS; '*' -> K.STAR; '/' -> K.SLASH; '%' -> K.PERCENT; '^' -> K.POW
            '=' -> K.ASSIGN; '<' -> K.LT; '>' -> K.GT
            '&' -> K.AMP; '|' -> K.PIPE; '!' -> K.BANG
            '∀' -> K.FORALL; '∃' -> K.EXISTS
            '∧' -> K.LAND; '∨' -> K.LOR; '¬' -> K.LNOT
            '→' -> K.IMPLIES; '↔' -> K.EQUIV; '≠' -> K.NEQ
            '⊤' -> K.TOP; '⊥' -> K.BOT
            '∵' -> K.BECAUSE; '∴' -> K.THEREFORE
            else -> {
                val fullWidth = "（）【】＜＞＝，；：\"＇！＆｜"
                val hint = if (c in fullWidth) "（这是中文全角符号，请换成英文半角符号）" else ""
                throw LexFailure(fileName, line, col, "这个字符 '$c' 语言不认识$hint")
            }
        }
        adv()
        // $n 参数引用（决策 65）：$ 紧贴数字粘连为一个 token
        if (c == '$' && i < src.length && src[i] in '0'..'9') {
            val sb = StringBuilder("$")
            while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
            return K.DOLLAR to sb.toString()
        }
        return one to c.toString()
    }

    private fun readInt(): Pair<K, String> {
        val sb = StringBuilder()
        while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
        // P10：1.0 是 FLOAT；点号后必须紧跟数字才粘连
        if (i < src.length && src[i] == '.' && peekAt(i + 1) != null && peekAt(i + 1)!! in '0'..'9') {
            sb.append('.'); adv()
            while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
            return K.FLOAT to sb.toString()
        }
        return K.INT to sb.toString()
    }

    private fun readString(): Pair<K, String> {
        val sl = line; val sc = col
        adv() // 开引号
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw LexFailure(fileName, sl, sc, "字符串未闭合")
            when (val c = src[i]) {
                '"' -> { adv(); return K.STR to sb.toString() }
                '\\' -> {
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

    /** 反引号名：内容任意（含空格/运算符），禁跨行与内嵌反引号；一律 IDENT 不查关键字表。 */
    private fun readBacktickIdent(): Pair<K, String> {
        val sl = line; val sc = col
        adv()
        val sb = StringBuilder()
        while (true) {
            if (i >= src.length) throw LexFailure(fileName, sl, sc, "反引号标识符未闭合")
            when (val c = src[i]) {
                '`' -> { adv(); return K.IDENT to sb.toString() }
                '\n', '\r' -> throw LexFailure(fileName, sl, sc, "反引号标识符不能跨行")
                else -> { sb.append(c); adv() }
            }
        }
    }

    private fun readIdent(): Pair<K, String> {
        val sb = StringBuilder()
        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) { sb.append(src[i]); adv() }
        // $ 段粘连（注解处理器合成名 recomposion$Box$1）：内部 $ 后随字母/_ 续读为同 token
        while (i < src.length && src[i] == '$' && i + 1 < src.length &&
            (src[i + 1].isLetter() || src[i + 1] == '_')) {
            sb.append(src[i]); adv()
            while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) { sb.append(src[i]); adv() }
            if (i < src.length && src[i] == '$' && i + 1 < src.length && src[i + 1] in '0'..'9') {
                sb.append(src[i]); adv()
                while (i < src.length && src[i] in '0'..'9') { sb.append(src[i]); adv() }
            }
        }
        val text = sb.toString()
        return (keywords[text] ?: K.IDENT) to text
    }

    /** 符号名：非 ASCII 数学符号最大匹配串（○ ⊗ ∈ …），可作函数名/名字位；∵∴ 排除。 */
    private fun readSymbol(): Pair<K, String> {
        val sb = StringBuilder()
        while (i < src.length && isSymbolStart(src[i])) { sb.append(src[i]); adv() }
        return K.SYMBOL to sb.toString()
    }

    private fun isSymbolStart(c: Char): Boolean =
        c.code > 127 && !c.isWhitespace() && !c.isLetterOrDigit() &&
            c !in setOf('∀', '∃', '∧', '∨', '¬', '→', '↔', '⊤', '⊥', '∵', '∴')

    companion object {
        // String#trim 按代码点处理；Kotlin/JS 无 Character.isLetter 兼容差异，统一用 isLetterOrDigit。
    }
}

// =====================================================================================
// §2 语法：AST（内部表示，只需承载求值所需信息）+ Parser（对齐 frontend/Parser.kt）
// =====================================================================================

internal data class Ty(val name: String, val args: List<Ty> = emptyList())

/** 形参/结构体字段：名字、注解（@mut/@list/@ref…）、类型（仅元数据）、默认值。 */
internal data class Pm(val name: String, val anns: List<String>, val ty: Ty?, val default: E?)

internal sealed class D {
    /** 函数声明（含方法成员）。body = 表达式体或块体；null = 无体（@root/签名）。 */
    class Fun(val name: String, val anns: List<String>, val params: List<Pm>,
              val preps: List<E>, val retTy: Ty?, val posts: List<E>, val body: E?) : D()
    class Struct(val name: String, val anns: List<String>, val fields: List<Pm>, val props: List<E>) : D()
    class EnumD(val name: String, val ctors: List<Pair<String, Int>>) : D()   // ctor 名 → 实参数
    class Impl(val trait: String?, val self: String, val members: List<Fun>, val delegate: String?) : D()
    class Alias(val name: String, val target: Ty) : D()
    class PropDef(val name: String) : D()
}

internal sealed class S {
    class Var(val name: String, val anns: List<String>, val ty: Ty?, val value: E,
              val destruct: List<String>?) : S()
    class Assign(val target: E, val value: E) : S()
    class ExprS(val e: E) : S()
    class Return(val e: E?) : S()
    class Unchecked(val inner: S) : S()
    class FunS(val fn: D.Fun) : S()
    object Noop : S()      // 命题位（axiom/∵∴/by/策略/量词/if[p]/when[p]/∃）：零运行时
}

internal sealed class E {
    class IntL(val value: String) : E()
    class FloatL(val value: String) : E()
    class StrL(val value: String) : E()
    class Name(val name: String) : E()
    class Bin(val op: String, val l: E, val r: E) : E()
    class Uni(val op: String, val e: E) : E()
    class Call(val callee: E, val args: List<E>, val named: MutableMap<String, E>) : E()
    class Block(val stmts: List<S>) : E()
    class If(val cond: E, val then: E, val els: E?) : E()
    class When(val subject: E?, val binding: String?, val arms: List<Arm>) : E()
    class Lambda(val params: List<String>, val body: E) : E()
    class StructCtor(val struct: String, val assigns: List<Pair<String, E>>) : E()
    class Field(val target: E, val name: String) : E()
    class Tuple(val items: List<E>) : E()
    class FunRef(val target: E, val name: String) : E()
    class TypeTest(val target: E, val typeName: String) : E()
    class SymCall(val op: String, val args: List<E>) : E()
    class VarE(val name: String, val anns: List<String>, val value: E) : E()
    object NoopE : E()      // 量词/⊤/⊥/$/命题位 if-then-else：求值得 Null
}

internal sealed class P {
    class Lit(val value: E) : P()
    class Ctor(val name: String, val args: List<P>) : P()
    class TupleP(val items: List<P>) : P()
    class Is(val typeName: String, val bind: String?) : P()
    class Bind(val name: String) : P()
    object Else : P()
    object Cond : P()
}

internal class Arm(val pattern: P, val guard: E?, val body: E, val cond: E?)

internal class ParseFailure(fileName: String, line: Int, col: Int, message: String) :
    RuntimeException("[语法][$fileName:$line:$col] $message") {
    constructor(fileName: String, t: Tk, message: String) : this(fileName, t.line, t.col, message)
}

internal class Parser(private val toks: List<Tk>, private val fileName: String, private val scriptMode: Boolean = false) {
    private var pos = 0
    private var stopIdents: Set<String> = emptySet()
    private var ifIsCond = false
    private var ifIsPending: Pair<E, P>? = null

    private fun cur(): Tk = toks[pos]
    private fun peek(k: K): Boolean = cur().kind == k
    private fun next(): Tk = toks[pos++]
    private fun at(k: K): Tk {
        val t = cur()
        if (t.kind != k) throw ParseFailure(fileName, t, k.name)
        return next()
    }

    /** 名字位：标识符、符号名、运算符符号（仿 Scala：fun +(a,b)）。 */
    private fun nameToken(): String {
        val t = cur()
        return when (t.kind) {
            K.IDENT, K.SYMBOL,
            K.PLUS, K.MINUS, K.STAR, K.SLASH, K.PERCENT,
            K.EQ, K.NEQ, K.LE, K.GE, K.AMP, K.PIPE,
            K.IFF, K.LT, K.GT,
            -> next().text
            else -> throw ParseFailure(fileName, t, "标识符")
        }
    }

    // ============ 文件与声明 ============

    /** 解析顶层：元素为 D（声明：fun/struct/enum/impl/type/def）或 S（脚本语句）。
     *  无类型类 impl（扩展方法/重载）：成员脱糖为自由函数（其首形参即接收者，core 标准库同规则）。 */
    fun parseFile(): List<Any> {
        val out = ArrayList<Any>()
        skipSemis()
        while (!peek(K.EOF)) {
            val e = parseEntry()
            if (e is D.Impl && e.trait == null) for (m in e.members) out += m
            else out += e
            skipSemis()
        }
        return out
    }

    private fun skipSemis() { while (peek(K.SEMI)) pos++ }

    private fun parseEntry(): Any = when (cur().kind) {
        K.FUN, K.CLASS, K.STRUCT, K.ENUM, K.IMPL, K.TYPE, K.DEF -> parseDecl()
        K.AT -> {
            val save = pos
            val anns = parseAnnotations()
            when (cur().kind) {
                K.FUN -> parseFun(anns)
                K.STRUCT -> parseStruct(anns)
                K.CLASS -> parseClass(anns)
                else -> if (scriptMode) { pos = save; parseStmt() } else { pos = save; throw noTopLevel() }
            }
        }
        else -> if (scriptMode || (cur().kind == K.IDENT && cur().text == "axiom")) parseStmt() else throw noTopLevel()
    }

    private fun noTopLevel(): ParseFailure =
        ParseFailure(fileName, cur(), "顶层只能写声明，执行代码请放进 fun main()（脚本模式后续支持）")

    fun parseDecl(): D = when (cur().kind) {
        K.FUN -> parseFun(emptyList())
        K.CLASS -> parseClass(emptyList())
        K.STRUCT -> parseStruct(emptyList())
        K.ENUM -> parseEnum()
        K.IMPL -> parseImpl()
        K.TYPE -> { at(K.TYPE); val n = nameToken(); if (peek(K.LBRACKET)) parseTheory(); at(K.ASSIGN); D.Alias(n, parseType()) }
        K.DEF -> { at(K.DEF); next(); if (peek(K.LT)) parseAngleList(); var t: E? = null
            if (peek(K.IFF) || peek(K.EQUIV)) { next(); t = if (peek(K.LT)) parseAngleList().single() else parseExpr(angle = false) }
            D.PropDef(nameToken()) }
        else -> throw ParseFailure(fileName, cur(), "声明（顶层只能写 fun/struct/enum/class/impl/type 声明）")
    }

    private fun parseAnnotations(): List<String> {
        val out = ArrayList<String>()
        while (peek(K.AT)) { pos++; out += at(K.IDENT).text }
        return out
    }

    private fun parseFun(anns: List<String>): D.Fun {
        at(K.FUN)
        val name = nameToken()
        val theory = if (peek(K.LBRACKET)) parseTheory() else emptyList()
        val params = if (peek(K.LPAREN)) parseParams() else emptyList()
        var preps: List<E> = emptyList()
        if (peek(K.LT)) {
            preps = if (constraintListAhead()) { parseConstraintList(); emptyList() } else parseAngleList()
        }
        var ret: Ty? = null
        var posts: List<E> = emptyList()
        if (peek(K.COLON)) {
            pos++
            if (peek(K.LT)) posts = parseAngleList()
            else { ret = parseType(); if (peek(K.LT)) posts = parseAngleList() }
        }
        val body: E? = when {
            peek(K.ASSIGN) && toks.getOrNull(pos + 1)?.kind == K.LBRACE ->
                throw ParseFailure(fileName, toks[pos + 1], "函数体不能 = 后接花括号混写：单表达式去掉花括号，块体去掉等号")
            peek(K.ASSIGN) -> { pos++; parseExpr(angle = false) }
            peek(K.LBRACE) -> parseBlock()
            else -> null
        }
        return D.Fun(name, anns, params, preps, ret, posts, body)
    }

    /** v3.0 约束表 <score <= 100> 判定（Parser.kt 同构）。 */
    private fun constraintListAhead(): Boolean {
        val nm = toks.getOrNull(pos + 1)
        val op = toks.getOrNull(pos + 2)
        return nm?.kind == K.IDENT && op != null && op.space &&
            (op.kind in setOf(K.LE, K.GE, K.EQ, K.NEQ) || (op.kind == K.IDENT && op.text == "in"))
    }

    private fun parseConstraintList(): List<E> {
        at(K.LT)
        while (true) {
            nameToken()
            val opTok = cur(); pos++
            parseExpr(angle = true)
            if (peek(K.COMMA)) { pos++; if (peek(K.GT)) break; continue }
            break
        }
        at(K.GT)
        return emptyList()
    }

    private fun parseTheory(): List<String> {
        at(K.LBRACKET)
        val out = ArrayList<String>()
        while (true) {
            if (peek(K.AT)) { pos++; nameToken() }
            else {
                val n = nameToken()
                if (peek(K.LBRACKET)) {   // HKT 占位 [F[_]]
                    pos++
                    while (peek(K.IDENT) && cur().text == "_") { pos++; if (peek(K.COMMA)) pos++ else break }
                    at(K.RBRACKET)
                }
                out += n
            }
            if (peek(K.COMMA)) { pos++; if (peek(K.RBRACKET)) break; continue }
            break
        }
        at(K.RBRACKET)
        return out
    }

    private fun parseParams(): List<Pm> {
        at(K.LPAREN)
        val out = ArrayList<Pm>()
        if (!peek(K.RPAREN)) {
            while (true) {
                val anns = ArrayList<String>()
                while (peek(K.AT)) { pos++; anns += at(K.IDENT).text }
                val destruct = if (peek(K.LPAREN)) parseParamDestruct() else null
                val n = destruct?.first() ?: nameToken()
                val ty = if (peek(K.COLON)) { pos++; parseType() } else null
                val dft = if (peek(K.ASSIGN)) { pos++; parseExpr(angle = false) } else null
                out += Pm(n, anns, ty, dft)
                if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                break
            }
        }
        at(K.RPAREN)
        return out
    }

    private fun parseParamDestruct(): List<String> {
        at(K.LPAREN)
        val out = ArrayList<String>()
        if (!peek(K.RPAREN)) {
            while (true) {
                out += nameToken()
                if (peek(K.COMMA)) { pos++; if (peek(K.RPAREN)) break; continue }
                break
            }
        }
        at(K.RPAREN)
        return out
    }

    private fun isCloserNext(): Boolean = peek(K.RPAREN) || peek(K.RBRACKET) || peek(K.RBRACE)

    /** lambda 解构参数编码名 "(a,b)"（Parser.kt 同构）。 */
    private fun parseLambdaDestructName(): String {
        at(K.LPAREN)
        val parts = ArrayList<String>()
        if (!peek(K.RPAREN)) {
            while (true) {
                parts += nameToken()
                if (peek(K.COMMA)) { pos++; if (peek(K.RPAREN)) break; continue }
                break
            }
        }
        at(K.RPAREN)
        return "(" + parts.joinToString(",") + ")"
    }

    /** O3：A(name = …) 命名字段构造判定（() 空括号不算）。 */
    private fun looksLikeNamedCtor(): Boolean {
        if (!peek(K.LPAREN)) return false
        val a = toks.getOrNull(pos + 1) ?: return false
        val b = toks.getOrNull(pos + 2) ?: return false
        return a.kind == K.IDENT && b.kind == K.ASSIGN
    }

    private fun parseStructCtorArgs(): List<Pair<String, E>> {
        at(K.LPAREN)
        val out = ArrayList<Pair<String, E>>()
        if (!peek(K.RPAREN)) {
            while (true) {
                val fname = nameToken()
                at(K.ASSIGN)
                out += fname to parseExpr(angle = false)
                if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                break
            }
        }
        at(K.RPAREN)
        return out
    }

    // ---- 类型（仅元数据：基名用于 is 判定/方法字典；其余解析即弃） ----

    private fun parseType(): Ty {
        if (peek(K.AT)) {
            pos++
            val an = at(K.IDENT).text
           val inner = parseType()
            return when (an) { "mut" -> Ty("@mut", listOf(inner)); "atomic" -> Ty("@atomic", listOf(inner)); "impure" -> Ty("@impure", listOf(inner)); else -> inner }
        }
        val base: Ty = if (peek(K.LBRACKET)) parsePolyFunType()
            ?: if (peek(K.LPAREN)) parseFunOrTupleType() else parseNamedType()
        else if (peek(K.LPAREN)) parseFunOrTupleType() else parseNamedType()
        var t = base
        if (peek(K.PIPE)) {   // 联合类型 A | B
            val vs = ArrayList<Ty>(); vs += t
            while (peek(K.PIPE)) { pos++; vs += if (peek(K.LPAREN)) parseFunOrTupleType() else parseNamedType() }
            t = Ty("(union)", vs)
        }
        return t
    }

    private fun parsePolyFunType(): Ty? {
        val save = pos
        return try {
            at(K.LBRACKET)
            while (peek(K.IDENT)) { pos++; if (peek(K.COMMA)) pos++ else break }
            at(K.RBRACKET)
            if (!peek(K.LPAREN)) { pos = save; null } else parseFunOrTupleType()
        } catch (_: ParseFailure) { pos = save; null }
    }

    private fun parseNamedType(): Ty {
        val first = nameToken()
        val segs = ArrayList<String>(); segs += first
        while (peek(K.DOT)) { pos++; segs += nameToken() }
        var args: List<Ty> = emptyList()
        if (peek(K.LBRACKET)) {
            val save = pos
            try { pos++; args = parseTypeArgs() } catch (_: ParseFailure) { pos = save }
        }
        return Ty(segs.last(), args)
    }

    private fun parseFunOrTupleType(): Ty {
        at(K.LPAREN)
        val ps = ArrayList<Ty>()
        if (!peek(K.RPAREN)) {
            while (true) {
                ps += parseType()
                if (peek(K.COMMA)) { pos++; if (peek(K.RPAREN)) break; continue }
                break
            }
        }
        at(K.RPAREN)
        if (peek(K.LT)) parseAngleList()      // 函数类型上下文：解析即弃
        return if (peek(K.DARROW)) { pos++; parseType(); Ty("(fn)", emptyList()) } else Ty("(tuple)", ps)
    }

    private fun parseTypeArgs(): List<Ty> {
        val out = ArrayList<Ty>()
        while (true) {
            out += parseType()
            if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
            break
        }
        at(K.RBRACKET)
        return out
    }

    private fun parseAngleList(): List<E> {
        at(K.LT)
        val out = ArrayList<E>()
        while (true) {
            out += parseExpr(angle = true)
            if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
            break
        }
        at(K.GT)
        return out
    }

    private fun parseClass(anns: List<String> = emptyList()): D {
        at(K.CLASS)
        val name = nameToken()
        if (peek(K.LBRACKET)) parseTheory()
        if (peek(K.COLON)) {   // 父类型类列表
            pos++
            while (true) { parseNamedType(); if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }; break }
        }
        if (!peek(K.LBRACE)) return D.PropDef(name)
        at(K.LBRACE)
        while (!peek(K.RBRACE)) {
            skipSemis()
            if (peek(K.RBRACE)) break
            parseFun(parseAnnotations())
        }
        at(K.RBRACE)
        return D.PropDef(name)   // 类型类：零运行时（方法字典来自 impl）
    }

    private fun parseStruct(anns: List<String> = emptyList()): D {
        at(K.STRUCT)
        val name = nameToken()
        if (peek(K.LBRACKET)) parseTheory()
        val isCompose = "compose" in anns
        if (peek(K.LBRACE) && !isCompose)
            throw ParseFailure(fileName, cur(), "struct 体是参数列表，请改写为 $name(字段: 类型 = 默认值)")
        if (!peek(K.LPAREN)) {
            if (isCompose && peek(K.LBRACE)) return parseComposeBlock(name, anns, emptyList())
            if (isCompose && cur().kind == K.ASSIGN) { pos++; if (exprStarts()) { parseExpr(angle = false); return D.Struct(name, anns, emptyList(), emptyList()) }; pos-- }
            val props = if (peek(K.LT)) parseAngleList() else emptyList()
            return D.Struct(name, anns, emptyList(), props)
        }
        val fields = parseParams()
        val props = if (peek(K.LT)) parseAngleList() else emptyList()
        if (isCompose && peek(K.LBRACE)) return parseComposeBlock(name, anns, fields)
        if (isCompose && cur().kind == K.ASSIGN) {
            val save = pos; next()
            if (exprStarts()) { parseExpr(angle = false); return D.Struct(name, anns, fields, props) }
            pos = save
        }
        return D.Struct(name, anns, fields, props)
    }

    /** @compose 视图内容体：解析即弃（SfA 侧 @compose 通路不经本解释器）。 */
    private fun parseComposeBlock(name: String, anns: List<String>, fields: List<Pm>): D.Struct {
        at(K.LBRACE)
        while (!peek(K.RBRACE) && !peek(K.EOF)) parseStmt()
        at(K.RBRACE)
        return D.Struct(name, anns, fields, emptyList())
    }

    private fun exprStarts(): Boolean = when (cur().kind) {
        K.IDENT, K.STR, K.INT, K.FLOAT, K.SYMBOL, K.LPAREN, K.LBRACKET, K.LBRACE,
        K.MINUS, K.BANG, K.LNOT, K.IF, K.WHEN, K.FUN -> true
        else -> false
    }

    private fun parseEnum(): D.EnumD {
        at(K.ENUM)
        val name = nameToken()
        if (peek(K.LBRACKET)) parseTheory()
        if (!peek(K.LBRACE)) return D.EnumD(name, emptyList())
        at(K.LBRACE)
        val ctors = ArrayList<Pair<String, Int>>()
        while (!peek(K.RBRACE)) {
            skipSemis()
            if (peek(K.RBRACE)) break
            val c = nameToken()
            val arity = if (peek(K.LPAREN)) parseCtorFields() else 0
            ctors += c to arity
            if (peek(K.COMMA)) pos++
        }
        at(K.RBRACE)
        return D.EnumD(name, ctors)
    }

    private fun parseCtorFields(): Int {
        at(K.LPAREN)
        var n = 0
        if (!peek(K.RPAREN)) {
            while (true) { parseType(); n++; if (peek(K.COMMA)) { pos++; if (peek(K.RPAREN)) break; continue }; break }
        }
        at(K.RPAREN)
        return n
    }

    private fun parseImpl(): D.Impl {
        at(K.IMPL)
        val t0 = parseType()
        val trait: String?
        val self: String
        if (peek(K.FOR)) { pos++; trait = t0.name; self = parseType().name }
        else if (t0.args.size == 1 && t0.name.firstOrNull()?.isUpperCase() == true) {
            // 简写 impl Trait[Head[…]] ≡ impl Trait for Head[…]
            trait = t0.name
            self = t0.args[0].name
        } else { trait = null; self = t0.name }
        var delegate: String? = null
        if (peek(K.BY)) { pos++; delegate = nameToken() }
        val members = ArrayList<D.Fun>()
        if (peek(K.LBRACE)) {
            at(K.LBRACE)
            while (!peek(K.RBRACE)) {
                skipSemis()
                if (peek(K.RBRACE)) break
                members += parseFun(parseAnnotations())
            }
            at(K.RBRACE)
        }
        return D.Impl(trait, self, members, delegate)
    }

    // ============ 语句 ============

    private fun parseStmt(): S {
        if (peek(K.EXISTS)) return parseExistsStmt()
        if (peek(K.FUN)) return S.FunS(parseFun(if (peek(K.AT)) parseAnnotations() else emptyList()))
        if (peek(K.IF) && toks.getOrNull(pos + 1)?.kind == K.LBRACKET) return parseIfProp()
        if (peek(K.WHEN) && toks.getOrNull(pos + 1)?.kind == K.LBRACKET) return parseWhenProp()
        if (peek(K.THEREFORE)) { next(); parseExpr(angle = false); return S.Noop }
        if (peek(K.IDENT) && cur().text == "axiom" && canStartAxiomProp()) return parseAxiomStmt()
        if (peek(K.BECAUSE)) return parseBTS()
        if (peek(K.UNCHECKED)) return parseUnchecked()
        if (peek(K.RET)) return parseReturn()
        if (peek(K.BY)) { pos++; parseBracketList(); return S.Noop }
        if (peek(K.AT)) {
            if (toks.getOrNull(pos + 1)?.kind == K.IDENT && toks.getOrNull(pos + 2)?.kind == K.LBRACKET) {
                next(); at(K.IDENT); at(K.LBRACKET); parseExpr(angle = true); at(K.RBRACKET); return S.Noop
            }
            val anns = parseAnnotations()
            if (peek(K.VAR)) return parseVar(anns)
            throw ParseFailure(fileName, cur(), "var")
        }
        if (peek(K.VAR)) return parseVar(emptyList())
        // 赋值探测：左值是名字/字段访问的 NAME = ... 判为赋值；其余 = 是命题等式中缀
        val save = pos
        try {
            val lhs = parsePostfix(stopArrow = false, angle = false)
            if (peek(K.ASSIGN) && (lhs is E.Name || lhs is E.Field)) {
                pos++
                return S.Assign(lhs, parseExpr(angle = false))
            }
            pos = save
        } catch (_: ParseFailure) { pos = save }
        return S.ExprS(parseExpr(angle = false))
    }

    /** ∵ form, p₁, p₂ ∴ conclusion —— 因为-所以：零运行时。 */
    private fun parseBTS(): S {
        next()
        val form = parseExpr(angle = false)
        if (!peek(K.COMMA) && !peek(K.THEREFORE)) return S.Noop
        while (peek(K.COMMA)) { pos++; if (peek(K.THEREFORE)) break; parseExpr(angle = false) }
        at(K.THEREFORE)
        parseExpr(angle = false)
        return S.Noop
    }

    private fun canStartAxiomProp(): Boolean {
        val nx = toks.getOrNull(pos + 1) ?: return false
        return nx.kind in setOf(K.IDENT, K.SYMBOL, K.INT, K.FLOAT, K.STR, K.LPAREN, K.LNOT, K.BANG,
            K.TOP, K.BOT, K.MINUS, K.FORALL, K.EXISTS, K.EXISTS1)
    }

    private fun parseAxiomStmt(): S {
        next()
        parseExpr(angle = false)
        while (peek(K.COMMA)) {
            pos++
            val nx = cur()
            if (nx.kind == K.RBRACE || nx.kind == K.SEMI || nx.kind == K.EOF) break
            parseExpr(angle = false)
        }
        return S.Noop
    }

    private fun parseWhenProp(): S {
        next(); at(K.LBRACKET); parseExpr(angle = true); at(K.RBRACKET); at(K.LBRACE)
        while (!peek(K.RBRACE)) {
            parseExpr(angle = false)
            if (peek(K.DARROW)) pos++
            parseBlock()
            if (peek(K.COMMA)) pos++
        }
        at(K.RBRACE)
        return S.Noop
    }

    private fun parseIfProp(): S {
        next(); at(K.LBRACKET); parseExpr(angle = true); at(K.RBRACKET)
        parseBlock()
        if (peek(K.ELSE)) { pos++; parseBlock() }
        return S.Noop
    }

    private fun parseReturn(): S {
        next()
        if (peek(K.AT) && !cur().space) { pos++; at(K.IDENT) }   // return@label（标签返回：按裸 return 处理）
        val nx = cur()
        val bare = nx.kind == K.RBRACE || nx.kind == K.SEMI || nx.kind == K.EOF
        return S.Return(if (bare) null else parseExpr(angle = false))
    }

    /** ∃ 引入/消去：零运行时。 */
    private fun parseExistsStmt(): S {
        next()
        at(K.IDENT)
        if (peek(K.ASSIGN)) {
            parseExpr(angle = false)
            while (peek(K.COMMA)) { pos++; at(K.IDENT); at(K.ASSIGN); parseExpr(angle = false) }
            at(K.DOT); parseExpr(angle = true)
        } else {
            at(K.DOT); parseExpr(angle = true)
            if (peek(K.LBRACE)) parseBlock()
        }
        return S.Noop
    }

    private fun parseUnchecked(): S {
        if (peek(K.DOT)) throw ParseFailure(fileName, cur(), "语句（unchecked 后直接跟语句）")
        pos++
        return S.Unchecked(parseStmt())
    }

    private fun parseVar(anns: List<String>): S.Var {
        at(K.VAR)
        val destruct = parseVarDestruct()
        val name = destruct?.first() ?: nameToken()
        val ty = if (peek(K.COLON)) { pos++; parseType() } else null
        at(K.ASSIGN)
        return S.Var(name, anns, ty, parseExpr(angle = false), destruct)
    }

    private fun parseVarDestruct(): List<String>? {
        if (!peek(K.LPAREN)) return null
        pos++
        val names = ArrayList<String>()
        names += nameToken()
        while (peek(K.COMMA)) { if (peek(K.RPAREN)) break; pos++; names += nameToken() }   // 尾随逗号
        at(K.RPAREN)
        return names
    }

    private fun parseBlock(): E.Block {
        at(K.LBRACE)
        val stmts = ArrayList<S>()
        while (!peek(K.RBRACE)) {
            skipSemis()
            if (peek(K.RBRACE)) break
            stmts += parseStmt()
        }
        at(K.RBRACE)
        return E.Block(stmts)
    }

    // ============ 表达式 ============

    private fun parseExpr(angle: Boolean, stopArrow: Boolean = false): E {
        var left = parseUnary(stopArrow, angle)
        while (true) {
            val t = cur()
            if (t.kind == K.IDENT && t.text in stopIdents) break
            if (stopArrow && (t.kind == K.ARROW || t.kind == K.DARROW)) break
            if (stopArrow && t.kind == K.LPAREN && t.space) break
            if (t.kind == K.BY) { pos++; parseBracketList(); continue }
            if (t.kind == K.GT) {
                if (angle && !(t.space && nextStartsOperand())) break
                pos++; left = E.Bin(">", left, parseUnary(stopArrow, angle)); continue
            }
            if (t.kind == K.LT) {
                if (!t.space) break       // 紧贴 < 属于 postfix 实例化
                pos++; left = E.Bin("<", left, parseUnary(stopArrow, angle)); continue
            }
            if (t.kind == K.IS) {
                val leftEnd = toks.getOrNull(pos - 1)?.line
                if (leftEnd != null && leftEnd != t.line) break    // 跨行 is 属下一 when 分支模式
                if (isCtorForm(pos + 1)) {
                    if (ifIsCond) {
                        // ifis 智能解构哨兵：条件位的 a is Ctor(...) 记为 pending，
                        // 由 parseIf 脱糖为 when（S-11：复用全套模式匹配）；
                        // parsePattern 自身消费 is，此处不可前移 pos
                        val pat = parsePattern()
                        ifIsPending = left to pat
                        return left
                    }
                    break
                }
                pos++
                left = E.TypeTest(left, parseType().name); continue
            }
            if (t.kind == K.RANGE) { pos++; left = E.Bin("..", left, parseUnary(stopArrow, angle)); continue }
            if (t.kind == K.IDENT && t.text == "in") {
                pos++; left = E.Call(E.Name("in"), listOf(left, parseRangeOperand(stopArrow, angle)), mutableMapOf()); continue
            }
            if (t.kind in binops) { pos++; left = E.Bin(t.text, left, parseUnary(stopArrow, angle)); continue }
            // 带空格的符号名中缀（≥ ≤ ≠ ∈ …）：两参解糖 q ○ p
            if (t.kind == K.SYMBOL && t.space) { pos++; left = E.Bin(t.text, left, parseUnary(stopArrow, angle)); continue }
            // 标识符两参中缀糖 a f b（f 前后均有空格且同行；右操作数也须带空格前缀）
            if (t.kind == K.IDENT && t.space && t.text !in stopIdents && infixOperandAhead()) {
                pos++
                left = E.Call(E.Name(t.text), listOf(left, parseUnary(stopArrow, angle)), mutableMapOf()); continue
            }
            break
        }
        return left
    }

    private fun infixOperandAhead(): Boolean {
        val nx = toks.getOrNull(pos + 1) ?: return false
        if (!nx.space || cur().line != nx.line) return false
        val prev = toks.getOrNull(pos - 1)
        if (prev != null && prev.line != cur().line) return false
        if (nx.kind in setOf(K.ARROW, K.DARROW, K.COMMA, K.RBRACE, K.RBRACKET, K.SEMI, K.RPAREN, K.ASSIGN, K.RET)) return false
        return true
    }

    private fun nextStartsOperand(): Boolean {
        val t = toks.getOrNull(pos + 1) ?: return false
        return t.kind in setOf(K.INT, K.FLOAT, K.STR, K.DOLLAR, K.IDENT, K.SYMBOL, K.LPAREN, K.MINUS, K.BANG, K.LNOT, K.LT)
    }

    private fun parseRangeOperand(stopArrow: Boolean, angle: Boolean): E {
        var e = parseUnary(stopArrow, angle)
        if (peek(K.RANGE)) { pos++; e = E.Bin("..", e, parseUnary(stopArrow, angle)) }
        return e
    }

    private fun parseUnary(stopArrow: Boolean, angle: Boolean): E {
        val t = cur()
        when (t.kind) {
            K.MINUS -> { pos++; return E.Uni("-", parseUnary(stopArrow, angle)) }
            K.BANG -> { pos++; return E.Uni("!", parseUnary(stopArrow, angle)) }
            K.LNOT -> { pos++; return E.Uni("¬", parseUnary(stopArrow, angle)) }
            K.IDENT -> if (t.text == "not") { pos++; return E.Uni("¬", parseUnary(stopArrow, angle)) }
            K.LT -> {   // 命题尖括号 <e>
                val inner = parseAngleList()
                if (inner.size != 1) throw ParseFailure(fileName, cur(), "命题尖括号内只能有一个表达式")
                return inner[0]
            }
            else -> {}
        }
        return parsePostfix(stopArrow, angle)
    }

    private fun parsePostfix(stopArrow: Boolean, angle: Boolean): E {
        var e = parsePrimary(angle)
        while (true) {
            val t = cur()
            when {
                stopArrow && t.kind == K.LPAREN && t.space -> break
                t.kind == K.COLON2 -> {
                    pos++
                    val member = nameToken()
                    e = if (e is E.Name && e.name.first().isUpperCase())
                        E.Name(e.name + "::" + member) else E.FunRef(e, member)
                }
                // 字段访问：点前无空格（a.b），或点在行首的换行链式调用（a\n .map）
                t.kind == K.DOT && (!t.space || toks.getOrNull(pos - 1)?.line != t.line) -> {
                    pos++
                    e = E.Field(e, nameToken())
                }
                t.kind == K.LPAREN -> {
                    if (e is E.Name && !e.name.contains("::") && e.name.first().isUpperCase() && looksLikeNamedCtor())
                        e = E.StructCtor(e.name, parseStructCtorArgs())
                    else {
                        val (pa, na) = parseArgs()
                        e = E.Call(e, pa, na)
                    }
                }
                t.kind == K.LT && !t.space -> {
                    val save = pos
                    try { parseAngleList(); e = e }    // 实例化/供给：擦除（运行时无类型实参）
                    catch (_: ParseFailure) { pos = save; break }
                }
                t.kind == K.LBRACE && (braceIsLambda(pos) || braceHasImplicitBody(pos)) &&
                    e !is E.IntL && e !is E.StrL && e !is E.Bin && e !is E.Uni -> {
                    // 尾随 lambda：{ … } 追加为最后一个位置实参
                    val lam = parseBraceLambda()
                    e = if (e is E.Call) E.Call(e.callee, e.args + lam, e.named) else E.Call(e, listOf(lam), mutableMapOf())
                }
                else -> break
            }
        }
        return e
    }

    private fun parseArgs(): Pair<List<E>, MutableMap<String, E>> {
        at(K.LPAREN)
        val posArgs = ArrayList<E>()
        val named = LinkedHashMap<String, E>()
        if (!peek(K.RPAREN)) {
            while (true) {
                if (cur().kind == K.IDENT && toks.getOrNull(pos + 1)?.kind == K.ASSIGN) {
                    val nm = cur().text
                    pos += 2
                    named[nm] = parseExpr(angle = false)
                } else posArgs += parseExpr(angle = false)
                if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                break
            }
        }
        at(K.RPAREN)
        return posArgs to named
    }

    /** 字符串插值脱糖（Parser.parseStringLit 同构）：$name / ${expr} → concat(toStr(...)…) 链。 */
    private fun parseStringLit(): E {
        val t = cur(); pos++
        val raw = t.text
        if ('$' !in raw) return E.StrL(raw)
        val parts = ArrayList<E>()
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '$' && i + 1 < raw.length) {
                val nx = raw[i + 1]
                if (nx == '{') {
                    val j = matchInterpBrace(raw, i + 1)
                    if (j >= 0) {
                        if (sb.isNotEmpty()) { parts += E.StrL(sb.toString()); sb.clear() }
                        val inner = raw.substring(i + 2, j)
                        if (inner.isNotBlank())
                            parts += E.Call(E.Name("toStr"), listOf(parseSubExpr(inner)), mutableMapOf())
                        i = j + 1; continue
                    }
                } else if (nx.isLetter()) {
                    var j = i + 1
                    while (j < raw.length && (raw[j].isLetterOrDigit() || raw[j] == '_')) j++
                    if (sb.isNotEmpty()) { parts += E.StrL(sb.toString()); sb.clear() }
                    parts += E.Call(E.Name("toStr"), listOf(E.Name(raw.substring(i + 1, j))), mutableMapOf())
                    i = j; continue
                }
            }
            sb.append(c); i++
        }
        if (sb.isNotEmpty()) parts += E.StrL(sb.toString())
        var acc = parts.first()
        for (k in 1 until parts.size) acc = E.Call(E.Name("concat"), listOf(acc, parts[k]), mutableMapOf())
        return acc
    }

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

    /** 插值内部表达式用子 Parser 重 lex（${1 + 2}）。 */
    private fun parseSubExpr(inner: String): E =
        Parser(Lexer(inner, fileName).tokenize(), fileName, scriptMode = true).parseExpr(angle = false)

    private fun parsePrimary(angle: Boolean): E {
        val t = cur()
        return when {
            t.kind == K.INT -> { pos++; E.IntL(t.text) }
            t.kind == K.FLOAT -> { pos++; E.FloatL(t.text) }
            t.kind == K.STR -> parseStringLit()
            t.kind == K.DOLLAR -> {
                pos++
                if (t.text.length > 1) throw ParseFailure(fileName, t, "参数引用 \$n 只能在前后件/不变量中使用")
                E.NoopE
            }
            t.kind == K.TOP -> { pos++; E.NoopE }
            t.kind == K.BOT -> { pos++; E.NoopE }
            t.kind == K.LPAREN -> {
                pos++
                val first = parseExpr(angle = false)
                if (peek(K.COMMA)) {
                    val items = ArrayList<E>()
                    items += first
                    while (peek(K.COMMA)) { pos++; if (peek(K.RPAREN)) break; items += parseExpr(angle = false) }
                    at(K.RPAREN)
                    E.Tuple(items)
                } else { at(K.RPAREN); first }
            }
            t.kind == K.LBRACE && (braceIsLambda(pos) || braceHasImplicitBody(pos)) -> parseBraceLambda()
            t.kind == K.VAR -> parseVarExpr(emptyList())
            t.kind == K.AT -> { val a = parseAnnotations(); if (peek(K.VAR)) parseVarExpr(a) else throw ParseFailure(fileName, cur(), "var") }
            t.kind == K.FUN -> parseAnonFunExpr()
            t.kind == K.IF -> parseIf()
            t.kind == K.WHEN -> parseWhen()
            t.kind == K.FORALL || t.kind == K.EXISTS || t.kind == K.EXISTS1 -> { next(); parseQuant(); E.NoopE }
            t.kind == K.IDENT && t.text in mapOf("forall" to "∀", "exists" to "∃", "exists1" to "∃!") &&
                toks.getOrNull(pos + 1)?.kind == K.IDENT -> { next(); parseQuant(); E.NoopE }
            t.kind == K.IDENT || t.kind == K.SYMBOL -> { pos++; E.Name(t.text) }
            t.kind in symbolCallStart && toks.getOrNull(pos + 1)?.kind == K.LPAREN -> {
                val op = next().text
                E.SymCall(op, parseArgs().first)
            }
            t.kind in opPrefixKinds && toks.getOrNull(pos + 1)?.kind == K.LPAREN -> {
                val opTok = next()
                val (pa, na) = parseArgs()
                E.Call(E.Name(opTok.text), pa, na)
            }
            else -> throw ParseFailure(fileName, t, "表达式")
        }
    }

    private fun parseQuant() {
        while (cur().kind != K.DOT) {
            if (cur().kind == K.EOF) throw ParseFailure(fileName, cur(), "量词变量列表后的句点（如 ∀x. body）")
            if (peek(K.AT) || peek(K.HASH)) pos++
            nameToken()
            if (peek(K.COMMA)) pos++
        }
        at(K.DOT)
        parseExpr(angle = false)
    }

    private fun isCtorForm(i: Int): Boolean {
        if (toks.getOrNull(i)?.kind != K.IDENT) return false
        if (toks.getOrNull(i + 1)?.kind == K.LPAREN) return true
        if (toks.getOrNull(i + 1)?.kind == K.COLON2)
            return toks.getOrNull(i + 2)?.kind == K.IDENT && toks.getOrNull(i + 3)?.kind == K.LPAREN
        return false
    }

    private fun parseVarExpr(anns: List<String>): E.VarE {
        at(K.VAR)
        val destruct = parseVarDestruct()
        val name = destruct?.first() ?: nameToken()
        val ty = if (peek(K.COLON)) { pos++; parseType() } else null
        at(K.ASSIGN)
        return E.VarE(name, anns, parseExpr(angle = false))
    }

    private fun parseAnonFunExpr(): E {
        at(K.FUN)
        if (!(peek(K.LBRACKET) || peek(K.LPAREN))) nameToken()
        if (peek(K.LBRACKET)) parseTheory()
        val params = if (peek(K.LPAREN)) parseParams() else emptyList()
        if (peek(K.LT)) parseAngleList()
        if (peek(K.COLON)) { pos++; parseType() }
        if (peek(K.LT)) parseAngleList()
        val body: E? = when {
            peek(K.ASSIGN) -> { pos++; parseExpr(angle = false) }
            peek(K.LBRACE) -> parseBlock()
            else -> null
        }
        // 匿名函数（运行时按 Lambda 通道求值）
        return E.Lambda(params.map { it.name }, body ?: E.Block(emptyList()))
    }

    /** if 表达式：if (c) A else B（分支可块可单表达式）；条件位的 a is Ctor(...) 脱糖为 when。 */
    private fun parseIf(): E {
        at(K.IF)
        if (peek(K.LPAREN)) {
            pos++
            val saved = ifIsCond
            val savedPending = ifIsPending
            ifIsCond = true
            ifIsPending = null
            val cond = try { parseExpr(angle = false) } finally { ifIsCond = saved }
            at(K.RPAREN)
            // S-11 脱糖：if (o is PAT) A else B → when (o) { is PAT -> A (else -> B) }
            if (ifIsPending != null) {
                val (subj, pat) = ifIsPending!!
                ifIsPending = savedPending
                val arms = ArrayList<Arm>()
                val thenB = parseBranchBlock()
                arms += Arm(pat, null, thenB, null)
                if (peek(K.ELSE)) { pos++; arms += Arm(P.Else, null, parseBranchBlock(), null) }
                return E.When(subj, null, arms)
            }
            ifIsPending = savedPending
            val thenB = parseBranchBlock()
            val elseB = if (peek(K.ELSE)) { pos++; parseBranchBlock() } else null
            return E.If(cond, thenB, elseB)
        }
        // 命题位 if c then a else b（数学风，then/else 是 IDENT 停止词）：体不运行
        val saved = stopIdents
        stopIdents = setOf("then")
        parseExpr(angle = false)
        stopIdents = saved
        at2("then")
        stopIdents = setOf("else")
        parseExpr(angle = false)
        stopIdents = saved
        at2("else")
        parseExpr(angle = false)
        return E.NoopE
    }

    private fun at2(text: String) {
        val t = cur()
        if (t.kind != K.IDENT || t.text != text) throw ParseFailure(fileName, t, text)
        pos++
    }

    /** when 表达式（决策 29/46：真模式匹配 + ADT 解构）。 */
    private fun parseWhen(): E {
        val sl = cur().line; val sc = cur().col
        at(K.WHEN)
        var binding: String? = null
        var subject: E? = null
        var templateBase: E? = null
        if (peek(K.LPAREN)) {
            pos++
            subject = if (peek(K.VAR)) {
                pos++
                binding = nameToken()
                at(K.ASSIGN)
                parseExpr(angle = false)
            } else parseExpr(angle = false)
            at(K.RPAREN)
            // 模板 when：when(t in _) → 无主题 when，分支模式 p 自动包成 t in p
            (subject as? E.Call)?.let { ce ->
                val r = ce.args.getOrNull(1)
                if (ce.callee is E.Name && (ce.callee as E.Name).name == "in" && r is E.Name && r.name == "_") {
                    templateBase = ce.args[0]; subject = null
                }
            }
        }
        at(K.LBRACE)
        val arms = ArrayList<Arm>()
        while (!peek(K.RBRACE)) {
            skipSemis()
            if (peek(K.RBRACE)) break
            val al = cur().line; val ac = cur().col
            val pat: P; var guard: E? = null; var cond: E? = null
            if (templateBase != null) {
                if (peek(K.ELSE)) { pos++; pat = P.Else }
                else {
                    val pe = parseExpr(angle = false, stopArrow = true)
                    if (pe is E.Name && pe.name == "_") pat = P.Else
                    else { pat = P.Cond; cond = E.Call(E.Name("in"), listOf(templateBase!!, pe), mutableMapOf()) }
                }
                guard = if (peek(K.IF)) { pos++; parseExpr(angle = false, stopArrow = true) } else null
            } else if (subject == null && peek(K.ELSE)) {
                pos++
                pat = P.Else
            } else if (subject == null) {
                cond = parseExpr(angle = false, stopArrow = true)
                pat = P.Cond
            } else {
                pat = parsePattern()
                guard = if (peek(K.IF)) { pos++; parseExpr(angle = false, stopArrow = true) } else null
            }
            at(K.DARROW)
            arms += Arm(pat, guard, parseBranchBody(stopArrow = templateBase != null), cond)
            skipSemis()
            if (peek(K.COMMA)) pos++
        }
        at(K.RBRACE)
        return E.When(subject, binding, arms)
    }

    /** 模式：is T [as x] / _ / else / 构造子解构 / 字面量 / 名字（绑定 or 零参构造）。 */
    private fun parsePattern(): P {
        val t = cur()
        val p = "${t.line}:${t.col}"
        if (t.kind == K.IS) {
            pos++
            // is E::A(...) 枚举成员路径
            if (peek(K.IDENT) && toks.getOrNull(pos + 1)?.kind == K.COLON2) {
                val en = next().text
                at(K.COLON2)
                val ctor = nameToken()
                val args = parseCtorPatternArgs()
                return P.Ctor("$en::$ctor", args)
            }
            // is Ctor(args) 构造子解构
            if (peek(K.IDENT) && toks.getOrNull(pos + 1)?.kind == K.LPAREN) {
                val ctor = next().text
                at(K.LPAREN)
                val args = ArrayList<P>()
                if (!peek(K.RPAREN)) {
                    while (true) {
                        args += parsePattern()
                        if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                        break
                    }
                }
                at(K.RPAREN)
                return P.Ctor(ctor, args)
            }
            val ty = parseType().name
            val bind = if (peek(K.IDENT) && cur().text == "as") { pos++; nameToken() } else null
            return P.Is(ty, bind)
        }
        if (t.kind == K.ELSE || (t.kind == K.IDENT && t.text == "else")) { pos++; return P.Else }
        // 元组模式 (p1, p2)
        if (t.kind == K.LPAREN) {
            pos++
            val items = ArrayList<P>()
            items += parsePattern()
            while (peek(K.COMMA)) { pos++; items += parsePattern() }
            at(K.RPAREN)
            return if (items.size == 1) items[0] else P.TupleP(items)
        }
        // 字面量模式
        if (t.kind == K.INT) { pos++; return P.Lit(E.IntL(t.text)) }
        if (t.kind == K.FLOAT) { pos++; return P.Lit(E.FloatL(t.text)) }
        if (t.kind == K.STR) { pos++; return P.Lit(E.StrL(t.text)) }
        if (t.kind == K.IDENT) {
            val nm = next().text
            if (nm == "_") return P.Bind("_")
            // E::A 枚举成员路径 → 构造子复合名（空参，可带 () 值比较）
            if (peek(K.COLON2)) {
                pos++
                val ctor = nameToken()
                val full = "$nm::$ctor"
                if (peek(K.LPAREN)) { pos++; if (!peek(K.RPAREN)) throw ParseFailure(fileName, cur(), "以 is 开头的构造子解构（类型解构须显式 is：is E::A(...)）"); at(K.RPAREN) }
                return P.Ctor(full, emptyList())
            }
            // 无 is 的括号模式仅允许空参值比较 A()；带实参的解构须显式 is
            if (peek(K.LPAREN)) {
                pos++
                if (!peek(K.RPAREN)) throw ParseFailure(fileName, cur(), "以 is 开头的构造子解构（类型解构须显式 is：is Some(v)）")
                at(K.RPAREN)
                return P.Ctor(nm, emptyList())
            }
            // 无参构造子与绑定变量同形：由求值器按 ctor 注册表判定（Parser.kt 同构）
            return P.Ctor(nm, emptyList())
        }
        throw ParseFailure(fileName, t, "模式")
    }

    private fun parseCtorPatternArgs(): List<P> = if (!peek(K.LPAREN)) emptyList() else {
        pos++
        val args = ArrayList<P>()
        if (!peek(K.RPAREN)) {
            while (true) {
                args += parsePattern()
                if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                break
            }
        }
        at(K.RPAREN)
        args
    }

    private fun parseBracketList(): List<E> {
        at(K.LBRACKET)
        val out = ArrayList<E>()
        if (!peek(K.RBRACKET)) {
            while (true) {
                out += parseExpr(angle = false)
                if (peek(K.COMMA)) { pos++; if (isCloserNext()) break; continue }
                break
            }
        }
        at(K.RBRACKET)
        return out
    }

    /** 分支体：{ 语句 } 块或单表达式。stopArrow=true 在首个顶层 -> / 带空格 ( 停（模板 when）。 */
    private fun parseBranchBody(stopArrow: Boolean): E =
        if (peek(K.LBRACE)) parseBlock() else parseExpr(angle = false, stopArrow = stopArrow)

    private fun parseBranchBlock(): E =
        if (peek(K.LBRACE)) parseBlock() else E.Block(listOf(S.ExprS(parseExpr(angle = false))))

    /** v1.1 Kotlin 化 lambda：{ a, b => 语句…; 末表达式 }；隐式单参 { _ + 1 }。 */
    private fun parseBraceLambda(): E {
        val start = pos
        at(K.LBRACE)
        val ps = ArrayList<String>()
        var hasArrow = false
        if (braceIsLambda(start)) {
            while (!peek(K.DARROW)) {
                ps += if (peek(K.LPAREN)) parseLambdaDestructName() else nameToken()
                if (peek(K.COMMA)) pos++
            }
            hasArrow = true
            at(K.DARROW)
        }
        val body = parseLambdaBody()
        at(K.RBRACE)
        return E.Lambda(if (hasArrow) ps else listOf("_"), body)
    }

    private fun parseLambdaBody(): E {
        val stmts = ArrayList<S>()
        while (!peek(K.RBRACE)) {
            skipSemis()
            if (peek(K.RBRACE)) break
            stmts += parseStmt()
        }
        return if (stmts.size == 1 && stmts[0] is S.ExprS) (stmts[0] as S.ExprS).e else E.Block(stmts)
    }

    /**
     * 大括号起点判 lambda：深度 1 内首个 => 之前只允许名字与逗号（至少一个名字），
     * 防 when 分支体 { 1 -> x } 的箭头被误判；${(a, b) => …} 解构括号成对计数。
     */
    private fun braceIsLambda(start: Int): Boolean {
        var depth = 0
        var pd = 0
        var j = start
        var sawBad = false
        while (j < toks.size) {
            when (val k = toks[j].kind) {
                K.LBRACE -> depth++
                K.RBRACE -> { depth--; if (depth == 0) return false }
                K.DARROW -> return if (depth == 1) !sawBad else false
                K.EOF -> return false
                K.LPAREN -> if (depth == 1) pd++
                K.RPAREN -> if (depth == 1) { if (pd > 0) pd-- else sawBad = true }
                else -> if (depth == 1 && pd > 0) {} else if (depth == 1 && (k == K.IDENT || k == K.SYMBOL || k == K.COMMA)) {} else if (depth == 1) sawBad = true
            }
            j++
        }
        return false
    }

    /** 隐式单参 lambda 诗据：深度 1 内无箭头/无冒号（{ 表达式 }，体里 _ 引用单参）。 */
    private fun braceHasImplicitBody(start: Int): Boolean {
        var depth = 0
        var j = start
        while (j < toks.size) {
            when (val k = toks[j].kind) {
                K.LBRACE -> depth++
                K.RBRACE -> { depth--; if (depth == 0) return true }
                K.DARROW, K.COLON -> if (depth == 1) return false
                K.EOF -> return false
                else -> {}
            }
            j++
        }
        return false
    }

    companion object {
        // 指导：= 命题等式、: 类型测（均可中缀）；== != 是 Bool 函数（v1.1 允许中缀）
        val binops = setOf(
            K.PLUS, K.MINUS, K.STAR, K.SLASH, K.PERCENT, K.POW, K.ASSIGN, K.COLON,
            K.LE, K.GE, K.AMP, K.PIPE, K.IFF,
            K.EQ, K.NEQ,
            K.LAND, K.LOR, K.IMPLIES, K.EQUIV,
        )
        private val symbolCallStart = setOf(K.LT, K.GT, K.EQ, K.NEQ, K.LE, K.GE)
        private val opPrefixKinds = setOf(K.PLUS, K.STAR, K.SLASH, K.PERCENT, K.AMP, K.PIPE, K.IFF)
    }
}

// =====================================================================================
// §3 求值器
// =====================================================================================

internal class InterpError(pos: String, msg: String) : RuntimeException("[subs:$pos] $msg")

/** 运行时帧：闭包作用域链 + 局部变量（@mut/@ref 标注）+ 局部函数。 */
internal class Frame(val parent: Frame?) {
    private val vars = LinkedHashMap<String, V>()
    private val muts = HashSet<String>()
    private val refs = HashSet<String>()
    private val funs = LinkedHashMap<String, MutableList<Pair<D.Fun, Frame>>>()

    fun declareVar(name: String, v: V, mutable: Boolean, ref: Boolean) {
        vars[name] = v; muts.remove(name); refs.remove(name)
        if (mutable) muts += name
        if (ref) refs += name
    }

    fun lookupVar(name: String): V? {
        var f: Frame? = this
        while (f != null) { if (name in f.vars) return f.vars[name]; f = f.parent }
        return null
    }

    fun isMutable(name: String): Boolean {
        var f: Frame? = this
        while (f != null) { if (name in f.vars) return name in f.muts; f = f.parent }
        return false
    }

    fun isRef(name: String): Boolean {
        var f: Frame? = this
        while (f != null) { if (name in f.vars) return name in f.refs; f = f.parent }
        return false
    }

    fun assignVar(name: String, v: V): Boolean {
        var f: Frame? = this
        while (f != null) { if (name in f.vars) { f.vars[name] = v; return true }; f = f.parent }
        return false
    }

    fun declareFun(fn: D.Fun, declFrame: Frame = this) {
        funs.getOrPut(fn.name) { ArrayList() } += fn to declFrame
    }

    fun lookupFunctions(name: String): List<D.Fun> {
        val out = ArrayList<D.Fun>()
        var f: Frame? = this
        while (f != null) { out += (f.funs[name] ?: emptyList()).map { it.first }; f = f.parent }
        return out
    }

    /** 函数 + 其声明帧（闭包源：词法作用域，对齐 lambda 捕获）。 */
    fun lookupFunEntry(name: String): Pair<D.Fun, Frame>? {
        var f: Frame? = this
        while (f != null) {
            val list = f.funs[name]
            if (list != null && list.isNotEmpty()) return list.last()
            f = f.parent
        }
        return null
    }

    fun localNames(): List<String> = vars.keys.toList()
    fun localFunctions(): List<D.Fun> = funs.values.flatten().map { it.first }
}

/** 结构体元数据：字段名顺序 + 默认值 + 函数型字段（尾随 lambda 绑定用，R5）。 */
internal class StructMeta(val name: String, val fieldNames: List<String>,
                          val defaults: Map<String, E>, val funFields: Set<String>)

internal class MethodImpl(val selfTag: String, val trait: String?, val fn: D.Fun, val declFrame: Frame)

/** 尾调用重定向：callFunction 抛出、invokeFunDecl 的蹦床循环捕获（防深递归栈溢出）。 */
internal class TailCall(val fn: D.Fun, val args: List<V>, val declFrame: Frame) : RuntimeException()

/** 函数体内的 return：最近的函数/lambda 边界捕获并作为返回值。 */
internal class ReturnEx(val value: V) : RuntimeException()

internal class Interp(val out: (String) -> Unit = ::println) {

    /** 根帧：全局函数声明挂此（所有闭包链的顶点）。 */
    val root = Frame(null)

    // ---- 声明注册表（模块级，由 loadModule 累积） ----
    val globalFuns = LinkedHashMap<String, MutableList<D.Fun>>()
    val structs = LinkedHashMap<String, StructMeta>()
    val enumCtors = LinkedHashMap<String, String>()   // ctor 名 → 枚举名
    val ctorArity = LinkedHashMap<String, Int>()
    val methods = LinkedHashMap<String, MutableList<MethodImpl>>()
    private val listCtorSet = HashSet<String>()       // 枚举名恰为 List 的 ctor 集合（数组化）
    private val hostFns = LinkedHashMap<String, (List<V>) -> V>()
    private val hostObjs = LinkedHashMap<String, SubsHostObject>()

    init {
        // 预置 Optional/Result（prelude 枚举工厂同构；用户源码可重复声明，同名覆盖）
        regCtor("Some", "Optional", 1)
        regCtor("None", "Optional", 0)
        regCtor("Ok", "Result", 1)
        regCtor("Err", "Result", 1)
    }

    private fun regCtor(ctor: String, enumName: String, arity: Int) {
        enumCtors[ctor] = enumName
        ctorArity[ctor] = arity
        if (enumName == "List") listCtorSet += ctor
    }

    // ---- 宿主互操作（§5 详述，此处仅注册） ----

    fun registerHostFunction(name: String, fn: (List<V>) -> V) { hostFns[name] = fn }
    fun injectObject(name: String, obj: SubsHostObject) {
        hostObjs[name] = obj
    }

    internal fun hostObject(name: String): SubsHostObject? = hostObjs[name]
    internal fun hostFunction(name: String): ((List<V>) -> V)? = hostFns[name]

    // ---- 声明登记 ----

    fun registerDecl(d: D, frame: Frame = root) {
        when (d) {
            is D.Fun -> {
                globalFuns.getOrPut(d.name) { ArrayList() } += d
                frame.declareFun(d, frame)
            }
            is D.Struct -> {
                val names = d.fields.map { it.name }
                val defaults = d.fields.mapNotNull { f -> f.default?.let { f.name to it } }.toMap()
                val funFields = d.fields.filter { it.ty?.name == "(fn)" }.map { it.name }.toSet()
                structs[d.name] = StructMeta(d.name, names, defaults, funFields)
                // 结构体名也是可构造名（模式匹配区分 ctor/bind 时与枚举 ctor 同表）
                enumCtors[d.name] = d.name
                ctorArity[d.name] = names.size
            }
            is D.EnumD -> for ((ctor, arity) in d.ctors) regCtor(ctor, d.name, arity)
            is D.Impl -> for (m in d.members) {
                methods.getOrPut(m.name) { ArrayList() } += MethodImpl(d.self, d.trait, m, frame)
                if (d.trait == null) registerDecl(m, frame)   // 扩展函数：同时挂自由函数表
            }
            is D.Alias, is D.PropDef -> {}   // 类型别名/命题定义：零运行时
        }
    }

    /** 载入 parseFile 产出的条目（声明登记 + 根帧挂函数）。 */
    fun loadEntries(entries: List<Any>, frame: Frame = root) {
        for (e in entries) if (e is D) registerDecl(e, frame)
    }

    /** 脚本模式：顶层语句逐条求值，返回最后一条表达式的值。 */
    fun runScript(entries: List<Any>, frame: Frame = root): V? {
        loadEntries(entries, frame)
        var last: V? = null
        for (e in entries) if (e is S) last = evalStmt(e, frame, tail = false)
        return last
    }

    /** 程序模式：载入声明 + 调 main()（无 main 返回 null）。 */
    fun runProgram(entries: List<Any>): V? {
        loadEntries(entries)
        return if (root.lookupFunctions("main").isNotEmpty())
            callFunction("main", emptyList(), root, "<main>")
        else null
    }

    // ---- 参数绑定 ----

    /** 形参实参对齐：位置 + 命名 + 默认值（编译侧 O3 决策 69/84 同构）。默认值在声明帧求值。 */
    internal fun bindParams(frame: Frame, params: List<Pm>, args: List<V>, named: Map<String, V> = emptyMap()): List<V> {
        val out = ArrayList<V>(args)
        while (out.size < params.size) out += V.Null
        for ((k, v) in named) {
            val idx = params.indexOfFirst { it.name == k }
            if (idx >= 0) out[idx] = v
        }
        for (i in params.indices) {
            if (i >= args.size && params[i].name !in named && params[i].default != null)
                out[i] = evalExpr(params[i].default!!, frame, tail = false)
        }
        params.forEachIndexed { i, p ->
            frame.declareVar(p.name, out.getOrNull(i) ?: V.Null, "mut" in p.anns, "ref" in p.anns)
        }
        return out
    }

    /** 函数调用分派：用户函数（词法闭包）→ 内建 @root → 宿主函数 → 方法字典自由形态 → 结构体构造子。 */
    fun callFunction(name: String, args: List<V>, frame: Frame, pos: String,
                     named: Map<String, V> = emptyMap(), tail: Boolean = false): V {
        val entry = frame.lookupFunEntry(name)
        if (entry != null) {
            val (fn, declFrame) = entry
            // 实参补全（默认值/具名）须在与调用无关的草稿帧完成——bindParams 会把形参
            // declareVar 进目标帧；直接写 declFrame 会污染共享根帧（后续调用覆盖形参）。
            val applied = bindParams(Frame(null), fn.params, args, named)
            if (tail && fn.body != null) throw TailCall(fn, applied, declFrame)
            return invokeFunDecl(fn, applied, declFrame)
        }
        // 内建/宿主函数（@root 名）：用户无同名定义时走内建
        val builtin = BUILTINS[name]
        if (builtin != null) return builtin(this, args, pos)
        val hostFn = hostFns[name]
        if (hostFn != null) return hostFn(args)
        // 类型类方法自由形态（fold(xs, 0, f)）：首实参 tag 走字典
        val rec = args.firstOrNull()
        if (rec != null) {
            val m = lookupTraitMethod(name, typeTag(rec))
            if (m != null) return invokeImplMethod(m, rec, args.drop(1), frame)
        }
        // 结构体构造子（位置实参形态：Point(1, 2)）
        if (name in structs) return structCtorValues(name, args)
        throw InterpError(pos, "未定义的函数「$name」")
    }

    /** 重载分派：按实参数量匹配（编译侧参数个数判据的运行时近似）。 */
    private fun pickOverload(fns: List<D.Fun>, args: List<V>): D.Fun =
        fns.firstOrNull { it.params.size == args.size } ?: fns.last()

    /** 方法字典查找：接收者 tag 命中（无名义区分，最近后注册优先）。 */
    internal fun lookupTraitMethod(name: String, selfTag: String): MethodImpl? {
        val ms = methods[name] ?: return null
        return ms.lastOrNull { it.selfTag == selfTag }
    }

    private fun invokeImplMethod(m: MethodImpl, receiver0: V, args: List<V>, frame: Frame): V {
        var recv = receiver0
        var curArgs = args
        while (true) {
            val child = Frame(m.declFrame)
            val ps = m.fn.params
            child.declareVar(ps[0].name, recv, "mut" in ps[0].anns, "ref" in ps[0].anns)
            bindParams(child, ps.drop(1), curArgs)
            child.declareFun(m.fn, m.declFrame)   // 体内自递归（自由形态：接收子占首位）
            try {
                return m.fn.body?.let { evalExpr(it, child, tail = true) } ?: V.Null
            } catch (tc: TailCall) {
                if (tc.fn === m.fn) {
                    // 尾调用实参含接收子（bindParams(fn.params, …) 的产物）：重排接收子与余参
                    if (tc.args.isNotEmpty()) { recv = tc.args[0]; curArgs = tc.args.drop(1) } else curArgs = emptyList()
                    continue
                }
                // 跨函数/跨方法尾调用：本帧丢弃直调（实参已含接收子位，invokeFunDecl 按全参数表绑定）
                return invokeFunDecl(tc.fn, tc.args, tc.declFrame)
            } catch (e: ReturnEx) {
                return e.value
            }
        }
    }

    /** 执行函数体（蹦床：尾调用复用本帧，防栈爆）。 */
    internal fun invokeFunDecl(fn: D.Fun, args: List<V>, definingFrame: Frame): V {
        var cur = args
        while (true) {
            val child = Frame(definingFrame)
            child.declareFun(fn)
            bindParams(child, fn.params, cur)
            try {
                return fn.body?.let { evalExpr(it, child, tail = true) } ?: V.Null
            } catch (tc: TailCall) {
                if (tc.fn === fn) { cur = tc.args; continue }
                return invokeFunDecl(tc.fn, tc.args, tc.declFrame)
            } catch (e: ReturnEx) {
                return e.value
            }
        }
    }

    // ---- 可变拷贝四规则（对齐 root doc《副作用与可变性控制》修正案 / Checker.binderNeedsCopy / __dc） ----

    internal fun bindCopy(v: V, targetRef: Boolean, sourceRef: Boolean, targetMut: Boolean, sourceMut: Boolean): V {
        val tgtPure = !targetRef && !targetMut
        val srcPure = !sourceRef && !sourceMut
        val copy = (targetMut && (!targetRef || srcPure)) ||
            (sourceMut && tgtPure) ||
            (sourceRef && !targetRef)
        return if (copy) deepCopy(v) else v
    }

    /** 深拷贝（对齐编译侧 __dc：数组/枚举/结构体递归拷，函数值透传）。 */
    internal fun deepCopy(v: V): V = when (v) {
        is V.Arr -> V.Arr(v.items.map { deepCopy(it) }.toMutableList())
        is V.TagCtor -> V.TagCtor(v.name, v.args.map { deepCopy(it) })
        is V.Obj -> V.Obj(v.typeName, v.fields.mapValuesTo(LinkedHashMap()) { deepCopy(it.value) })
        else -> v   // Num/Str/Bool/Null/Fun/HostFn/HostObj/Range：透传
    }

    // ==================== 求值 ====================

    internal fun evalStmt(s: S, frame: Frame, tail: Boolean = false): V? = when (s) {
        is S.Var -> {
            val v = evalExpr(s.value, frame, tail = false)
            val mutable = "mut" in s.anns
            val ref = "ref" in s.anns
            val bound = bindCopy(v, ref, sourceIsRef(s.value, frame), mutable, sourceIsMut(s.value, frame))
            if (s.destruct != null) {
                // 元组解构 var (a, b) = e：分量绑定（编译侧 const [x, y] = e 同构）
                val items = (bound as? V.Arr)?.items
                if (items != null) s.destruct.forEachIndexed { i, nm ->
                    frame.declareVar(nm, items.getOrNull(i) ?: V.Null, mutable, ref)
                }
                else frame.declareVar(s.destruct.first(), bound, mutable, ref)
            } else frame.declareVar(s.name, bound, mutable, ref)
            bound
        }
        is S.Assign -> {
            val v = evalExpr(s.value, frame, tail = false)
            when (val t = s.target) {
                is E.Name -> {
                    if (!frame.isMutable(t.name)) throw InterpError("", "不可变变量不能赋值：${t.name}（声明加 @mut 或赋值包 unchecked）")
                    val bound = bindCopy(v, targetRef = frame.isRef(t.name), sourceRef = false,
                        targetMut = frame.isMutable(t.name), sourceMut = false)
                    if (!frame.assignVar(t.name, bound)) throw InterpError("", "未定义的变量「${t.name}」")
                }
                is E.Field -> {
                    val obj = evalExpr(t.target, frame, tail = false)
                    when (obj) {
                        is V.Obj -> obj.fields[t.name] = v
                        is V.HostObj -> obj.obj.setField(t.name, v)
                        else -> throw InterpError("", "不能对非结构体值访问字段 " + t.name)
                    }
                }
                else -> throw InterpError("", "赋值目标必须是变量名或字段访问")
            }
            V.Null
        }
        is S.ExprS -> evalExpr(s.e, frame, tail)
        is S.Return -> throw ReturnEx(s.e?.let { evalExpr(it, frame, tail = false) } ?: V.Null)
        S.Noop -> V.Null
        is S.Unchecked -> evalStmt(s.inner, frame, tail)
        is S.FunS -> { registerDecl(s.fn, frame); frame.declareFun(s.fn, frame); V.Null }
    }

    private fun sourceIsRef(e: E, frame: Frame): Boolean = e is E.Name && frame.isRef(e.name)
    private fun sourceIsMut(e: E, frame: Frame): Boolean = e is E.Name && frame.isMutable(e.name)

    internal fun evalExpr(e: E, frame: Frame, tail: Boolean = false): V = when (e) {
        is E.IntL -> V.Num(e.value.toDouble())
        is E.FloatL -> V.Num(e.value.toDouble())
        is E.StrL -> V.Str(e.value)
        is E.Name -> evalName(e.name, frame)
        E.NoopE -> V.Null
        is E.Bin -> evalBin(e.op, e.l, e.r, frame, "")
        is E.Uni -> evalUni(e.op, e.e, frame)
        is E.Call -> evalCall(e, frame, tail)
        is E.SymCall -> evalSymCall(e.op, e.args, frame, "")
        is E.Block -> evalBlock(e, frame, tail)
        is E.If -> {
            val c = evalExpr(e.cond, frame, tail = false)
            evalExpr(if (truthy(c)) e.then else (e.els ?: E.Block(emptyList())), frame, tail)
        }
        is E.When -> evalWhen(e, frame, tail)
        is E.Lambda -> {
            // 修正案（副作用与可变性控制）：闭包创建时深拷捕获变量——编译侧 JS 闭包不透明做不到，
            // 此处按编译侧行为透传捕获帧（与 SfA/编译交叉验证口径一致；分歧已记录于文件头注）
            V.Fun { args -> invokeLambda(e, args, frame) }
        }
        is E.StructCtor -> structCtor(e.struct, e.assigns.associate { it }, emptyList(), frame, "")
        is E.Field -> evalField(evalExpr(e.target, frame, tail = false), e.name, frame, "")
        is E.Tuple -> V.Arr(e.items.map { evalExpr(it, frame, tail = false) }.toMutableList())
        is E.FunRef -> {
            // o::f（方法引用）：绑接收者的闭包；名字引用：按函数名包
            val target = evalExpr(e.target, frame, tail = false)
            V.Fun { args ->
                callFunction(e.name, listOf(target) + args, frame, "<funref>")
            }
        }
        is E.TypeTest -> {
            val v = evalExpr(e.target, frame, tail = false)
            V.Bool(isa(v, e.typeName))
        }
        is E.VarE -> {
            val v = evalExpr(e.value, frame, tail = false)
            val bound = bindCopy(v, "ref" in e.anns, sourceIsRef(e.value, frame), "mut" in e.anns, sourceIsMut(e.value, frame))
            frame.declareVar(e.name, bound, "mut" in e.anns, "ref" in e.anns)
            bound
        }
    }

    private fun evalName(name: String, frame: Frame): V = when (name) {
        "true" -> V.Bool(true)
        "false" -> V.Bool(false)
        "null" -> V.Null
        "Nil" -> if (name in listCtorSet) V.Arr(ArrayList()) else lookupOrFail(name, frame)
        else -> lookupOrFail(name, frame)
    }

    private fun lookupOrFail(name: String, frame: Frame): V =
        frame.lookupVar(name) ?: hostObjs[name]?.let { V.HostObj(name, it) }
            ?: frame.lookupFunEntry(name)?.let { (fn, declFrame) -> V.Fun { args -> invokeFunDecl(fn, args, declFrame) } }
            ?: BUILTINS[name]?.let { V.Fun { args -> it(this, args, "<builtin:$name>") } }
            ?: hostFns[name]?.let { V.Fun { args -> it(args) } }
            ?: throw InterpError("", "未定义的名字「$name」")

    private fun invokeLambda(e: E.Lambda, args: List<V>, env: Frame): V {
        val child = Frame(env)
        bindLambdaParams(child, e.params, args)
        return try { evalExpr(e.body, child, tail = false) }
        catch (r: ReturnEx) { r.value }
    }

    private fun bindLambdaParams(child: Frame, names: List<String>, args: List<V>) {
        names.forEachIndexed { i, name ->
            if (name.startsWith("(") && name.endsWith(")")) {
                // 圆括号单元组解构参数 {(a, b) => …}：单实参元组拆分量
                val parts = name.substring(1, name.length - 1).split(",").map { it.trim() }
                val items = (args.getOrNull(i) as? V.Arr)?.items ?: emptyList()
                parts.forEachIndexed { j, nm -> child.declareVar(nm, items.getOrNull(j) ?: V.Null, false, false) }
            } else child.declareVar(name, args.getOrNull(i) ?: V.Null, false, false)
        }
    }

    private fun evalBlock(b: E.Block, frame: Frame, tail: Boolean): V {
        var last: V? = null
        for ((i, s) in b.stmts.withIndex()) {
            last = evalStmt(s, frame, tail && i == b.stmts.lastIndex)
        }
        return last ?: V.Null
    }

    // ---- 运算符（对齐 evalBin：float64 数值合一；/ 恒浮点；^ 幂） ----

    private fun evalBin(op: String, lx: E, rx: E, frame: Frame, pos: String): V {
        val l = evalExpr(lx, frame, tail = false)
        val r = evalExpr(rx, frame, tail = false)
        return when (op) {
            "+" -> if (l is V.Str || r is V.Str) V.Str(dshStr(l) + dshStr(r)) else arith(l, r) { a, b -> a + b }
            "-" -> arith(l, r) { a, b -> a - b }
            "*" -> arith(l, r) { a, b -> a * b }
            "/" -> { val (a, b) = nums(l, r, op, pos); V.Num(a / b) }
            "%" -> arith(l, r) { a, b -> a % b }
            "≠" -> V.Bool(!valEq(l, r))
            "==" -> V.Bool(valEq(l, r))
            "!=" -> V.Bool(!valEq(l, r))
            "<", "<=", ">", ">=", "≤", "≥" -> {
                val (a, b) = nums(l, r, op, pos)
                V.Bool(when (op) { "<" -> a < b; "≤" -> a <= b; ">" -> a > b; else -> a >= b })
            }
            "&", "∧" -> if (truthy(l)) r else l   // JS &&：左真得右值
            "|", "∨" -> if (truthy(l)) l else r   // JS ||：左真得左值
            // 命题位算符（编译侧 bin() 擦除为 true）：= : → ↔ <-> 等
            "=", ":", "→", "↔", "<->", "->" -> V.Bool(true)
            "∈", "in" -> evalIn(l, r, pos)
            ".." -> {
                val lo = num(l) ?: throw InterpError(pos, "区间下界需为数字")
                val hi = num(r) ?: throw InterpError(pos, "区间上界需为数字")
                V.Range(lo, hi)
            }
            "^" -> { val (a, b) = nums(l, r, op, pos); V.Num(a.pow(b)) }
            else -> throw InterpError(pos, "不支持中缀运算符「$op」（= / → / ↔ 是命题位运算，不参与运行时求值）")
        }
    }

    private fun evalUni(op: String, e: E, frame: Frame): V {
        val v = evalExpr(e, frame, tail = false)
        return when (op) {
            "-" -> V.Num(-(num(v) ?: throw InterpError("", "一元负需要数字")))
            "!", "¬" -> V.Bool(!truthy(v))
            else -> throw InterpError("", "不支持一元运算符「$op」")
        }
    }

    private fun arith(l: V, r: V, f: (Double, Double) -> Double): V {
        val (a, b) = nums(l, r, "+", "")
        return V.Num(f(a, b))
    }

    private fun nums(l: V, r: V, op: String, pos: String): Pair<Double, Double> {
        val a = num(l) ?: throw InterpError(pos, "运算符 $op 需要数字，实际 $l")
        val b = num(r) ?: throw InterpError(pos, "运算符 $op 需要数字，实际 $r")
        return a to b
    }

    private fun num(v: V): Double? = when (v) {
        is V.Num -> v.v
        is V.Str -> v.v.toDoubleOrNull()   // JS 弱类型不启用；仅 Num 字面量通道（编译侧同构）
        else -> null
    }

    private fun truthy(v: V): Boolean = when (v) {
        is V.Bool -> v.v
        V.Null -> false
        else -> true
    }

    private fun evalIn(l: V, r: V, pos: String): V {
        when (r) {
            is V.Range -> return V.Bool(r.lo <= (num(l) ?: return V.Bool(false)) && (num(l) ?: return V.Bool(false)) <= r.hi)
            is V.Arr -> return V.Bool(r.items.any { valEq(it, l) })
            is V.Obj -> if (r.typeName == "Range") {
                val lo = (r.fields["lo"] as? V.Num)?.v ?: 0.0
                val hi = (r.fields["hi"] as? V.Num)?.v ?: 0.0
                val x = num(l) ?: return V.Bool(false)
                return V.Bool(x >= lo && x <= hi)
            }
            else -> {}
        }
        throw InterpError(pos, "in 右操作数需要列表或区间")
    }

    /** 符号前缀调用 ==(a, b) / ≤(a, b) 等：按运算符求值。 */
    private fun evalSymCall(op: String, args: List<E>, frame: Frame, pos: String): V {
        if (op == "≠") return V.Bool(!valEq(evalExpr(args[0], frame), evalExpr(args[1], frame)))
        val mapped = when (op) {
            "=" -> "=="; "≠" -> "!="; "≤" -> "<="; "≥" -> ">="
            else -> op
        }
        return evalBin(mapped, args[0], args[1], frame, pos)
    }

    // ---- 调用 ----

    private fun evalCall(c: E.Call, frame: Frame, tail: Boolean): V {
        val callee = c.callee
        if (callee is E.Field) return evalMethodCall(callee, c, frame, tail)
        if (callee is E.Name) {
            val name = callee.name
            // true()/false()/null() 字面量特殊位（编译侧 call 特判同构）
            when (name) {
                "true" -> return V.Bool(true)
                "false" -> return V.Bool(false)
                "null" -> return V.Null
            }
            // 数组化 List 构造子（枚举名恰为 List）：Nil() → []、Cons(h, t) → [h, ...t]
            if (name in listCtorSet) {
                val argVals = c.args.map { evalExpr(it, frame, tail = false) }
                return when (argVals.size) {
                    0 -> V.Arr(ArrayList())
                    2 -> {
                        val tailV = argVals[1]
                        val items = ArrayList<V>(); items += argVals[0]
                        if (tailV is V.Arr) items += tailV.items
                        V.Arr(items)
                    }
                    else -> V.TagCtor(name, argVals)
                }
            }
            // Some/None/Ok/Err（Optional/Result 预置枚举）
            if (name in preludeCtors) {
                val argVals = c.args.map { evalExpr(it, frame, tail = false) }
                return V.TagCtor(name, argVals)
            }
            // 命名实参 + 结构体构造（checker 合并后形态；parser 直接产出 StructCtor，此处兜底）
            if (name in structs && c.named.isNotEmpty()) {
                val assigns = c.named.map { it.key to it.value }.toList()
                val s = structs[name]!!
                if (assigns.all { it.first in s.fieldNames })
                    return structCtor(name, c.named, c.args, frame, "")
            }
            // 用户定义枚举构造子（非 List/Optional/Result）：位置存参
            if (name in enumCtors && name !in structs) {
                val argVals = c.args.map { evalExpr(it, frame, tail = false) }
                return V.TagCtor(name, argVals)
            }
            val argVals = c.args.map { evalExpr(it, frame, tail = false) }
            val namedVals = c.named.mapValues { evalExpr(it.value, frame, tail = false) }
            // R5：裸调用 X() { … } 的尾随 lambda 绑定到唯一未设置的函数型字段
            if (name in structs && argVals.size == 1 && argVals[0] is V.Fun && c.named.isEmpty()) {
                val meta = structs[name]!!
                val cand = meta.funFields.firstOrNull()
                if (cand != null)
                    return structCtorWithValue(name, cand, argVals[0])
            }
            // 值位名字优先：局部/全局变量遮蔽同名函数（JS 语义）
            val local = frame.lookupVar(name)
            if (local != null) {
                return when (local) {
                    is V.Fun -> local.fn(argVals)
                    is V.HostFn -> local.fn(argVals)
                    else -> throw InterpError("", "不可调用的值：$name")
                }
            }
            // 结构体位置构造
            if (name in structs) return structCtorValues(name, argVals)
            return callFunction(name, argVals, frame, "", namedVals, tail)
        }
        // lambda/函数值直接调用
        val fn = evalExpr(callee, frame, tail = false)
        if (fn is V.Fun) return fn.fn(c.args.map { evalExpr(it, frame, tail = false) })
        if (fn is V.HostFn) return fn.fn(c.args.map { evalExpr(it, frame, tail = false) })
        if (fn is V.TagCtor || fn is V.Obj) throw InterpError("", "不可调用: $fn")
        throw InterpError("", "不可调用: $fn")
    }

    private val preludeCtors = setOf("Some", "None", "Ok", "Err")

    /** 结构体构造（命名域注入 R5 尾随 lambda：唯一 funField 置给定值，其余按默认值）。 */
    private fun structCtorWithValue(name: String, field: String, value: V): V {
        val meta = structs[name] ?: throw InterpError("", "未定义的结构体/构造子「$name」")
        val fields = LinkedHashMap<String, V>()
        for (fn0 in meta.fieldNames) {
            fields[fn0] = if (fn0 == field) value
                else meta.defaults[fn0]?.let { evalExpr(it, root, tail = false) } ?: V.Null
        }
        return V.Obj(name, fields)
    }

    /** 结构体构造（实参已是值：callFunction 结构体名分支用；默认值在根帧求值）。 */
    private fun structCtorValues(name: String, args: List<V>): V {
        val meta = structs[name] ?: throw InterpError("", "未定义的结构体/构造子「$name」")
        val fields = LinkedHashMap<String, V>()
        args.forEachIndexed { i, v -> if (i < meta.fieldNames.size) fields[meta.fieldNames[i]] = v }
        for (fn0 in meta.fieldNames) {
            if (fn0 !in fields) fields[fn0] = meta.defaults[fn0]?.let { evalExpr(it, root, tail = false) } ?: V.Null
        }
        return V.Obj(name, fields)
    }

    /** 结构体构造：命名赋值 + 位置实参 + 默认值 + props 忽略（编译侧工厂同构）。 */
    private fun structCtor(name: String, named: Map<String, E>, posArgs: List<E>, frame: Frame, pos: String,
                           extraField: String? = null, extraValue: V? = null): V {
        val meta = structs[name] ?: throw InterpError(pos, "未定义的结构体/构造子「$name」")
        val fields = LinkedHashMap<String, V>()
        val taken = HashSet<String>()
        for ((k, expr) in named) {
            fields[k] = evalExpr(expr, frame, tail = false); taken += k
        }
        posArgs.forEachIndexed { i, expr ->
            if (i < meta.fieldNames.size) {
                val fn0 = meta.fieldNames[i]
                fields[fn0] = evalExpr(expr, frame, tail = false); taken += fn0
            }
        }
        if (extraField != null && extraValue != null) {
            fields[extraField] = extraValue; taken += extraField
        }
        for (fn0 in meta.fieldNames) {
            if (fn0 !in taken) {
                val def = meta.defaults[fn0]
                fields[fn0] = if (def != null) evalExpr(def, frame, tail = false) else V.Null
            }
        }
        return V.Obj(name, fields)
    }

    /** 字段访问 + 方法路径作值（H6：o.m 不加括号 → 绑定 receiver 的闭包）。 */
    private fun evalField(target: V, name: String, frame: Frame, pos: String): V = when (target) {
        is V.Obj -> target.fields[name] ?: methodPathValue(name, target, frame)
            ?: throw InterpError(pos, "结构体 ${target.typeName} 无字段 $name")
        is V.HostObj -> target.obj.getField(name) ?: methodPathValue(name, target, frame)
            ?: throw InterpError(pos, "宿主对象 ${target.name} 无字段 $name")
        is V.TagCtor -> {
            // 枚举位置参数访问：Some(v).0 形式按位置取（编译侧 v[0]）
            val idx = name.toIntOrNull()
            if (idx != null && idx in target.args.indices) target.args[idx]
            else throw InterpError(pos, "枚举 ${target.name} 无字段 $name")
        }
        is V.Str -> stringField(target, name, frame, pos)
        is V.Arr -> methodPathValue(name, target, frame) ?: throw InterpError(pos, "列表不支持字段 $name")
        is V.Range -> rangeField(target, name, frame, pos)
        else -> methodPathValue(name, target, frame) ?: throw InterpError(pos, "不支持对 $target 访问字段 $name")
    }

    private fun methodPathValue(name: String, receiver: V, frame: Frame): V? {
        val tag = typeTag(receiver)
        val m = lookupTraitMethod(name, tag)
        val hasFree = frame.lookupFunctions(name).isNotEmpty() || hostFns.containsKey(name) || BUILTINS.containsKey(name)
        if (m == null && !hasFree) return null
        return V.Fun { args -> invokeImplMethod(m!!, receiver, args, frame) }
            .takeIf { m != null }
            ?: V.Fun { args -> callFunction(name, listOf(receiver) + args, frame, "<method-path>") }
    }

    /** 方法调用 o.m(args)：Range 内建方法 → 方法字典 → 自由函数接收者首参。 */
    private fun evalMethodCall(field: E.Field, c: E.Call, frame: Frame, tail: Boolean): V {
        val receiver = evalExpr(field.target, frame, tail = false)
        val argVals = c.args.map { evalExpr(it, frame, tail = false) }
        val name = field.name
        when (receiver) {
            is V.Range -> return rangeMethod(receiver, name, argVals, frame)
            is V.HostObj -> {
                val res = receiver.obj.callMethod(name, argVals)
                if (res != null) return res
                val fv = receiver.obj.getField(name)
                if (fv is V.Fun) return fv.fn(argVals)
                if (fv is V.HostFn) return fv.fn(argVals)
            }
            is V.Str -> {
                val sf = stringMethod(receiver, name, argVals)
                if (sf != null) return sf
            }
            else -> {}
        }
        val m = lookupTraitMethod(name, typeTag(receiver))
        if (m != null) return invokeImplMethod(m, receiver, argVals, frame)
        return callFunction(name, listOf(receiver) + argVals, frame, "<method>", tail = tail)
    }

    // ---- when 模式匹配（matchJs 语义同构） ----

    private fun evalWhen(w: E.When, frame: Frame, tail: Boolean): V {
        var subjectVal: V? = null
        var subjFrame = frame
        if (w.subject != null) {
            subjectVal = evalExpr(w.subject, subjFrame, tail = false)
            if (w.binding != null) frame.declareVar(w.binding, subjectVal!!, false, false)
        }
        for (arm in w.arms) {
            val child = Frame(subjFrame)
            if (w.binding != null && subjectVal != null) child.declareVar(w.binding, subjectVal, false, false)
            val matched = when {
                arm.pattern is P.Cond -> {
                    val cond = arm.cond
                    if (cond != null) truthy(evalExpr(cond, child, tail = false)) else true
                }
                arm.pattern is P.Else -> true
                else -> subjectVal != null && matchPattern(arm.pattern, subjectVal, child)
            }
            if (matched) {
                val guardOk = arm.guard == null || truthy(evalExpr(arm.guard, child, tail = false))
                if (guardOk) return evalExpr(arm.body, child, tail)
            }
        }
        // 穷尽性由编译期保证；运行时不匹配兜底 Null（编译侧 matchJs 同构：return null）
        return V.Null
    }

    /** 模式匹配：PatLit 严格相等；PatCtor 按 tag（List ctor 走数组分支、struct 按字段名）；PatIs 结构判定。 */
    private fun matchPattern(p: P, subject: V, frame: Frame): Boolean = when (p) {
        P.Else -> true
        P.Cond -> true
        is P.Bind -> { if (p.name != "_") frame.declareVar(p.name, subject, false, false); true }
        is P.Lit -> {
            val lv = evalExpr(p.value, frame, tail = false)
            strictEq(subject, lv)
        }
        is P.Is -> {
            val ok = isa(subject, p.typeName)
            if (ok && p.bind != null) frame.declareVar(p.bind, subject, false, false)
            ok
        }
        is P.TupleP -> {
            if (subject !is V.Arr || subject.items.size != p.items.size) false
            else p.items.zip(subject.items).all { (pp, sv) -> matchPattern(pp, sv, frame) }
        }
        is P.Ctor -> matchCtor(p, subject, frame)
    }

    /** 裸名模式：已知 ctors 按标签比较，否则按绑定变量（Parser.kt / patternBoundNames 同规则）。 */
    private fun matchCtor(p: P.Ctor, subject: V, frame: Frame): Boolean {
        val cn = p.name.substringAfterLast("::")
        return when (cn) {
            "true" -> subject is V.Bool && subject.v
            "false" -> subject is V.Bool && !subject.v
            "null" -> subject === V.Null
            "Nil" -> subject is V.Arr && subject.items.isEmpty()
            "Cons" -> {
                if (subject !is V.Arr || subject.items.isEmpty() || p.args.size != 2) false
                else {
                    val head = subject.items[0]
                    val tail = V.Arr(subject.items.subList(1, subject.items.size).toMutableList())
                    matchPattern(p.args[0], head, frame) && matchPattern(p.args[1], tail, frame)
                }
            }
            "Some" -> {
                if (subject !is V.TagCtor || subject.name != "Some" || subject.args.isEmpty()) false
                else if (p.args.isEmpty()) true
                else p.args.size == 1 && matchPattern(p.args[0], subject.args[0], frame)
            }
            "None" -> subject is V.TagCtor && subject.name == "None"
            "Ok" -> subject is V.TagCtor && subject.name == "Ok" &&
                (p.args.isEmpty() || (p.args.size == 1 && matchPattern(p.args[0], subject.args.getOrNull(0) ?: V.Null, frame)))
            "Err" -> subject is V.TagCtor && subject.name == "Err" &&
                (p.args.isEmpty() || (p.args.size == 1 && matchPattern(p.args[0], subject.args.getOrNull(0) ?: V.Null, frame)))
            else -> {
                if (p.args.isEmpty()) {
                    // 无参构造子与绑定变量同形：按 ctor 注册表判定
                    if (cn in enumCtors) {
                        if (subject is V.TagCtor) return subject.name == cn
                        if (cn in structs && subject is V.Obj) return subject.typeName == cn
                        return false
                    }
                    if (cn.firstOrNull()?.isLowerCase() == true) {
                        frame.declareVar(cn, subject, false, false); return true
                    }
                    return false
                }
                // 带参解构：枚举按位置（v[0]）；struct 按字段名（path.fieldName）
                if (cn in structs && subject is V.Obj) {
                    val meta = structs[cn]!!
                    if (subject.typeName != cn) return false
                    return p.args.zip(meta.fieldNames).all { (pp, fn) ->
                        matchPattern(pp, subject.fields[fn] ?: V.Null, frame)
                    }
                }
                if (subject is V.TagCtor && subject.name == cn) {
                    if (p.args.size != subject.args.size) return false
                    return p.args.zip(subject.args).all { (pp, sv) -> matchPattern(pp, sv, frame) }
                }
                false
            }
        }
    }

    /** PatLit 的严格相等（matchJs path === lit）：数值/字符串/布尔按值，其余 false。 */
    private fun strictEq(v: V, lit: V): Boolean = when {
        v is V.Num && lit is V.Num -> v.v == lit.v
        v is V.Str && lit is V.Str -> v.v == lit.v
        v is V.Bool && lit is V.Bool -> v.v == lit.v
        v === V.Null && lit === V.Null -> true
        else -> false
    }

    // ---- Range 内建方法（对齐 __range 小运行时：闭区间 [lo, hi]） ----

    private fun rangeField(r: V.Range, name: String, frame: Frame, pos: String): V = when (name) {
        "lo" -> V.Num(r.lo)
        "hi" -> V.Num(r.hi)
        "length", "size", "contains", "toList", "map", "fold" ->
            V.Fun { args -> rangeMethod(r, name, args, frame) }
        else -> throw InterpError(pos, "Range 不支持字段 $name")
    }

    private fun rangeMethod(r: V.Range, name: String, args: List<V>, frame: Frame): V = when (name) {
        "contains" -> { val x = args.getOrNull(0); V.Bool(x is V.Num && x.v >= r.lo && x.v <= r.hi) }
        "length", "size" -> V.Num(kotlin.math.floor(r.hi - r.lo + 1))
        "toList" -> {
            val items = ArrayList<V>()
            var i = kotlin.math.ceil(r.lo)
            while (i <= r.hi) { items += V.Num(i); i += 1.0 }
            V.Arr(items)
        }
        "map" -> {
            val f = args.getOrNull(0) ?: throw InterpError("", "Range.map 需要函数实参")
            val items = ArrayList<V>()
            var i = kotlin.math.ceil(r.lo)
            while (i <= r.hi) { items += callFn(f, listOf(V.Num(i)), frame); i += 1.0 }
            V.Arr(items)
        }
        "fold" -> {
            val init = args.getOrNull(0) ?: throw InterpError("", "Range.fold 需要初值")
            val f = args.getOrNull(1) ?: throw InterpError("", "Range.fold 需要函数实参")
            var acc = init
            var i = kotlin.math.ceil(r.lo)
            while (i <= r.hi) { acc = callFn(f, listOf(V.Num(i), acc), frame); i += 1.0 }
            acc
        }
        else -> throw InterpError("", "Range 不支持方法 $name")
    }

    /** 函数值统一调用通道（V.Fun / V.HostFn；lambda 与宿主函数同构）。 */
    internal fun callFn(fn: V, args: List<V>, frame: Frame): V = when (fn) {
        is V.Fun -> fn.fn(args)
        is V.HostFn -> fn.fn(args)
        else -> throw InterpError("", "不可调用: $fn")
    }

    private fun stringField(s: V.Str, name: String, frame: Frame, pos: String): V =
        methodPathValue(name, s, frame) ?: throw InterpError(pos, "字符串不支持字段/方法 $name")

    private fun stringMethod(s: V.Str, name: String, args: List<V>): V? = null   // 一律走自由函数分派
}

// =====================================================================================
// §4 内建表（@root 白名单的纯函数子集——IO/异步/网络由宿主侧实现；签名对齐 core/*.subl 声明）
// =====================================================================================

internal typealias BuiltinFn = (Interp, List<V>, String) -> V

@Suppress("unused")
internal val BUILTINS: Map<String, BuiltinFn> = mapOf(
    // ---- 标准输出 ----
    "print" to { i, a, _ -> i.out(a.joinToString(" ") { dshStr(it) }); V.Null },
    "println" to { i, a, _ -> i.out(a.joinToString(" ") { dshStr(it) } + "\n"); V.Null },

    // ---- 元组 ----
    "emptyTuple" to { _, _, _ -> V.Arr(ArrayList()) },
    "singleTuple" to { _, a, _ -> V.Arr(arrayListOf(a.getOrElse(0) { V.Null })) },

    // ---- 字符串 ----
    "concat" to { _, a, p -> V.Str(a.joinToString("") { dshStr(it) }) },
    "in" to { _, a, p ->
        val v = arg(a, 0, p)
        val r = arg(a, 1, p)
        when (r) {
            is V.Range -> { val x = (v as? V.Num)?.v ?: throw InterpError(p, "in 左操作数需为数字"); V.Bool(r.lo <= x && x <= r.hi) }
            else -> V.Bool(true)   // 语义层已拦；编译侧兜底 true
        }
    },
    "length" to { _, a, p ->
        val v = arg(a, 0, p)
        V.Num(when (v) { is V.Str -> v.v.length.toDouble(); is V.Arr -> v.items.size.toDouble(); else -> throw InterpError(p, "length 需要 Str 或 List") })
    },
    "charAt" to { _, a, p ->
        val s = strArg(a, 0, p); val k = intArg(a, 1, p)
        V.Str(if (k in s.indices) s[k].toString() else "")
    },
    "substring" to { _, a, p ->
        val s = strArg(a, 0, p); val x = intArg(a, 1, p); val y = intArg(a, 2, p)
        V.Str(s.substring(x.coerceIn(0, s.length), y.coerceIn(0, s.length)))
    },
    "strCmp" to { _, a, p ->
        val x = strArg(a, 0, p); val y = strArg(a, 1, p)
        V.Num(if (x < y) -1.0 else if (x > y) 1.0 else 0.0)
    },
    "toStr" to { _, a, p -> V.Str(dshStr(arg(a, 0, p))) },
    "parseNat" to { _, a, p -> parseOpt(strArg(a, 0, p)) { it.toDoubleOrNull()?.let { d -> if (d == kotlin.math.floor(d)) d else null } } },
    "strUpper" to { _, a, p -> V.Str(strArg(a, 0, p).uppercase()) },
    "strLower" to { _, a, p -> V.Str(strArg(a, 0, p).lowercase()) },
    "strTrim" to { _, a, p -> V.Str(strArg(a, 0, p).trim()) },
    "startsWith" to { _, a, p -> V.Bool(strArg(a, 0, p).startsWith(strArg(a, 1, p))) },
    "endsWith" to { _, a, p -> V.Bool(strArg(a, 0, p).endsWith(strArg(a, 1, p))) },
    "strReplace" to { _, a, p -> V.Str(strArg(a, 0, p).replace(strArg(a, 1, p), strArg(a, 2, p))) },
    "strSplit" to { _, a, p -> V.Arr(strArg(a, 0, p).split(strArg(a, 1, p)).map { V.Str(it) }.toMutableList()) },
    "strIndexOf" to { _, a, p ->
        val k = strArg(a, 0, p).indexOf(strArg(a, 1, p))
        if (k >= 0) V.TagCtor("Some", listOf(V.Num(k.toDouble()))) else V.TagCtor("None", emptyList())
    },

    // ---- 数值转换（Nat/Rat 运行时同为 float64，转换恒等） ----
    "toReal" to { _, a, p -> numOrArg(a, 0, p) },
    "toRat" to { _, a, p -> numOrArg(a, 0, p) },
    "toNat" to { _, a, p -> V.Num(kotlin.math.floor(numOrArg(a, 0, p).v)) },
    "parseRat" to { _, a, p -> parseOpt(strArg(a, 0, p)) { it.toDoubleOrNull() } },

    // ---- 浮点（P10）----
    "abs" to { _, a, p -> V.Num(kotlin.math.abs(numOrArg(a, 0, p).v)) },
    "floor" to { _, a, p -> V.Num(kotlin.math.floor(numOrArg(a, 0, p).v)) },
    "ceil" to { _, a, p -> V.Num(kotlin.math.ceil(numOrArg(a, 0, p).v)) },
    "pow" to { _, a, p -> V.Num(numOrArg(a, 0, p).v.pow(numOrArg(a, 1, p).v)) },

    // ---- Math（v2.0）----
    "mathMin" to { _, a, p -> V.Num(kotlin.math.min(numOrArg(a, 0, p).v, numOrArg(a, 1, p).v)) },
    "mathMax" to { _, a, p -> V.Num(kotlin.math.max(numOrArg(a, 0, p).v, numOrArg(a, 1, p).v)) },
    "mathClamp" to { _, a, p ->
        val x = numOrArg(a, 0, p).v; val lo = numOrArg(a, 1, p).v; val hi = numOrArg(a, 2, p).v
        V.Num(kotlin.math.min(kotlin.math.max(x, lo), hi))
    },
    "mathRound" to { _, a, p -> V.Num(kotlin.math.floor(numOrArg(a, 0, p).v + 0.5)) },
    "mathSin" to { _, a, p -> V.Num(kotlin.math.sin(numOrArg(a, 0, p).v)) },
    "mathCos" to { _, a, p -> V.Num(kotlin.math.cos(numOrArg(a, 0, p).v)) },
    "mathTan" to { _, a, p -> V.Num(kotlin.math.tan(numOrArg(a, 0, p).v)) },

    // ---- 伪随机（@impure 侧；宿主可覆盖） ----
    "rand" to { _, a, p -> V.Num(kotlin.math.floor(kotlin.random.Random.nextDouble() * numOrArg(a, 0, p).v)) },

    // ---- JSON（V 表示：对象 → V.Obj("Json", 字段)；数组 → V.Arr；标量 → Num/Str/Bool/Null） ----
    "jsonParse" to { _, a, p -> JsonSupport.parse(strArg(a, 0, p), p) },
    "jsonToStr" to { _, a, p -> V.Str(JsonSupport.stringify(arg(a, 0, p))) },
    "jsonGet" to { _, a, p ->
        val j = arg(a, 0, p); val key = strArg(a, 1, p)
        optOf((j as? V.Obj)?.fields?.get(key))
    },
    "jsonAt" to { _, a, p ->
        val j = arg(a, 0, p); val k = intArg(a, 1, p)
        optOf((j as? V.Arr)?.items?.getOrNull(k))
    },
    "jsonLen" to { _, a, p ->
        val j = arg(a, 0, p)
        V.Num(when (j) { is V.Arr -> j.items.size.toDouble(); is V.Obj -> j.fields.size.toDouble(); else -> 0.0 })
    },
    "jsonStr" to { _, a, p -> strArgOf(arg(a, 0, p), "jsonStr", p) },
    "jsonNum" to { _, a, p -> numOrArg(a, 0, p) },
    "jsonBool" to { _, a, p -> V.Bool(arg(a, 0, p) is V.Bool && (arg(a, 0, p) as V.Bool).v) },
    "jsonIsNull" to { _, a, p -> V.Bool(arg(a, 0, p) === V.Null) },
    "jsonIsArr" to { _, a, p -> V.Bool(arg(a, 0, p) is V.Arr) },
    "jsonToList" to { _, a, p ->
        val j = arg(a, 0, p)
        if (j is V.Arr) V.TagCtor("Some", listOf(j)) else V.TagCtor("None", emptyList())
    },
)

internal fun arg(args: List<V>, i: Int, pos: String): V =
    args.getOrNull(i) ?: throw InterpError(pos, "第 ${i + 1} 个实参缺失")
internal fun strArg(args: List<V>, i: Int, pos: String): String =
    (args.getOrNull(i) as? V.Str)?.v ?: throw InterpError(pos, "第 ${i + 1} 个实参需要 Str，实际 ${args.getOrNull(i)}")
internal fun intArg(args: List<V>, i: Int, pos: String): Int =
    (args.getOrNull(i) as? V.Num)?.v?.toInt()
        ?: throw InterpError(pos, "第 ${i + 1} 个实参需要 Nat，实际 ${args.getOrNull(i)}")
internal fun numOrArg(args: List<V>, i: Int, pos: String): V.Num =
    (args.getOrNull(i) as? V.Num) ?: throw InterpError(pos, "第 ${i + 1} 个实参需要数值，实际 ${args.getOrNull(i)}")
internal fun strArgOf(v: V, name: String, pos: String): V.Str =
    (v as? V.Str) ?: throw InterpError(pos, "$name 需要 JSON 字符串值，实际 $v")

internal fun parseOpt(s: String, f: (String) -> Double?): V {
    val d = f(s)
    return if (d != null) V.TagCtor("Some", listOf(V.Num(d))) else V.TagCtor("None", emptyList())
}

internal fun optOf(v: V?): V =
    if (v != null) V.TagCtor("Some", listOf(v)) else V.TagCtor("None", emptyList())

/** 最小 JSON 解析器（零依赖；与 JS JSON.parse 的标量/数组/对象结构一一对应）。 */
internal object JsonSupport {
    fun parse(s: String, pos: String): V {
        val p = JsonParser(s)
        return try {
            p.skipWs(); p.parseValue()
        } catch (e: Exception) {
            throw InterpError(pos, "jsonParse 失败：${e.message}")
        }
    }

    fun stringify(v: V): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: V) {
        when (v) {
            is V.Num -> sb.append(jsNum(v.v))
            is V.Str -> sb.append(jsonEscape(v.v))
            is V.Bool -> sb.append(if (v.v) "true" else "false")
            V.Null -> sb.append("null")
            is V.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { i, it -> if (i > 0) sb.append(','); write(sb, it) }
                sb.append(']')
            }
            is V.Obj -> {
                sb.append('{')
                v.fields.entries.forEachIndexed { i, (k, it) ->
                    if (i > 0) sb.append(',')
                    sb.append(jsonEscape(k)); sb.append(':'); write(sb, it)
                }
                sb.append('}')
            }
            else -> sb.append("null")   // 函数/宿主对象/区间不可序列化：与 JSON.stringify 一致（降级 null）
        }
    }

    private fun jsonEscape(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\t' -> sb.append("\\t")
            '\r' -> sb.append("\\r")
            else -> if (c.code < 0x20) sb.append("\\u" + c.code.toString(16).padStart(4, '0')) else sb.append(c)
        }
        return sb.append('\"').toString()
    }
}

internal class JsonParser(private val s: String) {
    private var i = 0

    fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }

    fun parseValue(): V {
        skipWs()
        if (i >= s.length) throw IllegalStateException("意外的输入结束")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { expect("true"); V.Bool(true) }
            'f' -> { expect("false"); V.Bool(false) }
            'n' -> { expect("null"); V.Null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): V {
        i++
        val fields = LinkedHashMap<String, V>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return V.Obj("Json", fields) }
        while (true) {
            skipWs()
            if (i >= s.length || s[i] != '"') throw IllegalStateException("JSON 对象需要字符串键")
            val key = parseString().v
            skipWs()
            if (i >= s.length || s[i] != ':') throw IllegalStateException("JSON 对象需要冒号")
            i++
            fields[key] = parseValue()
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == '}') { i++; return V.Obj("Json", fields) }
            throw IllegalStateException("JSON 对象需要逗号或闭花括号")
        }
    }

    private fun parseArray(): V {
        i++
        val items = ArrayList<V>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return V.Arr(items) }
        while (true) {
            items += parseValue()
            skipWs()
            if (i < s.length && s[i] == ',') { i++; continue }
            if (i < s.length && s[i] == ']') { i++; return V.Arr(items) }
            throw IllegalStateException("JSON 数组需要逗号或闭方括号")
        }
    }

    private fun parseString(): V.Str {
        i++
        val sb = StringBuilder()
        while (i < s.length) {
            when (val c = s[i++]) {
                '"' -> return V.Str(sb.toString())
                '\\' -> {
                    if (i >= s.length) throw IllegalStateException("JSON 字符串转义未闭合")
                    val e = s[i++]
                    sb.append(when (e) {
                        'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'b' -> '\b'; 'f' -> 12.toChar()
                        '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                        'u' -> {
                            if (i + 4 > s.length) throw IllegalStateException("JSON unicode 转义不完整")
                            val code = s.substring(i, i + 4).toInt(16); i += 4
                            code.toChar()
                        }
                        else -> throw IllegalStateException("非法 JSON 转义 \\$e")
                    })
                }
                else -> sb.append(c)
            }
        }
        throw IllegalStateException("JSON 字符串未闭合")
    }

    private fun parseNumber(): V {
        val start = i
        if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        if (i == start) throw IllegalStateException("非法 JSON 值")
        return V.Num(s.substring(start, i).toDouble()
            ?: throw IllegalStateException("非法 JSON 数字"))
    }

    private fun expect(w: String) {
        if (s.substring(i, minOf(i + w.length, s.length)) != w) throw IllegalStateException("期望 $w")
        i += w.length
    }
}


// =====================================================================================
// §5 预置标准库（最小集，内嵌；宿主可用 loadModule 载入完整 core/ 覆盖或扩展）
// 与 core/collection 同构：数组化 List、Optional/Result、Functor/Foldable 方法糖。
// =====================================================================================

internal const val PRELUDE = """// ---- 预置标准库（SubsInterpreter 内嵌； @@ 插值符转义） ----
enum List[T] { Nil() Cons(T, List[T]) }
enum Optional[T] { None() Some(T) }
enum Result[T, E] { Ok(T) Err(E) }

class Functor[f[_]] { fun map(fa: f[a], f: (a) => b): f[b] }
class Foldable[t[_]] { fun fold(fa: t[a], init: b, f: (b, a) => b): b }

fun size(xs: List[a]): Nat = when (xs) {
  Nil => 0
  is Cons(h, t) => 1 + size(t)
}

fun head(xs: List[a]): a = when (xs) { is Cons(h, _) => h }

fun tail(xs: List[a]): List[a] = when (xs) { is Cons(_, t) => t }

fun emptyList[a](): List[a] = Nil()

fun append(xs: List[a], ys: List[a]): List[a] = when (xs) {
  Nil => ys
  is Cons(h, t) => Cons(h, append(t, ys))
}

fun reverse(xs: List[a]): List[a] = when (xs) {
  Nil => Nil()
  is Cons(h, t) => append(reverse(t), Cons(h, Nil()))
}

fun filter(xs: List[a], p: (a) => Bool): List[a] = when (xs) {
  Nil => Nil()
  is Cons(h, t) => if (p(h)) Cons(h, filter(t, p)) else filter(t, p)
}

fun flatMap(xs: List[a], f: (a) => List[b]): List[b] = when (xs) {
  Nil => Nil()
  is Cons(h, t) => append(f(h), flatMap(t, f))
}

fun forall(xs: List[a], p: (a) => Bool): Bool = when (xs) {
  Nil => true
  is Cons(h, t) => if (p(h)) forall(t, p) else false
}

fun exists(xs: List[a], p: (a) => Bool): Bool = when (xs) {
  Nil => false
  is Cons(h, t) => if (p(h)) true else exists(t, p)
}

impl Functor for List[a] {
  fun map(fa: List[a], f: (a) => b): List[b] = when (fa) { Nil => Nil() is Cons(h, t) => Cons(f(h), t.map(f)) }
}

impl Foldable for List[a] {
  fun fold(fa: List[a], init: b, f: (b, a) => b): b = when (fa) { Nil => init is Cons(h, t) => fold(t, f(init, h), f) }
}
"""

// =====================================================================================
// §6 对外 API
// =====================================================================================

/**
 * Subs 解释器（Sublimation 脚本运行时）。
 *
 * 使用：
 *   val subs = SubsInterpreter()                       // 内嵌最小标准库已就绪
 *   subs.loadModule(sourceOfCoreFile)                  // 可选：装载更多 .subl 模块
 *   subs.injectObject("world", gameWorldHostObject)     // 可选：注入宿主游戏状态对象
 *   subs.registerHostFunction("queryData") { args -> … } // 可选：注册宿主函数
 *   val v = subs.eval("1 + 2 * 3")                     // 脚本模式：求值片段
 *   subs.run(programSource)                            // 程序模式：声明 + main()
 *
 * 与编译执行的一致性：值表示/模式匹配/@mut/算术/插值按编译侧运行时语义（见文件头注）。
 *
 * @param output print 输出通道（默认 stdout；Kotlin/JS 编译时映射 console.log）。
 */
class SubsInterpreter(private val output: (String) -> Unit = ::println) {

    internal val interp = Interp(output)

    init {
        // 内嵌预置标准库：List/Optional/Result + Functor/Foldable 方法糖
        loadModule(PRELUDE.replace("@@", "$"))
    }

    /** 装载模块（仅声明：fun/struct/enum/impl/type）。同一解释器实例可累积多个模块。 */
    fun loadModule(source: String) {
        val toks = Lexer(source, "<module>").tokenize()
        val entries = Parser(toks, "<module>", scriptMode = false).parseFile()
        interp.loadEntries(entries, interp.root)
    }

    /** 脚本模式：解析 .subs/.subl 片段，执行顶层语句，返回最后一条表达式的值。 */
    fun eval(source: String): V? {
        val toks = Lexer(source, "<eval>").tokenize()
        val entries = Parser(toks, "<eval>", scriptMode = true).parseFile()
        return interp.runScript(entries, interp.root)
    }

    /** 程序模式：装载声明并执行 main()（不存在的脚本/无 main 返回 null）。 */
    fun run(source: String): V? {
        val toks = Lexer(source, "<run>").tokenize()
        val entries = Parser(toks, "<run>", scriptMode = false).parseFile()
        return interp.runProgram(entries)
    }

    /** 调用已声明的函数（按名 + 实参表）。 */
    fun callFunction(name: String, vararg args: V): V? {
        val entry = interp.root.lookupFunEntry(name) ?: return null
        return interp.let { it.invokeFunDeclPublic(entry.first, args.toList(), entry.second) }
    }

    /** 注册宿主函数：.subl 按名调用，实参按编译侧表示传入。 */
    fun registerHostFunction(name: String, fn: (List<V>) -> V) =
        interp.registerHostFunction(name, fn)

    /** 注入宿主对象：模组逻辑按名直接访问字段/调方法（无白名单 op）。 */
    fun injectObject(name: String, obj: SubsHostObject) =
        interp.injectObject(name, obj)

    /**
     * 安装 sublEval/sublRun 宿主桥自回路（演示/自测用）：
     * 把本解释器自身注册为 @root 内建 sublEval/sublRun 的实现，返回值按编译器内建表签名
     * 包 Result（Ok(value) / Err(msg)，与 core/script/script.subl 声明一致）。
     * 真实部署时宿主（Android WebView / CandyIDE）实现 __subl.call 注册桥。
     */
    fun installSublEvalBridge() {
        registerHostFunction("sublEval") { args ->
            val src = (args.getOrNull(0) as? V.Str)?.v
                ?: return@registerHostFunction V.TagCtor("Err", listOf(V.Str("sublEval 需要 Str 源码")))
            try {
                val child = SubsInterpreter(output)
                V.TagCtor("Ok", listOf(child.eval(src) ?: V.Null))
            } catch (e: Throwable) {
                V.TagCtor("Err", listOf(V.Str(e.message ?: e.toString())))
            }
        }
        registerHostFunction("sublRun") { args ->
            val src = (args.getOrNull(0) as? V.Str)?.v
                ?: return@registerHostFunction V.TagCtor("Err", listOf(V.Str("sublRun 需要 Str 源码")))
            try {
                val child = SubsInterpreter(output)
                V.TagCtor("Ok", listOf(child.run(src) ?: V.Null))
            } catch (e: Throwable) {
                V.TagCtor("Err", listOf(V.Str(e.message ?: e.toString())))
            }
        }
    }
}

/** Parity：invokeFunDecl 的公开包装（callFunction 用）。 */
internal fun Interp.invokeFunDeclPublic(fn: D.Fun, args: List<V>, declFrame: Frame): V =
    invokeFunDecl(fn, args, declFrame)