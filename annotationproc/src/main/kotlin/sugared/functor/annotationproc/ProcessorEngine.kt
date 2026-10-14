package sugared.functor.annotationproc

import sugared.functor.ast.*
import sugared.functor.checker.Diag
import sugared.functor.checker.DiagBag
import sugared.functor.checker.Severity
import sugared.functor.checker.Symbols

/**
 * 注解处理器引擎：注册表 + 多轮运行壳（对齐 root doc 注解处理器.md 4-5）。
 *
 * ## 一轮流程
 * 1. 扫描当前 AST -> 收集 [AnnotatedNode]
 * 2. 按注册顺序逐处理器调用：
 *    - 若 annotated 中无该处理器关注的注解 -> 跳过
 *    - 否则构建 [ProcessorInput]，调 process()，收集输出
 *    - 若 output.ast != 当前 AST -> 更新当前 AST
 * 3. 本轮发现变化 -> 调用 recheck 重新检查（可选）
 * 4. 无变化 -> 退出；超过 maxRounds 轮 -> 报 E-PROCESSOR-LOOP
 *
 * 重新检查保证「所有代码都过检查」——处理器产出视为新代码，不可绕过语言设计。
 */
class ProcessorEngine(
    private val maxRounds: Int = 5,
) {

    /** 一次完整的处理运行的结果 */
    data class RunResult(
        val ast: FileAst,
        val diagnostics: List<Diag>,
        val annotated: List<AnnotatedNode>,
        val rounds: Int,
        val loopDetected: Boolean,
    )

    /** 重新检查的产物（引擎内部用） */
    data class RecheckResult(val diagnostics: DiagBag, val symbols: Symbols? = null)

    private val processors = LinkedHashMap<String, AnnotationProcessor>()

    /** 注册处理器（按注册顺序 = 声明顺序）。返回 this 供链式调用 */
    fun addProcessor(p: AnnotationProcessor): ProcessorEngine {
        processors.putIfAbsent(p.name, p)
        return this
    }

    /** 移除指定名字的处理器 */
    fun removeProcessor(name: String) { processors.remove(name) }

    /** 已注册的处理器名列表（声明序） */
    val registeredNames: List<String> get() = processors.keys.toList()

    /** 所有处理器关注的注解名集合（供外部判断是否可能触发任何处理器） */
    val interestedAnnotationNames: Set<String>
        get() = processors.values.flatMap { it.interestedAnnotations }.toSet()

    /**
     * 执行注解处理多轮循环。
     *
     * @param inputAst 初始 AST（应已过 checker）
     * @param symbols 初始符号表；null 表示不提供符号表（[ProcessorContext] 会给空视图）
     * @param typeOf 类型查询闭包（见 [ProcessorInput.typeOf]）；默认永远返回 null
     * @param emit 生成代码发射器（见 [ProcessorInput.emit]）；默认无操作
     * @param recheck 重新检查闭包：输入新 AST，返回诊断和（可选的）更新后符号表。
     *                在一轮的 AST 变换后调用；若返回 null 则跳过本轮重新检查。
     */
    fun run(
        inputAst: FileAst,
        symbols: Symbols? = null,
        typeOf: (Expr) -> Type? = { null },
        emit: (String) -> Unit = {},
        recheck: ((FileAst) -> RecheckResult?)? = null,
    ): RunResult {
        var currentSymbols = symbols
        val diags = ArrayList<Diag>()
        var current = inputAst
        var lastAnnotated: List<AnnotatedNode> = emptyList()
        var changed = true
        var round = 0

        while (changed && round < maxRounds) {
            round++
            var roundChanged = false

            for (p in processors.values) {
                val sites = AnnotationScanner.scan(current)
                lastAnnotated = sites

                // 无关注注解 → 跳过（性能优化，对齐 doc 4.1）
                if (sites.none { it.annotation.name in p.interestedAnnotations })
                    continue

                val input = ProcessorContext(current, currentSymbols, sites, typeOf, emit)
                val output = try {
                    p.process(input)
                } catch (ex: Exception) {
                    diags += Diag(Severity.ERROR, "E-PROCESSOR-ERROR", "",
                        "注解处理器 " + p.name + " 抛异常：" + (ex.message ?: ""))
                    continue
                }

                diags += output.diagnostics
                // 结构相等（FileAst 是 data class）：处理器返回的 AST 与当前相同即视为「无变化」
                if (output.ast != current) {
                    current = output.ast
                    roundChanged = true
                }
            }

            changed = roundChanged
            if (changed && recheck != null) {
                val rc = recheck(current)
                if (rc != null) {
                    diags += rc.diagnostics.diags
                    if (rc.symbols != null) currentSymbols = rc.symbols
                }
            }
        }

        val loop = changed && round >= maxRounds
        if (loop) {
            diags += Diag(Severity.ERROR, "E-PROCESSOR-LOOP", "",
                "注解处理器在 " + maxRounds + " 轮内未收敛（可能产生触发自己的代码）")
        }

        return RunResult(current, diags, lastAnnotated, round, loop)
    }
}
