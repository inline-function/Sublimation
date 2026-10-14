package sugared.functor.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sugared.functor.checker.Checker
import sugared.functor.codegen.JsCodeGen
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.assertEquals

/**
 * 日常脚本端到端（examples/日常-*.subl）：读取真实源文件 → Checker 语义检查 →
 * JsCodeGen 生成 JS → node 实跑 → 断言 stdout。node 不可用时自动跳过。
 */
class DailyScriptsE2ETest {
    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    private fun run(src: String): String {
        val ast = parseSource(src, "daily.subl")
        val chk = Checker(ast)
        val bag = chk.run()
        assertEquals(false, bag.hasError, "语义检查应通过，实际:\n${bag.report()}")
        val js = JsCodeGen().generate(ast, chk.dictHits, chk.moduleHits, chk.consHits, chk.dictSubHits, chk.namedArgOrder, chk.methodSugarArgs, chk.asyncAwaitHits, chk.channelUsed, chk.mutexUsed, chk.atomicUsed, chk.flowUsed, chk.rangeHits, chk.rangeUsed, chk.receiveSmartHits, chk.varUnwrapHits, chk.funBodyUnwrapHits, chk.flowEmitHits, chk.hktKindPosHits, chk.funRefTargets, chk.funRefFree.keys, chk.lambdaDestructKeys, chk.delegatedImpls, chk.deepCopyHits)
        val f = File.createTempFile("subl-daily-", ".js")
        f.writeText(js)
        return try {
            val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), "node 退出码非 0，输出:\n$out\n生成 JS:\n$js")
            out.trim()
        } finally { f.delete() }
    }

    private fun runExample(name: String): String = run(File("examples/$name.subl").readText())

    @Test fun temperatureAndBmi() {
        assumeTrue(nodeAvailable())
        assertEquals("99\n100\n212\nBMI=23\nnormal\nunder\nCelsius", runExample("日常-温度与BMI"))
    }

    @Test fun billStatistics() {
        assumeTrue(nodeAvailable())
        assertEquals("4\ntotal=86\n30,40\nrice\n40", runExample("日常-账单统计"))
    }

    @Test fun contacts() {
        assumeTrue(nodeAvailable())
        assertEquals("Alice 30\nBob 25\nnot-found\n2", runExample("日常-通讯录"))
    }

    @Test fun textProcessing() {
        assumeTrue(nodeAvailable())
        assertEquals("HELLO\nspaced\nsubl\na+b+c\ntrue\n7\ncannot parse: xx\n2\n5", runExample("日常-文本处理"))
    }

    @Test fun sequencesAndRanges() {
        assumeTrue(nodeAvailable())
        assertEquals("5\n1,4,9,16,25\n3,4,5\n15\n5,4,3,2,1\n120", runExample("日常-数列与区间"))
    }

    @Test fun asyncTasks() {
        assumeTrue(nodeAvailable())
        assertEquals("receive total 42\nchild task\nclosed = true", runExample("日常-异步任务"))
    }

    @Test fun hktContainer() {
        assumeTrue(nodeAvailable())
        assertEquals("3,6,9", runExample("日常-容器范型"))
    }
}
