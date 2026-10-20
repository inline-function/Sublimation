package sugared.functor.lsp

/**
 * LSP 协议类型定义 —— JSON-RPC 2.0 基础 + LSP 核心消息。
 * 零第三方依赖：直接手写轻量序列化（Kotlin data class → JSON 字符串）。
 *
 * 参考：LSP 3.17 规范子集。本文件只定义 Sublimation 语言服务所需的类型。
 */

// ─────────── LSP 基本类型 ───────────

data class Position(
    val line: Int,      // 0-based
    val character: Int, // 0-based
)

data class Range(
    val start: Position,
    val end: Position,
)

data class Location(
    val uri: String,
    val range: Range,
)

// ─────────── 诊断 ───────────

enum class DiagnosticSeverity(val value: Int) {
    Error(1), Warning(2), Information(3), Hint(4);
}

data class Diagnostic(
    val range: Range,
    val severity: DiagnosticSeverity? = null,
    val code: String? = null,
    val source: String? = "sublimation",
    val message: String,
    val documentation: String? = null,   // 中文说明：code 对应的原因与修法，IDE 用于悬停提示
)

// ─────────── 补全 ───────────

enum class CompletionItemKind(val value: Int) {
    Text(1), Method(2), Function(3), Constructor(4), Field(5),
    Variable(6), Class(7), Interface(8), Module(9), Property(10),
    Unit(11), Value(12), Enum(13), Keyword(14), Snippet(15),
    Color(16), File(17), Reference(18), EnumMember(20), Struct(22), TypeParameter(25),
    Operator(24);
}

data class CompletionItem(
    val label: String,
    val kind: CompletionItemKind? = null,
    val detail: String? = null,         // 类型/签名等
    val documentation: String? = null,   // 文档字符串
    val insertText: String? = null,      // 若不提供则用 label
    val sortText: String? = null,
    val filterText: String? = null,
    val textEdit: TextEdit? = null,      // 显式替换范围（如 \forall -> ∀ 整段替换）
    val insertTextFormat: Int? = null,   // 1=PlainText（默认）2=Snippet：insertText 按 LSP snippet 解析（$0 最终光标、${n:占位}）
)

data class CompletionList(
    val isIncomplete: Boolean,
    val items: List<CompletionItem>,
)

// ─────────── Hover ───────────

data class Hover(
    val contents: Any,   // MarkupContent | MarkedString | MarkedString[]
    val range: Range? = null,
)

data class MarkupContent(
    val kind: String,    // "markdown" | "plaintext"
    val value: String,
)

// ─────────── 文档符号 ───────────

enum class SymbolKind(val value: Int) {
    File(1), Module(2), Namespace(3), Package(4), Class(5),
    Method(6), Property(7), Field(8), Constructor(9), Enum(10),
    Interface(11), Function(12), Variable(13), Constant(14), String(15),
    Number(16), Boolean(17), Array(18), Object(19), Key(20),
    Null(21), EnumMember(22), Struct(23), Event(24), Operator(25),
    TypeParameter(26);
}

data class DocumentSymbol(
    val name: String,
    val kind: SymbolKind,
    val range: Range,
    val selectionRange: Range,
    val detail: String? = null,
    val children: List<DocumentSymbol>? = null,
)

data class SymbolInformation(
    val name: String,
    val kind: SymbolKind,
    val location: Location,
    val containerName: String? = null,
)

// ─────────── Document Highlight ───────────

enum class DocumentHighlightKind(val value: Int) {
    Text(1), Read(2), Write(3);
}

data class DocumentHighlight(
    val range: Range,
    val kind: DocumentHighlightKind? = null,
)

// ─────────── Edits / WorkspaceEdit ───────────

data class TextEdit(
    val range: Range,
    val newText: String,
)

/** 工作区编辑（rename 与 codeAction 复用） */
data class WorkspaceEdit(
    val changes: Map<String, List<TextEdit>> = emptyMap(),
)

/** 客户端命令（让 IDE 执行内置动作，如重命名/转到定义） */
data class Command(
    val title: String,
    val command: String,
    val arguments: List<Any?> = emptyList(),
)

/** 快速修复（灯泡） */
data class CodeAction(
    val title: String,
    val kind: String = "quickfix",
    val edit: WorkspaceEdit? = null,
    val command: Command? = null,   // 触发 IDE 内置命令（重命名/转到定义等），与 edit 互斥
)

/** 内联提示（inlayHint）：类型/参数名等 */
data class InlayHint(
    val position: Position,
    val label: String,
    val kind: Int? = null,        // 1 = Type, 2 = Parameter
    val paddingRight: Boolean? = null,
)

// ─────────── Signature Help ───────────

data class ParameterInformation(
    val label: String,
    val documentation: String? = null,
)

data class SignatureInformation(
    val label: String,
    val documentation: String? = null,
    val parameters: List<ParameterInformation> = emptyList(),
)

data class SignatureHelp(
    val signatures: List<SignatureInformation>,
    val activeSignature: Int = 0,
    val activeParameter: Int = 0,
)

// ─────────── Folding Range ───────────

data class FoldingRange(
    val startLine: Int,      // 0-based
    val endLine: Int,        // 0-based（含）
    val startCharacter: Int? = null,
    val endCharacter: Int? = null,
    val kind: String? = null,   // "comment" | "imports" | "region"
)

// ─────────── 辅助：位置计算 ───────────

/** 将 1-based line:col（编译器内部格式）转为 0-based Position */
fun String.toPosition(): Position? {
    // 格式："line:col" 如 "3:12"
    val parts = this.split(":")
    if (parts.size != 2) return null
    val line = parts[0].toIntOrNull()?.minus(1) ?: return null
    val col = parts[1].toIntOrNull()?.minus(1) ?: return null
    return Position(line, col.coerceAtLeast(0))
}

/** 将 Diag 的 Severity 映射到 LSP DiagnosticSeverity */
fun sugared.functor.checker.Severity.toDiagnosticSeverity(): DiagnosticSeverity = when (this) {
    sugared.functor.checker.Severity.ERROR -> DiagnosticSeverity.Error
    sugared.functor.checker.Severity.WARN -> DiagnosticSeverity.Warning
    sugared.functor.checker.Severity.HINT -> DiagnosticSeverity.Hint
    sugared.functor.checker.Severity.SUPPLEMENT -> DiagnosticSeverity.Information
}

/** 将 Diag 的位置字符串和源码转为 LSP Range（单行，含列范围估计） */
fun diagPosToRange(pos: String, source: String): Range {
    val start = pos.toPosition() ?: return Range(Position(0, 0), Position(0, 0))
    // 尝试计算该标识符的长度
    val parts = pos.split(":")
    if (parts.size == 2) {
        val lineNum = parts[0].toIntOrNull() ?: return Range(start, Position(start.line, start.character + 1))
        val colNum = parts[1].toIntOrNull() ?: return Range(start, Position(start.line, start.character + 1))
        val srcLines = source.lines()
        if (lineNum in 1..srcLines.size) {
            val line = srcLines[lineNum - 1]
            // 从 colNum-1 开始，找到词法边界
            var end = colNum
            while (end <= line.length) {
                val idx = end - 1
                if (idx < line.length && (line[idx].isLetterOrDigit() || line[idx] == '_')) {
                    end++
                } else break
            }
            val endChar = (end - 1).coerceAtLeast(colNum)
            return Range(start, Position(start.line, endChar))
        }
    }
    return Range(start, Position(start.line, start.character + 1))
}