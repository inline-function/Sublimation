package sugared.functor.checker

import sugared.functor.codegen.JsCodeGen
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * P0 模块系统测试（v1.0 补全计划 §2.5 验收逐条对应）：
 * M1 目录即模块 / M2 同模块跨文件 / M3 父子兄弟可见性 / M4 后代看不到根模块 /
 * M5 挂载（别名/多父/传递/环/冲突/settings 语法）/ M6 impl 也挂载 / M8 mangle 前缀。
 * 临时目录构建 fixture，用完即删；全程不依赖命令行（CLI 分支在汇报中手工验证）。
 */
class ModuleTreeTest {
    private fun tmpTree(spec: Map<String, String>, block: (File, MultiModule) -> Unit) {
        val dir = Files.createTempDirectory("subl-mod").toFile()
        try {
            for ((rel, content) in spec) {
                val f = File(dir, rel)
                f.parentFile?.mkdirs()
                f.writeText(content)
            }
            block(dir, MultiModule(dir))
        } finally { dir.deleteRecursively() }
    }

    private fun errs(bag: DiagBag): List<String> =
        bag.diags.filter { it.severity == Severity.ERROR }.map { it.code }

    private fun checked(spec: Map<String, String>): DiagBag {
        var bagOut: DiagBag? = null
        tmpTree(spec) { _, mm -> bagOut = mm.checkAll() }
        return bagOut!!
    }

    // ============ M1：目录即模块 ============

    @Test
    fun `M1 扫描 - 目录即模块树状结构`() {
        tmpTree(
            mapOf(
                "main.subl" to "fun rootFn(): Nat = 1",
                "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a",
                "project/data/stringData.subl" to "fun shout(s: Str): Str = s",
                "project/processor/format.subl" to "fun fmt(): Nat = 0",
            )
        ) { dir, _ ->
            val tree = scanModuleTree(dir)
            assertEquals("", tree.absPath, "根模块 absPath 应为空串")
            assertEquals(1, tree.files.size, "根目录的 .subl 文件属于根模块")
            val core = tree.children["core"]!!
            assertEquals("core", core.absPath)
            assertNotNull(core.files.firstOrNull { it.name == "math.subl" })
            val pd = tree.children["project"]!!.children["data"]!!
            assertEquals("project/data", pd.absPath)
            assertEquals("data", pd.dir.name)
            assertNotNull(pd.files.firstOrNull { it.name == "stringData.subl" })
        }
    }

    // ============ M2：同模块内文件互相可见 ============

    @Test
    fun `M2 同模块跨文件裸名可见`() {
        // 同模块（data/）内两个文件互相裸名引用（共享符号表）；根 main 限定访问
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(data.wrapper()) }",
            "data/stringData.subl" to "fun helper(): Nat = 1",
            "data/wrapperData.subl" to "fun wrapper(): Nat = helper()",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "同模块跨文件裸名应可用：\n${bag.report()}")
    }

    @Test
    fun `M2 同模块跨文件重复声明 E-DUP-DECL`() {
        val spec = mapOf(
            "a/x.subl" to "fun f(): Nat = 1",
            "a/y.subl" to "fun f(): Nat = 2",
            "main.subl" to "fun main() { f() }",
        )
        assertTrue(errs(checked(spec)).contains("E-DUP-DECL"))
    }

    // ============ M3：父看子（点号限定）、子看不到父、兄弟默认不可见 ============

    @Test
    fun `M3 父看子 - 点号限定`() {
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(data.shout(\"hi\")) }",
            "data/stringData.subl" to "fun shout(s: Str): Str = s",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "project/main 调 project/data 函数应通过（限定）：\n${bag.report()}")
    }

    @Test
    fun `M3 子看不到父 - E-UNBOUND-NAME`() {
        val spec = mapOf(
            "main.subl" to "fun topSecret(): Nat = 1\nfun main() { topSecret() }",
            "data/leaf.subl" to "fun useParent(): Nat = topSecret()",
            "data/second.subl" to "fun useParent2(): Nat = useParent()",
        )
        val bag = checked(spec)
        assertTrue(errs(bag).contains("E-UNBOUND-NAME"), "子模块引用父模块函数应报未定义：\n${bag.report()}")
    }

    @Test
    fun `M3 兄弟默认不可见 - E-UNBOUND-NAME`() {
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(siblingFn()) }",
            "a/f.subl" to "fun siblingFn(): Nat = 1",
            "b/g.subl" to "fun siblingFn(): Nat = 2",
        )
        val bag = checked(spec)
        assertTrue(errs(bag).contains("E-UNBOUND-NAME"), "兄弟模块 a 与 b 默认不互相可见：\n${bag.report()}")
    }

    // ============ M4：后代看不到根模块（根 = 入口容器） ============

    @Test
    fun `M4 后代看不到根模块 - E-UNBOUND-NAME`() {
        val spec = mapOf(
            "util.subl" to "fun rootUtil(): Nat = 99",
            "core/math.subl" to "fun useRoot(): Nat = rootUtil()",
            "obj.subl" to "fun main() { unchecked print(useRoot()) }",
        )
        val bag = checked(spec)
        assertTrue(errs(bag).contains("E-UNBOUND-NAME"), "子模块引用根模块函数应报未定义（根只是入口容器）：\n${bag.report()}")
    }

    @Test
    fun `M4 根看子可用 - 根模块 main 调子模块函数`() {
        val spec = mapOf(
            "main.subl" to "fun main() { unchecked print(core.gcd(3, 4)) }",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "根模块引用子模块应通过（父看子+限定）：\n${bag.report()}")
    }

    // ============ M5：挂载 ============

    @Test
    fun `M5 挂载后可见`() {
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "project/main.subl" to "fun main() { unchecked print(core.gcd(3, 4)) }",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "project 挂载 core 后应可见：\n${bag.report()}")
    }

    @Test
    fun `M5 挂载别名 as 生效`() {
        val spec = mapOf(
            "project/project.settings" to "mount core as c",
            "project/main.subl" to "fun main() { unchecked print(c.gcd(3, 4)) }",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "别名 c 应可用：\n${bag.report()}")
    }

    @Test
    fun `M5 挂载传递 - project 挂 core core 挂 foo`() {
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "core/core.settings" to "mount foo",
            "project/main.subl" to "fun main() { unchecked print(foo.bar()) }",
            "core/mid.subl" to "fun bridge(): Nat = foo.bar()",
            "foo/f.subl" to "fun bar(): Nat = 7",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "挂载应传递（project 经 core 可见 foo）：\n${bag.report()}")
    }

    @Test
    fun `M5 引用路径写法一致 - 深层与浅层引 core 写法一致`() {
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "project/main.subl" to "fun main() { unchecked print(core.gcd(1, 1)) }",
            "project/data/leaf.subl" to "fun viaData(): Nat = core.gcd(2, 2)",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR },
            "project 与 project/data 引用 core 写法应一致（core.gcd）：\n${bag.report()}")
    }

    @Test
    fun `M5 多父挂载 - 同一份符号`() {
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "other/other.settings" to "mount core",
            "project/main.subl" to "fun main() { unchecked print(core.gcd(1, 2)) }",
            "other/o.subl" to "fun useCore(): Nat = core.gcd(3, 4)",
            "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "core 可被多父挂载：\n${bag.report()}")
    }

    @Test
    fun `M5 挂载链成环 E-CIRCULAR-MOUNT`() {
        val spec = mapOf(
            "project/project.settings" to "mount core",
            "core/core.settings" to "mount project",
            "main.subl" to "fun main() {}",
        )
        assertTrue(errs(checked(spec)).contains("E-CIRCULAR-MOUNT"), "project↔core 应报环")
    }

    @Test
    fun `M5 本地目录名与挂载名冲突 E-DUP-DECL`() {
        val spec = mapOf(
            "project/project.settings" to "mount core as data",
            "project/main.subl" to "fun main() {}",
            "project/data/leaf.subl" to "fun x(): Nat = 1",
            "core/math.subl" to "fun gcd(): Nat = 1",
        )
        assertTrue(errs(checked(spec)).contains("E-DUP-DECL"), "本地目录 data 与挂载别名 data 冲突应报错")
    }

    @Test
    fun `M5 settings 语法错 E-PARSE-SETTINGS`() {
        val spec = mapOf(
            "project/project.settings" to "mountt core",
            "project/main.subl" to "fun main() {}",
        )
        assertTrue(errs(checked(spec)).contains("E-PARSE-SETTINGS"), "settings 非法指令应报 E-PARSE-SETTINGS")
    }

    @Test
    fun `M5 挂载不存在的模块 E-UNBOUND-NAME`() {
        val spec = mapOf(
            "project/project.settings" to "mount nonexist",
            "project/main.subl" to "fun main() {}",
        )
        assertTrue(errs(checked(spec)).contains("E-UNBOUND-NAME"), "挂载不存在的模块应报错")
    }

    // ============ M6：impl 也要挂载 ============

    @Test
    fun `M6 impl 挂载后可用`() {
        val spec = mapOf(
            "d/d.settings" to "mount core",
            "core/point.subl" to
                "struct Point(x: Nat)\n" +
                "class Show { fun show(): Str }\n" +
                "impl Show for Point { fun show(): Str = \"P\" }",
            "d/main.subl" to
                "fun main() { var p = core.Point(1)\nunchecked print(show(p)) }",
        )
        val bag = checked(spec)
        assertTrue(bag.diags.none { it.severity == Severity.ERROR }, "挂载 core 后 d 应能用 core 的 impl：\n${bag.report()}")
    }

    @Test
    fun `M6 impl 不挂载 E-NO-INSTANCE`() {
        val spec = mapOf(
            "d/main.subl" to
                "class Show { fun show(): Str }\n" +
                "fun main() { var p = core.Point(1)\nunchecked print(show(p)) }",
            "d/d.settings" to "",   // 不挂载 core
            "core/point.subl" to
                "struct Point(x: Nat)\n" +
                "impl Show for Point { fun show(): Str = \"P\" }",
        )
        // 注：impl 在 core，d 也声明了 trait Show（方法名可见）——不挂载 core → 无实例
        val bag = checked(spec)
        assertTrue(errs(bag).contains("E-NO-INSTANCE"), "不挂载 core 不应有实例：\n${bag.report()}")
    }

    // ============ M7/M8：合并编译、mangle 前缀 ============

    @Test
    fun `M8 根模块无前缀 子模块函数带前缀`() {
        tmpTree(
            mapOf(
                "main.subl" to "fun greet(): Str = \"hi\"\nfun main() { unchecked print(greet()) }",
                "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
            )
        ) { _, mm ->
            val bag = mm.checkAll()
            assertFalse(bag.hasError, bag.report())
            val js = mm.generateJs()
            assertTrue(js.contains("function greet("), "根模块函数应无前缀：\n$js")
            assertTrue(js.contains("function core__gcd("), "子模块函数应带模块前缀：\n$js")
            assertTrue(js.contains("main();"), "根模块 main 应无前缀调用：\n$js")
            assertFalse(js.contains("root__"), "不应出现 root__ 前缀")
        }
    }

    @Test
    fun `M8 深层模块前缀 core_math__`() {
        tmpTree(
            mapOf(
                "main.subl" to "fun main() {}",
                "core/math/extra.subl" to "fun deep(): Nat = 1",
            )
        ) { _, mm ->
            val bag = mm.checkAll()
            assertFalse(bag.hasError, bag.report())
            val js = mm.generateJs()
            assertTrue(js.contains("function core_math__deep("), "深层模块应折叠为 core_math：\n$js")
        }
    }

    @Test
    fun `M7 多模块 compile 通过且产物可直接运行片段正确`() {
        tmpTree(
            mapOf(
                "main.subl" to "fun main() { unchecked print(proj.work()) }",
                "proj/proj.settings" to "mount lib",
                "proj/p.subl" to "fun work(): Nat = lib.helper()",
                "lib/l.subl" to "fun helper(): Nat = 42",
            )
        ) { _, mm ->
            val bag = mm.checkAll()
            assertFalse(bag.hasError, bag.report())
            val js = mm.generateJs()
            assertTrue(js.contains("lib__helper"), "跨模块调用应为目标模块前缀：\n$js")
            assertTrue(js.contains("proj__work"), "同模块函数也带本模块前缀：\n$js")
        }
    }

    @Test
    fun `M8 限定调用 codegen 名字与 checker 一致`() {
        tmpTree(
            mapOf(
                "main.subl" to "fun main() { unchecked print(core.gcd(3, 4)) }",
                "core/math.subl" to "fun gcd(a: Nat, b: Nat): Nat = a + b",
            )
        ) { _, mm ->
            mm.checkAll()
            val js = mm.generateJs()
            assertTrue(js.contains("core__gcd(3, 4)"), "调用点应使用 core__gcd：\n$js")
            // 若 codegen 用了裸名/点号访问模块名，这里会缺 core__gcd 调用
        }
    }

    // ============ P2：stdlib 隐式挂载（决策 78） ============

    private val MIN_STDLIB = """
        enum List[T] { Nil(), Cons(T, List[T]) }
        fun listLength[T](xs: List[T]): Nat = when(xs) {
            Nil -> 0
            Cons(_, t) -> 1 + listLength(t)
        }
    """.trimIndent()

    @Test
    fun `P2 stdlib 隐式挂载 - 子模块无需 settings 即可引用`() {
        // proj 没有任何 settings 文件，仍应经隐式挂载看到 stdlib
        val bag = checked(mapOf(
            "stdlib/list.subl" to MIN_STDLIB,
            "proj/use.subl" to "fun u(): Nat = stdlib.listLength(stdlib.Cons(1, stdlib.Nil()))",
        ))
        assertTrue(errs(bag).isEmpty(), "子模块应经隐式挂载看到 stdlib：${bag.report()}")
    }

    @Test
    fun `P2 stdlib 隐式挂载 - 本地同名子目录占用时跳过注入`() {
        // proj 自带 stdlib/ 子目录时，自动挂载被跳过；proj 的 stdlib.xxx 指自身子模块
        val bag = checked(mapOf(
            "stdlib/list.subl" to MIN_STDLIB,
            "proj/stdlib/own.subl" to "fun own(): Nat = 7",
            "proj/use.subl" to "fun u(): Nat = stdlib.own()",
        ))
        assertTrue(errs(bag).isEmpty(), "本地 stdlib 子目录应遮挡自动挂载：${bag.report()}")
    }
}