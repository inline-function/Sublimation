package sugared.functor.annotationproc

import sugared.functor.ast.FileAst
import sugared.functor.checker.Checker
import sugared.functor.checker.Diag
import sugared.functor.checker.Severity
import sugared.functor.checker.Symbols
import sugared.functor.interpreter.Interpreter
import sugared.functor.parser.parseSource
import java.io.File

/**
 * 注解处理器的宿主外壳——脚本模式（root doc/脚本模式.md 8）的典型用法对接点。
 *
 * 场景：宿主（Kotlin）注入 @host 函数，脚本调用它们操作注解处理器：
 * ```
 * @host fun loadProject(path: Str): Null
 * @host fun processAnnotations(): Null
 * @host fun writeGenerated(path: Str): Null
 *
 * loadProject("myproject/")
 * processAnnotations()
 * writeGenerated("out.subl")
 * ```
 *
 * Kotlin 宿主侧用法：
 * ```
 * val host = AnnotationProcessorHost()
 * host.addProcessor(MyProcessor())
 * val interp = Interpreter()
 * host.register(interp)          // 注入 loadProject / processAnnotations / writeGenerated
 * interp.run(scriptSource)
 * ```
 *
 * [register] 是「与脚本模式 Interpreter 的对接点」：把 [hostFunctions] 里的 @host 函数
 * 逐个注入 Interpreter。解释器子代理只需调 [AnnotationProcessorHost.register] 即可让脚本
 * 拥有操作注解处理器的三个宿主函数，无需理解注解处理细节。
 */
class AnnotationProcessorHost(
    private val engine: ProcessorEngine = ProcessorEngine(maxRounds = 5),
) {
    /** 已加载的工程源文件（绝对路径 -> AST），保持加载顺序 */
    private val loaded = LinkedHashMap<String, FileAst>()

    /** 处理器 emit 的生成代码累积区（由 [writeGenerated] 写出） */
    private val generated = StringBuilder()

    /** 最近一次 processAnnotations 的结果 */
    var lastResult: ProcessorEngine.RunResult? = null
        private set

    /** 最近一次 processAnnotations 的全量诊断（初始检查 + 处理器 + 重查） */
    var lastDiagnostics: List<Diag> = emptyList()
        private set

    // ============ 处理器注册 ============

    fun addProcessor(p: AnnotationProcessor): AnnotationProcessorHost {
        engine.addProcessor(p)
        return this
    }

    // ============ @host loadProject ============

    /** 加载工程源码。path 可为单个 .subl 文件或目录（递归收集 .subl，按绝对路径排序） */
    fun loadProject(path: String) {
        val f = File(path)
        val files: List<File> = when {
            f.isDirectory -> f.walkTopDown()
                .filter { it.isFile && it.extension == "subl" }
                .sortedBy { it.absolutePath }
                .toList()
            f.isFile -> listOf(f)
            else -> throw IllegalArgumentException("loadProject: 路径不存在或不可读：" + path)
        }
        for (src in files) {
            loaded[src.absolutePath] = parseSource(src.readText(), src.name)
        }
    }

    /** 已加载工程的 AST 列表（只读视图） */
    fun loadedProjectAsts(): List<FileAst> = loaded.values.toList()

    /** 把已加载工程的所有文件合并为单个 FileAst（同模块多文件共享符号表） */
    fun mergedProjectAst(): FileAst = FileAst(loaded.values.flatMap { it.entries })

    // ============ @host processAnnotations ============

    /**
     * 运行注解处理器管线：初检 -> 多轮处理（含重查） -> 汇总诊断。
     *
     * 初检通过 [check] 得到符号表（供处理器查询）；之后每轮 AST 变化后通过 recheck
     * 重新跑 checker（兑现「处理器产出视为新代码」的重新检查契约）。
     */
    fun processAnnotations(): ProcessorEngine.RunResult {
        require(loaded.isNotEmpty()) { "processAnnotations: 请先调用 loadProject(path)" }
        val merged = mergedProjectAst()

        val init = check(merged)
        val result = engine.run(
            inputAst = merged,
            symbols = init.symbols,
            typeOf = { null },
            emit = { generated.append(it) },
            recheck = { ast -> check(ast) },
        )

        lastResult = result
        lastDiagnostics = init.diagnostics.diags + result.diagnostics
        return result
    }

    /** 变换后的最终 AST（供宿主侧接 codegen；无处理记录时返回 null） */
    fun transformedAst(): FileAst? = lastResult?.ast

    // ============ @host writeGenerated ============

    /** 处理器 emit 累积的生成代码文本 */
    fun generatedText(): String = generated.toString()

    /** 清空生成代码累积区 */
    fun resetGenerated() { generated.setLength(0) }

    /** 把生成代码写出到指定文件（自动创建父目录） */
    fun writeGenerated(path: String) {
        val f = File(path)
        f.parentFile?.mkdirs()
        f.writeText(generated.toString())
    }

    // ============ 诊断查询（供脚本分支 / 宿主报告） ============

    fun diagnosticsReport(): String = lastDiagnostics.joinToString("\n") { d -> d.render() }

    fun hasErrors(): Boolean = lastDiagnostics.any { it.severity == Severity.ERROR }

    // ============ 与脚本模式 Interpreter 的对接点 ============

    /**
     * 产出 @host 函数表，供 Interpreter.register 逐个注册。
     *
     * 函数签名（§4.6 类型映射：Str -> String；Null -> null）：
     *  - loadProject(path: String) -> null
     *  - processAnnotations() -> null
     *  - writeGenerated(path: String) -> null
     *  - annotatedCount() -> Long（本次扫描到的注解挂载点数）
     *  - diagnosticReport() -> List<String>（诊断文本，按行）
     *  - hasErrors() -> Boolean
     */
    fun hostFunctions(): Map<String, (List<Any?>) -> Any?> = linkedMapOf(
        "loadProject" to { args -> loadProject(args[0] as String); null },
        "processAnnotations" to { _ -> processAnnotations(); null },
        "writeGenerated" to { args -> writeGenerated(args[0] as String); null },
        "annotatedCount" to { _ -> (lastResult?.annotated?.size ?: 0).toLong() },
        "diagnosticReport" to { _ -> diagnosticsReport().lines() },
        "hasErrors" to { _ -> hasErrors() },
    )

    /** 把 @host 函数注入到解释器（脚本即可调用 loadProject/processAnnotations/writeGenerated） */
    fun register(interp: Interpreter) {
        hostFunctions().forEach { (name, fn) -> interp.register(name, fn) }
    }

    // ============ 内部 ============

    /** 跑 checker：既是重新检查，也是符号表重建来源 */
    private fun check(ast: FileAst): ProcessorEngine.RecheckResult {
        val checker = Checker(ast)
        val bag = checker.run()
        return ProcessorEngine.RecheckResult(bag, checker.syms)
    }
}
