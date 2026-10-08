package sugared.functor.checker

/** 编译器诊断四级（用户定义）：错误/警告/提示/补充 */
enum class Severity { ERROR, WARN, HINT, SUPPLEMENT }

data class Diag(
    val severity: Severity,
    val code: String,
    val pos: String,          // "line:col"，"" 表示无位置
    val message: String,
) {
    fun render(): String {
        val tag = when (severity) {
            Severity.ERROR -> "[错误]"; Severity.WARN -> "[警告]"; Severity.HINT -> "[提示]"; Severity.SUPPLEMENT -> "[补充]"
        }
        val p = if (pos.isEmpty()) "" else "[$pos]"
        return "$tag$p $code: $message"
    }
}

class DiagBag {
    private val all = ArrayList<Diag>()
    fun error(code: String, pos: String, message: String) { all += Diag(Severity.ERROR, code, pos, message) }
    fun warn(code: String, pos: String, message: String) { all += Diag(Severity.WARN, code, pos, message) }
    fun hint(code: String, pos: String, message: String) { all += Diag(Severity.HINT, code, pos, message) }
    fun supplement(code: String, pos: String, message: String) { all += Diag(Severity.SUPPLEMENT, code, pos, message) }

    val diags: List<Diag> get() = all
    val hasError: Boolean get() = all.any { it.severity == Severity.ERROR }

    /** 合并（P0 多模块：各模块 Checker 的 d 汇入总袋） */
    fun absorb(other: DiagBag) { all += other.all }

    /** 默认不输出补充级（用户定义） */
    fun report(includeSupplement: Boolean = false): String =
        all.filter { includeSupplement || it.severity != Severity.SUPPLEMENT }
            .joinToString("\n") { it.render() }
}
