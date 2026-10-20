package sugared.functor.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * LSP 桥接层 + JSON-RPC 基础单元测试。
 * 覆盖：诊断转换 / 符号收集 / hover / definition / completion / documentSymbol / Json 往返。
 */
class LspAnalysisTest {

    // 工程模式与脚本模式共用：core 目录（内建容器/构造子等）。Gradle 测试 cwd 为模块目录，需回退到仓库根。
    private val coreDir: java.io.File? = listOf(java.io.File("core"), java.io.File("../core"))
        .firstOrNull { it.isDirectory }

    private fun opened(src: String): LspAnalysis {
        val a = LspAnalysis()
        if (coreDir != null) a.loadPrelude(coreDir)
        a.openDocument("file:///t.subl", src)
        return a
    }

    private fun openedSubs(src: String): LspAnalysis {
        val a = LspAnalysis()
        a.openDocument("file:///t.subs", src)
        return a
    }

    // ───────── JSON 序列化往返 ─────────

    @Test fun `Json 序列化基本类型`() {
        assertEquals("null", Json.stringify(null))
        assertEquals("true", Json.stringify(true))
        assertEquals("42", Json.stringify(42))
        assertEquals("\"hi\"", Json.stringify("hi"))
    }

    @Test fun `Json 序列化转义`() {
        assertEquals("\"a\\nb\"", Json.stringify("a\nb"))
        assertEquals("\"\u4f60\u597d\"", Json.stringify("你好"))
    }

    @Test fun `Json 序列化对象与数组`() {
        assertEquals("{\"a\":1}", Json.stringify(mapOf("a" to 1)))
        assertEquals("[1,2,3]", Json.stringify(listOf(1, 2, 3)))
    }

    @Test fun `Json 解析往返`() {
        val parsed = Json.parse("{\"method\":\"init\",\"id\":1,\"ok\":true}")
        assertTrue(parsed is Map<*, *>)
        val map = parsed as Map<*, *>
        assertEquals("init", map["method"])
        assertEquals(1L, map["id"])
        assertEquals(true, map["ok"])
    }

    // ───────── 诊断转换 ─────────

    @Test fun `诊断转换为 LSP Diagnostic`() {
        val a = opened("fun f(): Nat = \"s\"") // E-TYPE-MISMATCH
        val diags = a.diagnosticsFor("file:///t.subl")
        assertTrue(diags.any { it.code == "E-TYPE-MISMATCH" })
        val d = diags.first { it.code == "E-TYPE-MISMATCH" }
        assertEquals(DiagnosticSeverity.Error, d.severity)
        assertNotNull(d.message)
    }

    @Test fun `无错误源码零诊断`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        val diags = a.diagnosticsFor("file:///t.subl")
        assertTrue(diags.none { it.severity == DiagnosticSeverity.Error }, "应无 error 级诊断，实际: \$diags")
    }

    @Test fun `词法错误进入诊断`() {
        val a = opened("var s = \"hi") // 未闭合字符串
        val diags = a.diagnosticsFor("file:///t.subl")
        assertTrue(diags.any { it.code == "E-LEX" }, "应有 E-LEX 诊断")
    }

    // ───────── 符号收集 ─────────

    @Test fun `收集顶层声明符号`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b\nstruct User(name: Str)")
        val syms = a.collectSymbols("file:///t.subl")
        val names = syms.map { it.name }
        assertTrue("add" in names)
        assertTrue("User" in names)
        val add = syms.first { it.name == "add" }
        assertEquals("fun", add.variety)
        assertTrue(add.detail.contains("Nat"), "detail 应含返回类型")
    }

    // ───────── hover ─────────

    @Test fun `hover 显示函数签名`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        // "add" 位于第 1 行第 5 列（0-based: line 0, char 4）
        val hover = a.hoverFor("file:///t.subl", Position(0, 4))
        assertNotNull(hover)
        val content = hover.contents as? MarkupContent
        assertNotNull(content)
        assertTrue(content.value.contains("add"), "hover 应含函数名")
    }

    @Test fun `hover 关键字给出文档`() {
        val a = opened("fun f(): Nat = 1")
        val hover = a.hoverFor("file:///t.subl", Position(0, 0))
        assertNotNull(hover)
        // T2：断言 value 里确实是 fun 的关键字文档，而非仅非空
        val content = hover.contents as? MarkupContent
        assertNotNull(content)
        assertTrue(content.value.contains("declare a function"), "hover 应含 fun 关键字文档，实际: " + content.value)
    }

    // ───────── definition ─────────

    @Test fun `definition 跳转到声明`() {
        val a = opened("fun add(a: Nat): Nat = a")
        val loc = a.definitionFor("file:///t.subl", Position(0, 4))
        assertNotNull(loc)
        assertEquals("file:///t.subl", loc.uri)
    }

    // ───────── typeDefinition ─────────

    @Test fun `typeDefinition 跳到类型声明`() {
        val src = "struct User(name: Str)\nfun main() { var x: User = User(\"a\") }"
        val a = opened(src)
        // T1：动态算第 2 行类型标注 User 的列，避免硬编码
        val lineStart = src.indexOf('\n') + 1
        val col = src.indexOf(": User", lineStart) - lineStart + 2
        val loc = a.typeDefinitionFor("file:///t.subl", Position(1, col))
        assertNotNull(loc, "光标在类型名 User 上应能找到 struct User")
        assertEquals("file:///t.subl", loc.uri)
    }

    @Test fun `typeDefinition 对函数名返回空`() {
        val a = opened("fun add(a: Nat): Nat = a")
        // 光标在函数名 add 上，不是类型声明，应返回 null
        val loc = a.typeDefinitionFor("file:///t.subl", Position(0, 4))
        assertNull(loc, "函数名不应有类型定义")
    }

    // ───────── completion ─────────

    @Test fun `completion 含关键字与符号`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        val list = a.completionFor("file:///t.subl", Position(0, 0))
        val labels = list.items.map { it.label }
        assertTrue("fun" in labels, "应含关键字 fun")
        assertTrue("add" in labels, "应含已声明符号 add")
    }

    // ───────── documentSymbol ─────────

    @Test fun `documentSymbol 返回声明`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b\nstruct User(name: Str)")
        val syms = a.documentSymbols("file:///t.subl")
        assertEquals(2, syms.size)
        assertEquals("add", syms[0].name)
        assertEquals("User", syms[1].name)
    }

    @Test fun `documentSymbol 层级嵌套子元素`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b\nstruct User(name: Str, age: Nat)")
        val syms = a.documentSymbols("file:///t.subl")
        assertEquals(2, syms.size)
        val add = syms.first { it.name == "add" }
        assertTrue(add.children?.any { it.name == "a" } == true, "add 应含参数 a 子节点")
        val user = syms.first { it.name == "User" }
        assertTrue(user.children?.any { it.name == "name" } == true, "User 应含字段 name 子节点")
    }

    // ───────── 局部符号 ─────────

    @Test fun `局部 var 声明进入符号表`() {
        val a = opened("fun calc(): Nat { var x = 1; return x }")
        val syms = a.collectSymbols("file:///t.subl")
        val xs = syms.filter { it.name == "x" }
        assertTrue(xs.isNotEmpty(), "应有局部 var x, 实际: " + syms.map { it.name + "/" + it.variety })
        assertEquals("var", xs.first().variety)
    }

    @Test fun `函数参数进入符号表`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        val syms = a.collectSymbols("file:///t.subl")
        val as_ = syms.filter { it.name == "a" }
        assertTrue(as_.isNotEmpty(), "应有参数 a")
        assertEquals("param", as_.first().variety)
    }

    @Test fun `struct 字段进入符号表`() {
        val a = opened("struct User(name: Str, age: Nat)")
        val syms = a.collectSymbols("file:///t.subl")
        assertTrue(syms.any { it.name == "name" && it.variety == "field" })
        assertTrue(syms.any { it.name == "age" && it.variety == "field" })
    }

    @Test fun `enum 构造子进入符号表`() {
        val a = opened("enum Color { Red, Green, Blue }")
        val syms = a.collectSymbols("file:///t.subl")
        assertTrue(syms.any { it.name == "Red" && it.variety == "ctor" })
    }

    @Test fun `局部 var 可 definition`() {
        val a = opened("fun calc(): Nat { var x = 1; return x }")
        // "x" in "var x =" is at 1-based col 23 → 0-based char 22
        val loc = a.definitionFor("file:///t.subl", Position(0, 22))
        assertNotNull(loc, "局部 var x 应有 definition")
    }

    // ───────── references ─────────

    @Test fun `references 找到声明与调用`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b\nfun main() { add(1, 2); add(3, 4) }")
        val refs = a.referencesFor("file:///t.subl", Position(0, 4))
        assertTrue(refs.size >= 2, "至少应有声明 + 1 个调用点, 实际: " + refs.size)
        assertEquals("file:///t.subl", refs.first().uri)
    }

    @Test fun `rename 函数名替换声明与所有调用`() {
        val src = "fun add(a: Nat, b: Nat): Nat = a + b\nfun main() { add(1, 2); add(3, 4) }"
        val a = opened(src)
        val edits = a.renameFor("file:///t.subl", Position(0, 4), "plus") ?: error("rename 返回 null")
        val list = edits["file:///t.subl"] ?: error("缺 uri")
        assertEquals(3, list.size, "声明 + 2 处调用应 3 个编辑，实际: " + list.map { it.range })
        assertTrue(list.all { it.newText == "plus" }, "newText 应都为 plus")
        assertTrue(list.map { it.range }.toSet().size == 3, "3 处编辑的 range 应互不相同")
    }

    @Test fun `rename 跨作用域多处引用一击替换`() {
        // x 声明在 main 内，引用散落在 if 条件、if 块体与同层调用中（跨作用域）
        val src = "fun main() { var x = 1; if (x > 0) { print(x) }; print(x) }"
        val a = opened(src)
        val edits = a.renameFor("file:///t.subl", Position(0, 17), "y") ?: error("rename 返回 null")
        val list = edits["file:///t.subl"] ?: error("缺 uri")
        assertEquals(4, list.size, "声明 + 3 处引用应 4 个编辑，实际: " + list.map { it.range })
        assertTrue(list.all { it.newText == "y" }, "newText 应都为 y")
        assertTrue(list.map { it.range }.toSet().size == 4, "4 处编辑的 range 应互不相同")
        assertTrue(list.none { it.range.start == it.range.end && it.range.start == Position(0, 0) }, "不应有空范围 (0,0)")
    }

    // ───────── highlight ─────────

    @Test fun `highlight 高亮同名出现`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        val hs = a.highlightsFor("file:///t.subl", Position(0, 4))
        assertTrue(hs.isNotEmpty(), "应有 highlight")
    }

    // ───────── Json 特殊字符 ─────────

    @Test fun `Json 序列化反斜杠与引号`() {
        assertEquals("\"a\\b\"", Json.stringify("a\b"))
        assertEquals("\"x\\\"y\"", Json.stringify("x\"y"))
    }

    // ───────── signatureHelp ─────────

    @Test fun `signatureHelp 基础签名`() {
        val src = "fun add(a: Nat, b: Nat): Nat = a + b\nfun main() { add(1, 2) }"
        val a = opened(src)
        // T1：动态算 main() 里第二个 add( 的 '(' 后列，避免硬编码列号
        val lineStart = src.indexOf('\n') + 1
        val paren = src.indexOf("add(", lineStart) + 3
        val col = paren - lineStart + 1
        val help = a.signatureHelpFor("file:///t.subl", Position(1, col))
        assertNotNull(help, "应有签名提示")
        assertTrue(help.signatures.isNotEmpty(), "应有签名")
        val sig = help.signatures.first()
        assertTrue(sig.label.contains("add"), "签名应含函数名")
        assertTrue(sig.label.contains("a: Nat"), "签名应含参数 a")
        assertTrue(sig.parameters.size >= 2, "应有两个参数")
    }

    @Test fun `signatureHelp 在嵌套调用中取内层函数`() {
        val a = opened("fun neg(x: Int): Int = -x\nfun main() { neg(neg(1)) }")
        // cursor inside inner neg(▍1) at line 1 (0-based), inside the inner '('
        // "neg(neg(1))" — inner '(' for second "neg" is at 21
        val help = a.signatureHelpFor("file:///t.subl", Position(1, 22))
        assertNotNull(help, "嵌套调用也应有签名")
        val sig = help.signatures.first()
        assertTrue(sig.label.contains("neg"), "应取内层 neg")
    }

    @Test fun `signatureHelp 逗号在顶层才计数`() {
        val a = opened("fun fold(init: Nat, f: (Nat,Nat)=>Nat): Nat = f(init,init)\nfun main() { fold(0, { a, b -> a + b }) }")
        // cursor at comma after 0 in fold(0, ▍{...})
        val help = a.signatureHelpFor("file:///t.subl", Position(1, 21))
        assertNotNull(help)
        assertEquals(1, help.activeParameter, "第二个实参")
    }

    // ───────── foldingRange ─────────

    @Test fun `foldingRange 多行花括号折叠`() {
        val a = opened("fun main() {\n  var x = 1\n  print(x)\n}")
        val folds = a.foldingRanges("file:///t.subl")
        assertTrue(folds.isNotEmpty(), "应有折叠区间")
        // '{' 在行 0，'}' 在行 3
        val fold = folds.find { it.startLine == 0 && it.endLine == 3 }
        assertNotNull(fold, "应有 {0..3} 折叠")
    }

    @Test fun `foldingRange 跳过字符串花括号`() {
        val a = opened("fun main() {\n  var s = \"{ not a brace }\"\n  var x = 1\n}")
        val folds = a.foldingRanges("file:///t.subl")
        // T3：字符串花括号不应被误识别——折叠应恰好覆盖 {0..3} 这一段，无额外区间
        assertEquals(1, folds.size, "字符串花括号不应产生额外折叠，实际: " + folds)
        val fold = folds.find { it.startLine == 0 && it.endLine == 3 }
        assertNotNull(fold, "字符串花括号不应干扰折叠")
    }

    // ───────── rename ─────────

    @Test fun `rename 替换声明与引用`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b\nfun main() { add(1, 2) }")
        val changes = a.renameFor("file:///t.subl", Position(0, 4), "plus")
        assertNotNull(changes, "应有重命名结果")
        val edits = changes!!["file:///t.subl"]
        assertNotNull(edits)
        assertTrue(edits!!.size >= 2, "至少应有声明 + 1 个引用, 实际: " + edits.size)
        assertTrue(edits.all { it.newText == "plus" }, "所有编辑都应替换为 plus")
    }

    @Test fun `prepareRename 返回范围`() {
        val a = opened("fun add(a: Nat, b: Nat): Nat = a + b")
        val range = a.prepareRenameFor("file:///t.subl", Position(0, 4))
        assertNotNull(range, "应有可重命名范围")
    }

    // ───────── workspace/symbol ─────────

    @Test fun `workspaceSymbol 跨文档搜索`() {
        val a = LspAnalysis()
        a.openDocument("file:///a.subl", "fun alpha(): Nat = 1")
        a.openDocument("file:///b.subl", "struct Beta(x: Nat)")
        val syms = a.workspaceSymbols("alpha")
        assertTrue(syms.any { it.name == "alpha" && it.location.uri == "file:///a.subl" })
        // 不区分大小写子串匹配
        val beta = a.workspaceSymbols("be")
        assertTrue(beta.any { it.name == "Beta" && it.location.uri == "file:///b.subl" })
    }

    // ───────── prelude ─────────

    @Test fun `prelude 基本类型补全`() {
        val a = opened("fun main() { }")
        a.loadPrelude(null)
        val list = a.completionFor("file:///t.subl", Position(0, 0))
        val names = list.items.map { it.label }
        assertTrue(names.contains("Nat"), "应含内建类型 Nat")
        assertTrue(names.contains("Bool"), "应含内建类型 Bool")
        assertTrue(names.contains("List"), "应含 stdlib 类型 List")
    }

    @Test fun `prelude 函数补全`() {
        val a = opened("fun main() { }")
        a.loadPrelude(null)
        val list = a.completionFor("file:///t.subl", Position(0, 0))
        val names = list.items.map { it.label }
        assertTrue(names.contains("print"), "应含内建函数 print")
        assertTrue(names.contains("toStr"), "应含内建函数 toStr")
        assertTrue(names.contains("concat"), "应含内建函数 concat")
    }

    @Test fun `prelude 函数悬停文档`() {
        val a = opened("fun main() { var x: Nat = 1 }")
        a.loadPrelude(null)
        // "fun main() { var x: Nat = 1 }" —— "Nat" 约在列 21-23
        val hover = a.hoverFor("file:///t.subl", Position(0, 21))
        assertNotNull(hover, "内建类型 Nat 应有 hover 文档")
    }

    @Test fun `prelude 不污染定义跳转`() {
        val a = opened("fun main() { var x: Nat = 1 }")
        a.loadPrelude(null)
        // Nat 是 prelude 符号，无源码位置，definition 应返回 null
        val loc = a.definitionFor("file:///t.subl", Position(0, 21))
        assertNull(loc, "内建类型不应有可跳转定义")
    }

    @Test fun `prelude core 标准库文件符号（存在才验）`() {
        val stdlib = java.io.File("core")
        if (!stdlib.isDirectory) return  // 无标准库目录则跳过
        val a = opened("fun main() { }")
        a.loadPrelude(stdlib)
        val list = a.completionFor("file:///t.subl", Position(0, 0))
        val names = list.items.map { it.label }
        // core 标准库顶层声明至少含 List（collection/list.subl）或 map/fold
        assertTrue(names.contains("List") || names.contains("map") || names.contains("fold"),
            "应含至少一个 core 顶层符号，实际前若干: " + names.take(20).joinToString())
    }

    // ───────── Unicode 符号补全 ─────────

    @Test fun `unicode 补全 forall 全称量词`() {
        val src = """fun main() { \forall"""
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(0, src.length))
        val symbols = list.items.map { it.insertText }
        assertTrue("∀" in symbols, "应能补全出 ∀，实际: $symbols")
    }

    @Test fun `unicode 补全 不等于`() {
        val src = """fun main() { \!="""
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(0, src.length))
        val symbols = list.items.map { it.insertText }
        assertTrue("≠" in symbols, "应能补全出 ≠，实际: $symbols")
    }

    @Test fun `unicode 补全 前缀过滤`() {
        val src = """fun main() { \ex"""
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(0, src.length))
        val labels = list.items.mapNotNull { it.filterText }
        assertTrue(labels.any { it.startsWith("ex") }, "应按前缀 ex 过滤，实际: $labels")
    }

    // ───────── semanticTokens ─────────

    @Test fun `semanticTokens delta 编码首 token`() {
        val a = opened("fun main() { }")
        val data = a.semanticTokens("file:///t.subl") ?: error("semanticTokens 返回 null")
        assertEquals(0, data[0])  // deltaLine
        assertEquals(0, data[1])  // deltaStart
        assertEquals(3, data[2])  // "fun" 长度
        assertEquals(0, data[3])  // keyword
        assertEquals(0, data[4])  // modifiers
    }

    @Test fun `semanticTokens 关键类型齐全`() {
        val a = opened("struct User(n: Nat)\nfun main(u: User): User = 1")
        val data = a.semanticTokens("file:///t.subl") ?: error("semanticTokens 返回 null")
        val types = data.chunked(5).map { it[3] }
        // struct 声明与类型引用现在独立为 struct(12)，不再压进 type(1)
        for (expected in listOf(0, 2, 3, 5, 7, 12)) {
            assertTrue(expected in types, "缺失类型索引 $expected；实际: $types")
        }
    }

    @Test fun `semanticTokens 结构体枚举类型别名类型类注解变量齐全`() {
        val src = "struct Point(x: Nat, y: Nat)\nenum Color { Red, Green }\ntype Size = Nat\nclass Ord { }\n@mut fun msg(): Str = \"hi\"\nfun bump(): Nat { var n: Nat = 1; n + 1 }\nfun use(p: Point): Color = Red"
        val a = opened(src)
        val data = a.semanticTokens("file:///t.subl") ?: error("semanticTokens 返回 null")
        val types = data.chunked(5).map { it[3] }
        // 0 keyword; 1 type(Size 别名); 2 function; 3 parameter(p); 4 variable(n);
        // 5 field(x,y); 6 enumMember(Red/Green ctor); 7 number(1); 8 string("hi");
        // 9 operator(+); 10 class(Ord 类型类); 11 enum(Color); 12 struct(Point/Nat); 13 decorator(@mut)
        for (expected in 0..13) {
            assertTrue(expected in types, "缺失类型索引 $expected；实际: $types")
        }
    }

    @Test fun `P5 同行两个声明位置不重叠`() {
        val a = opened("struct A(x: Int) struct B(y: Int)")
        val syms = a.collectSymbols("file:///t.subl")
        val aSym = syms.find { it.name == "A" && it.variety == "struct" }
        val bSym = syms.find { it.name == "B" && it.variety == "struct" }
        assertTrue(aSym != null && bSym != null, "应收集到 A 与 B，实际: " + syms.map { it.name + ":" + it.variety })
        assertTrue(aSym!!.colStart != bSym!!.colStart, "同行两个 struct 列号应不同：A=" + aSym.colStart + " B=" + bSym.colStart)
    }

    // ───────── 审查回归（H1/H2/H3/H4）─────────

    @Test fun `H1 光标在末尾空行不失效`() {
        val a = opened("fun main() {\n}\n")   // 末尾多一个空行（第 3 行）
        val list = a.completionFor("file:///t.subl", Position(2, 0))
        assertTrue(list.items.isNotEmpty(), "末尾空行上补全应返回关键字等，而非因 positionToOffset 失效")
    }

    @Test fun `H2 inlayHint 不把 == 当命名参数`() {
        val a = opened("fun same(x: Bool, y: Bool): Bool = x\nfun main() { same(a == b, c) }")
        val hints = a.inlayHints("file:///t.subl")
        val labels = hints.map { it.label }
        assertTrue(labels.any { it == "x:" }, "a == b 是位置实参应有 x: 提示，实际: " + labels)
        assertTrue(labels.any { it == "y:" }, "c 是位置实参应有 y: 提示，实际: " + labels)
    }

    @Test fun `H3 未知 receiver 的成员补全不泄漏全局`() {
        val src = "fun main() { someUnknownVar. }"
        val a = opened(src)
        val col = src.indexOf("someUnknownVar.") + "someUnknownVar.".length
        val list = a.completionFor("file:///t.subl", Position(0, col))
        assertTrue(list.items.isEmpty(), "未知 receiver 的 . 之后不应补全全局 fun/Nat，实际: " + list.items.map { it.label })
    }

    @Test fun `H4 注解变量声明不分行`() {
        val src = "@mut var x = 1"
        val a = opened(src)
        val edits = a.formatDocument("file:///t.subl") ?: emptyList()
        val text = edits.firstOrNull()?.newText ?: src
        assertTrue(text.contains("@mut var"), "@mut 与 var 应同行，实际: " + text)
        assertTrue(!text.contains("@mut\nvar"), "@mut 与 var 之间不应换行，实际: " + text)
    }

    @Test fun `L3 hover 字符串里的标识符不命中`() {
        val src = "fun main() { var s = \"fun f\" }"
        val a = opened(src)
        val col = src.indexOf("fun f") + 1   // 指向字符串内 "fun" 的 'u'
        val hover = a.hoverFor("file:///t.subl", Position(0, col))
        assertNull(hover, "字符串里的 fun 不应命中关键字文档")
    }

    @Test fun `L8 未匹配 unicode 前缀不屏蔽补全`() {
        val src = "fun main() { \\fun }"
        val a = opened(src)
        val col = src.indexOf("\\fun") + 4   // \fun 后
        val list = a.completionFor("file:///t.subl", Position(0, col))
        assertTrue(list.items.any { it.label == "fun" }, "未匹配 unicode 前缀应回退普通补全（含 fun 关键字），实际: " + list.items.map { it.label })
    }

    @Test fun `N2 畸形 JSON 请求回 -32700`() {
        val input = lspFrame("""{"jsonrpc":"2.0","id":7,"method":"textDocument/hover","params":{"token":truX}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val err = frames.firstOrNull { it.contains("-32700") }
        assertTrue(err != null, "畸形 JSON 请求应回 -32700，实际帧: " + frames)
    }

    // ───────── 端到端服务器（JSON-RPC over stdio）─────────

    private fun lspFrame(body: String): String {
        val n = body.toByteArray(Charsets.UTF_8).size
        return "Content-Length: " + n + "\r\n\r\n" + body
    }

    private fun parseFrames(all: String): List<String> {
        // 与服务器一致：按 UTF-8 字节定位 \r\n\r\n 并切 body（P1：Content-Length 是字节数，不是字符数）
        val bytes = all.toByteArray(Charsets.UTF_8)
        val frames = mutableListOf<String>()
        var i = 0
        while (i < bytes.size - 3) {
            var he = -1
            for (j in i until bytes.size - 3) {
                if (bytes[j].toInt() == 13 && bytes[j + 1].toInt() == 10 &&
                    bytes[j + 2].toInt() == 13 && bytes[j + 3].toInt() == 10
                ) { he = j; break }
            }
            if (he < 0) break
            val header = String(bytes, i, he - i, Charsets.UTF_8)
            val len = header.substringAfter("Content-Length: ", "0").trim().toIntOrNull() ?: 0
            frames += String(bytes, he + 4, len, Charsets.UTF_8)
            i = he + 4 + len
        }
        return frames
    }

    @Test fun `端到端 initialize 与错误响应`() {
        val input = lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"no/such/method","params":{}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        assertTrue(frames.size >= 2, "应至少有 initialize 与错误响应，实际: $frames")
        assertTrue(frames[0].contains("positionEncoding"), "initialize 应声明 positionEncoding")
        assertTrue(frames[0].contains("utf-16"), "应声明 utf-16")
        assertTrue(frames[1].contains("-32601"), "未知方法应返回 -32601，实际: " + frames[1])
    }

    @Test fun `端到端 补全返回 Unicode 补全项（字节分帧）`() {
        // didOpen 文本含中文字符串（UTF-8 每字 3 字节）：验证 P1 按字节分帧后服务器不挂、后续请求正常
        val doc = """fun main() {
    // 你好世界
    var x = 1
}"""
        val didOpenText = doc.replace("\n", "\\n")
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"initialized"}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subl","languageId":"subl","version":1,"text":"$didOpenText"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"file:///t.subl"},"position":{"line":0,"character":0}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val completion = frames.firstOrNull { it.contains("\"id\":2") }
        assertTrue(completion != null, "应有 completion 响应，实际帧数=${frames.size}")
        // 补全应返回 items，且含关键字（fun）或 prelude 类型（List）
        assertTrue(completion.contains("\"items\""), "completion 含 items，实际: " + completion)
        assertTrue(completion.contains("fun") || completion.contains("List"), "补全项应含内容，实际: " + completion)
        // 演示：打印实际补全到的 label（供肉眼确认补全功能生效）
        val labels = Regex("\"label\":\"([^\"]*)\"").findAll(completion).map { it.groupValues[1] }.toList()
        println("LSP 补全演示：共 ${labels.size} 个补全项")
        println("前 40 项: " + labels.take(40).joinToString(", "))
    }

    // ───────── formatting ─────────

    @Test fun `formatting 注解异行`() {
        val a = opened("""@theorem fun add(a: Nat): Nat = a""")
        val edits = a.formatDocument("file:///t.subl") ?: error("formatDocument 返回 null")
        assertTrue(edits.isNotEmpty(), "应产生格式化编辑")
        val newText = edits.first().newText
        assertTrue(newText.contains("@theorem\nfun"), "注解应独占一行，实际:\n" + newText)
    }

    @Test fun `formatting 缩进与花括号`() {
        val a = opened("""fun main(){var x=1;}""")
        val newText = a.formatDocument("file:///t.subl")!!.first().newText
        assertTrue(newText.contains("fun main() {"), "左括号应与声明同行，实际:\n" + newText)
        assertTrue(newText.contains("    var x = 1;"), "块内应缩进 4 空格，实际:\n" + newText)
    }

    @Test fun `formatting 空块合并`() {
        val a = opened("""fun foo() {}""")
        val newText = a.formatDocument("file:///t.subl")!!.first().newText
        assertTrue(newText.contains("{}"), "空块应保留 {}，实际:\n" + newText)
    }

    // ───────── codeAction ─────────

    @Test fun `codeAction 全角括号替换半角`() {
        val a = opened("""fun main（） { }""")
        val actions = a.codeActions("file:///t.subl")
        assertTrue(actions.isNotEmpty(), "含全角符号应产生产生快速修复")
        val edits = actions.first().edit?.changes?.get("file:///t.subl") ?: emptyList()
        assertTrue(edits.any { it.newText == "(" }, "应有全角（换成半角 ( 的编辑")
    }

    // ───────── semanticTokens range ─────────

    @Test fun `semanticTokensRange 只返回范围内 token`() {
        val a = opened("fun main() { var x = 1 }")
        val full = a.semanticTokens("file:///t.subl") ?: error("semanticTokens 返回 null")
        val range = a.semanticTokensRange("file:///t.subl", Position(0, 0), Position(0, 3)) ?: error("null")
        assertTrue(range.isNotEmpty(), "范围内至少含 fun 一个 token")
        assertTrue(range.size <= full.size, "范围内 token 数不应超过全量")
    }

    // ───────── 成员上下文补全 ─────────

    @Test fun `memberCompletion struct 字段与 impl 方法`() {
        val a = opened("""
            struct User(n: Nat)
            impl Show for User { fun show(): Str = "" }
        """.trimIndent())
        val names = a.memberCompletion("file:///t.subl", "User", false).map { it.label }
        assertTrue("n" in names, "应包含字段 n，实际: " + names)
        assertTrue("show" in names, "应包含方法 show，实际: " + names)
    }

    @Test fun `memberCompletion enum 构造子`() {
        val a = opened("""
            enum Color { Red(), Blue() }
        """.trimIndent())
        val names = a.memberCompletion("file:///t.subl", "Color", true).map { it.label }
        assertTrue("Red" in names && "Blue" in names, "应包含枚举构造子 Red/Blue，实际: " + names)
    }

    @Test fun `memberCompletion 变量类型推断`() {
        val a = opened("""
            struct User(n: Int)
            fun main() {
                var u = User(1)
                u.n
            }
        """.trimIndent())
        // receiver "u" 无类型注解，须由 checker 类型推导器推得 User
        val names = a.memberCompletion("file:///t.subl", "u", false).map { it.label }
        assertTrue("n" in names, "变量 u 应由 checker 推得 User 并补全字段 n，实际: " + names)
    }

    // ───────── implementation 跳转 ─────────

    @Test fun `implementation 跳转 struct`() {
        val a = opened("""
            struct User(n: Nat)
            impl Show for User { fun show(): Str = "" }
        """.trimIndent())
        val locs = a.implementationsFor("file:///t.subl", Position(0, 7))
        assertTrue(locs.isNotEmpty(), "应找到 impl Show for User")
    }

    // ───────── inlayHint ─────────

    @Test fun `inlayHint 参数名提示`() {
        val a = opened("""
            fun add(a: Nat, b: Nat): Nat = a + b
            fun main() { add(1, 2) }
        """.trimIndent())
        val labels = a.inlayHints("file:///t.subl").map { it.label }
        assertTrue("a:" in labels, "应提示参数名 a:，实际: " + labels)
        assertTrue("b:" in labels, "应提示参数名 b:，实际: " + labels)
    }

    @Test fun `inlayHint 变量类型提示`() {
        val a = opened("""
            struct User(n: Int)
            fun main() { var u = User(1) }
        """.trimIndent())
        val labels = a.inlayHints("file:///t.subl").map { it.label }
        assertTrue(labels.any { it.contains("User") }, "变量 u 应提示推导类型 : User，实际: " + labels)
    }

    // ───────── Json 边界（M4/M5）─────────

    @Test fun `Json 解析 null true false`() {
        assertEquals(null, Json.parse("null"))
        assertEquals(true, Json.parse("true"))
        assertEquals(false, Json.parse("false"))
    }

    @Test fun `Json 解析负数与浮点`() {
        assertEquals(-5L, Json.parse("-5"))
        assertEquals(1.5, Json.parse("1.5"))
        assertEquals(-3.25, Json.parse("-3.25"))
    }

    @Test fun `Json 解析数组与嵌套`() {
        val arr = Json.parse("""[1,true,null,"x"]""")
        assertTrue(arr is List<*>, "应解析为数组")
        assertEquals(listOf(1L, true, null, "x"), arr)
    }

    @Test fun `Json 畸形字面量抛异常`() {
        var threw = false
        try { Json.parse("tru") } catch (e: Exception) { threw = true }
        assertTrue(threw, "M4：tru 不是 true，应抛异常而非盲跳")
    }

    @Test fun `Json 超大整数抛异常`() {
        var threw = false
        try { Json.parse("99999999999999999999999") } catch (e: Exception) { threw = true }
        assertTrue(threw, "M5：超大整数溢出应抛异常，由 dispatch 兜住")
    }

    // ───────── 格式化 / codeAction 边界 ─────────

    @Test fun `formatting 区间不加空格`() {
        val a = opened("fun main() { var x = 0 .. 3 }")
        val newText = a.formatDocument("file:///t.subl")!!.first().newText
        assertTrue(newText.contains("0..3"), "M1：区间应保持 0..3，实际:\n" + newText)
    }

    @Test fun `formatting 运算符加空格`() {
        val a = opened("fun f(): Nat = 1+2")
        val newText = a.formatDocument("file:///t.subl")!!.first().newText
        assertTrue(newText.contains("1 + 2"), "运算符两侧应加空格，实际:\n" + newText)
    }

    @Test fun `formatting 比较运算符加空格`() {
        val a = opened("fun main() { var b = 1==1 }")
        val newText = a.formatDocument("file:///t.subl")!!.first().newText
        assertTrue(newText.contains("1 == 1"), "== 两侧应加空格，实际:\n" + newText)
    }

    @Test fun `codeAction 全角逗号替换`() {
        val a = opened("fun main() { add(1，2) }")
        val actions = a.codeActions("file:///t.subl")
        val edits = actions.first().edit?.changes?.get("file:///t.subl") ?: emptyList()
        assertTrue(edits.any { it.newText == "," }, "应有全角，换成半角 , 的编辑")
    }

    // ───────── hover 边界 ─────────

    @Test fun `hover struct 字段`() {
        val src = "struct User(n: Nat)\nfun main(u: User) { u.n }"
        val a = opened(src)
        val lineStart = src.indexOf('\n') + 1
        val col = src.indexOf("u.n", lineStart) - lineStart + 2   // 相对第 2 行、指向字段名 n
        val hover = a.hoverFor("file:///t.subl", Position(1, col))
        assertNotNull(hover, "字段 n 上应有 hover")
        val content = hover.contents as? MarkupContent
        assertTrue(content!!.value.contains("n"), "字段 hover 应含字段名 n，实际: " + content.value)
    }

    @Test fun `hover 内建类型 Nat 给出文档`() {
        val a = opened("fun f(): Nat = 1")
        a.loadPrelude(null)
        val hover = a.hoverFor("file:///t.subl", Position(0, 9))
        assertNotNull(hover, "内建类型 Nat 应有 prelude 文档")
        val content = hover.contents as? MarkupContent
        assertTrue(content!!.value.isNotEmpty(), "Nat 文档不应为空")
    }

    @Test fun `semanticTokens 空文档`() {
        val a = opened("")
        val tokens = a.semanticTokens("file:///t.subl")
        assertTrue(tokens == null || tokens.isEmpty(), "空文档应无 token")
    }

    // ───────── 端到端 hover / definition / formatting ─────────

    @Test fun `端到端 hover 请求`() {
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subl","languageId":"subl","version":1,"text":"fun add(a: Nat): Nat = a"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/hover","params":{"textDocument":{"uri":"file:///t.subl"},"position":{"line":0,"character":4}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val hover = frames.firstOrNull { it.contains("contents") && it.contains("\"id\":2") }
        assertNotNull(hover, "应有 hover 响应，实际帧: " + frames)
        assertTrue(hover.contains("add"), "hover 应含函数名 add，实际: " + hover)
    }

    @Test fun `端到端 definition 请求`() {
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subl","languageId":"subl","version":1,"text":"fun add(a: Nat): Nat = a"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/definition","params":{"textDocument":{"uri":"file:///t.subl"},"position":{"line":0,"character":4}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val def = frames.firstOrNull { it.contains("uri") && it.contains("range") }
        assertNotNull(def, "definition 应返回 location，实际帧: " + frames)
    }

    @Test fun `端到端 格式化请求`() {
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subl","languageId":"subl","version":1,"text":"fun main(){var x=1;}"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/formatting","params":{"textDocument":{"uri":"file:///t.subl"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val fmt = frames.firstOrNull { it.contains("fun main() {") }
        assertNotNull(fmt, "格式化应返回含缩进的文本，实际帧: " + frames)
    }

    // ───────── 脚本模式（.subs）─────────

    @Test fun `subs 脚本模式解析顶层语句`() {
        val a = openedSubs("print(\"hi\")\nvar x = 1\nprint(x)")
        val diags = a.diagnosticsFor("file:///t.subs")
        assertTrue(diags.none { it.code == "E-PARSE" }, "脚本模式顶层语句应可解析: " + diags.map { it.code })
    }

    @Test fun `subs 顶层 var 与函数进入符号表`() {
        val a = openedSubs("fun greet(): Str = \"hi\"\n@mut var acc = 1")
        val syms = a.collectSymbols("file:///t.subs")
        assertTrue(syms.any { it.name == "greet" && it.variety == "fun" }, "应收集脚本函数 greet")
        assertTrue(syms.any { it.name == "acc" && it.variety == "var" }, "应收集顶层 var acc")
    }

    @Test fun `subs 补全含脚本声明`() {
        val a = openedSubs("fun greet(): Str = \"hi\"\nvar n = 1")
        val list = a.completionFor("file:///t.subs", Position(1, 0))
        val labels = list.items.map { it.label }
        assertTrue("greet" in labels, "应补全脚本函数 greet，实际: " + labels)
    }


    @Test fun `subs 全局类型推导填充参数类型`() {
        val a = openedSubs("fun add(a, b) = a + b\nprint(toStr(add(1, 2)))")
        val hover = a.hoverFor("file:///t.subs", Position(0, 4))
        assertNotNull(hover, "add 应有 hover")
        val content = hover!!.contents as? MarkupContent
        assertTrue(content!!.value.contains("Nat"), "全局推导后参数应显示 Nat，实际: " + content.value)
    }

    @Test fun `subs 脚本样本分析`() {
        val src = "struct Account(owner: Str, @mut balance: Nat, country: Str = \"中国\")" + "\n" +
            "@mut var acc = Account(owner = \"张三\", balance = 1000)" + "\n" +
            "print(\"开户=\" + toStr(acc.balance))"
        val a = openedSubs(src)
        val syms = a.collectSymbols("file:///t.subs")
        assertTrue(syms.any { it.name == "Account" && it.variety == "struct" }, "应收集 struct Account")
        assertTrue(syms.any { it.name == "acc" }, "应收集顶层 var acc")
    }

    @Test fun `subs 成员补全推导 receiver`() {
        val src = "struct Account(owner: Str, balance: Nat)" + "\n" +
            "@mut var acc = Account(owner = \"张三\", balance = 1000)" + "\n" +
            "acc.balance"   // 光标置于 '.' 之后（balance 的 b 之前）
        val a = openedSubs(src)
        val list = a.completionFor("file:///t.subs", Position(2, 4))
        val labels = list.items.map { it.label }
        assertTrue(labels.any { it == "balance" || it == "owner" }, "acc. 应补全 Account 字段，实际: " + labels)
    }

    @Test fun `subs inlayHint 参数名提示`() {
        val a = openedSubs("fun add(a: Nat, b: Nat): Nat = a + b\nprint(toStr(add(1, 2)))")
        val labels = a.inlayHints("file:///t.subs").map { it.label }
        assertTrue("a:" in labels && "b:" in labels, "subs 也应有参数名提示，实际: " + labels)
    }

    @Test fun `端到端 subs 脚本补全含声明`() {
        // 单行脚本：didOpen text 无需换行/引号转义，JSON 安全
        val doc = "fun add(a: Nat, b: Nat): Nat = a + b"
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"initialized"}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subs","languageId":"subs","version":1,"text":"$doc"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/completion","params":{"textDocument":{"uri":"file:///t.subs"},"position":{"line":0,"character":0}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        // exit 无响应：completion（id:2）是最后一帧
        val completion = frames.lastOrNull()
        assertNotNull(completion, "subs 应有 completion 响应，实际帧: " + frames)
        assertTrue(completion!!.contains("add"), "subs 补全应含脚本声明 add，实际: " + completion)
    }

    // ───────── onTypeFormatting：按 Enter 智能缩进 ─────────

    @Test fun `onTypeFormatting if 块后换行缩进一级`() {
        val src = "if (x > 0) {\n"   // 用户刚在 { 后按了 Enter
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals(1, edits.size, "应返回一个编辑: " + edits)
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting 嵌套块缩进两级`() {
        val src = "fun main() {\n    if (x) {\n"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(2, 0), "\n")
        assertEquals("        ", edits[0].newText)   // 8 空格 = 2 级
    }

    @Test fun `onTypeFormatting 大括号之间换行右括号下移`() {
        // 用户在 if (x) {|} 的光标处按 Enter：文档已变为 if (x > 0) {\n}
        val src = "if (x > 0) {\n}"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals(1, edits.size, "应返回一个编辑: " + edits)
        assertEquals("    \n}", edits[0].newText)
        assertEquals(1, edits[0].range.start.line)
        assertEquals(1, edits[0].range.end.character)
    }

    @Test fun `onTypeFormatting 无大括号 if 体续行`() {
        val src = "if (x > 0)\n"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting 等号函数续行`() {
        val src = "fun f() =\n"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting 链式调用点号后续行`() {
        val src = "val s = a.\n"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting 未闭合括号续行`() {
        val src = "print(\n"
        val a = opened(src)
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "\n")
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting subs 脚本顶层缩进`() {
        val src = "if (x > 0) {\n"
        val a = openedSubs(src)
        val edits = a.onTypeFormattingEdits("file:///t.subs", Position(1, 0), "\n")
        assertEquals("    ", edits[0].newText)
    }

    @Test fun `onTypeFormatting 非换行触发字符返回空`() {
        val a = opened("fun main() {\n}")
        val edits = a.onTypeFormattingEdits("file:///t.subl", Position(1, 0), "}")
        assertTrue(edits.isEmpty(), "非换行触发不应产生编辑: " + edits)
    }

    @Test fun `端到端 onTypeFormatting 换行缩进`() {
        // 单行声明（无引号转义负担）；didOpen 的 text 含 JSON 转义换行，模拟用户在 { 后按了 Enter
        val input =
            lspFrame("""{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"textDocument/didOpen","params":{"textDocument":{"uri":"file:///t.subl","languageId":"subl","version":1,"text":"if (x > 0) {\n"}}}""") +
            lspFrame("""{"jsonrpc":"2.0","id":2,"method":"textDocument/onTypeFormatting","params":{"textDocument":{"uri":"file:///t.subl"},"position":{"line":1,"character":0},"ch":"\n"}}""") +
            lspFrame("""{"jsonrpc":"2.0","method":"exit"}""")
        val output = java.io.ByteArrayOutputStream()
        LspServer.start(java.io.ByteArrayInputStream(input.toByteArray(Charsets.UTF_8)), output)
        val frames = parseFrames(output.toString(Charsets.UTF_8))
        val resp = frames.lastOrNull()
        assertNotNull(resp, "应有 onTypeFormatting 响应，实际帧: " + frames)
        assertTrue(resp!!.contains("newText"), "响应应含 newText 编辑，实际: " + resp)
        assertTrue(resp.contains("range"), "响应应含 range，实际: " + resp)
    }

    @Test fun `补全排序 有前缀时已声明符号优先于关键字`() {
        val src = "fun main() {\n    var idx = 1\n    i\n}"
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(2, 5))
        val idx = list.items.firstOrNull { it.label == "idx" }
        val ifKw = list.items.firstOrNull { it.label == "if" }
        assertNotNull(idx, "应补全局部变量 idx")
        assertNotNull(ifKw, "应补全关键字 if")
        assertTrue(idx!!.sortText!! < ifKw!!.sortText!!,
            "有前缀时已声明符号应排在关键字之前: idx=${idx.sortText} if=${ifKw.sortText}")
    }

    @Test fun `补全排序 空行时声明关键字优先`() {
        val src = "fun main() {\n    var idx = 1\n\n}"
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(2, 0))
        val idx = list.items.firstOrNull { it.label == "idx" }
        val funKw = list.items.firstOrNull { it.label == "fun" }
        assertNotNull(funKw, "空行应补全关键字 fun")
        assertTrue(idx == null || funKw!!.sortText!! < idx!!.sortText!!,
            "空行时声明类关键字应排在符号之前: fun=${funKw?.sortText} idx=${idx?.sortText}")
    }

    @Test fun `补全排序 精确匹配排在所有前缀匹配之前`() {
        val src = "fun main() {\n    var idx = 1\n    var idxValue = 2\n    idx\n}"
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(3, 7))
        val idx = list.items.firstOrNull { it.label == "idx" }
        val idxValue = list.items.firstOrNull { it.label == "idxValue" }
        assertNotNull(idx, "应有精确匹配 idx")
        assertNotNull(idxValue, "应有前缀匹配 idxValue")
        assertTrue(idx!!.sortText!! < idxValue!!.sortText!!, "精确匹配 idx 应排在前缀匹配 idxValue 之前: ${idx.sortText} vs ${idxValue.sortText}")
    }

    @Test fun `关键字补全走 Snippet 格式可展开语句骨架`() {
        val a = opened("fun main() { i")
        val list = a.completionFor("file:///t.subl", Position(0, 14))
        val ifItem = list.items.firstOrNull { it.label == "if" }
        assertNotNull(ifItem, "应有 if 关键字补全")
        assertEquals(2, ifItem!!.insertTextFormat, "insertTextFormat 应为 2(Snippet)，实际: " + ifItem.insertTextFormat)
        assertNotNull(ifItem.insertText, "insertText 不应为 null")
        assertTrue(ifItem.insertText!!.startsWith("if ("), "if 骨架应以 if ( 开头，实际: " + ifItem.insertText)
    }

    @Test fun probeActsList() {
        val src = "fun main(): Null { var x = 1\n    x = 2\n    0 }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-IMMUT-ASSIGN" }
        println("DBG d=" + (d != null) + " range=" + d?.range + " code=" + d?.code)
        val acts = a.codeActionsFor("file:///t.subl", d?.range, listOf(d!!))
        println("DBG acts=" + acts.map { it.title })
    }
    @Test fun `快速修正 E-IMPL-INCOMPLETE 为缺失方法补存根`() {
        val src = "class Show[T] { fun show(t: T): Str }\nimpl Show[Nat] for Nat { }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-IMPL-INCOMPLETE" }
        if (d != null) {
            val acts = a.codeActionsFor("file:///t.subl", d.range, listOf(d))
            val fix = acts.firstOrNull { it.title.contains("存根") } ?: error("无修正: ${acts.map { it.title }}")
            val text = fix.edit!!.changes!!["file:///t.subl"]!!.first().newText
            assertTrue(text.contains("fun show"), "存根应含方法签名: $text")
        }
    }
    @Test fun `快速修正 E-ARITY 删掉函数调用中多余的实参`() {
        val src = "fun one(a: Nat): Nat = a\nfun main() { one(1, 2) }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-ARITY" } ?: error("应报 E-ARITY")
        val acts = a.codeActionsFor("file:///t.subl", d.range, listOf(d))
        val fix = acts.firstOrNull { it.title.contains("多余的实参") } ?: error("无修正: ${acts.map { it.title }}")
        val text = fix.edit!!.changes!!["file:///t.subl"]!!.first().newText
        assertEquals(")", text)
    }
    @Test fun `转到定义 标准库 print 跳到 core 文件`() {
        val src = "fun main(): Null { print(1) }"
        val a = opened(src)
        // print 在 1:19（"fun main(): Null { " 之后）
        val loc = a.definitionFor("file:///t.subl", Position(0, 19))
        assertNotNull(loc, "stdlib 函数 print 应能转到定义")
        assertTrue(loc!!.uri.endsWith(".subl"), "stdlib 定义应在 core 的 .subl 文件里: ${loc.uri}")
    }

    @Test fun `快捷操作 指名调用`() {
        val src = "fun add(a: Nat, b: Nat): Nat = a + b\nfun main(): Null { add(1, 2) }"
        val a = opened(src)
        // add 调用名在 1:20
        val acts = a.codeActionsFor("file:///t.subl", Range(Position(1, 19), Position(1, 22)), null)
        val fix = acts.firstOrNull { it.title.contains("指名调用") } ?: error("无指名调用: ${acts.map { it.title }}")
        val edits = fix.edit!!.changes!!["file:///t.subl"]!!
        val texts = edits.map { it.newText }
        assertTrue(texts.contains("a = ") && texts.contains("b = "), "应给两实参加形参名: $texts")
    }

    @Test fun `快捷操作 声明处给重命名 使用处给转到定义`() {
        val src = "fun double(x: Nat): Nat = x + x\nfun main(): Nat = double(1)"
        val a = opened(src)
        // 声明处 double 在 0:4
        val atDecl = a.codeActionsFor("file:///t.subl", Range(Position(0, 4), Position(0, 9)), null)
        val rename = atDecl.firstOrNull { it.command != null }
        assertNotNull(rename, "声明处应有命令型动作")
        assertEquals("editor.action.rename", rename!!.command!!.command)
        // 使用处 double 在 1:21
        val atUse = a.codeActionsFor("file:///t.subl", Range(Position(1, 18), Position(1, 23)), null)
        val goto = atUse.firstOrNull { it.command != null }
        assertNotNull(goto, "使用处应有命令型动作")
        assertEquals("editor.action.revealDefinition", goto!!.command!!.command)
    }
    @Test fun `快速修正 E-IMPURE-CALL 标记当前函数不纯`() {
        val src = "fun work(): Null { print(1) }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-IMPURE-CALL" } ?: error("应报 E-IMPURE-CALL（实际: ${a.diagnosticsFor("file:///t.subl").map { it.code }}）")
        val acts = a.codeActionsFor("file:///t.subl", d.range, listOf(d))
        val fix = acts.firstOrNull { it.title.contains("不纯") } ?: error("无修正: ${acts.map { it.title }}")
        val text = fix.edit!!.changes!!["file:///t.subl"]!!.joinToString("") { it.newText }
        assertEquals("@impure ", text, "应在 fun 前插 @impure")
    }

    @Test fun `快速修正 E-IMPURE-CALL 使调用不受检`() {
        val src = "fun work(): Null { print(1) }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-IMPURE-CALL" } ?: error("应报 E-IMPURE-CALL")
        val acts = a.codeActionsFor("file:///t.subl", d.range, listOf(d))
        val fix = acts.firstOrNull { it.title.contains("不受检") } ?: error("无修正: ${acts.map { it.title }}")
        val text = fix.edit!!.changes!!["file:///t.subl"]!!.joinToString("") { it.newText }
        assertEquals("unchecked ", text, "应在语句前插 unchecked")
    }

    @Test fun `快速修正 E-IMMUT-ASSIGN 使变量可变`() {
        val src = "fun main(): Null { var x = 1\n    x = 2\n    0 }"
        val a = opened(src)
        val d = a.diagnosticsFor("file:///t.subl").firstOrNull { it.code == "E-IMMUT-ASSIGN" } ?: error("应报 E-IMMUT-ASSIGN（实际: ${a.diagnosticsFor("file:///t.subl").map { it.code }}）")
        run {
            val acts = a.codeActionsFor("file:///t.subl", d.range, listOf(d))
            val fix = acts.firstOrNull { it.title.contains("使变量可变") } ?: error("无修正: ${acts.map { it.title }}")
            val text = fix.edit!!.changes!!["file:///t.subl"]!!.joinToString("") { it.newText }
            assertEquals("@mut ", text, "应在 var 前插 @mut")
        }
    }

    @Test fun `快速修正 E-NEED-ASYNC 给所在函数加 async 注解`() {
        val src = "fun work(): Unit { t.join() }"
        val a = opened(src)
        val diag = Diagnostic(
            range = diagPosToRange("1:24", src),
            severity = DiagnosticSeverity.Error,
            code = "E-NEED-ASYNC",
            message = "t.join() 是挂起操作，需 @async 上下文",
        )
        val acts = a.codeActionsFor("file:///t.subl", null, listOf(diag))
        val fix = acts.firstOrNull { it.title.contains("async") }
        assertNotNull(fix, "应有 @async 快速修正")
        val edits = fix!!.edit?.changes?.get("file:///t.subl")
        assertNotNull(edits, "应携带编辑")
        assertEquals(1, edits!!.size, "应只产生一处编辑")
        assertEquals("@async ", edits[0].newText, "应在 fun 前插入 @async ")
        val p = edits[0].range.start
        assertEquals(0, p.line, "插入点应在第 1 行")
        assertEquals(0, p.character, "插入点应在 fun 关键字处（行首）")
    }

    @Test fun `rename 声明编辑应指向函数名而非 fun 关键字`() {
        val src = "fun double(x: Nat): Nat = x + 1"
        val a = opened(src)
        val edits = a.renameFor("file:///t.subl", Position(0, 6), "twice") ?: error("rename 返回 null")
        val list = edits["file:///t.subl"] ?: error("缺 uri")
        // 函数名 double 起于 (0,4)；fun 关键字起于 (0,0)——声明编辑必须覆盖函数名
        val declEdit = list.firstOrNull { it.range.start == Position(0, 4) }
        assertNotNull(declEdit, "声明编辑应起于函数名 (0,4)，实际: ${list.map { it.range.start }}")
        assertEquals("twice", declEdit!!.newText)
    }

    @Test fun `诊断携带 code 对应的中文说明`() {
        val src = "fun f(a: Nat, b: Nat): Nat = a + b\nfun main() { f(1) }"
        val a = opened(src)
        val ds = a.diagnosticsFor("file:///t.subl")
        // E-ARITY 的诊断应携带表内中文说明
        val arity = ds.firstOrNull { it.code == "E-ARITY" } ?: error("缺 E-ARITY 诊断")
        assertNotNull(arity.documentation, "documentation 不应为 null")
        assertTrue(arity.documentation!!.contains("实参"), "说明应包含实参字样: ${arity.documentation}")
    }

    @Test fun `快速修正 E-NAMED-ARG 方法糖改自由函数调用`() {
        // 方法调用用了命名实参（E-NAMED-ARG）：应给出「改成自由函数调用」编辑，把 接收者.f(x: v) 改写为 f(接收者, x: v)
        val src = "fun add(x: Nat, y: Nat): Nat = x\nfun main() { add(1).add(x: 2) }"
        val a = opened(src)
        val ds = a.diagnosticsFor("file:///t.subl")
        val named = ds.firstOrNull { it.code == "E-NAMED-ARG" }
        if (named != null) {
            val acts = a.codeActionsFor("file:///t.subl", null, listOf(named))
            val fix = acts.firstOrNull { it.title.contains("自由函数") }
            assertNotNull(fix, "应有改写方法糖的快速修正: ${acts.map { it.title }}")
            val edits = fix!!.edit?.changes?.get("file:///t.subl")
            assertNotNull(edits)
            val text = edits!![0].newText
            assertTrue(text.contains("add("), "编辑应把方法调用改写为自由函数: $text")
            assertTrue(text.contains("add(1)"), "编辑应保留接收者: $text")
        } else {
            // 兜底：直接注入诊断验证改写逻辑（pos 指向第二个 add 方法名）
            val src2 = "fun add(x: Nat, y: Nat): Nat = x\nfun main() { add(1).add(2) }"
            val a2 = opened(src2)
            val diag = Diagnostic(
                range = diagPosToRange("2:22", src2),   // pos 列为 1-based；add 方法名在列 23(1-based)/22(0-based)
                severity = DiagnosticSeverity.Error,
                code = "E-NAMED-ARG",
                message = "方法 `.add(x: 1)` 不支持命名参数",
            )
            val acts = a2.codeActionsFor("file:///t.subl", null, listOf(diag))
            val fix = acts.firstOrNull { it.title.contains("自由函数") }
            assertNotNull(fix, "注入诊断也应给出改写修正: ${acts.map { it.title }}")
            val edits = fix!!.edit?.changes?.get("file:///t.subl")
            val text = edits!![0].newText
            assertTrue(text.startsWith("add(add(1), "), "应把 接收者.f( 改写为 f(接收者, : $text")
        }
    }

    @Test fun `补全注解条目带副标题与详细说明`() {
        val src = "@"
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(0, 1))
        val mut = list.items.firstOrNull { it.label == "mut" } ?: error("无 mut 项")
        assertEquals("使得变量可变", mut.detail, "@mut 副标题应是功能短语")
        assertNotNull(mut.documentation, "@mut 应带详细说明")
    }
    @Test fun `补全 @ 前缀给出内建注解列表`() {
        val src = "@"
        val a = opened(src)
        // 光标在 @ 之后（偏移 1，前缀为空 → 列全部注解）
        val list = a.completionFor("file:///t.subl", Position(0, 1))
        val labels = list.items.map { it.label }
        assertTrue(labels.contains("async"), "应提供 async 注解: $labels")
        assertTrue(labels.contains("impure") && labels.contains("mut"), "应提供 impure 与 mut: $labels")
    }

    @Test fun `补全 @as 前缀过滤出 async`() {
        val src = "@as"
        val a = opened(src)
        val list = a.completionFor("file:///t.subl", Position(0, 3))
        val labels = list.items.map { it.label }
        assertTrue(labels.any { it == "async" }, "@as 前缀应至少匹配 async: $labels")
        assertTrue(labels.none { it == "mut" }, "@as 前缀不应匹配 mut: $labels")
    }

    @Test fun `格式化保留字符串引号`() {
        val src = "fun main() { var s = \"hello\" }"
        val a = opened(src)
        val text = (a.formatDocument("file:///t.subl") ?: error("null")).first().newText
        assertTrue(text.contains("\"hello\""), "格式化后字符串引号必须在: " + text.lines().filter { it.contains("\"hello\"") })
    }
    @Test fun `格式化标准 链式调用换行缩进对齐成员`() {
        val src = "fun main() { xs.map(f).filter(g) }"
        val a = opened(src)
        val text = (a.formatDocument("file:///t.subl") ?: error("null")).first().newText
        // 无换行源码：格式化后应仍在一行（不应主动拆链）——验证不破坏单行链式
        val body = text.lines().first { it.contains("map") }
        assertTrue(body.contains(".map(f).filter(g)"), "单行链式应保持单行: $body")
    }

    @Test fun `格式化标准 源码换行的链式调用保持深一级缩进`() {
        val src = "fun main() { xs.map(f)\n.filter(g)\n.reduce(h) }"
        val a = opened(src)
        val text = (a.formatDocument("file:///t.subl") ?: error("null")).first().newText
        val dotLines = text.lines().filter { it.contains(".filter") || it.contains(".reduce") }
        assertTrue(dotLines.size == 2, "filter/reduce 各占一行: ${dotLines.size}")
        assertTrue(dotLines.all { it.startsWith("        ") }, "链式换行应缩进 8 空格(块体一级+链式一级): $dotLines")
    }

    @Test fun `格式化标准 单表达式函数体换行缩进`() {
        val src = "fun f(x: Nat): Nat =\nx + 1"
        val a = opened(src)
        val text = (a.formatDocument("file:///t.subl") ?: error("null")).first().newText
        val bodyLine = text.lines().firstOrNull { it.contains("x + 1") } ?: error("找不到体: $text")
        assertTrue(bodyLine.startsWith("    "), "单表达式体换行应缩进 4 空格: [$bodyLine]")
    }

    @Test fun `格式化保留块体多语句换行不压一行`() {
        val src = "fun main() { var a = 1\nvar b = 2\nprint(a)\nprint(b) }"
        val a = opened(src)
        val edits = a.formatDocument("file:///t.subl") ?: error("formatDocument 返回 null")
        val text = edits[0].newText
        val lines = text.split("\n").filter { it.isNotBlank() }
        assertTrue(lines.size >= 3, "块体 3 条语句应至少占 3 行，实际: ${lines.size} 行 -> ${lines}")
        assertTrue(lines.any { it.contains("var a") } && lines.any { it.contains("var b") },
            "a 与 b 应各占一行: $lines")
    }

    @Test fun `挂 core 后诊断不泄漏 stdlib 行号偏移`() {
        // 用户文件只有 2 行；core 挂上后 stdlib 自身诊断行号必然 > 2，应全部过滤掉
        val src = "fun f(s: Nat, x: Nat): Nat = x\nfun main() { f(1) }"
        val a = opened(src)
        val ds = a.diagnosticsFor("file:///t.subl")
        val userLines = src.split("\n").size
        val spilled = ds.filter { it.range.start.line + 1 > userLines && (it.range.start.line != 0 || it.range.start.character != 0) }
        assertTrue(spilled.isEmpty(), "不应有行号超出用户文件的诊断: ${spilled.map { it.code + "@" + it.range.start.line }}")
    }

    @Test fun `工程模式也应识别 core 内建构造子 Ok`() {
        // core/collection/result.subl 定义 Result 及构造子 Ok/Err；工程模式未挂 core 时此处报未知构造子
        val src = "fun f(): Result<Nat, Str> = Ok(1)"
        val a = opened(src)
        val ds = a.diagnosticsFor("file:///t.subl")
        val unbound = ds.firstOrNull { it.code == "E-UNBOUND-NAME" }
        assertNull(unbound, "工程模式不该把 Ok/Result 报成未知符号: ${ds.map { it.code + ":" + it.message }}")
    }
}