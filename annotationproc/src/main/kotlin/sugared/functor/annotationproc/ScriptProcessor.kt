package sugared.functor.annotationproc

import sugared.functor.checker.Diag
import sugared.functor.checker.Severity
import sugared.functor.interpreter.Interpreter
import java.io.File

/**
 * 脚本模式注解处理器（root doc/注解处理器.md 11「第二版」）：处理器逻辑是一段 Sublimation 脚本
 * （.subs 顶层语句），由 [Interpreter] 解释执行；脚本通过 [ProcessorApi] 暴露的 host 函数
 * 只读查询 AST、累积注解编辑与结构性编辑、报告诊断。本类在 process() 收尾把两类编辑
 * 不可变地作用回 AST（结构性编辑先、注解编辑后，均经引擎多轮 + 重查收敛）。
 *
 * 多轮驱动 / 重新检查 / 轮数上限保护统一由 [ProcessorEngine] 负责——本类只做「跑一次脚本 + 收编辑」，
 * 引擎按 AST 是否变化决定下一轮与重查（root doc 4 多轮契约）。
 *
 * 脚本约定：
 * - 顶层语句直接执行（脚本模式，无需 main）；
 * - 脚本抛异常（类型检查失败 / 运行时错误）→ 转译为 E-PROCESSOR-ERROR 诊断，AST 不变；
 * - 编辑幂等：注解已存在不重复加，引擎据此在下一轮判定「无变化」而停机。
 *
 * @param name 处理器名（注册与诊断用）
 * @param interestedAnnotations 关注的注解名（引擎据此跳过无关 AST）
 * @param scriptSource 脚本源码（.subs 形态：顶层语句 + 辅助 fun 声明）
 * @param scriptFileName 脚本文件名（诊断定位用）
 * @param stdlibDir 解释器加载的标准库目录（默认工作目录下的 test/core/，core 统一标准库包目录）
 */
class ScriptProcessor(
    override val name: String,
    override val interestedAnnotations: Set<String>,
    private val scriptSource: String,
    private val scriptFileName: String = "<processor:$name>",
    private val stdlibDir: File = File("test/core"),
    /** `@postcompile` 指令声明的入口名：指明文件内哪个顶层函数是编译后阶段入口。 */
    private val postEntry: String? = null,
) : AnnotationProcessor {

    override fun process(input: ProcessorInput): ProcessorOutput {
        val edits = ArrayList<AnnotationEdit>()
        val astEdits = ArrayList<AstEdit>()
        val diags = ArrayList<Diag>()
        val interp = Interpreter(stdlibDir)
        ProcessorApi.register(interp, input, edits, astEdits, diags)
        try {
            interp.run(scriptSource, scriptFileName)
        } catch (ex: Exception) {
            return ProcessorResult(
                input.ast,
                diags + Diag(Severity.ERROR, "E-PROCESSOR-ERROR", "",
                    "脚本处理器 $name 执行失败：" + (ex.message ?: ex.toString())),
            )
        }
        // 先结构性编辑（体替换/语句注入/声明增删）、再注解编辑——两者按声明名匹配，互不干扰
        val afterStruct = applyAstEdits(input.ast, astEdits, diags)
        val newAst = applyAnnotationEdits(afterStruct, edits, diags)
        return ProcessorResult(newAst, diags)
    }

    /**
     * 编译后阶段（V2）：`@postcompile <入口函数名>` 指令声明时，重新跑脚本并调用该入口；
     * 脚本可通过 option() 读 `-A key=value` 参数、用 runShell/writeFile 产物。
     * 入口名约定：`@postcompile build` → 执行脚本顶层定义的 build()。
     */
    override fun postCompile(ctx: PostCompileContext): List<Diag> {
        val entry = postEntry ?: return emptyList()
        val diags = ArrayList<Diag>()
        val interp = Interpreter(stdlibDir)
        ProcessorApi.register(interp, postInput(ctx), ArrayList(), ArrayList(), diags)
        try {
            // 入口调用作为顶层语句执行一次：脚本体先声明辅助函数，末尾调用入口；
            // 处理器常规处理语句须自行幂等或由 option("__post_compile__") 分支守卫。
            interp.run("fun __dshPostGuard() : Null = " + entry + "()\n" + scriptSource
                + "\n__dshPostGuard()\n", "<post:$name>")
        } catch (ex: Exception) {
            return diags + Diag(Severity.ERROR, "E-POSTCOMPILE-SCRIPT", "",
                "脚本处理器 $name 编译后阶段失败：" + (ex.message ?: ex.toString()))
        }
        return diags
    }

    /** postCompile 的 ProcessorInput 视图：最终 AST + options；查询函数照常可用。 */
    private fun postInput(ctx: PostCompileContext): ProcessorInput {
        val base = ProcessorContext(ctx.finalAst, null, emptyList(), { null }, {}, options = ctx.options)
        return object : ProcessorInput by base {
            override val options: Map<String, String> = ctx.options
        }
    }
}
