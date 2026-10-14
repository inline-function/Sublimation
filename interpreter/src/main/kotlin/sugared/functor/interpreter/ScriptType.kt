package sugared.functor.interpreter

/**
 * 脚本模式类型表示（§4.3/§4.6）。
 *
 * 支持完整类型语法：Named、List、Optional、Tuple、Function。
 * ScriptType("Nat") 工厂返回 [Named]；
 * ScriptType.Named("Point") 显式构造嵌套/参数化类型。
 */
sealed class ScriptType {

    companion object {
        /** 工厂：ScriptType("Nat") → Named("Nat") */
        operator fun invoke(name: String): ScriptType = Named(name)

        val Nat = ScriptType("Nat")
        val Int = ScriptType("Int")
        val Rat = ScriptType("Rat")
        val Str = ScriptType("Str")
        val Bool = ScriptType("Bool")
        val Null = ScriptType("Null")
        val Any = ScriptType.AnyT
    }

    /** 具名类型 / 类型引用：Nat、List[Nat]、T */
    data class Named(val name: String, val args: List<ScriptType> = emptyList()) : ScriptType()

    /** 列表类型 List[T] */
    data class ListT(val elem: ScriptType) : ScriptType()

    /** 可选类型 Optional[T]（可空类型 T?） */
    data class OptionalT(val inner: ScriptType) : ScriptType()

    /** 元组类型 (T, U) */
    data class TupleT(val items: List<ScriptType>) : ScriptType()

    /** 函数类型 (A, B) => C */
    data class FunT(val params: List<ScriptType>, val ret: ScriptType) : ScriptType()

    /** 顶类型（运行时擦除，值可为任意） */
    object AnyT : ScriptType()

    /** 单值 Null 类型 */
    object NullT : ScriptType()

    override fun toString(): String = render()

    fun render(): String {
        return when (this) {
            is Named -> if (args.isEmpty()) name else name + "[" + args.joinToString(", ") { it.render() } + "]"
            is ListT -> "List[" + elem.render() + "]"
            is OptionalT -> "Optional[" + inner.render() + "]"
            is TupleT -> "(" + items.joinToString(", ") { it.render() } + ")"
            is FunT -> "(" + params.joinToString(", ") { it.render() } + ")=>" + ret.render()
            AnyT -> "Any"
            NullT -> "Null"
        }
    }

    // ---- 静态工厂 ----

    fun list(elem: ScriptType): ScriptType = ListT(elem)
    fun optional(inner: ScriptType): ScriptType = OptionalT(inner)
    fun funType(params: List<ScriptType>, ret: ScriptType): ScriptType = FunT(params, ret)
    fun tuple(items: List<ScriptType>): ScriptType = TupleT(items)
}
