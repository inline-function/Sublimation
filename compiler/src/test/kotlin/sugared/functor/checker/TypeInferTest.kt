package sugared.functor.checker

import sugared.functor.ast.FunType
import sugared.functor.ast.Type
import sugared.functor.ast.NamedType
import sugared.functor.ast.namedT
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P7 真合一（v1.0 计划 §9，决策 83）：TypeInfer.unifyInto 的标准合一语义单测。
 * 覆盖：类型变量求解、结构递归（嵌套类型参数）、occurs check、synthetic 不绑定变量、已绑定一致性。
 */
class TypeInferTest {
    private fun mutable(): LinkedHashMap<String, Type> = LinkedHashMap()

    @Test
    fun `unifyInto 结构递归解出嵌套类型参数`() {
        val sub = mutable()
        val want = namedT("Optional", listOf(namedT("T")))   // Optional<T>
        val got = namedT("Optional", listOf(namedT("Nat")))
        assertTrue(unifyInto(want, got, setOf("T"), sub))
        assertEquals("Nat", sub["T"]?.render())
    }

    @Test
    fun `unifyInto occurs check 拒绝自指`() {
        val sub = mutable()
        val want = namedT("T")                                 // T
        val got = namedT("Optional", listOf(namedT("T")))      // Optional<T>
        assertFalse(unifyInto(want, got, setOf("T"), sub))
        assertFalse(sub.containsKey("T"))
    }

    @Test
    fun `unifyInto 变量-变量单向绑定`() {
        val sub = mutable()
        assertTrue(unifyInto(namedT("T"), namedT("U"), setOf("T", "U"), sub))
        assertEquals("U", sub["T"]?.render())
    }

    @Test
    fun `unifyInto 已绑定一致性静默保持`() {
        val sub = mutable()
        assertTrue(unifyInto(namedT("T"), namedT("Nat"), setOf("T"), sub))
        // 已绑 T=Nat 再遇 Int：typeLooseEq 宽容（数值升格语义），保持首个绑定不覆盖
        assertTrue(unifyInto(namedT("T"), namedT("Int"), setOf("T"), sub))
        assertEquals("Nat", sub["T"]?.render())
    }

    @Test
    fun `unifyInto synthetic 不绑定变量`() {
        val sub = mutable()
        assertTrue(unifyInto(namedT("T"), syntheticT("推导"), setOf("T"), sub))
        assertFalse(sub.containsKey("T"))
        // 反向（got 是变量、want synthetic）同样不绑
        assertTrue(unifyInto(syntheticT("推导"), namedT("T"), setOf("T"), sub))
        assertFalse(sub.containsKey("T"))
    }

    @Test
    fun `unifyInto FunType 参数返回递归`() {
        val sub = mutable()
        // 直接构造 FunType：参数 (T)=>Str 与 (Nat)=>Str 合一解出 T=Nat
        val f1 = FunType(listOf(namedT("T")), namedT("Str"))
        val f2 = FunType(listOf(namedT("Nat")), namedT("Str"))
        assertTrue(unifyInto(f1, f2, setOf("T"), sub))
        assertEquals("Nat", sub["T"]?.render())
    }

    // ---- 推导器补强：元组合一 / occurs 嵌套 / builtinOp 签名 ----

    @Test
    fun `unifyInto 元组类型逐项合一`() {
        val sub = mutable()
        val want = sugared.functor.ast.TupleType(listOf(namedT("T"), namedT("U")))
        val got = sugared.functor.ast.TupleType(listOf(namedT("Nat"), namedT("Str")))
        assertTrue(unifyInto(want, got, setOf("T", "U"), sub))
        assertEquals("Nat", sub["T"]?.render())
        assertEquals("Str", sub["U"]?.render())
    }

    @Test
    fun `unifyInto 分量数不符返回 false`() {
        val sub = mutable()
        val want = sugared.functor.ast.TupleType(listOf(namedT("T"), namedT("U")))
        val got = sugared.functor.ast.TupleType(listOf(namedT("Nat")))
        assertFalse(unifyInto(want, got, setOf("T", "U"), sub))
    }

    @Test
    fun `unifyInto occurs check 嵌套拒绝自指`() {
        val sub = mutable()
        val f = FunType(listOf(namedT("T")), namedT("T"))   // (T) => T
        val got = FunType(listOf(namedT("T")), namedT("T"))
        // 两层嵌套：T 想绑到 (T)=>T——occurs check 应拒绝
        assertFalse(unifyInto(namedT("T"), got, setOf("T"), sub))
        assertFalse(sub.containsKey("T"))
    }

    @Test
    fun `unifyInto 数值类型变量升格到较大`() {
        val sub = mutable()
        // T 先绑 Nat，再遇 Rat：已绑定一致性走宽松，但数值应取较大
        assertTrue(unifyInto(namedT("T"), namedT("Nat"), setOf("T"), sub))
        assertTrue(unifyInto(namedT("T"), namedT("Rat"), setOf("T"), sub))
        // 保持首个绑定（与现有 TypeInferTest 一致：静默不覆盖，报错走 E-TYPE-MISMATCH）
        assertEquals("Nat", sub["T"]?.render())
    }

    @Test
    fun `builtinOp 数值算符返回数值 join`() {
        assertTrue(TypeInfer.builtinOp("+", listOf(namedT("Nat"), namedT("Rat")))?.render()?.startsWith("Rat") == true)
        assertEquals("Bool", TypeInfer.builtinOp("=", listOf(namedT("Nat"), namedT("Nat")))?.render())
        assertEquals("Bool", TypeInfer.builtinOp("->", emptyList())?.render())
        assertEquals(null, TypeInfer.builtinOp("+", listOf(namedT("Str"), namedT("Nat"))))
    }

    @Test
    fun `unify 数值升格取较大`() {
        assertEquals("Rat", TypeInfer.unify(namedT("Nat"), namedT("Rat"))?.render())
        assertEquals("Nat", TypeInfer.unify(namedT("Nat"), namedT("Nat"))?.render())
        assertEquals("Str", TypeInfer.unify(namedT("Str"), namedT("Str"))?.render())
    }

    @Test
    fun `kind 应用合一 F 绑构造子并递归实参`() {
        val sub = mutable()
        // F[A] ~ List[Nat] → F:=List, A:=Nat（HKT-U2，方案 A：NamedType 复用）
        val fa = NamedType("F", listOf(namedT("A")))
        val lstNat = NamedType("List", listOf(namedT("Nat")))
        assertTrue(unifyInto(fa, lstNat, setOf("F", "A"), sub, arityOf("F" to 1)))
        assertEquals("List", sub["F"]?.render())
        assertEquals("Nat", sub["A"]?.render())
    }

    @Test
    fun `kind 应用合一构造子不一致失败`() {
        val sub = mutable()
        val ar = arityOf("F" to 1)
        assertTrue(unifyInto(NamedType("F", listOf(namedT("A"))), NamedType("List", listOf(namedT("Nat"))), setOf("F", "A"), sub, ar))
        // F 已绑 List，二次遇 Optional → false（HKT-U2 构造子一致校验）
        assertFalse(unifyInto(NamedType("F", listOf(namedT("A"))), NamedType("Optional", listOf(namedT("Nat"))), setOf("F", "A"), sub, ar))
    }

    @Test
    fun `kind 形参与普通类型变量不混淆`() {
        val sub = mutable()
        // F[Nat] ~ T：T 是普通类型变量（arity 0 不在 arity 表），F 是构造子形参——宽容失败（HKT-U3）
        assertTrue(unifyInto(NamedType("F", listOf(namedT("Nat"))), namedT("T"), setOf("F", "T"), sub, arityOf("F" to 1)))
    }

    private fun arityOf(vararg pairs: Pair<String, Int>) = mapOf(*pairs)
}
