package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.checker.Checker
import sugared.functor.checker.generateSingle
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 安卓视图可变性语义（android doc §77）：v.f = x「看似返回 Null，实则返回视图自己的副本，
 *  再根据副本调用 recomposion」——codegen 将 @view 结构体上的字段赋值编译为
 *  深拷贝 → 改副本 → recomposion(副本)。
 */
class AndroidMutationTest {

    private val src =
        "@view struct Box(modifier: Any)\n" +
        "\n" +
        "@android @impure\n" +
        "fun androidRecompose(view: Str, ordinal: Nat, v: Any): Null\n" +
        "\n" +
        "@composable @impure\n" +
        "fun update(): Null {\n" +
        "    var b: Any = Box(modifier = 1)\n" +
        "    b.modifier = 2\n" +
        "}\n"

    @Test
    fun mutation_compiles_to_copy_then_recompose() {
        val engine = ProcessorEngine(maxRounds = 5)
        val p = BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR).first { it.name == "view" }
        engine.addProcessor(p)
        val ast = parseSource(src, "main.subl")
        val r = engine.run(inputAst = ast)
        val dbg = File(System.getProperty("java.io.tmpdir"), "dsh-mut.txt")
        dbg.writeText(r.diagnostics.joinToString("\n") { "${it.severity} ${it.code} ${it.message}" })
        dbg.appendText("\n--- JS ---\n")
        try {
            val chk = Checker(r.ast)
            val bag = chk.run()
            dbg.appendText("checker errors: " + bag.diags.filter { it.severity == sugared.functor.checker.Severity.ERROR }.size + "\n")
            val js = generateSingle(chk, r.ast)
            dbg.appendText(js)
        } catch (e: Exception) {
            dbg.appendText("EX: " + (e.message ?: e.toString()))
        }
        // 断言：JS 里 v.f = x 变成「拷贝副本 → 改副本 → recomposion(副本)」
        val js = File(System.getProperty("java.io.tmpdir"), "dsh-mut.txt").readText()
        assertTrue(js.contains("__dc(b)"), "应深拷贝视图：" + js.take(300))
        assertTrue(js.contains("__c.modifier = 2"), "应在副本上修改字段")
        assertTrue(js.contains("__c.recomposion(__c)"), "应据副本调用 recomposion")
    }
}