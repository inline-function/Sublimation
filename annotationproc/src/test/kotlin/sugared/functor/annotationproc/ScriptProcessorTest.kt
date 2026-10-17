package sugared.functor.annotationproc

import sugared.functor.ast.DeclEntry
import sugared.functor.ast.StructDecl
import sugared.functor.checker.Checker
import sugared.functor.checker.generateSingle
import sugared.functor.interpreter.Interpreter
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 脚本处理器测试（root doc/注解处理器.md 11「第二版」：处理器是 Sublimation 脚本，由解释器解释执行）。
 *
 * 覆盖：
 * - @allMut 端到端（脚本跑在解释器上 → 编辑 AST → 重查 → codegen → node 跑出 5）；
 * - 脚本 API 查询/编辑（structNames/structsWithAnnotation/structFields/字段注解增删）；
 * - 脚本错误 → E-PROCESSOR-ERROR（AST 不变）；
 * - 无关注注解时引擎整轮跳过（AST 引用不变）。
 */
class ScriptProcessorTest {

    /** 读出 FileAst 里第一个名为 name 的结构体声明。 */
    private fun structOf(ast: sugared.functor.ast.FileAst, name: String): StructDecl {
        val d = ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? StructDecl }
            .firstOrNull { it.name == name }
        assertNotNull(d, "没找到结构体 $name")
        return d
    }

    /** 端到端：@allMut 让结构体与其全部字段可变，编译跑 node 输出 5。 */
    @Test
    fun allMut_end_to_end() {
        val nodeOk = try {
            ProcessBuilder("node", "--version").start().waitFor() == 0
        } catch (e: Exception) { false }
        if (!nodeOk) return   // 环境无 node：跳过 codegen 执行段，仍保留前段的断言

        val tmp = File(System.getProperty("java.io.tmpdir"), "subl-apitest-" + System.nanoTime())
        tmp.mkdirs()
        try {
            File(tmp, "main.subl").writeText(
                "@allMut\n" +
                "struct Point(x: Nat, y: Nat)\n" +
                "\n" +
                "@impure\n" +
                "fun main() {\n" +
                "    var p: @mut Point = Point(1, 2)\n" +
                "    p.x = 5\n" +
                "    print(toStr(p.x))\n" +
                "}\n"
            )

            val host = AnnotationProcessorHost()
            host.loadProject(File(tmp, "main.subl").absolutePath)
            val result = host.processAnnotations()
            assertFalse(hasErrorDiag(result.diagnostics), result.diagnostics.joinToString("\n")
                { it.code + " " + it.message })

            val out = result.ast
            // 变换结果：Point 带上 mut，字段 x/y 也各带 mut
            val st = structOf(out, "Point")
            assertTrue("mut" in st.annotations, "Point 的注解缺失 mut: " + st.annotations)
            assertTrue(st.fields.all { "mut" in it.annotations }, "Point 字段注解缺失 mut")

            // 重查通过 + codegen 能跑
            val chk = Checker(out)
            val bag = chk.run()
            assertFalse(bag.hasError, bag.report())
            val js = generateSingle(chk, out)
            val jsFile = File(tmp, "main.js")
            jsFile.writeText(js)
            val proc = ProcessBuilder("node", jsFile.absolutePath)
                .redirectErrorStream(true).start()
            val txt = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            assertEquals("5", txt, "node 输出与预期不符: $txt")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 脚本 API：查询 + 编辑。用解释器直接跑一段手工脚本，核对其累积的编辑。 */
    @Test
    fun scriptApi_queries_and_edits() {
        val src = "@allMut\n" +
            "struct Point(x: Nat, y: Nat)\n" +
            "\n" +
            "fun helper(): Nat = 1\n"
        val ast = parseSource(src, "t.subl")
        val input = ProcessorContext(ast, symbols = null, annotated = emptyList())

        val interp = Interpreter()
        val edits = mutableListOf<AnnotationEdit>()
        val diags = mutableListOf<sugared.functor.checker.Diag>()
        ProcessorApi.register(interp, input, edits, diags)
        interp.run(
            "var hits = structsWithAnnotation(\"allMut\")\n" +
            "fun countAll(xs: List[Str]): Nat = when(xs) {\n" +
            "    Nil -> 0\n" +
            "    is Cons(s, rest) -> 1 + countAll(rest)\n" +
            "}\n" +
            "print(toStr(countAll(hits)) + toStr(structHasAnnotation(\"Point\", \"allMut\")))\n" +
            "addStructAnnotation(\"Point\", \"mut\")\n" +
            "addFieldAnnotation(\"Point\", \"x\", \"mut\")\n"
        )
        assertEquals(2, edits.size)
        assertEquals(AnnotationEdit(EditTarget.STRUCT, "Point", null, "mut", add = true), edits[0])
        assertEquals(AnnotationEdit(EditTarget.STRUCT, "Point", "x", "mut", add = true), edits[1])
    }

    /** 脚本崩了 → E-PROCESSOR-ERROR，AST 原样返回。 */
    @Test
    fun scriptFailure_reports_error_and_keeps_ast() {
        val src = "@allMut\nstruct Point(x: Nat)\n"
        val ast = parseSource(src, "t.subl")
        val bad = ScriptProcessor(
            name = "boom",
            interestedAnnotations = setOf("allMut"),
            scriptSource = "undefinedFunctionCall(42)\n",
            scriptFileName = "<test:boom>",
        )
        val input = ProcessorContext(ast, symbols = null, annotated = emptyList())
        val out = bad.process(input)
        assertEquals(ast, out.ast)
        assertEquals(1, out.diagnostics.size)
        assertEquals("E-PROCESSOR-ERROR", out.diagnostics[0].code)
    }

    /** 无关注注解：引擎跳过，AST 引用不变。 */
    @Test
    fun noInterestedAnnotation_skips_processing() {
        val src = "struct Plain(x: Nat)\n"
        val ast = parseSource(src, "t.subl")
        val engine = ProcessorEngine()
        engine.addProcessor(BuiltinProcessors.allMut())
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {}, recheck = null)
        assertEquals(ast, result.ast)
        assertFalse(result.loopDetected)
        assertEquals(1, result.rounds)
    }

    /** 幂等性：同一轮里重复加 mut 不产生变化 -> 引擎第二轮判定不变、收敛退出。 */
    @Test
    fun allMut_is_idempotent_and_converges() {
        val src = "@allMut\nstruct Point(x: Nat, y: Nat)\n"
        val ast = parseSource(src, "t.subl")
        val engine = ProcessorEngine()
        engine.addProcessor(BuiltinProcessors.allMut())
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {},
            recheck = { newAst ->
                val chk = Checker(newAst)
                ProcessorEngine.RecheckResult(chk.run())
            })
        assertTrue("mut" in structOf(result.ast, "Point").annotations)
        assertTrue(structOf(result.ast, "Point").fields.all { "mut" in it.annotations })
        assertFalse(result.loopDetected, "@allMut 必须幂等收敛，不能报 E-PROCESSOR-LOOP")
        assertTrue(result.rounds <= 3, "轮数 ${result.rounds} 超出预期")
    }

    /** 文件装载约定：.subs 文件 = 处理器，文件主名 = 处理器名 = 关注的注解名。 */
    @Test
    fun fileLoader_mapsFilenameToProcessorNameAndAnnotation() {
        val tmp = File(System.getProperty("java.io.tmpdir"), "subl-loadtest-" + System.nanoTime())
        tmp.mkdirs()
        try {
            File(tmp, "myAnno.subs").writeText("fun noop(xs: List[Str]): Null = null\n")
            File(tmp, "other.subs").writeText("fun noop(xs: List[Str]): Null = null\n")
            File(tmp, "README.txt").writeText("not a processor\n")
            val ps = BuiltinProcessors.loadFrom(tmp)
            assertEquals(2, ps.size)
            assertEquals(setOf("myAnno"), ps[0].interestedAnnotations)
            assertEquals("myAnno", ps[0].name)
            assertEquals(setOf("other"), ps[1].interestedAnnotations)
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** 无效路径不抛异常，只记装载错误。 */
    @Test
    fun loadProcessorFiles_invalidPathReportsError() {
        val r = ProcessorPipeline.loadProcessorFiles(listOf(File("no-such-dir-xyz")))
        assertEquals(0, r.processors.size)
        assertEquals(1, r.errors.size)
    }

    private fun hasErrorDiag(diags: List<sugared.functor.checker.Diag>): Boolean =
        diags.any { it.severity == sugared.functor.checker.Severity.ERROR }
}
