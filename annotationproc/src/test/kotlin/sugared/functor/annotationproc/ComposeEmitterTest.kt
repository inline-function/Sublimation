package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.parser.parseSource
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * @compose 视图 → Kotlin Compose 源码发射器测试（SfA「生成 .kt + Gradle 编译」通路）。
 *
 * 覆盖：
 * - 解析：@compose struct 的大括号调用序列体 / `= 表达式` 单表达式体（普通 struct 仍拒绝大括号）；
 * - 翻译（对齐 SfA《Compose 代码生成契约》§3/§5）：Box(orientation/wrap)→Column/Row/FlowRow、
 *   spacing/arrangement、Text(text/color/size sp/bold/maxLines)、Button(style/label)、
 *   Card(cornerRadius/elevation/backgroundColor)、Icon(name→Icons.Filled.X)、AlertDialog/BottomSheet、
 *   modifier 链（链式 AST + MKind 构造子）、颜色 #RRGGBB/#AARRGGBB + 主题色名；
 * - 发射形态：@Composable fun、package、自动 import（扩展类 API 用到才引）、@file:OptIn、依赖坐标；
 * - 端到端：compose.subs 处理器把 .kt 写进 <outDir>/<Name>.kt，@view 通路不受影响。
 */
class ComposeEmitterTest {

    private fun structOf(ast: FileAst, name: String): StructDecl {
        val d = ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? StructDecl }.firstOrNull { it.name == name }
        assertNotNull(d, "没找到结构体 $name")
        return d
    }

    /** Sublimation 侧样例（组件字段对齐 android stdl 形态）。 */
    private val subSrc = """
        // @compose 样例（组件字段对齐 android stdl）
        @compose struct Home
        @compose struct Title(text: Str, color: Str)
        @compose struct Home2 {
            Box(orientation = "vertical", modifier = fillWidth().padding(16), spacing = 16) {
                Text(text = "你好")
                Text(text = "点我", color = "#FF6600", size = 16.0, bold = true)
            }
            Box(orientation = "horizontal", spacing = 8, arrangement = "Center") {
                Card(cornerRadius = 12, elevation = 1) {
                    Text(text = "卡片")
                }
                Button(label = "确定")
                Button(style = "text", label = "取消")
            }
            Title(text = "页脚", color = "#80FF0000")
        }
        @compose struct Home3(path: Str) = Home2()
    """.trimIndent()

    private fun parse() = parseSource(subSrc, "main.subl")

    // ---------- 1. 解析 ----------

    @Test
    fun compose_struct_parses_block_body() {
        val st = structOf(parse(), "Home2")
        val body = st.body
        assertNotNull(body, "@compose 视图的大括号体应解析为内容（BlockExpr），实际 null")
        val stmts = (body as BlockExpr).stmts
        assertEquals(3, stmts.size, "Home2 体应有 3 条调用语句")
        val first = ((stmts[0] as ExprStmt).expr as CallExpr).callee as StructCtorExpr
        assertEquals("Box", first.struct)
    }

    @Test
    fun compose_struct_parses_expr_body() {
        val st = structOf(parse(), "Home3")
        assertEquals(listOf("path"), st.fields.map { it.name })
        val body = st.body
        assertNotNull(body, "@compose 视图的 = 体应解析为单表达式视图")
        assertTrue(body is CallExpr || body is StructCtorExpr, "Home3 体应为 Home2() 调用，实际 " + body)
    }

    @Test
    fun plain_struct_still_rejects_brace_body() {
        var failed = false
        try { parseSource("struct P { x }", "bad.subl") } catch (e: Exception) { failed = true }
        assertTrue(failed, "普通 struct 的大括号体仍应报错（@compose 之外的语法不变）")
    }

    @Test
    fun compose_annotated_struct_is_listed() {
        val sts = parse().entries.mapNotNull { (it as? DeclEntry)?.decl as? StructDecl }
        val views = sts.filter { it.isCompose() }.map { it.name }
        assertEquals(listOf("Home", "Title", "Home2", "Home3"), views)
    }

    // ---------- 2. 发射：组件映射（契约 §5）----------

    @Test
    fun emit_composable_fun_with_package_and_params() {
        val r = ComposeEmitter.emitStruct(structOf(parse(), "Title"))
        assertTrue(r.source.contains("package app.compose"), r.source)
        assertTrue(r.source.contains("@Composable"), r.source)
        assertTrue(r.source.contains("fun Title(text: String, color: String) {"), r.source)
        assertFalse(r.source.contains("@Composable fun Title"), "Kotlin 写法是 @Composable 换行 fun")
    }

    @Test
    fun emit_box_orientation_maps_to_column_and_row() {
        val out = ComposeEmitter.emitStruct(structOf(parse(), "Home2")).source
        assertTrue(out.contains("androidx.compose.foundation.layout.Column("), out)
        assertTrue(out.contains("androidx.compose.foundation.layout.Row("), out)
        assertFalse(out.contains("androidx.compose.foundation.layout.Box("), "orientation 已消解，不应再出 Box")
        // spacing → spacedBy；arrangement → Arrangement.X（契约 §5）
        assertTrue(out.contains("verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp)"), out)
        assertTrue(out.contains("horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center"), out)
    }

    @Test
    fun emit_box_wrap_maps_to_flow_row() {
        // 空内容视图：FlowRow 是 foundation 组件，不应顺带声明 material3 依赖
        val src = """
            @compose struct Chips {
                Box(orientation = "horizontal", wrap = true)
            }
        """.trimIndent()
        val r = ComposeEmitter.emitStruct(structOf(parseSource(src, "chips.subl"), "Chips"))
        assertTrue(r.source.contains("androidx.compose.foundation.layout.FlowRow("), r.source)
        assertTrue(r.source.contains("@file:OptIn("), r.source)
        assertFalse(r.usesMaterial3, "FlowRow 走 foundation，不应声明 material3：" + r.components)
        assertTrue(r.dependencies.contains(ComposeEmitter.MATERIAL3_DEPENDENCY) == false, r.dependencies.toString())
    }

    @Test
    fun emit_material3_components() {
        val out = ComposeEmitter.emitStruct(structOf(parse(), "Home2")).source
        assertTrue(out.contains("androidx.compose.material3.Text(text = \"你好\", modifier = Modifier)"), out)
        assertTrue(out.contains(
            "androidx.compose.material3.Text(text = \"点我\", modifier = Modifier, color = Color(0xFFFF6600), fontSize = 16.0.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)"), out)
        // Button：onClick 必填；style=text → TextButton；label 便捷字段进 content slot（SfA Button stdl）
        assertTrue(out.contains("androidx.compose.material3.Button(onClick = { }"), out)
        assertTrue(out.contains("androidx.compose.material3.TextButton(onClick = { }"), out)
        // Card：圆角/阴影真值（契约 §5）
        assertTrue(out.contains("androidx.compose.material3.Card("), out)
        assertTrue(out.contains("shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)"), out)
        assertTrue(out.contains("elevation = androidx.compose.material3.CardDefaults.cardElevation(1.dp)"), out)
        // 跨视图组合：按视图函数调用并透传命名实参
        assertTrue(out.contains("Title(text = \"页脚\", color = \"#80FF0000\")"), out)
        // 简名引用 + 文件头自动 import（用到才引）
        assertTrue(out.contains("import androidx.compose.ui.Modifier"), out)
        assertTrue(out.contains("import androidx.compose.ui.graphics.Color"), out)
        assertTrue(out.contains("import androidx.compose.ui.unit.sp"), out)
        // 链元素是 Modifier 扩展函数：用到的 import 自动收集（缺 import 不能编译）
        assertTrue(out.contains("import androidx.compose.foundation.layout.fillMaxWidth"), out)
        assertTrue(out.contains("import androidx.compose.foundation.layout.padding"), out)
        assertTrue(out.contains("import androidx.compose.ui.unit.dp"), out)
    }

    @Test
    fun emit_color_literals() {
        assertEquals("Color(0xFFFF6600)", ComposeEmitter.colorLiteral("#FF6600"))
        assertEquals("Color(0x80FF0000)", ComposeEmitter.colorLiteral("#80FF0000"))
        assertEquals("Color(0xFFAABBCC)", ComposeEmitter.colorLiteral("#abc"))
        assertNull(ComposeEmitter.colorLiteral("red"))
        val out = ComposeEmitter.emitStruct(structOf(parse(), "Home2")).source
        assertTrue(out.contains("color = Color(0xFFFF6600)"), out)
    }

    @Test
    fun emit_theme_color_names() {
        // 主题色名 / 主题函数 → MaterialTheme.colorScheme.<槽位>（契约 §4.1）
        assertEquals("MaterialTheme.colorScheme.primary", ComposeEmitter.colorOrExpr(StrLit("primary")))
        assertEquals("MaterialTheme.colorScheme.onSurfaceVariant",
            ComposeEmitter.colorOrExpr(CallExpr(NameRef("onSurfaceVariant"), emptyList())))
        assertNull(ComposeEmitter.colorOrExpr(StrLit("warning")), "warning 不是 M3 槽位（自定义主题色待桥侧细化）")
        assertNull(ComposeEmitter.colorOrExpr(NameRef("x")))
    }

    @Test
    fun emit_modifier_chain() {
        // Str 简写原样透传（低级逃逸口）
        val m = ComposeEmitter.modifierChain(StrLit("fillMaxWidth"))
        assertEquals("Modifier.fillMaxWidth()", m)
        val chain = ComposeEmitter.modifierChain(CallExpr(NameRef("listOf"), listOf(StrLit("fillMaxWidth"), StrLit("padding(8)"))))
        assertEquals("Modifier.fillMaxWidth().padding(8)", chain)
        // 结构化调用：nat → dp（契约 §3.2）
        val call = ComposeEmitter.modifierChain(
            CallExpr(NameRef("listOf"), listOf(StrLit("fillMaxWidth"), CallExpr(NameRef("height"), listOf(IntLit("56"))))))
        assertEquals("Modifier.fillMaxWidth().height(56.dp)", call)
        // SfA stdl 链式形态：width(4).height(20)（CallExpr(FieldExpr(...))）
        val sfa = ComposeEmitter.modifierChain(
            CallExpr(FieldExpr(CallExpr(NameRef("width"), listOf(IntLit("4"))), "height", ""), listOf(IntLit("20"))))
        assertEquals("Modifier.width(4.dp).height(20.dp)", sfa)
        // MKind 构造子：M 前缀按 §3.2 表消解；background 的 hex → Color
        val mkind = ComposeEmitter.modifierChain(CallExpr(NameRef("listOf"), listOf(
            CallExpr(NameRef("MWidth"), listOf(IntLit("4"))),
            CallExpr(NameRef("MBackground"), listOf(StrLit("#FF6600"))))))
        assertEquals("Modifier.width(4.dp).background(Color(0xFFFF6600))", mkind)
        // paddingHV(h, v) → padding(horizontal = h.dp, vertical = v.dp)
        val hv = ComposeEmitter.modifierChain(CallExpr(NameRef("paddingHV"), listOf(IntLit("24"), IntLit("8"))))
        assertEquals("Modifier.padding(horizontal = 24.dp, vertical = 8.dp)", hv)
        assertNull(ComposeEmitter.modifierChain(null))
    }

    @Test
    fun emit_modifier_chain_collects_extension_imports() {
        // 链元素是 Modifier 扩展函数：用到的 import 记进集合（缺 import 不能编译）
        val imps = mutableSetOf<String>()
        ComposeEmitter.modifierChain(CallExpr(NameRef("fillWidth"), emptyList()), imps)
        assertTrue("androidx.compose.foundation.layout.fillMaxWidth" in imps, imps.toString())
        ComposeEmitter.modifierChain(CallExpr(NameRef("weight"), listOf(FloatLit("1.0"))), imps)
        assertFalse("androidx.compose.foundation.layout.weight" in imps,
            "weight 是 RowScope/ColumnScope 成员，不 import：" + imps.toString())
    }

    @Test
    fun emit_icon_maps_name_to_icons_filled() {
        val src = """
            @compose struct IconRow {
                Icon(name = "close", size = 32, tint = "#FF6600")
            }
        """.trimIndent()
        val out = ComposeEmitter.emitStruct(structOf(parseSource(src, "icon.subl"), "IconRow")).source
        // name 首字母大写 → Icons.Filled.X；非默认 size 进 modifier.size(dp)
        assertTrue(out.contains(
            "androidx.compose.material3.Icon(imageVector = androidx.compose.material.icons.Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(32.dp), tint = Color(0xFFFF6600))"), out)
        // 扩展属性（Icons.Filled.X）必须 import：扩展类 API 不能全限定直引
        assertTrue(out.contains("import androidx.compose.material.icons.filled.Close"), out)
        assertTrue(out.contains("import androidx.compose.foundation.layout.size"), out)
    }

    @Test
    fun emit_header_optin_and_dependencies() {
        // 实验性组件（AlertDialog）→ 文件级 @file:OptIn；依赖坐标精确（runtime 恒有 + material3 按用）
        val dlg = ComposeEmitter.emitStruct(structOf(parseDialog(), "Dialog"))
        assertTrue(dlg.source.contains("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)"), dlg.source)
        assertTrue(dlg.usesMaterial3)
        assertTrue(dlg.dependencies.contains(ComposeEmitter.RUNTIME_DEPENDENCY))
        assertTrue(dlg.dependencies.contains(ComposeEmitter.MATERIAL3_DEPENDENCY))
        assertTrue(dlg.components.all { it.startsWith("androidx.compose.") }, dlg.components.toString())
        // 非实验性视图不引用 ExperimentalMaterial3Api（不伪造没用到的东西）
        val r = ComposeEmitter.emitStruct(structOf(parse(), "Home2"))
        assertFalse(r.source.contains("OptIn"), r.source)
        assertTrue(r.usesMaterial3)
        // 无内容视图：只声明 runtime，不声明 material3
        val home = ComposeEmitter.emitStruct(structOf(parse(), "Home"))
        assertFalse(home.usesMaterial3, "无内容视图不应声明 material3 依赖")
        assertFalse(home.dependencies.contains(ComposeEmitter.MATERIAL3_DEPENDENCY))
    }

    /** AlertDialog 样例（实验 API 组件，title 是 slot）。 */
    private fun parseDialog() = parseSource("""
        @compose struct Dialog {
            AlertDialog(title = "删除服药记录？", confirmText = "删除")
        }
    """.trimIndent(), "dialog.subl")

    @Test
    fun emit_alert_dialog_shape_is_valid_material3() {
        val out = ComposeEmitter.emitStruct(structOf(parseDialog(), "Dialog")).source
        // 命名实参形态：confirmButton 是必填 slot（给了文案放 Text，没给空 slot 顶着）；title slot 包一层 Text
        assertTrue(out.contains(
            "androidx.compose.material3.AlertDialog(onDismissRequest = { }, title = { androidx.compose.material3.Text(text = \"删除服药记录？\") }, confirmButton = { androidx.compose.material3.Text(text = \"删除\") })"), out)
    }

    @Test
    fun emit_alert_dialog_hidden_when_visible_literal_false() {
        val src = """
            @compose struct Hidden {
                AlertDialog(title = "x", visible = false)
            }
        """.trimIndent()
        val out = ComposeEmitter.emitStruct(structOf(parseSource(src, "hidden.subl"), "Hidden")).source
        assertFalse(out.contains("AlertDialog("), "visible = false（数据驱动显隐）不应生成对话框：\n" + out)
    }

    @Test
    fun emit_bottom_sheet_maps_to_modal_bottom_sheet() {
        val src = """
            @compose struct Sheet {
                BottomSheet(modifier = "fillMaxWidth") {
                    Text(text = "抽屉内容")
                }
            }
        """.trimIndent()
        val out = ComposeEmitter.emitStruct(structOf(parseSource(src, "sheet.subl"), "Sheet")).source
        assertTrue(out.contains(
            "androidx.compose.material3.ModalBottomSheet(onDismissRequest = { }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.fillMaxWidth()) {"), out)
        assertTrue(out.contains("@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)"), out)
        assertTrue(out.contains("androidx.compose.material3.Text(text = \"抽屉内容\", modifier = Modifier)"), out)
    }

    @Test
    fun emit_empty_view_and_imports() {
        val r = ComposeEmitter.emitStruct(structOf(parse(), "Home"), pkg = "candy.app", imports = listOf("candy.compose.*"))
        assertTrue(r.source.contains("package candy.app"), r.source)
        assertTrue(r.source.contains("import candy.compose.*"), r.source)
        assertTrue(r.source.contains("fun Home() {"), r.source)
        assertFalse(r.usesMaterial3, "无内容视图不应声明 material3 依赖")
        val noPkg = ComposeEmitter.emitStruct(structOf(parse(), "Home"), pkg = "")
        assertFalse(noPkg.source.contains("package "), "空包名 → 默认包（不写 package 行）")
        assertFalse(noPkg.source.contains("import "), "空内容视图不需要自动 import：\n" + noPkg.source)
    }

    // ---------- 3. 端到端：compose.subs 处理器 ----------

    private fun composeProcessor(): ScriptProcessor =
        BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR).first { it.name == "compose" }

    @Test
    fun compose_subs_writes_kt_files() {
        val dir = Files.createTempDirectory("subl-compose").toFile()
        try {
            val ast = parseSource(subSrc, "main.subl")
            val engine = ProcessorEngine(maxRounds = 5)
            engine.addProcessor(composeProcessor())
            val r = engine.run(
                inputAst = ast,
                options = mapOf(
                    "composeOutDir" to dir.absolutePath,
                    "composePackage" to "candy.test",
                    "composeImports" to "candy.compose.*",
                ),
            )
            assertFalse(r.loopDetected, "处理器未收敛")
            val errs = r.diagnostics.filter { it.severity == sugared.functor.checker.Severity.ERROR }
            assertTrue(errs.isEmpty(), "处理器不应报错：\n" + errs.joinToString("\n") { it.code + " " + it.message })
            assertFalse(r.ast != ast, "@compose 处理器不改 AST（引用/结构都应保持）")
            // 每个视图一个 .kt，且形态是语法正确的 Kotlin
            for (name in listOf("Home", "Home2", "Home3")) {
                val f = File(dir, name + ".kt")
                assertTrue(f.isFile, "缺少生成文件 " + f.path)
                val text = f.readText()
                assertTrue(text.contains("package candy.test"), text)
                assertTrue(text.contains("@Composable"), text)
                assertTrue(text.contains("fun " + name + "("), text)
                val opens = text.count { it == '{' }
                val closes = text.count { it == '}' }
                assertEquals(opens, closes, "花括号不配对：\n" + text)
            }
            assertTrue(File(dir, "Home2.kt").readText().contains("androidx.compose.material3.Text("), "Home2 应发射 Text")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun compose_subs_flags_name_clash_with_view() {
        val dir = Files.createTempDirectory("subl-compose-clash").toFile()
        try {
            val src2 = """
                @view struct Box(modifier: Any)
                @compose struct Box
            """.trimIndent()
            val engine = ProcessorEngine(maxRounds = 3)
            engine.addProcessor(composeProcessor())
            val r = engine.run(inputAst = parseSource(src2, "main.subl"), options = mapOf("composeOutDir" to dir.absolutePath))
            assertTrue(r.diagnostics.any { it.severity == sugared.functor.checker.Severity.ERROR },
                "同名 @view 结构体应报错：\n" + r.diagnostics.joinToString("\n") { it.message })
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun view_pipeline_unaffected() {
        // @view 通路仍在：view 处理器装载如常，构造点改写照旧（回归保护）
        val loads = BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR).map { it.name }
        assertTrue("view" in loads && "compose" in loads, loads.toString())
        val engine = ProcessorEngine(maxRounds = 5)
        engine.addProcessor(BuiltinProcessors.loadFrom(BuiltinProcessors.DEFAULT_DIR).first { it.name == "view" })
        val vSrc = """
            @view struct Box(modifier: Any)
            @android @impure
            fun androidRecompose(view: Str, ordinal: Nat, v: Any): Null
            @composable @impure
            fun root(): Any = Box(modifier = 1) {}
        """.trimIndent()
        val r = engine.run(inputAst = parseSource(vSrc, "main.subl"))
        val box = structOf(r.ast, "Box")
        assertTrue(box.fields.any { it.name == "children" }, "view 注入仍在：" + box.fields.map { it.name })
    }

    @Test
    fun collectNodes_nests_trailing_brace_children() {
        val src = """
            @compose struct Home2 {
                Box(orientation = "vertical") {
                    Text(text = "a")
                }
            }
        """.trimIndent()
        val st = structOf(parseSource(src, "main.subl"), "Home2")
        val out = ArrayList<ComposeEmitter.UiNode>()
        ComposeEmitter.collectNodes(st.body!!, out)
        assertEquals(1, out.size)
        assertEquals("Box", out[0].name)
        assertEquals(1, out[0].children.size, "Box 的尾随花括号里的 Text 应成为子节点：" + out[0].children)
        assertEquals("Text", out[0].children[0].name)
    }

    @Test
    fun emit_real_nesting_compiles_shape() {
        val r = ComposeEmitter.emitStruct(structOf(parse(), "Home2"))
        // 嵌套形态（回归：尾随花括号体曾解析不到，导致容器空、子组件丢失）
        assertTrue(r.source.contains("androidx.compose.material3.Text(text = \"你好\", modifier = Modifier)"), r.source)
        assertTrue(r.source.contains("Title(text = \"页脚\", color = \"#80FF0000\")"),
            "跨视图组合保留原实参（非本视图的参数原样透传）：\n" + r.source)
        // 用到的组件全部是 Compose 全限定名（缺依赖即编译报错，不会静默降级）
        assertTrue(r.components.all { it.startsWith("androidx.compose.") }, r.components.toString())
    }
}