package sugared.functor.lexer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LexerTest {
    private fun toks(s: String) = Lexer(s, "t.subl").tokenize()

    @Test
    fun `紧贴尖括号无空格标记`() {
        val ts = toks("witness<torch>()")
        assertEquals(Kind.IDENT, ts[0].kind)
        assertEquals(Kind.LT, ts[1].kind)
        assertFalse(ts[1].precededBySpace)
        assertEquals(Kind.GT, ts[3].kind)
        assertFalse(ts[3].precededBySpace)
    }

    @Test
    fun `带空格尖括号标记为比较`() {
        val ts = toks("a < b")
        assertTrue(ts[1].precededBySpace)
    }

    @Test
    fun `符号名成为 SYMBOL 标识符`() {
        val ts = toks("fun ○(a: Bool): Bool")
        assertEquals(Kind.SYMBOL, ts[1].kind)
        assertEquals("○", ts[1].text)
    }

    @Test
    fun `逻辑符单 token`() {
        val ts = toks("∀ ∃! ∧ ∨ ¬ → ↔ ⊤ ⊥")
        assertEquals(
            listOf(Kind.FORALL, Kind.EXISTS1, Kind.LAND, Kind.LOR, Kind.LNOT,
                Kind.IMPLIES, Kind.EQUIV, Kind.TOP, Kind.BOT),
            ts.dropLast(1).map { it.kind },
        )
    }

    @Test
    fun `字符串转义`() {
        val ts = toks("\"a\\nb\"")
        assertEquals(Kind.STR, ts[0].kind)
        assertEquals("a\nb", ts[0].text)
    }

    @Test
    fun `注释后行号`() {
        val ts = toks("// x\n1")
        assertEquals(2, ts[0].line)
    }

    @Test
    fun `非法字符报错带行列`() {
        val e = assertFailsWith<LexFailure> { toks("中") }
        assertEquals(1, e.line)
    }
}
