package sugared.functor.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sugared.functor.checker.MultiModule
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

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

    // ============ P2 stdlib（决策 78，用户拍板 B：隐式挂载的 stdlib 模块） ============

    @Test
    fun `P2 range 走 listLength 得 3`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        assertEquals("3", runTree(mapOf(
            "stdlib/list.subl" to std,
            "main.subl" to "@unpure fun main() {\nprint(stdlib.listLength(stdlib.range(0, 3)))\n}",
        )))
    }

    @Test
    fun `P2 map 加一后长度 2 求和 5`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val main = "@unpure fun main() {\n" +
            "print(stdlib.listLength(stdlib.map(\\(x) => x + 1, stdlib.Cons(1, stdlib.Cons(2, stdlib.Nil())))))\n" +
            "print(stdlib.fold(0, \\(x, acc) => acc + x, stdlib.map(\\(x) => x + 1, stdlib.Cons(1, stdlib.Cons(2, stdlib.Nil())))))\n" +
            "}"
        assertEquals("2\n5", runTree(mapOf("stdlib/list.subl" to std, "main.subl" to main)))
    }

    @Test
    fun `P2 打印 1 到 10 的平方 - 平方和为 385`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val main = "@unpure fun main() {\n" +
            "print(stdlib.fold(0, \\(x, acc) => acc + x, stdlib.map(\\(x) => x * x, stdlib.range(1, 11))))\n" +
            "}"
        assertEquals("385", runTree(mapOf("stdlib/list.subl" to std, "main.subl" to main)))
    }

    @Test
    fun `P2 隐式挂载 - 深处子模块也能引用 stdlib`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        assertEquals("3", runTree(mapOf(
            "stdlib/list.subl" to std,
            "a/b/c/main.subl" to "@unpure fun main() {\nprint(stdlib.listLength(stdlib.range(0, 3)))\n}",
        )))
    }

    // ============ P3 IO（v1.0 计划 §5） ============

    @Test
    fun `P3 getArgs 无参数 - listLength 0`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        assertEquals("0", runTree(mapOf(
            "stdlib/list.subl" to std,
            "main.subl" to "@unpure fun main() {\nprint(stdlib.listLength(getArgs()))\n}",
        )))
    }

    @Test
    fun `P3 writeFile 写文件后 readFile 读回`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val res = File("stdlib/result.subl").readText()
        val f = File.createTempFile("subl-p3-wf", ".txt")
        val p = f.absolutePath.replace("\\", "\\\\")
        // P5：readFile 返回 Result<Str,Str>，解构 Ok/Err 取内容
        val main = "@unpure fun main() {\nwriteFile(\"$p\", \"hi\")\nvar r = readFile(\"$p\")\n" +
            "when(r) {\n    Ok(s) -> print(s)\n    Err(e) -> print(e)\n}\n}"
        try {
            assertEquals("hi", runTree(mapOf(
                "stdlib/list.subl" to std, "stdlib/result.subl" to res, "main.subl" to main)))
        } finally { f.delete() }
    }

    @Test
    fun `P3 readLine 读 stdin 该行`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val res = File("stdlib/result.subl").readText()
        // P5：readLine 返回 Result<Str,Str>，解构 Ok/Err 取该行
        val main = "@unpure fun main() {\nvar r = readLine()\n" +
            "when(r) {\n    Ok(s) -> print(s)\n    Err(e) -> print(e)\n}\n}"
        assertEquals("hello", runTreeIn(mapOf(
            "stdlib/list.subl" to std, "stdlib/result.subl" to res, "main.subl" to main), "hello\n"))
    }

    // ============ P5 错误处理 Result（v1.0 计划 §7.4 验收；决策 81，用户拍板候选 A） ============

    @Test
    fun `P5 readFile 读不存在返回 Err 分支`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val res = File("stdlib/result.subl").readText()
        // 读不存在的文件 → Err 分支（平台无关，只断言命中 Err 分支）
        val main = "@unpure fun main() {\nvar r = readFile(\"C:/no/such/subl-file.txt\")\n" +
            "when(r) {\n    Ok(_) -> print(\"ok\")\n    Err(e) -> print(\"err\")\n}\n}"
        assertEquals("err", runTree(mapOf(
            "stdlib/list.subl" to std, "stdlib/result.subl" to res, "main.subl" to main)))
    }

    @Test
    fun `P5 readFile 写后读回返回 Ok 分支`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val res = File("stdlib/result.subl").readText()
        val f = File.createTempFile("subl-p5-rf", ".txt")
        val p = f.absolutePath.replace("\\", "\\\\")
        val main = "@unpure fun main() {\nwriteFile(\"$p\", \"ok content\")\nvar r = readFile(\"$p\")\n" +
            "when(r) {\n    Ok(s) -> print(s)\n    Err(e) -> print(\"err \" + e)\n}\n}"
        try {
            assertEquals("ok content", runTree(mapOf(
                "stdlib/list.subl" to std, "stdlib/result.subl" to res, "main.subl" to main)))
        } finally { f.delete() }
    }

    @Test
    fun `P5 unwrapOr 和 mapResult 链式组合`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val res = File("stdlib/result.subl").readText()
        // mapResult 的 U 由 lambda 反推（点号方法 x.length），unwrapOr 消费——嵌套泛型调用即 §7.3 用法
        val main = "@unpure fun main() {\nwriteFile(\"p5-mr.txt\", \"hello!\")\n" +
            "var r = stdlib.mapResult(\\(x) => x.length, readFile(\"p5-mr.txt\"))\n" +
            "print(stdlib.unwrapOr(r, 0))\n}"
        try {
            assertEquals("6", runTree(mapOf(
                "stdlib/list.subl" to std, "stdlib/result.subl" to res, "main.subl" to main)))
        } finally { File("p5-mr.txt").delete() }
    }

    // ============ P6 泛型约束字典透传（v1.0 计划 §8.4 验收；决策 82，Max 倾向） ============

    @Test
    fun `P6 showAll 整数列表输出 12`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        // 约束 T: Show——类型参数约束经字典槽透传（§8.2：f 的 JS 签名带 d_Show_T 隐藏首参）
        val main = "class Show[T] { fun show(): Str }\n" +
            "impl Show for Nat { fun show(): Str = toStr(self) }\n" +
            "fun showAll[T: Show](xs: List[T]): Str = stdlib.fold(\"\", \\(x, acc) => acc + x.show(), xs)\n" +
            "@unpure fun main() {\n" +
            "print(showAll(stdlib.Cons(1, stdlib.Cons(2, stdlib.Nil()))))\n}"
        assertEquals("12", runTree(mapOf("stdlib/list.subl" to std, "main.subl" to main)))
    }

    @Test
    fun `P6 printAll 字符串列表输出 x`() {
        assumeTrue(nodeAvailable())
        val std = File("stdlib/list.subl").readText()
        val main = "class Show[T] { fun show(): Str }\n" +
            "impl Show for Str { fun show(): Str = self }\n" +
            "fun printAll[T: Show](xs: List[T]): Str = stdlib.fold(\"\", \\(x, acc) => acc + x.show(), xs)\n" +
            "@unpure fun main() {\n" +
            "print(printAll(stdlib.Cons(\"x\", stdlib.Nil())))\n}"
        assertEquals("x", runTree(mapOf("stdlib/list.subl" to std, "main.subl" to main)))
    }
}