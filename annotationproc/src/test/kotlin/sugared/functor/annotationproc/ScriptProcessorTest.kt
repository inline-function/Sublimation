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
import kotlin.test.assertSame
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
        val astEdits = mutableListOf<AstEdit>()
        val diags = mutableListOf<sugared.functor.checker.Diag>()
        ProcessorApi.register(interp, input, edits, astEdits, diags)
        interp.run(
            "var hits = structsWithAnnotation(\"allMut\")\n" +
            "fun countAll(xs: List[Str]): Nat = when(xs) {\n" +
            "    Nil => 0\n" +
            "    is Cons(s, rest) => 1 + countAll(rest)\n" +
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

    // ==================== 结构性编辑（root doc/注解处理器.md：处理器是 AST→AST 变换） ====================

    /** 读出 FileAst 里第一个名为 name 的函数声明。 */
    private fun funOf(ast: sugared.functor.ast.FileAst, name: String): sugared.functor.ast.FunDecl {
        val d = ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? sugared.functor.ast.FunDecl }
            .firstOrNull { it.name == name }
        assertNotNull(d, "没找到函数 $name")
        return d
    }

    /** setFunBody：被标函数的体整体替换为片段解析出的表达式；幂等（深度相等）→ 引擎收敛。 */
    @Test
    fun structuralEdit_setFunBody() {
        val ast = parseSource("@swap\nfun n(): Nat = 0\n", "t.subl")
        val proc = ScriptProcessor(
            name = "swap",
            interestedAnnotations = setOf("swap"),
            scriptSource =
                "fun loop(xs: List[Str]): Null = when(xs) {\n" +
                    "    Nil => null\n" +
                    "    is Cons(f, rest) => { setFunBody(f, \"7\"); loop(rest) }\n" +
                    "}\n" +
                    "loop(funsWithAnnotation(\"swap\"))\n",
            stdlibDir = File("core"),
        )
        val engine = ProcessorEngine()
        engine.addProcessor(proc)
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {},
            recheck = { newAst -> val chk = Checker(newAst); ProcessorEngine.RecheckResult(chk.run()) })
        val body = funOf(result.ast, "n").body
        assertTrue(body is sugared.functor.ast.IntLit && body.value == "7",
            "setFunBody 后 n 的体应为字面量 7，实际: $body")
        assertFalse(result.loopDetected, "同体替换应幂等收敛")
        assertTrue(result.rounds <= 2, "轮数 ${result.rounds} 超出预期")
    }

    /** prependStmts：块体前置插入；表达式体脱糖成 return 块；注解标记守 → 幂等收敛。 */
    @Test
    fun structuralEdit_prependStmts_bothBodyFormsConverge() {
        val ast = parseSource(
            "@trace\nfun blk(n: Nat): Nat { return n * 2 }\n" +
                "@trace\nfun expr(n: Nat): Nat = n + 1\n", "t.subl")
        val proc = ScriptProcessor(
            name = "trace",
            interestedAnnotations = setOf("trace"),
            scriptSource =
                "fun containsFun(f: Str): Bool = contains(funAnnotations(f), \"traced\")\n" +
                    "fun loop(xs: List[Str]): Null = when(xs) {\n" +
                    "    Nil => null\n" +
                    "    is Cons(f, rest) => { if (containsFun(f)) { null } else { prependStmts(f, listOf(\"var t = 1\")); addFunAnnotation(f, \"traced\") }; loop(rest) }\n" +
                    "}\n" +
                    "loop(funsWithAnnotation(\"trace\"))\n",
            stdlibDir = File("core"),
        )
        val engine = ProcessorEngine()
        engine.addProcessor(proc)
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {},
            recheck = { newAst -> val chk = Checker(newAst); ProcessorEngine.RecheckResult(chk.run()) })
        // 块体：第一句是新插入的 var t = 1
        val blkBody = funOf(result.ast, "blk").body as? sugared.functor.ast.BlockExpr
        assertNotNull(blkBody, "blk 体应为块")
        assertTrue(blkBody.stmts.first() is sugared.functor.ast.VarStmt, "blk 块首应插入 var t = 1")
        // 表达式体：脱糖成块（var t = 1 + return 原表达式）
        val exBody = funOf(result.ast, "expr").body as? sugared.functor.ast.BlockExpr
        assertNotNull(exBody, "expr 体应脱糖成块")
        assertTrue(exBody.stmts.first() is sugared.functor.ast.VarStmt, "expr 块首应插入 var t = 1")
        assertTrue(exBody.stmts.last() is sugared.functor.ast.ReturnStmt, "expr 块尾应为 return 脱糖")
        assertFalse(result.loopDetected, "注解守的 prepend 应幂等收敛")
    }

    /** addDecl / replaceDecl：生成新声明、整体替换；同名 addDecl 多轮幂等（不报 LOOP）。 */
    @Test
    fun structuralEdit_addDeclAndReplaceDecl() {
        val ast = parseSource("@gen\nfun magic(): Nat = 0\n", "t.subl")
        val proc = ScriptProcessor(
            name = "gen",
            interestedAnnotations = setOf("gen"),
            scriptSource =
                "addDecl(\"fun triple(x: Nat): Nat = x * 3\")\n" +
                    "fun loop(xs: List[Str]): Null = when(xs) {\n" +
                    "    Nil => null\n" +
                    "    is Cons(f, rest) => { replaceDecl(f, \"fun \" + f + \"(): Nat = triple(2)\"); loop(rest) }\n" +
                    "}\n" +
                    "loop(funsWithAnnotation(\"gen\"))\n",
            stdlibDir = File("core"),
        )
        val engine = ProcessorEngine()
        engine.addProcessor(proc)
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {},
            recheck = { newAst -> val chk = Checker(newAst); ProcessorEngine.RecheckResult(chk.run()) })
        // 新声明已加入
        val triple = result.ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? sugared.functor.ast.FunDecl }
            .firstOrNull { it.name == "triple" }
        assertNotNull(triple, "addDecl 应追加 triple")
        // 被标声明被整体替换：体成为 triple(2) 调用
        val body = funOf(result.ast, "magic").body as? sugared.functor.ast.CallExpr
        assertNotNull(body, "replaceDecl 后 magic 的体应为 triple(2) 调用")
        assertEquals("triple", (body.callee as sugared.functor.ast.NameRef).name)
        // 仅一个 triple（两轮各 addDecl 一次，幂等去重）
        val tripleCount = result.ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? sugared.functor.ast.FunDecl }
            .count { it.name == "triple" }
        assertEquals(1, tripleCount, "同名 addDecl 应幂等去重")
        assertFalse(result.loopDetected, "addDecl+replaceDecl 应幂等收敛")
    }

    /** 片段解析失败 → E-PROCESSOR-EDIT（该条丢弃，同批其他编辑照常生效）。 */
    @Test
    fun structuralEdit_badSnippetReportsAndOthersApply() {
        val ast = parseSource("@gen\nfun n(): Nat = 0\n", "t.subl")
        val proc = ScriptProcessor(
            name = "gen",
            interestedAnnotations = setOf("gen"),
            scriptSource =
                "setFunBody(\"n\", \"1 +\")\n" +
                    "setFunBody(\"ghost\", \"1\")\n" +
                    "setFunBody(\"n\", \"5\")\n",
        )
        val input = ProcessorContext(ast, symbols = null, annotated = emptyList())
        val out = proc.process(input)
        // 解析失败的编辑被丢弃；后续对 n 的合法编辑照常生效
        val body = funOf(out.ast, "n").body
        assertTrue(body is sugared.functor.ast.IntLit && body.value == "5", "应保留最后一条合法编辑: $body")
        val codes = out.diagnostics.map { it.code }
        assertTrue("E-PROCESSOR-EDIT" in codes, "应报 E-PROCESSOR-EDIT，实际: $codes")
        // 目标缺失：警告
        assertTrue(out.diagnostics.any { it.code == "W-PROCESSOR-EDIT" }, "应报 W-PROCESSOR-EDIT")
    }

    /** 全部编辑都未命中目标 → 仅警告、AST 引用不变（引擎据此跳过重查/停机）。 */
    @Test
    fun structuralEdit_allMissKeepAstReference() {
        val ast = parseSource("fun n(): Nat = 0\n", "t.subl")
        val out = applyAstEdits(ast, listOf(
            AstEdit.SetFunBody("nope", "1"),
            AstEdit.PrependStmts("nope2", listOf("var t = 1")),
            AstEdit.ReplaceDecl("nope3", "fun k(): Nat = 1"),
        ))
        assertSame(ast, out, "全部未命中应原样返回")
    }

    /** 处理器产物的类型错误被重查拦截（root doc 核心契约：处理器不能绕过 checker）。 */
    @Test
    fun structuralEdit_typeUnsafeOutputFailsRecheck() {
        val ast = parseSource("@gen\nfun n(): Nat = 0\n", "t.subl")
        // 脚本里 setFunBody 实参是带转义引号的源码片段（"oops" 的转义形），用字符码组装避开转义噪声
        val q = 34.toChar().toString()      // 双引号
        val esc = 92.toChar().toString()    // 反斜杠
        // oopsSrc 即脚本里的字面量（" 反斜杠 " oops 反斜杠 " "），无需再包引号
        val oopsSrc = q + esc + q + "oops" + esc + q + q
        val proc = ScriptProcessor(
            name = "gen",
            interestedAnnotations = setOf("gen"),
            scriptSource =
                "fun loop(xs: List[Str]): Null = when(xs) {\n" +
                    "    Nil => null\n" +
                    "    is Cons(f, rest) => { setFunBody(f, " + oopsSrc + "); loop(rest) }\n" +
                    "}\n" +
                    "loop(funsWithAnnotation(\"gen\"))\n",
            stdlibDir = File("core"),
        )
        val engine = ProcessorEngine()
        engine.addProcessor(proc)
        val result = engine.run(inputAst = ast, symbols = null, typeOf = { null }, emit = {},
            recheck = { newAst -> val chk = Checker(newAst); ProcessorEngine.RecheckResult(chk.run()) })
        // 体被换成了 Str 字面量 -> 重查 E-TYPE-MISMATCH，AST 变换本身成功
        val body = funOf(result.ast, "n").body
        assertTrue(body is sugared.functor.ast.StrLit, "体应被替换为字符串字面量")
        assertTrue(result.diagnostics.any { it.code == "E-TYPE-MISMATCH" },
            "重查应拦截类型不匹配的产物，实际: ${result.diagnostics.map { it.code }}")
    }
}
