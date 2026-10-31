package sugared.functor.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

class InterpreterTest {

    private fun capture(body: (Interpreter) -> Unit): List<Any?> {
        val interp = Interpreter()
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        body(interp)
        return out
    }

    @Test
    fun testArithmetic() {
        val out = capture { i -> i.run("""
            var x = 1 + (2 * 3)
            capture(x)
            var y = (1 + 2) * 3
            capture(y)
            var z = 10 / 4
            capture(z)
        """.trimIndent()) }
        assertEquals(listOf<Any?>(7L, 9L, 2.5), out)
    }
    // 审查报告 §2.4：类型级 @mut 视图（var c: @mut Cell）任一侧 → 绑定即深拷贝：c 改 d 不见。
    // 旧 M5 偏差：脚本侧只看 @ref，本例在脚本模式曾是别名共享（与编译模式行为分裂）
    @Test
    fun testMutDeepCopyOnBind() {
        val interp = Interpreter()
        interp.injectStruct("Cell", "n" to ScriptType.Nat, mutableFields = listOf("n"))
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            var c: @mut Cell = Cell(n = 0)
            var d: @mut Cell = c
            c.n = 2
            capture(d.n)
        """.trimIndent())
        assertEquals(listOf<Any?>(0L), out)
    }

    // 双 @ref → 别名共享（§6.6：拷贝规则的唯一例外）：字段级变异透过别名可见
    @Test
    fun testRefAliasOnBind() {
        val interp = Interpreter()
        interp.injectStruct("Cell", "n" to ScriptType.Nat, mutableFields = listOf("n"))
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            @ref var a: @mut Cell = Cell(n = 0)
            @ref var b: @mut Cell = a
            b.n = 1
            capture(a.n)
        """.trimIndent())
        assertEquals(listOf<Any?>(1L), out)
    }

    @Test
    fun testFunctionCallAndRecursion() {
        val out = capture { i -> i.run("""
            fun fact(n: Nat): Nat {
                if (n == 0) { return 1 }
                return n * fact(n - 1)
            }
            capture(fact(5))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(120L), out)
    }

    @Test
    fun testWhenOptionalPattern() {
        val out = capture { i -> i.run("""
            var v: Nat = 5
            when (v) {
                5 => capture("five")
                else => capture("other")
            }
        """.trimIndent()) }
        assertEquals(listOf<Any?>("five"), out)
    }

    @Test
    fun testStruct() {
        val interp = Interpreter()
        interp.injectStruct("Point", "x" to ScriptType.Nat, "y" to ScriptType.Nat,
            mutableFields = listOf("x", "y"))
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            var p: @mut Point = Point(x = 0, y = 0)
            p.x = 3
            p.y = 4
            capture(p.x + p.y)
        """.trimIndent())
        assertEquals(listOf<Any?>(7L), out)
    }

    @Test
    fun testListAndClosure() {
        val out = capture { i -> i.run("""
            fun add(a: Nat, b: Nat): Nat = a + b
            capture(add(1, 2))
            capture(add(10, 20))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(3L, 30L), out)
    }

    @Test
    fun testStdlibTypeErrorRejected() {
        val interp = Interpreter()
        var threw = false
        try {
            // size 期望 List，传入 Nat 应被 Checker 拒绝（证明 stdlib 精确签名生效）
            interp.run("""
                var n = 5
                size(n)
            """.trimIndent())
        } catch (e: InterpreterError) {
            threw = true
        }
        kotlin.test.assertTrue(threw, "size(Nat) 应被 Checker 以类型错误拒绝（精确签名生效）")
    }
    @Test
    fun testPrepChecking() {
        // 前件可证明（n = n 自反），Checker 静态通过；Evaluator 执行
        val out = capture { i -> i.run("""
            fun id(n: Nat)<n = n>: Nat = n
            capture(id(5))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(5L), out)
    }

    @Test
    fun testPrepUnprovenRejected() {
        // 前件 p 未证明 → Checker 报 E-PRE-UNPROVEN，拒绝执行
        val interp = Interpreter()
        var threw = false
        try {
            interp.run("""
                fun use()<p>: Null {}
                use()
            """.trimIndent())
        } catch (e: InterpreterError) {
            threw = true
        }
        kotlin.test.assertTrue(threw, "未证明前件 p 应被 Checker 拒绝")
    }
    @Test
    fun testFunctionReference() {
        // 脚本函数引用：var f = square; f(4) → 16
        val out = capture { i -> i.run("""
            fun square(n: Nat): Nat = n * n
            var f = square
            capture(f(4))
            capture(f(10))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(16L, 100L), out)
    }

    @Test
    fun testHostFunctionReference() {
        // @host 函数引用：var f = toStr; f(42) → "42"
        val interp = Interpreter()
        val results = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> results.add(args.getOrNull(0)); null }
        interp.run("""
            var f = toStr
            capture(f(42))
        """.trimIndent())
        assertEquals(listOf<Any?>("42"), results)
    }

    @Test
    fun testFunctionAsArgument() {
        // 宿主接收脚本函数值并调用（apply 泛型高阶函数）
        val interp = Interpreter()
        val results = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> results.add(args.getOrNull(0)); null }
        interp.run("""
            fun square(n: Nat): Nat = n * n
            capture(apply(square, 5))
            capture(apply(square, 10))
        """.trimIndent())
        assertEquals(listOf<Any?>(25L, 100L), results)
    }

    @Test
    fun testMapFilterFold() {
        // map/filter/fold 泛型高阶函数（配合 range 构造列表）
        val out = capture { i -> i.run("""
            // core 标准库自带 isEven（math），本地不再重声明（同名 E-DUP-DECL）
            fun square(n: Nat): Nat = n * n
            fun add(a: Nat, b: Nat): Nat = a + b
            var xs = range(1, 6)
            capture(xs.map { n => square(n) })
            capture(filter(xs, isEven))
            capture(xs.fold(0) { acc, x => add(acc, x) })
        """.trimIndent()) }
        // map: [1,4,9,16,25], filter: [2,4], fold: 1+2+3+4+5=15
        assertEquals(listOf<Any?>(
            listOf(1L, 4L, 9L, 16L, 25L),
            listOf(2L, 4L),
            15L,
        ), out)
    }

    @Test
    fun testRefAliasAndDeepCopy() {
        // 双 @ref → 别名；否则深拷贝（root doc 副作用与可变性控制）
        val interp = Interpreter()
        interp.injectStruct("Cell", "n" to ScriptType.Nat, mutableFields = listOf("n"))
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            @ref var a: @mut Cell = Cell(n = 0)
            @ref var b: @mut Cell = a
            b.n = 1
            capture(a.n)

            var c: @mut Cell = Cell(n = 0)
            var d: @mut Cell = c
            c.n = 2
            capture(d.n)
        """.trimIndent())
        // 别名：b 改 a 见 → 1；深拷贝：c 改 d 不见 → 0
        assertEquals(listOf<Any?>(1L, 0L), out)
    }

    @Test
    fun testOptionalWhen() {
        // Optional 构造 + when 解包
        val out = capture { i -> i.run("""
            var a = Some(42)
            var b = None
            capture(when (a) { is Some(x) => x; None => 0 })
            capture(when (b) { is Some(x) => x; None => -1 })
        """.trimIndent()) }
        assertEquals(listOf<Any?>(42L, -1L), out)
    }

    @Test
    fun testIfIsDestruct() {
        // ifis 智能解构：`if (m is Some(v)) …` 脱糖为 when——枚举/结构体/嵌套通用，解构变量在分支内可见
        val out = capture { i -> i.run("""
            var m: Optional[Nat] = Some(8)
            if (m is Some(v)) { capture(v) }
            if (m is None()) { capture(100) }
            var n: Optional[Nat] = None
            if (n is Some(v)) { capture(-1) } else { capture(9) }
        """.trimIndent()) }
        assertEquals(listOf<Any?>(8L, 9L), out)
    }

    @Test
    fun testResultWhen() {
        // Result 构造 + when 解包
        val out = capture { i -> i.run("""
            var ok = Ok(10)
            var err = Err("fail")
            capture(when (ok) { is Ok(v) => v; is Err(e) => "bad" })
            capture(when (err) { is Ok(v) => v; is Err(e) => e })
        """.trimIndent()) }
        assertEquals(listOf<Any?>(10L, "fail"), out)
    }

    @Test
    fun testListConsNil() {
        // Cons/Nil 列表构造 + 求值
        val out = capture { i -> i.run("""
            var xs = Cons(1, Cons(2, Cons(3, Nil)))
            capture(size(xs))
            capture(head(xs))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(3L, 1L), out)
    }

    @Test
    fun testListWhenPattern() {
        // when 解包 Cons/Nil
        val out = capture { i -> i.run("""
            fun sumList(xs: List[Nat]): Nat {
                return when (xs) {
                    Nil => 0
                    is Cons(h, t) => h + sumList(t)
                }
            }
            var xs = Cons(1, Cons(2, Cons(3, Nil)))
            capture(sumList(xs))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(6L), out)
    }

    @Test
    fun testResultListComprehensive() {
        // Result + List + when + 递归 + 闭包联合端到端
        val interp = Interpreter()
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            fun safeDiv(a: Nat, b: Nat): Result[Nat, Str] {
                if (b == 0) { return Err("division by zero") }
                return Ok(a / b)
            }
            fun sumList(xs: List[Nat]): Nat {
                return when (xs) {
                    Nil => 0
                    is Cons(h, t) => h + sumList(t)
                }
            }
            fun firstDiv(xs: List[Nat], d: Nat): Result[Nat, Str] {
                return when (xs) {
                    Nil => Err("empty list")
                    is Cons(h, t) => safeDiv(h, d)
                }
            }
            var xs = Cons(6, Cons(4, Cons(2, Nil)))
            capture(sumList(xs))
            capture(when (firstDiv(xs, 2)) { is Ok(v) => v; is Err(e) => e })
            capture(when (firstDiv(xs, 0)) { is Ok(v) => v; is Err(e) => e })
            capture(when (firstDiv(Nil, 1)) { is Ok(v) => v; is Err(e) => e })
        """.trimIndent())
        assertEquals(listOf<Any?>(12L, 3.0, "division by zero", "empty list"), out)
    }

    @Test
    fun testStringParseMathBuiltins() {
        // 字符串 / parse / Math 内建函数的运行时（Checker builtinPure 与 Evaluator 实现对齐）
        // 空安全 `?:` 已移除——parse 的 Optional 改用 when 解构取值/兜底
        val out = capture { i -> i.run("""
            fun natOr(s: Str, fallback: Nat): Nat = when(parseNat(s)) { is Some(n) => n  None => fallback }
            // core 标准库 real 自带 ratOr，本地改名 myRatOr 避开 E-DUP-DECL
            fun myRatOr(s: Str, fallback: Rat): Rat = when(parseRat(s)) { is Some(r) => r  None => fallback }
            capture(natOr("42", 0))
            capture(natOr("abc", -1))
            capture(length("hello"))
            capture(concat("foo", "bar"))
            capture(substring("hello", 1, 3))
            capture(strUpper("ab"))
            capture(myRatOr("2.5", 0.0))
            capture(myRatOr("x", -1.0))
            capture(abs(-3.0))
            capture(floor(2.7))
            capture(sqrt(16.0))
            capture(mathMax(3.0, 7.0))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(42L, -1L, 5L, "foobar", "el", "AB", 2.5, -1.0, 3.0, 2.0, 4.0, 7.0), out)
    }

    @Test
    fun testScriptDeclaredStructEnum() {
        // 脚本自己声明的 struct / enum 构造子也能运行时求值（不是只有 host 注入类型）
        val out = capture { i -> i.run("""
            struct Point(x: Nat, y: Nat)
            var p = Point(1, 2)
            capture(p.x + p.y)

            enum Color { Red(), Green(), Blue() }
            fun show(c: Color): Str = when (c) { Red => "red"; Green => "green"; else => "blue" }
            capture(show(Red()))
            capture(show(Green()))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(3L, "red", "green"), out)
    }

    @Test
    fun testOptionalWhenDestruct() {
        // 空安全糖（?. / ?: / var x: Nat = Some(1) 自动解构）已移除——
        // Optional 仍是普通容器：when 解构 + is Some(v) 绑定取内部值
        val out = capture { i -> i.run("""
            var s: Optional[Str] = Some("ab")
            when (s) { is Some(v) => capture("Some:" + toStr(length(v))); else => capture("BAD") }
        """.trimIndent()) }
        assertEquals(listOf<Any?>("Some:2"), out)
    }

    @Test
    fun testNamedArgs() {
        // H4：命名参数 f(b=2, a=3) 必须按形参名落位，不能静默丢成 Null
        val out = capture { i -> i.run("""
            fun myadd(a: Nat, b: Nat): Nat = a + b
            capture(myadd(b = 2, a = 3))
            capture(myadd(a = 10, b = 20))
        """.trimIndent()) }
        assertEquals(listOf<Any?>(5L, 30L), out)
    }

    @Test
    fun testRepeatedRunNoAccumulation() {
        // H7：同一 Interpreter 连续 run，全局帧/声明不跨 run 累积（第二次声明覆盖第一次）
        val interp = Interpreter()
        val out = mutableListOf<Any?>()
        interp.register("capture", retType = ScriptType.Null) { args -> out.add(args.getOrNull(0)); null }
        interp.run("""
            fun v(): Nat = 1
            capture(v())
        """.trimIndent())
        interp.run("""
            fun v(): Nat = 2
            capture(v())
        """.trimIndent())
        assertEquals(listOf<Any?>(1L, 2L), out)
    }

    @Test
    fun testPlaygroundScriptsOutput() {
        // 跑 show/解释器/ 目录（6 份日常脚本，仓库内），goldens 同源；推导版脚本开推导闸门
        val interp = Interpreter(globalInference = true)
        val buf = ByteArrayOutputStream()
        val ps = PrintStream(buf, true, "UTF-8")
        val orig = System.out
        System.setOut(ps)
        try {
            // 脚本模式：目录下每个 .subs 独立运行（互不共享声明）
            interp.runScriptDir(File("show/解释器"))
        } finally {
            System.setOut(orig)
        }
        val text = buf.toString("UTF-8")
        val expected = listOf(
            "开户=张三(中国) 余额=¥1000",      // struct + 命名参数 + 字段默认值 + impl 方法糖
            "取300后余额=1200",                // Result + when 构造子解构
            "转账(50→200,150)=失败",           // Result.flatMap Err 路径
            "圆(半径1) 面积=3.14",             // enum 构造子解构
            "总面积=19.14",                    // enum 多态 + fold 累加
            "平方>10=[16,25,36,49,64]",        // List map+filter 链
            "并集=[1,2,3,4,5,6]",              // Set union
            "Map小红=88",                      // Map put/get
            "Range map=[1,4,9,16,25]",         // Range 惰性 map
            "邮箱长度=5",                      // when 解构 Optional 取内部值
            "解构=42",                        // when 解构 Some(42) 取内部值
            "方法路径作值=[11,12,13]",         // 方法路径作值 xs.map
            "C(5,2)=10",                       // 递减递归
            "gradeChar(88)=优秀",              // 区间穷尽 when(n in _) + 前置条件证明
            "拆分=[a,b,c]",                    // 字符串 strSplit
            "sqrt(16)=4",                      // 数学 sqrt
        )
        for (e in expected) {
            assertTrue(text.contains(e), "playground 输出缺少: $e")
        }
    }

}