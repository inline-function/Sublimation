package sugared.functor.annotationproc

import sugared.functor.ast.*

/**
 * @compose 视图 → Kotlin Compose 源码发射器（SfA「生成 .kt 源码 + Gradle 编译」通路）。
 *
 * 为什么在编译器仓：`.subs` 处理器脚本的 API 只有名字级查询（structNames/structFields…），
 * 拿不到字段默认值表达式、结构体字面量、函数体等表达式 AST，拼不出 Kotlin 源码；
 * 因此遍历与翻译放在框架侧（本文件），脚本只负责编排（选哪些视图、写到哪、报什么诊断）。
 *
 * 一条 @compose 视图声明 → 一个 `@Composable fun <Name>(...)`：
 * ```
 * @compose struct Home
 * @compose struct Title(text: Str)
 * @compose struct Home2 {
 *     Box(orientation = "vertical", modifier = fillWidth().padding(16)) {
 *         Text(text = "你好", color = "#FF6600")
 *     }
 * }
 * @compose struct Home3(path: Str) = Home2()
 * ```
 *
 * 发射形态：
 * - 包：`-A composePackage`，缺省 `app.compose`（空串 = 默认包，不写 package 行）；
 * - 顶层 `@file:Suppress(...)` 只含**语法/未用类**告警（不压制解析级错误：缺依赖必须暴露）；
 * - 组件写 Material3/Foundation **全限定名**（`androidx.compose.material3.Text(...)`，不依赖 import，
 *   缺依赖时编译错误定位在引用处）；`Modifier` / `Color` / `.dp` / `.sp` 等扩展用简名，
 *   由文件头自动收集的 import 兜底（用到才引，不引没用到的东西）；
 * - 视图参数直接进 `@Composable fun` 形参表：**不给默认值**——Sublimation 的来源是真来源，
 *   生成的 Kotlin 不伪造取值语义；要在预览/入口处调用时由工程侧显式传参；
 * - 每个视图在 [ComposeEmitResult.dependencies] 里汇报精确依赖（脚本据此写构建脚本 / 报诊断）。
 *
 * 映射表对齐 SfA《Compose 代码生成契约》（`sublimation for android/android doc/Compose代码生成契约.md`）：
 * - §3 Modifier 链：链式 AST（`width(4).height(20)`）与 MKind 构造子（`MWidth(4)`）都按 §3.2 表折叠，
 *   nat → dp、Rat → Float；链元素全是 Modifier 扩展函数，用到的 import 自动收集；
 * - §5 组件映射：Box→Column/Row/FlowRow（orientation/wrap）、spacing→Arrangement.spacedBy、
 *   arrangement→Arrangement.X、backgroundColor→`.background(Color)`；Text(text/color/size sp/bold/maxLines)；
 *   Button 按 style 选 Button/TextButton/OutlinedButton、label 便捷字段进 content；Card(shape/elevation/colors)；
 *   Icon(name→Icons.Filled.X)；AlertDialog(title/confirm/dismiss slot)；BottomSheet→ModalBottomSheet；
 * - 尚未落地（等 SfA 首页样例交付后细化）：动态状态槽 `DshComposeHost.stateOf`、回调 `callJs`、
 *   scrollable 滚动、容器 onClick clickable、flash 动画、FloatingButton/TextField/Chart 等。
 *
 * AST 形态约定：@compose 结构体的 `{ … }` 块体是**调用序列**（`Text(text = "x")` 的语法糖，
 * 解析为 `CallExpr(StructCtorExpr(Text, assigns), [])`）；`= 表达式` 是单表达式视图。
 */
object ComposeEmitter {

    /**
     * 发射结果：
     * [source] 整份 .kt 文本；
     * [components] 用到的组件（FQN，声明序）；
     * [dependencies] 精确依赖坐标（`runtime` 恒有；用到 Material3 时再加 material3）。
     */
    data class ComposeEmitResult(
        val view: String,
        val source: String,
        val components: List<String>,
        val dependencies: List<String>,
    ) {
        val usesMaterial3: Boolean get() = components.any { it.startsWith("androidx.compose.material3.") }
    }

    /** Compose 运行时坐标（@Composable 注解来自这里）。 */
    const val RUNTIME_DEPENDENCY = "androidx.compose.runtime:runtime:1.6.0"
    /** Material3 坐标（Text/Button/Card… 组件来自这里）。 */
    const val MATERIAL3_DEPENDENCY = "androidx.compose.material3:material3:1.2.0"

    /** 简名引用、由文件头自动收集的 import（用到才引）。 */
    private const val MODIFIER_IMPORT = "androidx.compose.ui.Modifier"
    private const val COLOR_IMPORT = "androidx.compose.ui.graphics.Color"
    private const val DP_IMPORT = "androidx.compose.ui.unit.dp"
    private const val SP_IMPORT = "androidx.compose.ui.unit.sp"

    /** Material3 ColorScheme 槽位名（主题色别名 primary/onSurfaceVariant… 的合法目标）。 */
    private val M3_COLOR_SLOTS = setOf(
        "primary", "onPrimary", "primaryContainer", "onPrimaryContainer",
        "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
        "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
        "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
        "surfaceTint", "inverseSurface", "inverseOnSurface", "inversePrimary",
        "error", "onError", "errorContainer", "onErrorContainer", "outline", "outlineVariant", "scrim",
    )

    /** Sublimation 类型 → Kotlin 形参类型。 */
    internal fun kotlinTypeOf(t: Type?): String = when (t) {
        null -> "Any?"
        is NamedType -> when (t.name) {
            "Str" -> "String"
            "Nat", "Int" -> "Int"
            "Rat" -> "Double"
            "Bool" -> "Boolean"
            "Any" -> "Any?"
            "Null" -> "Unit"
            else -> t.name
        }
        is FunType -> "(" + t.params.joinToString(", ") { kotlinTypeOf(it) } + ") -> " + kotlinTypeOf(t.ret)
        is MutType -> kotlinTypeOf(t.inner)
        is AtomicType -> kotlinTypeOf(t.inner)
        is QualifiedType -> t.name
        else -> "Any?"
    }

    /** Sublimation 字符串 → Kotlin 字符串字面量（转义反斜杠/引号/美元符 + 换行）。 */
    internal fun kotlinString(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when (ch) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '$' -> sb.append("\\$")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    /** 表达式 → Kotlin 表达式文本（值级子集；不认识的节点落 null，可编译优先）。 */
    internal fun exprToKotlin(e: Expr?): String = when (e) {
        null -> "null"
        is IntLit -> e.value
        is FloatLit -> e.value
        is StrLit -> kotlinString(e.value)
        is NameRef -> when (e.name) {
            "true" -> "true"
            "false" -> "false"
            "Nil" -> "emptyList<Any>()"
            else -> e.name
        }
        is BinExpr -> "(" + exprToKotlin(e.left) + " " + e.op + " " + exprToKotlin(e.right) + ")"
        is UniExpr -> "(" + e.op + exprToKotlin(e.operand) + ")"
        is CallExpr -> {
            val callee = (e.callee as? NameRef)?.name
            if (callee == null) "null"
            else if (callee == "listOf") e.args.joinToString(", ", "listOf(", ")") { exprToKotlin(it) }
            else callee + e.args.joinToString(", ", "(", ")") { exprToKotlin(it) }
        }
        is TupleExpr -> e.items.joinToString(", ", "(", ")") { exprToKotlin(it) }
        else -> "null"
    }

    /** 颜色字面量 `#RGB` / `#RRGGBB` / `#AARRGGBB` → `Color(0xAARRGGBB)`；不认识返回 null。 */
    internal fun colorLiteral(raw: String): String? {
        val s = raw.trim()
        if (!s.startsWith("#")) return null
        val digits = s.substring(1).uppercase()
        if (digits.isEmpty() || digits.any { it !in "0123456789ABCDEF" }) return null
        return when (digits.length) {
            3 -> "Color(0xFF" + digits.map { it.toString() + it }.joinToString("") + ")"
            6 -> "Color(0xFF" + digits + ")"
            8 -> "Color(0x" + digits + ")"
            else -> null
        }
    }

    /**
     * 值 → Kotlin 颜色表达式（契约 §4.1/§5）：
     * `#hex` → `Color(0x…)`；主题色名（`"primary"` 字面量或 `primary()` 主题函数调用）
     * → `MaterialTheme.colorScheme.<槽位>`；其余返回 null（交回调用方决定）。
     */
    internal fun colorOrExpr(e: Expr?): String? = when (e) {
        is StrLit -> colorLiteral(e.value) ?: themeColor(e.value.trim())
        is CallExpr -> (e.callee as? NameRef)?.name?.let { themeColor(it) }
        else -> null
    }

    /** 主题色名 → `MaterialTheme.colorScheme.<槽位>`；非 M3 槽位名返回 null。 */
    private fun themeColor(name: String): String? =
        if (name in M3_COLOR_SLOTS) "MaterialTheme.colorScheme." + name else null

    /**
     * 发射一个 @compose 视图结构体。
     *
     * @param pkg 生成文件的包名（空串 = 默认包，不写 package 行）
     * @param imports 额外 import 的 FQN（SfA 宿主工程需要的 candy.compose.* 等）
     * @param optIns 额外 @file:OptIn 的注解 FQN（缺省按用到的实验 API 自动收集）
     */
    fun emitStruct(
        st: StructDecl,
        pkg: String = "app.compose",
        imports: List<String> = emptyList(),
        optIns: List<String> = emptyList(),
    ): ComposeEmitResult {
        val params = st.fields.filter { it.name !in setOf("children", "recomposion", "__ord") }
        val nodes = ArrayList<UiNode>()
        st.body?.let { collectNodes(it, nodes) }

        val components = LinkedHashSet<String>()
        val autoImports = LinkedHashSet<String>()

        // 函数体先行发射（组件全限定名；扩展类 API 简名 + 自动 import 收集），
        // 头部的 import 段才知道该引什么——用到才引，不引没用到的东西。
        val body = StringBuilder()
        if (nodes.isEmpty()) body.append("    // 视图 ").append(st.name).append(" 未声明内容\n")
        emitNodes(body, "    ", nodes, components, autoImports)

        val sb = StringBuilder()
        sb.append("// 由 Sublimation 编译器 @compose 处理器生成，请勿手改。\n")
        // 文件级注解：抑制未用类告警；@file:OptIn 只针对**用到的**实验 API——不引用没声明的依赖。
        sb.append("@file:Suppress(\"unused\", \"unused_variable\", \"UNUSED_PARAMETER\", \"UNUSED_VARIABLE\", \"UNUSED_EXPRESSION\", \"UNUSED_VALUE\")\n")
        val optInSet = LinkedHashSet<String>()
        scanOptIns(nodes, optInSet)
        val optInList = optInSet.toList().plus(optIns).distinct()
        if (optInList.isNotEmpty())
            sb.append("@file:OptIn(").append(optInList.joinToString(", ") { it + "::class" }).append(")\n")
        sb.append("\n")
        if (pkg.isNotEmpty()) sb.append("package ").append(pkg).append("\n\n")
        // import：框架自动收集（Modifier/Color/dp/sp/链元素扩展…）+ 脚本传入的额外 import；
        // 支持 "a.b.*"、"a.b.C" 或整行 "import a.b.C"，以 # 开头的条目原样透传（工程指令）。
        val importLines = LinkedHashSet<String>()
        for (fqn in autoImports) importLines += "import " + fqn
        for (imp in imports.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            importLines += if (imp.startsWith("import ")) imp else "import " + imp
        }
        for (line in importLines) sb.append(line).append("\n")
        if (importLines.isNotEmpty()) sb.append("\n")

        sb.append("@Composable\n")
        sb.append("fun ").append(st.name).append("(")
        sb.append(params.joinToString(", ") { p -> p.name + ": " + kotlinTypeOf(p.type) })
        sb.append(") {\n")
        sb.append(body)
        sb.append("}\n")

        val deps = ArrayList<String>()
        deps += RUNTIME_DEPENDENCY
        if (components.any { it.startsWith("androidx.compose.material3.") }) deps += MATERIAL3_DEPENDENCY
        return ComposeEmitResult(st.name, sb.toString(), components.toList(), deps)
    }

    /** 递归登记节点用到的实验 API（AlertDialog/TopAppBar/BottomSheet→M3 实验 API；Box wrap→FlowRow 实验 API）。 */
    private fun scanOptIns(nodes: List<UiNode>, out: MutableSet<String>) {
        for (n in nodes) {
            experimentalOptIn(n.name)?.let { out += it }
            if (n.name == "Box" && boolOf(n.assigns.firstOrNull { it.first == "wrap" }?.second) == true)
                out += FLOW_ROW_OPT_IN
            scanOptIns(n.children, out)
        }
    }

    /** UI 节点：组件名 + 命名实参（按源码序）+ 花括号体内的子节点。 */
    internal data class UiNode(
        val name: String,
        val assigns: List<Pair<String, Expr>>,
        val children: List<UiNode>,
    )

    /** 收集「调用序列」为 UI 树：尾随花括号体（parser 包成 LambdaExpr）里的语句成为 children。 */
    internal fun collectNodes(e: Expr, out: MutableList<UiNode>) {
        when (e) {
            is BlockExpr -> e.stmts.forEach { s -> if (s is ExprStmt) collectNodes(s.expr, out) }
            is LambdaExpr -> collectNodes(e.body, out)
            is CallExpr -> {
                val ctor = e.callee as? StructCtorExpr
                val kids = ArrayList<UiNode>()
                e.args.forEach { a -> collectNodes(a, kids) }
                if (ctor != null) out += UiNode(ctor.struct, ctor.assigns, kids)
                else (e.callee as? NameRef)?.let { nm -> out += UiNode(nm.name, e.namedArgs.toList(), kids) }
            }
            is StructCtorExpr -> out += UiNode(e.struct, e.assigns, emptyList())
            else -> {}
        }
    }

    /** 组件 FQN：布局容器在 foundation.layout，其余在 material3；非组件（其它视图）返回 null。 */
    private fun componentFqn(name: String): String? = when (name) {
        "Box", "Column", "Row" -> "androidx.compose.foundation.layout." + name
        // BottomSheet 的 Material3 组件名是 ModalBottomSheet（BottomSheet 只是 Sublimation 侧的视图名）
        "BottomSheet" -> "androidx.compose.material3.ModalBottomSheet"
        "Text", "Icon", "Divider", "Button", "TextButton", "OutlinedButton", "Card",
        "LazyColumn", "LazyRow", "AlertDialog", "TopAppBar" -> "androidx.compose.material3." + name
        else -> null
    }

    /** 组件用到的实验 API 注解（需要 @file:OptIn）；稳定 API 返回 null（不伪造依赖）。 */
    private fun experimentalOptIn(name: String): String? = when (name) {
        "AlertDialog", "TopAppBar", "BottomSheet" -> "androidx.compose.material3.ExperimentalMaterial3Api"
        else -> null
    }

    /** FlowRow 的实验 API（Box(wrap = true) 生成 FlowRow 时需要）。 */
    private const val FLOW_ROW_OPT_IN = "androidx.compose.foundation.layout.ExperimentalLayoutApi"

    /** Bool 字面量（true/false 在 AST 里是 NameRef）→ Boolean；其余 null。 */
    private fun boolOf(e: Expr?): Boolean? = (e as? NameRef)?.let {
        when (it.name) { "true" -> true; "false" -> false; else -> null }
    }

    /** modifier 兜底 Modifier（兜底也是简名引用，同样要 import）。 */
    private fun modifierOr(mod: String?, imports: MutableSet<String>): String {
        imports += MODIFIER_IMPORT
        return mod ?: "Modifier"
    }

    /** 递归发射一组 UI 节点（源码序 + 缩进）。 */
    private fun emitNodes(
        sb: StringBuilder, ind: String, nodes: List<UiNode>, components: MutableSet<String>,
        imports: MutableSet<String>,
    ) {
        for (n in nodes) {
            val fqn = componentFqn(n.name)
            if (fqn == null) {
                // 其它 @compose 视图（跨视图组合）：按视图函数调用，命名实参原样透传
                sb.append(ind).append(n.name).append(n.assigns.joinToString(", ", "(", ")") {
                    it.first + " = " + exprToKotlin(it.second)
                }).append("\n")
                continue
            }
            components += fqn
            val a = LinkedHashMap<String, Expr>()
            for ((k, v) in n.assigns) a[k] = v
            val mod = modifierChain(a["modifier"] ?: a["modifiers"], imports)
            if (mod != null) imports += MODIFIER_IMPORT
            val color = colorOrExpr(a["color"] ?: a["tint"])
            when (n.name) {
                "Box" -> {
                    // SfA Box stdl：orientation vertical→Column / horizontal→Row（缺省 vertical）；
                    // wrap=true → FlowRow（流式换行）；spacing→Arrangement.spacedBy；arrangement→Arrangement.X；
                    // backgroundColor → 链尾 .background(Color)。
                    val o = ((a["orientation"] as? StrLit)?.value ?: "vertical").trim().lowercase()
                    val wrap = boolOf(a["wrap"]) == true
                    val fn0 = if (wrap) "FlowRow" else if (o.startsWith("h") || o == "row") "Row" else "Column"
                    val fn = "androidx.compose.foundation.layout." + fn0
                    components += fn
                    val horizontal = fn0 == "Row" || fn0 == "FlowRow"
                    val arr = arrangementArg((a["arrangement"] as? StrLit)?.value ?: "", a["spacing"], horizontal)
                    val bg = colorOrExpr(a["backgroundColor"])
                    var fullMod = mod
                    if (bg != null) {
                        imports += "androidx.compose.foundation.background"
                        if (bg.startsWith("Color(")) imports += COLOR_IMPORT
                        fullMod = modifierOr(mod, imports) + ".background(" + bg + ")"
                    }
                    sb.append(ind).append(fn).append("(")
                    if (arr != null) sb.append(arr)
                    if (fullMod != null) {
                        if (arr != null) sb.append(", ")
                        sb.append("modifier = ").append(fullMod)
                    }
                    sb.append(") {\n")
                    if (n.children.isEmpty()) sb.append(ind).append("    // 空容器\n")
                    else emitNodes(sb, ind + "    ", n.children, components, imports)
                    sb.append(ind).append("}\n")
                }
                "Card" -> {
                    // 契约 §5：圆角/阴影/背景色真值——shape/elevation/colors 走命名实参（CardDefaults 是对象，FQN 直引）。
                    val bg = colorOrExpr(a["backgroundColor"])
                    if (bg != null) imports += COLOR_IMPORT
                    val corner = (a["cornerRadius"] as? IntLit)?.value
                    val elev = (a["elevation"] as? IntLit)?.value
                    sb.append(ind).append(fqn).append("(")
                    var first = true
                    fun arg(text: String) { if (!first) sb.append(", "); sb.append(text); first = false }
                    if (bg != null) arg("colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = " + bg + ")")
                    if (corner != null) { imports += DP_IMPORT; arg("shape = androidx.compose.foundation.shape.RoundedCornerShape(" + corner + ".dp)") }
                    if (elev != null) { imports += DP_IMPORT; arg("elevation = androidx.compose.material3.CardDefaults.cardElevation(" + elev + ".dp)") }
                    if (mod != null) arg("modifier = " + mod)
                    sb.append(") {\n")
                    if (n.children.isEmpty()) sb.append(ind).append("    // 空容器\n")
                    else emitNodes(sb, ind + "    ", n.children, components, imports)
                    sb.append(ind).append("}\n")
                }
                "Button", "TextButton" -> {
                    // SfA Button stdl：style="text"→TextButton / "outlined"→OutlinedButton / 其余 Button；
                    // label 是便捷字段——body 为空时直接显示 label；enabled=false 透传。
                    val style = if (n.name == "Button") ((a["style"] as? StrLit)?.value ?: "filled").trim().lowercase() else "filled"
                    val fn = when (style) {
                        "text" -> "androidx.compose.material3.TextButton"
                        "outlined" -> "androidx.compose.material3.OutlinedButton"
                        else -> fqn
                    }
                    if (fn != fqn) components += fn
                    val label = (a["label"] ?: a["text"]) as? StrLit
                    sb.append(ind).append(fn).append("(onClick = { }")
                    if (mod != null) sb.append(", modifier = ").append(mod)
                    if (boolOf(a["enabled"]) == false) sb.append(", enabled = false")
                    sb.append(") {\n")
                    if (n.children.isEmpty() && label != null && label.value.isNotEmpty()) {
                        // label 便捷字段：content slot 放一个 Text
                        sb.append(ind).append("    ").append("androidx.compose.material3.Text(text = ").append(exprToKotlin(label)).append(")\n")
                        components += "androidx.compose.material3.Text"
                    } else if (n.children.isEmpty()) sb.append(ind).append("    // 空容器\n")
                    else emitNodes(sb, ind + "    ", n.children, components, imports)
                    sb.append(ind).append("}\n")
                }
                "BottomSheet" -> {
                    // 契约 §5：ModalBottomSheet + skipPartiallyExpanded 的 sheetState；visible 字面 false → 不生成。
                    if (visibleSkip(a, sb, ind)) continue
                    sb.append(ind).append(fqn)
                        .append("(onDismissRequest = { }, sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)")
                    if (mod != null) sb.append(", modifier = ").append(mod)
                    sb.append(") {\n")
                    if (n.children.isEmpty()) sb.append(ind).append("    // 空抽屉\n")
                    else emitNodes(sb, ind + "    ", n.children, components, imports)
                    sb.append(ind).append("}\n")
                }
                "LazyColumn", "LazyRow" -> {
                    // LazyListScope 的位是 `item { }` / `items(n) { }`，不能直接放兄弟组件；
                    // 首版：子节点按「声明位」注释记录（契约细化后接数据源），运行时为空列表。
                    sb.append(ind).append(fqn).append("(")
                    if (mod != null) sb.append("modifier = ").append(mod)
                    sb.append(") {\n")
                    if (n.children.isEmpty()) sb.append(ind).append("    // 空列表\n")
                    else sb.append(ind).append("    // 列表子项（count = ").append(n.children.size).append("）：SfA 契约细化的数据来源\n")
                    sb.append(ind).append("}\n")
                }
                "Text" -> {
                    // SfA Text stdl：text/color/#hex 或主题色名；size（sp）/bold/maxLines 透传。
                    sb.append(ind).append(fqn).append("(text = ")
                        .append(exprToKotlin(a["text"] ?: a["label"] ?: a["title"] ?: a["content"]))
                    sb.append(", modifier = ").append(modifierOr(mod, imports))
                    if (color != null) { sb.append(", color = ").append(color); imports += COLOR_IMPORT }
                    val size = (a["size"] as? FloatLit)?.value?.toDoubleOrNull()
                        ?: (a["size"] as? IntLit)?.value?.toDoubleOrNull()
                    if (size != null && size > 0) { imports += SP_IMPORT; sb.append(", fontSize = ").append(size).append(".sp") }
                    if (boolOf(a["bold"]) == true) sb.append(", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold")
                    if (boolOf(a["italic"]) == true) sb.append(", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic")
                    val maxLines = (a["maxLines"] as? IntLit)?.value?.toIntOrNull()
                    if (maxLines != null && maxLines > 0) sb.append(", maxLines = ").append(maxLines)
                    sb.append(")\n")
                }
                "Icon" -> {
                    // SfA Icon stdl：name 是内置矢量图标名（material-icons-core 子集）→ Icons.Filled.<首字母大写>；
                    // 未知名按契约回退 Icons.Filled.Circle；非默认 size 进 modifier。
                    val iconName = ((a["name"] ?: a["icon"] ?: a["imageVector"]) as? StrLit)?.value?.trim() ?: ""
                    val icon = if (iconName.isNotEmpty()) iconName.replaceFirstChar { it.uppercase() } else "Circle"
                    imports += "androidx.compose.material.icons.filled." + icon
                    val isize = (a["size"] as? IntLit)?.value?.toIntOrNull()
                    var imod = modifierOr(mod, imports)
                    if (isize != null && isize != 24) {
                        imports += "androidx.compose.foundation.layout.size"; imports += DP_IMPORT
                        imod += ".size(" + isize + ".dp)"
                    }
                    sb.append(ind).append(fqn).append("(imageVector = androidx.compose.material.icons.Icons.Filled." + icon)
                        .append(", contentDescription = ").append(exprToKotlin(a["contentDescription"]))
                        .append(", modifier = ").append(imod)
                    if (color != null) { sb.append(", tint = ").append(color); imports += COLOR_IMPORT }
                    sb.append(")\n")
                }
                "Divider" -> {
                    sb.append(ind).append(fqn).append("(")
                    if (mod != null) sb.append("modifier = ").append(mod)
                    sb.append(")\n")
                }
                "AlertDialog" -> {
                    // 契约 §5：title/confirmText/dismissText 是 slot——字符串文案包一层 Text；visible 字面 false → 不生成。
                    if (visibleSkip(a, sb, ind)) continue
                    sb.append(ind).append(fqn).append("(onDismissRequest = { }")
                    fun slot(nameArg: String, expr: Expr?) {
                        val s = expr as? StrLit ?: return
                        if (s.value.isEmpty()) return
                        sb.append(", ").append(nameArg).append(" = { androidx.compose.material3.Text(text = ").append(exprToKotlin(s)).append(") }")
                        components += "androidx.compose.material3.Text"
                    }
                    slot("title", a["title"])
                    // confirmButton 是必填 slot：给了文案放 Text，没给空 slot 顶着（命名实参形态）
                    sb.append(", confirmButton = { ")
                    (a["confirmText"] as? StrLit)?.takeIf { it.value.isNotEmpty() }?.let {
                        sb.append("androidx.compose.material3.Text(text = ").append(exprToKotlin(it)).append(") ")
                        components += "androidx.compose.material3.Text"
                    }
                    sb.append("}")
                    slot("dismissButton", a["dismissText"])
                    if (n.children.isNotEmpty()) {
                        sb.append(", text = {\n")
                        emitNodes(sb, ind + "    ", n.children, components, imports)
                        sb.append(ind).append("    }\n")
                        sb.append(ind).append(")\n")
                    } else sb.append(")\n")
                }
                else -> {
                    // TopAppBar 等需要 slot 的组件：标题 slot 占位（文件头已按需 @file:OptIn 实验 API）。
                    sb.append(ind).append(fqn).append("(title = { })\n")
                }
            }
        }
    }

    /** visible 字面 false → 节点不生成（数据驱动显隐，契约 §5）；动态值注释提示（状态槽待桥侧细化）。 */
    private fun visibleSkip(a: Map<String, Expr>, sb: StringBuilder, ind: String): Boolean {
        val v = a["visible"] ?: return false
        val b = boolOf(v)
        if (b == false) {
            sb.append(ind).append("// 隐藏（visible = false，数据驱动显隐）\n")
            return true
        }
        if (b == null) sb.append(ind).append("// visible 为动态值（").append(exprToKotlin(v))
            .append("）：显隐待 DshComposeHost.stateOf 桥细化\n")
        return false
    }

    /** Box 主轴排列（SfA Box stdl）：arrangement 非默认 → Arrangement.X；否则 spacing>0 → spacedBy(n.dp)。 */
    private fun arrangementArg(arrangement: String, spacing: Expr?, horizontal: Boolean): String? {
        val kind = if (horizontal) "horizontalArrangement" else "verticalArrangement"
        val arrKt = when (arrangement.trim()) {
            "Center" -> "Arrangement.Center"
            "End" -> "Arrangement.End"
            "SpaceBetween" -> "Arrangement.SpaceBetween"
            "SpaceAround" -> "Arrangement.SpaceAround"
            "SpaceEvenly" -> "Arrangement.SpaceEvenly"
            else -> null
        }
        // Arrangement 是 foundation.layout 的对象，成员 spacedBy/SpaceBetween 可全限定直引
        if (arrKt != null) return kind + " = androidx.compose.foundation.layout." + arrKt
        val n = (spacing as? IntLit)?.value?.toIntOrNull() ?: return null
        if (n <= 0) return null
        return kind + " = androidx.compose.foundation.layout.Arrangement.spacedBy(" + n + ".dp)"
    }

    /**
     * modifier 值 → `Modifier.fillMaxWidth().padding(...)…` 链（SfA 契约 §3，编译期折叠、不求值）。
     *
     * 支持的 AST 形态（@compose 体里写出来的链）：
     * - 链式调用：`width(4).height(20)`（`CallExpr(FieldExpr(...))`——SfA stdl 的链式重载）；
     * - 单元素：`fillWidth()`（链函数名）或 `MFillWidth()`（MKind 构造子，M 前缀按 §3.2 表消解）；
     * - 组合：`one(MWidth(4))` / `chain(a, b)` / `MChain(...)` / `Cons(h, t)` / `listOf(...)` / `+` 拼接 / 块；
     * - Str 简写：`"fillMaxWidth"` / `"padding(8)"` 原样透传（低级逃逸口，import 自负）。
     * 不认识的元素跳过（如 flash——动画最小集待 SfA 桥侧细化）。
     *
     * 链元素全是 Modifier 的**扩展函数**，用到的 import 记进 [imports]（缺 import 不能编译）。
     */
    internal fun modifierChain(e: Expr?, imports: MutableSet<String>? = null): String? {
        if (e == null) return null
        val parts = ArrayList<String>()
        fun elem(name: String, args: List<Expr>) {
            modElemToKotlin(name, args, imports)?.let { parts += it }
        }
        fun one(x: Expr) {
            when (x) {
                is StrLit -> {
                    shorthandModifier(x.value)?.let { parts += it }
                    shorthandImport(x.value)?.let { imports?.plusAssign(it) }
                }
                is CallExpr -> when (val callee = x.callee) {
                    is FieldExpr -> { one(callee.target); elem(callee.name, x.args) }
                    is NameRef -> when (callee.name) {
                        "listOf", "chain", "MChain", "Cons", "one", "emptyList", "Nil" -> x.args.forEach { one(it) }
                        else -> elem(callee.name, x.args)
                    }
                    else -> {}
                }
                is NameRef -> if (x.name != "Modifier" && x.name != "Nil") parts += x.name
                is BinExpr -> if (x.op == "+") { one(x.left); one(x.right) }
                is TupleExpr -> x.items.forEach { one(it) }
                is BlockExpr -> x.stmts.forEach { s -> if (s is ExprStmt) one(s.expr) }
                else -> {}
            }
        }
        when (e) {
            is BlockExpr -> e.stmts.forEach { s -> if (s is ExprStmt) one(s.expr) }
            else -> one(e)
        }
        if (parts.isEmpty()) return null
        return "Modifier." + parts.joinToString(".")
    }

    /**
     * 链元素（SfA stdl 链函数名 / MKind 构造子名）→ Kotlin Modifier 扩展调用（契约 §3.2 表）。
     * nat → dp（同尺度）、Rat → Float字面量；返回 null 的元素跳过。
     */
    private fun modElemToKotlin(name: String, args: List<Expr>, imports: MutableSet<String>?): String? {
        // MKind 构造子（M 前缀，如 MWidth）消解为链函数名
        val n = if (name.length > 1 && name.startsWith("M"))
            name.substring(1).replaceFirstChar { it.lowercase() } else name
        fun dp(e: Expr): String { imports?.plusAssign(DP_IMPORT); return exprToKotlin(e) + ".dp" }
        fun fl(e: Expr): String {
            val lit = (e as? FloatLit)?.value ?: (e as? IntLit)?.value
            return if (lit != null) lit + "f" else exprToKotlin(e)
        }
        val out = when (n) {
            // 尺寸（dp）：SfA nat 与 Compose dp 同尺度
            "width" -> args.firstOrNull()?.let { "width(" + dp(it) + ")" }
            "height" -> args.firstOrNull()?.let { "height(" + dp(it) + ")" }
            "fillWidth", "fillMaxWidth" -> "fillMaxWidth()"
            "fillHeight", "fillMaxHeight" -> "fillMaxHeight()"
            "fillMaxSize" -> "fillMaxSize()"
            "wrapContent" -> "wrapContentSize()"
            // 内边距
            "padding" -> args.firstOrNull()?.let { "padding(" + dp(it) + ")" }
            "paddingH" -> args.firstOrNull()?.let { "padding(horizontal = " + dp(it) + ")" }
            "paddingV" -> args.firstOrNull()?.let { "padding(vertical = " + dp(it) + ")" }
            "paddingHV" -> {
                val h = args.getOrNull(0); val v = args.getOrNull(1)
                if (h != null && v != null) "padding(horizontal = " + dp(h) + ", vertical = " + dp(v) + ")" else null
            }
            "padding4" -> {
                val l = args.getOrNull(0); val t = args.getOrNull(1)
                val r = args.getOrNull(2); val b = args.getOrNull(3)
                if (l != null && t != null && r != null && b != null)
                    "padding(start = " + dp(l) + ", top = " + dp(t) + ", end = " + dp(r) + ", bottom = " + dp(b) + ")" else null
            }
            // 比例填充（Rat → Float）
            "fractionWidth" -> args.firstOrNull()?.let { "fillMaxWidth(" + fl(it) + ")" }
            "fractionHeight" -> args.firstOrNull()?.let { "fillMaxHeight(" + fl(it) + ")" }
            // 弹性权重（RowScope/ColumnScope 成员，无需 import；仅线性容器直接子级有效）
            "weight" -> args.firstOrNull()?.let { "weight(" + fl(it) + ")" }
            // 外观
            "background" -> args.firstOrNull()?.let {
                val c = colorOrExpr(it) ?: exprToKotlin(it)
                if (c.startsWith("Color(")) imports?.plusAssign(COLOR_IMPORT)
                "background(" + c + ")"
            }
            "clip" -> args.firstOrNull()?.let { "clip(androidx.compose.foundation.shape.RoundedCornerShape(" + dp(it) + "))" }
            "alpha" -> args.firstOrNull()?.let { "alpha(" + fl(it) + ")" }
            else -> null   // MPlain/flash（动画最小集）/未知元素：跳过
        } ?: return null
        // 链元素是 Modifier 扩展函数：按用到的元素记 import（缺 import 不能编译）
        val imp = when (n) {
            "background" -> "androidx.compose.foundation.background"
            "alpha" -> "androidx.compose.ui.draw.alpha"
            "clip" -> "androidx.compose.ui.draw.clip"
            "weight" -> null   // RowScope/ColumnScope 成员
            "wrapContent" -> "androidx.compose.foundation.layout.wrapContentSize"
            "fractionWidth" -> "androidx.compose.foundation.layout.fillMaxWidth"
            "fractionHeight" -> "androidx.compose.foundation.layout.fillMaxHeight"
            "paddingHV", "padding4", "paddingH", "paddingV", "padding" -> "androidx.compose.foundation.layout.padding"
            "width", "height" -> "androidx.compose.foundation.layout." + n
            "fillWidth", "fillMaxWidth" -> "androidx.compose.foundation.layout.fillMaxWidth"
            "fillHeight", "fillMaxHeight" -> "androidx.compose.foundation.layout.fillMaxHeight"
            "fillMaxSize" -> "androidx.compose.foundation.layout.fillMaxSize"
            else -> null
        }
        if (imp != null) imports?.plusAssign(imp)
        return out
    }

    /** 简写 modifier 字符串：`fill`/`fillMaxWidth` → fillMaxWidth()；其余原样；空 → null。 */
    private fun shorthandModifier(s: String): String? = when (s.trim()) {
        "" -> null
        "fill", "fillMaxWidth" -> "fillMaxWidth()"
        "fillMaxHeight" -> "fillMaxHeight()"
        "fillMaxSize" -> "fillMaxSize()"
        else -> s.trim()
    }

    /** 已知简写的 import（其余简写串原样透传，import 由书写者负责——低级逃逸口）。 */
    private fun shorthandImport(s: String): String? = when (s.trim()) {
        "fill", "fillMaxWidth" -> "androidx.compose.foundation.layout.fillMaxWidth"
        "fillMaxHeight" -> "androidx.compose.foundation.layout.fillMaxHeight"
        "fillMaxSize" -> "androidx.compose.foundation.layout.fillMaxSize"
        else -> null
    }

    /** 发射结果摘要（供 processorInfo 汇报）：`<view>|<组件数>|<组件 FQN 逗号表>|<依赖逗号表>`。 */
    fun describe(r: ComposeEmitResult): String =
        r.view + "|" + r.components.size + "|" + r.components.joinToString(",") + "|" + r.dependencies.joinToString(",")
}
