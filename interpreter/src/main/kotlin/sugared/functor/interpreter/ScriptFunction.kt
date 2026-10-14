package sugared.functor.interpreter

/**
 * 跨边界的函数值统一接口（§4.2）。
 *
 * 泛型全部擦除为属性；上下文（前后件）全部擦除为属性（渲染后的字符串）。
 * 宿主调用 call(args) 时由解释器负责：泛型实例化、值包装、前件检查、求值、结果包装。
 * 宿主不需要理解泛型和上下文。
 */
interface ScriptFunction {
    /** 泛型参数名，如 ["T"]；非泛型为空 */
    val typeParams: List<String>

    /** 上文（前置命题）——渲染后的字符串 */
    val preps: List<String>

    /** 下文（后置命题）——渲染后的字符串 */
    val posts: List<String>

    /** 形参类型（渲染），供宿主参考 */
    val paramTypes: List<String>

    /** 返回类型（渲染） */
    val retType: String

    /** 调用——实参按脚本值约定传入（§4.6 类型映射） */
    fun call(args: List<Any?>): Any?

    /**
     * 把多态函数值实例化为单态（可选，§4.2）。返回新的 [ScriptFunction]，
     * 供宿主多次调用，避免每次 call 都重新推导泛型。非泛型函数返回自身。
     */
    fun instantiate(typeArgs: List<String>): ScriptFunction = this
}
