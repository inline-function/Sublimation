package sugared.functor.annotationproc

import java.io.File

/**
 * 脚本处理器的**文件装载**（root doc/注解处理器.md 11「第二版」：处理器是 Sublimation 脚本，
 * 由解释器解释执行）。
 *
 * 装载约定：一个 `.subs` 文件 = 一个处理器——
 * - 处理器名 = 文件主名（`allMut.subs` → 名 `allMut`）
 * - 关注的注解 = 文件主名（处理器只对同名注解起反应，引擎据此跳过无关 AST）
 *
 * 编译器默认从工作目录下的 [DEFAULT_DIR] 装载（仓库根的 `annotation processer/`），
 * 也可用 CLI `-p <路径>` 追加任意文件或目录（见 [ProcessorPipeline.loadProcessorFiles]）。
 */
object BuiltinProcessors {

    /** 默认处理器目录（相对工作目录；仓库根下含 allMut.subs 等内建脚本）。 */
    val DEFAULT_DIR = File("annotation processer")

    /** 标准库目录（跑处理器脚本的解释器用；core 统一标准库位于仓库根 core/）。 */
    private val stdlibDir = File("core")

    /**
     * 从目录装载全部 `.subs` 处理器（按文件名排序，保证注册序稳定）。
     * 目录不存在 -> 空列表（不报错：无内建处理器也是一种合法环境）。
     */
    fun loadFrom(dir: File): List<ScriptProcessor> {
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".subs") }
            ?.sortedBy { it.name }
            ?.map { scriptProcessor(it) }
            ?: emptyList()
    }

    /** 单文件装载：`.subs` 文件 -> 处理器；非 .subs 文件忽略。 */
    fun loadFile(f: File): ScriptProcessor? =
        if (f.isFile && f.name.endsWith(".subs")) scriptProcessor(f) else null

    private fun scriptProcessor(f: File): ScriptProcessor {
        val name = f.name.removeSuffix(".subs")
        val src = f.readText()
        // 处理器可声明额外关注的注解：首行/注释行 `@interested ann1, ann2`。
        // 用于跨注解纪律（如 composable.subs 同时关注 view：@view 出现时也要校验视图构造纪律）。
        val extra = Regex("@interested\\s+([A-Za-z0-9_, ]+)").find(src)
            ?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        return ScriptProcessor(
            name = name,
            interestedAnnotations = setOf(name) + extra,
            scriptSource = src,
            scriptFileName = f.path,
            stdlibDir = stdlibDir,
        )
    }

    /** 默认目录装载的内建处理器（CLI 不传 -p 时的默认集合）。 */
    fun all(): List<ScriptProcessor> = loadFrom(DEFAULT_DIR)

    /** @allMut 处理器实例（默认目录必须有 allMut.subs；给测试与宿主用）。 */
    fun allMut(): ScriptProcessor = loadFrom(DEFAULT_DIR).first { it.name == "allMut" }
}

/**
 * 脚本处理器装载结果：成功列表 + 装载期诊断（路径无效等，供 CLI 直接报给用户）。
 */
data class ProcessorLoadResult(
    val processors: List<ScriptProcessor>,
    val errors: List<String>,
)
