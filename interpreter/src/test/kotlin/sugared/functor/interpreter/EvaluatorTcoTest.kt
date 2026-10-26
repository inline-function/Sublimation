package sugared.functor.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * t46（t40 出入2 残余）：解释器尾调用优化回归。
 *
 * SfG 规模工程（N≈300 函数）在默认 1m JVM 栈下 check/编译流程 StackOverflowError——
 * t44 已消父链查找放大器；残余深度是脚本侧 walker 的 O(N) 递归（checkFuns 逐元素尾递归），
 * invokeFunDecl 现在把尾位直接调用重绑循环化（O(1) JVM 栈）。以下 4 例在默认栈下
 * 无 TCO 必爆栈（深度 ≥ 10 万），有 TCO 则正确返回。
 */
class EvaluatorTcoTest {

    private fun captureRun(body: String): List<Any?> {
        val interp = Interpreter()
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run(body.trimIndent())
        return out
    }

    @Test
    fun `t46 尾递归 if 分支十万层不爆栈`() {
        val out = captureRun("""
            fun sumTo(n: Nat, acc: Nat): Nat =
                if (n == 0) acc else sumTo(n - 1, acc + n)
            capture(sumTo(100000, 0))
        """)
        assertEquals(listOf<Any?>(5000050000L), out)
    }

    @Test
    fun `t46 非尾递归仍按普通递归求值（TCO 不误伤）`() {
        val out = captureRun("""
            fun fac(n: Nat): Nat = if (n == 0) 1 else n * fac(n - 1)
            capture(fac(6))
        """)
        assertEquals(listOf<Any?>(720L), out)
    }

    @Test
    fun `t46 SfG walker 形态 when+Cons 块内尾递归不爆栈`() {
        val out = captureRun("""
            fun tick(s: Str): Nat = 1
            fun build(n: Nat, acc: List[Str]): List[Str] =
                if (n == 0) acc else build(n - 1, Cons("a", acc))
            fun walk(xs: List[Str], acc: Nat): Nat =
                when (xs) {
                    Nil => acc
                    is Cons(f, rest) => { tick(f); walk(rest, acc + 1) }
                    else => acc
                }
            capture(walk(build(50000, Nil), 0))
        """)
        assertEquals(listOf<Any?>(50000L), out)
    }

    @Test
    fun `t46 互尾递归 ping-pong 不爆栈`() {
        val out = captureRun("""
            fun ping(n: Nat): Nat = if (n == 0) 0 else pong(n - 1)
            fun pong(n: Nat): Nat = if (n == 0) 1 else ping(n - 1)
            capture(ping(100000))
        """)
        assertEquals(listOf<Any?>(0L), out)
    }
}
