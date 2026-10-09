package sugared.functor.checker

import sugared.functor.ast.FunType
import sugared.functor.ast.TupleType
import sugared.functor.ast.namedT
import sugared.functor.parser.parseSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckerTest {
    private fun errs(src: String): List<String> =
        checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.ERROR }.map { it.code }

    private fun ok(src: String): Boolean = errs(src).isEmpty()

    private fun warns(src: String): List<String> =
        checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.WARN }.map { it.code }

    private fun hints(src: String): List<String> =
        checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.HINT }.map { it.code }

    @Test
    fun `弥散通过 - 最初例子做对了的样子`() {
        assertTrue(ok("fun test1(): Null <p> { unchecked.axiom[p] }\nfun test2()<p>: Null {}\nfun main() { test1()\ntest2() }"))
    }

    @Test
    fun `未证明下文报 E-POST-UNPROVEN`() {
        assertTrue(errs("fun bad(): Null <p> {}").contains("E-POST-UNPROVEN"))
    }

    @Test
    fun `未证明前置报 E-PRE-UNPROVEN`() {
        val src = "fun use()<p>: Null {}\nfun main() { use() }"
        assertTrue(errs(src).contains("E-PRE-UNPROVEN"))
    }

    @Test
    fun `美元等式自反`() {
        assertTrue(ok("fun get7(): Int <\$ = 7> { return 7 }\nfun main() { get7() }"))
    }

    @Test
    fun `全称合一特化供给`() {
        val src = "fun need7()<valid<7>>: Null {}\nfun main() { unchecked.axiom[forall n. valid<n>]\nneed7() }"
        assertTrue(ok(src))
    }

    @Test
    fun `modus ponens 自动证明`() {
        val src = "fun useQ()<q>: Null {}\nfun main() { unchecked.axiom[p]\nunchecked.axiom[p -> q]\nuseQ() }"
        assertTrue(ok(src))
    }

    @Test
    fun `命题参数自动供给`() {
        val src = "fun witness[@q]<q>: Null {}\nfun main() { unchecked.axiom[torch]\nwitness() }"
        assertTrue(ok(src))
    }

    @Test
    fun `显式供给但作用域无该命题`() {
        val src = "fun witness[@q]<q>: Null {}\nfun main() { witness<torch>() }"
        assertTrue(errs(src).contains("E-PROP-UNBOUND"))
    }

    @Test
    fun `可变性 - 无 mut 赋值报错`() {
        assertTrue(errs("fun main() { var x = 1\nx = 2 }").contains("E-IMMUT-ASSIGN"))
    }

    @Test
    fun `可变性 - mut 赋值合法`() {
        assertTrue(ok("fun main() { @mut var x = 1\nx = 2 }"))
    }

    @Test
    fun `纯度 - 非 unchecked 调 print 报错`() {
        assertTrue(errs("fun main() { print(\"hi\") }").contains("E-IMPURE-CALL"))
    }

    @Test
    fun `纯度 - unchecked 调 print 合法`() {
        assertTrue(ok("fun main() { unchecked print(\"hi\") }"))
    }

    @Test
    fun `纯度 - unpure 函数体内调 print 合法`() {
        assertTrue(ok("fun log(m: Str): Null { unchecked print(m) }\n@unpure fun loud(): Null { log(\"x\") }"))
    }

    @Test
    fun `impure 标注后纯函数调用需逃逸`() {
        val src = "@unpure fun loud(): Null {}\nfun main() { loud() }"
        assertTrue(errs(src).contains("E-IMPURE-CALL"))
    }

    @Test
    fun `结构体字段与构造`() {
        assertTrue(ok("struct Point(x: Nat, y: Nat)\nfun main() { var p = Point(1, 2)\nvar a = p.x }"))
    }

    @Test
    fun `结构体未知字段报错`() {
        assertTrue(errs("struct Point(x: Nat, y: Nat)\nfun main() { var p = Point(1, 2)\nvar a = p.z }").contains("E-UNBOUND-NAME"))
    }

    @Test
    fun `impl 缺方法报 E-IMPL-INCOMPLETE`() {
        assertTrue(errs("class Show[T] { fun show(v: T): Str }\nimpl Show for Str { }").contains("E-IMPL-INCOMPLETE"))
    }

    @Test
    fun `impl 完整实现通过`() {
        val src = "class Show[T] { fun show(v: T): Str }\nstruct Point(x: Nat, y: Nat)\nimpl Show for Point { fun show(v: Point): Str = \"pt\" }"
        assertTrue(ok(src))
    }

    @Test
    fun `impl 额外方法缺 new 报错`() {
        val src = "class Show[T] { fun show(v: T): Str }\nstruct Point(x: Nat, y: Nat)\nimpl Show for Point { fun show(v: Point): Str = \"pt\"\nfun extra(v: Point): Str = \"e\" }"
        assertTrue(errs(src).contains("E-IMPL-EXTRA"))
    }

    @Test
    fun `var 注入等式事实可作供给`() {
        val src = "fun need0()<n = 0>: Null {}\nfun main() { var n = 0\nneed0() }"
        assertTrue(ok(src))
    }

    @Test
    fun `类型测注入可作供给`() {
        val src = "fun needNat()<n : Nat>: Null {}\nfun main() { var n = 0\nn : Nat\nneedNat() }"
        assertTrue(ok(src))
    }

    @Test
    fun `未知名字报错`() {
        assertTrue(errs("fun main() { var x = y }").contains("E-UNBOUND-NAME"))
    }

    @Test
    fun `A5 Nothing后语句不可达警告`() {
        val src = "fun die(): Nothing { return die() }\nfun main() { die()\nunchecked print(1) }"
        assertTrue(warns(src).contains("W-UNREACHABLE"))
    }

    @Test
    fun `命题算符中缀与归约 Unicode版`() {
        val src = "fun needQ()<q>: Null {}\nfun main() { unchecked.axiom[p]\nunchecked.axiom[p → q]\nneedQ() }"
        assertTrue(ok(src))
    }

    @Test
    fun `Bool when 分支穷尽`() {
        val src = "fun f(b: Bool): Null { when(b) { true -> null false -> null } }"
        assertTrue(ok(src))
    }

    @Test
    fun `A3 嵌套var声明即表达式`() {
        assertTrue(ok("fun main() { var n = var m = 0 }"))
    }

    @Test
    fun `A3 匿名函数表达式`() {
        assertTrue(ok("fun main() { var id = fun _[T](a: T): T = a }"))
    }

    @Test
    fun `A3 匿名函数下文证明义务`() {
        assertTrue(errs("fun main() { var f = fun _(): Int <p> {} }").contains("E-POST-UNPROVEN"))
    }

    @Test
    fun `A3 下划线名不可引用`() {
        assertTrue(ok("fun _(): Null {}"))
        assertTrue(errs("fun _(): Null {}\nfun main() { _() }").contains("E-UNBOUND-NAME"))
    }

    @Test
    fun `A2 Optional带字段枚举穷尽`() {
        val src = "fun unwrap(o: Optional[Nat]): Nat = when(o) { None -> 0 Some(x) -> x }"
        assertTrue(warns(src).none { it == "W-NON-EXHAUSTIVE" }, "Optional 两分支应穷尽")
        assertTrue(ok(src))
    }

    @Test
    fun `A2 Optional缺分支非穷尽`() {
        val src = "fun f(o: Optional[Nat]): Null { when(o) { Some(x) -> 1 } }"
        assertTrue(warns(src).contains("W-NON-EXHAUSTIVE"), "缺 None 分支应判非穷尽")
    }

    @Test
    fun `A2 带字面量构造子模式保守非穷尽`() {
        val src = "fun f(o: Optional[Nat]): Null { when(o) { None -> 0 Some(0) -> 1 } }"
        assertTrue(warns(src).contains("W-NON-EXHAUSTIVE"), "Some(0) 不覆盖所有 Some，应判非穷尽")
    }

    @Test
    fun `A5 字面量除零警告`() {
        assertTrue(warns("fun main() { var x = 1 / 0 }").contains("W-DIV-ZERO"))
    }

    @Test
    fun `A2 非密封when流向非Null返回报专属错误`() {
        val src = "fun f(b: Bool): Nat = when(b) { true -> 1 }"
        val codes = errs(src)
        assertTrue(codes.contains("E-NON-SEALED-MATCH"), "应报非密封专属错误，实际: $codes")
        assertFalse(codes.contains("E-TYPE-MISMATCH"))
    }

    @Test
    fun `A2 非密封when返回Null只警告不报错`() {
        val src = "fun f(b: Bool): Null = when(b) { true -> 1 }"
        assertTrue(errs(src).isEmpty())
        assertTrue(warns(src).contains("W-NON-EXHAUSTIVE"))
    }

    @Test
    fun `决策54 pure命题驱动纯度`() {
        assertTrue(errs("@unpure fun side(): Null {}\nfun main() { side() }").contains("E-IMPURE-CALL"))
        assertTrue(ok("@unpure fun side(): Null {}\nfun main() { unchecked.axiom[pure<side>]\nside() }"))
    }

    @Test
    fun `决策52 Any是合法类型名`() {
        assertTrue(ok("fun f(x: Any): Any = x"))
    }

    @Test
    fun `决策30 类型别名与目标类型互相匹配`() {
        val src = "type MyNat = Nat\nfun twice(x: MyNat): Nat = x + x\nfun main() { twice(3) }"
        assertTrue(ok(src))
    }

    @Test
    fun `决策30 参数化别名`() {
        val src = "type NatOpt = Optional[Nat]\nfun f(o: NatOpt): Nat = when(o) { Some(x) -> x None -> 0 }\nfun main() { f(Some(1)) }"
        assertTrue(ok(src))
    }

    @Test
    fun `决策30 别名与既有类型重名报错`() {
        assertTrue(errs("type Nat = Int").contains("E-DUP-DECL"))
    }

    @Test
    fun `决策30 别名右值引用未定义类型报错`() {
        assertTrue(errs("type X = Foo").contains("E-UNBOUND-NAME"))
    }

    @Test
    fun `T0 嵌套构造子模式穷尽无假阳性`() {
        val src = "fun depth(o: Optional[Optional[Nat]]): Nat = " +
            "when(o) { Some(Some(y)) -> y  Some(None) -> 0  None -> 0 }"
        val bad = checkFile(parseSource(src, "t")).diags.filter { it.severity == Severity.ERROR || it.code == "W-NON-EXHAUSTIVE" }
        assertTrue(bad.isEmpty(), "三层分支应判穷尽，实际: ${bad.map { it.render() }}")
    }

    @Test
    fun `T0 嵌套模式仍保守 - 缺内层分支`() {
        val src = "fun f(o: Optional[Optional[Nat]]): Null { when(o) { Some(Some(y)) -> 0  None -> 0 } }"
        assertTrue(warns(src).contains("W-NON-EXHAUSTIVE"), "缺 Some(None) 分支应判非穷尽")
    }

    @Test
    fun `T0 嵌套模式等式保留完整结构`() {
        val src = "fun f(o: Optional[Optional[Nat]]): Nat = when(o) { Some(Some(y)) -> y else -> 0 }"
        assertTrue(ok(src))
    }

    @Test
    fun `T1 sealed 类型三形态构造与渲染`() {
        assertEquals("Nat", namedT("Nat").render())
        assertEquals("Optional[Nat]", namedT("Optional", listOf(namedT("Nat"))).render())
        assertEquals("(Nat, Str)=>Bool", FunType(listOf(namedT("Nat"), namedT("Str")), namedT("Bool")).render())
        assertEquals("(Nat, Nat)", TupleType(listOf(namedT("Nat"), namedT("Nat"))).render())
        assertFalse(FunType(listOf(namedT("Nat")), namedT("Nat")).isNominal())
        assertFalse(TupleType(listOf(namedT("Nat"))).isNominal())
        assertTrue(namedT("Nat").isNominal())
    }

    @Test
    fun `T2 函数类型标注与高阶调用`() {
        assertTrue(ok("fun apply(f: (Nat)=>Nat, x: Nat): Nat = f(x)\nfun inc(n: Nat): Nat = n + 1\nfun main() { apply(inc, 1) }"))
    }

    @Test
    fun `T2 lambda 双向推导`() {
        assertTrue(ok("fun apply(f: (Nat)=>Nat, x: Nat): Nat = f(x)\nfun main() { apply({ n -> n + 1 }, 2) }"))
    }

    @Test
    fun `T2 lambda 参数个数不符报错`() {
        val src = "fun apply(f: (Nat,Nat)=>Nat): Nat = f(1,2)\nfun main() { apply({ n -> n }) }"
        assertTrue(errs(src).contains("E-TYPE-MISMATCH"))
    }

    @Test
    fun `T2 零参函数类型`() {
        assertTrue(ok("fun call0(f: ()=>Nat): Nat = f()\nfun main() { call0({ -> 7 }) }"))
    }

    @Test
    fun `T2 type 别名承载函数类型`() {
        assertTrue(ok("type Pred = (Nat)=>Bool\nfun use(p: Pred): Bool = p(1)"))
    }

    @Test
    fun `T3 元组字面量与类型`() {
        assertTrue(ok("fun f(): (Nat,Str) = (1, \"a\")"))
    }

    @Test
    fun `T3 元组解构`() {
        assertTrue(ok("fun fst(p: (Nat,Nat)): Nat = when(p) { (a, b) -> a else -> 0 }"))
    }

    @Test
    fun `决策66 元组主题判不出穷尽时静默退化不警告`() {
        val src = "fun f(p: (Nat,Nat)): Null { when(p) { (a, b) -> null } }"
        assertTrue(ok(src), "元组主题无兜底应无错误")
        assertTrue(warns(src).isEmpty(), "无覆盖律的主题不应发 W-NON-EXHAUSTIVE")
    }

    @Test
    fun `决策66 枚举主题非穷尽仍警告`() {
        val src = "enum Color { Red(), Green(), Blue() }\nfun g(c: Color): Null { when(c) { Red() -> null } }"
        assertTrue(warns(src).contains("W-NON-EXHAUSTIVE"), "有覆盖律却覆盖不全应提示用户")
    }

    @Test
    fun `T3 EmptyTuple 与 SingleTuple 构造`() {
        assertTrue(ok("fun main() { var e = emptyTuple()\nvar s = singleTuple(7) }"))
    }

    @Test
    fun `T3 元组分量数不符报错`() {
        assertTrue(errs("fun f(p: (Nat,Nat)): Nat = when(p) { (a, b, c) -> a }").contains("E-TYPE-MISMATCH"))
    }

    @Test
    fun `解构 var 元组声明绑定分量`() {
        assertTrue(ok("fun main() { var a = (1, 2)\nvar (b, c) = a\nb }"))
    }

    @Test
    fun `解构 var 非元组初值报 E-TUPLE-DESTRUCT`() {
        assertTrue(errs("fun main() { var n = 5\nvar (b, c) = n }").contains("E-TUPLE-DESTRUCT"))
    }

    @Test
    fun `解构 var 分量数不符报 E-TUPLE-ARITY`() {
        assertTrue(errs("fun main() { var a = (1, 2, 3)\nvar (b, c) = a }").contains("E-TUPLE-ARITY"))
    }

    @Test
    fun `T4 含 Any 成员的结构体禁直接构造`() {
        assertTrue(errs("struct P(v: Any)\nfun mk(): P = P(1)").contains("E-ANY-STRUCT"))
        assertTrue(ok("struct P(v: Any)\nfun mk(): P { unchecked var p = P(1)\nreturn p }"))
    }

    @Test
    fun `T4 Any 形参接受任意实参`() {
        assertTrue(ok("fun show(v: Any): Null { unchecked print(v) }\nfun main() { show(1)\nshow(\"s\") }"))
    }

    @Test
    fun `T4 @tuple 形参只收元组字面量`() {
        val head = "fun sum(@tuple t: (Nat, Nat)): Nat = when(t) { (a, b) -> a + b else -> 0 }\n"
        assertTrue(ok(head + "fun main() { sum((1, 2)) }"))
        assertTrue(errs(head + "fun main() { var x = 1\nsum(x) }").contains("E-TUPLE-VARARG"))
    }

    @Test
    fun `T6 参数引用 dollar n 前件`() {
        assertTrue(ok("fun f(a: Nat, b: Str): Null < \$1 = a > {}"))
    }

    @Test
    fun `T6 参数引用越界报错`() {
        assertTrue(errs("fun f(a: Nat): Null < \$3 = a > {}").contains("E-PARAM-REF"))
    }

    @Test
    fun `T6 上文用 dollar 1 等价参数名并给提示`() {
        val src = "fun f(n: Nat)<\$1 = 0>: Nat { return n }"
        val bag = checkFile(parseSource(src, "t"))
        assertTrue(bag.diags.none { it.severity == Severity.ERROR })
        assertTrue(bag.diags.any { it.code == "H-PARAM-REF" })
    }

    @Test
    fun `T6 函数体内禁用 dollar n`() {
        assertTrue(errs("fun f(n: Nat): Nat = \$1").contains("E-PARAM-REF-IN-BODY"))
    }

    @Test
    fun `T5 字典解析与调用`() {
        assertTrue(ok("class Show { fun show(): Str }\nimpl Show for Int { fun show(): Str = \"i\" }\nfun main() { show(3) }"))
    }

    @Test
    fun `T5 无实例报错`() {
        assertTrue(errs("class Show { fun show(): Str }\nfun main() { show(3) }").contains("E-NO-INSTANCE"))
    }

    @Test
    fun `P6 带约束泛型函数通过`() {
        assertTrue(ok("class Show[T] { fun show(): Str }\n" +
            "impl Show for Nat { fun show(): Str = toStr(self) }\n" +
            "fun showIt[T: Show](x: T): Str = x.show()\n" +
            "@unpure fun main() { print(showIt(1)) }"))
    }

    @Test
    fun `P7 id 泛型反推 n 类型 Nat`() {
        // §9.4 验收①：id(3) 的 T 从实参解出 Nat——needNat(id(3)) 通过即证明（id(3) 若解不出就是 synthetic/自由 T，实参核对报错）
        assertTrue(ok("fun id[T](x: T): T = x\n" +
            "fun needNat(x: Nat): Nat = x\n" +
            "fun main() { needNat(id(3)) }"))
    }

    @Test
    fun `O2 隐式self裸用字段`() {
        assertTrue(ok("struct P(x: Nat)\nclass M { fun dbl(): Nat }\nimpl M for P { fun dbl(): Nat = x * 2 }"))
    }

    @Test
    fun `O2 参数遮蔽字段发提示`() {
        val src = "struct P(x: Nat)\nclass M { fun f(x: Nat): Nat }\nimpl M for P { fun f(x: Nat): Nat = x }"
        assertTrue(hints(src).contains("H-FIELD-SHADOW"))
        assertTrue(errs(src).isEmpty())
    }

    @Test
    fun `O2 点号与自由式等价`() {
        val decl = "struct P(x: Nat)\nclass M { fun get(): Nat }\nimpl M for P { fun get(): Nat = x }\n"
        assertTrue(ok(decl + "fun main() { unchecked print(P(1).get()) }"))
        assertTrue(ok(decl + "fun main() { unchecked print(get(P(1))) }"))
    }

    @Test
    fun `O2 方法实参个数含self核对`() {
        val src = "struct P(x: Nat)\nclass M { fun get(): Nat }\nimpl M for P { fun get(): Nat = x }\nfun main() { unchecked print(P(1).get(9)) }"
        assertTrue(errs(src).contains("E-METHOD-ARGS"))
    }

    @Test
    fun `O2 点号字段优先于同名方法`() {
        val src = "struct P(m: (Nat) => Nat)\nfun main() { unchecked print(P({ x -> x }).m(3)) }"
        assertTrue(ok(src))
    }

    @Test
    fun `O3 字段默认值与缺省构造`() {
        assertTrue(ok("struct A(name: Str = \"unknown\")\nfun main() { var a = A() }"))
    }

    @Test
    fun `O3 缺无默认值字段报错`() {
        assertTrue(errs("struct A(name: Str, age: Nat)\nfun main() { var a = A(\"x\") }").contains("E-FIELD-MISSING"))
    }

    @Test
    fun `O3 命名字段构造`() {
        assertTrue(ok("struct A(name: Str = \"u\", age: Nat = 0)\nfun main() { var a = A(age = 3) }"))
    }

    @Test
    fun `O3 未知字段报错`() {
        assertTrue(errs("struct A(name: Str)\nfun main() { var a = A(nickname = \"x\") }").contains("E-UNBOUND-NAME"))
    }

    @Test
    fun `O3 重复字段报错`() {
        assertTrue(errs("struct A(name: Str)\nfun main() { var a = A(name = \"x\", name = \"y\") }").contains("E-DUP-FIELD"))
    }

    @Test
    fun `O3 函数形参默认值禁令`() {
        assertTrue(errs("fun f(x: Nat = 1): Nat = x").contains("E-DEFAULT-PARAM"))
    }

    @Test
    fun `O3 默认值类型不符报错`() {
        assertTrue(errs("struct A(name: Str = 1)").contains("E-TYPE-MISMATCH"))
    }

    @Test
    fun `O4 入口main不能有参数`() {
        assertTrue(errs("fun main(a: Nat) {}").contains("E-MAIN-PARAMS"))
    }

    @Test
    fun `O4 重复main报错`() {
        assertTrue(errs("fun main() {}\nfun main() {}").contains("E-DUP-DECL"))
    }

    // ---------- 第四轮返工：Kotlin 风格铁律 ----------

    @Test
    fun `体形态互斥 - 等号接花括号报错`() {
        val ex = runCatching { parseSource("fun f(): Nat = { 1 }", "t") }.exceptionOrNull()
        assertTrue(ex is sugared.functor.parser.ParseFailure && ex.message!!.contains("不能 `= {"), "实际: ${ex?.message}")
    }

    @Test
    fun `单表达式体 - 等号不带花括号合法`() {
        assertTrue(ok("fun f(): Nat = 1 + 2"))
    }

    @Test
    fun `块体 - 花括号不带等号合法`() {
        assertTrue(ok("fun f(): Nat { return 1 + 2 }"))
    }

    @Test
    fun `顶层禁语句 - 裸调用报解析错`() {
        val ex = runCatching { parseSource("foo()", "t") }.exceptionOrNull()
        assertTrue(ex is sugared.functor.parser.ParseFailure && ex.message!!.contains("顶层只能写声明"), "实际: ${ex?.message}")
    }

    @Test
    fun `提前return - 语句位if分支合法`() {
        assertTrue(ok("fun f(n: Nat): Nat { if (n > 0) { return 1 }\nreturn 0 }"))
    }

    @Test
    fun `提前return - 尾if全路径return合法`() {
        assertTrue(ok("fun f(n: Nat): Nat { if (n > 0) { return 1 } else { return 2 } }"))
    }

    @Test
    fun `值位if的return被拒`() {
        assertTrue(errs("fun f(x: Nat): Nat { var t = if (x > 0) { return 9 } else { x }\nreturn t }").contains("E-RETURN-OUTSIDE"))
    }

    @Test
    fun `return 值类型不符报错`() {
        assertTrue(errs("fun f(): Nat { return \"s\" }").contains("E-RETURN-TYPE"))
    }

    @Test
    fun `整个示例语义通过`() {
        val src = java.io.File("examples/diffuse.subl").readText()
        val bag = checkFile(parseSource(src, "diffuse.subl"))
        val errors = bag.diags.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "示例应通过语义检查，实际: ${errors.map { it.render() }}")
    }

    @Test
    fun `巡礼示例语义通过`() {
        val src = java.io.File("examples/showcase.subl").readText()
        val bag = checkFile(parseSource(src, "showcase.subl"))
        val errors = bag.diags.filter { it.severity == Severity.ERROR }
        assertTrue(errors.isEmpty(), "巡礼示例应通过语义检查，实际: ${errors.map { it.render() }}")
    }

    // ============ P1 字符串内建（决策 77） ============

    @Test
    fun `P1 concat 类型 Str`() {
        val src = "fun f(): Str = concat(\"a\", \"b\")\nfun main() { unchecked f() }"
        assertTrue(ok(src), "concat 应返回 Str")
    }

    @Test
    fun `P1 length 类型 Nat`() {
        val src = "fun f(): Nat = length(\"abc\")\nfun main() { unchecked f() }"
        assertTrue(ok(src), "length 应返回 Nat")
    }

    @Test
    fun `P1 charAt 类型 Str`() {
        val src = "fun f(): Str = charAt(\"hi\", 0)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "charAt 应返回 Str")
    }

    @Test
    fun `P1 substring 类型 Str`() {
        val src = "fun f(): Str = substring(\"hello\", 1, 3)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "substring 应返回 Str")
    }

    @Test
    fun `P1 strCmp 类型 Int`() {
        val src = "fun f(): Int = strCmp(\"a\", \"b\")\nfun main() { unchecked f() }"
        assertTrue(ok(src), "strCmp 应返回 Int")
    }

    @Test
    fun `P1 toStr 类型 Str`() {
        val src = "fun f(): Str = toStr(42)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "toStr 应返回 Str")
    }

    @Test
    fun `P1 parseNat 类型 Optional Nat`() {
        val src = "fun f(): Optional[Nat] = parseNat(\"12\")\nfun main() { unchecked f() }"
        assertTrue(ok(src), "parseNat 应返回 Optional[Nat]")
    }

    @Test
    fun `P1 parseNat 入参必须 Str`() {
        val src = "fun f(): Optional[Nat] = parseNat(1)\nfun main() { unchecked f() }"
        assertTrue(errs(src).contains("E-TYPE-MISMATCH"), "parseNat 收 Nat 应报类型不匹配")
    }

    @Test
    fun `P1 concat 实参个数校验`() {
        val src = "fun f(): Str = concat(\"a\")\nfun main() { unchecked f() }"
        assertTrue(errs(src).contains("E-TYPE-MISMATCH"), "concat 缺第二个实参应报类型不匹配")
    }

    // ============ P2 数组原语（步骤 A） ============

    @Test
    fun `P2 arrayOf 类型 Array`() {
        val src = "fun f(): Array[Nat] = arrayOf(1, 2, 3)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "arrayOf 应返回 Array[Nat]")
    }

    @Test
    fun `P2 arrayLength 类型 Nat`() {
        val src = "fun f(): Nat = arrayLength(arrayOf(1, 2, 3))\nfun main() { unchecked f() }"
        assertTrue(ok(src), "arrayLength 应返回 Nat")
    }

    @Test
    fun `P2 arrayGet 类型 Optional`() {
        val src = "fun f(): Optional[Nat] = arrayGet(arrayOf(7, 8), 0)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "arrayGet 应返回 Optional[T]")
    }

    @Test
    fun `P2 arraySet 类型 Array`() {
        val src = "fun f(): Array[Nat] = arraySet(arrayOf(1, 2), 0, 9)\nfun main() { unchecked f() }"
        assertTrue(ok(src), "arraySet 应返回 Array[T]")
    }

    @Test
    fun `P2 arrayGet 入参类型错`() {
        val src = "fun f(): Optional[Nat] = arrayGet(arrayOf(7, 8), \"x\")\nfun main() { unchecked f() }"
        assertTrue(errs(src).contains("E-TYPE-MISMATCH"), "arrayGet 第二参收 Str 应报类型不匹配")
    }

    // ============ P4 基础自动推理（决策 80，v1.0 计划 §6.6 验收） ============

    @Test
    fun `P4 等式对称自动推理`() {
        // `<5 = n>` 与作用域里的 `x = 5` 方向相反，需对称：5 = x ⊢ x = 5
        assertTrue(ok("fun need5(n: Nat)<5 = n>: Null {}\nfun f(): Null { var x = 5\nneed5(x) }"),
            "等式对称应自动成立")
    }

    @Test
    fun `P4 等式传递自动推理`() {
        // a = 5、b = a 两条事实，证 b = 5 需传递
        assertTrue(ok("fun need5(n: Nat)<n = 5>: Null {}\nfun f(): Null { var a = 5\nvar b = a\nneed5(b) }"),
            "等式传递应自动成立")
    }

    @Test
    fun `P4 等量代换自动推理`() {
        // var x = f(5)（纯函数调用注入 x = f(5)）+ 后件 f(5) = 5，证 x = 5 需代换/传递
        val src = "fun f(n: Nat): Nat <\$ = n> = n\n" +
            "fun need5(n: Nat)<n = 5>: Null {}\n" +
            "fun main() { var x = f(5)\nneed5(x) }"
        assertTrue(ok(src), "纯函数调用的等量代换应自动成立")
    }

    @Test
    fun `P4 类型事实的等式推理`() {
        // var m = n（未标类型）需经 n : Nat 与 m = n 推出 m : Nat
        val src = "fun needNat(m: Nat)<m : Nat>: Null {}\n" +
            "fun f(): Null { var n = 5\nvar m = n\nneedNat(m) }"
        assertTrue(ok(src), "类型事实应随等式传播")
    }

    @Test
    fun `P4 链式调用自动推理`() {
        // y = g(x)、x = f(7)、后件 g(x) = x / f(7) = 7 → 证 y = 7（多步传递 + 代换）
        val src = "fun f(n: Nat): Nat <\$ = n> = n\n" +
            "fun g(n: Nat): Nat <\$ = n> = n\n" +
            "fun need7(n: Nat)<n = 7>: Null {}\n" +
            "fun main() { var x = f(7)\nvar y = g(x)\nneed7(y) }"
        assertTrue(ok(src), "链式调用的多步等式推理应自动成立")
    }

    // ============ P9 递归停机判定（v1.0 计划 §11.4 验收；决策 44/85，用户授权 Max 倾向：报错 + 含 when 解构） ============

    @Test
    fun `P9 非结构递归报 E-NON-STRUCTURAL-REC`() {
        // 验收①：fact(n - 1) 的实参不是形参的语法子项（BinExpr），须报错
        val src = "fun fact(n: Nat): Nat = when(n) { 0 -> 1\n_ -> n * fact(n - 1) }"
        assertTrue(errs(src).contains("E-NON-STRUCTURAL-REC"), "数值递减（n-1）不是结构子项，应报错")
    }

    @Test
    fun `P9 when 解构的结构递归通过`() {
        // 验收②：len(t) 的 t 由 when(xs){Cons(_, t)} 解构而来，是 xs 的语法子项 → 放行
        val src = "enum List[T] { Nil(), Cons(T, List[T]) }\n" +
            "fun len[T](xs: List[T]): Nat = when(xs) {\n" +
            "    Nil -> 0\n" +
            "    Cons(_, t) -> 1 + len(t)\n" +
            "}"
        assertTrue(ok(src), "解构变量的结构递归应通过")
    }

    @Test
    fun `P9 纯函数无子项递归报错且直接传形参也报错`() {
        // 补充：f(x) 直接传形参自身（无递减）也应拒绝；多参数递归放行需任一实参为子项
        val self = "fun f(n: Nat): Nat = f(n)"
        assertTrue(errs(self).contains("E-NON-STRUCTURAL-REC"))
        val map = "enum List[T] { Nil(), Cons(T, List[T]) }\n" +
            "fun map[T, U](f: (T) => U, xs: List[T]): List[U] = when(xs) {\n" +
            "    Nil -> Nil()\n" +
            "    Cons(h, t) -> Cons(f(h), map(f, t))\n" +
            "}"
        assertTrue(ok(map), "多参数递归至少一个实参为子项（map(f, t) 的 t）应放行")
    }

    @Test
    fun `P9 unchecked 与 unpure 放行非结构递归`() {
        // 验收③：unchecked/unpure 逃逸通道不受停机检查约束
        val src = "fun dieA(): Nat { unchecked var r = dieA()\nreturn r }\n" +
            "@unpure fun dieB(): Nat = dieB()"
        assertTrue(ok(src), "unchecked 与 @unpure 应放行非结构递归")
    }
}
