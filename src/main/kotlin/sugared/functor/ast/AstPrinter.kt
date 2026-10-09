package sugared.functor.ast

import sugared.functor.lexer.Token

/** 单 token 的表格行，供词法 dump 与测试断言复用 */
fun tokenLine(t: Token): String =
    "${t.kind}\t${escapeText(t.text)}\t${t.line}:${t.col}\tspace=${t.precededBySpace}"

private fun escapeText(s: String): String = when (s) {
    "\n" -> "\\n"; "\t" -> "\\t"; "\r" -> "\\r"
    else -> s
}

data class Tree(val label: String, val children: List<Tree> = emptyList())

class AstPrinter {
    companion object {
        fun toTreeString(file: FileAst): String = buildString { render(file.toTree(), 0) }

        private fun StringBuilder.render(n: Tree, depth: Int) {
            repeat(depth) { append("  ") }
            append(n.label)
            n.children.forEach { append("\n"); render(it, depth + 1) }
        }
    }
}

fun FileAst.toTree(): Tree = Tree("File", entries.map { it.toTree() })

private fun Entry.toTree(): Tree = when (this) {
    is DeclEntry -> decl.toTree()
    is StmtEntry -> Tree("stmt", listOf(stmt.toTree()))
}

private fun Decl.toTree(): Tree = when (this) {
    is FunDecl -> Tree(
        "fun $name${theoryTag(theory)}(${params.joinToString(", ") { it.toTag() }})" +
            "${angleTag(preps)}${retType?.let { ": ${it.toTag()}" } ?: ""}${angleTag(posts)}" +
            annTag(annotations),
        listOfNotNull(body?.toTree()),
    )
    is ClassDecl -> Tree("class $name${theoryTag(theory)}", members.map { it.toTree() })
    is ImplDecl -> Tree("impl ${trait.toTag()} for ${self.toTag()}", members.map { it.toTree() })
    is StructDecl -> Tree("struct $name${theoryTag(theory)}(${fields.joinToString(", ") { it.toTag() }})")
    is EnumDecl -> Tree(
        "enum $name${theoryTag(theory)}",
        ctors.map { Tree("${it.name}(${it.fields.joinToString(", ") { t -> t.toTag() }})") },
    )
    is TypeAliasDecl -> Tree("type $name${theoryTag(theory)} = ${target.toTag()}")
}

private fun theoryTag(ts: List<TheoryParam>): String =
    if (ts.isEmpty()) "" else ts.joinToString(", ", "[", "]") {
        when (it) {
            is TypeParam -> if (it.constraint == null) it.name else "${it.name}:${it.constraint}"
            is PropParam -> "@${it.name}"
        }
    }

private fun annTag(anns: List<String>): String =
    if (anns.isEmpty()) "" else " " + anns.joinToString(" ") { "@$it" }

private fun angleTag(xs: List<Expr>): String =
    if (xs.isEmpty()) "" else xs.joinToString(", ", "<", ">") { it.toTag() }

private fun Param.toTag(): String = "$name: ${type.toTag()}"

private fun Type.toTag(): String =
    if (args.isEmpty()) name else "$name[" + args.joinToString(", ") { it.toTag() } + "]"

private fun Stmt.toTree(): Tree = when (this) {
    is VarStmt -> Tree("var $name${annTag(annotations)} = ${value.toTag()}")
    is AssignStmt -> Tree("assign", listOf(target.toTree(), value.toTree()))
    is AxiomStmt -> Tree("axiom[${props.joinToString(", ") { it.toTag() }}]")
    is ByStmt -> Tree("by[${props.joinToString(", ") { it.toTag() }}]")
    is UncheckedStmt -> Tree("unchecked", listOf(inner.toTree()))
    is ReturnStmt -> Tree("return", listOfNotNull(expr?.toTree()))
    is ExprStmt -> Tree("expr", listOf(expr.toTree()))
}

private fun Expr.toTree(): Tree = when (this) {
    is IntLit -> Tree("int $value")
    is FloatLit -> Tree("float $value")   // P10（决策 86）
    is StrLit -> Tree("str \"$value\"")
    is NameRef -> Tree("name $name")
    ReturnSym -> Tree("$")
    is ParamRefExpr -> Tree("\$${index}")
    TopExpr -> Tree("⊤")
    BotExpr -> Tree("⊥")
    is BinExpr -> Tree("bin ${op}", listOf(left.toTree(), right.toTree()))
    is UniExpr -> Tree("un ${op}", listOf(operand.toTree()))
    is ElvisExpr -> Tree("elvis", listOf(left.toTree(), right.toTree()))
    is SafeCallExpr -> Tree("safecall .${name}", listOf(target.toTree()) + args.map { it.toTree() })
    is CastExpr -> Tree("cast >: ${type.toTag()}", listOf(target.toTree()))
    is TypeTestExpr -> Tree("typetest ? ${type.toTag()}", listOf(target.toTree()))
    is TaskExpr -> Tree("task", body.stmts.map { it.toTree() })
    is CallExpr -> Tree("call ${callee.toTag()}(${args.joinToString(", ") { it.toTag() }})")
    is InstExpr -> Tree("inst ${target.toTag()}<${terms.joinToString(", ") { it.toTag() }}>")
    is FieldExpr -> Tree("field ${target.toTag()}.$name")
    is SymbolCallExpr -> Tree("sym ${op}(${args.joinToString(", ") { it.toTag() }})")
    is LambdaExpr -> Tree("lambda(${params.joinToString(", ")}) => ${body.toTag()}")
    is BlockExpr -> Tree("block", stmts.map { it.toTree() })
    is IfExpr -> Tree("if", listOf(cond.toTree(), Tree("then", thenBlock.stmts.map { it.toTree() })) +
        (elseBlock?.let { listOf(Tree("else", it.stmts.map { s -> s.toTree() })) } ?: emptyList()))
    is ByExpr -> Tree("by ${target.toTag()}[${props.joinToString(", ") { it.toTag() }}]")
    is WhenExpr -> Tree(
        "when ${subjectBinding?.let { "(var $it = ${subject.toTag()})" } ?: subject.toTag()}",
        arms.map { Tree("arm ${it.pattern.toTag()}${it.guard?.let { g -> " if ${g.toTag()}" } ?: ""} -> ${it.body.toTag()}") },
    )
    is TupleExpr -> Tree("tuple", items.map { it.toTree() })
    is StructCtorExpr -> Tree("ctor $struct", assigns.map { (n, v) -> Tree("$n = ${v.toTag()}") })
    is QuantExpr -> Tree("$q ${names.joinToString(", ")} .", listOf(body.toTree()))
    is VarExpr -> Tree("var-expr ${annTag(annotations)}$name${type?.let { ": ${it.toTag()}" } ?: ""}", listOf(value.toTree()))
    is AnonFunExpr -> Tree(
        "fun-expr $name${theoryTag(theory)}(${params.joinToString(", ") { it.toTag() }})" +
            "${angleTag(preps)}${retType?.let { ": ${it.toTag()}" } ?: ""}${angleTag(posts)}",
        listOfNotNull(body?.toTree()),
    )
}

private fun Expr.toTag(): String = when (this) {
    is IntLit -> value
    is FloatLit -> value
    is StrLit -> "\"$value\""
    is NameRef -> name
    ReturnSym -> "$"
    is ParamRefExpr -> "\$${index}"
    TopExpr -> "⊤"
    BotExpr -> "⊥"
    is BinExpr -> "($op ${left.toTag()} ${right.toTag()})"
    is UniExpr -> "($op ${operand.toTag()})"
    is ElvisExpr -> "(${left.toTag()} ?: ${right.toTag()})"
    is SafeCallExpr -> "${target.toTag()}?.${name}(${args.joinToString(", ") { it.toTag() }})"
    is CastExpr -> "(${target.toTag()} >: ${type.toTag()})"
    is TypeTestExpr -> "(${target.toTag()} ? ${type.toTag()})"
    is TaskExpr -> "Task {${body.stmts.joinToString("; ") { it.toPlain() }}}"
    is CallExpr -> "${callee.toTag()}(${args.joinToString(", ") { it.toTag() }})"
    is InstExpr -> "${target.toTag()}<${terms.joinToString(", ") { it.toTag() }}>"
    is FieldExpr -> "${target.toTag()}.$name"
    is SymbolCallExpr -> "$op(${args.joinToString(", ") { it.toTag() }})"
    is LambdaExpr -> "\\(${params.joinToString(", ")}) => ${body.toTag()}"
    is BlockExpr -> "{${stmts.joinToString("; ") { it.toPlain() }}}"
    is IfExpr -> "if ${cond.toTag()} {${thenBlock.stmts.joinToString("; ") { it.toPlain() }}}" +
        (elseBlock?.let { " else {${it.stmts.joinToString("; ") { s -> s.toPlain() }}}" } ?: "")
    is ByExpr -> "(${target.toTag()} by [${props.joinToString(", ") { it.toTag() }}])"
    is WhenExpr -> "when " + (subjectBinding?.let { "(var $it = ${subject.toTag()})" } ?: subject.toTag()) + " {" +
        arms.joinToString("; ") { "${it.pattern.toTag()}${it.guard?.let { g -> " if ${g.toTag()}" } ?: ""} -> ${it.body.toTag()}" } + "}"
    is TupleExpr -> "(" + items.joinToString(", ") { it.toTag() } + ")"
    is StructCtorExpr -> "$struct(" + assigns.joinToString(", ") { (n, v) -> "$n = ${v.toTag()}" } + ")"
    is QuantExpr -> "$q ${names.joinToString(", ")} . ${body.toTag()}"
    is VarExpr -> "var $name = ${value.toTag()}"
    is AnonFunExpr -> "fun $name(${params.joinToString(", ") { it.toTag() }})" +
        (retType?.let { ": ${it.toTag()}" } ?: "") + (body?.let { " = ${it.toTag()}" } ?: "")
}

private fun Stmt.toPlain(): String = when (this) {
    is VarStmt -> "var $name = ${value.toTag()}"
    is AssignStmt -> "${target.toTag()} = ${value.toTag()}"
    is AxiomStmt -> "axiom[${props.joinToString(", ") { it.toTag() }}]"
    is ByStmt -> "by[${props.joinToString(", ") { it.toTag() }}]"
    is UncheckedStmt -> "unchecked ${inner.toPlain()}"
    is ReturnStmt -> if (expr != null) "return ${expr.toTag()}" else "return"
    is ExprStmt -> expr.toTag()
}

private fun Pattern.toTag(): String = when (this) {
    is PatElse -> "else"
    is PatBind -> name
    is PatLit -> value.toTag()
    is PatIs -> "is ${type.toTag()}" + (bind?.let { " as $it" } ?: "")
    is PatCtor -> if (args.isEmpty()) name else "$name(${args.joinToString(", ") { it.toTag() }})"
    is PatTuple -> "(" + items.joinToString(", ") { it.toTag() } + ")"
}
