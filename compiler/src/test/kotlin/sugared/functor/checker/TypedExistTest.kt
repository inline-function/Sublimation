package sugared.functor.checker

import sugared.functor.parser.parseSource
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/** 有类型命题的存在量词构造（wiki/上下文系统·存在引入）：`∃ x = 1 . x : Nat`。
 *  见证 t 的类型归属 `x : T` 作为性质的一部分——替换实例 `t : T` 走类型判定通道；
 *  与其它命题合取时由合取引入通道（证 A ∧ B ⇔ 证 A 且证 B）分解。
 *  wiki 原载「存在语句的见证部分不支持类型断言混在性质里」即缺该通道，现已解除。 */
class TypedExistTest {
    private fun errs(src: String): List<String> =
        checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.ERROR }.map { it.code }
    private fun ok(src: String): Boolean = errs(src).isEmpty()

    @Test
    fun `类型断言性质应可构造`() {
        assertTrue(ok("fun f(): Null {\n  ∃ x = 1 . x : Nat\n}\nfun main() { f() }"))
    }

    @Test
    fun `字面量见证类型不符应被拒`() {
        // 见证 \"s\" 配 Nat 性质：替换后 \"s\" : Nat 不可证 → 健全性
        assertTrue(errs("fun f(): Null {\n  ∃ x = \"s\" . x : Nat\n}\nfun main() { f() }").contains("E-QUANT-UNPROVEN"))
    }

    @Test
    fun `参数作见证时其类型归属可构造`() {
        assertTrue(ok("fun f(n: Nat): Null {\n  ∃ x = n . x : Nat\n}\nfun main() { f(1) }"))
    }

    @Test
    fun `类型断言与数值命题合取`() {
        assertTrue(ok("fun f(): Null {\n  ∃ x = 1 . (x : Nat) ∧ (x > 0)\n}\nfun main() { f() }"))
    }

    @Test
    fun `类型断言与等式命题合取`() {
        assertTrue(ok("fun f(n: Nat): Null {\n  ∃ x = n . (x : Nat) ∧ (x >= 0)\n}\nfun main() { f(3) }"))
    }

    @Test
    fun `合取中类型成分不成立应整体被拒`() {
        // (x : Str) 不成立 → 整个存在引入失败（合取引入不放宽任一成分）
        assertTrue(errs("fun f(): Null {\n  ∃ x = 1 . (x : Str) ∧ (x > 0)\n}\nfun main() { f() }").contains("E-QUANT-UNPROVEN"))
    }

    @Test
    fun `合取右支失败应整体被拒`() {
        // (1 = 1) 真但 (1 > 5) 假：合取引入保留严格性
        assertTrue(errs("fun f(): Null {\n  ∴ (1 = 1) ∧ (1 > 5)\n}\nfun main() { f() }").contains("E-CANNOT-PROVE"))
    }

    @Test
    fun `裸 BTS 合取目标可证`() {
        // 通用证明器补全：合取引入同时修复了普通 ∴ 的合取目标
        assertTrue(ok("fun f(): Null {\n  ∴ (1 = 1) ∧ (2 > 1)\n}\nfun main() { f() }"))
    }

    @Test
    fun `嵌套与三路合取`() {
        assertTrue(ok("fun f(): Null {\n  ∃ x = 3 . ((x : Nat) ∧ (x > 1)) ∧ (x < 10)\n}\nfun main() { f() }"))
    }

    @Test
    fun `wiki 原等式例保持可用`() {
        // 回归保护：纯表达式性质的行为不变
        assertTrue(ok("fun f(): Null {\n  ∃ x = 1 . x = 1\n}\nfun main() { f() }"))
    }
}