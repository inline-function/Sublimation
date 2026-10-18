package sugared.functor.annotationproc

import sugared.functor.ast.BlockExpr
import sugared.functor.ast.Decl
import sugared.functor.ast.DeclEntry
import sugared.functor.ast.Entry
import sugared.functor.ast.FileAst
import sugared.functor.ast.FunDecl
import sugared.functor.ast.ReturnStmt
import sugared.functor.ast.Stmt
import sugared.functor.checker.Diag
import sugared.functor.checker.Severity
import sugared.functor.parser.ParseFailure
import sugared.functor.parser.parseDeclSnippet
import sugared.functor.parser.parseFunBodySnippet
import sugared.functor.parser.parseStmtsSnippet

/**
 * 处理器脚本发出的**结构性 AST 编辑**指令（root doc/注解处理器.md：处理器是 AST→AST 变换）。
 *
 * 与 [AnnotationEdit]（注解增删）并行：脚本只累积指令，由 [applyAstEdits] 在处理器收尾时
 * 把源码片段**解析并嫁接**回 AST（保持不可变——data class copy 重建）。
 *
 * 指令形态（脚本侧宿主函数）：
 * - setFunBody(name, src)：函数体整体替换（块体或表达式体）
 * - prependStmts(name, [src…])：语句插到函数块体最前（表达式体先脱糖成 return 块）
 * - addDecl(src)：追加一条新声明（fun/struct/enum/class/impl/type）
 * - replaceDecl(name, src)：把名为 name 的声明整体替换
 *
 * 收敛契约：指令须幂等或用注解自守（见 wiki 注解处理器页）——引擎按 AST 是否变化判定停机，
 * 结构性编辑在重查（recheck）里整体过 Checker，处理器产出的代码不能绕过语言设计。
 */
internal sealed interface AstEdit {

    /** 把函数 [name] 的体替换为解析 [src] 得到的块/表达式。 */
    data class SetFunBody(val name: String, val src: String) : AstEdit

    /** 把语句片段（多条源码拼接）插到函数 [name] 的块体最前。 */
    data class PrependStmts(val name: String, val stmts: List<String>) : AstEdit

    /** 在文件末尾追加一条解析自 [src] 的声明。 */
    data class AddDecl(val src: String) : AstEdit

    /** 把名为 [name] 的声明整体替换为解析自 [src] 的新声明。 */
    data class ReplaceDecl(val name: String, val src: String) : AstEdit
}

/**
 * 把结构性编辑不可变地作用到 AST（源码片段在此时解析——脚本运行期不触碰 AST）。
 *
 * - 片段解析失败 → E-PROCESSOR-EDIT（错误级：处理器源码本身坏了），该条编辑被丢弃，其余继续；
 * - setFunBody / prependStmts / replaceDecl 的目标声明不存在 → W-PROCESSOR-EDIT（与注解编辑同码）；
 * - prependStmts 遇无体函数（@host 声明等）同样按目标不存在处理；
 * - 无任何编辑实际作用时**原样返回**（引用不变——引擎据此判定本轮无变化而停机）。
 *
 * 应用顺序：先结构性编辑、后注解编辑（见 [ScriptProcessor]），两者按声明名匹配互不干扰。
 */
internal fun applyAstEdits(
    ast: FileAst,
    edits: List<AstEdit>,
    diags: MutableList<Diag> = mutableListOf(),
): FileAst {
    if (edits.isEmpty()) return ast

    // ---------- 阶段一：解析全部片段 ----------
    val bodies = HashMap<AstEdit.SetFunBody, sugared.functor.ast.Expr>()
    val stmts = HashMap<AstEdit.PrependStmts, List<Stmt>>()
    val decls = HashMap<AstEdit, Decl>()
    val parsedOk = HashSet<AstEdit>()

    for (ed in edits) {
        try {
            when (ed) {
                is AstEdit.SetFunBody -> bodies[ed] = parseFunBodySnippet(ed.src, "<processor:setFunBody:${ed.name}>")
                is AstEdit.PrependStmts -> stmts[ed] =
                    if (ed.stmts.isEmpty()) emptyList() else parseStmtsSnippet(ed.stmts.joinToString("\n"), "<processor:prepend:${ed.name}>")
                is AstEdit.AddDecl -> decls[ed] = parseDeclSnippet(ed.src, "<processor:addDecl>")
                is AstEdit.ReplaceDecl -> decls[ed] = parseDeclSnippet(ed.src, "<processor:replaceDecl:${ed.name}>")
            }
            parsedOk += ed
        } catch (ex: ParseFailure) {
            diags += Diag(Severity.ERROR, "E-PROCESSOR-EDIT", "",
                "处理器结构性编辑的源码片段解析失败（编辑被丢弃）：" + ex.message)
        }
    }
    if (parsedOk.isEmpty()) return ast

    // ---------- 阶段二：嫁接 ----------
    val applied = HashSet<AstEdit>()
    val out = ArrayList<Entry>(ast.entries.size)
    var changed = false

    for (e in ast.entries) {
        var entry = e
        val decl = (entry as? DeclEntry)?.decl
        if (decl != null) {
            // replaceDecl：整体替换（原位；ImplDecl 无名，不参与按名替换）
            val dname = declNameOf(decl)
            if (dname != null) {
                val rep = edits.filterIsInstance<AstEdit.ReplaceDecl>()
                    .firstOrNull { it in parsedOk && it.name == dname }
                if (rep != null) {
                    applied += rep
                    decls[rep]?.let { entry = DeclEntry(it); changed = true }
                }
            }
            // setFunBody / prependStmts：作用于当前 entry 的函数声明
            val cur = (entry as? DeclEntry)?.decl as? FunDecl
            if (cur != null && cur.name.isNotEmpty()) {
                for (ed in edits) {
                    if (ed !in parsedOk || applied.contains(ed)) continue
                    when (ed) {
                        is AstEdit.SetFunBody -> {
                            if (ed.name == cur.name) {
                                applied += ed
                                entry = DeclEntry(cur.copy(body = bodies[ed])); changed = true
                            }
                        }
                        is AstEdit.PrependStmts -> {
                            if (ed.name == cur.name && ed.stmts.isNotEmpty()) {
                                val prep = stmts[ed]
                                if (prep.isNullOrEmpty()) continue
                                val nb = when (val b = cur.body) {
                                    is BlockExpr -> BlockExpr(prep + b.stmts)
                                    null -> null   // 无体声明（@host 等）：无法插入
                                    else -> BlockExpr(prep + listOf(ReturnStmt(b)))   // 表达式体脱糖
                                }
                                if (nb != null) {
                                    applied += ed
                                    entry = DeclEntry(cur.copy(body = nb)); changed = true
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
        out += entry
    }

    // addDecl：追加到文件末尾（幂等：同名声明已存在则跳过；无名 impl 按深度相等跳过；同批多次 add 同名也去重）
    for (ed in edits.filterIsInstance<AstEdit.AddDecl>()) {
        if (ed !in parsedOk) continue
        applied += ed
        val nd = decls[ed] ?: continue
        val nname = declNameOf(nd)
        val dup = if (nname != null) out.any { (it as? DeclEntry)?.decl?.let { d -> declNameOf(d) } == nname }
            else nd is sugared.functor.ast.ImplDecl &&
                out.any { (it as? DeclEntry)?.decl == nd }
        if (!dup) {
            out += DeclEntry(nd)
            changed = true
        }
    }

    // 未命中（目标声明不存在）→ 警告
    for (ed in edits) {
        if (ed !in parsedOk || ed in applied) continue
        when (ed) {
            is AstEdit.SetFunBody, is AstEdit.PrependStmts, is AstEdit.ReplaceDecl ->
                diags += Diag(Severity.WARN, "W-PROCESSOR-EDIT", "",
                    "处理器编辑未命中：目标声明 " + edTargetName(ed) + " 不存在")
            is AstEdit.AddDecl -> {}
        }
    }

    return if (changed) FileAst(out) else ast
}

private fun edTargetName(ed: AstEdit): String = when (ed) {
    is AstEdit.SetFunBody -> ed.name
    is AstEdit.PrependStmts -> ed.name
    is AstEdit.ReplaceDecl -> ed.name
    is AstEdit.AddDecl -> ""
}

/** 声明的（按名匹配的）名字；ImplDecl 无名字段（按 trait/self 匹配），返回 null。 */
private fun declNameOf(d: Decl): String? = when (d) {
    is sugared.functor.ast.FunDecl -> d.name
    is sugared.functor.ast.StructDecl -> d.name
    is sugared.functor.ast.EnumDecl -> d.name
    is sugared.functor.ast.ClassDecl -> d.name
    is sugared.functor.ast.TypeAliasDecl -> d.name
    is sugared.functor.ast.PropDefDecl -> d.name
    is sugared.functor.ast.ImplDecl -> null
}
