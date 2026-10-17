package sugared.functor.checker

import sugared.functor.ast.*
import sugared.functor.parser.parseSource
import kotlin.test.Test
import kotlin.test.assertTrue

class RealTest {
    private fun errs(src: String): List<String> =
        checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.ERROR }.map { it.code }

    private fun ok(src: String): Boolean = errs(src).isEmpty()

    private fun allDiags(src: String): String =
        checkFile(parseSource(src, "t")).diags.joinToString(" | ") { it.render() }

    @Test
    fun `Real sqrt2 自乘等于 2`() {
        val src = "@impure fun main() { ∴ sqrt(2) * sqrt(2) = 2 }"
        assertTrue(ok(src), "sqrt(2)*sqrt(2)=2 应可证, 诊断: " + allDiags(src))
    }

    @Test
    fun `Real 分数加法精确`() {
        val src = "@impure fun main() { ∴ (1 / 3) + (1 / 6) = (1 / 2) }"
        assertTrue(ok(src), "1/3+1/6=1/2 应可证, 诊断: " + allDiags(src))
    }

    @Test
    fun `Real sqrt8 等于 2sqrt2`() {
        val src = "@impure fun main() { ∴ sqrt(8) = (2 * sqrt(2)) }"
        assertTrue(ok(src), "sqrt(8)=2*sqrt(2) 应可证, 诊断: " + allDiags(src))
    }

    @Test
    fun `Real toReal 调用合法`() {
        val src = "@impure fun main() { var x: Real = toReal(1.5) }"
        assertTrue(ok(src), "toReal(1.5) 应合法, 诊断: " + allDiags(src))
    }

    @Test
    fun `Real 类型自反等式`() {
        val src = "@impure fun main() { ∴ sqrt(4) = 2 }"
        assertTrue(ok(src), "sqrt(4)=2 应可证, 诊断: " + allDiags(src))
    }
}
