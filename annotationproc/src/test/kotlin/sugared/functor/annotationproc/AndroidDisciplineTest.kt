package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.checker.Severity
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 安卓注解处理器——@composable 纪律 + @android 白名单校验（android doc/安卓标准库.md）。
 *
 * composable.subs：视图结构体只能在 @composable 函数里构造 → 非 composable 函数里构造报错；
 * android.subs：@android 函数必须在白名单、必须 @impure、必须无体。
 */
class AndroidDisciplineTest {

    private fun loadAll(): List<ScriptProcessor> =
        BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR)
            .filter { it.name == "view" || it.name == "composable" || it.name == "android" }

    private fun runAll(src: String): ProcessorEngine.RunResult {
        val ast = parseSource(src, "main.subl")
        val engine = ProcessorEngine(maxRounds = 5)
        loadAll().forEach { engine.addProcessor(it) }
        return engine.run(inputAst = ast)
    }

    private fun dump(tag: String, r: ProcessorEngine.RunResult) {
        val dbg = File(System.getProperty("java.io.tmpdir"), "dsh-disc-$tag.txt")
        dbg.writeText("=== $tag rounds=" + r.rounds + " loop=" + r.loopDetected)
        r.diagnostics.forEach { dbg.appendText("\n${it.severity} ${it.code} ${it.message}") }
        r.ast.entries.forEach { x ->
            val d = (x as? DeclEntry)?.decl
            dbg.appendText("\nE: " + (d?.toString()?.replace("\n", " ")?.take(200) ?: "?"))
        }
    }

    /** 非 @composable 函数里构造视图 → 报错。 */
    @Test
    fun view_ctor_outside_composable_reported() {
        val src =
            "@view struct Button(label: Any)\n" +
            "@impure\n" +
            "fun main() { Button(label = 1) }\n"
        val r = runAll(src)
        dump("discipline", r)
        val errs = r.diagnostics.filter { it.severity == Severity.ERROR }
        assertTrue(errs.isNotEmpty(), "非 composable 函数里构造视图应报错")
        assertTrue(errs.any { it.code == "E-PROCESSOR-SCRIPT" && it.message.contains("composable") },
            "错误消息应指向 composable 纪律：" + errs.joinToString { it.message })
    }

    /** @composable 函数里构造视图 → 不报错且完成改写。 */
    @Test
    fun view_ctor_inside_composable_ok() {
        val src =
            "@view struct Button(label: Any)\n" +
            "@composable @impure\n" +
            "fun root(): Any = Button(label = 1)\n"
        val r = runAll(src)
        dump("ok", r)
        val errs = r.diagnostics.filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), errs.map { it.code }, "composable 内构造不应有错误：" + errs.joinToString { it.message })
    }

    /** @android 非白名单函数 → 报错。 */
    @Test
    fun android_non_whitelist_reported() {
        val src = "@android @impure\nfun myFun(v: Any): Null\n"
        val r = runAll(src)
        dump("whitelist", r)
        val msgs = r.diagnostics.map { it.code + ":" + it.message }
        assertTrue(msgs.any { it.contains("不是编译器支持的安卓内建") || it.contains("E-ANDROID-NOT-ALLOWED") },
            "应报白名单错：" + msgs)
    }

    /** @android 白名单函数（无体、impure）→ 通过。 */
    @Test
    fun android_whitelist_ok() {
        val src = "@android @impure\nfun log(msg: Str, level: Str): Null\n"
        val r = runAll(src)
        dump("builtin", r)
        val errs = r.diagnostics.filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), errs.map { it.code }, "白名单 @android 函数不应报错：" + errs.joinToString { it.message })
    }
}