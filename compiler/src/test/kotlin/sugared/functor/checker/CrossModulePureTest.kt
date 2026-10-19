package sugared.functor.checker

import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** 跨模块 pure<f> 事实注册（L9）：可见模块的非纯哈数不再需要 unchecked 逃逸；同名 impure 定义仍拦住。 */
class CrossModulePureTest {
    private fun checkTree(spec: Map<String, String>): DiagBag {
        val dir = Files.createTempDirectory("subl-xmp").toFile()
        try {
            for ((rel, content) in spec) {
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
            val mm = MultiModule(dir)
            return mm.checkAll()
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun a_跨模块纯函数无需unchecked() {
        val bag = checkTree(mapOf(
            "lib/a.subl" to "@impure fun log(n: Nat): Nat = 0\nfun helper(n: Nat): Nat = n + 1",
            "app/app.settings" to "mount lib",
            "app/main.subl" to "fun g(): Nat = helper(5)"
        ))
        val codes = bag.diags.map { it.code }
        assertFalse(codes.contains("E-IMPURE-CALL"), "跨模块纯函数 helper 调用不应报 E-IMPURE-CALL，实际: $codes")
    }

    @Test
    fun b_跨模块impure仍须unchecked() {
        val bag = checkTree(mapOf(
            "lib/a.subl" to "@impure fun sink(n: Nat): Nat = 0",
            "app/app.settings" to "mount lib",
            "app/main.subl" to "fun g(): Nat = sink(5)"
        ))
        val codes = bag.diags.map { it.code }
        assertTrue(codes.contains("E-IMPURE-CALL"), "跨模块 @impure sink 应报 E-IMPURE-CALL，实际: $codes")
    }

    @Test
    fun c_同名impure定义拦截() {
        // 健全性场景：本模块 dup 是 @impure，可见模块 lib 另有纯版 dup。
        // 若 pure<dup> 因 lib 的纯版被注册，g 体内对本地 impure dup 的调用会被事实「pure<dup>」错误放行；
        // 同名任一处 @impure → 整名不注册，调用被拦下。
        val bag = checkTree(mapOf(
            "lib/a.subl" to "fun dup(n: Nat): Nat = n",
            "app/app.settings" to "mount lib",
            "app/main.subl" to "@impure fun dup(n: Nat): Nat = 0\nfun g(): Nat = dup(5)"
        ))
        val codes = bag.diags.map { it.code }
        assertTrue(codes.contains("E-IMPURE-CALL"), "同名 impure 定义应按保守拦截，实际: $codes")
    }
}
