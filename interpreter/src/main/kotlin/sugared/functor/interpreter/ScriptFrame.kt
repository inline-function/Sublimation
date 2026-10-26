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

    // t44（t40 出入2）：沿父链的查找一律改迭代——深作用域链（嵌套块 × 调用链动态叠加）下，
    // 递归版每次查找都把整条链压进 JVM 栈（SfG 规模工程 SO 的主因：单次 lookup 就 ~千帧）。
    fun lookupVar(name: String): ScriptValue? {
        var f: ScriptFrame? = this
        while (f != null) { val v = f.vars[name]; if (v != null) return v; f = f.parent }
        return null
    }

    fun hasVar(name: String): Boolean {
        var f: ScriptFrame? = this
        while (f != null) { if (f.vars.containsKey(name)) return true; f = f.parent }
        return false
    }

    fun declareVar(name: String, value: ScriptValue, mutable: Boolean = false, ref: Boolean = false) {
        vars[name] = value
        if (mutable) muts.add(name)
        if (ref) refs.add(name)
    }

    fun assignVar(name: String, value: ScriptValue) {
        var f: ScriptFrame? = this
        while (f != null) {
            if (f.vars.containsKey(name)) { f.vars[name] = value; return }
            f = f.parent
        }
        throw InterpreterError("未定义的变量 `" + name + "`，无法赋值")
    }

    fun isMutable(name: String): Boolean {
        var f: ScriptFrame? = this
        while (f != null) { if (f.muts.contains(name)) return true; f = f.parent }
        return false
    }
    fun isRef(name: String): Boolean {
        var f: ScriptFrame? = this
        while (f != null) { if (f.refs.contains(name)) return true; f = f.parent }
        return false
    }

    fun declareFunction(fn: sugared.functor.ast.FunDecl) {
        functions.getOrPut(fn.name) { mutableListOf() }.add(fn)
    }

    /** 同名重载组（标准库 List/Set/Map 等容器方法重载，按接收者类型分派）。
     *  收集顺序与原递归版一致：最内层声明在前、逐层向外，按 signatureKey 去重时内层覆盖外层。 */
    fun lookupFunctions(name: String): List<sugared.functor.ast.FunDecl> {
        // 父链：下标 0 = 当前（最内层），末尾 = 顶层；从外向内累加以保持「内层在前」的结果序
        val chain = ArrayList<ScriptFrame>()
        var f: ScriptFrame? = this
        while (f != null) { chain.add(f); f = f.parent }
        var acc: List<sugared.functor.ast.FunDecl> = emptyList()
        for (i in chain.size - 1 downTo 0) {
            val local = chain[i].functions[name].orEmpty()
            acc = when {
                local.isEmpty() -> acc
                acc.isEmpty() -> local
                else -> (local + acc).distinctBy { it.signatureKey() }
            }
        }
        return acc
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