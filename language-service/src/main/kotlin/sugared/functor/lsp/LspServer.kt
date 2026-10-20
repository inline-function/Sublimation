package sugared.functor.lsp

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Sublimation 语言服务（LSP 服务器）。
 *
 * 零第三方依赖：基于 stdio 的 JSON-RPC 2.0 实现（Content-Length 分帧）。
 * 只依赖编译器前端（lexer/parser/checker），经 LspAnalysis 桥接。
 *
 * 支持：
 *   - initialize / initialized
 *   - textDocument/didOpen / didChange / didClose
 *   - textDocument/publishDiagnostics (server -> client)
 *   - textDocument/hover
 *   - textDocument/definition
 *   - textDocument/completion
 *   - textDocument/documentSymbol
 *   - shutdown / exit
 *
 * 用法: java -cp sublimation.jar sugared.functor.lsp.LspServerKt
 */
object LspServer {

    /** 协议健壮性上限：header/body 超过则按畸形输入处理（防止缺 \r\n\r\n 的流让内存无限增长） */
    private const val MAX_HEADER_BYTES = 16 * 1024
    private const val MAX_BODY_BYTES = 16 * 1024 * 1024

    private val analysis = LspAnalysis()

    /** handleRequest 对未知方法返回的哨兵（区分「方法不存在」与「方法返回 null」） */
    private object UNKNOWN_METHOD

    @JvmStatic
    fun main(args: Array<String>) {
        start(System.`in`, System.out)
    }

    /** 启动服务器：监听 stdin，按 LSP Content-Length 分帧读取 JSON-RPC 消息 */
    fun start(input: InputStream, output: OutputStream) {
        // 载入内建 prelude；尽力从 cwd 的 core 标准库（仓库根 core/ 包目录，递归）补全标准库符号（找不到则跳过）
        analysis.loadPrelude(java.io.File("core"))
        val writer = LspWriter(output)
        var running = true

        while (running) {
            val contentLength = readHeader(input) ?: break
            if (contentLength <= 0) continue
            val json = readBody(input, contentLength) ?: break
            running = dispatch(json, writer)
        }
        writer.flush()
    }

    /** 读取 Content-Length 头，返回 body 长度；EOF 返回 null。
     *  按字节读：Content-Length 是 UTF-8 字节数，不能用字符读。 */
    private fun readHeader(input: InputStream): Int? {
        val raw = java.io.ByteArrayOutputStream()
        var b = input.read()
        if (b < 0) return null
        while (b >= 0) {
            raw.write(b)
            if (blankLineEnds(raw)) break
            if (raw.size() > MAX_HEADER_BYTES) {
                System.err.println("[sublimation-ls] header 超过 " + MAX_HEADER_BYTES + " 字节，按畸形输入丢弃")
                return null
            }
            b = input.read()
        }
        val text = String(raw.toByteArray(), 0, raw.size(), Charsets.UTF_8)
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.lowercase(java.util.Locale.ROOT).startsWith("content-length:")) {
                return t.substringAfter(':').trim().toIntOrNull()
            }
        }
        return null
    }

    /** header 是否以空行结束（\r\n\r\n 或 \n\n） */
    private fun blankLineEnds(raw: java.io.ByteArrayOutputStream): Boolean {
        val a = raw.toByteArray()
        val n = a.size
        if (n >= 4 && a[n - 4] == 13.toByte() && a[n - 3] == 10.toByte() &&
            a[n - 2] == 13.toByte() && a[n - 1] == 10.toByte()
        ) return true
        return n >= 2 && a[n - 2] == 10.toByte() && a[n - 1] == 10.toByte()
    }

    /** 按字节读取 body（Content-Length 是 UTF-8 字节数）。返回 null 表示畸形应断开。 */
    private fun readBody(input: InputStream, contentLength: Int): String? {
        if (contentLength > MAX_BODY_BYTES) {
            System.err.println("[sublimation-ls] body 长度 " + contentLength + " 超过上限 " + MAX_BODY_BYTES + "，断开")
            return null
        }
        val bytes = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(bytes, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    /** 从原始 JSON 文本兜底提取 id（parse 失败时用于回 -32700），保留 number/string 原类型 */
    private fun extractId(json: String): Any? {
        var i = 0
        val n = json.length
        while (i + 4 < n) {
            if (json[i] != '"') { i++; continue }
            if (json[i + 1] == 'i' && json[i + 2] == 'd' && json[i + 3] == '"') {
                var j = i + 4
                while (j < n && json[j] == ' ') j++
                if (j < n && json[j] == ':') {
                    j++
                    while (j < n && json[j] == ' ') j++
                    if (j >= n) return null
                    if (json[j] == '"') {
                        val end = json.indexOf('"', j + 1)
                        return if (end > j) json.substring(j + 1, end) else null
                    }
                    val start = j
                    while (j < n && (json[j].isDigit() || json[j] == '-')) j++
                    return json.substring(start, j).toLongOrNull()
                }
            }
            i++
        }
        return null
    }

    /** 分发消息，返回是否继续（shutdown 后仍要处理 exit） */
    private fun dispatch(json: String, writer: LspWriter): Boolean {
        // M5/N2：Json.parse 失败不杀服务器；若请求带 id 则回 -32700（Parse error），通知则静默
        val msg = try {
            Json.parse(json) as? Map<*, *>
        } catch (e: Exception) {
            val id = extractId(json)
            if (id != null) writer.writeError(id, -32700, "parse error: " + (e.message ?: "invalid JSON"))
            return true
        } ?: return true
        val method = msg["method"] as? String
        // P4：id 保留原类型（JSON-RPC 允许 string id 与 number id），回写时原样带回
        val id = msg["id"]
        val params = msg["params"] as? Map<*, *> ?: emptyMap<String, Any?>()

        if (method == null) return true  // 响应/无关消息

        if (id != null) {
            // Request：需要响应（result 为 null 时序列化为 JSON null）
            val result = try {
                handleRequest(method, params)
            } catch (e: Exception) {
                // 未知异常不杀服务器：按 JSON-RPC -32603 返回
                writer.writeError(id, -32603, "internal error: " + (e.message ?: e.javaClass.simpleName))
                return true
            }
            if (result === UNKNOWN_METHOD) {
                writer.writeError(id, -32601, "method not found: " + method)
                return true
            }
            writer.write(mapOf("jsonrpc" to "2.0", "id" to id, "result" to result))
            return true
        }

        // Notification：不响应；处理异常也不应杀服务器
        return try {
            handleNotification(method, params, writer)
        } catch (e: Exception) {
            true
        }
    }

    // ───────── 请求处理 ─────────

    /** 处理请求；结果 null 序列化为 JSON null（如 shutdown、无匹配 hover/definition 等） */
    private fun handleRequest(method: String, params: Map<*, *>): Any? {
        return when (method) {
            "initialize" -> mapOf(
                "capabilities" to mapOf(
                    "positionEncoding" to "utf-16",
                    "textDocumentSync" to mapOf("openClose" to true, "change" to 1),
                    "hoverProvider" to true,
                    "definitionProvider" to true,
                    "completionProvider" to mapOf(
                        "triggerCharacters" to listOf(".", ":", "@", "\\"),   // @ 触发注解补全
                        "resolveProvider" to false,
                    ),
                    "documentSymbolProvider" to true,
                    "referencesProvider" to true,
                    "documentHighlightProvider" to true,
                    "signatureHelpProvider" to mapOf(
                        "triggerCharacters" to listOf("(", ","),
                    ),
                    "foldingRangeProvider" to true,
                    "renameProvider" to true,
                    "workspaceSymbolProvider" to true,
                    "typeDefinitionProvider" to true,
                    "semanticTokensProvider" to mapOf(
                        "legend" to mapOf(
                            "tokenTypes" to LspAnalysis.SEMANTIC_TOKEN_TYPES,
                            "tokenModifiers" to LspAnalysis.SEMANTIC_TOKEN_MODIFIERS,
                        ),
                        "full" to true,
                        "range" to true,
                    ),
                    "documentFormattingProvider" to true,
                    "codeActionProvider" to true,
                    "implementationProvider" to true,
                    "inlayHintProvider" to true,
                    // onTypeFormatting：按 Enter 智能缩进（客户端需开启 formatOnType）
                    "documentOnTypeFormattingProvider" to mapOf(
                        "firstTriggerCharacter" to "\n",
                        "moreTriggerCharacter" to listOf("}", ")")
                    ),
                ),
                "serverInfo" to mapOf("name" to "sublimation-ls", "version" to "0.1.0"),
            )

            "shutdown" -> null

            "textDocument/hover" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val hover = analysis.hoverFor(uri, position) ?: return null
                val content = hover.contents as? MarkupContent ?: return null
                mapOf("contents" to mapOf("kind" to content.kind, "value" to content.value))
            }

            "textDocument/definition" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val location = analysis.definitionFor(uri, position) ?: return null
                mapOf("uri" to location.uri, "range" to rangeToMap(location.range))
            }

            "textDocument/typeDefinition" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val location = analysis.typeDefinitionFor(uri, position) ?: return emptyList<Map<String, Any?>>()
                listOf(mapOf("uri" to location.uri, "range" to rangeToMap(location.range)))
            }

            "textDocument/implementation" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return emptyList<Map<String, Any?>>()
                analysis.implementationsFor(uri, position).map {
                    mapOf("uri" to it.uri, "range" to rangeToMap(it.range))
                }
            }

            "textDocument/semanticTokens/full" -> {
                val uri = (params as? Map<*, *>)?.get("textDocument") as? Map<*, *>
                val textUri = uri?.get("uri") as? String ?: return null
                val data = analysis.semanticTokens(textUri) ?: return mapOf("data" to emptyList<Int>())
                mapOf("data" to data)
            }

            "textDocument/semanticTokens/range" -> {
                val td = params["textDocument"] as? Map<*, *>
                val textUri = td?.get("uri") as? String ?: return null
                val rangeParam = params["range"] as? Map<*, *> ?: return null
                val start = rangeParam["start"] as? Map<*, *> ?: return null
                val end = rangeParam["end"] as? Map<*, *> ?: return null
                val startLine = (start["line"] as? Number)?.toInt() ?: 0
                val startChar = (start["character"] as? Number)?.toInt() ?: 0
                val endLine = (end["line"] as? Number)?.toInt() ?: 0
                val endChar = (end["character"] as? Number)?.toInt() ?: 0
                val data = analysis.semanticTokensRange(
                    textUri, Position(startLine, startChar), Position(endLine, endChar)
                ) ?: return mapOf("data" to emptyList<Int>())
                mapOf("data" to data)
            }

            "textDocument/formatting" -> {
                val td = params["textDocument"] as? Map<*, *> ?: return null
                val textUri = td["uri"] as? String ?: return null
                val edits = analysis.formatDocument(textUri) ?: return null
                edits.map { editToMap(it) }
            }

            "textDocument/onTypeFormatting" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val ch = params["ch"] as? String ?: return null
                val edits = analysis.onTypeFormattingEdits(uri, position, ch)
                edits.map { editToMap(it) }
            }

            "textDocument/codeAction" -> {
                val td = params["textDocument"] as? Map<*, *> ?: return emptyList<Map<String, Any?>>()
                val textUri = td["uri"] as? String ?: return emptyList<Map<String, Any?>>()
                val range = (params["range"] as? Map<*, *>)?.let { r ->
                    val s = r["start"] as? Map<*, *>
                    val e = r["end"] as? Map<*, *>
                    if (s != null && e != null) Range(
                        Position((s["line"] as? Number)?.toInt() ?: 0, (s["character"] as? Number)?.toInt() ?: 0),
                        Position((e["line"] as? Number)?.toInt() ?: 0, (e["character"] as? Number)?.toInt() ?: 0),
                    ) else null
                }
                analysis.codeActionsFor(textUri, range).map { codeActionToMap(it) }
            }

            "textDocument/inlayHint" -> {
                val td = params["textDocument"] as? Map<*, *> ?: return emptyList<Map<String, Any?>>()
                val textUri = td["uri"] as? String ?: return emptyList<Map<String, Any?>>()
                analysis.inlayHints(textUri).map { inlayHintToMap(it) }
            }

            "textDocument/completion" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val list = analysis.completionFor(uri, position)
                mapOf(
                    "isIncomplete" to list.isIncomplete,
                    "items" to list.items.map { completionItemToMap(it) },
                )
            }

            "textDocument/documentSymbol" -> {
                val td = params["textDocument"] as? Map<*, *> ?: return null
                val uri = td["uri"] as? String ?: return null
                analysis.documentSymbols(uri).map { documentSymbolToMap(it) }
            }

            "textDocument/signatureHelp" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val help = analysis.signatureHelpFor(uri, position) ?: return null
                mapOf(
                    "signatures" to help.signatures.map { sig ->
                        mapOf(
                            "label" to sig.label,
                            "parameters" to sig.parameters.map { p ->
                                val pm = mutableMapOf<String, Any?>("label" to p.label)
                                if (p.documentation != null) pm["documentation"] = p.documentation
                                pm
                            },
                            "documentation" to (sig.documentation ?: ""),
                        )
                    },
                    "activeSignature" to help.activeSignature,
                    "activeParameter" to help.activeParameter,
                )
            }

            "textDocument/foldingRange" -> {
                val td = params["textDocument"] as? Map<*, *> ?: return emptyList<Map<String, Any?>>()
                val uri = td["uri"] as? String ?: return emptyList<Map<String, Any?>>()
                analysis.foldingRanges(uri).map { fr ->
                    val m = mutableMapOf<String, Any?>("startLine" to fr.startLine, "endLine" to fr.endLine)
                    if (fr.kind != null) m["kind"] = fr.kind
                    m
                }
            }

            "textDocument/references" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return emptyList<Map<String, Any?>>()
                analysis.referencesFor(uri, position).map { loc ->
                    mapOf("uri" to loc.uri, "range" to rangeToMap(loc.range))
                }
            }

            "textDocument/documentHighlight" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return emptyList<Map<String, Any?>>()
                analysis.highlightsFor(uri, position).map { documentHighlightToMap(it) }
            }

            "textDocument/prepareRename" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val range = analysis.prepareRenameFor(uri, position) ?: return null
                rangeToMap(range)
            }

            "textDocument/rename" -> {
                val (uri, position) = textDocumentAndPosition(params) ?: return null
                val newName = params["newName"] as? String ?: return null
                val changes = analysis.renameFor(uri, position, newName) ?: return null
                mapOf("changes" to changes.mapValues { (_, edits) ->
                    edits.map { editToMap(it) }
                })
            }

            "workspace/symbol" -> {
                val query = params["query"] as? String ?: ""
                analysis.workspaceSymbols(query).map { symbolInformationToMap(it) }
            }

            else -> UNKNOWN_METHOD
        }
    }

    // ───────── 通知处理 ─────────

    /** 返回是否继续运行（exit 后返回 false） */
    private fun handleNotification(method: String, params: Map<*, *>, writer: LspWriter): Boolean {
        return when (method) {
            "initialized" -> true

            "textDocument/didOpen" -> {
                val td = params["textDocument"] as? Map<*, *>
                val uri = td?.get("uri") as? String
                val text = td?.get("text") as? String
                if (uri != null && text != null) {
                    analysis.openDocument(uri, text)
                    publishDiagnostics(uri, writer)
                }
                true
            }

            "textDocument/didChange" -> {
                val td = params["textDocument"] as? Map<*, *>
                val uri = td?.get("uri") as? String
                val changes = params["contentChanges"] as? List<*>
                val text = changes?.lastOrNull()?.let { (it as? Map<*, *>)?.get("text") as? String }
                if (uri != null && text != null) {
                    analysis.updateDocument(uri, text)
                    publishDiagnostics(uri, writer)
                }
                true
            }

            "textDocument/didClose" -> {
                val td = params["textDocument"] as? Map<*, *>
                val uri = td?.get("uri") as? String
                if (uri != null) {
                    analysis.closeDocument(uri)
                    writer.write(mapOf(
                        "jsonrpc" to "2.0",
                        "method" to "textDocument/publishDiagnostics",
                        "params" to mapOf("uri" to uri, "diagnostics" to emptyList<Any>()),
                    ))
                }
                true
            }

            "textDocument/didSave" -> {
                // 全量同步下 didChange 已处理；此处兜底：若附带了 text 就更新，否则仅重发诊断
                val td = params["textDocument"] as? Map<*, *>
                val uri = td?.get("uri") as? String
                val text = td?.get("text") as? String
                if (uri != null) {
                    if (text != null) analysis.updateDocument(uri, text)
                    publishDiagnostics(uri, writer)
                }
                true
            }

            "exit" -> false

            else -> true
        }
    }

    private fun publishDiagnostics(uri: String, writer: LspWriter) {
        val diags = analysis.diagnosticsFor(uri)
        writer.write(mapOf(
            "jsonrpc" to "2.0",
            "method" to "textDocument/publishDiagnostics",
            "params" to mapOf(
                "uri" to uri,
                "diagnostics" to diags.map { diagnosticToMap(it) },
            ),
        ))
    }

    // ───────── 参数/结构转换辅助 ─────────

    private fun textDocumentAndPosition(params: Map<*, *>): Pair<String, Position>? {
        val td = params["textDocument"] as? Map<*, *> ?: return null
        val pos = params["position"] as? Map<*, *> ?: return null
        val uri = td["uri"] as? String ?: return null
        val line = (pos["line"] as? Number)?.toInt() ?: 0
        val character = (pos["character"] as? Number)?.toInt() ?: 0
        return uri to Position(line, character)
    }

    private fun rangeToMap(r: Range): Map<String, Map<String, Int>> = mapOf(
        "start" to mapOf("line" to r.start.line, "character" to r.start.character),
        "end" to mapOf("line" to r.end.line, "character" to r.end.character),
    )

    private fun diagnosticToMap(d: Diagnostic): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "range" to rangeToMap(d.range),
            "severity" to (d.severity?.value ?: 1),
            "message" to d.message,
        )
        if (d.code != null) m["code"] = d.code
        if (d.source != null) m["source"] = d.source
        if (d.documentation != null) m["documentation"] = d.documentation
        return m
    }

    private fun completionItemToMap(item: CompletionItem): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>("label" to item.label)
        if (item.kind != null) m["kind"] = item.kind.value
        if (item.detail != null) m["detail"] = item.detail
        if (item.documentation != null) m["documentation"] = item.documentation
        if (item.insertText != null) m["insertText"] = item.insertText
        if (item.sortText != null) m["sortText"] = item.sortText
        if (item.filterText != null) m["filterText"] = item.filterText
        if (item.textEdit != null) m["textEdit"] = editToMap(item.textEdit)
        if (item.insertTextFormat != null) m["insertTextFormat"] = item.insertTextFormat
        return m
    }

    private fun documentSymbolToMap(s: DocumentSymbol): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "name" to s.name,
            "kind" to s.kind.value,
            "range" to rangeToMap(s.range),
            "selectionRange" to rangeToMap(s.selectionRange),
        )
        if (s.detail != null) m["detail"] = s.detail
        if (s.children != null) m["children"] = s.children.map { documentSymbolToMap(it) }
        return m
    }

    private fun documentHighlightToMap(h: DocumentHighlight): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "range" to rangeToMap(h.range),
        )
        if (h.kind != null) m["kind"] = h.kind.value
        return m
    }

    private fun editToMap(e: TextEdit): Map<String, Any?> = mapOf(
        "range" to rangeToMap(e.range),
        "newText" to e.newText,
    )

    private fun codeActionToMap(ca: CodeAction): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>("title" to ca.title, "kind" to ca.kind)
        val edit = ca.edit
        if (edit != null) {
            m["edit"] = mapOf(
                "changes" to edit.changes.mapValues { (_, edits) -> edits.map { editToMap(it) } },
            )
        }
        val cmd = ca.command
        if (cmd != null) {
            m["command"] = mapOf(
                "title" to cmd.title,
                "command" to cmd.command,
                "arguments" to cmd.arguments,
            )
        }
        return m
    }

    private fun inlayHintToMap(h: InlayHint): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "position" to mapOf("line" to h.position.line, "character" to h.position.character),
            "label" to h.label,
        )
        if (h.kind != null) m["kind"] = h.kind
        if (h.paddingRight != null) m["paddingRight"] = h.paddingRight
        return m
    }

    private fun symbolInformationToMap(s: SymbolInformation): Map<String, Any?> {
        val m = mutableMapOf<String, Any?>(
            "name" to s.name,
            "kind" to s.kind.value,
            "location" to mapOf("uri" to s.location.uri, "range" to rangeToMap(s.location.range)),
        )
        if (s.containerName != null) m["containerName"] = s.containerName
        return m
    }
}

/**
 * LSP 分帧写器：Content-Length 头 + 空行 + JSON body。
 */
class LspWriter(private val output: OutputStream) {
    fun write(message: Map<String, Any?>) {
        val json = Json.stringify(message)
        val bytes = json.toByteArray(Charsets.UTF_8)
        val header = "Content-Length: " + bytes.size + "\r\n\r\n"
        output.write(header.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        flush()
    }

    /** JSON-RPC 错误响应（请求失败） */
    fun writeError(id: Any?, code: Int, message: String) {
        write(mapOf(
            "jsonrpc" to "2.0",
            "id" to id,
            "error" to mapOf("code" to code, "message" to message),
        ))
    }

    fun flush() {
        try { output.flush() } catch (e: IOException) { /* 忽略 */ }
    }
}

/**
 * 极简 JSON 解析/序列化（零第三方依赖）。
 * 仅支持 LSP 消息所需的子集：对象/数组/字符串/数值/布尔/null。
 */
object Json {

    // ───────── 序列化 ─────────

    fun stringify(value: Any?): String = buildString { writeValue(value) }

    private fun StringBuilder.writeValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> writeString(value)
            is Boolean -> append(value.toString())
            is Number -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) append(',')
                    first = false
                    writeString(k.toString())
                    append(':')
                    writeValue(v)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                var first = true
                for (item in value) {
                    if (!first) append(',')
                    first = false
                    writeValue(item)
                }
                append(']')
            }
            else -> writeString(value.toString())
        }
    }

    private fun StringBuilder.writeString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    // ───────── 解析 ─────────

    fun parse(json: String): Any? {
        val p = Parser(json)
        val v = p.parseValue()
        p.skipWhitespace()
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWhitespace()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                // M4：校验完整字面量（避免 "text" 被误读为 true 后 i 错跳）
                't' -> { if (s.startsWith("true", i)) { i += 4; true } else error("invalid JSON literal at $i") }
                'f' -> { if (s.startsWith("false", i)) { i += 5; false } else error("invalid JSON literal at $i") }
                'n' -> { if (s.startsWith("null", i)) { i += 4; null } else error("invalid JSON literal at $i") }
                else -> parseNumber()
            }
        }

        private fun parseObject(): Map<String, Any?> {
            val result = mutableMapOf<String, Any?>()
            i++  // skip '{'
            skipWhitespace()
            if (i < s.length && s[i] == '}') { i++; return result }
            while (i < s.length) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                if (i < s.length && s[i] == ':') i++
                skipWhitespace()
                result[key] = parseValue()
                skipWhitespace()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == '}' -> { i++; return result }
                }
            }
            return result
        }

        private fun parseArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            i++  // skip '['
            skipWhitespace()
            if (i < s.length && s[i] == ']') { i++; return result }
            while (i < s.length) {
                skipWhitespace()
                result += parseValue()
                skipWhitespace()
                when {
                    i < s.length && s[i] == ',' -> i++
                    i < s.length && s[i] == ']' -> { i++; return result }
                }
            }
            return result
        }

        private fun parseString(): String {
            i++  // skip opening '"'
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { i++; return sb.toString() }
                if (c == '\\' && i + 1 < s.length) {
                    i++
                    when (s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 4 < s.length) {
                                sb.append(s.substring(i + 1, i + 5).toInt(16).toChar())
                                i += 4
                            }
                        }
                        else -> sb.append(s[i])
                    }
                    i++
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        private fun parseNumber(): Number {
            val start = i
            // P8：JSON 只允许 ASCII 数字（isDigit 会误纳全角/阿拉伯数字）
            while (i < s.length && (s[i] in '0'..'9' || s[i] == '-' || s[i] == '.' || s[i] == '+' || s[i] == 'e' || s[i] == 'E')) i++
            val text = s.substring(start, i)
            return if (text.contains('.')) text.toDouble() else text.toLong()
        }

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }
}