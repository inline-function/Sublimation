package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.interpreter.Annotation

/**
 * 注解挂载点：一条「注解 + 被挂载的 AST 节点」。
 *
 * 这是注解处理器的基本输入粒度——扫描器遍历 [FileAst]，对每个「可挂注解」的节点，
 * 按 node.annotations 里出现的每个名字生成一条 [AnnotatedNode]（注解 → 节点一一拆分）。
 *
 * 说明（AST 改动取舍）：现有 AST 节点的注解字段是 List<String>（见 Ast.kt 的
 * FunDecl/ClassDecl/StructDecl/VarStmt/VarExpr/Param.annotations），解析器 parseAnnotations()
 * 只读 @IDENT 名字、不读实参。为避免动这 30 处左右构造点与全链路（checker/codegen/测试）的
 * 巨大影响面，本接口**不改 AST**，改用 interpreter.Annotation(name, args) 作为统一容器：
 * 扫描时把 List<String> 升格为 Annotation(name, args = emptyList())。今后若解析器支持
 * @Anno(常量/类型) 实参，只需在 [AnnotationScanner] 的转换点补实参，AST 不变量继续成立。
 */
data class AnnotatedNode(
    /** 该挂载点上的注解 */
    val annotation: Annotation,
    /** 被挂载的 AST 节点（只读引用，处理器的变换通过返回新 FileAst 完成，不改它） */
    val node: Node,
    /** 节点分类，供处理器按种类过滤 */
    val kind: NodeKind,
    /** 来源文件（多文件扫描时定位；单文件合并处理时为 null） */
    val file: FileAst? = null,
) {
    /** 节点名（函数/结构体/变量名等；无名节点返回空串） */
    val nodeName: String get() = nameOf(node)

    /** 源位置（"line:col"；无位置为空串） */
    val pos: String get() = node.pos

    companion object {
        /** 从节点提取人类可读的名字（诊断/处理器过滤用） */
        fun nameOf(n: Node): String = when (n) {
            is FunDecl -> n.name
            is StructDecl -> n.name
            is ClassDecl -> n.name
            is EnumDecl -> n.name
            is TypeAliasDecl -> n.name
            is PropDefDecl -> n.name
            is VarStmt -> n.name
            is VarExpr -> n.name
            is ImplDecl -> n.self.render()
            else -> ""
        }
    }
}

/** 被注解节点的种类（对齐 Ast.kt 的节点类型，供处理器按类别分发） */
enum class NodeKind {
    /** 自由函数 / 类方法 / 定理函数签名 */
    FUN,
    /** 结构体声明 */
    STRUCT,
    /** 类型类声明 */
    CLASS,
    /** 枚举声明 */
    ENUM,
    /** impl 声明 */
    IMPL,
    /** 类型别名 */
    TYPE_ALIAS,
    /** 自定义命题 def */
    PROP_DEF,
    /** 语句位 var 声明 */
    VAR_STMT,
    /** 表达式位 var 声明 */
    VAR_EXPR,
    /** 其他 / 未分类（含新增节点类型未登记时的兜底） */
    UNKNOWN,
}
