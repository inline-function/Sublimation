package sugared.functor.subs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Subs 解释器自测（t84）：覆盖编译侧行为对齐点。
 * 对照组为 compiler 的 JsCodeGen 产物语义（同 .subl 输入，解释器结果 == 编译执行）。
 * 语言语义要点（与 frontend/Parser + JsCodeGen 对齐，非直觉约定）：
 *   1. 无运算符优先级，一律左结合（1 + 2 * 3 == 9）。
 *   2. 绑定一律 var；@mut var 才可写。
 *   3. 值位 & | 与 JS && || 同构（取值而非强制 Bool）；&& || : = -> 为命题位，运行时 true。
 *   4. 列表是 Cons/Nil；when 分支换行分隔（单行仅限下一 arm 以 is Ctor( 开头）。
 *   5. 字符串无单引号字面量；插值用 ${...}（编译侧脱糖 concat 链）。
 */
class SubsInterpreterTest {

    private val out = ArrayList<String>()
    private fun subs(): SubsInterpreter = SubsInterpreter(out::add)
    private val LIST = "Cons(1, Cons(2, Cons(3, Nil())))"
    private fun num(v: V?): Double = (v as V.Num).v
    private fun str(v: V?): String = (v as V.Str).v
    private fun bool(v: V?): Boolean = (v as V.Bool).v

    @Test fun `数值字面量与算术_左结合对齐`() {
        val s = subs()
        assertEquals(9.0, num(s.eval("1 + 2 * 3")))
        assertEquals(7.0, num(s.eval("1 + (2 * 3)")))
        assertEquals(9.0, num(s.eval("(1 + 2) * 3")))
        assertEquals(2.5, num(s.eval("5 / 2")))
        assertEquals(1.0, num(s.eval("5 % 2")))
        assertEquals(1024.0, num(s.eval("2 ^ 10")))
        assertEquals(-3.0, num(s.eval("1 - 4")))
        assertEquals(6.0, num(s.eval("-2 * -3")))
    }

    @Test fun `浮点展示对齐 JS String`() {
        val s = subs()
        s.eval("print(5); print(2.5); print(0.1 + 0.2)")
        assertEquals(listOf("5", "2.5", "0.30000000000000004"), out.toList())
    }

    @Test fun `concat 变参与隐式 toStr`() {
        val s = subs()
        assertEquals("Hello sub", str(s.eval("concat(\"Hello\", \" \", \"sub\")")))
        assertEquals("n7", str(s.eval("concat(\"n\", 7)")))
        assertEquals("1a", str(s.eval("1 + \"a\"")))
        assertEquals(3.0, num(s.eval("length(\"abc\")")))
    }

    @Test fun `布尔与值位逻辑_JS 取值语义`() {
        val s = subs()
        assertFalse(bool(s.eval("1 == 2")))
        assertTrue(bool(s.eval("1 != 2")))
        assertTrue(bool(s.eval("\"a\" != \"b\"")))
        assertTrue(bool(s.eval("(3 <= 3) & (2 < 3)")))
        assertTrue(bool(s.eval("3 <= 3 & 2 < 3")))
        assertEquals(2.0, num(s.eval("1 & 2")))
        assertEquals(1.0, num(s.eval("1 | 0")))
        assertFalse(bool(s.eval("!true")))
    }

    @Test fun `in 区间`() {
        val s = subs()
        assertTrue(bool(s.eval("3 in 1..6")))
        assertFalse(bool(s.eval("9 in 1..6")))
    }

    @Test fun `var 绑定与字符串插值`() {
        val s = subs()
        val v = s.eval("var n = 7\nprint(\"n = \${n * 2}, ok\")")
        assertEquals(V.Null, v)
        assertEquals("n = 14, ok", out.last())
    }

    @Test fun `块体 fun 与默认参数`() {
        val s = subs()
        s.eval("fun greet(name: Str, prefix: Str = \"Hello\"): Str = concat(prefix, \" \", name)")
        assertEquals("Hello sub", str(s.callFunction("greet", V.Str("sub"))))
        assertEquals("Hi sub", str(s.callFunction("greet", V.Str("sub"), V.Str("Hi"))))
    }

    @Test fun `闭包_块体返回 lambda`() {
        val s = subs()
        val v = s.eval("""
            fun mkAdder(x: Nat) {
                { n => n + x }
            }
            mkAdder(10)(5)
        """.trimIndent())
        assertEquals(15.0, num(v))
    }

    @Test fun `裸名守卫与字面量守卫`() {
        val s = subs()
        assertEquals("even", str(s.eval("when (42) { n if n % 2 == 0 => \"even\" else => \"odd\" }")))
        assertEquals("odd", str(s.eval("when (41) { n if n % 2 == 0 => \"even\" else => \"odd\" }")))
        assertEquals("five", str(s.eval("when (5) { n if n > 10 => \"big\" 5 => \"five\" else => \"other\" }")))
    }

    @Test fun `Cons 解构与预置 size`() {
        val s = subs()
        assertEquals(3.0, num(s.eval("size($LIST)")))
        assertEquals(3.0, num(s.eval("when ($LIST) { Nil => 0\n is Cons(h, t) => h + size(t) }")))
        assertEquals(1.0, num(s.eval("head($LIST)")))
        assertEquals(2.0, num(s.eval("head(tail($LIST))")))
    }

    @Test fun `Optional 与 Result 分支`() {
        val s = subs()
        s.eval("fun div(a: Nat, b: Nat): Result[Nat, Str] = if (b == 0) Err(\"div by zero\") else Ok(a / b)")
        assertEquals(5.0, num(s.eval("when (div(10, 2)) { is Ok(v) => v\n is Err(e) => -1 }")))
        assertEquals(-1.0, num(s.eval("when (div(10, 0)) { is Ok(v) => v\n is Err(e) => -1 }")))
        assertEquals(3.0, num(s.eval("when (Some(3)) { is Some(v) => v\n is None => 0 }")))
    }

    @Test fun `struct 模式按字段位置绑定`() {
        val s = subs()
        val v = s.eval("""
            struct Person(name: Str, age: Nat)
            when (Person("Ada", 36)) {
              is Person(p, a) if a > 18 => concat(p, "-adult")
              is Person(p, a) => concat(p, "-minor")
            }
        """.trimIndent())
        assertEquals("Ada-adult", str(v))
    }

    @Test fun `元组与 ifis 智能解构`() {
        val s = subs()
        val v = s.eval("""
            var (a, b) = (1, 2)
            var o: Optional[Nat] = Some(a + b)
            if (o is Some(v)) v else 0
        """.trimIndent())
        assertEquals(3.0, num(v))
    }

    @Test fun `map fold filter reverse`() {
        val s = subs()
        assertEquals("[2,4,6]", dshStr(s.eval("$LIST.map({ x => x * 2 })") ?: V.Null))
        assertEquals(6.0, num(s.eval("$LIST.fold(0, { acc, x => acc + x })")))
        assertEquals(6.0, num(s.eval("fold($LIST, 0, { acc, x => acc + x })")))
        assertEquals("[2,3]", dshStr(s.eval("filter($LIST, { x => x > 1 })") ?: V.Null))
        assertEquals("[3,2,1]", dshStr(s.eval("reverse($LIST)") ?: V.Null))
        assertEquals(12.0, num(s.eval("fold(append($LIST, reverse($LIST)), 0, { acc, x => acc + x })")))
    }

    @Test fun `宿主函数注入`() {
        val s = subs()
        s.registerHostFunction("hostAdd") { args ->
            val a = (args[0] as V.Num).v; val b = (args[1] as V.Num).v
            V.Num(a + b)
        }
        assertEquals(7.0, num(s.eval("hostAdd(3, 4)")))
    }

    @Test fun `宿主对象注入`() {
        val s = subs()
        s.injectObject("world", object : SubsHostObject {
            override fun getField(field: String): V? = if (field == "score") V.Num(42.0) else null
            override fun setField(field: String, v: V) {}
            override fun callMethod(name: String, args: List<V>): V? =
                if (name == "double") V.Num((args[0] as V.Num).v * 2) else null
        })
        assertEquals(42.0, num(s.eval("world.score")))
        assertEquals(10.0, num(s.eval("world.double(5)")))
    }

    @Test fun `sublEval 桥自回路`() {
        val s = subs()
        s.installSublEvalBridge()
        assertEquals(7.0, num(s.eval("when (sublEval(\"1 + (2 * 3)\")) { is Ok(v) => v\n is Err(e) => -1 }")))
        assertEquals(-1.0, num(s.eval("when (sublEval(\"1 +\")) { is Ok(v) => v\n is Err(e) => -1 }")))
        assertEquals(9.0, num(s.eval("when (sublEval(\"1 + 2 * 3\")) { is Ok(v) => v\n is Err(e) => -1 }")))
    }

    @Test fun `json 解析`() {
        val s = subs()
        val v = s.eval("""
            when (jsonGet(jsonParse("{\"score\": 42}"), "score")) {
              is Some(v) => v
              is None => 0
            }
        """.trimIndent())
        assertEquals(42.0, num(v))
    }

    @Test fun `run 程序模式调 main`() {
        val s = subs()
        s.run("""
            fun main() {
                print("program-ok")
            }
        """.trimIndent())
        assertEquals("program-ok", out.last())
    }
}