package sugared.functor.checker

import sugared.functor.ast.*

/**
 * 通用线性算术判定（整数域）：证明器的**通用算术通道**，不是任何语法形态的特例。
 *
 * 前提（gamma）解析为「单变量线性界约束」的合取：`x ≤ c` `x < c` `x ≥ c` `x > c` `x = c`
 * `x in (a..b)`，以及这些原子的否定（`¬(x ≤ c)` ⟺ `x ≥ c+1` 等，整数语义）。
 * 目标为单个线性原子，或它们的析取链（when 穷尽的分支条件、if[p] 不可能分支、守卫蕴含、
 * 前件/∵∴ 的算术目标）时，用**界收紧（arc consistency）**判定蕴含：
 *
 *  - 单原子目标：前提收紧出 x 的域 [lo, hi]，检查域是否蕴含目标界；
 *  - 析取目标：反证「前提 ∧ ¬p₁ ∧ … ∧ ¬pₙ」——每个变量域为空即不可满足 → 前提 ⊨ ∨pᵢ。
 *
 * 全部是整数上的有序域推理，与具体语法（in 区间还是不等式）无关。
 * 返回三态：true=蕴含成立；false=判定为不蕴含；null=形态不在本片段内（由既有证明器接管）。
 */
internal object ArithProve {

    private const val POS_INF = Long.MAX_VALUE
    private const val NEG_INF = Long.MIN_VALUE

    fun proves(gamma: List<Prop>, goal: Prop): Boolean? {
        val lo = HashMap<String, Long>()
        val hi = HashMap<String, Long>()
        if (!collectBounds(gamma, lo, hi)) return null
        return when (goal) {
            is PAtom -> atomImplied(goal, lo, hi)
            is POr -> disjRefute(goal, lo, hi)
            else -> null
        }
    }

    /** 收集前提界约束；无法表达的前提原子（x = self.x、x : T、复合命题等）**跳过**而非放弃——
     *  少用前提只会更保守（可能少证、不会假证），算术通道应尽量可用。 */
    private fun collectBounds(gamma: List<Prop>, lo: MutableMap<String, Long>, hi: MutableMap<String, Long>): Boolean {
        fun walk(body: Prop) {
            when (body) {
                is PAnd -> { walk(body.a); walk(body.b) }
                is PAtom -> applyBound(body, lo, hi, negate = false)
                is PNot -> (body.p as? PAtom)?.let { applyBound(it, lo, hi, negate = true) }
                else -> {}
            }
        }
        gamma.forEach { walk(it) }
        return true
    }

    /** 应用一条界约束（negate=true 取反）。无法单界表达 → false（整体放弃本通道）。 */
    private fun applyBound(p: PAtom, lo: MutableMap<String, Long>, hi: MutableMap<String, Long>, negate: Boolean): Boolean {
        if (p.terms.size != 2) return false
        val v = (p.terms[0] as? NameRef)?.name ?: return false
        if (p.head == "in") {
            if (negate) return false   // ¬(x ∈ (a..b)) 是两析取，超出单界片段
            val r = rangeOf(p.terms[1]) ?: return false
            if (!tight(lo, hi, v, ">=", r[0], negate = false)) return false
            return tight(lo, hi, v, "<=", r[1], negate = false)
        }
        val c = (p.terms[1] as? IntLit)?.value?.toLongOrNull() ?: return false
        return tight(lo, hi, v, p.head, c, negate)
    }

    /** 收紧界；已空（lo > hi）返回 false（本通道不可用，走旧证明器更安全——但 bound 应用冲突即前提本身矛盾，
     *  多数场景表示我们的片段判定不了，返回 false 放弃）。 */
    private fun tight(lo: MutableMap<String, Long>, hi: MutableMap<String, Long>, v: String, op: String, c: Long, negate: Boolean): Boolean {
        val (fop, fc) = when {
            !negate -> op to c
            op == "<=" -> ">=" to c + 1
            op == "<" -> ">=" to c
            op == ">=" -> "<=" to c - 1
            op == ">" -> "<=" to c
            op == "=" -> {
                // ¬(x = c)：c 已在前提域内则无法单界表达（要分两支）；域外恒真无约束力
                val loV = lo[v]; val hiV = hi[v]
                val inside = (loV == null || loV <= c) && (hiV == null || c <= hiV)
                return !inside
            }
            else -> return false
        }
        if ((fop == "<" || fop == "<=") && fc == NEG_INF) return false
        if ((fop == ">" || fop == ">=") && fc == POS_INF) return false
        val curLo = lo[v] ?: NEG_INF
        val curHi = hi[v] ?: POS_INF
        return when (fop) {
            "<=", "≤", "<" -> {
                val h = if (fop == "<") fc - 1 else fc
                if (h < curLo) return false
                val nh = if (h < curHi) h else curHi
                hi[v] = nh; true
            }
            ">=", "≥", ">" -> {
                val l = if (fop == ">") fc + 1 else fc
                if (l > curHi) return false
                val nl = if (l > curLo) l else curLo
                lo[v] = nl; true
            }
            else -> false
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

    /** 单原子目标蕴含：前提域 ⊆ 目标约束。null=目标形态超片段（走旧证明器）。 */
    private fun atomImplied(g: PAtom, lo: Map<String, Long>, hi: Map<String, Long>): Boolean? {
        if (g.terms.size != 2) return null
        val v = (g.terms[0] as? NameRef)?.name ?: return null
        val loV = lo[v] ?: NEG_INF
        val hiV = hi[v] ?: POS_INF
        if (g.head == "in") {
            val r = rangeOf(g.terms[1]) ?: return null
            return loV >= r[0] && hiV <= r[1]
        }
        val c = (g.terms[1] as? IntLit)?.value?.toLongOrNull() ?: return null
        return when (g.head) {
            "<=", "≤", "<" -> hi.containsKey(v) && hiV <= (if (g.head == "<") c - 1 else c)
            ">=", "≥", ">" -> lo.containsKey(v) && loV >= (if (g.head == ">") c + 1 else c)
            "=", "==" -> lo.containsKey(v) && hi.containsKey(v) && loV <= c && c <= hiV
            else -> null
        }
    }

    /** 析取目标域差集反证：前提之域（各变量界收紧）减去每个叶子分支区间，剩余为空 ⇒ 前提 ⊨ ∨pᵢ。
     *  任意单变量「区间/不等式/相等」叶子均可参与（in 只是其中一种写法）——通用算术，非区间特例。 */
    private fun disjRefute(goal: POr, lo: Map<String, Long>, hi: Map<String, Long>): Boolean? {
        val leaves = flattenOr(goal)
        if (leaves.isEmpty()) return null
        val v = (leaves[0] as? PAtom)?.terms?.firstOrNull() as? NameRef ?: return null
        if (leaves.any { p -> (p as? PAtom)?.terms?.firstOrNull() != v }) return null   // 多变量/空项析取：非本片段
        var segs: MutableList<LongArray> = mutableListOf(longArrayOf(lo[v.name] ?: NEG_INF, hi[v.name] ?: POS_INF))
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

    /** 叶子原子 → 该变量的一个区间 [l, r]（含 ±∞ 端）；无法表达返回 null。in 只是其中一种写法。 */
    private fun leafRange(p: Prop): LongArray? {
        val pa = p as? PAtom ?: return null
        if (pa.terms.size != 2) return null
        if (pa.head == "in") return rangeOf(pa.terms[1])
        val c = (pa.terms[1] as? IntLit)?.value?.toLongOrNull() ?: return null
        return when (pa.head) {
            "<=", "<" -> if (c == NEG_INF) null else longArrayOf(NEG_INF, if (pa.head == "<") c - 1 else c)
            ">=", ">" -> if (c == POS_INF) null else longArrayOf(if (pa.head == ">") c + 1 else c, POS_INF)
            "=", "==" -> longArrayOf(c, c)
            else -> null
        }
    }

    private fun flattenOr(p: Prop): List<Prop> = when (p) {
        is POr -> flattenOr(p.a) + flattenOr(p.b)
        else -> listOf(p)
    }
}
