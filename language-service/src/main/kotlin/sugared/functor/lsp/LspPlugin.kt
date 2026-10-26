package sugared.functor.lsp

import java.io.File

/**
 * 语言服务插件框架（V1，在 LspExtension 扩展点上演进）。
 *
 * 一个「插件」= 一个目录，内含清单文件 plugin.json：
 *
 * ```
 * {
 *   "id": "android",                 // 唯一标识（= 扩展名，注册/注销键）
 *   "version": "1.0.0",              // 插件独立 semver（stdlib/procs 内容变更即升）
 *   "displayName": "Sublimation for Android",
 *   "author": "SfA",                 // 可选展示字段
 *   "description": "安卓平台契约",     // 可选展示字段
 *   "minCoreVersion": "1.1",         // 要求的语言核心最低版本；宿主据此 gate 不兼容插件
 *   "stdlibDir": "stdlib",           // 相对插件目录（绝对路径亦可）；递归 .subl
 *   "processorsDir": "procs",        // 其下全部 .subs 注解处理器
 *   "enabled": true,                 // 缺省 true；宿主可注册后注销或重新注册切换
 *   "apiVersion": 1,                 // 清单 API 版本（缺省 1；超过 CURRENT_API_VERSION 拒绝）
 *   "contributes": { }               // 保留字段：V1 解析但不解释（诊断过滤等 V2 贡献点）
 * }
 * ```
 *
 * 加载与发现（LspPluginLoader）：插件根目录的每个子目录视为一个插件候选，按目录名排序
 * （结果确定）；清单缺失/非法/id 空/apiVersion 过高/minCoreVersion 不达标的目录跳过并记
 * stderr（与语言服务「容错降级」 philosophy 一致：一个坏插件不影响其它插件与服务器启动）。
 *
 * 生命周期与缓存：加载即注册为同名 LspExtension，沿用扩展注册表按 name 的槽缓存
 * （stdlib 条目/符号索引/处理器）；注销（unregisterExtension）立即丢弃。插件目录内容
 * 变更后须重新 loadPlugins 触发刷新（注册替换语义）。线程模型同 LSP：单线程 stdio 循环
 * 内访问，插件加载自身不做并发控制。
 */

/** 插件清单（plugin.json 的解析结果） */
data class LspPluginManifest(
    /** 唯一标识（非空；= 注册扩展名） */
    val id: String,
    /** 插件版本（独立 semver 字符串，缺省 "0"） */
    val version: String,
    /** 展示名（缺省 = id） */
    val displayName: String,
    /** 作者（可选） */
    val author: String?,
    /** 描述（可选） */
    val description: String?,
    /** 要求的语言核心最低版本（可选；与加载时传入的 coreVersion 比对） */
    val minCoreVersion: String?,
    /** 附加标准库目录（已按插件目录解析；null = 无贡献） */
    val stdlibDir: File?,
    /** 附加注解处理器目录（已按插件目录解析；null = 无贡献） */
    val processorsDir: File?,
    /** 是否生效（缺省 true） */
    val enabled: Boolean,
    /** 清单 API 版本（缺省 1） */
    val apiVersion: Int,
    /** 原始清单（未知键保留；V1 不解释，供未来贡献点向前兼容） */
    val raw: Map<String, Any?>,
)

/** 已加载的插件：清单 + 注册用扩展（stdlib/processors 贡献由扩展管线消费） */
data class LoadedPlugin(
    val manifest: LspPluginManifest,
    val extension: LspExtension,
)

object LspPluginLoader {
    /** 当前支持的清单 API 版本 */
    const val CURRENT_API_VERSION = 1
    private const val MANIFEST_FILE = "plugin.json"

    /**
     * 发现并加载插件根目录下的全部插件。
     *
     * @param rootDir 插件根目录（其下每个子目录一个插件候选；非目录返回空）
     * @param coreVersion 宿主的语言核心版本（如 "1.1"；null = 不做 minCoreVersion gate）
     * @return 加载结果（成功清单 + 被跳过目录及原因，供宿主展示）
     */
    fun load(rootDir: File, coreVersion: String? = null): LoadResult {
        val loaded = mutableListOf<LoadedPlugin>()
        val skipped = mutableListOf<Pair<String, String>>()
        if (!rootDir.isDirectory) return LoadResult(loaded, skipped)
        val dirs = rootDir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return LoadResult(loaded, skipped)
        for (dir in dirs) {
            val manifestFile = File(dir, MANIFEST_FILE)
            val text = runCatching { manifestFile.readText() }.getOrNull()
            if (text == null) { skipped += dir.name to "缺少 $MANIFEST_FILE"; continue }
            val map = MiniJson.parseObject(text)
            if (map == null) { skipped += dir.name to "$MANIFEST_FILE 解析失败（非法 JSON）"; continue }
            val manifest = manifestOf(dir, map, coreVersion)
            if (manifest == null) {
                skipped += dir.name to skipReason(dir, map, coreVersion)
                continue
            }
            loaded += LoadedPlugin(manifest, SimpleLspExtension(manifest.id, manifest.stdlibDir, manifest.processorsDir, manifest.enabled))
        }
        return LoadResult(loaded, skipped)
    }

    /** 加载结果 */
    data class LoadResult(
        val loaded: List<LoadedPlugin>,
        /** 被跳过的（目录名 → 原因） */
        val skipped: List<Pair<String, String>>,
    )

    private fun skipReason(dir: File, map: Map<String, Any?>, coreVersion: String?): String {
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() }
        if (id == null) return "清单缺非空 id"
        val api = (map["apiVersion"] as? Number)?.toInt() ?: 1
        if (api > CURRENT_API_VERSION) return "apiVersion=$api 高于当前支持的 $CURRENT_API_VERSION"
        val minCore = map["minCoreVersion"] as? String
        if (minCore != null && coreVersion != null && compareVersions(coreVersion, minCore) < 0)
            return "要求核心 >= $minCore，当前 $coreVersion（minCoreVersion 不达标）"
        return "清单字段非法"
    }

    private fun manifestOf(dir: File, map: Map<String, Any?>, coreVersion: String?): LspPluginManifest? {
        val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val api = (map["apiVersion"] as? Number)?.toInt() ?: 1
        if (api > CURRENT_API_VERSION) return null
        val minCore = map["minCoreVersion"] as? String
        if (minCore != null && coreVersion != null && compareVersions(coreVersion, minCore) < 0) return null
        return LspPluginManifest(
            id = id,
            version = (map["version"] as? String) ?: "0",
            displayName = (map["displayName"] as? String) ?: id,
            author = map["author"] as? String,
            description = map["description"] as? String,
            minCoreVersion = minCore,
            stdlibDir = resolvePath(dir, map["stdlibDir"]),
            processorsDir = resolvePath(dir, map["processorsDir"]),
            enabled = (map["enabled"] as? Boolean) ?: true,
            apiVersion = api,
            raw = map,
        )
    }

    /** 相对路径按插件目录解析；绝对路径原样；空/非字符串 → null */
    private fun resolvePath(dir: File, value: Any?): File? {
        val s = value as? String ?: return null
        if (s.isBlank()) return null
        val f = File(s)
        return if (f.isAbsolute()) f else File(dir, s)
    }

    /**
     * 点分数字版本比较（"1.1-SNAPSHOT" → [1, 1]）：逐段比较，短的前导补零。
     * 无法解析的段按 0 计；返回负数/0/正数表示 a 小于/等于/大于 b。
     */
    fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.takeWhile { c -> c.isDigit() }.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}

/**
 * 极简 JSON 解析（插件清单用；零依赖）。支持对象/数组/字符串/布尔/数字/null，
 * 转义覆盖 \" \\ \/ \n \t \r \b \f \uXXXX；非法输入整体返回 null（清单被跳过）。
 */
internal object MiniJson {
    private class ParseError : RuntimeException()
    private class Parser(val text: String) {
        var pos = 0
        fun peek(): Char = if (pos < text.length) text[pos] else throw ParseError()
        fun next(): Char = peek().also { pos++ }
        fun skipWs() { while (pos < text.length && text[pos].isWhitespace()) pos++ }
        fun expect(c: Char) { if (next() != c) throw ParseError() }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                val c = next()
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = next()
                        sb.append(when (e) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'b' -> '\u0008'
                            'f' -> '\u000C'
                            'u' -> {
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                hex.toIntOrNull(16)?.toChar() ?: throw ParseError()
                            }
                            else -> e
                        })
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun parseValue(): Any? = when (val c = peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { repeat(4) { next() }; true }
            'f' -> { repeat(5) { next() }; false }
            'n' -> { repeat(4) { next() }; null }
            else -> {
                if (c.isDigit() || c == '-' || c == '+') parseNumber() else throw ParseError()
            }
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWs()
            val out = LinkedHashMap<String, Any?>()
            if (peek() == '}') { next(); return out }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                skipWs()
                out[key] = parseValue()
                skipWs()
                when (next()) {
                    '}' -> return out
                    ',' -> skipWs()
                    else -> throw ParseError()
                }
            }
        }

        fun parseArray(): List<Any?> {
            expect('[')
            skipWs()
            val out = mutableListOf<Any?>()
            if (peek() == ']') { next(); return out }
            while (true) {
                skipWs()
                out += parseValue()
                skipWs()
                when (next()) {
                    ']' -> return out
                    ',' -> skipWs()
                    else -> throw ParseError()
                }
            }
        }

        fun parseNumber(): Number {
            val start = pos
            while (pos < text.length) {
                val c = text[pos]
                if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') pos++ else break
            }
            val s = text.substring(start, pos)
            return s.toIntOrNull() ?: s.toDoubleOrNull() ?: throw ParseError()
        }
    }

    /** 解析顶层 JSON 对象；非法输入返回 null */
    fun parseObject(text: String): Map<String, Any?>? = try {
        val p = Parser(text)
        p.skipWs()
        p.parseObject()
    } catch (e: ParseError) {
        null
    }
}