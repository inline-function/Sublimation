package sugared.functor.checker

import sugared.functor.parser.parseSource
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 诊断码全覆盖体检（第四轮，决策 72；返工后按新语法规则更新，决策 73/74/75）。
 * 每个已知诊断码一个触发场景，断言其确实产出。规则变化点：
 *  - 顶层只能声明，凡需执行/注入的源码都包进 `fun main() { … }`；
 *  - 函数体 `= 表达式` 与 `{ 语句 }` 互斥（决策 73），块体不再写 `= {`；
 *  - 提前 return：语句位 if 分支合法（决策 75），值位 if / lambda 体仍拒。
 */
class DiagCoverageTest {
    private fun diags(src: String) = checkFile(parseSource(src, "t")).diags
    private fun has(src: String, code: String) = diags(src).any { it.code == code }

    // ---------- 解析层（异常通道，非诊断码） ----------

    @Test fun `解析失败以异常上抛而非诊断码`() =
        assertTrue(runCatching { parseSource("struct", "t") }.exceptionOrNull() is sugared.functor.parser.ParseFailure)

    // ---------- 类型层 ----------

    @Test fun `E-TYPE-MISMATCH 函数体`() = assertTrue(has("fun f(): Nat = \"s\"", "E-TYPE-MISMATCH"))

    @Test fun `E-TYPE-MISMATCH 构造实参`() =
        assertTrue(has("struct A(x: Nat)\nfun main() { var a = A(\"s\") }", "E-TYPE-MISMATCH"))

    @Test fun `E-NON-SEALED-MATCH`() =
        assertTrue(has("enum C { Red(), Green() }\nfun f(c: C): Nat = when(c) { Red() -> 1 }", "E-NON-SEALED-MATCH"))

    // ---------- 命题层 ----------

    @Test fun `E-PRE-UNPROVEN`() =
        assertTrue(has("fun need()<p>: Null {}\nfun main() { need() }", "E-PRE-UNPROVEN"))

    @Test fun `E-POST-UNPROVEN`() = assertTrue(has("fun f(): Null <p> {}", "E-POST-UNPROVEN"))

    @Test fun `E-PROP-UNBOUND`() =
        assertTrue(has("fun w[@q]<q>: Null {}\nfun main() { w() }", "E-PROP-UNBOUND"))

    @Test fun `E-PROP-AMBIGUOUS`() =
        assertTrue(has("fun w[@q]<q>: Null {}\nfun main() { unchecked.axiom[alpha]\nunchecked.axiom[beta]\nw() }", "E-PROP-AMBIGUOUS"))

    // prove 对 PImp 目标逐层 d+1 递归；→ 左结合会一次下钻就转查原子目标，
    // 需右嵌套括号把深度堆过 maxDepth 才触发耗尽
    @Test fun `E-DEEP-SATURATION 右嵌套长蕴含链`() =
        assertTrue(has("fun f(): Null <p1 → (p2 → (p3 → (p4 → (p5 → (p6 → (p7 → p8))))))> {}",
            "E-DEEP-SATURATION"))

    // ---------- 实体层 ----------

    @Test fun `E-DUP-DECL`() = assertTrue(has("struct A(x: Nat)\nstruct A(y: Nat)", "E-DUP-DECL"))

    @Test fun `E-UNBOUND-NAME 未定义调用`() = assertTrue(has("fun main() { nosuch() }", "E-UNBOUND-NAME"))

    @Test fun `E-IMPURE-CALL`() =
        assertTrue(has("@unpure fun side(): Null {}\nfun f(): Null { side() }", "E-IMPURE-CALL"))

    @Test fun `E-IMMUT-ASSIGN`() =
        assertTrue(has("fun main() { var a = 1\na = 2 }", "E-IMMUT-ASSIGN"))

    @Test fun `E-ASSIGN-TARGET 字段赋值`() =
        assertTrue(has("struct A(x: Nat)\nfun main() { var p = A(1)\np.x = 2 }", "E-ASSIGN-TARGET"))

    @Test fun `E-IMPL-INCOMPLETE`() =
        assertTrue(has("class C { fun m1(): Null }\nimpl C for Nat { }", "E-IMPL-INCOMPLETE"))

    @Test fun `E-IMPL-EXTRA`() =
        assertTrue(has("class C { fun m1(): Null }\nimpl C for Nat { fun m1(): Null {}\nfun extra(): Null {} }", "E-IMPL-EXTRA"))

    @Test fun `E-IMPL-MISMATCH`() =
        assertTrue(has("class C { fun m1(): Str }\nimpl C for Nat { fun m1(): Nat { return 1 } }", "E-IMPL-MISMATCH"))

    // ---------- 类型类字典（决策 60/68） ----------

    @Test fun `E-NO-INSTANCE`() =
        assertTrue(has("class Show { fun show(): Str }\nfun main() { show(3) }", "E-NO-INSTANCE"))

    @Test fun `E-AMBIGUOUS-INSTANCE Nat与Int双实例`() =
        assertTrue(has(
            "class Show { fun show(): Nat }\n" +
            "impl Show for Nat { fun show(): Nat { return 1 } }\n" +
            "impl Show for Int { fun show(): Nat { return 2 } }\n" +
            "fun main() { show(3) }", "E-AMBIGUOUS-INSTANCE"))

    @Test fun `E-NO-SELF 自由式缺接收者`() =
        assertTrue(has("class M { fun m(): Nat }\nimpl M for Nat { fun m(): Nat { return 1 } }\nfun main() { m() }", "E-NO-SELF"))

    @Test fun `E-METHOD-ARGS`() =
        assertTrue(has(
            "struct A(x: Nat)\nclass M { fun get(): Nat }\nimpl M for A { fun get(): Nat { return x } }\nfun main() { A(1).get(9) }",
            "E-METHOD-ARGS"))

    @Test fun `E-SELF-UNKNOWN 类型类成员带体`() =
        assertTrue(has("class M { fun m(): Nat { return 1 } }", "E-SELF-UNKNOWN"))

    // ---------- Any 准入（决策 59） ----------

    @Test fun `E-ANY-STRUCT`() =
        assertTrue(has("struct P(v: Any)\nfun mk(): P = P(1)", "E-ANY-STRUCT"))

    @Test fun `E-ANY-CALL`() =
        assertTrue(has("fun inner(x: Any): Null {}\nfun outer(a: Any): Null { inner(a) }", "E-ANY-CALL"))

    @Test fun `E-TUPLE-VARARG`() =
        assertTrue(has(
            "fun sum(@tuple t: (Nat, Nat)): Nat = when(t) { (a, b) -> a + b else -> 0 }\nfun main() { var x = 1\nsum(x) }",
            "E-TUPLE-VARARG"))

    // ---------- P9 递归停机（决策 44/85） ----------

    @Test fun `E-NON-STRUCTURAL-REC`() =
        assertTrue(has("fun f(n: Nat): Nat = when(n) { 0 -> 0\n_ -> f(n - 1) }", "E-NON-STRUCTURAL-REC"))

    // ---------- P8 命名参数（决策 84） ----------

    @Test fun `E-NAMED-ARG-UNKNOWN`() =
        assertTrue(has("fun g(a: Nat): Nat = a\nfun main() { g(b = 1) }", "E-NAMED-ARG"))

    @Test fun `E-NAMED-ARG-METHOD`() =
        assertTrue(has("struct P(x: Nat)\nclass M { fun d(x: Nat): Nat }\nimpl M for P { fun d(x: Nat): Nat { return self.x } }\nfun main() { var p = P(1)\np.d(x = 2) }", "E-NAMED-ARG"))

    // ---------- dollar n 参数引用（决策 58） ----------

    @Test fun `E-PARAM-REF 越界`() =
        assertTrue(has("fun f(a: Nat): Null <\$3 = a> {}", "E-PARAM-REF"))

    @Test fun `E-PARAM-REF-IN-BODY`() =
        assertTrue(has("fun f(n: Nat): Nat = \$1", "E-PARAM-REF-IN-BODY"))

    @Test fun `H-PARAM-REF`() =
        assertTrue(has("fun f(n: Nat): Null <\$1 = 0> {}", "H-PARAM-REF"))

    // ---------- 对象与方法（决策 68/69/70） ----------

    @Test fun `H-FIELD-SHADOW`() =
        assertTrue(has(
            "struct P(x: Nat)\nclass M { fun f(x: Nat): Nat }\nimpl M for P { fun f(x: Nat): Nat { return x } }",
            "H-FIELD-SHADOW"))

    @Test fun `E-FIELD-MISSING 位置式缺尾部`() =
        assertTrue(has("struct A(name: Str, age: Nat)\nfun main() { var a = A(\"x\") }", "E-FIELD-MISSING"))

    @Test fun `E-FIELD-MISSING 命名字段缺无默认值字段`() =
        assertTrue(has("struct A(name: Str, age: Nat)\nfun main() { var a = A(age = 1) }", "E-FIELD-MISSING"))

    @Test fun `E-DUP-FIELD`() =
        assertTrue(has("struct A(name: Str)\nfun main() { var a = A(name = \"x\", name = \"y\") }", "E-DUP-FIELD"))

    @Test fun `E-UNBOUND-NAME 未知字段`() =
        assertTrue(has("struct A(name: Str)\nfun main() { var a = A(nickname = \"x\") }", "E-UNBOUND-NAME"))

    @Test fun `E-DEFAULT-PARAM`() =
        assertTrue(has("fun f(x: Nat = 1): Nat = x", "E-DEFAULT-PARAM"))

    @Test fun `E-MAIN-PARAMS`() =
        assertTrue(has("fun main(a: Nat) {}", "E-MAIN-PARAMS"))

    @Test fun `E-MAIN重复由DUP-DECL兜底`() =
        assertTrue(has("fun main() {}\nfun main() {}", "E-DUP-DECL"))

    // ---------- 显式 return（决策 71/75，返工定稿） ----------

    @Test fun `提前return - 语句位if分支合法`() =
        assertTrue(has("fun f(n: Nat): Nat { if (n > 0) { return 1 }\nreturn 0 }", "E-RETURN-OUTSIDE") == false)

    @Test fun `提前return - 尾if全路径合法`() =
        assertTrue(has("fun f(n: Nat): Nat { if (n > 0) { return 1 } else { return 2 } }", "E-RETURN-OUTSIDE") == false)

    @Test fun `E-RETURN-OUTSIDE lambda 体内`() =
        assertTrue(has("fun f(): Nat { var g = fun _(x: Nat): Nat { return x }\nreturn 0 }", "E-RETURN-OUTSIDE"))

    @Test fun `E-RETURN-OUTSIDE 值位if`() =
        assertTrue(has("fun f(x: Nat): Nat { var t = if (x > 0) { return 9 } else { x }\nreturn t }", "E-RETURN-OUTSIDE"))

    @Test fun `E-RETURN-TYPE 值类型不符`() =
        assertTrue(has("fun f(): Nat { return \"s\" }", "E-RETURN-TYPE"))

    @Test fun `E-RETURN-TYPE 裸return要求Null函数`() =
        assertTrue(has("fun f(): Nat { return }", "E-RETURN-TYPE"))

    // ---------- 警告/提示/补充 ----------

    @Test fun `W-NON-EXHAUSTIVE`() =
        assertTrue(has("enum C { Red(), Green(), Blue() }\nfun f(c: C): Null { when(c) { Red() -> null } }", "W-NON-EXHAUSTIVE"))

    @Test fun `W-UNREACHABLE Nothing 之后`() =
        assertTrue(has("fun die(): Nothing { return die() }\nfun f(): Null { die()\nunchecked print(1) }", "W-UNREACHABLE"))

    @Test fun `W-UNREACHABLE return 之后`() =
        assertTrue(has("fun f(): Nat { return 1\nreturn 2 }", "W-UNREACHABLE"))

    @Test fun `W-DIV-ZERO`() =
        assertTrue(has("fun main() { var a = 1 / 0 }", "W-DIV-ZERO"))

    @Test fun `W-UNUSED 未使用局部变量`() =
        assertTrue(has("fun main() { var x = 1 }", "W-UNUSED"))

    @Test fun `W-UNUSED 未使用参数`() =
        assertTrue(has("fun f(x: Nat): Nat = 1", "W-UNUSED"))

    @Test fun `D-TYPE-INFERRED`() = assertTrue(has("fun main() { var a = 1 }", "D-TYPE-INFERRED"))

    @Test fun `D-BY`() = assertTrue(has("fun f(): Null { by [p]\n}", "D-BY"))

    // ---------- NOTES：不单独触发的码 ----------
    // E-ANY-INFER：预留码，v1 未接线（决策 59 的"涉 Any 推导失效"由 E-ANY-CALL/E-ANY-STRUCT 落地）。

    @Test fun `E-ANY-INFER 为预留码当前不触发`() =
        assertTrue(diags("fun inner(x: Any): Null {}").none { it.code == "E-ANY-INFER" })

    // ---------- v2.0 @root 机制（内建声明白名单） ----------

    @Test fun `E-FUN-NO-BODY 无体且未标 @root`() =
        assertTrue(has("fun ghost(): Nat", "E-FUN-NO-BODY"))

    @Test fun `E-ROOT-NOT-ALLOWED @root 但不在白名单`() =
        assertTrue(has("@root fun myMagic(): Nat", "E-ROOT-NOT-ALLOWED"))

    @Test fun `H-ROOT-BUILTIN @root 命中白名单发提示`() =
        assertTrue(has("@root fun jsonParse(s: Str): Optional[Json]", "H-ROOT-BUILTIN"))
}
