package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.interpreter.Annotation

/**
 * 注解扫描器：遍历 [FileAst]（含嵌套语句/表达式/模式），收集每个带注解的节点。
 *
 * - [scan] 返回扁平化的 [AnnotatedNode] 列表（一个注解一条，保持源码顺序）；
 * - [annotationsOf] / [annotationsOfNode] 是「注解查询」入口，把节点上的注解升格为
 *   [Annotation]（参数当前恒为空，见 [AnnotatedNode] 的说明）。
 *
 * 覆盖的「可挂注解」节点（Ast.kt 里带 annotations 字段者）：
 * FunDecl、StructDecl、ClassDecl、VarStmt、VarExpr。Param 的注解（@tuple/@mut 等）不在此
 * 独立展开——它不实现 Node，且其注解主要面向 checker；处理器需要时可通过符号表拿完整声明。
 * EnumDecl/ImplDecl/TypeAliasDecl/PropDefDecl 无注解字段，天然扫不到。
 */
object AnnotationScanner {

    /** 节点上注解的原始名字列表（String）；不可挂注解的节点返回空序列 */
    fun rawAnnotationNames(node: Node): List<String> = when (node) {
        is FunDecl -> node.annotations
        is StructDecl -> node.annotations
        is ClassDecl -> node.annotations
        is VarStmt -> node.annotations
        is VarExpr -> node.annotations
        else -> emptyList()
    }

    /** 把节点上的 List<String> 注解升格为通用 Annotation（参数恒空，理由见 AnnotatedNode） */
    fun annotationsOfNode(node: Node): List<Annotation> =
        rawAnnotationNames(node).map { name -> Annotation(name) }

    /** 注解查询：给定声明，返回它的注解列表（delete 无注解字段的声明返回空） */
    fun annotationsOf(decl: Decl): List<Annotation> = annotationsOfNode(decl)

    /** 扫描单个文件：返回所有带注解的节点（一条注解一条 AnnotatedNode） */
    fun scan(file: FileAst): List<AnnotatedNode> {
        val out = ArrayList<AnnotatedNode>()
        for (entry in file.entries) {
            when (entry) {
                is DeclEntry -> scanDecl(entry.decl, file, out)
                is StmtEntry -> scanStmt(entry.stmt, file, out)
            }
        }
        return out
    }

    /** 扫描多个文件（目录即模块下的多文件），按文件合并、保留顺序 */
    fun scan(files: List<FileAst>): List<AnnotatedNode> {
        val out = ArrayList<AnnotatedNode>()
        for (f in files) {
            for (entry in f.entries) {
                when (entry) {
                    is DeclEntry -> scanDecl(entry.decl, f, out)
                    is StmtEntry -> scanStmt(entry.stmt, f, out)
                }
            }
        }
        return out
    }

    // ============ 声明 ============

    private fun scanDecl(decl: Decl, file: FileAst, out: MutableList<AnnotatedNode>) {
        emitIfAnnotated(decl, file, out)
        when (decl) {
            is FunDecl -> decl.body?.let { walkExpr(it, file, out) }
            is ClassDecl -> decl.members.forEach { m -> m.body?.let { walkExpr(it, file, out) } }
            is ImplDecl -> decl.members.forEach { m -> m.body?.let { walkExpr(it, file, out) } }
            else -> {}
        }
    }

    // ============ 语句 ============

    private fun scanStmt(stmt: Stmt, file: FileAst, out: MutableList<AnnotatedNode>) {
        emitIfAnnotated(stmt, file, out)
        when (stmt) {
            is VarStmt -> walkExpr(stmt.value, file, out)
            is AssignStmt -> {
                walkExpr(stmt.target, file, out)
                walkExpr(stmt.value, file, out)
            }
            is ExprStmt -> walkExpr(stmt.expr, file, out)
            is UncheckedStmt -> scanStmt(stmt.inner, file, out)
            is FunStmt -> scanDecl(stmt.fn, file, out)
            is ReturnStmt -> stmt.expr?.let { walkExpr(it, file, out) }
            is AxiomStmt -> stmt.props.forEach { walkExpr(it, file, out) }
            is ByStmt -> stmt.props.forEach { walkExpr(it, file, out) }
            is BTSStmt -> { walkExpr(stmt.form, file, out); stmt.premises.forEach { walkExpr(it, file, out) }; walkExpr(stmt.conclusion, file, out) }
            is WhenPropStmt -> { walkExpr(stmt.disjunction, file, out); stmt.arms.forEach { a -> walkExpr(a.condition, file, out); a.body.forEach { scanStmt(it, file, out) } } }
            is StrategyStmt -> walkExpr(stmt.goal, file, out)
            is IfPropStmt -> {
                stmt.thenStmts.forEach { scanStmt(it, file, out) }
                stmt.elseStmts.forEach { scanStmt(it, file, out) }
            }
            is ExistIntroStmt -> { stmt.witnesses.forEach { walkExpr(it, file, out) }; walkExpr(stmt.prop, file, out) }
            is ExistsElimStmt -> {
                walkExpr(stmt.prop, file, out)
                stmt.body.forEach { scanStmt(it, file, out) }
            }
        }
    }

    // ============ 表达式（递归下钻，收集 VarExpr 注解） ============

    private fun walkExpr(expr: Expr, file: FileAst, out: MutableList<AnnotatedNode>) {
        emitIfAnnotated(expr, file, out)
        when (expr) {
            is BinExpr -> { walkExpr(expr.left, file, out); walkExpr(expr.right, file, out) }
            is UniExpr -> walkExpr(expr.operand, file, out)
            is CallExpr -> {
                walkExpr(expr.callee, file, out)
                expr.args.forEach { walkExpr(it, file, out) }
                expr.namedArgs.values.forEach { walkExpr(it, file, out) }
            }
            is InstExpr -> { walkExpr(expr.target, file, out); expr.terms.forEach { walkExpr(it, file, out) } }
            is FieldExpr -> walkExpr(expr.target, file, out)
            is FunRefExpr -> walkExpr(expr.target, file, out)
            is SymbolCallExpr -> expr.args.forEach { walkExpr(it, file, out) }
            is LambdaExpr -> walkExpr(expr.body, file, out)
            is TupleExpr -> expr.items.forEach { walkExpr(it, file, out) }
            is StructCtorExpr -> expr.assigns.forEach { walkExpr(it.second, file, out) }
            is ByExpr -> { walkExpr(expr.target, file, out); expr.props.forEach { walkExpr(it, file, out) } }
            is VarExpr -> walkExpr(expr.value, file, out)
            is AnonFunExpr -> expr.body?.let { walkExpr(it, file, out) }
            is BlockExpr -> expr.stmts.forEach { scanStmt(it, file, out) }
            is IfExpr -> {
                walkExpr(expr.cond, file, out)
                walkExpr(expr.thenBlock, file, out)
                expr.elseBlock?.let { walkExpr(it, file, out) }
            }
            is WhenExpr -> {
                expr.subject?.let { walkExpr(it, file, out) }
                expr.arms.forEach { arm ->
                    walkPattern(arm.pattern, file, out)
                    arm.guard?.let { walkExpr(it, file, out) }
                    arm.cond?.let { walkExpr(it, file, out) }
                    walkExpr(arm.body, file, out)
                }
            }
            is TypeTestExpr -> walkExpr(expr.target, file, out)
        is IsPatExpr -> walkExpr(expr.target, file, out)   // ifis 哨兵（正常经 parseIf 脱糖为 when，此处保守遍历）
            is TaskExpr -> walkExpr(expr.body, file, out)
            is FlowExpr -> walkExpr(expr.body, file, out)
            is QuantExpr -> walkExpr(expr.body, file, out)
            else -> {}   // 字面量/名字/命题原子/返回符号等叶子，无子节点可扫
        }
    }

    // ============ 模式 ============

    private fun walkPattern(pattern: Pattern, file: FileAst, out: MutableList<AnnotatedNode>) {
        when (pattern) {
            is PatLit -> walkExpr(pattern.value, file, out)
            is PatCtor -> pattern.args.forEach { walkPattern(it, file, out) }
            is PatTuple -> pattern.items.forEach { walkPattern(it, file, out) }
            else -> {}
        }
    }

    // ============ 发射 ============

    private fun emitIfAnnotated(node: Node, file: FileAst, out: MutableList<AnnotatedNode>) {
        rawAnnotationNames(node).forEach { name ->
            out += AnnotatedNode(Annotation(name), node, kindOf(node), file)
        }
    }

    private fun kindOf(node: Node): NodeKind = when (node) {
        is FunDecl -> NodeKind.FUN
        is StructDecl -> NodeKind.STRUCT
        is ClassDecl -> NodeKind.CLASS
        is EnumDecl -> NodeKind.ENUM
        is ImplDecl -> NodeKind.IMPL
        is TypeAliasDecl -> NodeKind.TYPE_ALIAS
        is PropDefDecl -> NodeKind.PROP_DEF
        is VarStmt -> NodeKind.VAR_STMT
        is VarExpr -> NodeKind.VAR_EXPR
        else -> NodeKind.UNKNOWN
    }
}
