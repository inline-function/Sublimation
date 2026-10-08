package sugared.functor.checker

import sugared.functor.ast.FunType
import sugared.functor.ast.Type
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
}
