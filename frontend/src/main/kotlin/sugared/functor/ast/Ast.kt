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
    val constraints: List<Expr> = emptyList(),   // v3.0：`fun f<score <= 100>` 约束表（依赖类型方向；V1 仅解析登记）
    override val pos: String = "",
) : Decl

/** class ≈ Rust trait；成员为签名级 FunDecl（body=null）或带默认实现
 *  v2.1 子类型类（对齐 Rust 子特质）：`class Sub[T] : Base[T]`，parents 为父类型类列表。
 *  语义：impl Sub for X 时要求父类也实现于 X（E-NO-INSTANCE）；父类字典因此恒可用。 */
data class ClassDecl(
    val name: String,
    val theory: List<TheoryParam>,
    /** root doc《副作用与可变性控制》：@mut 类型类——允许声明可变（@mut）函数 */
    val annotations: List<String> = emptyList(),
    val members: List<FunDecl>,
    val parents: List<Type> = emptyList(),
    override val pos: String = "",
) : Decl

data class ImplDecl(
    /** trait 为 null = 单纯方法添加（无类型类，承担重载职责）：Parser 阶段成员即脱糖为带 self 首参的自由函数重载 */
    val trait: Type?,
    val self: Type,
    val members: List<FunDecl>,
    /** v3.0 特性 2：`impl C for T by field` 委托——转发字段的方法到 C 的所有方法 */
    val delegate: String? = null,
    override val pos: String = "",
) : Decl

data class StructDecl(
    val name: String,
    val theory: List<TheoryParam>,
    val fields: List<Param>,
    /** root doc：@mut struct 允许声明可变（@mut）字段 */
    val annotations: List<String> = emptyList(),
    /** v3.0 特性 1：结构体伴生命题 `struct Test(n: Nat) <n ≠ 0>`（构造时证明，参数位自动注入） */
    val props: List<Expr> = emptyList(),
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

/** 自定义命题（根文档）：`def p<a, b>`——定义原子谓词（参数不约束类型）；`def p<a,b> ↔ <等价式>`——等价定义。
 *  equiv 为 null 时 p 是原子谓词（须 Γ 中有事实才真）；非 null 时目标按定义展开证明。 */
data class PropDefDecl(
    val name: String,
    val params: List<String>,
    val equiv: Expr?,
    override val pos: String = "",
) : Decl

data class Ctor(val name: String, val fields: List<Type>)

sealed interface TheoryParam

/** `[T]` 或 `[T: Show]` —— 类型参数，可带类型类约束。
 *  高阶类型（远期宪法第二条，主人拍板）：`[F[_]]` → arity=1，`[F[_, _]]` → arity=2；
 *  arity 是类型构造子的**实参个数**（kind `*→…→*` 的广度），0 = 普通类型变量。 */
data class TypeParam(val name: String, val constraint: String?, val arity: Int = 0) : TheoryParam

/** `[@p]` —— 命题参数（方括号里放命题的名字/占位） */
data class PropParam(val name: String) : TheoryParam

/** 形参可带注解（如 @list 变长实参，Kotlin vararg 语义，实参打包成 List）；默认空保证既有构造点零改动。
 *  default（O3，决策 69）：仅 struct 字段允许，函数形参带默认值报错。 */

/** 全局类型推导（idea doc/全局类型推导.md）：脚本模式省略标注时的**占位类型**。
 *  仅 allowUntyped 的脚本解析器填入（编译模式解析器仍要求 `: Type`）；
 *  解释器的 GlobalInference 在 Checker 前替换为真实类型（Any 兜底），Checker 永不遇见它。 */
val InferMarker: Type = NamedType("<infer>")

/** 是否为推导占位类型 */
fun Type.isInferMarker(): Boolean = this is NamedType && name == "<infer>" && args.isEmpty()

data class Param(
    val name: String,
    val type: Type,
    val annotations: List<String> = emptyList(),
    val default: Expr? = null,
    /** P4（v2.1）：元组参数解构 `fun f((a, b): (Nat, Str))`——name 为占位，函数体内按 destruct 分量绑定 */
    val destruct: List<String>? = null,
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
data class FunType(
    val params: List<Type>,
    val ret: Type,
    val purity: String = "",
    // v3.0（完美函数类型）：理论参数（多态函数类型 `[T](T)=>T`）。theory 里的名字是**绑定者**——
    // substT 时被屏蔽（α 安全）；类型比对（typeEq/unifyInto/typeLooseEq）V1 忽略 theory 位（宽松，后续收紧）。
    val theory: List<TypeParam> = emptyList(),
    // 函数类型携带上下文（根文档·上下文系统）：`(T)<$1 ∈ xs> => Null`。
    // context 是命题表达式列表（解析自 <>），为 null 表示无上下文。
    val context: List<Expr>? = null,
) : Type {
    override val name: String get() = "(fn)"
    override val args: List<Type> get() = params + ret
    override fun render(): String =
        (if (purity.isEmpty()) "" else "$purity ") +
            (if (theory.isEmpty()) "" else "[" + theory.joinToString(", ") { it.name } + "]") +
            "(" + params.joinToString(", ") { it.render() } + ")" +
            (context?.let { "<" + it.joinToString("; ") { e -> e.toString() } + ">" } ?: "") +
            "=>" + ret.render()
    override fun substT(sub: Map<String, Type>): Type {
        val bound = theory.map { it.name }.toSet()
        val inner = if (bound.isEmpty()) sub else sub.filterKeys { it !in bound }
        return FunType(params.map { it.substT(inner) }, ret.substT(inner), purity, theory, context)
    }
}

/** 元组类型 `(T,U)`；`EmptyTuple`/`SingleTuple<T>` 是具名形态，走 NamedType（决策 62） */
data class TupleType(val items: List<Type>) : Type {
    override val name: String get() = "(tuple)"
    override val args: List<Type> get() = items
    override fun render(): String = "(" + items.joinToString(", ") { it.render() } + ")"
    override fun substT(sub: Map<String, Type>): Type = TupleType(items.map { it.substT(sub) })
}

/** MutType: @mut T 可变视图类型（root doc《副作用与可变性控制》54-61 行）。
 *  M-1：@mut T 与 T 不是子类型关系，是同一结构的两个视图；M-2：@mut T 到 T 隐式（转换点=拷贝点）。 */
data class MutType(val inner: Type) : Type {
    override val name: String get() = "(mut)"
    override val args: List<Type> get() = listOf(inner)
    override fun render(): String = "@mut " + inner.render()
    override fun substT(sub: Map<String, Type>): Type = MutType(inner.substT(sub))
}

/** AtomicType: @atomic T 原子类型（root doc《异步》）：对该类型的读写视为原子操作。
 *  因编译目标是 JS 单线程，原型值/容器读写天然原子，原子性由事件循环保证——该类型主要作为
 *  编译期声明，允许并发异步上下文安全读写；运行时无需额外包装，透明反射到内层类型。 */
data class AtomicType(val inner: Type) : Type {
    override val name: String get() = "(atomic)"
    override val args: List<Type> get() = listOf(inner)
    override fun render(): String = "@atomic " + inner.render()
    override fun substT(sub: Map<String, Type>): Type = AtomicType(inner.substT(sub))
}

data class UnionType(val variants: List<Type>) : Type {
    override val name: String get() = "(union)"
    override val args: List<Type> get() = variants
    override fun render(): String = variants.joinToString(" | ") { it.render() }
    override fun substT(sub: Map<String, Type>): Type = UnionType(variants.map { it.substT(sub) })
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
    val destruct: List<String>? = null,
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

// ---------- 根文档《手动证明》—— 因为-所以 / 非真即假 / 分类讨论 / 策略 ----------

/** 因为-所以语句（根文档《手动证明》）：
 *  `∵ form, p₁, p₂ ∴ conclusion`——
 *  form 是定理的全称形式（如 ∀a b.(a: Rat ∧ b: Rat)→...）；
 *  premises 是前提（自动证明验证）；conclusion 是结论（须与定理后件合一
 *  且与前提同义）；编译器从定理空间中匹配定理后注入 conclusion 到 Γ。 */
data class BTSStmt(
    val form: Expr,
    val premises: List<Expr>,
    val conclusion: Expr,
    override val pos: String = "",
) : Stmt

/** 分类讨论语句（根文档《手动证明》）：
 *  `when[p ∨ q] { p -> { ... ∴ r } q -> { ... ∴ r } }`——
 *  对析取式做 case 分析，每个分支假设一个析取支成立并证明相同结论 r；
 *  所有分支的公共结论注入 Γ。 */
data class WhenPropStmt(
    val disjunction: Expr,
    val arms: List<WhenPropArm>,
    override val pos: String = "",
) : Stmt

data class WhenPropArm(
    val condition: Expr,
    val body: List<Stmt>,
    override val pos: String = "",
) : Node

/** 策略调用（根文档《手动证明》）：
 *  `@simp[(3*2+6)=12]`——调用命名策略证明目标命题；
 *  strategy 即 @x 里的 x（如 "simp"），goal 是方括号里的命题。 */
data class StrategyStmt(
    val strategy: String,
    val goal: Expr,
    override val pos: String = "",
) : Stmt

// ---------- v3.0 特性 6：if[p] 排中律（语句位，纯证明构造，零运行时代码） ----------

// ---------- v3.0 特性 7：量词（∃引入/∃消去/∀引入=局部函数） ----------

/** `∃ x = t . P(x)` —— ∃ 引入：替换 x→t 后证明 P(t)，成功注入 `∃ x. P(x)` 到 Γ */
/** `∃ x₁ = t₁, x₂ = t₂, … . P(x₁, x₂, …)` —— ∃ 引入（多见证）：替换 xᵢ→tᵢ 后证明 P(t₁, t₂, …)，成功注入 `∃ x₁, x₂, … . P` 到 Γ */
data class ExistIntroStmt(val names: List<String>, val witnesses: List<Expr>, val prop: Expr, override val pos: String = "") : Stmt

/** `∃ x . P(x) { ∴ q… }` —— ∃ 消去：前提 ∃x. P(x) 在 Γ（否则 E-PROP-UNBOUND），块内新帧注入 x 与 P(x)，块外 Γ += 结论 */
data class ExistsElimStmt(val name: String, val prop: Expr, val body: List<Stmt>, override val pos: String = "") : Stmt

/** 块内局部函数定义（∀ 引入：定义即证明）——登记为当前帧局部定理，participates in ∴ 搜索；V1 不生成 JS 值代码 */
data class FunStmt(val fn: FunDecl, override val pos: String = "") : Stmt

/** `if[p] { ∴ q… } else { ∴ q… }` —— 两支分别以 p / ¬p 入 Γ 证明相同结论集，块外 Γ += 结论 */
data class IfPropStmt(
    val prop: Expr,
    val thenStmts: List<Stmt>,
    val elseStmts: List<Stmt>,
    override val pos: String = "",
) : Stmt

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
    // 尾 when 全臂发散且有兜底（主题式 PatElse / 无主题式 cond==null）才判发散——与 IfExpr 的 else 要求对齐（M4）。
    is WhenExpr -> e.arms.isNotEmpty() && e.arms.all { it.guard == null && diverges(it.body) } &&
        e.arms.any { if (e.subject != null) it.pattern is PatElse else it.cond == null }
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

/** `a is T` 运行时类型测：结构判定 a 是否为 T，返回 Bool；`if (a is T)` 分支内智能转换 a : T */
data class TypeTestExpr(val target: Expr, val type: Type, override val pos: String = "") : Expr

/** `a is Some(e)` 模式条件哨兵（parse 期独有）：仅诞生于 if 条件位，parseIf 立即脱糖为
 *  `when (a) { is Some(e) -> …; else -> … }`（复用 when 全套模式匹配：枚举/结构体/元组/嵌套）。
 *  正常 AST 里不应出现——checker/codegen 对它的分派是防御性报错。 */
data class IsPatExpr(val target: Expr, val pat: Pattern, override val pos: String = "") : Expr

/** `Task { ... }` 创建任务（TASK-1：仅创建不启动）。体是代码块（闭包语义，天然 @async）；类型 Task[T]。 */
data class TaskExpr(val body: BlockExpr, override val pos: String = "") : Expr

/** FlowExpr: `Flow { ... }` 惰性冷流（root doc 异步），类型 Flow[T]。 */
data class FlowExpr(val body: BlockExpr, override val pos: String = "") : Expr

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

/** P6（决策 95）：函数引用 `o::f`（类 Kotlin 方法引用）——不加括号传递函数值 */
data class FunRefExpr(val target: Expr, val name: String, override val pos: String = "") : Expr

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

/** 线性作用域 `%{ … }`（boring doc《线性作用域 · 形式化版》第十二卷 §3.1 产生式 LinearBlock → '%' BlockExpr）。
 *  语义：域内 @mut 实体的等式事实按版本化注入 Γ（LV-DECL/LV-ASSIGN），使可变变量可证明；
 *  `%` 是纯编译期分析标记，代码生成时擦除（§10.1 LV-ERASE-BLOCK：产物与普通块逐字节相同）。 */
data class LinearBlockExpr(val block: BlockExpr, override val pos: String = "") : Expr {
    val tailExpr: Expr? get() = block.tailExpr
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
    val subject: Expr?,   // v3.0（无主题 when）：null = `when { cond -> .. }` 布尔条件分支
    val subjectBinding: String?,
    val arms: List<WhenArm>,
    override val pos: String = "",
) : Expr

data class WhenArm(
    val pattern: Pattern,
    val guard: Expr?,
    val body: Expr,
    override val pos: String = "",
    val cond: Expr? = null,   // v3.0（无主题 when）：`when { cond -> .. }` 的条件表达式（pattern 用 PatCond 哨兵）
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

/** v3.0（无主题 when）：条件分支的占位模式——pattern 不参与匹配，见 WhenArm.cond */
data object PatCond : Pattern

/** q ∈ {∀,∃,∃!}（ascii 别名已在解析时归一为 Unicode） */
data class QuantExpr(val q: String, val names: List<String>, val body: Expr, override val pos: String = "") : Expr
