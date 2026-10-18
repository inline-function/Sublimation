package sugared.functor.interpreter

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import sugared.functor.ast.DeclEntry
import sugared.functor.ast.FunDecl
import sugared.functor.ast.NameRef
import sugared.functor.ast.NamedType
import sugared.functor.ast.PropParam
import sugared.functor.parser.parseScriptSource

/**
 * 全局类型推导（idea doc/全局类型推导.md，V2）：Interpreter(globalInference = true) 的脚本模式行为。
 *
 * 覆盖 V1：参数/字段类型（构造点 → 使用点 → 默认）、效应（非纯冒泡、停机失败降级 @impure）、
 * 可变性（@mut 链）与「默认关闭」行为。
 * 覆盖 V2：泛型化（[T: Num]/[T: Any]、结构体泛型化）、成员使用点（x.length()）、
 * SCC 假设-验证的递归返回类型（自递归/互递归）、冲突诊断 E-TYPE-CONFLICT、
 * D-AUTO-IMPURE 补充级提示、上下文自动填充（前件反推/后件交集、class 回填）。
 */
class GlobalInferenceTest {

    private fun captureScript(globalInference: Boolean, script: String): List<Any?> {
        val interp = Interpreter(globalInference = globalInference)
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run(script, "<test>")
        return out
    }

    /** 默认关闭：未标注参数仍是解析错（旧行为保持不变）。 */
    @Test
    fun 默认关闭_未标注参数仍报解析错() {
        assertFailsWith<RuntimeException> {
            captureScript(globalInference = false, "fun myf(n) { return n + 1 }\ncapture(myf(1))\n")
        }
    }

    /** 构造点：参数类型取自调用点实参字面量。 */
    @Test
    fun 参数类型_调用点构造点() {
        val out = captureScript(true, """
            fun myAdd(a, b) = a + b
            capture(myAdd(1, 2))
            fun greet(who) = concat("hi ", who)
            capture(greet("bob"))
        """.trimIndent())
        assertEquals(listOf<Any?>(3L, "hi bob"), out)
    }

    /** 使用点：无调用点但算术使用 → Nat（doc §三 使用点偏好）。 */
    @Test
    fun 参数类型_算术使用点() {
        val out = captureScript(true, """
            fun doubleIt(n) = n * 2
            capture(doubleIt(21))
        """.trimIndent())
        assertEquals(listOf<Any?>(42L), out)
    }

    /** 无信息 → Any 兜底（doc §五：默认 Any 保证过编译）。 */
    @Test
    fun 参数类型_无信息默认Any() {
        val out = captureScript(true, """
            fun passthrough(x) = x
            capture(passthrough(7))
        """.trimIndent())
        assertEquals(listOf<Any?>(7L), out)
    }

    /** struct 字段：构造点（位置实参 + 默认值）。 */
    @Test
    fun struct字段_构造点与默认值() {
        val out = captureScript(true, """
            struct Acc(owner, balance, country = "cn")
            var a = Acc("bob", 100)
            capture(a.owner)
            capture(a.balance)
            capture(a.country)
        """.trimIndent())
        assertEquals(listOf<Any?>("bob", 100L, "cn"), out)
    }

    /** 效应：调用 print → 自动 @impure（不再报 E-IMPURE-CALL）；顶层调用放行。 */
    @Test
    fun 效应_非纯调用自动impure() {
        val old = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            captureScript(true, """
                fun shout(msg) { print(msg) }
                shout("ok")
            """.trimIndent())
        } finally {
            System.setOut(old)
        }
        assertTrue(buf.toString("UTF-8").contains("ok"), "print 应被执行：${buf.toString("UTF-8")}")
    }

    /** 效应冒泡：g 调用 impure 的 h → g 也 impure。 */
    @Test
    fun 效应_向上冒泡() {
        val old = System.out
        val buf = ByteArrayOutputStream()
        System.setOut(PrintStream(buf, true, "UTF-8"))
        try {
            captureScript(true, """
                fun inner(x) { print(x) }
                fun outer(x) { inner(x) }
                outer("bubble")
            """.trimIndent())
        } finally {
            System.setOut(old)
        }
        assertTrue(buf.toString("UTF-8").contains("bubble"))
    }

    /** 停机失败降级（doc §7.5）：自递归无递减实参 → 自动 @impure，不再报 E-NON-STRUCTURAL-REC。 */
    @Test
    fun 停机失败_自动impure不报错() {
        // rec 无限递归不能真跑——只声明不调用，验证**检查通过**（过去会报 E-NON-STRUCTURAL-REC）
        captureScript(true, "fun myrec() = myrec()\n")
    }

    /** 停机成功不变：带递减实参的自递归保持纯；返回类型按 doc §7.6 逃生舱显式标注（V1 时代只能如此，
     *  V2 已由 SCC 假设-验证自动推出——见「SCC_自递归返回类型自动推断」）。 */
    @Test
    fun 停机成功_递减递归保持纯() {
        val out = captureScript(true, """
            fun myfact(n): Nat = if (n <= 1) { 1 } else { n * myfact(n - 1) }
            capture(myfact(5))
        """.trimIndent())
        assertEquals(listOf<Any?>(120L), out)
    }

    /** 可变性：赋值目标链 → @mut 字段 + @mut struct + @mut var。 */
    @Test
    fun 可变性_赋值目标链() {
        val out = captureScript(true, """
            struct Box(v)
            var b = Box(1)
            b.v = b.v + 9
            capture(b.v)
        """.trimIndent())
        assertEquals(listOf<Any?>(10L), out)
    }

    /** 显式标注优先：已标注类型/注解不被推断覆盖。 */
    @Test
    fun 显式标注_推断不覆盖() {
        val out = captureScript(true, """
            struct T(x: Nat, y: Str = "d")
            fun keep(n: Nat): Nat = n
            var t = T(5)
            capture(keep(t.x))
            capture(t.y)
        """.trimIndent())
        assertEquals(listOf<Any?>(5L, "d"), out)
    }

    // ==================== V2：泛型化（doc §六） ====================

    /** 泛型化：参数仅有算术使用点（无构造点）→ [T: Num]，每次调用独立实例化（Nat、Rat 都能传）。 */
    @Test
    fun 泛型化_算术使用点泛型化为TNum() {
        val out = captureScript(true, """
            fun doubleIt(n) = n * 2
            var a = 21
            var b = 1.5
            capture(doubleIt(a) == 42)
            capture(doubleIt(b) > 2)
        """.trimIndent())
        assertEquals(listOf<Any?>(true, true), out)
    }

    /** 无约束参数 → [T: Any]（doc §五：默认即泛型化的最低保证，过编译且不丢类型信息）。 */
    @Test
    fun 泛型化_无约束参数默认TAny() {
        val out = captureScript(true, """
            fun passthrough(x) = x
            capture(passthrough(7) == 7)
            capture(passthrough("s") == "s")
        """.trimIndent())
        assertEquals(listOf<Any?>(true, true), out)
    }

    /** 结构体字段无构造点 → 结构体泛型化 T: Any（doc §4.4：字段随实参带类型）。 */
    @Test
    fun 泛型化_无构造点字段为TAny() {
        val out = captureScript(true, """
            struct Pair2(a, b)
            var s = "x"
            var p = Pair2(1, s)
            capture(toStr(p.a))
            capture(p.b)
        """.trimIndent())
        assertEquals(listOf<Any?>("1", "x"), out)
    }

    // ==================== V2：使用点成员约束（doc §四） ====================

    /** 成员使用点：x.length() → 参数填 Str（T: Str 的 checker 路由不成，具体类型即足够）。 */
    @Test
    fun 使用点_成员约束length填Str() {
        val out = captureScript(true, """
            fun mylen(s) = s.length()
            var t = "abcde"
            capture(mylen(t))
        """.trimIndent())
        assertEquals(listOf<Any?>(5L), out)
    }

    // ==================== V2：SCC 假设-验证的递归返回类型（doc §七） ====================

    /** 自递归未标注返回类型：假设 (α)->β → 验证 → 填 Nat（doc §7.2），不再需要 §7.6 逃生舱。 */
    @Test
    fun SCC_自递归返回类型自动推断() {
        val out = captureScript(true, """
            fun myfact(n) = if (n <= 1) { 1 } else { n * myfact(n - 1) }
            capture(myfact(5))
        """.trimIndent())
        assertEquals(listOf<Any?>(120L), out)
    }

    /** 互递归 SCC：even/odd 3 轮迭代内收敛到 Bool（doc §7.3）。 */
    @Test
    fun SCC_互递归evenodd() {
        val out = captureScript(true, """
            fun myeven(n) = if (n == 0) { true } else { myodd(n - 1) }
            fun myodd(n) = if (n == 0) { false } else { myeven(n - 1) }
            capture(myeven(4))
            capture(myodd(7))
        """.trimIndent())
        assertEquals(listOf<Any?>(true, true), out)
    }

    // ==================== V2：冲突诊断 E-TYPE-CONFLICT（doc §十一） ====================

    /** 多构造点但无使用点：Nat 与 Str 各自实例化 [T: Any]——函数参数每次调用独立（doc §4.2），不报冲突。 */
    @Test
    fun 多构造点无使用_各自实例化TAny() {
        val out = captureScript(true, """
            fun conflict(n) { return n }
            capture(conflict(1) == 1)
            capture(conflict("s") == "s")
        """.trimIndent())
        assertEquals(listOf<Any?>(true, true), out)
    }

    /** 构造点与使用点冲突：f(1) 又 f("s") 而体里 n + 1 要数值 → E-TYPE-CONFLICT（doc §十一）。 */
    @Test
    fun 冲突_构造点与使用点冲突报错() {
        val e = assertFailsWith<RuntimeException> {
            captureScript(true, """
                fun bad2(n) = n + 1
                bad2(1)
                bad2("s")
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("E-TYPE-CONFLICT"), e.message)
    }

    /** struct 字段多个构造点类型不一致 → 冲突（doc §4.4：common type 必须唯一）。 */
    @Test
    fun 冲突_字段构造点冲突报错() {
        val e = assertFailsWith<RuntimeException> {
            captureScript(true, """
                struct Mixed(n)
                var m1 = Mixed(1)
                var m2 = Mixed("s")
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("E-TYPE-CONFLICT"), e.message)
    }

    /** 使用点冲突：n - 1 要数值、n.length() 要 Str → 报错（doc §7.4）。 */
    @Test
    fun 冲突_使用点约束冲突报错() {
        val e = assertFailsWith<RuntimeException> {
            captureScript(true, """
                fun bad(n) = n - n.length()
            """.trimIndent())
        }
        assertTrue(e.message!!.contains("E-TYPE-CONFLICT"), e.message)
    }

    // ==================== V2：D-AUTO-IMPURE（doc §7.5） ====================

    /** 停机失败：自动 @impure 且附 D-AUTO-IMPURE 补充级提示（经 Interpreter.inferNotes 暴露）。 */
    @Test
    fun 停机失败_补充级DAutoImpure提示() {
        val interp = Interpreter(globalInference = true)
        interp.run("fun myrec() = myrec()" + "\u000A" + "fun g() { myrec() }" + "\u000A", "<t>")
        assertTrue(interp.inferNotes.any { it.code == "D-AUTO-IMPURE" }, interp.inferNotes.toString())
    }

    // ==================== V2：上下文自动填充（doc §十） ====================

    /** 前件反推：f 体内调 useQ()<q> → f 获得 prep q 并成为超越函数 @q（doc §10.1，命题参数提升）。 */
    @Test
    fun 上下文_前件反推提升preps() {
        val raw = parseScriptSource("fun witness[@q]<q> { }" + "\u000A" + "fun f() { witness() }" + "\u000A", "<t>", allowUntyped = true)
        val out = GlobalInference.infer(raw, emptyList())
        val f = out.entries.mapNotNull { (it as? DeclEntry)?.decl as? FunDecl }.first { it.name == "f" }
        val preps = f.preps.mapNotNull { (it as? NameRef)?.name }
        val props = f.theory.mapNotNull { (it as? PropParam)?.name }
        assertTrue("q" in preps, "f 应反推到 prep q：" + preps)
        assertTrue("q" in props, "f 应成为超越函数带命题参数 @q：" + props)
    }

    /** 后件反推：所有 return 处都在 ∵ q 之后 → q 入后件（doc §10.2 严格交集）。 */
    @Test
    fun 上下文_后件交集填q() {
        val raw = parseScriptSource(
            "fun posty(c) {" + "\u000A" +
            "  unchecked.axiom[q]" + "\u000A" +
            "  if (c) { return 1 } else { return 2 }" + "\u000A" +
            "}" + "\u000A", "<t>", allowUntyped = true)
        val out = GlobalInference.infer(raw, emptyList())
        val f = out.entries.mapNotNull { (it as? DeclEntry)?.decl as? FunDecl }.first { it.name == "posty" }
        val posts = f.posts.mapNotNull { (it as? NameRef)?.name }
        assertTrue("q" in posts, "posty 应有后件 q：" + posts)
    }

    /** class 成员签名回填：impl 方法的返回类型回填到 class 声明（doc §4.5）。 */
    @Test
    fun class_成员签名从impl回填() {
        val raw = parseScriptSource(
            "struct Acc(owner)" + "\u000A" +
            "class Ops { fun show() }" + "\u000A" +
            "impl Ops for Acc { fun show() = \"owner\" }" + "\u000A", "<t>", allowUntyped = true)
        val out = GlobalInference.infer(raw, emptyList())
        val cls = out.entries.mapNotNull { (it as? DeclEntry)?.decl as? sugared.functor.ast.ClassDecl }
            .first { it.name == "Ops" }
        val ret = cls.members.first { it.name == "show" }.retType as? NamedType
        assertEquals("Str", ret?.name)
    }
}
