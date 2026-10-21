package sugared.functor.checker

import sugared.functor.ast.*

/** 基本类型集（prelude 内建；决策 45：单值类型=Null，None 专属 Optional[T]；决策 52：Any 受限顶类型；决策 62：元组具名形态） */
/** v3.0 特性 4（受限版）：预定义**自动类型类**集合——对每个类型自动按需实例化（用户不可自定义，防消歧义/字典爆炸）。
 *  当前仅 Any：恒满足、零方法无字典（原硬编码 v2.1 语义，机制化为 autoTraits 一员）。 */
val autoTraits = setOf("Any")

val BASE_TYPES = setOf("Nat", "Int", "Rat", "Real", "Str", "Bool", "Null", "Nothing", "Any",
    "EmptyTuple", "SingleTuple",
    "Task", "Channel",   // v2.0 异步（决策 93）：内建泛类型类型（无用户声明）
    "Mutex",           // v2.0 异步（root doc《异步》）：异步互斥锁（无类型参数）
    "Flow",            // v2.0 异步（root doc《异步》）：惰性冷流（Flow[T]，形如 Channel）
    "Range",           // v3.0 特性 3：惰性区间 `..` 返回 Range[Int]
    "Json")              // v2.0 基本库（内建 JS 实现）：JSON 值（编译成 JS 原生 JSON 对象/数组/字面量）

/** 合成类型（v1 类型推导不完整时的占位：泛型未绑定、lambda、$ 命题变量等） */
fun syntheticT(reason: String): Type = namedT("(推导:$reason)")
fun Type.isSynthetic(): Boolean = this is NamedType && name.startsWith("(推导:") && args.isEmpty()

/** 类型里是否含**未解析的类型变量**（任意深度 NamedType 零实参、不是基类型/已声明类型/合成占位）。
 *  P2（决策 78）只豁免裸类型参数位；v4（core 类型类）扩展到嵌套位：flatMap 的期望返回 List[B]
 *  与 listOf[T] 回退签名泄漏的 List[T]，都属「由调用点/lambda 体反推」的变量位，报了是误报。 */
internal fun Checker.containsUnresolvedTp(t: Type): Boolean = when (t) {
    is NamedType ->
        (t.args.isEmpty() && t.name !in BASE_TYPES && t.name !in syms.enums &&
            t.name !in syms.structs && t.name !in syms.classes && !t.isSynthetic()) ||
            t.args.any { containsUnresolvedTp(it) }
    is FunType -> t.params.any { containsUnresolvedTp(it) } || containsUnresolvedTp(t.ret)
    is TupleType -> t.items.any { containsUnresolvedTp(it) }
    is MutType -> containsUnresolvedTp(t.inner)
    else -> false
}

/** 名义类型判定：仅 NamedType 首字母大写 或 跨模块限定类型（函数/元组类型不是名义类型，T1/决策 57） */
fun Type.isNominal(): Boolean =
    (this is NamedType && name.isNotEmpty() && name.first().isUpperCase() && !isSynthetic()) ||
        this is QualifiedType

/**
 * 非密封 when 的值类型（§49：不密封的模式匹配固定返回 Null）。
 * 带标记以便上层给专属报错"作为表达式的模式匹配没有密封"，而非含糊的类型不匹配。
 * 注意：非 synthetic（参与真实类型比对），仅与 Null 相容。
 */
fun unsealedT(): Type = namedT("(未密封)")
fun Type.isUnsealed(): Boolean = this is NamedType && name == "(未密封)" && args.isEmpty()

/** 内建非纯函数集（@impure 语义的唯一来源，见《草案思路》27） */
fun builtinimpure(name: String): FunDecl? = when (name) {
    "print" -> FunDecl(
        name = "print", annotations = listOf("impure"), theory = emptyList(),
        params = listOf(Param("v", syntheticT("任意"))),
        preps = emptyList(), retType = namedT("Null"), posts = emptyList(), body = null,
    )
    // ---- P3 IO（v1.0 计划 §5）：全部 @impure；v2.0 异步（异步 §6 阶段 6）：readFile/readLine/writeFile 也标 @async——挂起操作须在 async 上下文调用（main 天然 async，Task 体天然 async）----
    // P5（决策 81）：IO 失败可携带错误信息——readFile/writeFile/readLine 返回 Result（Ok/Err，core/collection/result.subl）
    "readLine" -> FunDecl("readLine", listOf("impure", "async"), emptyList(), emptyList(),
        emptyList(), namedT("Result", listOf(namedT("Str"), namedT("Str"))), emptyList(), null)
    "readFile" -> FunDecl("readFile", listOf("impure", "async"), emptyList(),
        listOf(Param("p", namedT("Str"))), emptyList(),
        namedT("Result", listOf(namedT("Str"), namedT("Str"))), emptyList(), null)
    "writeFile" -> FunDecl("writeFile", listOf("impure", "async"), emptyList(),
        listOf(Param("p", namedT("Str")), Param("s", namedT("Str"))), emptyList(),
        namedT("Result", listOf(namedT("Null"), namedT("Str"))), emptyList(), null)
    "getArgs" -> FunDecl("getArgs", listOf("impure"), emptyList(), emptyList(),
        emptyList(), namedT("List", listOf(namedT("Str"))), emptyList(), null)
    // v2.0 异步（异步 §6 阶段 6）：sleep(ms) 显式延时——@async @impure，codegen 用 JS Promise+setTimeout
    "sleep" -> FunDecl("sleep", listOf("impure", "async"), emptyList(),
        listOf(Param("ms", namedT("Nat"))), emptyList(),
        namedT("Null"), emptyList(), null)
    // ---- v2.0 标准库补全（本会话加）：IO 扩展 / Time / Random——JS 原生映射，codegen 特判 ----
    "appendFile" -> FunDecl("appendFile", listOf("impure", "async"), emptyList(),
        listOf(Param("p", namedT("Str")), Param("s", namedT("Str"))), emptyList(),
        namedT("Result", listOf(namedT("Null"), namedT("Str"))), emptyList(), null)
    "fileExists" -> FunDecl("fileExists", listOf("impure"), emptyList(),
        listOf(Param("p", namedT("Str"))), emptyList(), namedT("Bool"), emptyList(), null)
    "readDir" -> FunDecl("readDir", listOf("impure", "async"), emptyList(),
        listOf(Param("p", namedT("Str"))), emptyList(),
        namedT("Result", listOf(namedT("List", listOf(namedT("Str"))), namedT("Str"))), emptyList(), null)
    "timeNow" -> FunDecl("timeNow", listOf("impure"), emptyList(), emptyList(),
        emptyList(), namedT("Nat"), emptyList(), null)
    "rand" -> FunDecl("rand", listOf("impure"), emptyList(), emptyList(),
        emptyList(), namedT("Rat"), emptyList(), null)
    // ---- core.async 内建运行时层（root 无体签名，编译期 JS 特判）：调度/缓冲/唤醒/并发组合
    // 无法在语言层正确表达，全部 impure+async；表内签名 = 调用点缺声明时的回退签名。
    // delay：显式延时（毫秒）——sleep 的语义包装。
    "delay" -> FunDecl("delay", listOf("impure", "async"), emptyList(),
        listOf(Param("ms", namedT("Nat"))), emptyList(),
        namedT("Null"), emptyList(), null)
    // runBoth：并发跑完两件事（先启动 f，主路径继续 g，join 等 f 收尾）。
    "runBoth" -> FunDecl("runBoth", listOf("impure", "async"), emptyList(),
        listOf(Param("f", FunType(listOf(namedT("Null")), namedT("Null"))),
               Param("g", FunType(listOf(namedT("Null")), namedT("Null")))), emptyList(),
        namedT("Null"), emptyList(), null)
    // runSeq：先做完 f 再做 g（串行语义）。
    "runSeq" -> FunDecl("runSeq", listOf("impure", "async"), emptyList(),
        listOf(Param("f", FunType(listOf(namedT("Null")), namedT("Null"))),
               Param("g", FunType(listOf(namedT("Null")), namedT("Null")))), emptyList(),
        namedT("Null"), emptyList(), null)
    // Flow 收集三件套：collectToList（发送序保持成表）、collectCount（计数）、collectAll（谓词短路）。
    "collectToList" -> FunDecl("collectToList", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("fl", namedT("Flow", listOf(namedT("T"))))), emptyList(),
        namedT("List", listOf(namedT("T"))), emptyList(), null)
    "collectCount" -> FunDecl("collectCount", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("fl", namedT("Flow", listOf(namedT("T"))))), emptyList(),
        namedT("Nat"), emptyList(), null)
    "collectAll" -> FunDecl("collectAll", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("fl", namedT("Flow", listOf(namedT("T")))),
               Param("p", FunType(listOf(namedT("T")), namedT("Bool")))), emptyList(),
        namedT("Bool"), emptyList(), null)
    // Mutex 临界区：withMutex = m.withLock(f)；bumpCounter/bumpPair 是计数器的读-改-写复合操作
    // （counter 形参按 @mut T 回退签名——实际声明见 core.async.mutex 的 Counter 结构体）。
    "withMutex" -> FunDecl("withMutex", listOf("impure", "async"), emptyList(),
        listOf(Param("m", namedT("Mutex")),
               Param("f", FunType(listOf(namedT("Null")), namedT("Null")))), emptyList(),
        namedT("Null"), emptyList(), null)
    "bumpCounter" -> FunDecl("bumpCounter", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("m", namedT("Mutex")), Param("c", MutType(namedT("T")))), emptyList(),
        namedT("Null"), emptyList(), null)
    "bumpPair" -> FunDecl("bumpPair", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("m1", namedT("Mutex")), Param("m2", namedT("Mutex")),
               Param("c1", MutType(namedT("T"))), Param("c2", MutType(namedT("T")))), emptyList(),
        namedT("Null"), emptyList(), null)
    // Channel 配套四件套：channelFromList（建通道发完即关）、sendAll（逐一发送）、
    // channelDrain（收到关闭为止，FIFO）、channelPipe（src 收一个往 dst 发一个，src 关则关 dst）。
    "channelFromList" -> FunDecl("channelFromList", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("xs", namedT("List", listOf(namedT("T"))))), emptyList(),
        namedT("Channel", listOf(namedT("T"))), emptyList(), null)
    "sendAll" -> FunDecl("sendAll", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("ch", namedT("Channel", listOf(namedT("T")))),
               Param("xs", namedT("List", listOf(namedT("T"))))), emptyList(),
        namedT("Null"), emptyList(), null)
    "channelDrain" -> FunDecl("channelDrain", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("ch", namedT("Channel", listOf(namedT("T"))))), emptyList(),
        namedT("List", listOf(namedT("T"))), emptyList(), null)
    "channelPipe" -> FunDecl("channelPipe", listOf("impure", "async"), listOf(TypeParam("T", null)),
        listOf(Param("src", namedT("Channel", listOf(namedT("T")))),
               Param("dst", namedT("Channel", listOf(namedT("T"))))), emptyList(),
        namedT("Null"), emptyList(), null)
    else -> null
}

/** 内建纯函数（prelude）：元组构造（决策 62）+ 字符串内建（P1，决策 77）。注解为空 ⇒ 天然纯，无需注册 pure<>。 */
fun builtinPure(name: String): FunDecl? = when (name) {
    "emptyTuple" -> FunDecl("emptyTuple", emptyList(), emptyList(), emptyList(),
        emptyList(), namedT("EmptyTuple"), emptyList(), null)
    "singleTuple" -> FunDecl("singleTuple", emptyList(), listOf(TypeParam("T", null)),
        listOf(Param("a", namedT("T"))), emptyList(),
        namedT("SingleTuple", listOf(namedT("T"))), emptyList(), null)
    // ---- P1 字符串内建（v1.0 计划 §3）：JS 原生映射，codegen 特判 ----
    "concat" -> FunDecl("concat", emptyList(), emptyList(),
        listOf(Param("a", namedT("Str")), Param("b", namedT("Str"))),
        emptyList(), namedT("Str"), emptyList(), null)
    "length" -> FunDecl("length", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(), namedT("Nat"), emptyList(), null)
    "charAt" -> FunDecl("charAt", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("i", namedT("Nat"))),
        emptyList(), namedT("Str"), emptyList(), null)
    "substring" -> FunDecl("substring", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("a", namedT("Nat")), Param("b", namedT("Nat"))),
        emptyList(), namedT("Str"), emptyList(), null)
    "strCmp" -> FunDecl("strCmp", emptyList(), emptyList(),
        listOf(Param("a", namedT("Str")), Param("b", namedT("Str"))),
        emptyList(), namedT("Int"), emptyList(), null)
    "toStr" -> FunDecl("toStr", emptyList(), listOf(TypeParam("T", null)),
        listOf(Param("n", namedT("T"))), emptyList(), namedT("Str"), emptyList(), null)   // P8（决策 84）：泛型化——插值 desugar 对任意插值段恒包 toStr
    "parseNat" -> FunDecl("parseNat", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(),
        namedT("Optional", listOf(namedT("Nat"))), emptyList(), null)
    // ---- P10 浮点数 Rat（v1.0 计划 §12；决策 86 候选 A：IEEE double via JS Number）----
    // 类型上不限定实参（文档记限制：预期 Nat/Int 使用；运行时 Number() 语义），避免双签名复杂度
    "toRat" -> FunDecl("toRat", emptyList(), listOf(TypeParam("T", null)),
        listOf(Param("n", namedT("T"))), emptyList(), namedT("Rat", emptyList()), emptyList(), null)
    "toReal" -> FunDecl("toReal", emptyList(), listOf(TypeParam("T", null)),
        listOf(Param("n", namedT("T"))), emptyList(), namedT("Real", emptyList()), emptyList(), null)
    "parseRat" -> FunDecl("parseRat", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(),
        namedT("Optional", listOf(namedT("Rat"))), emptyList(), null)
    "panic" -> FunDecl("panic", emptyList(), emptyList(), emptyList(), emptyList(), namedT("Nothing"), emptyList(), null)
    // ---- P51：in 为普通函数（v3.0 V1 区间成员，点名特判 + 内建表登记供 findFun/纯度判定）----
    "in" -> FunDecl("in", emptyList(), listOf(TypeParam("T", null)),
        listOf(Param("x", syntheticT("任意")), Param("r", namedT("Range", listOf(namedT("T"))))),
        emptyList(), namedT("Bool", emptyList()), emptyList(), null)
    "abs" -> FunDecl("abs", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat", emptyList()), emptyList(), null)
    "floor" -> FunDecl("floor", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat", emptyList()), emptyList(), null)
    "ceil" -> FunDecl("ceil", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat", emptyList()), emptyList(), null)
    "pow" -> FunDecl("pow", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat")), Param("y", namedT("Rat"))), emptyList(),
        namedT("Rat", emptyList()), emptyList(), null)
    // ---- v2.0 标准库补全（本会话加）：字符串扩展 / Math 扩展——JS 原生映射，codegen 特判 ----
    "strUpper" -> FunDecl("strUpper", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(), namedT("Str"), emptyList(), null)
    "strLower" -> FunDecl("strLower", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(), namedT("Str"), emptyList(), null)
    "strTrim" -> FunDecl("strTrim", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(), namedT("Str"), emptyList(), null)
    "startsWith" -> FunDecl("startsWith", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("p", namedT("Str"))), emptyList(),
        namedT("Bool"), emptyList(), null)
    "endsWith" -> FunDecl("endsWith", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("p", namedT("Str"))), emptyList(),
        namedT("Bool"), emptyList(), null)
    "strReplace" -> FunDecl("strReplace", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("a", namedT("Str")), Param("b", namedT("Str"))), emptyList(),
        namedT("Str"), emptyList(), null)
    "strSplit" -> FunDecl("strSplit", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("sep", namedT("Str"))), emptyList(),
        namedT("List", listOf(namedT("Str"))), emptyList(), null)   // List 数组化：直接返回 JS 数组
    "strIndexOf" -> FunDecl("strIndexOf", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str")), Param("p", namedT("Str"))), emptyList(),
        namedT("Optional", listOf(namedT("Nat"))), emptyList(), null)
    "mathMin" -> FunDecl("mathMin", emptyList(), emptyList(),
        listOf(Param("a", namedT("Rat")), Param("b", namedT("Rat"))), emptyList(),
        namedT("Rat"), emptyList(), null)
    "mathMax" -> FunDecl("mathMax", emptyList(), emptyList(),
        listOf(Param("a", namedT("Rat")), Param("b", namedT("Rat"))), emptyList(),
        namedT("Rat"), emptyList(), null)
    "mathClamp" -> FunDecl("mathClamp", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat")), Param("lo", namedT("Rat")), Param("hi", namedT("Rat"))),
        emptyList(), namedT("Rat"), emptyList(), null)
    "mathRound" -> FunDecl("mathRound", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat"), emptyList(), null)
    "mathSin" -> FunDecl("mathSin", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat"), emptyList(), null)
    "mathCos" -> FunDecl("mathCos", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat"), emptyList(), null)
    "mathTan" -> FunDecl("mathTan", emptyList(), emptyList(),
        listOf(Param("x", namedT("Rat"))), emptyList(), namedT("Rat"), emptyList(), null)
    // ---- v2.0 基本库（内建 JS 实现）：JSON —— 编译成 JS 原生 JSON.parse/对象访问，失败返回 None（Optional 包装）----
    "jsonParse" -> FunDecl("jsonParse", emptyList(), emptyList(),
        listOf(Param("s", namedT("Str"))), emptyList(),
        namedT("Optional", listOf(namedT("Json"))), emptyList(), null)
    "jsonGet" -> FunDecl("jsonGet", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json")), Param("k", namedT("Str"))), emptyList(),
        namedT("Optional", listOf(namedT("Json"))), emptyList(), null)
    "jsonAt" -> FunDecl("jsonAt", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json")), Param("i", namedT("Nat"))), emptyList(),
        namedT("Optional", listOf(namedT("Json"))), emptyList(), null)
    "jsonLen" -> FunDecl("jsonLen", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Optional", listOf(namedT("Nat"))), emptyList(), null)
    "jsonStr" -> FunDecl("jsonStr", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Optional", listOf(namedT("Str"))), emptyList(), null)
    "jsonNum" -> FunDecl("jsonNum", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Optional", listOf(namedT("Rat"))), emptyList(), null)
    "jsonBool" -> FunDecl("jsonBool", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Optional", listOf(namedT("Bool"))), emptyList(), null)
    "jsonIsNull" -> FunDecl("jsonIsNull", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Bool"), emptyList(), null)
    "jsonIsArr" -> FunDecl("jsonIsArr", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Bool"), emptyList(), null)
    "jsonToStr" -> FunDecl("jsonToStr", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Str"), emptyList(), null)
    // v2.0 基本库：JSON 数组 → List[Json]。List 数组化后零转换（底层同为 JS 数组），非数组返回 None
    "jsonToList" -> FunDecl("jsonToList", emptyList(), emptyList(),
        listOf(Param("j", namedT("Json"))), emptyList(),
        namedT("Optional", listOf(namedT("List", listOf(namedT("Json"))))), emptyList(), null)
    else -> null
}

/**
 * @root 白名单（v2.0 基本库机制）：允许用户以 `@root fun 名字(...)` 声明且**不写函数体**的内建函数名集合。
 * 语义：@root 声明 = 告知编译器「该签名对应编译期内建 JS 实现」，codegen 按名字特判翻译（Types.kt 内建表 + JsCodeGen.call()）。
 * 守卫：仅白名单内名字允许 @root（否则 E-ROOT-NOT-ALLOWED）；有 @root 才允许无体（否则 E-FUN-NO-BODY）。
 * 名字全集 = builtinimpure + builtinPure 的键（与 codegen 特判一一对应）。
 */
val ROOT_WHITELIST: Set<String> = setOf(
    // builtinimpure（IO/副作用入口）
    "print", "readLine", "readFile", "writeFile", "getArgs", "sleep",
    "appendFile", "fileExists", "readDir", "timeNow", "rand",
    // core.async 内建运行时层：delay/runBoth/runSeq（任务组合）、collect*（Flow 收集）、
    // withMutex/bump*（Mutex 临界区）、channel*（Channel 配套）——全部 @impure @async，
    // 白名单与表一一对应，codegen 编译期 JS 特判（Channel/Mutex/Flow 小运行时）。
    "delay", "runBoth", "runSeq",
    "collectToList", "collectCount", "collectAll",
    "withMutex", "bumpCounter", "bumpPair",
    "channelFromList", "sendAll", "channelDrain", "channelPipe",
    // builtinPure：元组 / 字符串（P1 + v2.0 补全）/ 浮点（P10）/ Math（v2.0 补全）/ JSON（v2.0）
    "emptyTuple", "singleTuple",
    "concat", "length", "charAt", "substring", "strCmp", "toStr", "parseNat",
    "strUpper", "strLower", "strTrim", "startsWith", "endsWith", "strReplace", "strSplit", "strIndexOf",
    "mathMin", "mathMax", "mathClamp", "mathRound", "mathSin", "mathCos", "mathTan",
    "toReal", "toRat", "parseRat", "abs", "floor", "ceil", "pow",
    "jsonParse", "jsonGet", "jsonAt", "jsonLen", "jsonStr", "jsonNum", "jsonBool",
    "jsonIsNull", "jsonIsArr", "jsonToStr", "jsonToList",
)

/** core.async 内建运行时层：调用这些 @root 内建即用到对应运行时——checker 在调用点置位，codegen 注入小运行时 class。
 *  与 ROOT_WHITELIST 交叠部分按名匹配；用户自定义同名函数不参与特判（白名单门 + codegen 按名特判同源）。 */
val CHANNEL_BUILTINS = setOf("channelFromList", "sendAll", "channelDrain", "channelPipe")
val MUTEX_BUILTINS = setOf("withMutex", "bumpCounter", "bumpPair")
val FLOW_BUILTINS = setOf("collectToList", "collectCount", "collectAll")

/** 类型 → 命题层的项表示；函数/元组类型用哨兵名（v1 不参与命题推理） */
fun typeToExpr(t: Type): Expr = when (t) {
    is NamedType -> if (t.args.isEmpty()) NameRef(t.name)
                    else InstExpr(NameRef(t.name), t.args.map { typeToExpr(it) })
    is QualifiedType -> NameRef(t.name)
    is FunType -> NameRef("(fn)")
    is TupleType -> NameRef("(tuple)")
    is UnionType -> NameRef("(union)")
    is MutType -> NameRef("(mut)")
    is AtomicType -> NameRef("(atomic)")
}

/** 类型相等（T1：名义类型同名义相等；函数/元组按结构相等） */
fun typeEq(a: Type, b: Type): Boolean = when {
    a is NamedType && b is NamedType ->
        a.name == b.name && a.args.size == b.args.size && a.args.zip(b.args).all { typeEq(it.first, it.second) }
    a is FunType && b is FunType ->
        a.params.size == b.params.size && a.purity == b.purity &&
            a.params.zip(b.params).all { typeEq(it.first, it.second) } && typeEq(a.ret, b.ret)
    a is TupleType && b is TupleType ->
        a.items.size == b.items.size && a.items.zip(b.items).all { typeEq(it.first, it.second) }
    a is MutType && b is MutType -> typeEq(a.inner, b.inner)   // M-1：@mut T 与 T 是不同类型
    // @atomic T 是透明标记（root doc《异步》）：读写原子，运行时无额外包装 → 与内层类型等价
    a is AtomicType && b is AtomicType -> typeEq(a.inner, b.inner)
    a is AtomicType -> typeEq(a.inner, b)
    b is AtomicType -> typeEq(a, b.inner)
    else -> false
}



data class CtorInfo(val enum: EnumDecl, val fields: List<Type>)
data class MethodEntry(
    val self: Type,
    val fn: FunDecl,
    val annotations: List<String>,
    val trait: String = "",
    /** P0：声明该 impl 的模块 absPath（字典名模块前缀用；单文件模式为空串） */
    val modulePath: String = "",
)

/** 类型类字典解析结果（决策 60，T5）：方法名 + 实参类型 → 唯一实例 */
data class DictResolution(val entry: MethodEntry, val dictName: String)

/** 全局符号表：一次收集，全文件可见（Rust 骨架的实体语义基准） */
class Symbols {
    val structs = LinkedHashMap<String, StructDecl>()
    val enums = LinkedHashMap<String, EnumDecl>()
    val ctors = LinkedHashMap<String, CtorInfo>()
    val classes = LinkedHashMap<String, ClassDecl>()
    val funs = LinkedHashMap<String, FunDecl>()
    /** 定理函数：`@theorem` 显式标注的函数（名 → 声明），参与 `∴` 的定理搜索。
     *  判据见 `Diffusion.isTheoremFun`：必须显式标注 @theorem（规范第四卷 §7.2）；有体定理照常生成运行时代码 */
    val theorems = LinkedHashMap<String, FunDecl>()
    /** 根文档：自定义命题 `def p<a,b> [↔ <等价式>]`（名 → 声明），求解时按名展开 */
    val propDefs = LinkedHashMap<String, PropDefDecl>()
    /** v3.0 特性 6：core.classic 已挂载标志（文件内任一声明带 @classic 注解即挂载）——if[p] 排中律前置 */
    var classicMounted = false
    /** v2.0：同名不同**首参类型**的自由函数重载（funs 含首个声明，其余候选存此）。
     *  集合方法名（map/contains/size…）在不同容器类型上共用——方法糖按接收者类型分派。 */
    val funOverloads = LinkedHashMap<String, MutableList<FunDecl>>()
    /** v1.1：表达式体省略返回类型时的**推导**返回类型表（函数名 → 类型），供调用点查询 */
    val inferredRets = LinkedHashMap<String, Type>()
    val methods = LinkedHashMap<String, MutableList<MethodEntry>>()
    /** 类型类声明里的候选方法名（决策 60，T5）：无 impl 实例时用于把调用判为 E-NO-INSTANCE */
    val traitMethods = LinkedHashSet<String>()
    val typeParamNames = LinkedHashMap<String, List<String>>()   // 声明名 → 其类型参数
    val aliases = LinkedHashMap<String, TypeAliasDecl>()          // 类型别名（决策 30）
    /** P6（决策 82）：类型类 → 实现条目（含声明模块路径）。约束 `T: Show` 的字典解析用（I-22 复用可见性过滤） */
    val implsByTrait = HashMap<String, MutableList<ImplEntry>>()
    /** v2.1 子类型类：类型类名 → 父类型类引用列表（`class Sub : Base` 的 parents；约束蕴含/子类覆盖用） */
    val clsParents = HashMap<String, List<Type>>()
    /** v3.0 特性 2：委托 impl（by field）的展开方法表（ImplDecl → 含转发的完整成员列表），供 codegen 字典生成 */
    val delegatedImpls = LinkedHashMap<ImplDecl, List<FunDecl>>()

    fun findFun(name: String): FunDecl? = funs[name] ?: builtinimpure(name) ?: builtinPure(name) ?: builtinAndroid(name)

    /** 别名展开（有限深度防环；v1 无递归别名；函数/元组类型不参与，T1）。
     *  P0：跨模块限定类型折叠为同简单名的 NamedType（校验由 checkTypeResolvable 负责）。 */
    fun expand(t: Type): Type = expand(t, 0)

    private fun expand(t: Type, depth: Int): Type = when (t) {
        is QualifiedType -> namedT(t.name, t.args.map { expand(it, depth + 1) })
        is MutType -> MutType(expand(t.inner, depth + 1))
        is AtomicType -> AtomicType(expand(t.inner, depth + 1))
        is UnionType -> UnionType(t.variants.map { expand(it, depth + 1) })
        is FunType -> FunType(t.params.map { expand(it, depth + 1) }, expand(t.ret, depth + 1), t.purity, t.theory, t.context)
        is TupleType -> TupleType(t.items.map { expand(it, depth + 1) })
        is NamedType -> expandNamed(t, depth)
    }

    private fun expandNamed(t: NamedType, depth: Int): Type {
        // M2：递归别名经复合类型包装（type A = @mut A）时，steps 计数会因每次经 expand() 重新进入而失效——
        // 用贯通全展开的 depth 计数兜底，超限即停止，避免栈溢出。
        if (depth >= 16) return t
        var cur: Type = t; var steps = 0
        while (cur is NamedType && cur.name in aliases && steps < 16) {
            val decl = aliases.getValue(cur.name)
            val tps = decl.theory.filterIsInstance<TypeParam>().map { it.name }
            cur = decl.target.substT(tps.zip(cur.args).toMap())
            steps++
        }
        // 别名目标若为复合类型（mut/atomic/union/fun/tuple 别名，v1 解析不产生但防御），继续穿透内层
        return if (cur is NamedType) cur else expand(cur, depth + 1)
    }

    /** 具名类型解析：T 参数引用返回 null；函数/元组类型不是名义类型（T1） */
    fun baseOf(t: Type): String? = when {
        t !is NamedType -> null
        t.name in BASE_TYPES -> t.name
        structs.containsKey(t.name) || enums.containsKey(t.name) ||
            classes.containsKey(t.name) || aliases.containsKey(t.name) -> t.name
        else -> null
    }
}

/** 表达式的类型参数替换（前置条件里的 `v` 解出为 Int 后，`p<v>` → `p<Int>`） */
fun substExprType(e: Expr, sub: Map<String, Type>): Expr = when (e) {
    is NameRef -> if (e.name in sub) typeToExpr(sub.getValue(e.name)) else e
    is BinExpr -> e.copy(left = substExprType(e.left, sub), right = substExprType(e.right, sub))
    is UniExpr -> e.copy(operand = substExprType(e.operand, sub))
    is CallExpr -> e.copy(callee = substExprType(e.callee, sub), args = e.args.map { substExprType(it, sub) })
    is InstExpr -> e.copy(target = substExprType(e.target, sub), terms = e.terms.map { substExprType(it, sub) })
    is FieldExpr -> e.copy(target = substExprType(e.target, sub))
    is SymbolCallExpr -> e.copy(args = e.args.map { substExprType(it, sub) })
    else -> e
}

fun substPropType(p: Prop, sub: Map<String, Type>): Prop = when (p) {
    is PAtom -> PAtom(p.head, p.terms.map { substExprType(it, sub) })
    is PNot -> PNot(substPropType(p.p, sub))
    is PAnd -> PAnd(substPropType(p.a, sub), substPropType(p.b, sub))
    is POr -> POr(substPropType(p.a, sub), substPropType(p.b, sub))
    is PImp -> PImp(substPropType(p.a, sub), substPropType(p.b, sub))
    is PIff -> PIff(substPropType(p.a, sub), substPropType(p.b, sub))
    is PForall -> PForall(p.names, substPropType(p.body, sub.filterKeys { it !in p.names }))
    is PExists -> PExists(p.names, substPropType(p.body, sub.filterKeys { it !in p.names }))
    is PExists1 -> PExists1(p.names, substPropType(p.body, sub.filterKeys { it !in p.names }))
    else -> p
}

/** P6（决策 82）：一个 impl 条目的轻量登记（跨模块可见性过滤 + 字典名拼装用） */
data class ImplEntry(val name: String, val self: Type, val traitName: String, val modulePath: String)
