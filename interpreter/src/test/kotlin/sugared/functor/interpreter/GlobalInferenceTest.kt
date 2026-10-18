package sugared.functor.interpreter

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 全局类型推导（idea doc/全局类型推导.md，V1）：Interpreter(globalInference = true) 的脚本模式行为。
 *
 * 覆盖 V1：参数/字段类型（构造点 → 使用点 → 默认 Any）、效应（非纯冒泡、停机失败降级）。
 * 可变性（@mut 链）与「默认关闭」行为。
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

    /** 停机成功不变：带递减实参的自递归保持纯；返回类型按 doc §7.6 逃生舱显式标注
     *  （V1 未做 §7.2 的「假设签名→验证」固定点：递归函数的返回类型推断交给用户标注）。 */
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
}
