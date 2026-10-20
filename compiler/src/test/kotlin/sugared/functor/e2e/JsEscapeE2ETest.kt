package sugared.functor.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sugared.functor.checker.Checker
import sugared.functor.codegen.JsCodeGen
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.assertEquals

/** 审查报告 §2.10：JS 字符串转义完整性（控制字符 + U+2028/2029 行终止符）回归测试。 */
class JsEscapeE2ETest {
    private fun nodeAvailable() = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    private fun run(src: String): String {
        val ast = parseSource(src, "e2e.subl")
        val chk = Checker(ast)
        val bag = chk.run()
        assertEquals(false, bag.hasError, "语义检查应通过，实际: " + bag.report())
        val js = JsCodeGen().generate(ast, chk.dictHits, chk.moduleHits, chk.consHits, chk.dictSubHits, chk.namedArgOrder, chk.methodSugarArgs, chk.asyncAwaitHits)
        val f = File.createTempFile("subl-jesc-", ".js")
        f.writeText(js)
        return try {
            val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), "node 退出码非 0: " + out + " 生成JS: " + js)
            out.trim()
        } finally { f.delete() }
    }

    // 含 U+0001 与 U+2028 的字符串：旧 q() 把 U+2028 原样写进 JS（行终止符引发语法错误）
    @Test
    fun a_控制字符与行终止符转义() {
        assumeTrue(nodeAvailable())
        val c1 = Char(1)
        val c2 = Char(0x2028)
        val src = "fun main() { unchecked print(\"a" + c1 + "b" + c2 + "c\") }"
        val out = run(src)
        assertEquals("a" + c1 + "b" + c2 + "c", out)
    }
}