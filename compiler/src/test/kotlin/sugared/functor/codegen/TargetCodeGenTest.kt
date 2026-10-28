package sugared.functor.codegen

import sugared.functor.checker.Checker
import sugared.functor.parser.parseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * t35（SfW Phase 1）：--target 平台分派的代码生成基线测试。
 *
 * 三目标基线：
 *  - node：自执行脚本、require('fs') 直连（既有行为不变，向后兼容）
 *  - browser：S-1 装载模型——导出 globalThis.__subl.main、不自执行、平台调用走宿主桥
 *    globalThis.__subl.call(op, args)；println 直连 console.log（宿主有 console）
 *  - wasm：同 S-1 装载模型；同步 IO 等平台调用编译期拒绝（E-PLATFORM-UNAVAILABLE）
 */
class TargetCodeGenTest {
    private val ioSrc = "@impure fun main() { var r = readFile(\"x.txt\")\nprint(r) }"

    private fun gen(src: String, target: Target): String {
        val ast = parseSource(src, "t35.subl")
        val chk = Checker(ast)
        val bag = chk.run()
        assertFalse(bag.hasError, "语义检查应通过，实际: " + bag.report())
        return JsCodeGen(target).generate(ast, chk.dictHits, chk.moduleHits, chk.consHits,
            chk.dictSubHits, chk.namedArgOrder, chk.methodSugarArgs, chk.asyncAwaitHits,
            chk.channelUsed, chk.mutexUsed, chk.atomicUsed, chk.flowUsed, chk.rangeHits,
            chk.rangeUsed, chk.receiveSmartHits, chk.flowEmitHits, chk.hktKindPosHits,
            chk.funRefTargets, chk.funRefFree.keys, chk.lambdaDestructKeys, chk.delegatedImpls,
            chk.deepCopyHits)
    }

    // ---------- Target 解析（CLI 契约） ----------

    @Test fun `target 解析 默认 node 与三取值`() {
        assertEquals(Target.NODE, Target.parse(null))
        assertEquals(Target.NODE, Target.parse("node"))
        assertEquals(Target.BROWSER, Target.parse("Browser"))
        assertEquals(Target.WASM, Target.parse("WASM"))
    }

    @Test fun `target 解析 非法取值抛异常`() {
        assertFailsWith<IllegalArgumentException> { Target.parse("deno") }
    }

    // ---------- 模块可用性表 ----------

    @Test fun `core io 在 wasm 不可用 其余 target 可用`() {
        // modulePath 为相对根的 "/" 连接路径（MultiModule 的模块键形式，根模块为 ""）
        assertTrue(Platform.moduleAvailable("core/io", Target.NODE))
        assertTrue(Platform.moduleAvailable("core/io", Target.BROWSER))
        assertFalse(Platform.moduleAvailable("core/io", Target.WASM))
        assertFalse(Platform.moduleAvailable("core/io/nested", Target.WASM))
    }

    @Test fun `未列模块全 target 可用 net 仅 node`() {
        for (t in Target.entries) assertTrue(Platform.moduleAvailable("core/string", t))
        assertTrue(Platform.moduleAvailable("", Target.WASM))
        assertTrue(Platform.moduleAvailable("app/sub", Target.WASM))
        assertTrue(Platform.moduleAvailable("core/net", Target.NODE))
        assertFalse(Platform.moduleAvailable("core/net", Target.BROWSER))
        assertFalse(Platform.moduleAvailable("core/android", Target.WASM))
    }

    // ---------- node 基线（既有行为不变） ----------

    @Test fun `node 基线 自执行 fs 直连无导出对象`() {
        val js = gen(ioSrc, Target.NODE)
        assertTrue(js.contains("require('fs').readFileSync"), "node 应直连 fs: $js")
        assertTrue(js.contains("main();"), "node 应自执行 main()")
        assertFalse(js.contains("__subl"), "node 不应有 S-1 导出对象")
    }

    // ---------- browser 基线（S-1 装载 + 宿主桥） ----------

    @Test fun `browser 基线 导出 main 不自执行 IO 走宿主桥`() {
        val js = gen(ioSrc, Target.BROWSER)
        assertTrue(js.contains("__subl.call(\"readFile\""), "browser readFile 应走宿主桥: $js")
        assertTrue(js.contains("(globalThis.__subl"), "browser 应导出 globalThis.__subl: $js")
        assertFalse(js.contains("require('fs')"), "browser 不应直连 node fs")
        assertFalse(js.contains("main();"), "browser 不应自执行 main")
    }

    @Test fun `browser println 直连 console getArgs 走宿主桥`() {
        val js = gen("@impure fun main() { print(\"hi\")\nprint(getArgs()) }", Target.BROWSER)
        assertTrue(js.contains("console.log("), "browser println 应直连 console.log: $js")
        assertTrue(js.contains("__subl.call(\"getArgs\""), "browser getArgs 应走宿主桥: $js")
    }

    // ---------- wasm 基线（S-1 装载 + 同步 IO 编译期拒绝） ----------

    @Test fun `wasm 无 IO 程序 导出 main 不自执行`() {
        // print/println 在 wasm 下一律经桥（宿主未必提供 console）；单文件 builtin 回退签名不含 println，故用 print
        val js = gen("@impure fun main() { print(\"hi\") }", Target.WASM)
        assertTrue(js.contains("__subl.call(\"println\""), "wasm println 应走宿主桥（无 console 保证）: $js")
        assertTrue(js.contains("(globalThis.__subl"), "wasm 应导出 globalThis.__subl: $js")
        assertFalse(js.contains("main();"), "wasm 不应自执行 main")
    }

    @Test fun `wasm 同步 IO 编译期拒绝 E-PLATFORM-UNAVAILABLE`() {
        val err = assertFailsWith<CodegenError> { gen(ioSrc, Target.WASM) }
        assertTrue(err.message?.contains("E-PLATFORM-UNAVAILABLE") == true, "应含诊断码: ${err.message}")
    }

    @Test fun `wasm sleep 与 getArgs 走宿主桥`() {
        val js = gen("@impure fun main() { sleep(10)\nprint(getArgs()) }", Target.WASM)
        assertTrue(js.contains("__subl.call(\"sleep\""), "wasm sleep 应走宿主桥: $js")
        assertTrue(js.contains("__subl.call(\"getArgs\""), "wasm getArgs 应走宿主桥: $js")
    }

    // ---------- t43（SfW/SfG t40 出入1）：@host 无体声明走宿主桥 ----------

    @Test
    fun `t43 host 无体声明 browser 走宿主桥无裸调`() {
        val src = "@host @impure fun demoBridge(): Str\n@impure fun main() { print(demoBridge()) }"
        val js = gen(src, Target.BROWSER)
        assertTrue(js.contains("__subl.call(\"demoBridge\", [])"), "browser @host 调用应走宿主桥: $js")
        assertFalse(js.contains("demoBridge()"), "browser @host 不应残留裸名调用: $js")
    }

    @Test
    fun `t43 host 无体声明 wasm 同走宿主桥`() {
        val src = "@host @impure fun demoBridge(): Str\n@impure fun main() { print(demoBridge()) }"
        val js = gen(src, Target.WASM)
        assertTrue(js.contains("__subl.call(\"demoBridge\", [])"), "wasm @host 调用应走宿主桥（非 WASM_REJECT 表）: $js")
        assertFalse(js.contains("demoBridge()"), "wasm @host 不应残留裸名调用: $js")
    }

    @Test
    fun `t43 host 无体声明 node 保持裸调`() {
        val src = "@host @impure fun demoBridge(): Str\n@impure fun main() { print(demoBridge()) }"
        val js = gen(src, Target.NODE)
        assertTrue(js.contains("demoBridge()"), "node @host 保持裸名调用（现状，宿主/运行时自接）: $js")
        assertFalse(js.contains("__subl.call("), "node 产物不应出现宿主桥: $js")
    }

    @Test
    fun `t43 host 省略的默认形参按声明补齐`() {
        val src = "@host @impure fun greet(name: Str, prefix: Str = \"hi\"): Str\n@impure fun main() { print(greet(\"x\")) }"
        val js = gen(src, Target.BROWSER)
        assertTrue(js.contains("__subl.call(\"greet\", [\"x\", \"hi\"])"), "省略的尾部默认形参应按声明补齐进桥参数: $js")
    }

    // ---------- 用户函数优先（桥不劫持同名用户定义） ----------

    @Test fun `用户定义同名函数不走平台分派`() {
        // 用户自己实现的 sleep（非 builtin 占位）——wasm 下不应被宿主桥劫持、也不直连 fs
        val src = "@impure fun sleep(ms: Nat): Null { print(\"slept\") }\n@impure fun main() { sleep(10) }"
        val jsWasm = gen(src, Target.WASM)
        assertFalse(jsWasm.contains("__subl.call(\"readFile\""), "用户 readFile 不应被桥劫持")
        assertFalse(jsWasm.contains("require('fs')"), "用户 readFile 不应直连 fs")
    }

    // ---------- t63（SfW GameDemo 回报）：单行无大括号 if 的赋值体 ----------

    @Test fun `t63 块体 if 赋值生成真赋值语句`() {
        // 修复前 `if (true) x = 2` 生成 (() => true)()：单行体的 `=` 当命题中缀擦成 true，
        // 赋值副作用静默丢失。修复后语义层门住 E-ASSIGN-AS-VALUE，块体路径保持真赋值。
        val src = "@impure fun main() { @mut var x = 1\nif (true) { x = 2 }\nprint(x) }"
        val js = gen(src, Target.NODE)
        assertTrue(js.contains("x = 2;"), "块体 if 赋值应生成真赋值语句: $js")
        assertFalse(js.replace(" ", "").contains("(()=>true)()"),
            "不应出现 (() => true)() 式擦除: $js")
    }

    @Test fun `t63 else 块体赋值也生成真赋值语句`() {
        val src = "@impure fun main() { @mut var x = 1\nif (false) { print(0) } else { x = 3 }\nprint(x) }"
        val js = gen(src, Target.NODE)
        assertTrue(js.contains("x = 3;"), "else 块体赋值应生成真赋值语句: $js")
    }
}
