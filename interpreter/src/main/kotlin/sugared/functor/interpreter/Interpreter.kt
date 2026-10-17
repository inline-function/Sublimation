package sugared.functor.interpreter

import sugared.functor.ast.*
import sugared.functor.checker.Checker
import sugared.functor.checker.DiagBag
import sugared.functor.parser.parseSource
import java.io.File

/**
 * 脚本模式解释器（§4/§6）。
 *
 * 执行管线：Lexer → Parser(scriptMode) → Checker → Evaluator
 * - Checker 做类型推断、上下文、纯度、高阶类型等全部静态检查
 * - Evaluator 只走运行时求值（不重复做类型系统）
 */
class Interpreter(
    /** 标准库目录（Sublimation 自举标准库 .subl 源文件；core 统一标准库为包目录，递归收集） */
    private val stdlibDir: File = File("test/core"),
) {

    // 宿主函数注册（含类型签名）
    private val hostFunctionInfos = LinkedHashMap<String, HostFuncSig>()
    private val hostStructs = LinkedHashMap<String, StructInfo>()
    private var globalFrame = ScriptFrame()

    /** 标准库目录（含子包）下的 .subl 源文件合集（顺序稳定：按相对路径排序） */
    private val stdlibFiles: List<File>
        get() = if (!stdlibDir.isDirectory) emptyList()
            else stdlibDir.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }
                .sortedBy { it.relativeTo(stdlibDir).path }.toList()

    init { registerStdlib() }

    /** 宿主函数签名（供 Checker 生成 @host FunDecl） */
    data class HostFuncSig(
        val params: List<Pair<String, ScriptType>>,
        val retType: ScriptType,
        val fn: (List<Any?>) -> Any?,
        val typeParams: List<String> = emptyList(),
    )

    // ============ 宿主 API（§4.1-4.5） ============

    /** 注册宿主函数——含类型签名（推荐），Checker 据此做类型检查 */
    fun register(
        name: String,
        vararg params: Pair<String, ScriptType>,
        retType: ScriptType = ScriptType.Null,
        fn: (List<Any?>) -> Any?,
    ) {
        hostFunctionInfos[name] = HostFuncSig(params.toList(), retType, fn)
    }

    /** 注册泛型宿主函数——可声明类型参数（如 map[A,B]），签名中的 [ScriptType.Named] 名即类型变量 */
    fun registerGeneric(
        name: String,
        typeParams: List<String>,
        params: List<Pair<String, ScriptType>>,
        retType: ScriptType,
        fn: (List<Any?>) -> Any?,
    ) {
        hostFunctionInfos[name] = HostFuncSig(params, retType, fn, typeParams)
    }

    /** 注册宿主函数——无类型签名（兼容旧接口），签名按 Any→Any 处理 */
    fun register(name: String, fn: (List<Any?>) -> Any?) {
        register(name, retType = ScriptType.Any, fn = fn)
    }

    /** 注入结构体类型定义（§4.3） */
    fun injectStruct(
        name: String,
        vararg fields: Pair<String, ScriptType>,
        props: List<String> = emptyList(),
        mutableFields: List<String> = emptyList(),
        annotations: List<Annotation> = emptyList(),
    ) {
        hostStructs[name] = StructInfo(
            name = name,
            fieldTypes = fields.toMap(),
            props = props,
            mutableFields = mutableFields,
            annotations = annotations,
        )
    }

    // ============ 执行（§6） ============

    /**
     * 执行脚本源码。
     * 管线：解析 → 注入 @host 声明 → Checker（类型检查） → Evaluator（求值）。
     */
    fun run(source: String, fileName: String = "<script>") {
        runEntries(sugared.functor.parser.parseScriptSource(source, fileName), scriptMode = true, mainName = null)
    }

    /** 工程/目录模式：合并目录下顶层 .subl 文件 + stdlib，以 fun main() 为入口解释执行。 */
    fun runDir(dir: File) {
        val files = dir.listFiles { f -> f.name.endsWith(".subl") && !f.isDirectory }?.sortedBy { it.name } ?: emptyList()
        if (files.isEmpty()) throw InterpreterError("目录 " + dir.path + " 下没有 .subl 文件")
        runEntries(FileAst(files.flatMap { f -> parseSource(f.readText(), f.name).entries }), scriptMode = false, mainName = "main")
    }

    /**
     * 脚本模式：运行单个 .subs 脚本。像 .kts 一样顶层语句直接执行，无 main 入口；
     * 每个脚本独立注入 stdlib/host，与其他脚本互不共享用户声明。
     */
    fun runScript(path: File) {
        run(path.readText(), path.name)
    }

    /** 脚本模式：运行多个脚本路径，各自独立（互不共享声明、互不可见）。 */
    fun runScripts(paths: List<File>) {
        for (p in paths) runScript(p)
    }

    /** 脚本模式：目录 = 目录下每个 .subs 各自独立运行（不合并、不共享）。 */
    fun runScriptDir(dir: File) {
        val files = subsIn(dir)
        if (files.isEmpty()) throw InterpreterError("目录 " + dir.path + " 下没有 .subs 脚本文件")
        runScripts(files)
    }

    /** 目录下所有 .subs 脚本文件（按文件名排序）。 */
    fun subsIn(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".subs") }?.sortedBy { it.name } ?: emptyList()

    private fun runEntries(rawAst: FileAst, scriptMode: Boolean, mainName: String?) {
        // H7：每次 run 独立全局帧，避免重复注册宿主/用户声明（declareFunction 只增不去重）
        globalFrame = ScriptFrame()

        // 1. 把宿主函数注入为 @host FunDecl（Checker 据此做类型检查）
        //    无类型签名时，近似为 fun name(x: Any): Any（至少 1 参，兼容简单调用）
        val hostEntries = hostFunctionInfos.map { (name, sig) ->
            val declParams = if (sig.params.isEmpty())
                listOf(Param("x", NamedType("Any", emptyList()), annotations = emptyList(), default = null))
            else
                sig.params.map { (pn, pt) ->
                    Param(pn, typeFromScriptType(pt), annotations = emptyList(), default = null)
                }
            DeclEntry(
                FunDecl(
                    name = name,
                    annotations = listOf("host"),
                    theory = sig.typeParams.map { TypeParam(it, null) },
                    params = declParams,
                    preps = emptyList(),
                    retType = if (sig.retType == ScriptType.Any) NamedType("Any", emptyList()) else typeFromScriptType(sig.retType),
                    posts = emptyList(),
                    body = null,  // @host 无体——体由宿主提供
                )
            )
        }

        // 1b. 把宿主结构体注入为 StructDecl（Checker 据此做构造/字段访问类型检查）
        val structEntries = hostStructs.map { (name, info) ->
            DeclEntry(
                StructDecl(
                    name = name,
                    theory = emptyList(),
                    fields = info.fieldTypes.map { (fname, ftype) ->
                        Param(fname, typeFromScriptType(ftype),
                            annotations = if (fname in info.mutableFields) listOf("mut") else emptyList(),
                            default = defaultValueFor(ftype))
                    },
                    annotations = if (info.mutableFields.isNotEmpty()) listOf("mut") else emptyList(),
                    props = emptyList(),
                )
            )
        }

        // 2. 合成 FileAst：完整标准库（容器 List/Set/Map/Result + 类型类 + 定理库）+ @host 声明 + 结构体声明，再脚本条目
        //    标准库是与语言同源的 .subl 源码（非残缺手写注入），脚本模式与编译模式共享同一抽象能力。
        val stdlibEntries = loadStdlibEntries()
        val checkedAst = FileAst(stdlibEntries + structEntries + hostEntries + rawAst.entries)

        // 3. Checker：类型推断 / 上下文 / 纯度 / HKT 等全部静态检查
        val checker = Checker(checkedAst, scriptMode = scriptMode)
        val diags = checker.run()
        if (diags.hasError) {
            val errors = diags.diags.filter { it.severity == sugared.functor.checker.Severity.ERROR }
            val msg = errors.joinToString("\n") { "[${it.code}] ${it.message}" }
            throw InterpreterError("脚本类型检查失败:\n$msg")
        }

        // 4. 把宿主函数注册到全局帧（Evaluator 执行时 resolve）
        for ((name, sig) in hostFunctionInfos) {
            globalFrame.declareFunction(
                FunDecl(
                    name = name, annotations = listOf("host"),
                    theory = emptyList(), params = emptyList(), preps = emptyList(),
                    retType = null, posts = emptyList(), body = null,
                )
            )
        }

        // 5. Evaluator：标准库（含其 FunDecl/enum/类型类）+ 脚本自己的条目；合成 @host 声明不参与求值
        val eval = Evaluator(
            hostFunctions = hostFunctionInfos.mapValues { it.value.fn },
            hostStructs = hostStructs,
        )
        val frame = ScriptFrame(globalFrame)
        eval.evaluate(FileAst(stdlibEntries + rawAst.entries), frame, mainName)
    }

    /** 加载标准库 .subl 源文件，合并为一个条目列表（标准库与语言同源，非手写注入）。
     *  v4（core 统一标准库）：@root 内建声明（print/readFile/jsonParse/timeNow…）跳过——
     *  脚本模式下内建由宿主函数表（hostEntries）与 checker 内建表提供，@root 条目仅为声明文档，
     *  留着会与 @host 声明撞 E-DUP-DECL。 */
    private fun loadStdlibEntries(): List<Entry> {
        val entries = ArrayList<Entry>()
        for (f in stdlibFiles) {
            try {
                entries += parseSource(f.readText(), f.name).entries
            } catch (e: RuntimeException) {
                throw InterpreterError("标准库文件解析失败 ${f.name}: ${e.message}")
            }
        }
        return entries.filter { e ->
            val fd = (e as? DeclEntry)?.decl as? FunDecl
            fd == null || "root" !in fd.annotations
        }
    }

    /** 字段默认值（供 Checker 构造检查） */
    private fun defaultValueFor(st: ScriptType): Expr? = when (st) {
        ScriptType.Nat, ScriptType.Int -> IntLit("0", "")
        ScriptType.Rat -> FloatLit("0.0", "")
        ScriptType.Str -> StrLit("", "")
        ScriptType.Bool -> NameRef("false", "")
        ScriptType.Null -> NameRef("null", "")
        else -> null  // 复杂类型无默认值，须显式构造
    }

    /** ScriptType → AST Type（供 Checker 合成声明用） */
    private fun typeFromScriptType(st: ScriptType): Type = when (st) {
        is ScriptType.ListT -> NamedType("List", listOf(typeFromScriptType(st.elem)))
        is ScriptType.OptionalT -> NamedType("Optional", listOf(typeFromScriptType(st.inner)))
        is ScriptType.TupleT -> TupleType(st.items.map { typeFromScriptType(it) })
        is ScriptType.FunT -> FunType(
            st.params.map { typeFromScriptType(it) },
            typeFromScriptType(st.ret), "", emptyList(),
        )
        ScriptType.AnyT -> NamedType("Any", emptyList())
        ScriptType.NullT -> NamedType("Null", emptyList())
        is ScriptType.Named -> NamedType(st.name, st.args.map { typeFromScriptType(it) })
    }

    // ============ 标准库内置 ============

    private fun registerStdlib() {

                // 打印——已在内建表（Types.kt: builtinimpure），Checker 自行解析；解释器兜底
        register("print") { args ->
            println(args.joinToString(" ") { it?.toString() ?: "null" })
            null
        }
        // 对齐编译模式 __dshtoStr：List 元素间无空格、整数 Rat 去浮点尾巴
        register("toStr", retType = ScriptType.Str) { args ->
            fun dshtoStr(v: Any?): String = when (v) {
                null -> "null"
                is List<*> -> "[" + v.joinToString(",") { dshtoStr(it) } + "]"
                is Double -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
                is Float -> { val d = v.toDouble(); if (d % 1.0 == 0.0) d.toLong().toString() else d.toString() }
                is ScriptValue.Struct -> if (v.fields.isEmpty()) v.typeName else v.toString()
                else -> v.toString()
            }
            dshtoStr(args.getOrNull(0))
        }

        // ---- 字符串 / 转换 / Math 内建运行时（Checker builtinPure 已有签名，这里补 Evaluator 运行时实现） ----
        register("concat", "a" to ScriptType.Str, "b" to ScriptType.Str, retType = ScriptType.Str) { args ->
            (args[0] as String) + (args[1] as String)
        }
        register("length", "s" to ScriptType.Str, retType = ScriptType.Nat) { args -> (args[0] as String).length.toLong() }
        register("charAt", "s" to ScriptType.Str, "i" to ScriptType.Nat, retType = ScriptType.Str) { args ->
            val s = args[0] as String; val i = (args[1] as Number).toInt()
            if (i in s.indices) s[i].toString() else ""
        }
        register("substring", "s" to ScriptType.Str, "a" to ScriptType.Nat, "b" to ScriptType.Nat, retType = ScriptType.Str) { args ->
            val s = args[0] as String; val a = (args[1] as Number).toInt(); val b = (args[2] as Number).toInt()
            s.substring(a.coerceIn(0, s.length), b.coerceIn(a, s.length))
        }
        register("strCmp", "a" to ScriptType.Str, "b" to ScriptType.Str, retType = ScriptType.Int) { args ->
            (args[0] as String).compareTo(args[1] as String).toLong()
        }
        register("strUpper", "s" to ScriptType.Str, retType = ScriptType.Str) { args -> (args[0] as String).uppercase() }
        register("strLower", "s" to ScriptType.Str, retType = ScriptType.Str) { args -> (args[0] as String).lowercase() }
        register("strTrim", "s" to ScriptType.Str, retType = ScriptType.Str) { args -> (args[0] as String).trim() }
        register("startsWith", "s" to ScriptType.Str, "p" to ScriptType.Str, retType = ScriptType.Bool) { args ->
            (args[0] as String).startsWith(args[1] as String)
        }
        register("endsWith", "s" to ScriptType.Str, "p" to ScriptType.Str, retType = ScriptType.Bool) { args ->
            (args[0] as String).endsWith(args[1] as String)
        }
        register("strReplace", "s" to ScriptType.Str, "a" to ScriptType.Str, "b" to ScriptType.Str, retType = ScriptType.Str) { args ->
            (args[0] as String).replace(args[1] as String, args[2] as String)
        }
        register("strSplit", "s" to ScriptType.Str, "sep" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            (args[0] as String).split(args[1] as String)
        }
        register("strIndexOf", "s" to ScriptType.Str, "p" to ScriptType.Str,
            retType = ScriptType.OptionalT(ScriptType.Nat)) { args ->
            val i = (args[0] as String).indexOf(args[1] as String)
            if (i < 0) ScriptValue.OptionalV(null) else ScriptValue.OptionalV(ScriptValue.Num(i.toLong()))
        }
        register("parseNat", "s" to ScriptType.Str,
            retType = ScriptType.OptionalT(ScriptType.Nat)) { args ->
            val n = (args[0] as String).toLongOrNull()
            if (n == null || n < 0) ScriptValue.OptionalV(null) else ScriptValue.OptionalV(ScriptValue.Num(n))
        }
        register("parseRat", "s" to ScriptType.Str,
            retType = ScriptType.OptionalT(ScriptType.Rat)) { args ->
            val r = (args[0] as String).toDoubleOrNull()
            if (r == null) ScriptValue.OptionalV(null) else ScriptValue.OptionalV(ScriptValue.Real(r))
        }
        registerGeneric("toRat", listOf("T"), listOf("n" to ScriptType.Named("T")),
            retType = ScriptType.Rat) { args ->
            (args[0] as Number).toDouble()
        }
        register("abs", "x" to ScriptType.Rat, retType = ScriptType.Rat) { args -> kotlin.math.abs((args[0] as Number).toDouble()) }
        register("sqrt", "x" to ScriptType.Rat, retType = ScriptType.Rat) { args -> kotlin.math.sqrt((args[0] as Number).toDouble()) }
        register("floor", "x" to ScriptType.Rat, retType = ScriptType.Rat) { args -> kotlin.math.floor((args[0] as Number).toDouble()) }
        register("ceil", "x" to ScriptType.Rat, retType = ScriptType.Rat) { args -> kotlin.math.ceil((args[0] as Number).toDouble()) }
        register("pow", "x" to ScriptType.Rat, "y" to ScriptType.Rat, retType = ScriptType.Rat) { args ->
            Math.pow((args[0] as Number).toDouble(), (args[1] as Number).toDouble())
        }
        register("mathMin", "a" to ScriptType.Rat, "b" to ScriptType.Rat, retType = ScriptType.Rat) { args ->
            kotlin.math.min((args[0] as Number).toDouble(), (args[1] as Number).toDouble())
        }
        register("mathMax", "a" to ScriptType.Rat, "b" to ScriptType.Rat, retType = ScriptType.Rat) { args ->
            kotlin.math.max((args[0] as Number).toDouble(), (args[1] as Number).toDouble())
        }
        register("mathRound", "x" to ScriptType.Rat, retType = ScriptType.Rat) { args -> kotlin.math.round((args[0] as Number).toDouble()) }

                registerGeneric("apply", listOf("A", "B"),
            listOf("f" to ScriptType.FunT(listOf(ScriptType.Named("A")), ScriptType.Named("B")),
                   "a" to ScriptType.Named("A")),
            retType = ScriptType.Named("B")
        ) { args ->
            (args[0] as ScriptFunction).call(listOf(args[1]))
        }

        }

    /** 脚本值 → 宿主值 */
    private fun toHost(v: ScriptValue): Any? = when (v) {
        is ScriptValue.Num -> v.value
        is ScriptValue.Real -> v.value
        is ScriptValue.Str -> v.value
        is ScriptValue.Bool -> v.value
        ScriptValue.Null -> null
        is ScriptValue.ListV -> v.items.map { toHost(it) }
        is ScriptValue.OptionalV -> if (v.value == null) null else toHost(v.value)
        is ScriptValue.ResultV -> v
        else -> v
    }

    /** 宿主值 → 脚本值（供返回 Optional/Result 的宿主函数构造包装值） */
    private fun fromHost(v: Any?): ScriptValue = when (v) {
        null -> ScriptValue.Null
        is ScriptValue -> v
        is Boolean -> ScriptValue.Bool(v)
        is Long -> ScriptValue.Num(v)
        is Int -> ScriptValue.Num(v.toLong())
        is Double -> ScriptValue.Real(v)
        is Float -> ScriptValue.Real(v.toDouble())
        is String -> ScriptValue.Str(v)
        is List<*> -> ScriptValue.ListV(v.map { fromHost(it) }.toMutableList())
        else -> ScriptValue.Str(v.toString())
    }
}