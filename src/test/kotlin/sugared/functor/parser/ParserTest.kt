package sugared.functor.parser

import sugared.functor.ast.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {
    /** 第四轮返工（决策 74：顶层禁语句）：语句级解析测试统一包进函数体再取回其语句列表。 */
    private fun stmtsOf(body: String): List<Stmt> {
        val f = parseSource("fun __t() { $body }", "t")
        return (((f.entries[0] as DeclEntry).decl as FunDecl).body as BlockExpr).stmts
    }

    @Test
    fun `上下文弥散声明`() {
        val f = parseSource(
            "fun test1(): None <p> { unchecked.axiom[p] }\nfun test2()<p>: None {}",
            "t",
        )
        val d1 = (f.entries[0] as DeclEntry).decl as FunDecl
        assertEquals("test1", d1.name)
        assertEquals(listOf(NameRef("p")), d1.posts)
        assertTrue((d1.body as BlockExpr).stmts[0] is AxiomStmt)
        val d2 = (f.entries[1] as DeclEntry).decl as FunDecl
        assertEquals(listOf(NameRef("p")), d2.preps)
        assertEquals("None", d2.retType?.name)
    }

    @Test
    fun `上文中的带空格比较`() {
        val f = parseSource("fun cmp(a: Int, b: Int)<a < b>: Bool = true()", "t")
        val d = (f.entries[0] as DeclEntry).decl as FunDecl
        val pre = d.preps[0] as BinExpr
        assertEquals("<", pre.op)
    }

    @Test
    fun `嵌套尖括号显式供给`() {
        val call = (stmtsOf("witness<valid<7>>()")[0] as ExprStmt).expr as CallExpr
        val inst = call.callee as InstExpr
        assertEquals("witness", (inst.target as NameRef).name)
        val inner = inst.terms[0] as InstExpr
        assertEquals("valid", (inner.target as NameRef).name)
    }

    @Test
    fun `返回值符号与等式`() {
        val f = parseSource("fun get7(): Int <\$ = 7> { return 7 }", "t")
        val d = (f.entries[0] as DeclEntry).decl as FunDecl
        val post = d.posts[0] as BinExpr
        assertEquals("=", post.op)
        assertSame(ReturnSym, post.left)
    }

    @Test
    fun `全称量词与紧贴实例化`() {
        val ax = stmtsOf("unchecked.axiom[forall n. valid<n>]")[0] as AxiomStmt
        val q = ax.props[0] as QuantExpr
        assertEquals("∀", q.q)
        val body = q.body as InstExpr
        assertEquals("valid", (body.target as NameRef).name)
    }

    @Test
    fun `命题参数理论`() {
        val f = parseSource("fun witness[@q]<q>: None {}", "t")
        val d = (f.entries[0] as DeclEntry).decl as FunDecl
        assertEquals(listOf(PropParam("q")), d.theory)
    }

    @Test
    fun `类型参数与 class 成员`() {
        val f = parseSource("class Show[T] { fun show(v: T): Str }", "t")
        val c = (f.entries[0] as DeclEntry).decl as ClassDecl
        assertEquals(listOf(TypeParam("T", null)), c.theory)
        assertEquals("show", c.members[0].name)
    }

    @Test
    fun `enum 逗号分隔构造子`() {
        val f = parseSource("enum Bool { true, false }", "t")
        val e = (f.entries[0] as DeclEntry).decl as EnumDecl
        assertEquals(listOf("true", "false"), e.ctors.map { it.name })
    }

    @Test
    fun `struct 与 impl`() {
        val f = parseSource(
            "struct Point(x: Nat, y: Nat)\nimpl Show for Point { fun show(v: Point): Str = \"pt\" }",
            "t",
        )
        assertTrue((f.entries[0] as DeclEntry).decl is StructDecl)
        val impl = (f.entries[1] as DeclEntry).decl as ImplDecl
        assertEquals("Show", impl.trait.name)
        assertEquals("Point", impl.self.name)
    }

    @Test
    fun `符号中缀两参解糖`() {
        val ax = stmtsOf("unchecked.axiom[q ○ p]")[0] as AxiomStmt
        assertEquals("○", (ax.props[0] as BinExpr).op)
    }

    @Test
    fun `无优先级左结合`() {
        val mul = (stmtsOf("var t = 1 + 1 * 1")[0] as VarStmt).value as BinExpr
        assertEquals("*", mul.op)
        assertEquals("+", (mul.left as BinExpr).op)
    }

    @Test
    fun `符号函数声明`() {
        val f = parseSource("fun +(a: Int, b: Int): Int = a", "t")
        assertEquals("+", ((f.entries[0] as DeclEntry).decl as FunDecl).name)
    }

    @Test
    fun `lambda 反斜杠与大括号两形态`() {
        val st = stmtsOf("var f1 = \\(x) => x\nvar f2 = { y => y }")
        assertTrue((st[0] as VarStmt).value is LambdaExpr)
        assertTrue((st[1] as VarStmt).value is LambdaExpr)
    }

    @Test
    fun `尾随 lambda`() {
        val call = (stmtsOf("each(1) { v => v }")[0] as ExprStmt).expr as CallExpr
        assertTrue(call.args.last() is LambdaExpr)
    }

    @Test
    fun `if 表达式`() {
        assertTrue((stmtsOf("var z = if true() { 1 } else { 2 }")[0] as VarStmt).value is IfExpr)
    }

    @Test
    fun `when 字面量分支与 else 兜底`() {
        val w = (stmtsOf("var odd = when(n) { 1 -> true() else -> false() }")[0] as VarStmt).value as WhenExpr
        assertEquals(2, w.arms.size)
        assertTrue(w.arms[0].pattern is PatLit)
        assertTrue(w.arms[1].pattern is PatElse)
    }

    @Test
    fun `when 解构与守卫`() {
        val w = (stmtsOf("when(o) { Some(x) -> { 1 } is Nat if o < 1 -> 2 _ -> 0 }")[0] as ExprStmt).expr as WhenExpr
        val p0 = w.arms[0].pattern as PatCtor
        assertEquals("Some", p0.name)
        assertEquals(listOf("x"), p0.args.map { (it as PatCtor).name })   // 裸名由语义层裁定为绑定
        assertTrue(w.arms[0].body is BlockExpr)
        assertEquals("Nat", (w.arms[1].pattern as PatIs).type.name)
        assertTrue(w.arms[1].guard is BinExpr)
        assertTrue(w.arms[2].pattern is PatBind)
    }

    @Test
    fun `when 绑定主题与 is as`() {
        val w = (stmtsOf("when(var e = 0) { is Nat as m -> m }")[0] as ExprStmt).expr as WhenExpr
        assertEquals("e", w.subjectBinding)
        assertEquals("m", (w.arms[0].pattern as PatIs).bind)
    }

    @Test
    fun `by 辅助策略两种形态`() {
        val st = stmtsOf("by [p]\nvar q = 1 by [r]")
        assertTrue(st[0] is ByStmt)
        val v = (st[1] as VarStmt).value
        assertTrue(v is ByExpr && v.props.size == 1)
    }

    @Test
    fun `type 别名声明解析`() {
        val f = parseSource("type Pair[T] = Optional[T]", "t")
        val a = (f.entries[0] as DeclEntry).decl as TypeAliasDecl
        assertEquals("Pair", a.name)
        assertEquals(listOf("T"), a.theory.filterIsInstance<TypeParam>().map { it.name })
        assertEquals("Optional", a.target.name)
        assertEquals("T", a.target.args.single().name)
    }

    @Test
    fun `T3 括号表达式不是元组`() {
        assertTrue((stmtsOf("var x = (1)")[0] as VarStmt).value is IntLit)
    }

    @Test
    fun `T3 元组字面量与元组模式解析`() {
        val f = parseSource("fun g(p: (Nat,Nat)): Nat = when(p) { (a, b) -> a }\nfun main() { var t = (1, 2) }", "t")
        val g = (f.entries[0] as DeclEntry).decl as FunDecl
        assertTrue((g.body as WhenExpr).arms[0].pattern is PatTuple)              // 单表达式体
        assertTrue((stmtsOf("var t = (1, 2)")[0] as VarStmt).value is TupleExpr)  // 元组字面量
    }

    @Test
    fun `体形态互斥 - 等号接花括号报错`() {
        // 决策 73：`= {…}` 混写解析层直接拒绝
        val e = assertFailsWith<ParseFailure> { parseSource("fun f(): Nat = { 1 }", "t") }
        assertTrue(e.message!!.contains("不能 `= {"), "实际: ${e.message}")
    }

    @Test
    fun `顶层禁语句 - 执行代码须进 main`() {
        // 决策 74：顶层只允许声明
        val e = assertFailsWith<ParseFailure> { parseSource("foo()", "t") }
        assertTrue(e.message!!.contains("顶层只能写声明"), "实际: ${e.message}")
    }

    @Test
    fun `块体无等号合法`() {
        val f = parseSource("fun f(): Nat { return 1 }", "t")
        val d = (f.entries[0] as DeclEntry).decl as FunDecl
        assertTrue(d.body is BlockExpr)
    }

    @Test
    fun `O1 impl成员块体`() {
        val f = parseSource("class B { fun m(): Null }\nimpl B for Int { fun m(): Null { return null } }", "t")
        val cls = (f.entries[0] as DeclEntry).decl as ClassDecl
        assertNull(cls.members[0].body)                       // 无体签名不受影响
        val impl = (f.entries[1] as DeclEntry).decl as ImplDecl
        assertEquals(1, impl.members.size)
        assertTrue(impl.members[0].body is BlockExpr)          // 双层花括号正确归属
    }

    @Test
    fun `O1 匿名函数块体`() {
        val v = (stmtsOf("var g = fun _(x: Nat): Nat { return x }")[0] as VarStmt).value as AnonFunExpr
        assertTrue(v.body is BlockExpr)
    }

    @Test
    fun `O3 大括号struct体报语法错并给指引`() {
        // 决策 69：struct 体是圆括号参数列表，大括号明确拒绝（带指向性文案）
        val e = assertFailsWith<ParseFailure> { parseSource("struct A {\n  name: Str = \"u\"\n}", "t") }
        assertTrue(e.message!!.contains("struct 体是参数列表"), "实际: ${e.message}")
    }

    @Test
    fun `命名字段构造解析出 StructCtorExpr`() {
        // 决策 69：`A(name = "x")`（大写 + `IDENT =` 前瞻）→ 独立节点
        val v = (stmtsOf("var a = A(name = \"x\")")[0] as VarStmt).value
        assertTrue(v is StructCtorExpr && v.struct == "A" && v.assigns.single().first == "name")
    }

    @Test
    fun `return 语句与带值形态解析`() {
        val st = stmtsOf("if n > 0 { return 1 }\nreturn")
        val ifStmt = (st[0] as ExprStmt).expr as IfExpr
        assertTrue((ifStmt.thenBlock.stmts[0] as ReturnStmt).expr != null)   // return 1
        assertTrue((st[1] as ReturnStmt).expr == null)                        // 裸 return
    }

    @Test
    fun `mut 注解与赋值语句`() {
        val st = stmtsOf("@mut var c = 0\nc = c + 1")
        assertEquals(listOf("mut"), (st[0] as VarStmt).annotations)
        assertTrue(st[1] is AssignStmt)
    }

    @Test
    fun `unchecked 纯度逃逸`() {
        val f = parseSource("fun log(m: Str): None { unchecked print(m) }", "t")
        val d = (f.entries[0] as DeclEntry).decl as FunDecl
        val inner = (d.body as BlockExpr).stmts[0] as UncheckedStmt
        assertTrue(inner.inner is ExprStmt)
    }

    @Test
    fun `语法错误带位置`() {
        val e = assertFailsWith<ParseFailure> { parseSource("fun 1", "t") }
        assertEquals(1, e.tok.line)
    }

    @Test
    fun `整个示例文件可解析`() {
        val src = File("examples/diffuse.subl").readText()
        parseSource(src, "diffuse.subl")
    }
}
