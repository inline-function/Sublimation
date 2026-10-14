package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.checker.*
import sugared.functor.interpreter.Annotation

/**
 * 注解处理器接口（对齐 root doc/注解处理器.md 3.1）。
 *
 * 每个处理器是 Ast -> Ast 的变换函数，链式执行（按注册顺序）。
 * 处理器不直接改符号表——符号表由 [ProcessorEngine] 在 AST 变化后通过重新检查重建。
 */
interface AnnotationProcessor {
    /** 处理器名字——用于诊断和注册 */
    val name: String
    /** 关注哪些注解——编译器据此决定是否调用这个处理器（优化：无关注注解则跳过） */
    val interestedAnnotations: Set<String>
    /** 处理入口 */
    fun process(input: ProcessorInput): ProcessorOutput
}

/**
 * 只读符号表查询接口（对齐 6.3）。
 *
 * 处理器通过它查询已登记的声明，不能写符号表。
 * 要增加新声明 → 通过 AST 变换（在 ProcessorOutput.ast 里加声明），由重新检查登记。
 */
interface SymbolsView {
    fun findFunction(name: String): FunDecl?
    fun findStruct(name: String): StructDecl?
    fun findEnum(name: String): EnumDecl?
    fun findTypeClass(name: String): ClassDecl?
    fun findAlias(name: String): TypeAliasDecl?

    fun allFunctions(): List<FunDecl>
    fun allStructs(): List<StructDecl>
    fun allEnums(): List<EnumDecl>
    fun allTypeClasses(): List<ClassDecl>

    /** trait 名字 -> 已实现的 impl 条目列表 */
    fun implsOfTrait(traitName: String): List<ImplEntry>
}

internal class SymbolsViewImpl(private val symbols: Symbols) : SymbolsView {
    override fun findFunction(name: String): FunDecl? = symbols.findFun(name)
    override fun findStruct(name: String): StructDecl? = symbols.structs[name]
    override fun findEnum(name: String): EnumDecl? = symbols.enums[name]
    override fun findTypeClass(name: String): ClassDecl? = symbols.classes[name]
    override fun findAlias(name: String): TypeAliasDecl? = symbols.aliases[name]

    override fun allFunctions(): List<FunDecl> = symbols.funs.values.toList()
    override fun allStructs(): List<StructDecl> = symbols.structs.values.toList()
    override fun allEnums(): List<EnumDecl> = symbols.enums.values.toList()
    override fun allTypeClasses(): List<ClassDecl> = symbols.classes.values.toList()
    override fun implsOfTrait(traitName: String): List<ImplEntry> =
        symbols.implsByTrait[traitName]?.toList() ?: emptyList()
}

internal object EmptySymbolsView : SymbolsView {
    override fun findFunction(name: String): FunDecl? = null
    override fun findStruct(name: String): StructDecl? = null
    override fun findEnum(name: String): EnumDecl? = null
    override fun findTypeClass(name: String): ClassDecl? = null
    override fun findAlias(name: String): TypeAliasDecl? = null
    override fun allFunctions(): List<FunDecl> = emptyList()
    override fun allStructs(): List<StructDecl> = emptyList()
    override fun allEnums(): List<EnumDecl> = emptyList()
    override fun allTypeClasses(): List<ClassDecl> = emptyList()
    override fun implsOfTrait(traitName: String): List<ImplEntry> = emptyList()
}

// ============================================================================
// 处理器输入/输出（对齐 3.1/7）
// ============================================================================

interface ProcessorInput {
    /** 当前 AST（只读引用） */
    val ast: FileAst
    /** 符号表（只读查询） */
    val symbols: SymbolsView
    /** 本次扫描收集的所有带注解的节点 */
    val annotated: List<AnnotatedNode>

    /**
     * 类型查询：给定表达式节点，返回它的类型，或 null 表示无类型信息。
     *
     * V1 限制：类型信息存于 Checker 的弥散上下文，不在 AST 节点上，因此 typeOf
     * 在大部分表达式上返回 null。仅当宿主提供了类型查询闭包（如从 Checker 捕获结果时）
     * 才会有非 null 结果。处理器应把 typeOf 视为尽力查询，不应依赖它做关键分支。
     */
    fun typeOf(expr: Expr): Type?

    /** 注解查询：给定声明，返回注解列表 */
    fun annotationsOf(decl: Decl): List<Annotation>

    /**
     * 生成代码发射器：处理器可以把需要写入生成文件的片段通过它累积，
     * 供宿主侧 [AnnotationProcessorHost.writeGenerated] 在脚本调用时写出。
     *
     * 注意：这不是 AST 变换的替代。AST 变换仍应通过 [ProcessorOutput.ast] 返回。
     */
    fun emit(source: String)
}

interface ProcessorOutput {
    /** 变换后的新 AST */
    val ast: FileAst
    /** 处理器新产生的诊断 */
    val diagnostics: List<Diag>
}

/** [ProcessorInput] 的默认实现 */
class ProcessorContext(
    override val ast: FileAst,
    symbols: Symbols?,
    override val annotated: List<AnnotatedNode>,
    private val typeQuery: (Expr) -> Type? = { null },
    private val emitFn: (String) -> Unit = {},
) : ProcessorInput {
    override val symbols: SymbolsView = symbols?.let(::SymbolsViewImpl) ?: EmptySymbolsView
    override fun typeOf(expr: Expr): Type? = typeQuery(expr)
    override fun annotationsOf(decl: Decl): List<Annotation> = AnnotationScanner.annotationsOf(decl)
    override fun emit(source: String) = emitFn(source)
}

/** [ProcessorOutput] 的默认实现 */
data class ProcessorResult(
    override val ast: FileAst,
    override val diagnostics: List<Diag> = emptyList(),
) : ProcessorOutput {
    companion object {
        /** 快捷构造：AST 不变，仅报诊断 */
        fun unchanged(ast: FileAst): ProcessorResult = ProcessorResult(ast)
    }
}
