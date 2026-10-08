package sugared.functor.e2e

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import sugared.functor.checker.Checker
import sugared.functor.codegen.JsCodeGen
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.test.assertEquals

/**
 * 端到端集成测试（B3）：subl 源码 → 语义检查 → 生成 JS → node 实跑 → 断言 stdout。
 * node 不可用时自动跳过（assumeTrue），不作为失败。
 * 第四轮返工：所有执行代码包进 `fun main()`（决策 74），函数体按 `= 表达式` / `{ 语句 }` 互斥写（决策 73）。
 */
class E2ETest {
    private fun nodeAvailable(): Boolean = try {
        ProcessBuilder("node", "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (_: Exception) { false }

    /** 编译并运行一段源码，返回 node 的 stdout（trim 后） */
    private fun run(src: String): String {
        val ast = parseSource(src, "e2e.subl")
        val chk = Checker(ast)
        val bag = chk.run()
        assertEquals(false, bag.hasError, "语义检查应通过，实际:\n${bag.report()}")
        val js = JsCodeGen().generate(ast, chk.dictHits, chk.consHits, chk.dictSubHits, chk.namedArgOrder)
        val f = File.createTempFile("subl-e2e-", ".js")
        f.writeText(js)
        return try {
            val p = ProcessBuilder("node", f.absolutePath).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), "node 退出码非 0，输出:\n$out\n生成 JS:\n$js")
            out.trim()
        } finally { f.delete() }
    }

    @Test
    fun `算术与函数调用`() {
        assumeTrue(nodeAvailable())
        assertEquals("5", run("fun add(a: Nat, b: Nat): Nat = a + b\nfun main() { unchecked print(add(2, 3)) }"))
    }

    @Test
    fun `when 字面量分支`() {
        assumeTrue(nodeAvailable())
        val src = "fun f(n: Nat): Str = when(n) { 0 -> \"z\" else -> \"nz\" }\n" +
            "fun main() { unchecked print(f(0))\nunchecked print(f(9)) }"
        assertEquals("z\nnz", run(src))
    }

    @Test
    fun `Optional 解构与默认值`() {
        assumeTrue(nodeAvailable())
        val src = "fun u(o: Optional<Nat>, d: Nat): Nat = when(o) { Some(x) -> x  None -> d }\n" +
            "fun main() { unchecked print(u(Some(42), 0))\nunchecked print(u(None, 7)) }"
        assertEquals("42\n7", run(src))
    }

    @Test
    fun `嵌套解构`() {
        assumeTrue(nodeAvailable())
        // 决策 64：嵌套 ∃ 展开后三层分支已可判穷尽，无需 else 兜底
        val src = "fun depth(o: Optional<Optional<Nat>>): Nat = " +
            "when(o) { Some(Some(y)) -> y  Some(None) -> 0  None -> 0 }\n" +
            "fun main() { unchecked print(depth(Some(Some(5))))\nunchecked print(depth(Some(None))) }"
        assertEquals("5\n0", run(src))
    }

    @Test
    fun `mut 变量累加`() {
        assumeTrue(nodeAvailable())
        val src = "fun main() { @mut var s = 0\ns = s + 1\ns = s + 2\nunchecked print(s) }"
        assertEquals("3", run(src))
    }

    @Test
    fun `if-else 与负数`() {
        assumeTrue(nodeAvailable())
        val src = "fun sign(n: Int): Str = if n < 0 { \"neg\" } else { \"pos\" }\n" +
            "fun main() { unchecked print(sign(-1))\nunchecked print(sign(1)) }"
        assertEquals("neg\npos", run(src))
    }

    @Test
    fun `struct 构造与字段`() {
        assumeTrue(nodeAvailable())
        val src = "struct P(x: Nat, y: Nat)\nfun sum(p: P): Nat = p.x + p.y\nfun main() { unchecked print(sum(P(3, 4))) }"
        assertEquals("7", run(src))
    }

    @Test
    fun `Bool 枚举 when`() {
        assumeTrue(nodeAvailable())
        val src = "fun b2s(b: Bool): Str = when(b) { true -> \"yes\" false -> \"no\" }\n" +
            "fun main() { unchecked print(b2s(true))\nunchecked print(b2s(false)) }"
        assertEquals("yes\nno", run(src))
    }

    @Test
    fun `结构相等`() {
        assumeTrue(nodeAvailable())
        // 决策 32/53：== 是内建结构相等函数，禁中缀，只能前缀调用；struct 全字段相等即相等
        val src = "struct P(x: Nat, y: Nat)\nfun f(a: P, b: P): Bool = ==(a, b)\n" +
            "fun main() { unchecked print(f(P(1,2), P(1,2)))\nunchecked print(f(P(1,2), P(3,4))) }"
        assertEquals("true\nfalse", run(src))
    }

    @Test
    fun `元组解构`() {
        assumeTrue(nodeAvailable())
        val src = "fun fst(p: (Nat,Nat)): Nat = when(p) { (a, b) -> a else -> 0 }\n" +
            "fun main() { unchecked print(fst((3, 4)))\nunchecked print(fst((9, 1))) }"
        assertEquals("3\n9", run(src))
    }

    @Test
    fun `型类字典`() {
        assumeTrue(nodeAvailable())
        // 决策 60/68（O2）：impl 生成字典，接收者隐式；show(5) 自由式按 5 的类型分发
        val src = "class Show { fun show(): Str }\n" +
            "impl Show for Int { fun show(): Str = \"num\" }\n" +
            "fun main() { unchecked print(show(5)) }"
        assertEquals("num", run(src))
    }

    @Test
    fun `型类字典接收者字段`() {
        assumeTrue(nodeAvailable())
        // 回归护栏：字典隐藏首参曾与实参错位；O2 后接收者隐式，方法体裸用字段名取 self 的值。
        val src = "struct A(name: Str)\n" +
            "class B { fun nameOf(): Str }\n" +
            "impl B for A { fun nameOf(): Str = name }\n" +
            "fun main() { unchecked print(A(\"test\").nameOf()) }"
        assertEquals("test", run(src))
    }

    @Test
    fun `字符串与 lambda`() {
        assumeTrue(nodeAvailable())
        val src = "fun main() { var id = \\(x) => x\nunchecked print(id(\"hi\")) }"
        assertEquals("hi", run(src))
    }

    @Test
    fun `提前return - 语句位if分支端到端`() {
        assumeTrue(nodeAvailable())
        // 决策 75：语句位 if 分支里的 return 展平成真正的 JS，直接退出函数
        val src = "fun classify(n: Nat): Str {\n  if n > 100 { return \"big\" }\n" +
            "  if n > 10 { return \"mid\" }\n  return \"small\"\n}\n" +
            "fun main() { unchecked print(classify(200))\nunchecked print(classify(50))\nunchecked print(classify(3)) }"
        assertEquals("big\nmid\nsmall", run(src))
    }

    @Test
    fun `提前return - 尾if全路径分支端到端`() {
        assumeTrue(nodeAvailable())
        val src = "fun parity(n: Nat): Str { if n > 0 { return \"pos\" } else { return \"nonpos\" } }\n" +
            "fun main() { unchecked print(parity(1))\nunchecked print(parity(0 - 1)) }"
        assertEquals("pos\nnonpos", run(src))
    }

    @Test
    fun `when 守卫引用绑定变量端到端`() {
        assumeTrue(nodeAvailable())
        // 守卫修复回归：`m if !=(m,0)` 必须先绑定 m 再判守卫
        val src = "fun range(n: Nat): Str = when(n) {\n  0 -> \"zero\"\n  m if !=(m, 0) & !=(m, 1) -> \"many\"\n  else -> \"small\"\n}\n" +
            "fun main() { unchecked print(range(0))\nunchecked print(range(7))\nunchecked print(range(1)) }"
        assertEquals("zero\nmany\nsmall", run(src))
    }

    @Test
    fun `O4 main入口自动调用`() {
        assumeTrue(nodeAvailable())
        // 决策 70：具名零参 fun main() 自动作为入口调用（Kotlin 风格），无需顶层语句
        val src = "@unpure fun main() {\n  unchecked print(\"from main\")\n}"
        assertEquals("from main", run(src))
    }

    @Test
    fun `O2-O4 用户目标代码端到端`() {
        assumeTrue(nodeAvailable())
        // 用户提供的目标形态：隐式 self + 点号调用 + 字段默认值 + 命名字段构造 + main 入口
        val src = "struct A(name: Str = \"unknown\")\n" +
            "class B { @unpure fun printMyself() }\n" +
            "impl B for A { @unpure fun printMyself() {\n  print(name)\n} }\n" +
            "@unpure fun main() {\n  var o = A(name = \"test\")\n  o.printMyself()\n}"
        assertEquals("test", run(src))
    }

    @Test
    fun `巡礼示例端到端`() {
        assumeTrue(nodeAvailable())
        // showcase.subl 覆盖决策 68/69/70/71/75 全链路；锁首尾行与行数防退化
        val src = java.io.File("examples/showcase.subl").readText()
        val out = run(src).lines()
        assertEquals("alice", out.first(), "巡礼示例首行应为 alice")
        assertEquals("done", out.last(), "巡礼示例末行应为 done")
        assertEquals(15, out.size, "巡礼示例应输出 15 行，实际 ${out.size} 行：\n$out")
    }

    // ============ P1 字符串内建端到端（决策 77） ============

    @Test
    fun `P1 concat 与 toStr`() {
        assumeTrue(nodeAvailable())
        assertEquals("a1", run("fun main() { unchecked print(concat(\"a\", toStr(1))) }"))
    }

    @Test
    fun `P1 parseNat 解构分支`() {
        assumeTrue(nodeAvailable())
        // when 分支体是表达式位，print 需 @unpure main（决策 74/77）；主语直接是调用
        val src = "@unpure fun main() { when(parseNat(\"12\")) { Some(n) -> print(n)  None -> print(\"bad\") } }"
        assertEquals("12", run(src))
    }

    @Test
    fun `P1 parseNat 非法输入落 None`() {
        assumeTrue(nodeAvailable())
        val src = "@unpure fun main() { when(parseNat(\"x\")) { Some(n) -> print(n)  None -> print(\"bad\") } }"
        assertEquals("bad", run(src))
    }

    @Test
    fun `P1 length charAt substring strCmp`() {
        assumeTrue(nodeAvailable())
        val src = "fun main() {\n" +
            "unchecked print(length(\"abc\"))\n" +
            "unchecked print(charAt(\"abc\", 1))\n" +
            "unchecked print(substring(\"hello\", 1, 3))\n" +
            "unchecked print(strCmp(\"a\", \"b\"))\n" +
            "unchecked print(strCmp(\"b\", \"a\"))\n" +
            "unchecked print(strCmp(\"a\", \"a\"))\n" +
            "}"
        assertEquals("3\nb\nel\n-1\n1\n0", run(src))
    }

    // ============ P2 数组端到端（步骤 A） ============

    @Test
    fun `P2 arrayLength 与 arrayGet 解构`() {
        assumeTrue(nodeAvailable())
        val src = "@unpure fun main() {\n" +
            "print(arrayLength(arrayOf(1, 2, 3)))\n" +
            "when(arrayGet(arrayOf(7, 8), 0)) { Some(x) -> print(x)  None -> print(\"none\") }\n" +
            "when(arrayGet(arrayOf(7, 8), 5)) { Some(x) -> print(x)  None -> print(\"none\") }\n" +
            "}"
        assertEquals("3\n7\nnone", run(src))
    }

    @Test
    fun `P2 arraySet 复制设值`() {
        assumeTrue(nodeAvailable())
        val src = "@unpure fun main() {\n" +
            "var a = arraySet(arrayOf(1, 2), 0, 9)\n" +
            "print(arrayLength(a))\n" +
            "when(arrayGet(a, 0)) { Some(x) -> print(x)  None -> print(\"none\") }\n" +
            "}"
        assertEquals("2\n9", run(src))
    }

    // ============ P8 语法糖批（v1.0 计划 §10.3 验收；决策 84） ============

    @Test
    fun `P8 字符串插值简单与表达式`() {
        assumeTrue(nodeAvailable())
        // §10.3：`"hello, $name"` → hello, alice；`"${1 + 2} is three"` → 3 is three
        val src = "@unpure fun main() {\n" +
            "var name = \"alice\"\n" +
            "print(\"hello, \$name\")\n" +
            "print(\"\${1 + 2} is three\")\n" +
            "}"
        assertEquals("hello, alice\n3 is three", run(src))
    }

    @Test
    fun `P8 命名参数乱序仍正确`() {
        assumeTrue(nodeAvailable())
        // §10.3：`greet(age = 30, name = "alice")` 顺序打乱仍正确（desugar→按形参声明顺序重排）
        val src = "fun greet(name: Str, age: Nat): Str = \"hi \$name\"\n" +
            "@unpure fun main() {\n" +
            "print(greet(age = 30, name = \"alice\"))\n" +
            "print(greet(\"bob\", 20))\n" +
            "}"
        assertEquals("hi alice\nhi bob", run(src))
    }

    // ============ P10 浮点数 Rat（v1.0 计划 §12.4 验收；决策 86 候选 A：IEEE double） ============

    @Test
    fun `P10 浮点除法与平方根近似`() {
        assumeTrue(nodeAvailable())
        // §12.4 验收①②：`print(1.0 / 3.0)` 近似、`print(sqrt(2.0))` 平方根
        val src = "@unpure fun main() {\n" +
            "print(1.0 / 3.0)\n" +
            "print(sqrt(2.0))\n" +
            "print(floor(2.7))\n" +
            "print(pow(2.0, 10.0))\n" +
            "print(toRat(3))\n" +
            "}"
        assertEquals("0.3333333333333333\n1.4142135623730951\n2\n1024\n3", run(src))
    }

    @Test
    fun `P10 parseRat 与向量混合`() {
        assumeTrue(nodeAvailable())
        val src = "@unpure fun main() {\n" +
            "when(parseRat(\"2.5\")) { Some(x) -> print(x)  None -> print(\"none\") }\n" +
            "when(parseRat(\"abc\")) { Some(x) -> print(x)  None -> print(\"none\") }\n" +
            "}"
        assertEquals("2.5\nnone", run(src))
    }

    // ============ P11 验收压测护栏（决策 87：约束泛型自递归字典槽透传） ============

    @Test
    fun `P11 约束泛型自递归透传字典槽`() {
        assumeTrue(nodeAvailable())
        // P11 压测（examples/v1-showcase.subl）暴露：约束泛型函数自递归调用点 tsub 解不出
        // 具体类型 → 字典实参漏插 → 参数错位。回退透传当前函数约束槽（d_Show_T）。
        val src = "class Show[T] { fun show(): Str }\n" +
            "impl Show for Nat { fun show(): Str = toStr(self) }\n" +
            "enum L[T] { LNil(), LCons(T, L<T>) }\n" +
            "fun joinAll[T: Show](xs: L<T>): Str = when(xs) {\n" +
            "    LNil() -> \"\"\n" +
            "    LCons(h, t) -> concat(h.show(), joinAll(t))\n" +
            "    _ -> \"\"\n" +
            "}\n" +
            "@unpure fun main() {\n" +
            "print(joinAll(LCons(1, LCons(2, LNil()))))\n" +
            "}"
        assertEquals("12", run(src))
    }
}
