package sugared.functor.interpreter

/**
 * 脚本运行时作用域/帧。
 * 嵌套帧继承外层；顶层帧无 parent。
 */
class ScriptFrame(val parent: ScriptFrame? = null) {

    private val vars = LinkedHashMap<String, ScriptValue>()
    private val muts = LinkedHashSet<String>()
    private val refs = LinkedHashSet<String>()
    private val functions = LinkedHashMap<String, MutableList<sugared.functor.ast.FunDecl>>()

    fun lookupVar(name: String): ScriptValue? = vars[name] ?: parent?.lookupVar(name)

    fun hasVar(name: String): Boolean = vars.containsKey(name) || (parent?.hasVar(name) ?: false)

    fun declareVar(name: String, value: ScriptValue, mutable: Boolean = false, ref: Boolean = false) {
        vars[name] = value
        if (mutable) muts.add(name)
        if (ref) refs.add(name)
    }

    fun assignVar(name: String, value: ScriptValue) {
        if (vars.containsKey(name)) { vars[name] = value; return }
        val p = parent
        if (p != null) p.assignVar(name, value)
        else throw InterpreterError("未定义的变量 `" + name + "`，无法赋值")
    }

    fun isMutable(name: String): Boolean = muts.contains(name) || (parent?.isMutable(name) ?: false)
    fun isRef(name: String): Boolean = refs.contains(name) || (parent?.isRef(name) ?: false)

    fun declareFunction(fn: sugared.functor.ast.FunDecl) {
        functions.getOrPut(fn.name) { mutableListOf() }.add(fn)
    }

    /** 同名重载组（标准库 List/Set/Map 等容器方法重载，按接收者类型分派） */
    fun lookupFunctions(name: String): List<sugared.functor.ast.FunDecl> {
        val local = functions[name].orEmpty()
        val fromParent = parent?.lookupFunctions(name).orEmpty()
        return when {
            local.isEmpty() -> fromParent
            fromParent.isEmpty() -> local
            else -> (local + fromParent).distinctBy { it.signatureKey() }
        }
    }

    private fun sugared.functor.ast.FunDecl.signatureKey(): String =
        name + "(" + params.map { it.type.render() }.joinToString(",") + ")"

    fun lookupFunction(name: String): sugared.functor.ast.FunDecl? = lookupFunctions(name).firstOrNull()

    fun localNames(): List<String> = vars.keys.toList()

    /** 本帧直接声明的函数（供闭包捕获快照复制；沿父链收集由调用方完成） */
    fun localFunctions(): List<sugared.functor.ast.FunDecl> = functions.values.flatten()

    fun child(): ScriptFrame = ScriptFrame(this)
}

class InterpreterError(message: String) : RuntimeException(message)