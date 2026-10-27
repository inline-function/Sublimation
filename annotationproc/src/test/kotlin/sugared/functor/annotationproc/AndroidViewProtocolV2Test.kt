package sugared.functor.annotationproc

import org.junit.jupiter.api.Assumptions.assumeTrue
import sugared.functor.ast.*
import sugared.functor.checker.Checker
import sugared.functor.checker.Severity
import sugared.functor.checker.generateSingle
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 视图协议 v2（与 SfA t54 DshBridge 对齐）：modifier 有序链序列化 + 回调实例 key。
 *
 * 处理器/框架侧分工：view.subs 的重组注入不变；链序列化与回调实例 key 落在
 * JsCodeGen 框架侧——layout/androidRecompose 序列化前经 `__dshViewTree` 规范化
 * （modifier 字段是 List（JS 数组）时键名改为 "modifiers"，按链顺序；单对象旧形态
 * {tag:Plain} 原样保留），视图 struct 函数型字段的回调注册从纯编译期 key 改为
 * `__dshCb`（构造点词法 id + 运行时实例序号）——旧协议下同一构造点每轮求值覆盖同一
 * 全局槽位，循环/递归渲染的同款按钮全部串到最后一个闭包（已知串点击 bug）。
 * node 不可用时 E2E 自动跳过（同 E2ETest 约定），文本断言仍覆盖代码生成形态。
 */
class AndroidViewProtocolV2Test {

    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    private val commonDecls =
        // 与 core/collection/list.subl 同构（单文件无 core 时自备）：泛型 List + 变长 listOf
        "enum List[T] { Nil() Cons(T, List[T]) }\n" +
        "\n" +
        "fun listOf[T](@list items: T): List[T] = items\n" +
        "\n" +
        "@android @impure\n" +
        "fun layout(content: (Any) => Any): Null\n" +
        "\n" +
        "@android @impure\n" +
        "fun androidRecompose(view: Str, ordinal: Nat, v: Any): Null\n" +
        "\n" +
        "@android @impure\n" +
        "fun log(x: Any): Null\n"

    /** view + composable + android 处理器全跑（真实安卓编译链），再过 Checker 生成 JS。 */
    private fun gen(src: String): String {
        val ast = parseSource(src, "main.subl")
        val engine = ProcessorEngine(maxRounds = 5)
        BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR)
            .filter { it.name in setOf("view", "composable", "android") }
            .forEach { engine.addProcessor(it) }
        val r = engine.run(inputAst = ast)
        assertFalse(r.loopDetected, "处理器未收敛")
        val errs = r.diagnostics.filter { it.severity == Severity.ERROR }
        assertEquals(emptyList<String>(), errs.map { it.code }, "处理器诊断含错误: " + errs.joinToString { it.message })
        val chk = Checker(r.ast)
        val bag = chk.run()
        assertFalse(bag.hasError, "语义检查应通过，实际: " + bag.report())
        return generateSingle(chk, r.ast)
    }

    /** node 实跑：预置 __dshAndroid 捕获桩；末尾打印 layout 负载与已注册的回调槽位。 */
    private fun runNode(js: String): String {
        val prelude = "globalThis.__dshCap = [];\n" +
            "globalThis.__dshAndroid = (kind, args) => { globalThis.__dshCap.push([kind, args]); return null; };\n"
        val tail = "\nconsole.log(JSON.stringify(globalThis.__dshCap));\n" +
            "console.log(JSON.stringify(Object.keys(globalThis).filter(k => k.indexOf('__dshFn_') === 0).sort()));\n"
        val f = File.createTempFile("subl-v2-", ".js")
        f.writeText(prelude + js + tail)
        return try {
            val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), "node 退出码非 0，输出:\n$out\n生成 JS:\n$js")
            out.trim()
        } finally { f.delete() }
    }

    /** 捕获行解开 JSON 转义（payload 是字符串，内层引号被 \" 转义），便于直接 contains 断言。 */
    private val bs = 92.toChar().toString()   // 反斜杠
    private val q = 34.toChar().toString()    // 引号
    private fun norm(cap: String): String = cap.replace(bs + q, q)

    /** E2E：modifier 链 → "modifiers" 有序数组。 */
    @Test
    fun modifier_chain_serializes_as_ordered_modifiers_array() {
        assumeTrue(nodeAvailable())
        val src = commonDecls +
            "enum Modifier { Plain(), Width(Nat), Height(Nat) }\n" +
            "\n" +
            "@view struct Box(modifier: Any)\n" +
            "\n" +
            "@composable @impure\n" +
            "fun root(): Null = layout { Box(modifier = Cons(Width(4), Cons(Height(20), Nil()))) }\n" +
            "\n" +
            "fun main() { unchecked root() }\n"
        val cap = norm(runNode(gen(src)).lineSequence().first())
        assertTrue(cap.contains("\"layout\""), "应捕获 layout 调用: $cap")
        assertTrue(cap.contains("\"modifiers\":"), "链应序列化为 modifiers 有序数组: $cap")
        val wIdx = cap.indexOf("\"tag\":\"Width\"")
        val hIdx = cap.indexOf("\"tag\":\"Height\"")
        assertTrue(wIdx >= 0 && hIdx > wIdx, "Width 应按链顺序排在 Height 前: $cap")
        assertFalse(cap.contains("\"modifier\":"), "链形态下不应再出现单对象 modifier 键: $cap")
    }

    /** E2E：旧单形态向后兼容（modifier = Plain() 保持单对象）。 */
    @Test
    fun single_modifier_legacy_form_kept() {
        assumeTrue(nodeAvailable())
        val src = commonDecls +
            "enum Modifier { Plain() }\n" +
            "\n" +
            "@view struct Box(modifier: Any)\n" +
            "\n" +
            "@composable @impure\n" +
            "fun root(): Null = layout { Box(modifier = Plain()) }\n" +
            "\n" +
            "fun main() { unchecked root() }\n"
        val cap = norm(runNode(gen(src)).lineSequence().first())
        assertTrue(cap.contains("\"modifier\":{\"tag\":\"Plain\"}"), "单对象旧形态应原样保留: $cap")
        assertFalse(cap.contains("\"modifiers\""), "单形态不应冒出 modifiers 键: $cap")
    }

    /** E2E：同一构造点多次求值（递归/列表渲染）→ 每实例独立回调槽位（串点击回归）。 */
    @Test
    fun repeated_ctor_gets_distinct_callback_slots() {
        assumeTrue(nodeAvailable())
        val src = commonDecls +
            "enum Modifier { Plain() }\n" +
            "\n" +
            "@view struct Box(modifier: Any)\n" +
            "@view struct Button(label: Str, onClick: (Any) => Null)\n" +
            "\n" +
            "@composable @impure\n" +
            "fun mkBtn(label: Str): Any = Button(label = label, onClick = { unchecked log(label); null })\n" +
            "\n" +
            "@composable @impure\n" +
            "fun root(): Null = layout {\n" +
            "    Box(modifier = Plain()) {\n" +
            "        mkBtn(\"a\")\n" +
            "        mkBtn(\"b\")\n" +
            "        mkBtn(\"c\")\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "fun main() { unchecked root() }\n"
        val out = runNode(gen(src))
        val cap = norm(out.lineSequence().first())
        val slots = out.lineSequence().drop(1).first()
        val capKeys = Regex("Button_onClick_1_[0-9]+").findAll(cap).map { it.value }.toSet()
        val slotKeys = Regex("Button_onClick_1_[0-9]+").findAll(slots).map { it.value }.toSet()
        assertEquals(3, capKeys.size, "3 个按钮实例应有 3 个互不相同的 onClick key: $cap")
        assertEquals(capKeys, slotKeys, "onClick key 应与已注册槽位一致: cap=$cap slots=$slots")
        assertTrue(capKeys.all { it.startsWith("Button_onClick_1") }, "key 应含构造点词法 id: $cap")
    }

    /** 生成形态：helper 注入、回调注册改走 __dshCb、androidRecompose 同走规范化。 */
    @Test
    fun emitted_helpers_and_cb_registration_form() {
        val src = commonDecls +
            "@view struct Button(label: Str, onClick: (Any) => Null)\n" +
            "\n" +
            "@composable @impure\n" +
            "fun mkBtn(label: Str): Any = Button(label = label, onClick = { unchecked log(label); null })\n"
        val js = gen(src)
        assertTrue(js.contains("function __dshViewTree(x){"), "应注入 __dshViewTree 助手")
        assertTrue(js.contains("function __dshCb(base, fn){"), "应注入 __dshCb 助手")
        assertTrue(js.contains("globalThis[\"__dshFn_\" + k] = fn"), "回调应按动态 key 注册")
        assertFalse(js.contains("globalThis.__dshFn_"), "旧式编译期固定 key 注册应已消失")
        assertTrue(js.contains("__dshCb(\"Button_onClick_1\", ("), "回调注册应走 __dshCb 实例 key: ${js.take(600)}")
        assertTrue(js.contains("__dshAndroid(\"androidRecompose\", ["), "recomposion 函数桥接存在")
        assertTrue(js.contains("JSON.stringify(__dshViewTree("), "recomposion 序列化同走规范化")
    }
}