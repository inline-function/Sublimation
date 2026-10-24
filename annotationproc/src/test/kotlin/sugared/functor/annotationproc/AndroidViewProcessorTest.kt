package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.checker.Checker
import sugared.functor.checker.MultiModule
import sugared.functor.checker.Severity
import sugared.functor.checker.generateSingle
import java.nio.file.Files
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 安卓注解处理器测试（android doc/安卓标准库.md）：@view/@composable/@android 三件套。
 *
 * 覆盖：
 * - @view 结构体重组：body 擦除 -> children: List[Any] + recomposion: (Any)=>Null；
 * - 构造点改写：容器 X(...) { 子视图 } -> children=[...]；叶子 X(...) -> children=Nil；
 * - 每个构造点生成单参函数 recomposion$X$N（调 @android androidRecompose 桥）；
 * - 多轮收敛（幂等）；
 * - 端到端：挂 core 标准库 -> 重查通过 -> codegen -> node 运行。
 */
class AndroidViewProcessorTest {

    private fun structOf(ast: FileAst, name: String): StructDecl {
        val d = ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? StructDecl }
            .firstOrNull { it.name == name }
        assertNotNull(d, "没找到结构体 $name")
        return d
    }

    private fun funOf(ast: FileAst, name: String): FunDecl {
        val d = ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? FunDecl }
            .firstOrNull { it.name == name }
        assertNotNull(d, "没找到函数 $name")
        return d
    }

    /** view.subs 处理器（仓库 annotation processer/view.subs）。 */
    private fun viewProcessor(): ScriptProcessor =
        BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR).first { it.name == "view" }

    /** 只跑变换（不做重查）——用于断言 AST 形态。 */
    private fun runView(src: String): ProcessorEngine.RunResult {
        val ast = parseSource(src, "main.subl")
        val engine = ProcessorEngine(maxRounds = 5)
        engine.addProcessor(viewProcessor())
        return engine.run(inputAst = ast)
    }

    /** 调试转储（gradle 不显示测试 stdout，写文件看）。 */
    private fun dump(tag: String, r: ProcessorEngine.RunResult) {
        val dbg = File(System.getProperty("java.io.tmpdir"), "dsh-aptest.txt")
        dbg.writeText("=== $tag rounds=" + r.rounds + " loop=" + r.loopDetected)
        dbg.appendText(r.diagnostics.joinToString("\n") { "\n${it.severity} ${it.code} @${it.pos} ${it.message}" })
        r.ast.entries.forEach { x ->
            val d = (x as? DeclEntry)?.decl
            dbg.appendText("\nENTRY " + (d?.toString()?.replace("\n", " ")?.take(300) ?: x.toString().take(80)))
        }
    }

    /** 独立 @view 程序：两个视图结构体 + 嵌套构造（覆盖次序分配）。 */
    private val viewSrc =
        "@view struct Box(modifier: Any)\n" +
        "@view struct Button(label: Any)\n" +
        "\n" +
        "@android @impure\n" +
        "fun androidRecompose(view: Str, ordinal: Nat, v: Any): Null\n" +
        "\n" +
        "@composable @impure\n" +
        "fun root(): Any = Box(modifier = 1) { Button(label = 2) }\n"

    @Test
    fun view_struct_gets_children_and_recomposion() {
        val r = runView(viewSrc)
        dump("children", r)
        val box = structOf(r.ast, "Box")
        assertEquals(listOf("modifier", "children", "recomposion", "__ord"), box.fields.map { it.name })
        val recom = box.fields.first { it.name == "recomposion" }
        assertTrue(recom.type is FunType, "recomposion 应是函数类型: ${recom.type.render()}")
    }

    @Test
    fun multi_round_converges() {
        val r = runView(viewSrc)
        dump("converges", r)
        assertFalse(r.loopDetected, "处理器未收敛（多轮不稳定）")
        assertTrue(r.rounds <= 2, "轮数 ${r.rounds} 过多")
    }

    /** 生成函数：recomposion$Box$1(v) -> androidRecompose("Box", 1, v)。 */
    @Test
    fun recomposion_function_generated() {
        val r = runView(viewSrc)
        dump("recomposionFn", r)
        val fn = funOf(r.ast, "recomposion\$Box\$1")
        assertEquals(1, fn.params.size, "recomposion 函数应单参")
        assertEquals("v", fn.params[0].name)
        val btn = funOf(r.ast, "recomposion\$Button\$1")
        assertNotNull(btn.body)
    }

    /** 构造点改写：body 擦除、子视图进 children、recomposion 绑定。 */
    @Test
    fun ctor_sites_rewritten() {
        val r = runView(viewSrc)
        dump("ctorSites", r)
        val root = funOf(r.ast, "root")
        val body = root.body as? StructCtorExpr
        assertNotNull(body, "root 体应为 Box 构造")
        assertEquals("Box", body.struct)
        val assigns = body.assigns.map { it.first }
        assertEquals(listOf("modifier", "children", "recomposion", "__ord"), assigns)
        val recom = body.assigns.first { it.first == "recomposion" }.second as? NameRef
        assertNotNull(recom, "recomposion 位应是 NameRef")
        assertEquals("recomposion\$Box\$1", recom.name)
        val kids = body.assigns.first { it.first == "children" }.second as? CallExpr
        assertNotNull(kids, "children 应是 listOf 调用")
        assertEquals("listOf", (kids.callee as? NameRef)?.name)
        assertEquals(1, kids.args.size)
        val btn = kids.args[0] as? StructCtorExpr
        assertNotNull(btn, "children 首成员应为 Button 构造")
        assertEquals(listOf("label", "children", "recomposion", "__ord"), btn.assigns.map { it.first })
    }

    /** 回归（旅行手记）：@list 视图 children 混排结构体与返回 Any 的 @composable 调用，
     *  不得误报 E-TYPE-MISMATCH「变长实参须为同一类型」——children 元素类型应退化为 List[Any]。 */
    @Test
    fun list_fallback_accepts_any_typed_child_call() {
        val dir = Files.createTempDirectory("subl-viewck").toFile()
        try {
            File(dir, "main.subl").writeText(
                "@view struct Box(modifier: Any)\n" +
                "@view struct Text(text: Str)\n" +
                "@android @impure\n" +
                "fun androidRecompose(view: Str, ordinal: Nat, v: Any): Null\n" +
                "\n" +
                "@composable\n" +
                "fun rows(): Any = when (true) {\n" +
                "    true => Box(modifier = 1) { Text(text = \"a\") }\n" +
                "    else => Box(modifier = 1) {}\n" +
                "}\n" +
                "\n" +
                "@composable\n" +
                "fun root(): Box = Box(modifier = 1) { Text(text = \"b\"); rows() }\n"
            )
            // 挂 core 标准库（listOf/Nil 等由 core 提供）
            val coreSrc = File("core")
            val coreDst = File(dir, "core"); coreDst.mkdirs()
            for (f in coreSrc.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }) {
                val rel = f.relativeTo(coreSrc).path
                File(coreDst, rel).apply { parentFile?.mkdirs() }.writeText(f.readText())
            }
            val mm = MultiModule(dir)
            val procs = BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR)
            val pbag = mm.processModules(ProcessorPipeline.builtinModuleTransformer(
                ProcessorLoadResult(procs, emptyList()), emptyMap()))
            val bag = mm.checkAll()
            bag.absorb(pbag)
            assertFalse(bag.hasError, "混排 Any 子视图不应报错，实际:\n" + bag.report())
        } finally { dir.deleteRecursively() }
    }
}
