package sugared.functor.annotationproc

import sugared.functor.ast.FileAst
import sugared.functor.checker.Checker
import sugared.functor.checker.Diag
import sugared.functor.checker.DiagBag
import sugared.functor.checker.Severity
import sugared.functor.checker.Symbols

/**
 * 注解处理器与编译管线的对接层（处理器由解释器解释执行；默认从 `annotation processer/` 装载文件脚本）。
 *
 * 三条入口：
 * - [loadProcessorFiles]：CLI `-p <路径>` 追加的脚本处理器装载（文件或目录）
 * - **单文件**：[run]——引擎多轮 + 每轮重查（独立 Checker）；返回最终 AST 与处理器诊断。
 * - **多模块**：[moduleTransformer]——把处理器包成「每模块一个引擎、不做模块内重查」的变换闭包，
 *   交给 compiler 侧 MultiModule.processModules 统一重查（跨模块重新检查在模块层做才完整）。
 */
object ProcessorPipeline {

    /**
     * CLI `-p <路径>` 的装载器：paths 里每项可为 `.subs` 文件或目录（目录递归收全部 .subs）。
     * 无效路径只记错误字符串（供 CLI 转成错误输出），不抛异常。
     */
    fun loadProcessorFiles(paths: List<java.io.File>): ProcessorLoadResult {
        val out = ArrayList<ScriptProcessor>()
        val errs = ArrayList<String>()
        for (p in paths) {
            when {
                p.isDirectory -> out += BuiltinProcessors.loadFrom(p)
                p.isFile && p.name.endsWith(".subs") -> BuiltinProcessors.loadFile(p)?.let { out += it }
                else -> errs += "注解处理器路径无效（不是 .subs 文件或目录）：" + p.path
            }
        }
        return ProcessorLoadResult(out, errs)
    }

    /** 跑内建脚本处理器（单文件管线）。AST 无关注注解时引擎整轮跳过、引用不变。 */
    fun runBuiltin(ast: FileAst): Pair<FileAst, DiagBag> = run(ast, BuiltinProcessors.all())

    /** 单文件管线 + CLI `-p` 追加处理器；装载错误汇入返回的诊断袋（E-PROCESSOR-LOAD）。 */
    fun runBuiltin(ast: FileAst, extra: ProcessorLoadResult): Pair<FileAst, DiagBag> {
        val (a, b) = run(ast, BuiltinProcessors.all() + extra.processors)
        extra.errors.forEach { e -> b.error("E-PROCESSOR-LOAD", "", e) }
        return a to b
    }

    /**
     * 单文件注解处理器管线：多轮（上限 5）+ 每轮变化后用独立 Checker 重查。
     * 无关注注解时直接返回原 AST 与空袋（零开销）。
     */
    fun run(ast: FileAst, processors: List<AnnotationProcessor>): Pair<FileAst, DiagBag> {
        if (processors.isEmpty()) return ast to DiagBag()
        val engine = ProcessorEngine(maxRounds = 5)
        processors.forEach { engine.addProcessor(it) }
        if (engine.interestedAnnotationNames.isEmpty()) return ast to DiagBag()
        val result = engine.run(
            inputAst = ast,
            symbols = null,
            typeOf = { null },
            emit = {},
            recheck = { newAst ->
                val chk = Checker(newAst)
                ProcessorEngine.RecheckResult(chk.run(), chk.syms)
            },
        )
        return result.ast to bagOf(result.diagnostics)
    }

    /**
     * 多模块适配：每个模块各自跑引擎（子轮循环/越界保护仍在），模块内不重查——
     * compiler 侧 processModules 在全部模块变换后统一重跑模块间检查（符号交叉引用才完整）。
     */
    fun moduleTransformer(
        processors: List<AnnotationProcessor>,
    ): (modulePath: String, ast: FileAst, symbols: Symbols, sink: (Diag) -> Unit) -> FileAst =
        { _, ast, symbols, sink ->
            if (processors.isEmpty()) ast
            else {
                val engine = ProcessorEngine(maxRounds = 5)
                processors.forEach { engine.addProcessor(it) }
                val r = engine.run(ast, symbols = symbols, typeOf = { null }, emit = {}, recheck = null)
                r.diagnostics.forEach(sink)
                r.ast
            }
        }

    /** 内建脚本处理器的多模块适配（默认装载）。 */
    fun builtinModuleTransformer() = moduleTransformer(BuiltinProcessors.all())

    /** 多模块适配 + CLI `-p` 追加处理器（内建默认集 + 装载结果）。 */
    fun builtinModuleTransformer(extra: ProcessorLoadResult) =
        moduleTransformer(BuiltinProcessors.all() + extra.processors)

    /** 把装载错误汇成诊断袋（E-PROCESSOR-LOAD），供 CLI 在跑处理器前先报。 */
    fun loadErrorsToBag(extra: ProcessorLoadResult): DiagBag = DiagBag().apply {
        extra.errors.forEach { e -> error("E-PROCESSOR-LOAD", "", e) }
    }
}

/** 把诊断列表汇成 DiagBag（逐级映射，保留严重级别）。 */
internal fun bagOf(diags: List<Diag>): DiagBag = DiagBag().apply {
    for (d in diags) when (d.severity) {
        Severity.ERROR -> error(d.code, d.pos, d.message)
        Severity.WARN -> warn(d.code, d.pos, d.message)
        Severity.HINT -> hint(d.code, d.pos, d.message)
        Severity.SUPPLEMENT -> supplement(d.code, d.pos, d.message)
    }
}
