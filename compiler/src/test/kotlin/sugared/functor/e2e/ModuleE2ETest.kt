package sugared.functor.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sugared.functor.checker.Checker
import sugared.functor.checker.MultiModule
import sugared.functor.lexer.LexFailure
import sugared.functor.parser.parseSource
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P0 多模块端到端（v1.0 补全计划 §2.5 验收）：
 * 目录树 → MultiModule 语义检查 → 合并编译 → node 实跑 → 断言 stdout。
 * node 不可用时自动跳过；临时目录用完即删。
 */
class ModuleE2ETest {
    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    /** 编译并运行一个多模块目录树，返回 node 的 stdout（trim 后） */
    private fun runTree(spec: Map<String, String>): String {
        val dir = Files.createTempDirectory("subl-mod-e2e").toFile()
        try {
            for ((rel, content) in spec) {
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertFalse(bag.hasError, "语义检查应通过，实际:\n${bag.report()}")
            val js = mm.generateJs()
            val f = File.createTempFile("subl-me2e-", ".js")
            f.writeText(js)
            return try {
                val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor(), "node 退出码非 0，输出:\n$out\n生成 JS:\n$js")
                out.trim()
            } finally { f.delete() }
        } finally { dir.deleteRecursively() }
    }

    /** core 统一标准库（仓库根 core/ 包目录）载入临时树规格：key 形如 "core/collection/list.subl"。
     *  v4 改名后标准库位于仓库根 sublimation/core，测试内联整包（stdlib → core）。 */
    private fun coreSpec(): Map<String, String> {
        val root = File("core")
        val out = LinkedHashMap<String, String>()
        for (f in root.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }
                .sortedBy { it.relativeTo(root).path }) {
            out["core/" + f.relativeTo(root).path.replace('\\', '/')] = f.readText()
        }
        return out
    }

    /** 同 runTree，但把 stdin 喂给 node 进程（P3 readLine 验收用） */
    private fun runTreeIn(spec: Map<String, String>, stdin: String): String {
        val dir = Files.createTempDirectory("subl-mod-e2e").toFile()
        try {
            for ((rel, content) in spec) {
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertFalse(bag.hasError, "语义检查应通过，实际:\n${bag.report()}")
            val js = mm.generateJs()
            val f = File.createTempFile("subl-me2e-", ".js")
            f.writeText(js)
            return try {
                val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
                p.outputStream.use { it.write(stdin.toByteArray()) }
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor(), "node 退出码非 0，输出:\n$out\n生成 JS:\n$js")
                out.trim()
            } finally { f.delete() }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `M8 根模块 main 调子模块函数`() {
        assumeTrue(nodeAvailable())
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(core.gcd(3, 4)) }",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        assertEquals("7", runTree(spec))
    }

    @Test
    fun `M5 挂载后跨模块调用`() {
        assumeTrue(nodeAvailable())
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "project/main.subl" to "fun main() { unchecked print(core.gcd(20, 22)) }",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        assertEquals("42", runTree(spec))
    }

    @Test
    fun `M5 挂载传递 - project 经 core 看到 foo`() {
        assumeTrue(nodeAvailable())
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "core/core.settings" to "mount foo",
            "project/main.subl" to "fun main() { unchecked print(foo.bar()) }",
            "core/mid.subl" to "fun via(): Nat = foo.bar()",
            "foo/f.subl" to "fun bar(): Nat = 7",
        )
        assertEquals("7", runTree(spec))
    }

    @Test
    fun `M6 impl 挂载 - 跨模块字典分发`() {
        assumeTrue(nodeAvailable())
        val spec = mapOf(
            "d/d.settings" to "mount core",
            "core/c.subl" to
                "struct Point(x: Nat)\n" +
                "class Show { fun show(): Str  fun getX(): Nat }\n" +
                "impl Show for Point { fun show(): Str = \"P\"\nfun getX(): Nat = x }",
            "d/main.subl" to
                "fun main() { var p = core.Point(5)\nunchecked print(show(p))\nunchecked print(p.getX()) }",
        )
        // 自由式 show(p)、点号式 p.getX() 都走跨模块字典分发；getX 体内裸字段 x → __self.x
        assertEquals("P\n5", runTree(spec))
    }

    @Test
    fun `M3 多模块协作 - 数据模块函数被主模块用`() {
        assumeTrue(nodeAvailable())
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(data.shout(\"hi\")) }",
            "data/stringData.subl" to "fun shout(s: Str): Str = s",
            "data/numberData.subl" to "fun twice(n: Nat): Nat = n + n",
        )
        assertEquals("hi", runTree(spec))
    }

    // ============ P2 core 标准库（决策 78，用户拍板 B：隐式挂载；v4 改名 stdlib → core） ============

    @Test
    fun `P2 range 走 size 得 3`() {
        assumeTrue(nodeAvailable())
        assertEquals("3", runTree(coreSpec() + mapOf(
            "main.subl" to "@impure fun main() {\nprint(size(range(0, 3)))\n}",
        )))
    }

    @Test
    fun `P2 map 加一后长度 2 求和 5`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = Cons(1, Cons(2, Nil()))\n" +
            "print(xs.map({ x -> x + 1 }).size())\n" +
            "print(xs.map({ x -> x + 1 }).fold(0) { acc, x => acc + x })\n" +
            "}"
        assertEquals("2\n5", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `P2 打印 1 到 10 的平方 - 平方和为 385`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = range(1, 11)\n" +
            "print(xs.map({ x -> x * x }).fold(0) { x, acc -> acc + x })\n" +
            "}"
        assertEquals("385", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `P2 隐式挂载 - 深处子模块也能引用 core 标准库`() {
        assumeTrue(nodeAvailable())
        assertEquals("3", runTree(coreSpec() + mapOf(
            "a/b/c/main.subl" to "@impure fun main() {\nprint(size(range(0, 3)))\n}",
        )))
    }

    // ============ v2.0 标准库补全（2026-10-10） ============

    @Test
    fun `v2 标准库 - strSplit 返回数组化 List`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var parts = strSplit(\"a,b,c\", \",\")\n" +
            "print(parts.size())\n" +
            "print(parts.joinToString(\"-\"))\n" +
            "}"
        assertEquals("3\na-b-c", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 标准库 - List 补全 any 找不到返回 false`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = range(1, 4)\n" +
            "print(xs.any({ x -> x > 5 }))\n" +
            "print(xs.all({ x -> x > 0 }))\n" +
            "}"
        assertEquals("false\ntrue", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 标准库 - List indexOf slice sortBy`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = range(1, 5)\n" +
            "when(xs.indexOf(2)) { is Some(i) => print(i); None => print(\"x\") }\n" +
            "print(xs.slice(1, 3).joinToString(\",\"))\n" +
            "print(xs.sortBy({ a, b -> a > b }).joinToString(\",\"))\n" +
            "print(xs.sortBy({ a, b -> a < b }).joinToString(\",\"))\n" +
            "}"
        assertEquals("1\n2,3\n4,3,2,1\n1,2,3,4", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 标准库 - IO 扩展 appendFile fileExists readDir`() {
        assumeTrue(nodeAvailable())
        val dir = Files.createTempDirectory("subl-v2-io").toFile()
        try {
            val p1 = File(dir, "a.txt").absolutePath.replace("\\", "\\\\")
            val d = dir.absolutePath.replace("\\", "\\\\")
            val main = "@impure fun main() {\n" +
                "writeFile(\"$p1\", \"x\")\n" +
                "appendFile(\"$p1\", \"y\")\n" +
                "var r = readFile(\"$p1\")\n" +
                "when(r) { is Ok(s) => print(s); is Err(e) => print(e) }\n" +
                "print(fileExists(\"$p1\"))\n" +
                "var rd = readDir(\"$d\")\n" +
                "when(rd) { is Ok(xs) => print(xs.size()); is Err(e) => print(e) }\n" +
                "}"
            assertEquals("xy\ntrue\n1", runTree(coreSpec() + mapOf("main.subl" to main)))
        } finally { dir.deleteRecursively() }
    }

    // ============ P3 IO（v1.0 计划 §5） ============

    @Test
    fun `P3 getArgs 无参数 - size 0`() {
        assumeTrue(nodeAvailable())
        assertEquals("0", runTree(coreSpec() + mapOf(
            "main.subl" to "@impure fun main() {\nprint(size(getArgs()))\n}",
        )))
    }

    @Test
    fun `P3 writeFile 写文件后 readFile 读回`() {
        assumeTrue(nodeAvailable())
        val f = File.createTempFile("subl-p3-wf", ".txt")
        val p = f.absolutePath.replace("\\", "\\\\")
        // P5：readFile 返回 Result<Str,Str>，解构 Ok/Err 取内容
        val main = "@impure fun main() {\nwriteFile(\"$p\", \"hi\")\nvar r = readFile(\"$p\")\n" +
            "when(r) {\n    is Ok(s) => print(s)\n    is Err(e) => print(e)\n}\n}"
        try {
            assertEquals("hi", runTree(coreSpec() + mapOf("main.subl" to main)))
        } finally { f.delete() }
    }

    @Test
    fun `P3 readLine 读 stdin 该行`() {
        assumeTrue(nodeAvailable())
        // P5：readLine 返回 Result<Str,Str>，解构 Ok/Err 取该行
        val main = "@impure fun main() {\nvar r = readLine()\n" +
            "when(r) {\n    is Ok(s) => print(s)\n    is Err(e) => print(e)\n}\n}"
        assertEquals("hello", runTreeIn(coreSpec() + mapOf("main.subl" to main), "hello\n"))
    }

    // ============ P5 错误处理 Result（v1.0 计划 §7.4 验收；决策 81，用户拍板候选 A） ============

    @Test
    fun `P5 readFile 读不存在返回 Err 分支`() {
        assumeTrue(nodeAvailable())
        // 读不存在的文件 → Err 分支（平台无关，只断言命中 Err 分支）
        val main = "@impure fun main() {\nvar r = readFile(\"C:/no/such/subl-file.txt\")\n" +
            "when(r) {\n    is Ok(_) => print(\"ok\")\n    is Err(e) => print(\"err\")\n}\n}"
        assertEquals("err", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `P5 readFile 写后读回返回 Ok 分支`() {
        assumeTrue(nodeAvailable())
        val f = File.createTempFile("subl-p5-rf", ".txt")
        val p = f.absolutePath.replace("\\", "\\\\")
        val main = "@impure fun main() {\nwriteFile(\"$p\", \"ok content\")\nvar r = readFile(\"$p\")\n" +
            "when(r) {\n    is Ok(s) => print(s)\n    is Err(e) => print(\"err \" + e)\n}\n}"
        try {
            assertEquals("ok content", runTree(coreSpec() + mapOf("main.subl" to main)))
        } finally { f.delete() }
    }

    @Test
    fun `P5 unwrapOr 和 mapOk 链式组合`() {
        assumeTrue(nodeAvailable())
        // mapOk 的 U 由 lambda 反推（点号方法 x.length），unwrapOr 消费——嵌套泛型调用即 §7.3 用法
        val main = "@impure fun main() {\nwriteFile(\"p5-mr.txt\", \"hello!\")\n" +
            "var r = mapOk(readFile(\"p5-mr.txt\"), { x -> x.length })\n" +
            "print(unwrapOr(r, 0))\n}"
        try {
            assertEquals("6", runTree(coreSpec() + mapOf("main.subl" to main)))
        } finally { File("p5-mr.txt").delete() }
    }

    // ============ P6 泛型约束字典透传（v1.0 计划 §8.4 验收；决策 82，Max 倾向） ============

    @Test
    fun `P6 showAll 整数列表输出 12`() {
        assumeTrue(nodeAvailable())
        // 约束 T: Show——类型参数约束经字典槽透传（§8.2：f 的 JS 签名带 d_Show_T 隐藏首参）
        val main = "class Show[T] { fun show(): Str }\n" +
            "impl Show for Nat { fun show(): Str = toStr(self) }\n" +
            "fun showAll[T: Show](xs: List[T]): Str = xs.fold(\"\") { acc, x => acc + x.show() }\n" +
            "@impure fun main() {\n" +
            "print(showAll(Cons(1, Cons(2, Nil()))))\n}"
        assertEquals("12", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    @Test
    fun `P6 printAll 字符串列表输出 x`() {
        assumeTrue(nodeAvailable())
        val main = "class Show[T] { fun show(): Str }\n" +
            "impl Show for Str { fun show(): Str = self }\n" +
            "fun printAll[T: Show](xs: List[T]): Str = xs.fold(\"\") { acc, x => acc + x.show() }\n" +
            "@impure fun main() {\n" +
            "print(printAll(Cons(\"x\", Nil())))\n}"
        assertEquals("x", runTree(coreSpec() + mapOf("main.subl" to main)))
    }

    // ============ v2.0 同名方法重载（决策 92 配套：跨容器共用方法名，按接收者路由） ============

    @Test
    fun `v2 同名重载 - List Set Map Optional 的 size map contains get 各归各桶`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = Cons(1, Cons(2, Nil()))\n" +
            "@mut var s: Set[Nat] = setEmpty()\n" +
            "s = s.add(3)\n" +
            "s = s.add(3)\n" +   // add 判重：不会重复插入
            "@mut var m: Map[Str, Nat] = mapEmpty()\n" +
            "m = put(m, \"a\", 1)\n" +
            "m = put(m, \"b\", 2)\n" +
            "print(\"listSize=\${xs.size()}\")\n" +
            "print(\"setSize=\${s.size()}\")\n" +
            "print(\"mapSize=\${m.size()}\")\n" +
            "print(\"mapLen=\${xs.map({ x -> x * 2 }).size()}\")\n" +
            "print(\"setHas=\${s.contains(3)}\")\n" +
            "print(\"listHas=\${xs.contains(2)}\")\n" +
            "var g = m.get(\"a\").getOrElse(0)\n" +
            "print(\"mapGet=\${g}\")\n" +
            "print(\"optMap=\${Some(5).map({ x -> x + 1 }).getOrElse(0)}\")\n" +
            "}"
        assertEquals(
            "listSize=2\nsetSize=1\nmapSize=2\nmapLen=2\nsetHas=true\nlistHas=true\nmapGet=1\noptMap=6",
            runTree(coreSpec() + mapOf("main.subl" to main)),
        )
    }

    @Test
    fun `v2 跨模块限定自由函数重载 - core filter map 路由到 List 版`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = range(1, 6)\n" +
            "var evens = core.collection.filter(xs, { x -> x % 2 == 0 })\n" +    // filter 跨 List/Optional/Result/Set 重载——限定调用须路由到 List 版
            "var doubled = evens.map({ x -> x * 10 })\n" +      // map 走类型类方法（Functor for List）
            "var j = joinToString(doubled, \", \")\n" +
            "print(\"evens=\${j}\")\n" +
            "print(\"sum=\${sum(doubled)}\")\n" +
            "print(\"size=\${size(evens)}\")\n" +                 // size 跨 List/Set/Map 重载
            "}"
        assertEquals(
            "evens=20, 40\nsum=60\nsize=2",
            runTree(coreSpec() + mapOf("main.subl" to main)),
        )
    }

    @Test
    fun `v2 新集合 API - sum maxNat minNat union intersect keys values`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var xs = Cons(4, Cons(1, Cons(3, Nil())))\n" +
            "@mut var s: Set[Nat] = setEmpty()\n" +
            "s = s.add(1)\n" +
            "s = s.add(2)\n" +
            "@mut var t: Set[Nat] = setEmpty()\n" +
            "t = t.add(2)\n" +
            "t = t.add(3)\n" +
            "@mut var m: Map[Str, Nat] = mapEmpty()\n" +
            "m = put(m, \"a\", 1)\n" +
            "m = put(m, \"b\", 2)\n" +
            "print(\"sum=\${xs.sum()}\")\n" +
            "print(\"max=\${xs.maxNat().getOrElse(0)}\")\n" +
            "print(\"min=\${xs.minNat().getOrElse(0)}\")\n" +
            "print(\"uni=\${s.union(t).size()}\")\n" +
            "print(\"inter=\${s.intersect(t).size()}\")\n" +
            "print(\"keys=\${m.keys().size()}\")\n" +
            "print(\"vals=\${m.values().size()}\")\n" +
            "}"
        assertEquals(
            "sum=8\nmax=4\nmin=1\nuni=3\ninter=1\nkeys=2\nvals=2",
            runTree(coreSpec() + mapOf("main.subl" to main)),
        )
    }

    // ============ 空安全运算符已移除：?: / ?. / >: / Some→T 隐式解构 ============

    @Test
    fun `空安全 - elvis 与 safecall 已从词法层移除`() {
        // 移除空安全糖后 `?:` / `?.` 不再是合法 token——词法层直接报「字符不认识」
        val e1 = assertFailsWith<LexFailure> { parseSource("fun f(): Null = a ?: b", "t.subl") }
        assertTrue(e1.message!!.contains("不认识"), "实际: ${e1.message}")
        val e2 = assertFailsWith<LexFailure> { parseSource("fun f(): Null = a?.b()", "t.subl") }
        assertTrue(e2.message!!.contains("不认识"), "实际: ${e2.message}")
    }

    @Test
    fun `空安全 - Some 传基层形参不再隐式解构`() {
        // 移除隐式转换：takeRat(Some(5)) 不再自动解包——Some(5) 是 Optional[Nat]，与 Rat 形参不符即报错
        val main = "fun takeRat(n: Rat): Nat = 1\n" +
            "@impure fun main() { var x = takeRat(Some(5)) }"
        val ast = parseSource(main, "main.subl")
        val bag = Checker(ast).run()
        assertEquals(true, bag.hasError, "Some 不再隐式解包传入 Rat 形参，应报类型错误，实际:\n${bag.report()}")
    }

    @Test
    fun `v2 空安全 - 类型测 is T`() {
        assumeTrue(nodeAvailable())
        val main = "fun describe(x: Any): Bool = x is Rat\n" +
            "@impure fun main() {\n" +
            "var b1 = describe(5)\n" +
            "var b2 = describe(\"a\")\n" +
            "print(\"r=\${b1}\")\n" +
            "print(\"s=\${b2}\")\n" +
            "}"
        assertEquals(
            "r=true\ns=false",
            runTree(mapOf("main.subl" to main)),
        )
    }

    // ============ v2.0 异步传染（决策 93，异步 §2）：@async 函数 + 挂起点 await ============

    @Test
    fun `v2 异步 - 传染链生成 async 和 await`() {
        assumeTrue(nodeAvailable())
        val main = "@async fun tick(): Nat = 1\n" +
            "@async fun fetch(): Nat {\n" +
            "  var x = tick()\n" +
            "  return x + 1\n" +
            "}\n" +
            "@async @impure fun main() {\n" +
            "  print(\"f=\${fetch()}\")\n" +
            "}"
        assertEquals(
            "f=2",
            runTree(mapOf("main.subl" to main)),
        )
    }

    @Test
    fun `v2 异步 - 同步上下文调用 async 函数报错`() {
        assumeTrue(nodeAvailable())
        // main 天然 @async（§5.2）→ 不报错；普通具名函数（非 main）同步上下文调 @async 函数 → E-NEED-ASYNC
        val main = "@async fun tick(): Nat = 1\n" +
            "fun helper(): Nat {\n" +
            "  var x = tick()\n" +
            "  return x\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var y = helper()\n" +
            "  print(\"y=\$y\")\n" +
            "}"
        // 编译必须失败：E-NEED-ASYNC（同步上下文调 @async 函数）
        val dir = Files.createTempDirectory("subl-async-neg").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NEED-ASYNC，实际通过")
            assertTrue(bag.report().contains("E-NEED-ASYNC"), "诊断应含 E-NEED-ASYNC，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    // ============ v2.0 异步 Task（决策 93，异步 §1/§5.3）：TaskExpr+start/join/isAlive ============

    @Test
    fun `v2 异步 - Task 创建 start join isAlive`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "var t = Task {\n" +
            "  print(\"A\")\n" +
            "  42\n" +
            "}\n" +
            "var a0 = t.isAlive()\n" +
            "t.start()\n" +
            "var a1 = t.isAlive()\n" +
            "var j = t.join()\n" +
            "print(\"alive=\$a0,\$a1 j=\$j\")\n" +
            "}"
        assertEquals(
            "A\nalive=false,true j=42",
            runTree(mapOf("main.subl" to main)),
        )
    }

    @Test
    fun `v2 异步 - Task 未 start 即 join 报 E-NOT-STARTED`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "  var t = Task { 42 }\n" +
            "  var j = t.join()\n" +
            "  print(\"j=\$j\")\n" +
            "}"
        val dir = Files.createTempDirectory("subl-task-neg").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NOT-STARTED，实际通过")
            assertTrue(bag.report().contains("E-NOT-STARTED"), "诊断应含 E-NOT-STARTED，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `v2 异步 - 同步函数 join 报 E-NEED-ASYNC`() {
        assumeTrue(nodeAvailable())
        val main = "fun caller(): Nat {\n" +
            "  var t = Task { 42 }\n" +
            "  t.start()\n" +
            "  var j = t.join()\n" +
            "  return j\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var y = caller()\n" +
            "  print(\"y=\$y\")\n" +
            "}"
        val dir = Files.createTempDirectory("subl-task-sync").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NEED-ASYNC，实际通过")
            assertTrue(bag.report().contains("E-NEED-ASYNC"), "诊断应含 E-NEED-ASYNC，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    // ============ v2.0 异步 Channel（决策 93，异步 §3/§5.4）：创建/send/receive/close + CH-5/CH-6 ============

    @Test
    fun `v2 异步 - Channel 有缓冲 send receive 智能转换解包`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "  var ch = Channel<Nat>(1)\n" +
            "  ch.send(42)\n" +
            "  var x = ch.receive()\n" +   // 本地未关闭通道 → CH-5 智能转换返回 Nat 并解包
            "  print(\"x=\$x\")\n" +
            "}"
        assertEquals("x=42", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - Channel 参数位 receive 返回 Optional 用 when 解包`() {
        assumeTrue(nodeAvailable())
        val main = "@impure @async fun consume(ch: Channel[Nat]): Null {\n" +
            "  var x = ch.receive()\n" +   // 参数位 → T?（保守，CH-5 表）
            "  when (x) {\n" +
            "    is Some(v) => print(\"val=\$v\")\n" +
            "    None => print(\"closed\")\n" +
            "  }\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var ch = Channel<Nat>(1)\n" +
            "  ch.send(7)\n" +
            "  var t = Task { consume(ch) }\n" +
            "  t.start()\n" +
            "  t.join()\n" +
            "}"
        assertEquals("val=7", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - Channel close 后 send 报 E-CHANNEL-CLOSED`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "  var ch = Channel<Nat>(1)\n" +
            "  ch.close()\n" +
            "  ch.send(42)\n" +
            "}"
        val dir = Files.createTempDirectory("subl-chan-closed").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-CHANNEL-CLOSED，实际通过")
            assertTrue(bag.report().contains("E-CHANNEL-CLOSED"), "诊断应含 E-CHANNEL-CLOSED，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `v2 异步 - Channel 同步函数 send 报 E-NEED-ASYNC`() {
        assumeTrue(nodeAvailable())
        val main = "fun helper(): Bool {\n" +
            "  var ch = Channel<Nat>(1)\n" +
            "  var ok = ch.send(1)\n" +
            "  return ok\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var y = helper()\n" +
            "  print(\"y=\$y\")\n" +
            "}"
        val dir = Files.createTempDirectory("subl-chan-sync").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NEED-ASYNC，实际通过")
            assertTrue(bag.report().contains("E-NEED-ASYNC"), "诊断应含 E-NEED-ASYNC，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    // ============ v2.0 异步 Mutex（root doc《异步》：lock/unlock/withLock） ============

    @Test
    fun `v2 异步 - Mutex lock unlock 保护临界区`() {
        assumeTrue(nodeAvailable())
        val main = "@mut struct Counter(@mut n: Nat)\n" +
            "@impure @async fun inc(m: Mutex, c: @mut Counter): Null {\n" +
            "  m.lock()\n" +
            "  var old = c.n\n" +
            "  c.n = old + 1\n" +
            "  m.unlock()\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var m = Mutex()\n" +
            "  var c : @mut Counter = Counter(0)\n" +
            "  var t = Task { inc(m, c) }\n" +
            "  t.start()\n" +
            "  t.join()\n" +
            "  print(\"n=\" + c.n)\n" +
            "}\n"
        assertEquals("n=1", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - Mutex 同步函数 lock 报 E-NEED-ASYNC`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun helper(m: Mutex): Null {\n" +
            "  m.lock()\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var m = Mutex()\n" +
            "  helper(m)\n" +
            "}\n"
        val dir = Files.createTempDirectory("subl-mutex-sync").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NEED-ASYNC，实际通过")
            assertTrue(bag.report().contains("E-NEED-ASYNC"), "诊断应含 E-NEED-ASYNC，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `v2 异步 - Mutex withLock 持锁执行`() {
        assumeTrue(nodeAvailable())
        val main = "@mut struct Counter(@mut n: Nat)\n" +
            "@impure @async fun bump(m: Mutex, c: @mut Counter): Null {\n" +
            "  m.withLock(fun() { c.n = c.n + 1 })\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var m = Mutex()\n" +
            "  var c : @mut Counter = Counter(0)\n" +
            "  var t = Task { bump(m, c) }\n" +
            "  t.start()\n" +
            "  t.join()\n" +
            "  print(\"n=\" + c.n)\n" +
            "}\n"
        assertEquals("n=1", runTree(mapOf("main.subl" to main)))
    }

    // ============ v2.0 异步 @atomic（root doc《异步》：原子类型注解） ============

    @Test
    fun `v2 异步 - @atomic 标量类型注解读写`() {
        assumeTrue(nodeAvailable())
        val main = "@impure fun main() {\n" +
            "  @mut var x : @atomic Nat = 0\n" +
            "  x = x + 1\n" +
            "  print(\"x=\$x\")\n" +
            "}\n"
        assertEquals("x=1", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - @atomic 作形参类型注解`() {
        assumeTrue(nodeAvailable())
        val main = "@impure @async fun proc(m: @atomic Nat): Null {\n" +
            "  print(\"m=\$m\")\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var t = Task { proc(41) }\n" +
            "  t.start()\n" +
            "  t.join()\n" +
            "}\n"
        assertEquals("m=41", runTree(mapOf("main.subl" to main)))
    }

    // ============ 空安全 Some→T 隐式转换已移除（原 root doc《空安全系统》隐式解包） ============

    @Test
    fun `空安全 - 变量位 Some 不再隐式转 T`() {
        // 移除隐式转换：var x: Nat = Some(1) 不再自动解包——Some(1) 是 Optional[Nat]，报 E-TYPE-MISMATCH
        val main = "@impure fun main() {\n" +
            "  var x : Nat = Some(1)\n" +
            "  print(\"x=\$x\")\n" +
            "}\n"
        val dir = Files.createTempDirectory("subl-var-unwrap-mismatch").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-TYPE-MISMATCH，实际通过")
            assertTrue(bag.report().contains("E-TYPE-MISMATCH"), "应含 E-TYPE-MISMATCH，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `空安全 - 函数返回位 Some 不再隐式转 T`() {
        // 移除隐式转换：fun identity(n: Nat): Nat = Some(n) 不再自动解包，报 E-TYPE-MISMATCH
        val main = "@impure fun identity(n: Nat): Nat = Some(n)\n" +
            "@impure fun main() {\n" +
            "  var y = identity(9)\n" +
            "  print(\"y=\$y\")\n" +
            "}\n"
        val ast = parseSource(main, "main.subl")
        val bag = Checker(ast).run()
        assertEquals(true, bag.hasError, "Some 不再隐式解包为返回类型，应报类型错误，实际:\n${bag.report()}")
    }

    // ============ v2.0 异步 Flow 冷流（root doc《异步》emit/collect/map） ============

    @Test
    fun `v2 异步 - Flow collect 消费发射值`() {
        assumeTrue(nodeAvailable())
        val main = "@impure @async fun main() {\n" +
            "  var fl = Flow { emit(1); emit(2); emit(3) }\n" +
            "  fl.collect(fun(x: Nat) { print(x) })\n" +
            "}\n"
        assertEquals("1\n2\n3", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - Flow map 派生再 collect`() {
        assumeTrue(nodeAvailable())
        val main = "@impure @async fun main() {\n" +
            "  var fl = Flow { emit(1); emit(2) }\n" +
            "  var f2 = fl.map(fun(x: Nat) = x * 10)\n" +
            "  f2.collect(fun(y: Nat) { print(y) })\n" +
            "}\n"
        assertEquals("10\n20", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - Flow 作形参类型注解 collect`() {
        assumeTrue(nodeAvailable())
        val main = "@impure @async fun consume(fl: Flow[Nat]): Null {\n" +
            "  fl.collect(fun(x: Nat) { print(x) })\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var fl = Flow { emit(7); emit(8) }\n" +
            "  var t = Task { consume(fl) }\n" +
            "  t.start()\n" +
            "  t.join()\n" +
            "}\n"
        assertEquals("7\n8", runTree(mapOf("main.subl" to main)))
    }

    // ============ v2.0 可变性自然命题（副作用 §4.2 immutable + §6.2 untouch） ============

    @Test
    fun `v2 可变性 - 废弃后 immutable 命题不可用`() {
        assumeTrue(nodeAvailable())
        // ⚠️ 2026-10-12 用户拍板：immutable<T> 自然命题已废弃（不再生成事实）——后续置条件不可证
        val main = "struct Test(a: Nat, @mut b: Nat)\n" +
            "fun f() : Null <immutable<Test>> {}\n" +
            "@impure fun main() { print(\"ok\") }\n"
        val dir = Files.createTempDirectory("subl-immutable-dep").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-POST-UNPROVEN，实际通过")
            assertTrue(bag.report().contains("E-POST-UNPROVEN"), "诊断应含 E-POST-UNPROVEN，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `v2 可变性 - @mut 字段结构体不注入 immutable`() {
        assumeTrue(nodeAvailable())
        val main = "struct Test(a: Nat, @mut b: Nat)\n" +
            "fun f() : Null <immutable<Test>> {}\n" +
            "@impure fun main() { print(\"ok\") }\n"
        val dir = Files.createTempDirectory("subl-immutable-neg").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-POST-UNPROVEN，实际通过")
            assertTrue(bag.report().contains("E-POST-UNPROVEN"), "诊断应含 E-POST-UNPROVEN，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun `v2 空安全 - if x is T 分支内智能转换收窄为 T`() {
        assumeTrue(nodeAvailable())
        // o: Any 参数，`if (o is Point)` 分支内 o 从 Any 收窄为 Point（Kotlin 智能转换扩展）
        val main = "struct Point(x: Nat, y: Nat)\n" +
            "@impure fun describe(o: Any): Null {\n" +
            "  if (o is Point) {\n" +
            "    var pt: Point = o\n" +
            "    print(\"pt.x=\${pt.x}\")\n" +
            "  }\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var p: Point = Point(3, 4)\n" +
            "  describe(p)\n" +
            "}\n"
        assertEquals("pt.x=3", runTree(mapOf("main.subl" to main)))
    }

    // ============ v2.0 异步 stage 6：IO 集成（readFile/readLine/writeFile @async + sleep 内建） ============

    @Test
    fun `v2 异步 - sleep 内建生成 await 挂起点并运行`() {
        assumeTrue(nodeAvailable())
        // main 天然 @async（决策 93）→ sleep 是挂起点，产物含 await Promise + setTimeout
        val main = "@impure fun main() {\n" +
            "  sleep(10)\n" +
            "  print(\"done\")\n" +
            "}\n"
        assertEquals("done", runTree(mapOf("main.subl" to main)))
    }

    @Test
    fun `v2 异步 - IO readFile 标 async：同步函数调用报 E-NEED-ASYNC`() {
        assumeTrue(nodeAvailable())
        val main = "fun sync(): Null {\n" +
            "  var r = readFile(\"x.txt\")\n" +
            "  Null\n" +
            "}\n" +
            "@impure fun main() { sync() }\n"
        val dir = Files.createTempDirectory("subl-io-async").toFile()
        try {
            File(dir, "main.subl").writeText(main)
            val mm = MultiModule(dir)
            val bag = mm.checkAll()
            assertTrue(bag.hasError, "应报 E-NEED-ASYNC，实际通过")
            assertTrue(bag.report().contains("E-NEED-ASYNC"), "诊断应含 E-NEED-ASYNC，实际:\n${bag.report()}")
        } finally { dir.deleteRecursively() }
    }

    // ============ v2.0 异步 stage 5：`if (!ch.isClosed())` 分支内 receive 智能转换 ============

    @Test
    fun `v2 异步 - if not isClosed 分支内参数位 receive 智能转换解包`() {
        assumeTrue(nodeAvailable())
        // ch 是**参数**（非本地创建）→ CH-5 不适用；但 `if (!ch.isClosed())` 分支内（阶段5 openChannels）
        // receive 仍智能转换为 T（生成解包）。send 一个值让运行可验证。
        val main = "@impure @async fun consume(ch: Channel[Nat]): Null {\n" +
            "  if (!ch.isClosed()) {\n" +
            "    var v: Nat = ch.receive()\n" +
            "    print(\"v=\$v\")\n" +
            "  }\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "  var ch: Channel[Nat] = Channel<Nat>(1)\n" +
            "  ch.send(42)\n" +
            "  consume(ch)\n" +
            "}\n"
        assertEquals("v=42", runTree(mapOf("main.subl" to main)))
    }

    // ============ v2.0 基本库：zip 恢复（类型参数 A/B 避开 Cons 的 T 撞名） ============

    @Test
    fun `v2 基本库 - zip 恢复可用 类型参数撞名绕过 occurs check`() {
        assumeTrue(nodeAvailable())
        // Cons 构造子枚举类型参数名 T 与 zip 自用 T 撞名 → extracted 绑定 `T := (T,U)` 触发 occurs
        // check 自指误判。zip 类型参数改名 A/B（用户避开重名，编译器哲学）后 core 可编译运行。
        val src = "@impure fun main() {\n" +
            "  var xs: List[Nat] = range(1, 4)\n" +
            "  var z = zip(xs, xs)\n" +
            "  print(\"zip ok\")\n" +
            "}\n"
        // 内联 core/collection/list.subl（含 range + zip）——runTree 不自动挂载真实 core
        assertEquals("zip ok", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v2 基本库 - distinct toSet toMap 常用集合函数`() {
        assumeTrue(nodeAvailable())
        val src = "@impure fun main() {\n" +
            "  var xs: List[Nat] = range(1, 4)\n" +
            "  var s = toSet(xs)\n" +
            "  var z = zip(xs, xs)\n" +
            "  var m = toMap(z)\n" +
            "  var d = distinct(append(xs, xs))\n" +
            "  print(\"s=\${s.size()} m=\${m.size()} d=\${d.size()}\")\n" +
            "}\n"
        // toSet/toMap 与 distinct/zip 同在 core/collection 模块——整包内联
        assertEquals("s=3 m=3 d=3", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v30 - setOf 可变元组时 Set map forEach 用户示例链路`() {
        assumeTrue(nodeAvailable())
        val src = "fun grade(score: Nat): Str =\n" +
            "    when {\n" +
            "        score in (90..100) => \"A\"\n" +
            "        score in (60..79) => \"C\"\n" +
            "        else => \"D\"\n" +
            "    }\n" +
            "@impure fun main() {\n" +
            "  var s = setOf(54, 75, 100)\n" +
            "  forEach(map(s) { grade(_) }) { x => print(x) }\n" +
            "}\n"
        assertEquals("D\nC\nA", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v30b - 裸名 setOf map forEach 方法糖链路`() {
        assumeTrue(nodeAvailable())
        val src = "@impure fun main() {\n" +
            "  var s = setOf(1, 2, 3)\n" +
            "  s.forEach { x => print(x) }\n" +
            "}\n"
        assertEquals("1\n2\n3", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v30c - 用户完整示例 Student Grade 约束 grade setOf map forEach 全链路`() {
        assumeTrue(nodeAvailable())
        val src = "struct Student(name: Str, score: Nat,)\n" +
            "enum Grade { A, B, C, D, }\n" +
            "class Gradable {\n" +
            "    fun grade() : Grade\n" +
            "}\n" +
            "impl Gradable for Student {\n" +
            "    fun grade<score <= 100> : Grade =\n" +
            "        when {\n" +
            "            score in (90..100) => Grade::A\n" +
            "            score in (80..89) => Grade::B\n" +
            "            score in (60..79) => Grade::C\n" +
            "            else => Grade::D\n" +
            "        }\n" +
            "}\n" +
            "@impure\n" +
            "fun main() {\n" +
            "    var set = setOf(\n" +
            "        Student(\"孙笑川\", 54),\n" +
            "        Student(\"丰川祥子\", 75),\n" +
            "        Student(\"37\", 100),\n" +
            "        Student(\"函子酱\", 13 + 78),\n" +
            "    )\n" +
            "    set\n" +
            "        .map { (_.name, _.score, _.grade()) }\n" +
            "        .forEach { (name, score, grade) => print(\"\$name:\$score:\$grade\") }\n" +
            "}\n"
        assertEquals("孙笑川:54:D\n丰川祥子:75:C\n37:100:A\n函子酱:91:A", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v30 完美函数类型 - map filter fold 链零魔法 + 多态函数作实参`() {
        assumeTrue(nodeAvailable())
        // memo 验收目标：map/filter/fold 零魔法成立；多态匿名函数作实参进 List.map 的 (T)=>U 槽
        val src = "@impure fun main() {\n" +
            "  var xs = Cons(1, Cons(2, Cons(3, Cons(4, Nil()))))\n" +
            "  var ys = xs.filter { x -> x > 2 }.map { x -> x * 2 }\n" +
            "  print(ys)\n" +
            "  var s = xs.fold(0) { acc, x => acc + x }\n" +
            "  print(s)\n" +
            "  var zs = xs.map(fun[T](x: T): T = x)\n" +
            "  print(zs)\n" +
            "}"
        assertEquals("[6,8]\n10\n[1,2,3,4]", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v30 完美函数类型 - 方法路径作值绑定 self`() {
        assumeTrue(nodeAvailable())
        // `xs.map` 无括号作值 → 绑定 self 的 ( (T)=>U ) => List[U]；调用 m({x -> x*2}) 实例化反推 U
        val src = "@impure fun main() {\n" +
            "  var xs = Cons(1, Cons(2, Nil()))\n" +
            "  var m = xs.map\n" +
            "  print(m({x -> x * 2}))\n" +
            "}"
        assertEquals("[2,4]", runTree(coreSpec() + mapOf("main.subl" to src)))
    }
    // ============ v3.0 第三批：Collection 型类 / 扩展调用法 / Tuple 类型族 ============

    @Test
    fun `v3第三批 - contains size 扩展调用法与方法糖分派`() {
        assumeTrue(nodeAvailable())
        // 扩展调用法 x.contains(e)/x.size() 走方法糖（同名自由函数按接收者路由）；普通调用法 contains(xs,e) 走自由函数
        val src = "@impure fun main() {\n" +
            "  var xs = Cons(1, Cons(2, Nil()))\n" +
            "  print(xs.contains(2))\n" +
            "  print(xs.size())\n" +
            "  var ys = Cons(3, Cons(4, Nil()))\n" +
            "  print(contains(ys, 4))\n" +
            "}"
        assertEquals("true\n2\ntrue", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v3第三批 - Tuple 类型族 size isEmpty 全形态`() {
        assumeTrue(nodeAvailable())
        // EmptyTuple/SingleTuple/(T,U)/(T,U,V) 统一 Tuple 型类：emptyTuple() 构造零元组
        val src = "@impure fun main() {\n" +
            "  print(emptyTuple().size())\n" +
            "  print(emptyTuple().isEmpty())\n" +
            "  print(singleTuple(5).size())\n" +
            "  print(singleTuple(5).isEmpty())\n" +
            "  print((1, \"x\").size())\n" +
            "  print((1, \"x\", true).size())\n" +
            "}"
        assertEquals("0\ntrue\n1\nfalse\n2\n3", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    @Test
    fun `v3第三批 - List Result Optional 统一在 collection 且方法糖可用`() {
        assumeTrue(nodeAvailable())
        // 重组：List/Result/Optional 并入 core.collection——三者在同一模块仍互见（方法重载按接收者路由）
        val src = "@impure fun main() {\n" +
            "  var xs = Cons(1, Cons(5, Nil()))\n" +
            "  print(xs.map({ x -> x * 2 }))\n" +
            "  print(xs.size())\n" +
            "}"
        assertEquals("[2,10]\n2", runTree(coreSpec() + mapOf("main.subl" to src)))
    }

    // ============ core.async 内建运行时层（@root 白名单扩展：编译期 JS 特判 + 小运行时） ============

    @Test
    fun `core async 内建 Channel Flow Mutex Task 端到端`() {
        assumeTrue(nodeAvailable())
        // 不挂 stdlib：List 本地声明（数组化按枚举名 List 生效），全部内建走 builtin 表回退签名。
        // 通道 FIFO、Flow 冷流收集、Mutex 计数器、任务并发/串行组合各出一条断言。
        val src = "enum List[T] { Nil(), Cons(T, List[T]) }\n" +
            "@mut struct Counter(@mut n: Nat)\n" +
            "fun firstOr[T](xs: List[T], d: T): T = when(xs) {\n" +
            "    Nil => d\n" +
            "    is Cons(h, _) => h\n" +
            "}\n" +
            "@impure fun main() {\n" +
            "    var ch = channelFromList(Cons(1, Cons(2, Cons(3, Nil()))))\n" +
            "    var xs = channelDrain(ch)\n" +
            "    print(concat(\"first: \", toStr(firstOr(xs, 0))))\n" +
            "    var src = channelFromList(xs)\n" +
            "    var dst = Channel<Nat>(3)\n" +
            "    channelPipe(src, dst)\n" +
            "    sendAll(dst, Cons(7, Cons(8, Nil())))\n" +
            "    print(concat(\"piped: \", toStr(firstOr(channelDrain(dst), 0))))\n" +
            "    var fl = Flow {\n" +
            "        emit(1)\n" +
            "        emit(2)\n" +
            "        emit(3)\n" +
            "    }\n" +
            "    print(concat(\"flow count: \", toStr(collectCount(fl))))\n" +
            "    print(concat(\"flow all: \", toStr(collectAll(fl, { v -> v > 0 }))))\n" +
            "    var mu = Mutex()\n" +
            "    var c: @mut Counter = Counter(0)\n" +
            "    bumpCounter(mu, c)\n" +
            "    bumpPair(mu, mu, c, c)\n" +
            "    withMutex(mu, { print(\"locked\") })\n" +
            "    print(concat(\"counter: \", toStr(c.n)))\n" +
            "    runSeq({ print(\"A\") }, { print(\"B\") })\n" +
            "    runBoth({ print(\"X\") }, { print(\"Y\") })\n" +
            "    delay(1)\n" +
            "    print(\"done\")\n" +
            "}"
        assertEquals(
            "first: 1\npiped: 1\nflow count: 3\nflow all: true\nlocked\ncounter: 3\nA\nB\nX\nY\ndone",
            runTree(mapOf("main.subl" to src)),
        )
    }

    // ============ core 统一标准库：挂载完整包名（决策：`mount core.math` 形） ============

    @Test
    fun `core 包 - 点分完整包名挂载与前缀包隐式可见`() {
        assumeTrue(nodeAvailable())
        // 点分挂载 `mount core.math`：目标名 math 可调（math.gcd），前缀包 core 隐式挂载后
        // 全限定 core.math.gcd 亦可调；裸名 gcd 经挂载传递可达（print/concat/toStr 走内建表回退）。
        val spec = mapOf(
            "core/math/math.subl" to
                "fun gcd(a: Nat, b: Nat): Nat = if (b == 0) { a } else { unchecked var r = gcd(b, a % b); r }",
            "p/p.settings" to "mount core.math",
            "p/main.subl" to
                "@impure fun main() {\n" +
                "    print(concat(\"full=\", toStr(core.math.gcd(12, 8))))\n" +
                "    print(concat(\"short=\", toStr(math.gcd(12, 8))))\n" +
                "    print(concat(\"bare=\", toStr(gcd(12, 8))))\n" +
                "}",
        )
        assertEquals("full=4\nshort=4\nbare=4", runTree(spec))
    }

    @Test
    fun `core 包 - 根模块不经挂载直接全限定调用子包`() {
        assumeTrue(nodeAvailable())
        // M3：根模块本就透过父→子见 core/math——零挂载即可 core.math.gcd(...)；裸名不可（无挂载传递）。
        val spec = mapOf(
            "core/math/math.subl" to
                "fun gcd(a: Nat, b: Nat): Nat = if (b == 0) { a } else { unchecked var r = gcd(b, a % b); r }",
            "main.subl" to
                "@impure fun main() {\n" +
                "    print(concat(\"gcd=\", toStr(core.math.gcd(12, 8))))\n" +
                "}",
        )
        assertEquals("gcd=4", runTree(spec))
    }
}