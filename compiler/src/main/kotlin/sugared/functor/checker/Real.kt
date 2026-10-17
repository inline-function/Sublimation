package sugared.functor.checker

import sugared.functor.ast.*
import java.math.BigInteger

/**
 * 精确实数 Real（草案 §3）——精确符号数值：分数 + 根号 + 运算树。
 * 第一波：RExact（有理数）、RSqrt（平方根）、ROp(ADD/MUL/POW/NEG)。
 * POW 节点只存不化简（属第二波），NEG 由规范化消融入 RExact 负号。
 */
sealed interface Real

data class RExact(val num: BigInteger, val den: BigInteger) : Real
data class RSqrt(val radicand: Real) : Real
data class ROp(val op: OpKind, val args: MutableList<Real>) : Real

enum class OpKind { ADD, MUL, POW, NEG }

// ================================================================
// 构造工厂
// ================================================================

private val ONE_BI = BigInteger.ONE
private val ZERO_BI = BigInteger.ZERO

internal fun rExact(num: BigInteger, den: BigInteger): RExact {
    var n = num; var d = den
    if (d.signum() == 0) throw ArithmeticException("Real 除以零")
    if (d.signum() < 0) { n = n.negate(); d = d.negate() }
    val g = n.gcd(d)
    return if (g.signum() != 0 && g != ONE_BI) RExact(n / g, d / g) else RExact(n, d)
}

internal val R_ZERO = RExact(ZERO_BI, ONE_BI)
internal val R_ONE = RExact(ONE_BI, ONE_BI)

internal fun rInt(n: Long): RExact = RExact(BigInteger.valueOf(n), ONE_BI)
internal fun rInt(n: BigInteger): RExact = RExact(n, ONE_BI)

// ================================================================
// 有理数算术
// ================================================================

internal fun rAdd(a: RExact, b: RExact): RExact =
    rExact(a.num * b.den + b.num * a.den, a.den * b.den)

internal fun rMul(a: RExact, b: RExact): RExact =
    rExact(a.num * b.num, a.den * b.den)

internal fun rNeg(a: RExact): RExact = RExact(a.num.negate(), a.den)

internal fun rDiv(a: RExact, b: RExact): RExact =
    rExact(a.num * b.den, a.den * b.num)

// ================================================================
// 结构相等（§5 事件 4.2）
// ================================================================

internal fun structuralEqual(a: Real, b: Real): Boolean = when {
    a is RExact && b is RExact -> a.num == b.num && a.den == b.den
    a is RSqrt && b is RSqrt -> structuralEqual(a.radicand, b.radicand)
    a is ROp && b is ROp -> a.op == b.op && a.args.size == b.args.size &&
        a.args.zip(b.args).all { (x, y) -> structuralEqual(x, y) }
    else -> false
}

// ================================================================
// 规范序（§5 事件 5.3 公共）
// ================================================================

private val OP_ORDER = mapOf(OpKind.ADD to 0, OpKind.MUL to 1, OpKind.POW to 2, OpKind.NEG to 3)

private fun compareReal(a: Real, b: Real): Int {
    return when {
        a is RExact && b is RExact -> (a.num * b.den).compareTo(b.num * a.den)
        a is RExact && b !is RExact -> -1   // 有理数最前
        b is RExact && a !is RExact -> 1
        a is RSqrt && b is RSqrt -> compareReal(a.radicand, b.radicand)
        a is RSqrt && b !is RSqrt -> -1    // 根号在 ROp 前
        b is RSqrt && a !is RSqrt -> 1
        a is ROp && b is ROp -> {
            val c = OP_ORDER[a.op]!! - OP_ORDER[b.op]!!
            if (c != 0) return c
            for (i in a.args.indices) {
                if (i >= b.args.size) return 1
                val d = compareReal(a.args[i], b.args[i])
                if (d != 0) return d
            }
            a.args.size - b.args.size
        }
        else -> 0
    }
}

// ================================================================
// 规范化 (底线 + 不动点))
// ================================================================

internal fun normalize(r0: Real): Real {
    var r = r0
    var guard = 0
    while (guard++ < 10000) {
        val n = normalizeChildren(r)
        val n2 = applyRules(n)
        if (structuralEqual(n2, r)) return r
        r = n2
    }
    return r
}

private fun normalizeChildren(r: Real): Real = when (r) {
    is RExact -> r
    is RSqrt -> RSqrt(normalize(r.radicand))
    is ROp -> {
        val normed = r.args.map { normalize(it) }
        // 展平同类型 ROp
        if (r.op == OpKind.ADD || r.op == OpKind.MUL) {
            val flat = ArrayList<Real>()
            for (x in normed) {
                if (x is ROp && x.op == r.op) flat.addAll(x.args) else flat.add(x)
            }
            ROp(r.op, flat)
        } else {
            ROp(r.op, ArrayList(normed))
        }
    }
}

private fun applyRules(r: Real): Real {
    var cur = r
    // 反复施规则直到不动（当前层级，子节点已归一）。
    // 关键：R18 对已规范的 √RExact 会构造出结构相等的“新”对象（√2 → √2），
    // 若不按结构相等判不动点，会在此无限循环（√2·√2 证明卡死）。
    while (true) {
        val n = onePass(cur) ?: return cur
        if (structuralEqual(n, cur)) return cur
        cur = n
    }
}

private fun onePass(r: Real): Real? {
    // ==== 基础简化 R1-R7 ====
    if (r is ROp) {
        val op = r.op; val a = r.args
        // R1: ADD[x] → x
        if (op == OpKind.ADD && a.size == 1) return a[0]
        // R2: MUL[x] → x
        if (op == OpKind.MUL && a.size == 1) return a[0]
        // R3: ADD with 0 → drop
        if (op == OpKind.ADD) {
            val noZero = a.filter { !(it is RExact && it.num.signum() == 0) }
            if (noZero.size != a.size) return if (noZero.isEmpty()) R_ZERO else ROp(OpKind.ADD, ArrayList(noZero))
        }
        // R4: MUL with 1 → drop
        if (op == OpKind.MUL) {
            val noOne = a.filter { !(it is RExact && it.num == ONE_BI && it.den == ONE_BI) }
            if (noOne.size != a.size) return if (noOne.isEmpty()) R_ONE else ROp(OpKind.MUL, ArrayList(noOne))
        }
        // R5: MUL with 0 → 0
        if (op == OpKind.MUL && a.any { it is RExact && it.num.signum() == 0 }) return R_ZERO
        // R6: NEG[RExact] → RExact(-p,q)
        if (op == OpKind.NEG && a.size == 1) {
            val x = a[0]
            if (x is RExact) return rNeg(x)
            // R7: NEG[NEG[x]] → x
            if (x is ROp && x.op == OpKind.NEG && x.args.size == 1) return x.args[0]
        }

        // ==== 有理数合并 R8/R9 ====
        if (op == OpKind.ADD) {
            val rats = a.filterIsInstance<RExact>()
            if (rats.size >= 2) {
                val others = a.filter { it !is RExact }
                val sum = rats.drop(1).fold(rats[0]) { acc, r -> rAdd(acc, r) }
                val result = ArrayList<Real>()
                if (sum.num.signum() != 0) result.add(sum)
                result.addAll(others)
                return if (result.isEmpty()) R_ZERO else ROp(OpKind.ADD, result)
            }
        }
        if (op == OpKind.MUL) {
            val rats = a.filterIsInstance<RExact>()
            if (rats.size >= 2) {
                val others = a.filter { it !is RExact }
                val prod = rats.drop(1).fold(rats[0]) { acc, r -> rMul(acc, r) }
                val result = ArrayList<Real>()
                if (prod != R_ONE) result.add(prod)
                result.addAll(others)
                return if (result.isEmpty()) R_ONE else ROp(OpKind.MUL, result)
            }
        }

        // ==== 排序 R11/R14 ====
        if (op == OpKind.ADD || op == OpKind.MUL) {
            val sorted = a.sortedWith(::compareReal)
            if (sorted != a) return ROp(op, ArrayList(sorted))
        }

        // ==== 同类项合并 R12 (ADD), R15 (MUL POW) ====
        if (op == OpKind.ADD) {
            // R12: k·x + m·x → (k+m)·x
            // R13: x + NEG(x) → 0  (already handled by rational merge since NEG of RExact → RExact(-), then ADD merges)
            for (i in a.indices) {
                for (j in i + 1 until a.size) {
                    if (isLikeTerm(a[i], a[j])) {
                        val (ki, xi) = splitCoeff(a[i])
                        val (kj, _) = splitCoeff(a[j])
                        val k = rAdd(ki, kj)
                        val result = ArrayList<Real>(a)
                        result.removeAt(maxOf(i, j))
                        result.removeAt(minOf(i, j))
                        if (k.num.signum() != 0) result.add(mul(k, xi))
                        return if (result.isEmpty()) R_ZERO else ROp(OpKind.ADD, result)
                    }
                    // R13 also: x + NEG(x) → 0
                    if (isNegOf(a[i], a[j])) {
                        val result = ArrayList<Real>(a)
                        result.removeAt(maxOf(i, j))
                        result.removeAt(minOf(i, j))
                        return if (result.isEmpty()) R_ZERO else ROp(OpKind.ADD, result)
                    }
                }
            }
        }
        if (op == OpKind.MUL && a.size >= 2) {
            // R15 lite: POW with same base → merge exponents
            for (i in a.indices) {
                for (j in i + 1 until a.size) {
                    val ai = a[i]; val aj = a[j]
                    if (ai is ROp && ai.op == OpKind.POW && ai.args.size == 2 &&
                        aj is ROp && aj.op == OpKind.POW && aj.args.size == 2 &&
                        structuralEqual(ai.args[0], aj.args[0])) {
                        val newExp = add(ai.args[1], aj.args[1])
                        val result = ArrayList<Real>(a)
                        result.removeAt(maxOf(i, j))
                        result.removeAt(minOf(i, j))
                        result.add(ROp(OpKind.POW, mutableListOf(ai.args[0], newExp)))
                        return ROp(OpKind.MUL, result)
                    }
                }
            }
        }
    }

    // R22: (√a)^n → a^(n/2)  for even integer n ≥ 0 (e.g. (√2)^2 → 2)
    if (r is ROp && r.op == OpKind.POW && r.args.size == 2) {
        val base = r.args[0]; val exp = r.args[1]
        if (base is RSqrt && exp is RExact && exp.den == ONE_BI && exp.num.signum() >= 0) {
            val n = exp.num; val two = BigInteger.valueOf(2)
            if (n.mod(two) == BigInteger.ZERO) {
                val half = rInt(n.divide(two))
                return if (half == R_ONE) base.radicand else ROp(OpKind.POW, mutableListOf(base.radicand, half))
            }
        }
    }

    // ==== 根号 R18/R20/R21 ====
    if (r is RSqrt) {
        val x = r.radicand
        // R18: sqrt(RExact) → extract square factors
        if (x is RExact) {
            val (outer, inner) = extractSquareFactor(x)
            if (inner.num.signum() == 0) return R_ZERO
            if (inner == R_ONE) return outer   // perfect square
            return if (outer == R_ONE) RSqrt(inner) else mul(outer, RSqrt(inner))
        }
        // R19 sqrt(x·x) → x (x >= 0)
        if (x is ROp && x.op == OpKind.MUL) {
            // R21: sqrt(a)*sqrt(a) handled via R20: sqrt(a)·sqrt(a) → sqrt(a·a) → by this rule
            // Find if x is a*a for some a
            if (x.args.size == 2 && structuralEqual(x.args[0], x.args[1]))
                return x.args[0]
        }
    }

    // R20: sqrt(a)·sqrt(b) → sqrt(a·b)
    if (r is ROp && r.op == OpKind.MUL) {
        val sqrts = r.args.filterIsInstance<RSqrt>()
        if (sqrts.size >= 2) {
            val others = r.args.filter { it !is RSqrt }
            val merged = RSqrt(sqrts.drop(1).fold(sqrts[0].radicand) { acc, s -> rmul(acc, s.radicand) })
            val result = ArrayList<Real>()
            result.addAll(others)
            result.add(merged)
            return ROp(OpKind.MUL, result)
        }
    }

    return null   // 不动
}

// ================================================================
// 辅助函数
// ================================================================

/** k·x → (k, x)，若 x 不是 MUL 包装则 1·x */
private fun splitCoeff(r: Real): Pair<RExact, Real> = when (r) {
    is ROp -> if (r.op == OpKind.MUL && r.args.isNotEmpty() && r.args[0] is RExact) {
        val k = r.args[0] as RExact
        val rest = if (r.args.size == 2) r.args[1] else ROp(OpKind.MUL, ArrayList(r.args.subList(1, r.args.size)))
        k to rest
    } else R_ONE to r
    else -> R_ONE to r
}

/** 规格化后的 PERFECT k·x 构造 */
private fun mul(k: RExact, x: Real): Real = when {
    x is RExact -> rMul(k, x)
    k == R_ONE -> x
    x is ROp && x.op == OpKind.MUL && x.args.isNotEmpty() && x.args[0] is RExact -> {
        val k0 = x.args[0] as RExact
        val rest = x.args.subList(1, x.args.size)
        val newK = rMul(k, k0)
        val all = ArrayList<Real>(); all.add(newK); all.addAll(rest)
        ROp(OpKind.MUL, all)
    }
    else -> ROp(OpKind.MUL, mutableListOf(k, x))
}

internal fun add(a: Real, b: Real): Real = ROp(OpKind.ADD, mutableListOf(a, b))
internal fun rmul(a: Real, b: Real): Real = ROp(OpKind.MUL, mutableListOf(a, b))

/** 两项是否为同类（除去系数后相同） */
private fun isLikeTerm(a: Real, b: Real): Boolean {
    val (_, xa) = splitCoeff(a)
    val (_, xb) = splitCoeff(b)
    return structuralEqual(xa, xb)
}

/** a 是否是 b 的 NOT：x + NEG(x) */
private fun isNegOf(a: Real, b: Real): Boolean = when {
    a is ROp && a.op == OpKind.NEG && a.args.size == 1 -> structuralEqual(a.args[0], b)
    b is ROp && b.op == OpKind.NEG && b.args.size == 1 -> structuralEqual(b.args[0], a)
    else -> false
}

/** 提取平方因子：√(p/q) = (a/b)·√(p'/q') 其中 p=a²p', q=b²q' */
private fun extractSquareFactor(x: RExact): Pair<RExact, RExact> {
    val p = x.num
    val q = x.den
    val ap = rootFloor(p)
    val aq = rootFloor(q)
    val pp = p / (ap * ap)
    val qq = q / (aq * aq)
    val outer = rExact(ap, aq)
    val inner = rExact(pp, qq)
    return outer to inner
}

/** Integer square root floor */
private fun rootFloor(n: BigInteger): BigInteger {
    if (n.signum() <= 0) return ZERO_BI
    var x = BigInteger.valueOf(n.toLong().coerceAtLeast(1).let { kotlin.math.sqrt(it.toDouble()).toLong() })
    while (true) {
        val nx = (x + n / x).shr(1)
        if (nx >= x) return x
        x = nx
    }
}

// ================================================================
// 从 AST Expr 构造 Real（第一波：IntLit / + - * / / sqrt / ^）
// ================================================================

/** 把 AST 表达式（部分支持）转换为 Real。不支持的返回 null。 */
internal fun realFromExpr(e: Expr): Real? {
    fun fail() = null as Real?
    return when (e) {
        is IntLit -> {
            val v = e.value.toBigIntegerOrNull() ?: return fail()
            rInt(v)
        }
        is FloatLit -> {
            val s = e.value
            val dot = s.indexOf('.')
            if (dot < 0) {
                rInt(s.toBigIntegerOrNull() ?: return fail())
            } else {
                val intPart = s.substring(0, dot).replace("-", "")
                val fracPart = s.substring(dot + 1)
                val neg = s.startsWith("-")
                val num = (intPart + fracPart).toBigIntegerOrNull() ?: return fail()
                val den = BigInteger.TEN.pow(fracPart.length)
                rExact(if (neg) num.negate() else num, den)
            }
        }
        is UniExpr -> when (e.op) {
            "-" -> {
                val inner = realFromExpr(e.operand) ?: return fail()
                ROp(OpKind.NEG, mutableListOf(inner))
            }
            else -> null
        }
        is BinExpr -> {
            val l = realFromExpr(e.left) ?: return fail()
            val r = realFromExpr(e.right) ?: return fail()
            when (e.op) {
                "+" -> ROp(OpKind.ADD, mutableListOf(l, r))
                "*" -> ROp(OpKind.MUL, mutableListOf(l, r))
                "-" -> ROp(OpKind.ADD, mutableListOf(l, ROp(OpKind.NEG, mutableListOf(r))))
                "/" -> if (l is RExact && r is RExact) rDiv(l, r) else ROp(OpKind.MUL, mutableListOf(l, ROp(OpKind.POW, mutableListOf(r, rInt(-1)))))
                "^" -> ROp(OpKind.POW, mutableListOf(l, r))
                else -> null
            }
        }
        is CallExpr -> {
            val callee = e.callee
            if (callee is NameRef && e.args.size == 1) {
                when (callee.name) {
                    "toReal" -> realFromExpr(e.args[0])  // 身份：实数域内 toReal(n) = n
                    "sqrt" -> { val arg = realFromExpr(e.args[0]) ?: return fail(); RSqrt(arg) }
                    else -> null
                }
            } else null
        }
        else -> null
    }
}