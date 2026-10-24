package sugared.functor.checker

import sugared.functor.ast.*
import sugared.functor.parser.parseSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.io.File

/**
 * 激进模式（idea doc/激进模式.md，第一波核心六件）测试。
 *
 * 每条都**双面断言**：激进模式证出，普通模式证不出（`--prover=aggressive` 之外行为完全不变，§11 其四）。
 * 不测「猜」——所有通过项必须真实可证；健全性底线（§3.1）由反面用例（未挂 @classic 时反证不启用）看守。
 */
class AggressiveModeTest {
    private fun errs(src: String, aggressive: Boolean): List<String> =
        checkFile(parseSource(src, "t"), aggressive = aggressive).diags
            .filter { it.severity == Severity.ERROR }.map { it.code }

    private fun okAgg(src: String) = errs(src, true).isEmpty()

    // ==================== 1. 饱和层：不动点判停 + 迭代加深（§5.1） ====================

    /** `a ∨ b` 与 `¬a`：分情况后 a 支矛盾爆炸、b 支直接命中 ⇒ b。
     *  普通模式无 ∨ 消去——b 不是任何蕴含的后件，回溯也够不着。 */
    @Test
    fun `析取三段论_消去析取支证出`() {
        val src = "fun disjSyl[@a, @b]<a ∨ b, ¬a>: Null {\n  ∴ b\n}\n"
        val dbg = checkFile(parseSource(src, "t"), aggressive = true).diags.joinToString("; ") { "${it.severity} ${it.code} ${it.message}" }
        assertTrue(okAgg(src), "激进模式应消去析取支证出 b，实际: " + dbg)
        assertFalse(errs(src, false).isEmpty(), "普通模式无 ∨ 消去，证不出")
    }

    // ==================== 2. 排中律前向实例化 + ∨ 消去（§5.3 item 6） ====================

    /** Γ 只有 p->q 与 ¬p->q：注入 `p ∨ ¬p` 后分情况，两支都出 q（需 @classic 挂载）。 */
    @Test
    fun `排中律_前向实例化后分情况证出`() {
        val src = "@theorem fun LEM[@p]: Null <p ∨ ¬p>\n" +
            "@classic\n" +
            "fun lemCase[@p, @q]<p → q, ¬p → q>: Null {\n  ∴ q\n}\n"
        assertTrue(okAgg(src), "激进模式应注入排中律并分情况证出 q")
        assertFalse(errs(src, false).isEmpty(), "普通模式无前向实例化，证不出")
    }

    // ==================== 3. 反证法（§5.3，需 @classic） ====================

    /** Γ 含 ¬p->q 与 ¬p->¬q：假设 ¬p 导出矛盾（q 与 ¬q）⇒ p。 */
    @Test
    fun `反证法_经典逻辑下证出`() {
        val src = "@classic\nfun raaCase[@p, @q]<¬p → q, ¬p → ¬q>: Null {\n  ∴ p\n}\n"
        assertTrue(okAgg(src), "激进模式应反证 p")
        assertFalse(errs(src, false).isEmpty(), "普通模式无反证通道")
    }

    /** 健全性看守（§3.1）：不挂 @classic 时反证**绝不**启用——直觉主义只给 ¬¬p，猜不得。 */
    @Test
    fun `反证法_未挂classic不启用`() {
        val src = "fun raaCase[@p, @q]<¬p → q, ¬p → ¬q>: Null {\n  ∴ p\n}\n"
        assertFalse(errs(src, true).isEmpty(), "未挂 core.classic 时反证不得启用")
    }

    // ==================== 4. 三维池的命题维 ∀ 实例化（§4.6） ====================

    /** `∀p. p ∨ ¬p` 的 p 是命题变量：按命题池候选 r 实例化出 `r ∨ ¬r`。
     *  普通模式的 closure 只有表达式维（PForall 无 ground 项）→ 实例化不出来。 */
    @Test
    fun `命题维_全称命题按命题池实例化`() {
        val src = "@classic\nfun propInst[r]: Null {\n  axiom ∀p. p ∨ ¬p\n  ∴ r ∨ ¬r\n}\n"
        assertTrue(okAgg(src), "激进模式应把 ∀p 的 p 分派到命题维并实例化出 r ∨ ¬r")
        assertFalse(errs(src, false).isEmpty(), "普通模式一维池实例化不出命题变量")
    }

    // ==================== 5. 维度分派单元（§4.6） ====================

    @Test
    fun `维度分派_类型位置走类型池`() {
        val body = PAtom(":", listOf(NameRef("z"), NameRef("T")))
        val dims = dimsOf("T", body)
        assertTrue(sugared.functor.checker.PoolDim.TYPE in dims, "T 出现在 ':' 事实右端 → 类型维")
        assertFalse(PoolDim.PROP in dims, "T 不是裸原子 → 不走命题维")
    }

    @Test
    fun `维度分派_裸原子走命题池`() {
        val body = POr(PAtom("p", emptyList()), PNot(PAtom("p", emptyList())))
        val dims = dimsOf("p", body)
        assertTrue(PoolDim.PROP in dims, "p 是 0 元裸原子 → 命题维")
    }

    // ==================== 6. 中断与诊断报告（§8） ====================

    /** Ctrl-C 语义的内核：abort → checkAbort 抛中断；报告含卡住目标与 Γ。 */
    @Test
    fun `中断_抛异常并写出诊断报告`() {
        val s = ProverSession()
        s.enter(PAtom("p", emptyList()), listOf(PAtom("q", emptyList()), PAtom("r", emptyList())))
        s.abort()
        var threw = false
        try { s.checkAbort() } catch (e: ProverInterrupted) { threw = true }
        assertTrue(threw, "abort 后 checkAbort 应抛 ProverInterrupted")
        val tmp = File.createTempFile("dsh-agg", ".md")
        s.writeReport(tmp.path)
        val txt = tmp.readText()
        assertTrue(txt.contains("激进模式诊断报告"), "报告应有标题")
        assertTrue(txt.contains("p"), "报告应列出卡住的目标 p")
        assertTrue(txt.contains("q"), "报告应列出 Γ 的事实")
        tmp.delete()
        s.exit(false)
    }
}
