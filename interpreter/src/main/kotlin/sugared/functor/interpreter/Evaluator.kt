package sugared.functor.interpreter

import sugared.functor.ast.*

/**
 * AST 树遍历解释器。
 */
class Evaluator(
    private val hostFunctions: Map<String, (List<Any?>) -> Any?> = emptyMap(),
    private val hostStructs: Map<String, StructInfo> = emptyMap(),
    // H3：Checker 留痕——变量位/表达式体的空安全自动解构（var x: Nat = Some(1) → x 存 1）
    private val varUnwrapHits: Map<VarStmt, Expr> = emptyMap(),
    private val funBodyUnwrapHits: Map<Expr, Expr> = emptyMap(),
) {

    /** 脚本自己声明的 struct/enum 构造子（运行时结构体元数据） */
    private val localStructs = LinkedHashMap<String, StructInfo>()

    /** enum 构造子 → 所属枚举名（重载分派用：SetCons→Set、MapCons→Map 等） */
    private val ctorToEnum = HashMap<String, String>()

    /** 类型类字典：方法名 → 候选列表（含 self 基名 / trait 名 / 实现方法）。多候选可检测（P4）。 */
    private val methodImpls = LinkedHashMap<String, MutableList<MethodImpl>>()

    data class MethodImpl(val selfTypeName: String, val traitName: String, val fn: FunDecl)

    private fun lookupStruct(name: String): StructInfo? = hostStructs[name] ?: localStructs[name]

    fun evaluate(file: FileAst, frame: ScriptFrame = ScriptFrame(), mainName: String? = null) {
        collectLocalDecls(file)
        for (entry in file.entries) {
            if (entry is DeclEntry) {
                val d = entry.decl
                if (d is FunDecl) frame.declareFunction(d)
            }
        }
        var hasTopStmt = false
        for (entry in file.entries) {
            when (entry) {
                is DeclEntry -> evalDecl(entry.decl, frame)
                is StmtEntry -> { evalStmt(entry.stmt, frame); hasTopStmt = true }
            }
        }
        // 工程模式（目录 / 多文件）：顶层全是声明时，调用 main() 作为程序入口
        if (!hasTopStmt && mainName != null && frame.lookupFunctions(mainName).isNotEmpty()) {
            callFunction(mainName, emptyList(), frame, "<main>")
        }
    }

    private fun evalDecl(decl: Decl, frame: ScriptFrame) { }

    /** 收集脚本声明的 struct/enum 构造子 → 运行时结构体元数据（§4.3 常量折叠为引用语义） */
    private fun collectLocalDecls(file: FileAst) {
        localStructs.clear()
        ctorToEnum.clear()
        methodImpls.clear()
        for (entry in file.entries) {
            if (entry !is DeclEntry) continue
            when (val d = entry.decl) {
                is StructDecl -> {
                    val mutable = d.fields.filter { "mut" in it.annotations }.map { it.name }
                    val defaults = d.fields.mapNotNull { f -> f.default?.let { f.name to it } }.toMap()
                    localStructs[d.name] = StructInfo(
                        name = d.name,
                        fieldTypes = linkedMapOf(*d.fields.map { it.name to ScriptType.Any }.toTypedArray()),
                        mutableFields = mutable,
                        fieldDefaults = defaults,
                    )
                }
                is EnumDecl -> {
                    for (ctor in d.ctors) {
                        ctorToEnum[ctor.name] = d.name
                        val names = ctor.fields.indices.map { "_$it" }
                        localStructs[ctor.name] = StructInfo(
                            name = ctor.name,
                            fieldTypes = linkedMapOf(*names.map { it to ScriptType.Any }.toTypedArray()),
                            mutableFields = emptyList(),
                        )
                    }
                }
                is ImplDecl -> {
                    // 类型类 impl（trait 非 null）：登记方法字典，供方法糖按接收者类型分发
                    val traitName = (d.trait as? NamedType)?.name
                    val selfTag = (d.self as? NamedType)?.name
                    if (traitName != null && selfTag != null) {
                        for (m in d.members) {
                            methodImpls.getOrPut(m.name) { mutableListOf() }
                                .add(MethodImpl(selfTag, traitName, m))
                        }
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * 变量绑定拷贝规则（root doc 副作用与可变性控制）：
     *   双侧 @ref → 别名（共享，不拷贝）；否则若值为引用类型 → 深拷贝。
     *   M5（已知偏差）：编译模式判据 bindNeedsCopy 是「目标/源标 @mut 类型或 @ref 才拷贝」，
     *   此处只看变量声明 @ref。普通结构体变量（无 @mut/@ref）在两模式对别名依赖时语义不同——
     *   对齐需运行时类型元数据（Struct.isMut 或 Checker 导出类型 mut），待拍板。
     */
    private fun bindCopy(src: ScriptValue, targetRef: Boolean, sourceRef: Boolean): ScriptValue =
        if (!targetRef || !sourceRef) deepCopy(src) else src

    private fun sourceIsRef(e: Expr, frame: ScriptFrame): Boolean =
        e is NameRef && frame.isRef(e.name)

    private fun deepCopy(v: ScriptValue): ScriptValue = when (v) {
        is ScriptValue.Struct -> {
            val fields = LinkedHashMap<String, ScriptValue>()
            v.fields.forEach { (k, sv) -> fields[k] = deepCopy(sv) }
            // mutableFields 是类型元数据（只读共享），深拷贝只复制值字段（P3）
            ScriptValue.Struct(v.typeName, fields, v.mutableFields)
        }
        is ScriptValue.ListV -> ScriptValue.ListV(v.items.map { deepCopy(it) }.toMutableList())
        is ScriptValue.OptionalV -> ScriptValue.OptionalV(v.value?.let { deepCopy(it) })
        is ScriptValue.ResultV -> ScriptValue.ResultV(v.isOk, v.value?.let { deepCopy(it) }, v.error?.let { deepCopy(it) })
        // Fun：闭包捕获语义（P6）第一版不深拷贝，保持原值
        else -> v
    }

    private fun evalStmt(stmt: Stmt, frame: ScriptFrame): ScriptValue {
        return when (stmt) {
            is VarStmt -> {
                val v = evalExpr(varUnwrapHits[stmt] ?: stmt.value, frame)
                val mutable = "mut" in stmt.annotations
                val ref = "ref" in stmt.annotations
                val bound = bindCopy(v, ref, sourceIsRef(stmt.value, frame))
                frame.declareVar(stmt.name, bound, mutable, ref)
                ScriptValue.Null
            }
            is AssignStmt -> {
                val v = evalExpr(stmt.value, frame)
                when (val target = stmt.target) {
                    is NameRef -> {
                        val bound = bindCopy(v, frame.isRef(target.name), sourceIsRef(stmt.value, frame))
                        frame.assignVar(target.name, bound)
                    }
                    is FieldExpr -> {
                        val obj = evalExpr(target.target, frame)
                        if (obj is ScriptValue.Struct) obj.fields[target.name] = v
                        else throw InterpreterError("不能对非结构体值访问字段 " + target.name)
                    }
                    else -> throw InterpreterError("赋值目标必须是变量名或字段访问")
                }
                ScriptValue.Null
            }
            is ExprStmt -> evalExpr(stmt.expr, frame)
            is ReturnStmt -> {
                val v = stmt.expr?.let { evalExpr(it, frame) } ?: ScriptValue.Null
                throw ReturnException(v)
            }
            is BecauseStmt, is ThereforeStmt, is PlusBecauseStmt, is AxiomStmt, is ByStmt -> ScriptValue.Null
            is IfPropStmt -> ScriptValue.Null
            is ExistIntroStmt, is ExistsElimStmt -> ScriptValue.Null
            is UncheckedStmt -> evalStmt(stmt.inner, frame)
            is FunStmt -> ScriptValue.Null
        }
    }

    private fun evalExpr(expr: Expr, frame: ScriptFrame): ScriptValue {
        return when (expr) {
            is IntLit -> {
                val n = expr.value.toLongOrNull()
                    ?: throw InterpreterError("非法整数: " + expr.value)
                ScriptValue.Num(n)
            }
            is FloatLit -> ScriptValue.Real(expr.value.toDouble())
            is StrLit -> ScriptValue.Str(expr.value)
            is NameRef -> frame.lookupVar(expr.name) ?: when (expr.name) {
                "true" -> ScriptValue.Bool(true)
                "false" -> ScriptValue.Bool(false)
                "null" -> ScriptValue.Null
                "None" -> ScriptValue.OptionalV(null)
                "Nil" -> ScriptValue.ListV()
                else -> frame.lookupFunctions(expr.name).takeIf { it.isNotEmpty() }?.let { funDeclsAsValue(it, frame) }
                    ?: throw InterpreterError("未定义的变量「" + expr.name + "」")
            }
            is BinExpr -> evalBin(expr.op, expr.left, expr.right, frame, expr.pos)
            is UniExpr -> evalUni(expr.op, expr.operand, frame)
            is CallExpr -> evalCall(expr, frame)
            is SymbolCallExpr -> evalSymCall(expr, frame)
            is BlockExpr -> evalBlock(expr, frame)
            is IfExpr -> evalIf(expr, frame)
            is WhenExpr -> evalWhen(expr, frame)
            is LambdaExpr -> evalLambda(expr, frame)
            is AnonFunExpr -> evalAnonFun(expr, frame)
            is FieldExpr -> {
                val obj = evalExpr(expr.target, frame)
                when (obj) {
                    is ScriptValue.Struct -> obj.fields[expr.name]
                        ?: methodPathValue(expr.name, obj, frame)
                        ?: throw InterpreterError("结构体 " + obj.typeName + " 无字段 " + expr.name)
                    else -> methodPathValue(expr.name, obj, frame)
                        ?: throw InterpreterError("不支持对 " + obj + " 访问字段 " + expr.name)
                }
            }
            is StructCtorExpr -> evalStructCtor(expr, frame)
            is SafeCallExpr -> evalSafeCall(expr, frame)
            is ElvisExpr -> evalElvis(expr, frame)
            is TypeTestExpr -> evalTypeTest(expr, frame)
            is TupleExpr -> ScriptValue.ListV(expr.items.map { evalExpr(it, frame) }.toMutableList())
            is InstExpr -> evalExpr(expr.target, frame)
            is VarExpr -> {
                val v = evalExpr(expr.value, frame)
                val mutable = "mut" in expr.annotations
                val ref = "ref" in expr.annotations
                val bound = bindCopy(v, ref, sourceIsRef(expr.value, frame))
                frame.declareVar(expr.name, bound, mutable, ref)
                bound
            }
            is ByExpr -> evalExpr(expr.target, frame)
            is FunRefExpr -> {
                // "o::f" → 绑定 self 的闭包：调用时求 f(o, ...args)
                val targetVal = evalExpr(expr.target, frame)
                val boundFn = object : ScriptFunction {
                    override val typeParams: List<String> = emptyList()
                    override val preps: List<String> = emptyList()
                    override val posts: List<String> = emptyList()
                    override val paramTypes: List<String> = listOf("Any")
                    override val retType: String = "Any"
                    override fun call(args: List<Any?>): Any? {
                        val boundArgs = listOf(targetVal) + args.map { fromHost(it) }
                        return try { toHost(callFunction(expr.name, boundArgs, frame, expr.pos)) }
                        catch (ex: ReturnException) { toHost(ex.value) }
                    }
                }
                ScriptValue.Fun(boundFn)
            }
            is TaskExpr, is FlowExpr -> throw InterpreterError("异步特性不在脚本中支持")
            is CastExpr -> evalCast(expr, frame)
            is QuantExpr -> ScriptValue.Null
            TopExpr, BotExpr -> ScriptValue.Null
            ReturnSym -> ScriptValue.Null
            is ParamRefExpr -> throw InterpreterError("参数引用不可在脚本运行时使用")
        }
    }
    // ============ 运算符 ============

    private fun evalBin(op: String, lx: Expr, rx: Expr, frame: ScriptFrame, pos: String): ScriptValue {
        val l = evalExpr(lx, frame)
        val r = evalExpr(rx, frame)
        return when (op) {
            "+" -> if (l is ScriptValue.Str || r is ScriptValue.Str) ScriptValue.Str(strOf(l) + strOf(r)) else arith(l, r, pos) { a, b -> a + b }
            "-" -> arith(l, r, pos) { a, b -> a - b }
            "*" -> arith(l, r, pos) { a, b -> a * b }
            "/" -> { val (a, b) = nums(l, r, pos); ScriptValue.Real(a / b) }
            "%" -> arith(l, r, pos) { a, b -> a % b }
            "=" -> throw InterpreterError("命题等式 '=' 是编译期概念，不参与运行时求值（值级比较请用 '=='）")
            "==" -> ScriptValue.Bool(structEquals(l, r))
            "!=", "≠" -> ScriptValue.Bool(!structEquals(l, r))
            "<", "<=", ">", ">=" -> cmp(l, r, pos) { a, b ->
                when (op) {
                    "<" -> a < b
                    "<=" -> a <= b
                    ">" -> a > b
                    else -> a >= b
                }
            }
            "&", "∧" -> ScriptValue.Bool(truthy(l) && truthy(r))
            "|", "∨" -> ScriptValue.Bool(truthy(l) || truthy(r))
            "->", "→" -> throw InterpreterError("蕴含是命题运算，不参与运行时求值")
            "<->", "↔" -> throw InterpreterError("等价是命题运算，不参与运行时求值")
            "∈", "in" -> evalIn(l, r, pos)
            ".." -> {
                val lo = num(l)?.toLong() ?: throw InterpreterError("区间下界需为数字: " + l)
                val hi = num(r)?.toLong() ?: throw InterpreterError("区间上界需为数字: " + r)
                rangeValue(lo, hi)
            }
            else -> throw InterpreterError("不支持中缀运算符「" + op + "」")
        }
    }

    private fun arith(l: ScriptValue, r: ScriptValue, pos: String, f: (Double, Double) -> Double): ScriptValue {
        val (a, b) = nums(l, r, pos)
        val res = f(a, b)
        return if (l is ScriptValue.Real || r is ScriptValue.Real) ScriptValue.Real(res)
        else ScriptValue.Num(res.toLong())
    }

    private fun nums(l: ScriptValue, r: ScriptValue, pos: String): Pair<Double, Double> {
        val a = num(l) ?: throw InterpreterError("运算符需要数字，实际 " + l + " (" + pos + ")")
        val b = num(r) ?: throw InterpreterError("运算符需要数字，实际 " + r + " (" + pos + ")")
        return a to b
    }

    private fun num(v: ScriptValue): Double? = when (v) {
        is ScriptValue.Num -> v.value.toDouble()
        is ScriptValue.Real -> v.value
        else -> null
    }

    /** 值 → 字符串（+ 拼接的降级：数字/布尔按字面，容器按 toStr 展示） */
    private fun strOf(v: ScriptValue): String = when (v) {
        is ScriptValue.Str -> v.value
        is ScriptValue.Num -> v.value.toString()
        is ScriptValue.Real -> v.value.toString()
        is ScriptValue.Bool -> v.value.toString()
        ScriptValue.Null -> "null"
        else -> v.toString()
    }

    private fun cmp(l: ScriptValue, r: ScriptValue, pos: String, f: (Double, Double) -> Boolean): ScriptValue {
        val (a, b) = nums(l, r, pos)
        return ScriptValue.Bool(f(a, b))
    }

    private fun evalIn(l: ScriptValue, r: ScriptValue, pos: String): ScriptValue {
        val x = num(l) ?: throw InterpreterError("in 左操作数需要数字: " + l)
        if (r is ScriptValue.ListV) return ScriptValue.Bool(r.items.any { num(it) == x })
        if (r is ScriptValue.Struct && r.typeName == "Range") {
            val lo = (r.fields["lo"] as? ScriptValue.Num)?.value ?: 0L
            val hi = (r.fields["hi"] as? ScriptValue.Num)?.value ?: 0L
            return ScriptValue.Bool(x.toLong() >= lo && x.toLong() <= hi)
        }
        throw InterpreterError("in 右操作数需要列表或区间: " + r)
    }

    /** 惰性 Range（v3.0 特性 3）：闭区间 [lo, hi]，不作为 List 预生成。 */
    private fun rangeValue(lo: Long, hi: Long): ScriptValue {
        val fields = LinkedHashMap<String, ScriptValue>()
        fields["lo"] = ScriptValue.Num(lo)
        fields["hi"] = ScriptValue.Num(hi)
        return ScriptValue.Struct("Range", fields)
    }

    /** Range 内建方法（__range 对象语义）：length/size/toList/contains/map/fold。 */
    private fun evalRangeMethod(name: String, range: ScriptValue.Struct, args: List<ScriptValue>, frame: ScriptFrame, pos: String): ScriptValue {
        val lo = (range.fields["lo"] as? ScriptValue.Num)?.value ?: 0L
        val hi = (range.fields["hi"] as? ScriptValue.Num)?.value ?: 0L
        val n = (hi - lo + 1).coerceAtLeast(0)
        return when (name) {
            "length", "size" -> ScriptValue.Num(n)
            "toList" -> ScriptValue.ListV((lo..hi).map { ScriptValue.Num(it) }.toMutableList())
            "contains" -> {
                val x = num(args.getOrNull(0) ?: ScriptValue.Null)?.toLong() ?: throw InterpreterError("contains 需要数字参数: " + pos)
                ScriptValue.Bool(x >= lo && x <= hi)
            }
            "map" -> {
                val f = (args.getOrNull(0) as? ScriptValue.Fun)?.fn ?: throw InterpreterError("map 需要函数参数: " + pos)
                ScriptValue.ListV((lo..hi).map { x -> callFunValue(f, listOf(ScriptValue.Num(x)), pos) }.toMutableList())
            }
            "fold" -> {
                val init = args.getOrNull(0) ?: ScriptValue.Null
                val f = (args.getOrNull(1) as? ScriptValue.Fun)?.fn ?: throw InterpreterError("fold 需要函数参数: " + pos)
                var acc = init
                // 编译模式 __range.fold 回调单参 f([i, acc])（单元组），分量顺序 [元素, 累积]
                for (x in lo..hi) {
                    val tuple = ScriptValue.ListV(mutableListOf(ScriptValue.Num(x), acc))
                    acc = callFunValue(f, listOf(tuple), pos)
                }
                acc
            }
            else -> throw InterpreterError("Range 不支持的方法「" + name + "」: " + pos)
        }
    }

    private fun truthy(v: ScriptValue): Boolean = when (v) {
        is ScriptValue.Bool -> v.value
        ScriptValue.Null -> false
        else -> true
    }

    /** 运行时结构相等（== / !=）：数值升格、容器递归、结构体/可选/结果按字段比较（P14） */
    private fun structEquals(l: ScriptValue, r: ScriptValue): Boolean = when {
        l is ScriptValue.Num && r is ScriptValue.Num -> l.value == r.value
        l is ScriptValue.Real && r is ScriptValue.Real -> l.value == r.value
        (l is ScriptValue.Num || l is ScriptValue.Real) && (r is ScriptValue.Num || r is ScriptValue.Real) ->
            num(l) == num(r)   // 数值升格：1 == 1.0
        l is ScriptValue.Str && r is ScriptValue.Str -> l.value == r.value
        l is ScriptValue.Bool && r is ScriptValue.Bool -> l.value == r.value
        l === ScriptValue.Null && r === ScriptValue.Null -> true
        l is ScriptValue.ListV && r is ScriptValue.ListV ->
            l.items.size == r.items.size && l.items.zip(r.items).all { (a, b) -> structEquals(a, b) }
        l is ScriptValue.Struct && r is ScriptValue.Struct ->
            l.typeName == r.typeName && l.fields.keys == r.fields.keys &&
                l.fields.all { (k, sv) -> structEquals(sv, r.fields[k]!!) }
        l is ScriptValue.OptionalV && r is ScriptValue.OptionalV -> optEquals(l.value, r.value)
        l is ScriptValue.ResultV && r is ScriptValue.ResultV ->
            l.isOk == r.isOk && optEquals(l.value, r.value) && optEquals(l.error, r.error)
        else -> l == r   // Fun 等按引用身份
    }

    private fun optEquals(a: ScriptValue?, b: ScriptValue?): Boolean = when {
        a == null && b == null -> true
        a == null || b == null -> false
        else -> structEquals(a, b)
    }

    private fun evalUni(op: String, operand: Expr, frame: ScriptFrame): ScriptValue {
        val v = evalExpr(operand, frame)
        return when (op) {
            "-" -> {
                val x = num(v) ?: throw InterpreterError("取负需要数字: " + v)
                if (v is ScriptValue.Real) ScriptValue.Real(-x) else ScriptValue.Num((-x).toLong())
            }
            "!", "¬" -> ScriptValue.Bool(!truthy(v))
            else -> throw InterpreterError("不支持一元运算符「" + op + "」")
        }
    }
    // ============ 调用 ============

    private fun evalCall(c: CallExpr, frame: ScriptFrame): ScriptValue {
        val callee = c.callee
        if (callee is FieldExpr) {
            val target = evalExpr(callee.target, frame)
            val methodName = callee.name
            val argVals = c.args.map { evalExpr(it, frame) }
            // v3.0 点号规则：字段优先（结构体含同名字段则取字段值调用），再方法糖（类型类字典 → 自由函数重载）
            if (target is ScriptValue.Struct && target.fields.containsKey(methodName)) {
                val fieldVal = target.fields[methodName]
                    ?: throw InterpreterError("字段 " + methodName + " 不存在")
                if (fieldVal is ScriptValue.Fun) return callFunValue(fieldVal.fn, argVals, c.pos)
                throw InterpreterError("字段 " + methodName + " 不是函数值，不能调用")
            }
            // v3.0 惰性 Range 内建方法（__range 对象语义，编译模式 rangeHits 同名特判）
            if (target is ScriptValue.Struct && target.typeName == "Range") {
                return evalRangeMethod(methodName, target, argVals, frame, c.pos)
            }
            val tag = typeTag(target)
            val implFn = lookupTraitMethod(methodName, tag)
            if (implFn != null) return invokeImplMethod(implFn, target, argVals, frame)
            return callFunction(methodName, listOf(target) + argVals, frame, c.pos, c.namedArgs.mapValues { (_, ex) -> evalExpr(ex, frame) })
        }
        if (callee is NameRef) {
            val name = callee.name
            // enum 构造子（§4.5 值体系）：产生对应 ScriptValue
            when (name) {
                "Some" -> return ScriptValue.OptionalV(evalExpr(c.args.getOrNull(0) ?: NameRef("null", c.pos), frame))
                "None" -> return ScriptValue.OptionalV(null)
                "Ok" -> return ScriptValue.ResultV(true, evalExpr(c.args.getOrNull(0) ?: NameRef("null", c.pos), frame), null)
                "Err" -> return ScriptValue.ResultV(false, null, evalExpr(c.args.getOrNull(0) ?: NameRef("null", c.pos), frame))
                "Nil" -> return ScriptValue.ListV()
                "Cons" -> {
                    val head = evalExpr(c.args.getOrNull(0) ?: NameRef("null", c.pos), frame)
                    val tail = evalExpr(c.args.getOrNull(1) ?: NameRef("null", c.pos), frame)
                    if (tail is ScriptValue.ListV) {
                        val items = mutableListOf(head)
                        items.addAll(tail.items)
                        return ScriptValue.ListV(items)
                    }
                    throw InterpreterError("Cons 尾部必须是列表，实际 " + tail)
                }
            }
            // 结构体构造：空括号 / 位置实参 / 命名实参均可
            val struct = lookupStruct(name)
            if (struct != null) {
                val positional: List<Pair<String, Expr>> = c.args.indices.map { i ->
                    (struct.fieldNames.getOrElse(i) { "_$i" }) to c.args[i]
                }
                return evalStructCtor(StructCtorExpr(name, positional + c.namedArgs.toList(), c.pos), frame)
            }
            // 变量持有函数值（var f = someFun; f(...)）→ 按函数值调用
            val asVar = frame.lookupVar(name)
            if (asVar is ScriptValue.Fun) {
                val argVals = c.args.map { evalExpr(it, frame) }
                return callFunValue(asVar.fn, argVals, c.pos)
            }
            val argVals = c.args.map { evalExpr(it, frame) }
            return callFunction(name, argVals, frame, c.pos, c.namedArgs.mapValues { (_, ex) -> evalExpr(ex, frame) })
        }
        val fnVal = evalExpr(c.callee, frame)
        if (fnVal is ScriptValue.Fun) {
            val argVals = c.args.map { evalExpr(it, frame) }
            return callFunValue(fnVal.fn, argVals, c.pos)
        }
        throw InterpreterError("不可调用: " + c.callee)
    }

    private fun callFunction(name: String, args: List<ScriptValue>, frame: ScriptFrame, pos: String, namedArgs: Map<String, ScriptValue> = emptyMap()): ScriptValue {
        // @vararg 可变元组名字特判：stdlib listOf/setOf 的 body 是占位，编译模式 codegen 同名特判打包（§决策 61/E-TUPLE-VARARG）
        if (name == "listOf") return ScriptValue.ListV(args.toMutableList())
        if (name == "setOf") return buildSet(args)
        val hostFn = hostFunctions[name]
        if (hostFn != null) {
            val hostArgs = args.map { toHost(it) }
            val res = hostFn(hostArgs)
            return fromHost(res)
        }
        val fns = frame.lookupFunctions(name)
        if (fns.isNotEmpty()) {
            val fn = pickOverload(fns, args)
            return invokeFunDecl(fn, applyNamedArgs(fn, args, namedArgs), frame)
        }
        throw InterpreterError("未定义的函数「" + name + "」")
    }

    /** 方法路径作值（H6）：rs.name（不加括号）→ 绑定 receiver 的闭包（对齐编译模式 funRefTargets） */
    private fun methodPathValue(name: String, receiver: ScriptValue, frame: ScriptFrame): ScriptValue? {
        val tag = typeTag(receiver)
        val implFn = try { lookupTraitMethod(name, tag) } catch (ex: InterpreterError) { throw ex }
        val hasFree = frame.lookupFunctions(name).isNotEmpty() || hostFunctions.containsKey(name)
        if (implFn == null && !hasFree) return null
        val boundFn = object : ScriptFunction {
            override val typeParams: List<String> = emptyList()
            override val preps: List<String> = emptyList()
            override val posts: List<String> = emptyList()
            override val paramTypes: List<String> = listOf("Any")
            override val retType: String = "Any"
            override fun call(args: List<Any?>): Any? {
                val svArgs = args.map { fromHost(it) }
                return try {
                    val r = if (implFn != null) invokeImplMethod(implFn, receiver, svArgs, frame)
                            else callFunction(name, listOf(receiver) + svArgs, frame, "<method-path>")
                    toHost(r)
                } catch (ex: ReturnException) { toHost(ex.value) }
            }
        }
        return ScriptValue.Fun(boundFn)
    }

    /** 命名参数（H4）：f(x = 1) 的命名通道按形参名落位到位置列表，不再静默丢成 Null */
    private fun applyNamedArgs(fn: FunDecl, positional: List<ScriptValue>, named: Map<String, ScriptValue>): List<ScriptValue> {
        if (named.isEmpty()) return positional
        val out = positional.toMutableList()
        while (out.size < fn.params.size) out.add(ScriptValue.Null)
        for ((k, v) in named) {
            val idx = fn.params.indexOfFirst { it.name == k }
            if (idx >= 0) out[idx] = v
        }
        return out
    }

    /** setOf 实参打包为 SetCons 链（stdlib Set 是结构枚举：SetCons(h, t)） */
    private fun buildSet(elems: List<ScriptValue>): ScriptValue {
        var acc: ScriptValue = ScriptValue.Struct("SetNil", LinkedHashMap())
        for (x in elems.reversed()) {
            val fields = LinkedHashMap<String, ScriptValue>()
            fields["_0"] = x
            fields["_1"] = acc
            acc = ScriptValue.Struct("SetCons", fields)
        }
        return acc
    }

    /** 以子帧绑定形参并执行 FunDecl 体 */
    private fun invokeFunDecl(fn: FunDecl, args: List<ScriptValue>, frame: ScriptFrame): ScriptValue {
        val child = ScriptFrame(frame)
        fn.params.forEachIndexed { i, p ->
            val av = if (i < args.size) args[i] else ScriptValue.Null
            val mutable = "mut" in p.annotations
            child.declareVar(p.name, av, mutable)
        }
        child.declareFunction(fn)
        return try {
            fn.body?.let { evalExpr(funBodyUnwrapHits[it] ?: it, child) } ?: ScriptValue.Null
        } catch (e: ReturnException) { e.value }
    }

    /** 类型类字典查找：按接收者运行时类型 tag 找同名方法。多候选立即报消歧错误（P4）。 */
    private fun lookupTraitMethod(methodName: String, tag: String): FunDecl? {
        val cands = methodImpls[methodName]?.filter { it.selfTypeName == tag } ?: return null
        if (cands.isEmpty()) return null
        if (cands.size > 1) {
            val origins = cands.joinToString(", ") { it.traitName }
            throw InterpreterError("方法 " + methodName + " 在类型 " + tag + " 上有多个类型类实现（" + origins + "），无法消歧")
        }
        return cands[0].fn
    }

    /** 执行类型类 impl 方法：self 首参 + 显式形参。
     *  H2（已知限制）：高阶类（F[_]，如 Functor 的 map(fa: F[A], f)）的 self 在 kind 应用位（fa），
     *  需走「参数全注入」（self 不作为独立 self 变量而是首形参）；当前按普通类型类 self 隐式处理。
     *  与 stdlib collection.subl 的 Functor 注释一致——HKT 点号式字典实参装配是语言侧待完善项，当前容器 map 由同名自由函数承载（不触发此路径）。 */
    private fun invokeImplMethod(fn: FunDecl, selfVal: ScriptValue, args: List<ScriptValue>, frame: ScriptFrame): ScriptValue {
        val child = ScriptFrame(frame)
        child.declareVar("self", selfVal)
        // impl 方法体内裸字段名解析为 self 的字段（Checker 的字段优先规则 / H-FIELD-SHADOW）；形参随后覆盖同名字段
        if (selfVal is ScriptValue.Struct) {
            for ((k, sv) in selfVal.fields) child.declareVar(k, sv)
        }
        fn.params.forEachIndexed { i, p ->
            child.declareVar(p.name, args.getOrNull(i) ?: ScriptValue.Null, "mut" in p.annotations)
        }
        child.declareFunction(fn)
        return try {
            fn.body?.let { evalExpr(funBodyUnwrapHits[it] ?: it, child) } ?: ScriptValue.Null
        } catch (e: ReturnException) { e.value }
    }

    /** 重载分派：同名容器函数（size/map/filter/…各容器一版）按首参运行时类型选实现 */
    private fun pickOverload(fns: List<FunDecl>, args: List<ScriptValue>): FunDecl {
        if (fns.size == 1) return fns[0]
        val tag = args.firstOrNull()?.let { typeTag(it) }
        return fns.firstOrNull { fn -> paramTypeMatches(fn.params.firstOrNull()?.type, tag) } ?: fns[0]
    }

    /** 运行时值 → 类型基名（重载分派的接收者标签） */
    private fun typeTag(v: ScriptValue): String = when (v) {
        is ScriptValue.ListV -> "List"
        is ScriptValue.OptionalV -> "Optional"
        is ScriptValue.ResultV -> "Result"
        is ScriptValue.Struct -> ctorToEnum[v.typeName] ?: v.typeName
        is ScriptValue.Num -> "Nat"
        is ScriptValue.Real -> "Rat"
        is ScriptValue.Str -> "Str"
        is ScriptValue.Bool -> "Bool"
        is ScriptValue.Null -> "Null"
        is ScriptValue.Fun -> "Fun"
    }

    /** 形参声明类型是否接受该接收者标签；类型变量（不属任何具体类型名）通配 */
    private fun paramTypeMatches(type: Type?, tag: String?): Boolean {
        if (type == null || tag == null) return true
        val nt = type as? NamedType ?: return true
        return if (nt.name !in concreteTypeNames()) true else nt.name == tag
    }

    private fun concreteTypeNames(): Set<String> =
        localStructs.keys + hostStructs.keys + ctorToEnum.values + setOf(
            "List", "Set", "Map", "Optional", "Result",
            "Nat", "Int", "Rat", "Str", "Bool", "Null", "Any",
        )

    private fun callFunValue(fn: ScriptFunction, args: List<ScriptValue>, pos: String): ScriptValue {
        val hostArgs = args.map { toHost(it) }
        return fromHost(fn.call(hostArgs))
    }

    private fun evalSymCall(c: SymbolCallExpr, frame: ScriptFrame): ScriptValue {
        val args = c.args.map { evalExpr(it, frame) }
        return when (c.op) {
            "<" -> ScriptValue.Bool((num(args[0]) ?: 0.0) < (num(args[1]) ?: 0.0))
            ">" -> ScriptValue.Bool((num(args[0]) ?: 0.0) > (num(args[1]) ?: 0.0))
            ">=" -> ScriptValue.Bool((num(args[0]) ?: 0.0) >= (num(args[1]) ?: 0.0))
            "<=" -> ScriptValue.Bool((num(args[0]) ?: 0.0) <= (num(args[1]) ?: 0.0))
            "==" -> ScriptValue.Bool(args[0] == args[1])
            "!=" -> ScriptValue.Bool(args[0] != args[1])
            else -> throw InterpreterError("不支持符号调用: " + c.op)
        }
    }

    // ============ 控制流 ============

    private fun evalBlock(b: BlockExpr, frame: ScriptFrame): ScriptValue {
        val child = ScriptFrame(frame)
        var last: ScriptValue = ScriptValue.Null
        for (s in b.stmts) {
            last = evalStmt(s, child)
        }
        return last
    }

    private fun evalIf(e: IfExpr, frame: ScriptFrame): ScriptValue {
        val cond = evalExpr(e.cond, frame)
        return if (truthy(cond)) evalExpr(e.thenBlock, frame)
        else e.elseBlock?.let { evalExpr(it, frame) } ?: ScriptValue.Null
    }

    private fun evalWhen(e: WhenExpr, frame: ScriptFrame): ScriptValue {
        // whenFrame：主题绑定与各分支都限定在 when 作用域内（不污染/遮蔽外层）
        val whenFrame = ScriptFrame(frame)
        val subject = e.subject?.let { evalExpr(it, whenFrame) }
        val subjectBinding = e.subjectBinding
        if (subjectBinding != null) {
            whenFrame.declareVar(subjectBinding, subject ?: ScriptValue.Null)
        }
        for (arm in e.arms) {
            // 每分支独立子帧：模式变量不泄漏到下一个分支 / 外层；parent 挂 whenFrame 以见主题绑定
            val armFrame = ScriptFrame(whenFrame)
            if (e.subject == null) {
                val condVal = arm.cond?.let { evalExpr(it, armFrame) }
                    ?: throw InterpreterError("无主题 when 的分支必须有 cond")
                if (!truthy(condVal)) continue
            } else if (!matchPattern(arm.pattern, subject!!, armFrame)) continue
            val guard = arm.guard
            if (guard != null && !truthy(evalExpr(guard, armFrame))) continue
            return evalExpr(arm.body, armFrame)
        }
        return ScriptValue.Null   // 不可判穷尽 → Null（语言语义：不可判穷尽的 when 固定返回 Null，不抛异常）
    }

    // ============ 模式匹配 ============

    private fun matchPattern(p: Pattern, subject: ScriptValue, frame: ScriptFrame): Boolean {
        return when (p) {
            is PatLit -> { val lit = evalExpr(p.value, frame); structEquals(lit, subject) }
            is PatBind -> { if (p.name != "_") frame.declareVar(p.name, subject); true }
            is PatElse -> true
            is PatCtor -> {
                // 无参且小写（非内建构造子）→ 绑定变量（Parser 把绑定变量与零参构造子同形输出，语义层按符号表判定）
                if (p.args.isEmpty() && isBindName(p.name)) {
                    frame.declareVar(p.name, subject)
                    true
                } else matchCtor(p, subject, frame)
            }
            is PatIs -> matchType(p, subject, frame)
            is PatTuple -> {
                if (subject !is ScriptValue.ListV) false
                else if (subject.items.size != p.items.size) false
                else p.items.zip(subject.items).all { (sp, sv) -> matchPattern(sp, sv, frame) }
            }
            is PatCond -> true
        }
    }

    /** 无参小写标识符（非内建构造子 true/false/null）→ 绑定变量 */
    private fun isBindName(name: String): Boolean =
        name != "true" && name != "false" && name != "null" &&
            name.isNotEmpty() && name[0].isLowerCase()

    private fun matchCtor(p: PatCtor, subject: ScriptValue, frame: ScriptFrame): Boolean {
        return when (p.name) {
            "true" -> subject is ScriptValue.Bool && subject.value
            "false" -> subject is ScriptValue.Bool && !subject.value
            "null" -> subject is ScriptValue.Null
            "Some" -> {
                if (subject !is ScriptValue.OptionalV || subject.value == null) false
                else if (p.args.isEmpty()) true
                else p.args.size == 1 && matchPattern(p.args[0], subject.value, frame)
            }
            "None" -> subject is ScriptValue.OptionalV && subject.value == null
            "Ok" -> {
                if (subject !is ScriptValue.ResultV || !subject.isOk) false
                else if (p.args.isEmpty()) true
                else p.args.size == 1 && matchPattern(p.args[0], subject.value!!, frame)
            }
            "Err" -> {
                if (subject !is ScriptValue.ResultV || subject.isOk) false
                else if (p.args.isEmpty()) true
                else p.args.size == 1 && matchPattern(p.args[0], subject.error!!, frame)
            }
            "Cons" -> {
                if (subject !is ScriptValue.ListV || subject.items.isEmpty()) false
                else if (p.args.size != 2) false
                else {
                    val head = subject.items[0]
                    val tail = ScriptValue.ListV(subject.items.subList(1, subject.items.size).toMutableList())
                    matchPattern(p.args[0], head, frame) && matchPattern(p.args[1], tail, frame)
                }
            }
            "Nil" -> subject is ScriptValue.ListV && subject.items.isEmpty()
            else -> {
                if (subject !is ScriptValue.Struct || subject.typeName != p.name) false
                else if (p.args.isEmpty()) true
                else {
                    val fns = subject.fields.keys.toList()
                    if (fns.size != p.args.size) false
                    else p.args.zip(fns).all { (sp, fn) -> matchPattern(sp, subject.fields[fn]!!, frame) }
                }
            }
        }
    }
    private fun matchType(p: PatIs, subject: ScriptValue, frame: ScriptFrame): Boolean {
        val tn = (p.type as? NamedType)?.name
        val ok = when (tn) {
            "Any" -> true
            "Nat", "Int" -> subject is ScriptValue.Num
            "Rat" -> subject is ScriptValue.Real || subject is ScriptValue.Num
            "Str" -> subject is ScriptValue.Str
            "Bool" -> subject is ScriptValue.Bool
            "Null" -> subject is ScriptValue.Null
            "List" -> subject is ScriptValue.ListV
            "Set" -> subject is ScriptValue.ListV   // 脚本里 Set 也是 ListV（SetCons 链）
            "Map" -> subject is ScriptValue.Struct   // 脚本里 Map 是 MapCons 链（Struct）
            null -> false
            else -> subject is ScriptValue.Struct && subject.typeName == tn
        }
        val bind = p.bind
        if (ok && bind != null) frame.declareVar(bind, subject)
        return ok
    }

    // ============ 函数值 ============

    /** 绑定 lambda 形参：圆括号单元组解构参数名形如 "(a,b)"（Parser 编码），接收单实参元组并拆解分量。 */
    private fun bindLambdaParams(child: ScriptFrame, names: List<String>, args: List<Any?>) {
        names.forEachIndexed { i, name ->
            if (name.startsWith("(") && name.endsWith(")")) {
                val parts = name.substring(1, name.length - 1).split(",").map { it.trim() }
                val tuple = fromHost(args.getOrNull(i))
                val items = (tuple as? ScriptValue.ListV)?.items ?: emptyList()
                parts.forEachIndexed { j, nm -> child.declareVar(nm, items.getOrNull(j) ?: ScriptValue.Null) }
            } else {
                child.declareVar(name, fromHost(args.getOrNull(i)))
            }
        }
    }

    private fun evalLambda(e: LambdaExpr, frame: ScriptFrame): ScriptValue {
        val fn = object : ScriptFunction {
            override val typeParams: List<String> = emptyList()
            override val preps: List<String> = emptyList()
            override val posts: List<String> = emptyList()
            override val paramTypes: List<String> = e.params.map { "Any" }
            override val retType: String = "Any"
            override fun call(args: List<Any?>): Any? {
                val child = ScriptFrame(frame)
                bindLambdaParams(child, e.params, args)
                return try { toHost(evalExpr(funBodyUnwrapHits[e.body] ?: e.body, child)) }
                catch (ex: ReturnException) { toHost(ex.value) }
            }
        }
        return ScriptValue.Fun(fn)
    }

    private fun evalAnonFun(e: AnonFunExpr, frame: ScriptFrame): ScriptValue {
        val fn = object : ScriptFunction {
            override val typeParams: List<String> = e.theory.filterIsInstance<TypeParam>().map { it.name }
            override val preps: List<String> = e.preps.map { it.toString() }
            override val posts: List<String> = e.posts.map { it.toString() }
            override val paramTypes: List<String> = e.params.map { it.type.render() }
            override val retType: String = e.retType?.render() ?: "Null"
            override fun call(args: List<Any?>): Any? {
                val child = ScriptFrame(frame)
                e.params.forEachIndexed { i, p -> child.declareVar(p.name, fromHost(args.getOrNull(i)), "mut" in p.annotations) }
                return try { toHost(e.body?.let { evalExpr(funBodyUnwrapHits[it] ?: it, child) } ?: ScriptValue.Null) }
                catch (ex: ReturnException) { toHost(ex.value) }
            }
        }
        return ScriptValue.Fun(fn)
    }

    /** 把顶层/局部声明函数名包装成函数值（§4.2 函数引用）。@host 函数委托宿主，普通函数求值体。 */
    private fun funDeclAsValue(fn: FunDecl, frame: ScriptFrame): ScriptValue.Fun {
        val sf = object : ScriptFunction {
            override val typeParams: List<String> = fn.theory.filterIsInstance<TypeParam>().map { it.name }
            override val preps: List<String> = fn.preps.map { it.toString() }
            override val posts: List<String> = fn.posts.map { it.toString() }
            override val paramTypes: List<String> = fn.params.map { it.type.render() }
            override val retType: String = fn.retType?.render() ?: "Null"
            override fun call(args: List<Any?>): Any? {
                if ("host" in fn.annotations) {
                    val hf = hostFunctions[fn.name]
                        ?: throw InterpreterError("宿主函数「" + fn.name + "」未注册实现")
                    return hf(args)
                }
                val child = ScriptFrame(frame)
                fn.params.forEachIndexed { i, p -> child.declareVar(p.name, fromHost(args.getOrNull(i)), "mut" in p.annotations) }
                child.declareFunction(fn)
                return try { toHost(fn.body?.let { evalExpr(funBodyUnwrapHits[it] ?: it, child) } ?: ScriptValue.Null) }
                catch (ex: ReturnException) { toHost(ex.value) }
            }
        }
        return ScriptValue.Fun(sf)
    }

    /** 函数名作值引用：单数直接包装；重载组构造「重载调度函数值」，调用时按实参运行时类型分派 */
    private fun funDeclsAsValue(fns: List<FunDecl>, frame: ScriptFrame): ScriptValue.Fun {
        if (fns.size == 1) return funDeclAsValue(fns[0], frame)
        val sf = object : ScriptFunction {
            override val typeParams: List<String> = fns.firstOrNull()?.theory?.filterIsInstance<TypeParam>()?.map { it.name } ?: emptyList()
            override val preps: List<String> = emptyList()
            override val posts: List<String> = emptyList()
            override val paramTypes: List<String> = emptyList()
            override val retType: String = "Any"
            override fun call(args: List<Any?>): Any? {
                val svArgs = args.map { fromHost(it) }
                val fn = pickOverload(fns, svArgs)
                return toHost(invokeFunDecl(fn, svArgs, frame))
            }
        }
        return ScriptValue.Fun(sf)
    }

    // ============ 结构体 ============

    private fun evalStructCtor(e: StructCtorExpr, frame: ScriptFrame): ScriptValue {
        val struct = lookupStruct(e.struct)
        if (struct == null) throw InterpreterError("未定义结构体 " + e.struct)
        val fields = LinkedHashMap<String, ScriptValue>()
        val mutables = struct.mutableFields.toMutableSet()
        for ((name, valueExpr) in e.assigns) {
            fields[name] = evalExpr(valueExpr, frame)
        }
        for (name in struct.fieldNames) {
            if (name !in fields) {
                val def = struct.fieldDefaults[name]
                    ?: throw InterpreterError("结构体 " + e.struct + " 缺字段 " + name + "（声明无默认值且构造未提供）")
                fields[name] = evalExpr(def, frame)
            }
        }
        return ScriptValue.Struct(e.struct, fields, mutables)
    }

    // ============ 空安全 ============

    private fun evalSafeCall(e: SafeCallExpr, frame: ScriptFrame): ScriptValue {
        val target = evalExpr(e.target, frame)
        if (target is ScriptValue.OptionalV && target.value == null) {
            return ScriptValue.OptionalV(null)
        }
        val inner = if (target is ScriptValue.OptionalV) target.value else target
        val argVals = e.args.map { evalExpr(it, frame) }
        val r = callFunction(e.name, listOf(inner!!) + argVals, frame, e.pos)
        return ScriptValue.OptionalV(r)
    }

    private fun evalElvis(e: ElvisExpr, frame: ScriptFrame): ScriptValue {
        val left = evalExpr(e.left, frame)
        if (left is ScriptValue.OptionalV && left.value == null) return evalExpr(e.right, frame)
        if (left is ScriptValue.OptionalV) return left.value!!
        return left
    }

    private fun evalTypeTest(e: TypeTestExpr, frame: ScriptFrame): ScriptValue {
        val subject = evalExpr(e.target, frame)
        val p = PatIs(e.type, null)
        return ScriptValue.Bool(matchType(p, subject, frame))
    }

    private fun evalCast(e: CastExpr, frame: ScriptFrame): ScriptValue {
        val subject = evalExpr(e.target, frame)
        val p = PatIs(e.type, null)
        return if (matchType(p, subject, frame)) ScriptValue.OptionalV(subject)
        else ScriptValue.OptionalV(null)
    }

    // ============ 值转换（§4.6） ============

    private fun toHost(v: ScriptValue): Any? = when (v) {
        is ScriptValue.Num -> v.value
        is ScriptValue.Real -> v.value
        is ScriptValue.Str -> v.value
        is ScriptValue.Bool -> v.value
        ScriptValue.Null -> null
        is ScriptValue.ListV -> v.items.map { toHost(it) }
        is ScriptValue.Struct -> v   // 保留 ScriptValue 身份（否则跨函数值参数往返后 typeName 丢失，字典分派错位）
        is ScriptValue.OptionalV -> if (v.value == null) null else toHost(v.value)
        is ScriptValue.ResultV -> v
        is ScriptValue.Fun -> v.fn
    }

    private fun fromHost(v: Any?): ScriptValue = when (v) {
        null -> ScriptValue.Null
        is ScriptValue -> v
        is ScriptFunction -> ScriptValue.Fun(v)
        is Boolean -> ScriptValue.Bool(v)
        is Long -> ScriptValue.Num(v)
        is Int -> ScriptValue.Num(v.toLong())
        is Double -> ScriptValue.Real(v)
        is Float -> ScriptValue.Real(v.toDouble())
        is String -> ScriptValue.Str(v)
        is List<*> -> ScriptValue.ListV(v.map { fromHost(it) }.toMutableList())
        is Map<*, *> -> {
            val m = LinkedHashMap<String, ScriptValue>()
            @Suppress("UNCHECKED_CAST")
            (v as Map<Any?, Any?>).forEach { (k, sv) -> m[k.toString()] = fromHost(sv) }
            ScriptValue.Struct("Map", m)
        }
        else -> ScriptValue.Str(v.toString())
    }
}

/** return 异常：用于从函数体提前返回 */
class ReturnException(val value: ScriptValue) : RuntimeException("return")

/** 宿主注入的结构体定义（§4.3） */
data class StructInfo(
    val name: String,
    val fieldTypes: Map<String, ScriptType> = emptyMap(),
    val props: List<String> = emptyList(),
    val mutableFields: List<String> = emptyList(),
    val annotations: List<Annotation> = emptyList(),
    /** 字段默认值（struct 声明里 字段: 类型 = 默认值）；构造时缺省字段按此求值（P12） */
    val fieldDefaults: Map<String, Expr> = emptyMap(),
) {
    val fieldNames: List<String> get() = fieldTypes.keys.toList()
}