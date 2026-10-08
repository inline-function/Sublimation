package sugared.functor.checker

import sugared.functor.ast.*

/**
 * P7（决策 83）：标准合一——约束求解写入 sub，返回是否成功（v1.0 计划 §9：替换表 + occurs check）。
 * - **类型变量**：变量 = 名字在 vars 且零实参的 NamedType（如 `T`）；变量-类型 → 绑定，变量-变量 → 单向绑定；
 * - **occurs check**：`T` 绑定到包含 `T` 的类型（`Optional<T>`）→ 失败（拒绝无限类型）；
 * - **结构递归**：NamedType 同形逐实参、FunType 参数+返回、TupleType 逐项；
 * - **数值升格**：已绑定变量遇更高数值类型（Nat→Int）走 typeLooseEq 静默保持（与 P2 putIfAbsent 语义一致，不破坏既有行为）；
 * - **synthetic 宽容**：合成类型不产生约束（true）。
 * deref 递归展开 sub 链（防环，steps 上限）。
 */
fun unifyInto(a: Type, b: Type, vars: Set<String>, sub: MutableMap<String, Type>): Boolean {
    val aa = deref(a, vars, sub)
    val bb = deref(b, vars, sub)
    val aVar = aa is NamedType && aa.args.isEmpty() && aa.name in vars
    val bVar = bb is NamedType && bb.args.isEmpty() && bb.name in vars
    if (aVar && bVar) {
        if (aa.name != bb.name) sub[aa.name] = bb
        return true
    }
    if (aVar) {
        if (bb.isSynthetic()) return true                 // 变量不得绑到 synthetic（与旧 extractTpBinding 语义一致）
        if (occurs(aa.name, bb, vars)) return false       // occurs check
        val cur = sub[aa.name]
        if (cur != null) return looseEq(cur, bb)          // 已绑定一致性（静默，提取语义）
        sub[aa.name] = bb
        return true
    }
    if (bVar) {
        if (aa.isSynthetic()) return true
        if (occurs(bb.name, aa, vars)) return false
        val cur = sub[bb.name]
        if (cur != null) return looseEq(cur, aa)
        sub[bb.name] = aa
        return true
    }
    return when {
        aa is FunType && bb is FunType ->
            aa.params.zip(bb.params).all { unifyInto(it.first, it.second, vars, sub) } &&
                unifyInto(aa.ret, bb.ret, vars, sub)
        aa is TupleType && bb is TupleType && aa.items.size == bb.items.size ->
            aa.items.zip(bb.items).all { unifyInto(it.first, it.second, vars, sub) }
        aa is NamedType && bb is NamedType && aa.name == bb.name && aa.args.size == bb.args.size ->
            aa.args.zip(bb.args).all { unifyInto(it.first, it.second, vars, sub) }
        // P7：类型-类型核对用宽松相等（数值升格 Nat↔Int 放行、synthetic 放行）——提取语义非核对语义
        else -> looseEq(aa, bb)
    }
}

/** P7：宽松相等（TypeInfer 自用，无别名展开/无 Checker 依赖）：数值放行、synthetic 放行、结构递归 */
private fun looseEq(a: Type, b: Type): Boolean {
    if (a.isSynthetic() || b.isSynthetic()) return true
    if (a is NamedType && b is NamedType) {
        if (a.args.isEmpty() && b.args.isEmpty() && looseNumeric(a.name, b.name)) return true
        if (a.name != b.name || a.args.size != b.args.size) return false
        return a.args.zip(b.args).all { looseEq(it.first, it.second) }
    }
    return typeEq(a, b)
}

private fun looseNumeric(a: String, b: String): Boolean =
    a in setOf("Nat", "Int", "Rat") && b in setOf("Nat", "Int", "Rat")

private fun deref(t: Type, vars: Set<String>, sub: MutableMap<String, Type>): Type {
    var cur = t
    var steps = 0
    while (cur is NamedType && cur.args.isEmpty() && cur.name in vars && cur.name in sub && steps < 32) {
        cur = sub.getValue(cur.name); steps++
    }
    return cur
}

/** P7：T 是否在 t 中出现（仅统计 vars 中的类型变量名；结构递归遍达嵌套） */
private fun occurs(name: String, t: Type, vars: Set<String>): Boolean = when (t) {
    is NamedType -> t.name == name && t.args.isEmpty() || t.args.any { occurs(name, it, vars) }
    is FunType -> t.params.any { occurs(name, it, vars) } || occurs(name, t.ret, vars)
    is TupleType -> t.items.any { occurs(name, it, vars) }
    else -> false
}

/** 数值类型偏序：Nat ⊔ Int = Int，Nat ⊔ Rat = Rat…… */
private val numericRank = mapOf("Nat" to 0, "Int" to 1, "Rat" to 2)

/**
 * v1 类型推导（本轮仅变量/表达式类型；泛型 lambda 与完整推导系统下一轮讨论）。
 * 推导失败以 synthetic 类型承接：上层按"宽松通过"处理，类型错误只报有确切证据的，
 * 这是"实用语言 + 增量类型系统"的过渡策略，不阻塞实体检查。
 */
object TypeInfer {
    fun unify(a: Type, b: Type): Type? = when {
        typeEq(a, b) -> a
        a.isSynthetic() || b.isSynthetic() -> a.takeUnless { it.isSynthetic() } ?: b
        a.isNumeric() && b.isNumeric() ->
            if (numericRank.getValue(a.name) >= numericRank.getValue(b.name)) a else b
        a.isNominal() && b.isNominal() && a.name == b.name && a.args.size == b.args.size -> a
        else -> null
    }

    private fun Type.isNumeric(): Boolean = name in numericRank && args.isEmpty()

    /** 内建运算符（符号函数）的类型签名；返回 null = 表外符号 */
    fun builtinOp(op: String, argTypes: List<Type?>): Type? {
        val ts = argTypes.filterNotNull()
        return when (op) {
            "+", "-", "*", "%" -> if (ts.size == argTypes.size && ts.all { it.isNumeric() })
                ts.reduce { x, y -> unify(x, y) ?: x } else null
            "/" -> if (ts.size == argTypes.size && ts.all { it.isNumeric() }) namedT("Rat", emptyList()) else null
            "=", "==", "!=", "<", ">", "<=", ">=" -> if (ts.size == 2) namedT("Bool", emptyList()) else null
            "!", "¬" -> namedT("Bool", emptyList())
            "&", "|", "->", "<->", "∧", "∨", "→", "↔",
            "u&", "u|", "u->", "u<->", "u!=" -> namedT("Bool", emptyList())
            else -> null
        }
    }
}
