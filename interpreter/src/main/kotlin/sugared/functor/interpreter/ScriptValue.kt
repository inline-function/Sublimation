package sugared.functor.interpreter

/**
 * 脚本运行时的值体系（§4.5/§4.6）。
 *
 * 值跨边界一律引用语义（非拷贝）。脚本改宿主对象 = 改宿主对象。
 *
 * §4.6 类型映射：
 *  Nat/Int/Rat → Num/Real（Long/Double）
 *  Str → Str（String）
 *  Bool → Bool（Boolean）
 *  Null → Null（null）
 *  List[T] → ListV
 *  Optional[T] → OptionalV（内部值为 null 表示 None）
 *  Result[T,E] → ResultV
 *  结构体/宿主注入类型 → Struct
 *  函数值 → Fun（宿主函数或脚本闭包）
 */
sealed class ScriptValue {

    /** 自然数/整数 */
    data class Num(val value: Long) : ScriptValue() {
        override fun toString(): String = value.toString()
    }

    /** 有理数 */
    data class Real(val value: Double) : ScriptValue() {
        override fun toString(): String = value.toString()
    }

    data class Str(val value: String) : ScriptValue() {
        override fun toString(): String = "\"" + value + "\""
    }

    data class Bool(val value: Boolean) : ScriptValue() {
        override fun toString(): String = value.toString()
    }

    object Null : ScriptValue() {
        override fun toString(): String = "null"
    }

    /** 列表（可变，引用语义） */
    class ListV(val items: MutableList<ScriptValue> = mutableListOf()) : ScriptValue() {
        override fun equals(other: Any?): Boolean = other is ListV && other.items == items
        override fun hashCode(): Int = items.hashCode()
        override fun toString(): String = items.joinToString(", ", "[", "]")
    }

    /** 结构体 / 宿主注入类型：字段表 + 可变字段集合 */
    class Struct(
        val typeName: String,
        val fields: MutableMap<String, ScriptValue> = LinkedHashMap(),
        val mutableFields: MutableSet<String> = LinkedHashSet(),
    ) : ScriptValue() {
        override fun toString(): String =
            typeName + "(" + fields.entries.joinToString(", ") { (k, v) -> k + "=" + v } + ")"
    }

    /** 可选值：value == null → None；否则 Some(value) */
    data class OptionalV(val value: ScriptValue?) : ScriptValue() {
        override fun toString(): String = if (value == null) "None" else "Some(" + value + ")"
    }

    /** Result[T, E]：isOk=true → Ok(value)；isOk=false → Err(error) */
    data class ResultV(val isOk: Boolean, val value: ScriptValue?, val error: ScriptValue?) : ScriptValue() {
        override fun toString(): String = if (isOk) "Ok(" + value + ")" else "Err(" + error + ")"
    }

    /** 函数值：宿主函数或脚本闭包，统一为 [ScriptFunction]（§4.2） */
    data class Fun(val fn: ScriptFunction) : ScriptValue() {
        override fun equals(other: Any?): Boolean = other is Fun && other.fn === fn
        override fun hashCode(): Int = System.identityHashCode(fn)
        override fun toString(): String = "<fun>"
    }
}
