package sugared.functor.lsp

import sugared.functor.annotationproc.AnnotationProcessor
import sugared.functor.annotationproc.ScriptProcessor
import sugared.functor.ast.ClassDecl
import sugared.functor.ast.DeclEntry
import sugared.functor.ast.Entry
import sugared.functor.ast.EnumDecl
import sugared.functor.ast.FunDecl
import sugared.functor.ast.StructDecl
import sugared.functor.parser.parseSource
import java.io.File

/**
 * 语言服务扩展点（插件机制）。
 *
 * 语言服务不内置任何目标平台（Android/WASM/…）的解析分支；第三方宿主（如 CandyIDE 的
 * 平台插件）实现本接口并经 LspAnalysis.registerExtension 注册，把「附加标准库目录 +
 * 注解处理器目录 + 生效开关」注入语言服务的统一分析管线：
 *
 *  - stdlibDir：递归收集其下 .subl 声明，并入 Checker 输入与只读符号索引（跳转目标）；
 *  - processorsDir：其下全部 .subs 脚本注解处理器（文件名=处理器名=关注的注解），
 *    在语义检查之前与用户 AST 一起运行（与编译链同构）；
 *  - isEnabled()：由宿主按工程类型决定——未生效的扩展不参与任何分析。
 *
 * 生命周期：
 *  - 注册即对后续分析生效；已打开文档在下一次 didOpen/didChange 重分析时套用；
 *  - 注销（LspAnalysis.unregisterExtension）立即丢弃该扩展的缓存与贡献；
 *  - 变更目录/开关：语言服务按扩展对象身份缓存，直接改字段不会刷新已缓存内容——
 *    请先 unregister(name) 再注册新配置（或注册一个新名字的扩展）。
 *
 * 线程模型：与 LSP 服务器一致，单线程 stdio 循环内访问，注册表不做并发控制。
 */
interface LspExtension {
    /** 扩展名——注册表内的唯一标识（注册/注销/诊断日志用） */
    val name: String

    /** 附加标准库目录（递归 .subl；不存在或 null 视为无贡献） */
    val stdlibDir: File? get() = null

    /** 附加注解处理器目录（其下全部 .subs；不存在或 null 视为无贡献） */
    val processorsDir: File? get() = null

    /** 是否生效；未生效的扩展不参与任何分析（默认 false，宿主按工程开启） */
    fun isEnabled(): Boolean = false
}

/**
 * 声明式扩展（数据形态）：宿主可直接构造注册，也可经 LSP initialize 的
 * initializationOptions.extensions 由服务器代构造（见 LspServer）。
 */
data class SimpleLspExtension(
    override val name: String,
    override val stdlibDir: File? = null,
    override val processorsDir: File? = null,
    private val enabled: Boolean = false,
) : LspExtension {
    override fun isEnabled(): Boolean = enabled
}

/**
 * 扩展注册表：按注册顺序持有扩展，并按扩展对象身份缓存三类产物
 * （标准库条目 / 符号索引 / 处理器）。注销即丢弃缓存；新增计算时懒加载。
 */
class LspExtensionRegistry(
    /** 处理器脚本自身的 stdlib 目录（脚本求值用，即 core 标准库；由 LspAnalysis 注入） */
    private val coreStdlibDir: () -> File?,
) {

    private class Slot(val extension: LspExtension) {
        var stdlibEntries: List<Entry>? = null
        var symbolIndex: Map<String, Triple<String, String, String>>? = null
        var processors: List<AnnotationProcessor>? = null
    }

    private val slots = LinkedHashMap<String, Slot>()

    /** 注册（同名替换）扩展；返回被替换的旧同名扩展，缓存以新对象重置。 */
    fun register(extension: LspExtension): LspExtension? {
        val old = slots.remove(extension.name)?.extension
        slots[extension.name] = Slot(extension)
        return old
    }

    /** 按名字注销扩展；被移除扩展的缓存一并丢弃。不存在返回 null。 */
    fun unregister(name: String): LspExtension? = slots.remove(name)?.extension

    /** 已注册扩展（注册顺序，含未生效者）。 */
    fun all(): List<LspExtension> = slots.values.map { it.extension }

    /** 生效的扩展（保留注册顺序）。 */
    fun enabled(): List<LspExtension> = all().filter { it.isEnabled() }

    /** 生效扩展的附加标准库条目（每个扩展递归 .subl 解析；结果按扩展缓存）。 */
    fun enabledStdlibEntries(): List<Entry> =
        enabled().flatMap { stdlibEntriesOf(it) }

    /** 生效扩展的注解处理器（每个扩展目录下的 .subs；结果按扩展缓存）。 */
    fun enabledProcessors(): List<AnnotationProcessor> =
        enabled().flatMap { processorsOf(it) }

    /** 生效扩展的只读符号索引（注册序合并，同名先注册者优先）。 */
    fun enabledSymbolIndex(): Map<String, Triple<String, String, String>> {
        val out = HashMap<String, Triple<String, String, String>>()
        for (ext in enabled()) for ((k, v) in symbolIndexOf(ext)) out.putIfAbsent(k, v)
        return out
    }

    // ───────── 每扩展的懒加载（缓存键为扩展对象身份） ─────────

    private fun stdlibEntriesOf(ext: LspExtension): List<Entry> {
        slotOf(ext)?.stdlibEntries?.let { return it }
        val out = mutableListOf<Entry>()
        val dir = ext.stdlibDir
        if (dir != null && dir.isDirectory) {
            val files = dir.walkTopDown().filter { it.isFile && it.extension == "subl" }.toList()
            for (f in files) {
                runCatching {
                    out += parseSource(f.readText(), f.name).entries
                }
            }
        }
        slotOf(ext)?.stdlibEntries = out
        return out
    }

    private fun symbolIndexOf(ext: LspExtension): Map<String, Triple<String, String, String>> {
        slotOf(ext)?.symbolIndex?.let { return it }
        val out = HashMap<String, Triple<String, String, String>>()
        val dir = ext.stdlibDir
        if (dir != null && dir.isDirectory) {
            for (f in dir.walkTopDown().filter { it.isFile && it.name.endsWith(".subl") }.sortedBy { it.path }) {
                val text = try { f.readText() } catch (e: Exception) { continue }
                val ast = try { parseSource(text, f.name) } catch (e: Exception) { continue }
                for (entry in ast.entries) {
                    val d = (entry as? DeclEntry)?.decl ?: continue
                    when (d) {
                        is FunDecl -> out.putIfAbsent(d.name, Triple(f.toURI().toString(), d.pos, text))
                        is StructDecl -> out.putIfAbsent(d.name, Triple(f.toURI().toString(), d.pos, text))
                        is EnumDecl -> out.putIfAbsent(d.name, Triple(f.toURI().toString(), d.pos, text))
                        is ClassDecl -> {
                            out.putIfAbsent(d.name, Triple(f.toURI().toString(), d.pos, text))
                            for (m in d.members) out.putIfAbsent(m.name, Triple(f.toURI().toString(), m.pos, text))
                        }
                        else -> {}
                    }
                }
            }
        }
        slotOf(ext)?.symbolIndex = out
        return out
    }

    private fun processorsOf(ext: LspExtension): List<AnnotationProcessor> {
        slotOf(ext)?.processors?.let { return it }
        val dir = ext.processorsDir
        val out = if (dir != null && dir.isDirectory)
            dir.listFiles { f -> f.isFile && f.name.endsWith(".subs") }?.sortedBy { it.name }?.map { f ->
                val name = f.name.removeSuffix(".subs")
                val txt = f.readText()
                val extra = Regex("@interested\\s+([A-Za-z0-9_, ]+)").find(txt)?.groupValues?.get(1)
                    ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
                ScriptProcessor(
                    name = name,
                    interestedAnnotations = setOf(name) + extra,
                    scriptSource = txt,
                    scriptFileName = f.path,
                    stdlibDir = coreStdlibDir() ?: File("core"),
                )
            } ?: emptyList()
        else emptyList()
        slotOf(ext)?.processors = out
        return out
    }

    private fun slotOf(ext: LspExtension): Slot? = slots[ext.name]?.takeIf { it.extension === ext }
}
