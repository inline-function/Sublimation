package sugared.functor.interpreter

/**
 * 通用注解（§4.4）。
 *
 * 所有 AST 节点统一带 annotations 属性，注解是通用容器。
 * 注解参数：常量 + 类型引用，不支持任意表达式。
 * 用户不能自定义注解名——注解名字空间由宿主提供。
 */
data class Annotation(
    val name: String,                    // "mut" / "impure" / "MyCheck"
    val args: List<Any?> = emptyList(),  // 常量或类型引用（ScriptType）
) {
    /** 内置注解名（编译器额外认识的名字） */
    companion object {
        const val MUT = "mut"
        const val IMPURE = "impure"
        const val ROOT = "root"
        const val HOST = "host"
        const val THEOREM = "theorem"
        const val CLASSIC = "classic"
        const val ASYNC = "async"

        /** 内置注解集合（§4.4 表） */
        val BUILTIN = setOf(MUT, IMPURE, ROOT, HOST, THEOREM, CLASSIC, ASYNC)

        fun mut() = Annotation(MUT)
        fun impure() = Annotation(IMPURE)
        fun root() = Annotation(ROOT)
        fun host() = Annotation(HOST)
        fun theorem() = Annotation(THEOREM)
        fun classic() = Annotation(CLASSIC)
        fun async() = Annotation(ASYNC)
    }

    /** 是否为内置注解 */
    fun isBuiltin(): Boolean = name in BUILTIN
}
