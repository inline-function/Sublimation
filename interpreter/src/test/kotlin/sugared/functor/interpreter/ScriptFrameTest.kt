package sugared.functor.interpreter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import sugared.functor.parser.parseSource

/**
 * t44（t40 出入2）：ScriptFrame 沿父链查找的迭代化回归。
 *
 * 原递归版（lookupVar/hasVar/assignVar/isMutable/isRef/lookupFunctions）把整条父链压进 JVM 栈——
 * SfG 规模工程（core×14 + 演示）的处理器脚本作用域链深达千级，单次查找即 ~千帧，默认栈 StackOverflowError。
 * 深链构造 + 查找完成即回归；顺带覆盖内层优先与重载合并的既定语义。
 */
class ScriptFrameTest {
    @Test
    fun `t44 深作用域链查找不爆栈（两万层）`() {
        var f = ScriptFrame()
        for (i in 1..20000) f = ScriptFrame(f)
        f.declareVar("n", ScriptValue.Num(7))
        assertEquals(7L, (f.lookupVar("n") as ScriptValue.Num).value)
        assertTrue(f.hasVar("n"))
        assertTrue(f.isMutable("n").not())
        f.declareVar("m", ScriptValue.Num(1), mutable = true)
        assertTrue(f.isMutable("m"))
        f.assignVar("m", ScriptValue.Num(9))
        assertEquals(9L, (f.lookupVar("m") as ScriptValue.Num).value)
    }

    @Test
    fun `t44 深链上声明函数 lookupFunctions 正常返回`() {
        val src = "fun pick(): Str = \"x\"\nfun pick(n: Nat): Str = \"y\""
        val decls = parseSource(src, "t44.subl").entries.filterIsInstance<sugared.functor.ast.DeclEntry>().map { it.decl }.filterIsInstance<sugared.functor.ast.FunDecl>()
        var f = ScriptFrame()
        for (i in 1..20000) f = ScriptFrame(f)
        decls.forEach { f.declareFunction(it) }
        assertEquals(2, f.lookupFunctions("pick").size, "深链末端的重载组应完整收集")
        assertEquals("pick", f.lookupFunction("pick")?.name)
        assertNull(f.lookupFunction("absent"), "不存在的名字返回 null 而不爆栈")
    }

    @Test
    fun `t44 内层声明优先于外层（覆盖语义不变）`() {
        val outer = ScriptFrame()
        outer.declareVar("x", ScriptValue.Num(1))
        val inner = ScriptFrame(outer)
        inner.declareVar("x", ScriptValue.Num(2))
        assertEquals(2L, (inner.lookupVar("x") as ScriptValue.Num).value, "内层覆盖外层")
        assertEquals(1L, (outer.lookupVar("x") as ScriptValue.Num).value, "外层不受影响")
        inner.assignVar("x", ScriptValue.Num(3))
        assertEquals(3L, (inner.lookupVar("x") as ScriptValue.Num).value)
        assertEquals(1L, (outer.lookupVar("x") as ScriptValue.Num).value, "赋值落在声明层，不串扰外层")
    }
}
