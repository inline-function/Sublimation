package sugared.functor.parser

import sugared.functor.ast.BlockExpr
import sugared.functor.ast.FunDecl
import sugared.functor.ast.IntLit
import sugared.functor.ast.ReturnStmt
import sugared.functor.ast.StrLit
import sugared.functor.ast.StructDecl
import sugared.functor.ast.VarStmt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 片段解析（root doc/注解处理器.md：处理器以源码片段编辑 AST）：
 * parseDeclSnippet / parseFunBodySnippet / parseStmtsSnippet 三入口与「片段须耗尽」契约。
 */
class ParserSnippetTest {

    @Test
    fun 声明片段_函数() {
        val d = parseDeclSnippet("fun f(x: Nat): Nat = x * 2", "<t>")
        assertIs<FunDecl>(d)
        assertEquals("f", d.name)
    }

    @Test
    fun 声明片段_结构体() {
        val d = parseDeclSnippet("struct P(x: Nat)", "<t>")
        assertIs<StructDecl>(d)
        assertEquals("P", d.name)
    }

    @Test
    fun 声明片段_多余内容拒绝() {
 assertFailsWith<ParseFailure> { parseDeclSnippet("fun a(): Nat = 1 fun b(): Nat = 2", "<t>") }
    }

    @Test
    fun 声明片段_非法声明拒绝() {
        assertFailsWith<ParseFailure> { parseDeclSnippet("var x = 1", "<t>") }
    }

    @Test
    fun 函数体片段_块体() {
        val b = parseFunBodySnippet("{ print(toStr(1)) }", "<t>")
        assertIs<BlockExpr>(b)
        assertEquals(1, b.stmts.size)
    }

    @Test
    fun 函数体片段_裸表达式() {
        assertIs<IntLit>(parseFunBodySnippet("42", "<t>"))
    }

    @Test
    fun 函数体片段_带等号的表达式() {
        assertIs<StrLit>(parseFunBodySnippet("= \"s\"", "<t>"))
    }

    @Test
    fun 函数体片段_等号后接花括号禁止() {
        assertFailsWith<ParseFailure> { parseFunBodySnippet("= { print(1) }", "<t>") }
    }

    @Test
    fun 函数体片段_多余内容拒绝() {
        assertFailsWith<ParseFailure> { parseFunBodySnippet("1 2", "<t>") }
    }

    @Test
    fun 语句片段_多条与分号() {
        val ss = parseStmtsSnippet("var a = 1\nvar b = 2; var c = 3", "<t>")
        assertEquals(3, ss.size)
        assertTrue(ss.all { it is VarStmt })
    }

    @Test
    fun 语句片段_空片段() {
        assertEquals(emptyList(), parseStmtsSnippet("", "<t>"))
    }

    @Test
    fun 语句片段_含return语句() {
        val ss = parseStmtsSnippet("print(toStr(1))\nreturn", "<t>")
        assertIs<ReturnStmt>(ss.last())
    }
}
