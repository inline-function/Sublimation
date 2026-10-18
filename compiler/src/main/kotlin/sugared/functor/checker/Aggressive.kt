package sugared.functor.checker

import sugared.functor.ast.Expr
import sugared.functor.ast.NameRef
import java.util.EnumSet

/**
 * 激进模式（idea doc/激进模式.md）——更彻底的搜索，不是更宽松的判定。
 *
 * 本文件承载「可观测性」与「三维池」两块：
 *  - [ProverSession]：进度（stderr 节流）、中断（Ctrl-C → 诊断报告 + 130）、正向记忆化、义务计数
 *  - [KeyPool]：表达式 / 类型 / 命题 三维关键池（§4），及 ∀ 实例化的按维度分派（§4.6）
 *
 * **健全性底线（§3.1）**：此处的一切只做"搜得更深更广"——不猜、不近似、不接受未证前提。
 * 池的守门人是"有约束来源"（§3.3）：候选只来自程序里实际出现过的项/类型/命题，
 * 超出的构造一律不做（无约束的凭空生成是永动机）。
 */

// ==================== 会话：进度 / 中断 / 记忆化 / 报告（§7、§8） ====================

/** 中断异常：Ctrl-C 时由 [ProverSession.checkAbort] 抛出，一路打到 CLI（checker 内无吞异常） */
class ProverInterrupted : RuntimeException("激进证明被用户中断")

/**
 * 激进证明会话。同一 Checker 一个；普通模式不创建（行为完全不变，§11 其四）。
 *
 * - 步进：饱和每轮 / 证明每目标各记一步，节流打 stderr（默认 5 秒或 100 步，§7.4）
 * - 义务：「证明义务」= 顶层 proveOrDeep 调用（前件/下文/∴/穷尽性……）；递归展开不计（§7.3）
 * - 记忆化：只记**已证**（失败可能只是深度不够，迭代加深后或可证——记失败会误杀）
 */
class ProverSession(
    val verbose: Boolean = false,
    val intervalMs: Long = 5_000L,
    val dumpPath: String? = null,
) {
    /** 池上限（§4.7；用户拍板 §12-1/2） */
    val maxExprs = 500
    val maxTypes = 200
    val maxProps = 200
    /** ∀ 实例化组合上限（每条 ∀）：只截搜索不猜——健全性不受影响 */
    val maxCombos = 4096

    @Volatile
    var aborted = false
        private set

    private var steps = 0L
    private var lastPrintSteps = 0L
    private var lastPrintMs = System.currentTimeMillis()

    /** 义务计数（深度 0 的 proveOrDeep 调用） */
    private var oblDepth = 0
    internal var pending = 0
        private set
    internal var proved = 0
        private set

    /** 当前目标栈（栈顶 = 当前义务），报告「当前目标」用 */
    private val goalStack = ArrayDeque<Prop>()
    /** 当前义务的 Γ（报告「当前 Γ」用） */
    private val gammaStack = ArrayDeque<List<Prop>>()

    /** 最近尝试的定理名（报告 §7.2 用，容量 8） */
    internal val recentTheorems = ArrayDeque<String>()

    @Volatile
    internal var poolSizes = Triple(0, 0, 0)

    /** 预算外注入的类型池（Checker 收集的结构体/枚举/类型类名，经 typeToExpr） */
    @Volatile
    internal var typeHints: List<Expr> = emptyList()

    /** 预算内由 proveOrDeep 预建、给 closure 复用的池（一次性，防跨义务串用） */
    @Volatile
    internal var prebuiltPool: KeyPool? = null

    /** 正向记忆化：facts↦goal 渲染键（只存已证，见类注释） */
    internal val memo = HashSet<String>()

    internal fun enter(goal: Prop, gamma: List<Prop>) {
        oblDepth++
        if (oblDepth == 1) {
            pending++
            goalStack.addLast(goal)
            gammaStack.addLast(gamma)
        }
    }

    internal fun exit(proved0: Boolean) {
        if (oblDepth == 1) {
            if (proved0) proved++
            goalStack.removeLast()
            gammaStack.removeLast()
        }
        if (oblDepth > 0) oblDepth--
    }

    /** 记一次步；逢节流阀（时间或步数，任一触发）打一行进度到 stderr（§7.2） */
    internal fun step(pool: Triple<Int, Int, Int>) {
        poolSizes = pool
        steps++
        val now = System.currentTimeMillis()
        if (verbose || steps - lastPrintSteps >= 100 || now - lastPrintMs >= intervalMs) {
            lastPrintSteps = steps
            lastPrintMs = now
            System.err.println(progressLine())
        }
    }

    internal fun noteTheorem(name: String) {
        if (recentTheorems.size >= 8) recentTheorems.removeFirst()
        recentTheorems.addLast(name)
    }

    /** §7.2 进度格式 */
    internal fun progressLine(): String {
        val sb = StringBuilder("[激进证明] 已证 " + proved + " / 待证 " + pending + " | 搜索中")
        val g = goalStack.lastOrNull()
        if (g != null) sb.append("\u000A  当前目标：").append(PropLogic.render(g))
        val ga = gammaStack.lastOrNull()
        if (ga != null && ga.isNotEmpty()) {
            sb.append("\u000A  当前 Γ 相关事实：")
                .append(ga.take(4).joinToString("， ") { PropLogic.render(it) })
                .append("（共 ").append(ga.size).append(" 项）")
        }
        if (recentTheorems.isNotEmpty())
            sb.append("\u000A  最近尝试的定理：").append(recentTheorems.joinToString(", "))
        sb.append("\u000A  池规模：表达式 " + poolSizes.first + " | 类型 " + poolSizes.second + " | 命题 " + poolSizes.third + "（步 " + steps + "）")
        return sb.toString()
    }

    /** 中断：置位即可——搜索循环在下一个 [checkAbort] 抛 [ProverInterrupted] 快速退场 */
    fun abort() { aborted = true }

    internal fun checkAbort() {
        if (aborted) throw ProverInterrupted()
    }

    /** §8.2 诊断报告（卡住目标 / 已证义务 / 搜索统计 / issue 模板） */
    fun writeReport(path: String) {
        val sb = StringBuilder()
        sb.appendLine("# 激进模式诊断报告")
        sb.appendLine()
        sb.appendLine("> 生成于 " + java.time.LocalTime.now() + "；健全性底线未破——证不出的目标如实列为未证。")
        sb.appendLine()
        sb.appendLine("## 一、卡住的目标")
        val g = goalStack.lastOrNull()
        if (g == null) {
            sb.appendLine("- （无——中断时不在某个证明义务上）")
        } else {
            sb.appendLine("- 目标命题：" + PropLogic.render(g))
            val ga = gammaStack.lastOrNull()
            if (ga != null) {
                sb.appendLine("- 当前 Γ（完整）：")
                ga.forEach { sb.appendLine("  - " + PropLogic.render(it)) }
            }
        }
        sb.appendLine()
        sb.appendLine("## 二、证明义务")
        sb.appendLine("- 已证：" + proved + " / 总计 " + pending)
        sb.appendLine()
        sb.appendLine("## 三、搜索统计")
        sb.appendLine("- 总步数：" + steps)
        sb.appendLine("- 三维池规模：表达式 " + poolSizes.first + " | 类型 " + poolSizes.second + " | 命题 " + poolSizes.third)
        sb.appendLine("- 最近尝试的定理：" + if (recentTheorems.isEmpty()) "（无）" else recentTheorems.joinToString(", "))
        sb.appendLine()
        sb.appendLine("## 四、提交 issue 的模板")
        sb.appendLine("「现象」激进模式下编译被中断；附本报告。")
        sb.appendLine("「复现」编译命令 + 最小模块（含全部 ∴ / ∵ / 前后件）。")
        sb.appendLine("「期望」哪条命题您认为应该证出？为什么？")
        try {
            java.io.File(path).writeText(sb.toString())
        } catch (_: Throwable) { /* 报告写失败不阻断主流程 */ }
    }
}

/** 活跃激进会话的进程级引用——CLI 的 Ctrl-C 钩子经它中断 */
object AggressiveRuntime {
    @Volatile
    var active: ProverSession? = null
}

// ==================== 三维关键池（§4） ====================

internal enum class PoolDim { EXPR, TYPE, PROP }

/**
 * 三维关键池（§4.1）：
 *  - [exprs] 代码 AST 子项、Γ ground 项（与普通模式 keyTerms 同源，同样固定不复收集）
 *  - [types] 代码里出现的类型（':' 事实的右端 T + 符号表类型名提示）
 *  - [props] Γ / 目标里出现的一切子命题（排中律等命题定理的实例化候选）
 *
 * 池内一切候选**都有约束来源**（§3.3）——不枚举名义类型、不枚举命题空间（§4.5）。
 */
internal class KeyPool(
    val exprs: List<Expr>,
    val types: List<Expr>,
    val props: List<Prop>,
) {
    fun sizes() = Triple(exprs.size, types.size, props.size)
}

/** 收集三维池：facts=Γ，goals=目标（目标只作候选来源，不作事实——否则目标平凡可证） */
internal fun buildKeyPool(facts: List<Prop>, goals: List<Prop>, typeHints: List<Expr>, session: ProverSession): KeyPool {
    val exprs = (PropLogic.groundTerms(facts) + PropLogic.groundTerms(goals))
        .distinctBy { PropLogic.termTag(it) }
    val types = (facts.mapNotNull { p ->
        if (p is PAtom && p.head == ":" && p.terms.size == 2) p.terms[1] else null
    } + typeHints).distinct()
    val propSet = LinkedHashSet<Prop>()
    fun collect(p: Prop) {
        when (p) {
            is PAtom -> propSet.add(p)
            is PNot -> { propSet.add(p); collect(p.p) }
            is PAnd -> { propSet.add(p); collect(p.a); collect(p.b) }
            is POr -> { propSet.add(p); collect(p.a); collect(p.b) }
            is PImp -> { propSet.add(p); collect(p.a); collect(p.b) }
            is PIff -> { propSet.add(p); collect(p.a); collect(p.b) }
            else -> {}
        }
    }
    facts.forEach { collect(it) }
    goals.forEach { collect(it) }
    // 上限（§4.7）：超限时保留与目标重叠度高者——按"是否目标的子命题"稳定排序后截断
    val goalProps = HashSet(propsOf(goals))
    val keepProps = propSet.toList().sortedByDescending { it in goalProps }.take(session.maxProps)
    return KeyPool(exprs.take(session.maxExprs), types.take(session.maxTypes), keepProps)
}

private fun propsOf(ps: List<Prop>): List<Prop> = ps.flatMap { p ->
    val out = ArrayList<Prop>()
    fun walk(q: Prop) {
        out.add(q)
        when (q) {
            is PNot -> walk(q.p)
            is PAnd -> { walk(q.a); walk(q.b) }
            is POr -> { walk(q.a); walk(q.b) }
            is PImp -> { walk(q.a); walk(q.b) }
            is PIff -> { walk(q.a); walk(q.b) }
            else -> {}
        }
    }
    walk(p)
    out
}

/**
 * 绑定变元的维度分派（§4.6）：名字在体里出现的位置决定去哪个池取候选。
 *  - 裸原子 p（0 元）→ 命题池（排中律的 P）
 *  - ':' 事实的右端 T → 类型池（∀T 的 T）
 *  - 其余项位置 → 表达式池（∀x 的 x）
 */
internal fun dimsOf(name: String, body: Prop): Set<PoolDim> {
    val out = EnumSet.noneOf(PoolDim::class.java)
    fun walk(p: Prop) {
        when (p) {
            is PAtom -> {
                if (p.head == name && p.terms.isEmpty()) out.add(PoolDim.PROP)
                if (p.head == ":" && p.terms.size == 2 && p.terms[1] is NameRef &&
                    (p.terms[1] as NameRef).name == name) out.add(PoolDim.TYPE)
                if (p.terms.any { it is NameRef && it.name == name }) out.add(PoolDim.EXPR)
            }
            is PNot -> walk(p.p)
            is PAnd -> { walk(p.a); walk(p.b) }
            is POr -> { walk(p.a); walk(p.b) }
            is PImp -> { walk(p.a); walk(p.b) }
            is PIff -> { walk(p.a); walk(p.b) }
            is PForall -> if (name !in p.names) walk(p.body)
            is PExists -> if (name !in p.names) walk(p.body)
            is PExists1 -> if (name !in p.names) walk(p.body)
            else -> {}
        }
    }
    walk(body)
    if (out.isEmpty()) out.add(PoolDim.EXPR)
    return out
}

/** 一条 ∀ 的实例化组合：按维度取候选的笛卡尔积，受 [ProverSession.maxCombos] 截断 */
internal fun instCombos(
    fa: PForall, pool: KeyPool, session: ProverSession,
): List<Pair<Map<String, Expr>, Map<String, Prop>>> {
    var combos: List<Pair<Map<String, Expr>, Map<String, Prop>>> =
        listOf(emptyMap<String, Expr>() to emptyMap<String, Prop>())
    for (n in fa.names) {
        val dims = dimsOf(n, fa.body)
        val cands: List<Pair<Expr?, Prop?>> = when {
            PoolDim.PROP in dims -> pool.props.map { null to it }
            PoolDim.TYPE in dims -> pool.types.map { it to null }
            else -> pool.exprs.map { it to null }
        }
        if (cands.isEmpty()) return emptyList()
        val acc = ArrayList<Pair<Map<String, Expr>, Map<String, Prop>>>()
        for (comb in combos) {
            if (acc.size >= session.maxCombos) break
            for (c in cands) {
                if (acc.size >= session.maxCombos) break
                val e2 = if (c.first != null) comb.first + (n to c.first!!) else comb.first
                val p2 = if (c.second != null) comb.second + (n to c.second!!) else comb.second
                acc.add(e2 to p2)
            }
        }
        combos = acc
    }
    return combos
}
