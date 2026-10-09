package sugared.functor.ast

sealed interface Entry

data class DeclEntry(val decl: Decl) : Entry
data class StmtEntry(val stmt: Stmt) : Entry

data class FileAst(val entries: List<Entry>)

/** 所有节点可携带 "行:列" 源位置（解析器填充，诊断使用） */
sealed interface Node {
    val pos: String get() = ""
}

// ---------- 声明 ----------

sealed interface Decl : Node

data class FunDecl(
    val name: String,
    val annotations: List<String>,
    val theory: List<TheoryParam>,
    val params: List<Param>,
    val preps: List<Expr>,
    val retType: Type?,
    val posts: List<Expr>,
    val body: Expr?,
    override val pos: String = "",
) : Decl

/** class ≈ Rust trait；成员为签名级 FunDecl（body=null）或带默认实现 */
data class ClassDecl(
    val name: String,
    val theory: List<TheoryParam>,
    val members: List<FunDecl>,
    override val pos: String = "",
) : Decl

data class ImplDecl(
    val trait: Type,
    val self: Type,
    val members: List<FunDecl>,
    override val pos: String = "",
) : Decl

data class StructDecl(
    val name: String,
    val theory: List<TheoryParam>,
    val fields: List<Param>,
    override val pos: String = "",
) : Decl

data class EnumDecl(
    val name: String,
    val theory: List<TheoryParam>,
    val ctors: List<Ctor>,
    override val pos: String = "",
) : Decl

/**
 * 类型别名（决策 30）：`type Name[T] = Type`。
 * v1 右值仅支持具名/应用类型；函数类型/元组右值待类型系统扩展（工程规范 §5.2）。
 */
data class TypeAliasDecl(
    val name: String,
    val theory: List<TheoryParam>,
    val target: Type,
    override val pos: String = "",
) : Decl

data class Ctor(val name: String, val fields: List<Type>)

sealed interface TheoryParam

/** `[T]` 或 `[T: Show]` —— 类型参数，可带型类约束 */
data class TypeParam(val name: String, val constraint: String?) : TheoryParam

/** `[@p]` —— 命题参数（方括号里放命题的名字/占位） */
data class PropParam(val name: String) : TheoryParam

/** 形参可带注解（如 @tuple 变长元组，决策 61）；默认空保证既有构造点零改动。
 *  default（O3，决策 69）：仅 struct 字段允许，函数形参带默认值报错。 */
data class Param(
    val name: String,
    val type: Type,
    val annotations: List<String> = emptyList(),
    val default: Expr? = null,
)

/**
 * 类型表示（第 2 轮 T1：sealed 化，决策 57）。
 *
 * **兼容层约定**：`name` 与 `args` 是 interface 的计算属性，让 sealed 化之前写下的
 * 大量读取点（`t.name`、`t.args`、`typeEq`、`baseOf`…）无需改动：
 *  - `NamedType`：返回真实名字与实参（与旧 `data class Type` 行为完全一致）
 *  - `FunType`：`name` = `"(fn)"`，`args` = params + ret
 *  - `TupleType`：`name` = `"(tuple)"`，`args` = items
 * 哨兵名含括号，不可能与用户标识符重名，因此按 name 查表的逻辑对函数/元组类型天然 miss，
 * 这正是期望行为（它们不是名义类型）。
 */
sealed interface Type {
    val name: String
    val args: List<Type>
    fun render(): String
    fun substT(sub: Map<String, Type>): Type
}

/** 名义类型 / 类型参数引用：`Nat`、`Optional<Nat>`、`T` */
data class NamedType(override val name: String, override val args: List<Type> = emptyList()) : Type {
    override fun render(): String =
        if (args.isEmpty()) name else "$name[" + args.joinToString(", ") { it.render() } + "]"
    override fun substT(sub: Map<String, Type>): Type =
        if (name in sub && args.isEmpty()) sub.getValue(name)
        else NamedType(name, args.map { it.substT(sub) })
}

/** 函数类型 `(A,B)=>C`（决策 57，v1 为 Kotlin 级：无理论参数、无前后件） */
data class FunType(val params: List<Type>, val ret: Type, val purity: String = "") : Type {
    override val name: String get() = "(fn)"
    override val args: List<Type> get() = params + ret
    override fun render(): String =
        (if (purity.isEmpty()) "" else "$purity ") +
            "(" + params.joinToString(", ") { it.render() } + ")=>" + ret.render()
    override fun substT(sub: Map<String, Type>): Type =
        FunType(params.map { it.substT(sub) }, ret.substT(sub), purity)
}

/** 元组类型 `(T,U)`；`EmptyTuple`/`SingleTuple<T>` 是具名形态，走 NamedType（决策 62） */
data class TupleType(val items: List<Type>) : Type {
    override val name: String get() = "(tuple)"
    override val args: List<Type> get() = items
    override fun render(): String = "(" + items.joinToString(", ") { it.render() } + ")"
    override fun substT(sub: Map<String, Type>): Type = TupleType(items.map { it.substT(sub) })
}

/**
 * 跨模块限定类型 `core.Point`（P0 模块系统）：`module` 为模块路径段（不含末段符号名），
 * `name` 是简单名（与 NamedType 同口径参与名字相等）。校验/解析由 checker 完成
 * （checkTypeResolvable 走模块树），`Symbols.expand` 把它折叠为同简单名的 NamedType。
 * 已知限制（P0 记录）：仅校验存在性与参数可解析性；"不同模块同名类型"会因折叠而合并为同一类型。
 */
data class QualifiedType(
    val module: List<String>,
    override val name: String,
    override val args: List<Type> = emptyList(),
) : Type {
    override fun render(): String {
        val base = module.joinToString(".") + "." + name
        return if (args.isEmpty()) base else "$base[" + args.joinToString(", ") { it.render() } + "]"
    }
    override fun substT(sub: Map<String, Type>): Type =
        if (name in sub && args.isEmpty()) sub.getValue(name)
        else QualifiedType(module, name, args.map { it.substT(sub) })
}

/** 构造具名类型的工厂（替代旧 `Type(name, args)` 的全部构造点） */
fun namedT(name: String, args: List<Type> = emptyList()): Type = NamedType(name, args)

// ---------- 语句 ----------

sealed interface Stmt : Node

data class VarStmt(
    val name: String,
    val annotations: List<String>,
    val type: Type?,
    val value: Expr,
    override val pos: String = "",
) : Stmt

data class AssignStmt(val target: Expr, val value: Expr, override val pos: String = "") : Stmt

/** `unchecked.axiom[q]` —— 命题逃逸（直接公理注入 Γ） */
data class AxiomStmt(val props: List<Expr>, override val pos: String = "") : Stmt

/**
 * `by [q₁, q₂]` —— 辅助策略（决策 48）。
 * 把命题注入当前作用域 Γ 供证明器使用；与 axiom 的区别：语义上标注"这是人给的中间步骤"，
 * 诊断走补充级且可被语言服务高亮为待补证明点。未来 `by` 复用作委托语法糖。
 */
data class ByStmt(val props: List<Expr>, override val pos: String = "") : Stmt

/** `unchecked <语句>` —— 纯度/可变性逃逸 */
data class UncheckedStmt(val inner: Stmt, override val pos: String = "") : Stmt

data class ExprStmt(val expr: Expr, override val pos: String = "") : Stmt

/**
 * `return e` / 裸 `return` / `return@label`（Kotlin 风格显式返回，v1.1 扩展标签）。
 * 具名函数体语句位：返回该函数；lambda 体内：返回当前 lambda（label 为 lambda 调用标签名）。
 */
data class ReturnStmt(val expr: Expr?, override val pos: String = "", val label: String? = null) : Stmt

// ---------- 表达式 ----------

sealed interface Expr : Node

/**
 * 语句流中是否出现 return（决策 75，checker 与 codegen 共用的展平判据）。
 * lambda/匿名函数体是独立值宇宙不计入——其中 return 由 checker 报 E-RETURN-OUTSIDE。
 */
fun exprHasStmtReturn(e: Expr): Boolean = when (e) {
    is BlockExpr -> e.stmts.any { stmtHasReturn(it) }
    is IfExpr -> exprHasStmtReturn(e.thenBlock) || (e.elseBlock?.let { exprHasStmtReturn(it) } ?: false)
    is LambdaExpr, is AnonFunExpr -> false
    else -> false
}

fun stmtHasReturn(s: Stmt): Boolean = when (s) {
    is ReturnStmt -> true
    is UncheckedStmt -> stmtHasReturn(s.inner)
    is ExprStmt -> exprHasStmtReturn(s.expr)
    else -> false
}

/**
 * 是否所有控制路径都以 return 收尾（决策 75，与 codegen 共用的发散判据）。
 * 只有全路径发散，if 才没有值语义、可按语句展平；
 * 混合形态（一边 return 一边出值）展平会吞掉值路径 → 仍按值位 IIFE 处理。
 */
fun diverges(e: Expr): Boolean = when (e) {
    is IfExpr -> e.elseBlock != null && diverges(e.thenBlock) && diverges(e.elseBlock)
    is BlockExpr -> e.stmts.lastOrNull()?.let { stmtDiverges(it) } ?: false
    else -> false
}

fun stmtDiverges(s: Stmt): Boolean = when (s) {
    is ReturnStmt -> true
    is UncheckedStmt -> stmtDiverges(s.inner)
    is ExprStmt -> diverges(s.expr)
    else -> false
}


data class IntLit(val value: String, override val pos: String = "") : Expr

/** P10（决策 86）：浮点字面量 `1.0`——类型 Rat（IEEE double via JS Number）；值保留原文给 codegen */
data class FloatLit(val value: String, override val pos: String = "") : Expr
data class StrLit(val value: String, override val pos: String = "") : Expr
data class NameRef(val name: String, override val pos: String = "") : Expr
data object ReturnSym : Expr                 // '$'

/** `$n` 参数引用（决策 65，T6）：指代第 n 个形参（1 起），仅在前件/后件/不变量中出现 */
data class ParamRefExpr(val index: Int, override val pos: String = "") : Expr
data object TopExpr : Expr                   // '⊤'
data object BotExpr : Expr                   // '⊥'

/** 两参解糖：`e1 ○ e2` 本质 `○<e1,e2>` 的中缀语法糖（M3 按命题原子处理） */
data class BinExpr(val op: String, val left: Expr, val right: Expr, override val pos: String = "") : Expr
data class UniExpr(val op: String, val operand: Expr, override val pos: String = "") : Expr

/**
 * v2.0 空安全运算符（决策 88-92）。
 * 不做 BinExpr 复用——语义层需特判（Optional 解构/包装、分支注入），独立节点更清晰。
 */

/** `a ?: b` 空替代：a 为 Some(x) → x；a 为 None → b。类型 = a 内层与 b 的 join */
data class ElvisExpr(val left: Expr, val right: Expr, override val pos: String = "") : Expr

/** `a?.f(b)` 安全调用（含中缀 `a ?.f b`，INFIX-7 解析层 desugar）：a 为 Some(x) → Some(x.f(b))；None → None */
data class SafeCallExpr(
    val target: Expr,
    val name: String,
    val args: List<Expr>,
    override val pos: String = "",
) : Expr

/** `a >: T` 安全转换：尝试把 a 转为 T（结构判定），成功 → Some(a)，失败 → None；不抛异常 */
data class CastExpr(val target: Expr, val type: Type, override val pos: String = "") : Expr

/** `a ? T` 运行时类型测：结构判定 a 是否为 T，返回 Bool；`if (a ? T)` 分支内智能转换 a : T */
data class TypeTestExpr(val target: Expr, val type: Type, override val pos: String = "") : Expr

/** `Task { ... }` 创建任务（TASK-1：仅创建不启动）。体是代码块（闭包语义，天然 @async）；类型 Task[T]。 */
data class TaskExpr(val body: BlockExpr, override val pos: String = "") : Expr

/** 函数调用；尾随 lambda 已并入 args */
data class CallExpr(
    val callee: Expr,
    val args: List<Expr>,
    val namedArgs: Map<String, Expr> = emptyMap(),   // P8（决策 84）：命名参数 `f(name = e)`——位置实参之外的命名通道
    override val pos: String = "",
) : Expr

/** 紧贴的 `<...>`：命题项 p<v>、显式供给 witness<torch>()、类型实例化——由语义层判定角色 */
data class InstExpr(val target: Expr, val terms: List<Expr>, override val pos: String = "") : Expr

data class FieldExpr(val target: Expr, val name: String, override val pos: String = "") : Expr

/** 比较符前缀调用形式 `<(1, 2)` */
data class SymbolCallExpr(val op: String, val args: List<Expr>, override val pos: String = "") : Expr

data class LambdaExpr(val params: List<String>, val body: Expr, override val pos: String = "") : Expr

/** 元组字面量 `(a,b)`；`(a)` 是括号表达式不产出本节点，`()` 不合法（决策 62） */
data class TupleExpr(val items: List<Expr>, override val pos: String = "") : Expr

/** 命名字段构造 `A(name = "test", age = 3)`（O3，决策 69）：按字段名给值，缺省字段用声明默认值 */
data class StructCtorExpr(val struct: String, val assigns: List<Pair<String, Expr>>, override val pos: String = "") : Expr

/** `e by [q]` —— 辅助策略后缀式：表达式带一条人给命题 */
data class ByExpr(val target: Expr, val props: List<Expr>, override val pos: String = "") : Expr

/** var 声明即表达式（决策 A3）：值为初值，`var n = var m = 0` 合法 */
data class VarExpr(
    val name: String,
    val annotations: List<String>,
    val type: Type?,
    val value: Expr,
    override val pos: String = "",
) : Expr

/**
 * 匿名函数表达式（指导§52-56，决策 A3）：`fun _[T](a:T):T = a` 或省略式 `fun[T](a:T):T = a`。
 * name 为 `_` 或空串，不可被后续引用（不入符号表）。其类型即函数类型 `[T](T)=>T`。
 */
data class AnonFunExpr(
    val name: String,
    val theory: List<TheoryParam>,
    val params: List<Param>,
    val preps: List<Expr>,
    val retType: Type?,
    val posts: List<Expr>,
    val body: Expr?,
    override val pos: String = "",
) : Expr

data class BlockExpr(val stmts: List<Stmt>, override val pos: String = "") : Expr {
    val tailExpr: Expr? get() = (stmts.lastOrNull() as? ExprStmt)?.expr
}

data class IfExpr(
    val cond: Expr,
    val thenBlock: BlockExpr,
    val elseBlock: BlockExpr?,
    override val pos: String = "",
) : Expr

// ---------- 模式匹配（决策 29/46：when = 真模式匹配 + ADT 解构） ----------

/**
 * when 表达式。主题可为普通表达式或绑定式 `when(var e = 0)`（决策 33，Kotlin 风格）。
 * 分支顺序敏感：后分支的 Γ 含前面所有分支命题的否定（指导§66）。
 */
data class WhenExpr(
    val subject: Expr,
    val subjectBinding: String?,
    val arms: List<WhenArm>,
    override val pos: String = "",
) : Expr

data class WhenArm(
    val pattern: Pattern,
    val guard: Expr?,
    val body: Expr,
    override val pos: String = "",
) : Node

sealed interface Pattern { val pos: String get() = "" }

/** 字面量/构造子名等值模式：`1`、`"s"`、`true` */
data class PatLit(val value: Expr, override val pos: String = "") : Pattern

/** 构造子解构：`Some(x)`、嵌套 `Some(Some(x))`；无参时 args 为空 */
data class PatCtor(val name: String, val args: List<Pattern>, override val pos: String = "") : Pattern

/** 元组模式 `(p1, p2)`（决策 61/62，T3） */
data class PatTuple(val items: List<Pattern>, override val pos: String = "") : Pattern

/** 类型模式：`is Nat`；可选绑定 `is Nat as x` */
data class PatIs(val type: Type, val bind: String?, override val pos: String = "") : Pattern

/** 通配 `_`（不可引用）与具名绑定 `x` */
data class PatBind(val name: String, override val pos: String = "") : Pattern

/** `else` 兜底分支：恒真 */
data class PatElse(override val pos: String = "") : Pattern

/** q ∈ {∀,∃,∃!}（ascii 别名已在解析时归一为 Unicode） */
data class QuantExpr(val q: String, val names: List<String>, val body: Expr, override val pos: String = "") : Expr
