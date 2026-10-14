package sugared.functor.checker

import sugared.functor.ast.*
import java.lang.Math.floorDiv

/**
 * 通用线性算术判定（整数域）：证明器的**通用算术通道**，覆盖绝大部分算术证明需求。
 *
 * 把约束（前提与目标）归一化为线性形式 `Σ cᵢ·vᵢ ≤ C`（移项、常数折叠、系数提取）：
 *  - 常量折叠：`1 + 2 * 3`、`x + 1` 的常数部分一律折叠；
 *  - 单变量（含系数）：`2x ≤ 6 → x ≤ 3`、`x + 1 ≤ 5 → x ≤ 4`、`-x ≥ 5 → x ≤ -5`
 *    收紧绝对界 [lo, hi]（整数上/下取整，两端安全）；
 *  - 双变量 ±1（差分）：`x ≤ y`、`x ≤ y + 2` 建相对界边，Bellman–Ford 传播
 *    （`x ≤ y ∧ y ≤ 5 → x ≤ 5` 即传递链）；
 *  - 否定蕴含：单原子目标 G 判「Γ ∧ ¬G」不可满足——¬G 为单变量界时用界判、
 *    差分时并入图判负环；等式的双向界（`x = y` 双向差分边、`x = c` 夹定）；
 *  - 析取目标（when 穷尽）：单变量叶子域差集反证（原子否定为区间补）。
 *
 * 健全性：无法表达的约束**跳过**（少用前提只会更保守）；片段内前提矛盾（负环/界空）
 * → 蕴含一切（爆炸原则）。返回三态：true=成立；false=判定不成立；null=形态不在片段内。
 */
internal object ArithProve {

    private const val POS_INF = Long.MAX_VALUE
    private const val NEG_INF = Long.MIN_VALUE

    /** 线性项：Σ coefs·var + c（系数右操作数在 minus 中取负——单边键也要反号） */
    private data class Lin(val coefs: Map<String, Long>, val c: Long) {
        operator fun plus(o: Lin): Lin {
            val m = LinkedHashMap(coefs)
            o.coefs.forEach { (k, v) -> m[k] = (m[k] ?: 0L) + v }
            return Lin(m, c + o.c)
        }
        operator fun minus(o: Lin): Lin {
            val m = LinkedHashMap(coefs)
            o.coefs.forEach { (k, v) -> m[k] = (m[k] ?: 0L) - v }
            return Lin(m, c - o.c)
        }
        fun neg() = Lin(coefs.mapValues { -it.value }, -c)
    }

    private fun Lin.scale(k: Long): Lin = Lin(if (coefs.isEmpty()) coefs else coefs.mapValues { it.value * k }, c * k)

    internal fun proves(gamma: List<Prop>, goal: Prop): Boolean? {
        val ctx = Ctx()
        // 无法表达的前提（x = self.x、x : T 等）**跳过**：少用前提只更保守，绝不因无关事实放弃通道
        fun walk(p: Prop) {
            when (p) {
                is PAnd -> { walk(p.a); walk(p.b) }
                is PAtom -> ctx.consume(p)
                is PNot -> (p.p as? PAtom)?.let { ctx.consumeNeg(it) }
                else -> {}
            }
        }
        gamma.forEach { walk(it) }
        if (ctx.contradicted) return true
        if (bfHasNegCycle(ctx.edges, ctx)) return true
        return when (goal) {
            is PAtom -> atomImplied(goal, ctx)
            is POr -> disjRefute(goal, ctx)
            else -> null
        }
    }

    // ============ 前提收集 ============

    private class Ctx {
        val lo = HashMap<String, Long>()
        val hi = HashMap<String, Long>()
        val edges = ArrayList<LongArray>()   // [from, to, w]：to ≤ from + w
        var contradicted = false
        // 名字索引表随 Ctx 一起新建：每次 proves 建立新 Ctx，杜绝跨证明同名变量拿同一索引（P1 全局状态污染）
        val nameIndex = HashMap<String, Int>()
        val nameList = ArrayList<String>()
        fun v2idx(name: String): Int {
            if (name !in nameIndex) { nameIndex[name] = nameList.size + 1; nameList.add(name) }
            return nameIndex[name]!!
        }
    }

    private fun Ctx.consume(p: PAtom): Boolean {
        if (p.terms.size != 2) return false
        if (p.head == "in") {
            val r = rangeOf(p.terms[1]) ?: return false
            return applyRel(">=", linOf(p.terms[0]) ?: return false, constLin(r[0]), this) &&
                applyRel("<=", linOf(p.terms[0]) ?: return false, constLin(r[1]), this)
        }
        val l = linOf(p.terms[0]) ?: return false
        val r = linOf(p.terms[1]) ?: return false
        return applyRel(p.head, l, r, this)
    }

    private fun Ctx.consumeNeg(p: PAtom): Boolean {
        if (p.terms.size != 2) return false
        val l = linOf(p.terms[0]) ?: return false
        val r = linOf(p.terms[1]) ?: return false
        val rel = when (p.head) {
            "<=", "≤" -> ">"
            "<" -> ">="
            ">=", "≥" -> "<"
            ">" -> "<="
            else -> return false
        }
        return applyRel(rel, l, r, this)
    }

    /** 应用 G：L op R（整数域）。返回 false=不可表达（跳过）；矛盾置 ctx.contradicted。 */
    private fun applyRel(op: String, l: Lin, r: Lin, ctx: Ctx): Boolean {
        val d = l.minus(r)
        val (coefs, base) = when (op) {
            "<=", "≤" -> d.coefs to d.c
            "<" -> d.coefs to d.c + 1
            ">=", "≥" -> d.neg().coefs to -d.c
            ">" -> d.neg().coefs to -d.c + 1
            "=", "==" -> {
                // M7：= 拆成 <= ∧ >= 是原子约束——任一侧不可表达则整体不注入，避免半注入（<= 已写 hi/lo/edges 而 >= 失败却残留）。
                // 快照后统一判定，失败即回滚（含泛型变量索引表，防边索引错位）。
                val snapLo = HashMap(ctx.lo); val snapHi = HashMap(ctx.hi)
                val snapEdges = ArrayList(ctx.edges); val snapContra = ctx.contradicted
                val snapIdx = HashMap(ctx.nameIndex); val snapNames = ArrayList(ctx.nameList)
                val ok = applyRel("<=", l, r, ctx) && applyRel(">=", l, r, ctx)
                if (ok) return true
                ctx.lo.clear(); ctx.lo.putAll(snapLo); ctx.hi.clear(); ctx.hi.putAll(snapHi)
                ctx.edges.clear(); ctx.edges.addAll(snapEdges); ctx.contradicted = snapContra
                ctx.nameIndex.clear(); ctx.nameIndex.putAll(snapIdx); ctx.nameList.clear(); ctx.nameList.addAll(snapNames)
                return false
            }
            else -> return false
        }
        if (base == NEG_INF || base == POS_INF) return false
        val C = -base
        if (coefs.isEmpty()) {
            if (C < 0) { ctx.contradicted = true }
            return true
        }
        if (coefs.size == 1) {
            val (v, a) = coefs.entries.first()
            if (a > 0) {
                val b = floorDivSafe(C, a) ?: return false
                val cur = ctx.hi[v] ?: POS_INF
                ctx.hi[v] = minOf(cur, b)
            } else {
                val b = ceilDivSafe(C, a) ?: return false
                val cur = ctx.lo[v] ?: NEG_INF
                ctx.lo[v] = maxOf(cur, b)
            }
            val loV = ctx.lo[v] ?: NEG_INF; val hiV = ctx.hi[v] ?: POS_INF
            if (loV > hiV) ctx.contradicted = true
            return true
        }
        if (coefs.size == 2) {
            val es = coefs.entries.toList()
            val (v1, a1) = es[0]; val (v2, a2) = es[1]
            ctx.edges += when {
                a1 == 1L && a2 == -1L -> longArrayOf(ctx.v2idx(v2).toLong(), ctx.v2idx(v1).toLong(), C)
                a1 == -1L && a2 == 1L -> longArrayOf(ctx.v2idx(v1).toLong(), ctx.v2idx(v2).toLong(), C)
                else -> return false
            }
            return true
        }
        return false
    }

    // 名字索引表也必须随克隆复制：edges 里已编码 `ctx.v2idx(..)` 的索引，
    // 若克隆后索引表重排，后续 negateAndApply 新建的边会与拷贝边错位（跨变量撞号）。
    private fun cloneCtx(ctx: Ctx) = Ctx().apply {
        lo.putAll(ctx.lo); hi.putAll(ctx.hi); edges.addAll(ctx.edges)
        nameIndex.putAll(ctx.nameIndex); nameList.addAll(ctx.nameList)
    }

    private fun constLin(c: Long) = Lin(emptyMap(), c)

    private fun linOf(e: Expr): Lin? {
        return when (e) {
            is IntLit -> constLin(e.value.toLongOrNull() ?: return null)
            is NameRef -> Lin(mapOf(e.name to 1L), 0)
            is UniExpr -> if (e.op == "-" || e.op == "−") linOf(e.operand)?.neg() else null
            is BinExpr -> when (e.op) {
                "+" -> (linOf(e.left) ?: return null) + (linOf(e.right) ?: return null)
                "-", "−" -> (linOf(e.left) ?: return null) - (linOf(e.right) ?: return null)
                "*" -> {
                    val a = linOf(e.left) ?: return null; val b = linOf(e.right) ?: return null
                    val ka = constOf(a); val kb = constOf(b)
                    when {
                        ka != null -> b.scale(ka)
                        kb != null -> a.scale(kb)
                        else -> null
                    }
                }
                "/" -> {
                    val a = constOf(linOf(e.left) ?: return null) ?: return null
                    val b = constOf(linOf(e.right) ?: return null)
                    if (b == null || b == 0L) return null
                    if (a % b != 0L) return null
                    constLin(a / b)
                }
                else -> null
            }
            else -> null
        }
    }

    private fun constOf(l: Lin): Long? = if (l.coefs.isEmpty()) l.c else null

    private fun evalConst(e: Expr): Long? {
        return when (e) {
            is IntLit -> e.value.toLongOrNull()
            is UniExpr -> if (e.op == "-" || e.op == "−") -(evalConst(e.operand) ?: return null) else null
            is BinExpr -> {
                val a = evalConst(e.left) ?: return null; val b = evalConst(e.right) ?: return null
                when (e.op) {
                    "+" -> a + b; "-", "−" -> a - b
                    "*" -> a * b
                    "/" -> if (b == 0L) null else a / b
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun floorDivSafe(a: Long, b: Long): Long? {
        if (b == 0L) return null
        if (a == NEG_INF && b == -1L) return null
        return floorDiv(a, b)
    }

    private fun ceilDivSafe(a: Long, b: Long): Long? {
        if (b == 0L) return null
        if (a == NEG_INF) return null
        return -floorDiv(-a, b)
    }

    // ============ Bellman–Ford（差分图负环检测） ============

    private const val ZERO = 0

    private fun bfHasNegCycle(edges: List<LongArray>, ctx: Ctx): Boolean {
        if (edges.isEmpty()) return false
        val n = ctx.nameIndex.size + 1
        val dist = LongArray(n)
        repeat(n) {
            var changed = false
            for (e in edges) {
                val u = e[0].toInt(); val v = e[1].toInt(); val w = e[2]
                val nd = if (dist[u] == POS_INF) POS_INF else dist[u] + w
                if (nd < dist[v]) { dist[v] = nd; changed = true }
            }
            if (!changed) return false
        }
        return true
    }

    /** 绝对上界：把单变量界转为 ZERO 边（v ≤ h → ZERO→v 权 h；v ≥ l → v→ZERO 权 -l），
     *  与差分边一起从 ZERO 出发求最短路——x = y = z ≤ 7 时 upper(x) = 7（等式链传播）。 */
    private fun upperBound(ctx: Ctx): Map<String, Long> {
        val edges = ArrayList<LongArray>(ctx.edges.size + ctx.lo.size + ctx.hi.size + 1)
        edges.addAll(ctx.edges)
        ctx.hi.forEach { (v, h) -> if (h != POS_INF) edges += longArrayOf(ZERO.toLong(), ctx.v2idx(v).toLong(), h) }
        ctx.lo.forEach { (v, l) -> if (l != NEG_INF) edges += longArrayOf(ctx.v2idx(v).toLong(), ZERO.toLong(), -l) }
        if (edges.isEmpty()) return emptyMap()
        val n = ctx.nameIndex.size + 1
        val dist = LongArray(n) { POS_INF }
        dist[ZERO] = 0L
        for (k in 0 until n - 1) {   // 前提无负环（已由 bfHasNegCycle 排除），BF 收敛
            var changed = false
            for (e in edges) {
                val u = e[0].toInt(); val v = e[1].toInt(); val w = e[2]
                if (dist[u] == POS_INF) continue
                val nd = dist[u] + w
                if (nd < dist[v]) { dist[v] = nd; changed = true }
            }
            if (!changed) break
        }
        val out = HashMap<String, Long>()
        ctx.nameList.forEachIndexed { i, nm -> val d = dist[i + 1]; if (d != POS_INF) out[nm] = d }
        return out
    }

    // ============ 目标判定 ============

    private fun atomImplied(g: PAtom, ctx: Ctx): Boolean? {
        if (g.terms.size != 2) return null
        if (g.head == "in") {
            val r = rangeOf(g.terms[1]) ?: return null
            val v = (g.terms[0] as? NameRef)?.name ?: return null
            val loV = ctx.lo[v] ?: NEG_INF; val hiV = ctx.hi[v] ?: POS_INF
            return (loV == NEG_INF || loV >= r[0]) && (hiV == POS_INF || hiV <= r[1])
        }
        val ca = evalConst(g.terms[0]); val cb = evalConst(g.terms[1])
        if (ca != null && cb != null) return when (g.head) {
            "=", "==" -> ca == cb; "!=", "≠" -> ca != cb
            "<" -> ca < cb; ">" -> ca > cb
            "<=", "≤" -> ca <= cb; ">=", "≥" -> ca >= cb
            else -> null
        }
        if (g.head == "=" || g.head == "==") {
            val lc = evalConst(g.terms[0]); val rc = evalConst(g.terms[1])
            if (lc != null && rc != null) return lc == rc
            val l = linOf(g.terms[0]) ?: return null
            val r = linOf(g.terms[1]) ?: return null
            // 两变量（或含系数）等式：判 ≤ 与 ≥ 两向蕴含都成立 ⟺ 相等
            val okLe = negateAndApply("<=", l, r, cloneCtx(ctx))
            val okGe = negateAndApply(">=", l, r, cloneCtx(ctx))
            return when {
                okLe == true && okGe == true -> true
                okLe == null || okGe == null -> null
                else -> false
            }
        }
        val l = linOf(g.terms[0]) ?: return null
        val r = linOf(g.terms[1]) ?: return null
        val negCtx = cloneCtx(ctx)   // 复制含名字索引（否则新边索引与拷贝边错位）
        return negateAndApply(g.head, l, r, negCtx)
    }

    private fun negateAndApply(op: String, l: Lin, r: Lin, ctx: Ctx): Boolean? {
        // ¬(L op R) 直接归一为一条 coefs·v ≤ C（C 即约束右手边，不再取负）：
        //   ¬(Σ+c≤0) = -Σ ≤ c-1； ¬(Σ+c≤-1) = -Σ ≤ c； ¬(Σ+c≥0) = Σ ≤ -c-1； ¬(Σ+c≥1) = Σ ≤ -c
        val d = l.minus(r)
        val (coefs, C) = when (op) {
            "<=", "≤" -> d.neg().coefs to d.c - 1
            "<" -> d.neg().coefs to d.c
            ">=", "≥" -> d.coefs to -d.c - 1
            ">" -> d.coefs to -d.c
            else -> return null
        }
        if (coefs.isEmpty()) return C >= 0
        if (coefs.size == 1) {
            val (v, a) = coefs.entries.first()
            if (a > 0) {
                val b = floorDivSafe(C, a) ?: return null
                val loV = ctx.lo[v] ?: NEG_INF
                return loV != NEG_INF && loV > b
            } else {
                val b = ceilDivSafe(C, a) ?: return null
                val up = upperBound(ctx)[v] ?: POS_INF
                return up != POS_INF && up < b
            }
        }
        if (coefs.size == 2) {
            val es = coefs.entries.toList()
            val (v1, a1) = es[0]; val (v2, a2) = es[1]
            when {
                a1 == 1L && a2 == -1L -> { ctx.edges += longArrayOf(ctx.v2idx(v2).toLong(), ctx.v2idx(v1).toLong(), C); }
                a1 == -1L && a2 == 1L -> { ctx.edges += longArrayOf(ctx.v2idx(v1).toLong(), ctx.v2idx(v2).toLong(), C); }
                else -> return null
            }
            return bfHasNegCycle(ctx.edges, ctx)
        }
        return null
    }

    private fun disjRefute(goal: POr, ctx: Ctx): Boolean? {
        val leaves = flattenOr(goal)
        if (leaves.isEmpty()) return null
        val v = (leaves[0] as? PAtom)?.terms?.firstOrNull() as? NameRef ?: return null
        if (leaves.any { p -> (p as? PAtom)?.terms?.firstOrNull() != v }) return null
        var segs: MutableList<LongArray> = mutableListOf(longArrayOf(ctx.lo[v.name] ?: NEG_INF, ctx.hi[v.name] ?: POS_INF))
        for (p in leaves) {
            val r = leafRange(p) ?: return null
            val out = ArrayList<LongArray>()
            for (s in segs) {
                if (s[1] < r[0] || r[1] < s[0]) { out += s; continue }
                if (s[0] < r[0] && r[0] > NEG_INF) out += longArrayOf(s[0], r[0] - 1)
                if (r[1] < s[1] && r[1] < POS_INF) out += longArrayOf(r[1] + 1, s[1])
            }
            segs = out
            if (segs.isEmpty()) return true
        }
        return false
    }

    private fun leafRange(p: Prop): LongArray? {
        val pa = p as? PAtom ?: return null
        if (pa.terms.size != 2) return null
        if (pa.head == "in") return rangeOf(pa.terms[1])
        val l = linOf(pa.terms[0]) ?: return null
        val r = linOf(pa.terms[1]) ?: return null
        val d = l.minus(r)
        if (d.coefs.size != 1) return null
        val (_, a) = d.coefs.entries.first()
        val c = -d.c   // M1：单变量比较分支须读常数项。d = l - r，值域阈值 = -d.c（constOf 只在 coefs 为空时非空，此前恒 null 使 < <= > >= = 分支全失效）
        return when (pa.head) {
            "<=", "≤" -> if (a == 1L) longArrayOf(NEG_INF, c) else longArrayOf(ceilDivSafe(c, a) ?: return null, POS_INF)
            "<" -> if (a == 1L) longArrayOf(NEG_INF, c - 1) else longArrayOf(ceilDivSafe(c + 1, a) ?: return null, POS_INF)
            ">=", "≥" -> if (a == 1L) longArrayOf(c, POS_INF) else longArrayOf(NEG_INF, floorDivSafe(c, a) ?: return null)
            ">" -> if (a == 1L) longArrayOf(c + 1, POS_INF) else longArrayOf(NEG_INF, floorDivSafe(c - 1, a) ?: return null)
            "=", "==" -> longArrayOf(c, c)
            else -> null
        }
    }

    private fun rangeOf(e: Expr): LongArray? = when (e) {
        is PAtom -> if (e.head == ".." && e.terms.size == 2) consts(e.terms[0], e.terms[1]) else null
        is BinExpr -> if (e.op == "..") consts(e.left, e.right) else null
        else -> null
    }

    private fun consts(a: Expr, b: Expr): LongArray? {
        val av = (a as? IntLit)?.value?.toLongOrNull() ?: return null
        val bv = (b as? IntLit)?.value?.toLongOrNull() ?: return null
        return longArrayOf(av, bv)
    }

    private fun flattenOr(p: Prop): List<Prop> = when (p) {
        is POr -> flattenOr(p.a) + flattenOr(p.b)
        else -> listOf(p)
    }
}
