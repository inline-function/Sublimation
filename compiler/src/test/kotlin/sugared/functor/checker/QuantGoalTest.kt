package sugared.functor.checker

import sugared.functor.parser.parseSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** when[p] 分类讨论语句 + 否定等式常量归约 回归测试（此前无覆盖，被审计误判为死产生生）。 */
class QuantGoalTest {
    private fun bag(src: String) = checkFile(parseSource(src, "t"))
    private fun errs(src: String): List<String> =
        bag(src).diags.filter { it.severity == Severity.ERROR }.map { it.code }

    // #1：when[p ∨ q] { p => … q => … } —— 分类讨论语句解析与命题检查（每支体 ∴ 该支前提）
    @Test
    fun a_whenProp分类讨论() {
        val src = "fun f(): Null {\n  unchecked.axiom[p ∨ q]\n  when[p ∨ q] { p => { ∴ p } q => { ∴ q } }\n}"
        assertEquals(emptyList(), errs(src), "when[p ∨ q] 分类讨论应可解析且两支皆可证")
    }

    @Test
    fun b_whenProp析取反例() {
        // 只供给 q：支体 ∴ p 无 p 支撑应报 E-CANNOT-PROVE ——
        // 确认语句真的进了命题检查，不是静默吞掉（D-BTS 留痕 + 诊断码）
        val bag = checkFile(parseSource("fun f(): Null {\n  unchecked.axiom[q]\n  when[p ∨ q] { p => { ∴ p } q => { ∴ q } }\n}", "t"))
        val codes = bag.diags.map { it.code }
        val msgs = bag.diags.joinToString(" | ") { it.code + " " + it.message.take(70) }
        assertTrue(codes.contains("E-CANNOT-PROVE") || codes.contains("W-PROP-UNBOUND") || codes.contains("E-PROP-UNBOUND"),
            "无 p 支撑应有 E/W 级诊断（证不出或析取前提不成立），实际: $msgs")
    }

    // #3：否定等式常量归约——var x = 1 后 ∴ x ≠ 0
    @Test
    fun c_否定等式常量归约() {
        val src = "fun f(): Null { var x = 1 ∴ x ≠ 0 }"
        assertEquals(emptyList(), errs(src), "var x = 1 后应能证 x ≠ 0（Γ 等式替换 + 常量判定）")
    }

    @Test
    fun d_否定等式不可证() {
        val src = "fun f(): Null { var x = 0 ∴ x ≠ 0 }"
        assertTrue(errs(src).isNotEmpty(), "x = 0 时证 x ≠ 0 应失败（抗误报回归）")
    }
}
