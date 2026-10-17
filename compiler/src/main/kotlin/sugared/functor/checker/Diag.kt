package sugared.functor.checker

/** 编译器诊断四级（用户定义）：错误/警告/提示/补充 */
enum class Severity { ERROR, WARN, HINT, SUPPLEMENT }

data class Diag(
    val severity: Severity,
    val code: String,
    val pos: String,          // "line:col"，"" 表示无位置
    val message: String,
    val modulePath: String = "",   // 产生该诊断的模块 absPath（根=""; core 标准库="core/..."）
) {
    fun render(source: String? = null): String {
        val tag = when (severity) {
            Severity.ERROR -> "[错误]"; Severity.WARN -> "[警告]"; Severity.HINT -> "[提示]"; Severity.SUPPLEMENT -> "[补充]"
        }
        val p = if (pos.isEmpty()) "" else "[$pos]"
        val base = "$tag$p $code: $message"
        // 报错增强（2026-10-11）：源码片段上下文——pos 为 "line:col" 且给出源文本时回读该行并画上箭头
        if (source == null || pos.isEmpty()) return base
        val m = Regex("(\\d+):(\\d+)").find(pos) ?: return base
        val line = m.groupValues[1].toIntOrNull() ?: return base
        val col = m.groupValues[2].toIntOrNull() ?: return base
        val srcLines = source.split("\n")
        if (line < 1 || line > srcLines.size) return base
        val text = srcLines[line - 1]
        val carets = " ".repeat((col - 1).coerceIn(0, text.length)) + "^"
        val width = srcLines.size.toString().length
        val lno = " ".repeat(width - line.toString().length) + line
        val pad = " ".repeat(width)
        return "$base\n  $lno | $text\n  $pad | $carets"
    }
}

class DiagBag {
    private val all = ArrayList<Diag>()
    /** 归属模块（Checker 设置）：用于多模块合并后按模块过滤补充级诊断 */
    var modulePath: String = ""
    fun error(code: String, pos: String, message: String) { all += Diag(Severity.ERROR, code, pos, message, modulePath) }
    fun warn(code: String, pos: String, message: String) { all += Diag(Severity.WARN, code, pos, message, modulePath) }
    fun hint(code: String, pos: String, message: String) { all += Diag(Severity.HINT, code, pos, message, modulePath) }
    fun supplement(code: String, pos: String, message: String) { all += Diag(Severity.SUPPLEMENT, code, pos, message, modulePath) }

    val diags: List<Diag> get() = all
    val hasError: Boolean get() = all.any { it.severity == Severity.ERROR }

    /** 合并（P0 多模块：各模块 Checker 的 d 汇入总袋） */
    fun absorb(other: DiagBag) { all += other.all }

    /** 多模块合并后：只保留来自指定模块的补充级诊断（其余模块的补充是内部噪声，如 core 标准库自身的推导） */
    fun dropSupplementsOutside(rootPath: String) {
        all.removeAll { it.severity == Severity.SUPPLEMENT && it.modulePath != rootPath }
    }

    /** 去重（按 code+pos+message）：类型推导定点迭代重查 lambda 时可能产生重复诊断，循环后清理保留首轮权威错误 */
    fun dedupe() {
        val seen = HashSet<Triple<String, String, String>>()
        val out = ArrayList<Diag>(all.size)
        for (x in all) if (seen.add(Triple(x.code, x.pos, x.message))) out += x
        all.clear(); all += out
    }

    /** 默认不输出补充级（用户定义）；source 非空时每条诊断附源码行片段上下文（报错增强） */
    fun report(includeSupplement: Boolean = false, source: String? = null): String =
        all.filter { includeSupplement || it.severity != Severity.SUPPLEMENT }
            .joinToString("\n") { it.render(source) }
}
