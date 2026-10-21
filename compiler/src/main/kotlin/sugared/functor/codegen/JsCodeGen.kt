package sugared.functor.codegen

import sugared.functor.ast.*
import sugared.functor.checker.MODULE_METHOD_MARKER
import sugared.functor.checker.dictNameOf
import sugared.functor.checker.jsMangle
import sugared.functor.checker.fnTag
import sugared.functor.checker.moduleJsName

class CodegenError(message: String) : RuntimeException("[代码生成] $message")

/**
 * JS 代码生成器（M4）：源到源、零运行时库、零成本擦除（决策 22）。
 * 命题层（类型/理论参数/上下文/axiom/by/命题算符）全部擦除；值层映射到原生 JS。
 * 纯语法变换，不依赖符号表——struct/enum 工厂由声明生成，prelude 的 Bool(true/false) 按名字特判为字面量。
 *
 * v1 限制（文档标注）：
 *  - when 的 `is T` 模式：基本类型用 typeof 近似，其余保守生成 true
 *  - impl 方法按函数名生成，跨 trait 同名会冲突（单态化留待后续）
 *  - `==`/`!=` 生成结构相等调用 `__eq`（递归比较 tag 与全字段），仅在源码用到时注入
 *
 * P0（M8 合并编译）：顶层名字一律带模块前缀（根模块无前缀，用户拍板 Q4a）；
 * 全局引用的 mangle 结果由 Checker 按引用点实例留痕（moduleHits）传入——
 * codegen 不再猜测"裸名 = 本模块符号"，跨模块/同模块非根调用都能拿到正确前缀。
 */
class JsCodeGen {
    private val sb = StringBuilder()
    private var indent = 0
    /** 已知构造子名（含 prelude），用于区分模式里的裸名是构造子还是绑定变量 */
    private val ctorNames = LinkedHashSet<String>()
    /** v2.1：裸构造子名 → 所在枚举数量（>1 时裸名工厂不生成，强制 E::T 消歧） */
    private val ctorOwnerCounts = HashMap<String, Int>()
    /** when 主题临时名计数，避免嵌套 when 的 __s 变量冲突 */
    private var subjCounter = 0
    /** 零参构造子名：值引用时生成工厂调用 Name() */
    private val nullaryCtors = LinkedHashSet<String>()
    /** 预扫描命中位：源码含 `==`/`!=` 时注入结构相等助手，否则产物零运行时（决策 22） */
    private var eqHit = false

    /** 带 @list 形参的函数名（键 = 模块前缀全名）→ 该形参的位置（P0 键带前缀防跨模块同名） */
    private val listParamPos = HashMap<String, Int>()

    /** 类型类方法调用的字典留痕（决策 60，T5；O2 改键）：调用点实例 → 字典名，由语义层传入。
     *  IdentityHashMap 语义：同构但不同实例的调用点各自独立。 */
    private var dicts: Map<CallExpr, String> = emptyMap()
    /** P6（决策 82）：约束槽方法调用留痕（调用点实例 → 槽名）——`d_Show_T.m(d_Show_T, self, ...)` */
    private var consDicts: Map<CallExpr, String> = emptyMap()
    /** P6：带约束泛型函数的调用点字典实参（按声明顺序的字典名），调用前插 */
    private var dictSubArgs: Map<CallExpr, List<String>> = emptyMap()
    /** P8（决策 84）：命名参数规范化后的实参留痕（调用点实例 → 按形参声明顺序的实参列表） */
    private var namedArgOrders: Map<CallExpr, List<Expr>> = emptyMap()
    /** v1.1 集合方法糖留痕：调用点实例 → 重排实参（接收者前置），codegen 直接按此生成 */
    private var sugarArgs: Map<CallExpr, List<Expr>> = emptyMap()
    /** P0（M8）：全局引用的 JS 名留痕（键 = 引用表达式节点；限定方法调用 = MODULE_METHOD_MARKER） */
    private var moduleHits: Map<Expr, String> = emptyMap()
    private var funRefTargets: Map<Expr, String> = emptyMap()
    private var funRefFree: Set<Expr> = emptySet()
    /** root doc《副作用与可变性控制》85-144：可变视图深拷贝留痕（绑定/赋值语句 → 需包 __dc） */
    private var deepCopyHits: Map<Node, Boolean> = emptyMap()
    /** P9：core 标准库数组化 List 方法调用留痕（CallExpr → 方法名），据此映射 JS 原生数组方法，不再硬编码模块前缀 */
    private var listMethodHits: Map<CallExpr, String> = emptyMap()
    /** v3.0 特性 2：委托 impl 的展开方法表（checker 传参） */
    private var delegatedImpls: Map<ImplDecl, List<FunDecl>> = emptyMap()
    private var lambdaDestructKeys: Map<Expr, List<List<String>?>> = emptyMap()
    /** P0（M8）：当前生成单元的模块 absPath（根 = ""） */
    private var currentPrefix = ""
    /** O2（决策 68）：当前正在生成的字典方法所属结构体的字段名（裸用→ __self.字段） */
    private var methodFields: Set<String> = emptySet()
    /** O2：当前方法体内局部声明过的名字（遮蔽字段者不重写；v1 粗粒度=体内全收集） */
    private var methodLocals: Set<String> = emptySet()
    /** P6（决策 82）：当前是否在生成 impl 字典方法体（`self` → `__self` 的映射开关；基类型 impl 无字段，methodFields 判据不可靠） */
    private var dictMethod = false
    /** 结构体字段名索引（从 AST 声明收集，codegen 不读符号表；P0：同名跨模块取最后者，见《模块系统.md》限制） */
    private val structFields = HashMap<String, List<String>>()
    /** v2.0：重载函数名集合（同名 >1 声明）——这些名字在 JS 里需按首参标签唯一化，避免同名覆盖 */
    private val overloadedFns = HashSet<String>()
    /** HKT（《高阶类型.md》HKT-D2）：kind 类型类名集合（类声明 theory 含 arity>0 形参，如 Functor[F[_]]）。
     *  其字典方法签名**无 __self**（self 显式在形参表 fa 位）——`(d, f, fa...)`，与零参类型类 `(d, __self)` 区分。 */
    private val kindTraits = HashSet<String>()
    /** v2.0：每模块的重载函数名集合（模块路径 → 名），声明侧按此加后缀（与 Checker 侧 moduleHits 判定一致） */
    private val overloadsByUnit = HashMap<String, Set<String>>()
    /** v2.0 异步（决策 93）：@async 函数体内的挂起点调用（CallExpr → true），生成 `await fn(...)` */
    private var awaitHits: Map<CallExpr, Boolean> = emptyMap()
    /** v2.0 异步（§5.4）：源码用到 Channel 时注入 Channel 运行时 class（唯一的运行时组件，几十行） */
    private var channelUsed = false
    /** v2.0 异步（root doc《异步》）：源码用到 Mutex 时注入 Mutex 运行时 class */
    private var mutexUsed = false
    /** v2.0 异步（root doc《异步》）：源码用到 @atomic（原子类型）时注入 atomic 注解运行时 */
    private var atomicUsed = false
    /** v2.0 异步（root doc《异步》）：源码用到 Flow 时注入 Flow 冷流运行时 class */
    private var flowUsed = false
    /** v3.0 特性 3：Range 内建方法调用点 → 方法名（成员访问生成） */
    private var rangeHits: Map<CallExpr, String> = emptyMap()
    /** v3.0 特性 3：用到 `..` 值或 Range 方法 → 注入 __range 小运行时（与 Channel 同层） */
    private var rangeUsed = false
    /** v2.0 异步（CH-5）：receive 智能转换（CallExpr → true）→ codegen 解包 `(…)[0]` */
    private var receiveSmartHits: Map<CallExpr, Boolean> = emptyMap()
    /** 安卓内建（android doc/安卓标准库.md）：@android 无体声明调用点的编译期桥接名集合。
     *  桌面/node 环境走 __dshAndroid 注入桩（空操作，保证产物可运行）；android webview 内由
     *  原生支持器注入同名全局函数，实现 log/toast/layout/生命周期/重组通知。 */
    private var androidUsed = false
    /** v2.0 异步（root doc《异步》）：Flow 体 emit(v) 调用点 → codegen 生成 yield v */
    private var flowEmitHits: Map<CallExpr, Boolean> = emptyMap()
    /** HKT（《高阶类型.md》HKT-D2）：带参类型类方法调用点 → kind 应用位下标（点号式 `xs.map(f)` 的 fa 位）。
     *  字典签名 (d, f, fa) 无 __self——接收者按此重排，而非首参位。 */
    private var hktKindPosHits: Map<CallExpr, Int> = emptyMap()
    /** v2.0 基本库（内建 JS 实现）：**数组化 List** 的构造子名集合（名为 `List` 的枚举的构造子）。
     *  List 在 JS 层直接表示为数组：`Nil()` → `[]`、`Cons(h,t)` → `[h, ...t]`，
     *  模式解构 `Nil ->` / `Cons(h,t) ->` 翻译为 `length`/`slice`——
     *  语义层（enum List 声明、when 检查、Optional 枚举）完全不动，纯 codegen 表示变换。
     *  仅按枚举名 List 收集，用户自定义 `enum Foo { Nil() }` 不受影响；单文件无 core 标准库时集合为空。 */
    private val listCtorSet = LinkedHashSet<String>()
    /** 修复（#3）：@theorem 定理函数的 JS 顶层名（运行时擦除，值级调用 → null） */
    private val theoremJsNames = LinkedHashSet<String>()

    fun generate(
        file: FileAst,
        dictHits: Map<CallExpr, String> = emptyMap(),
        moduleHits: Map<Expr, String> = emptyMap(),
        consHits: Map<CallExpr, String> = emptyMap(),
        dictSubHits: Map<CallExpr, List<String>> = emptyMap(),
        namedArgHits: Map<CallExpr, List<Expr>> = emptyMap(),
        sugarHits: Map<CallExpr, List<Expr>> = emptyMap(),
        awaitHits: Map<CallExpr, Boolean> = emptyMap(),
        channelUsed: Boolean = false,
        mutexUsed: Boolean = false,
        atomicUsed: Boolean = false,
        flowUsed: Boolean = false,
        rangeHits: Map<CallExpr, String> = emptyMap(),
        rangeUsed: Boolean = false,
        receiveSmartHits: Map<CallExpr, Boolean> = emptyMap(),
        flowEmitHits: Map<CallExpr, Boolean> = emptyMap(),
        hktKindPosHits: Map<CallExpr, Int> = emptyMap(),
        funRefTargets: Map<Expr, String> = emptyMap(),
        funRefFree: Set<Expr> = emptySet(),
        lambdaDestructKeys: Map<Expr, List<List<String>?>> = emptyMap(),
        delegatedImpls: Map<ImplDecl, List<FunDecl>> = emptyMap(),
        deepCopyHits: Map<Node, Boolean> = emptyMap(),
        listMethodHits: Map<CallExpr, String> = emptyMap(),
    ): String = generateUnits(listOf("" to file), dictHits, moduleHits, consHits, dictSubHits, namedArgHits, sugarHits, awaitHits, channelUsed, mutexUsed, atomicUsed, flowUsed, rangeHits, rangeUsed, receiveSmartHits, flowEmitHits, hktKindPosHits, funRefTargets, funRefFree, lambdaDestructKeys, delegatedImpls, deepCopyHits, listMethodHits)

    /** M8：全部模块合成一个 JS（顶层名字带模块前缀，根无前缀） */
    fun generateUnits(
        units: List<Pair<String, FileAst>>,
        dictHits: Map<CallExpr, String> = emptyMap(),
        moduleHits: Map<Expr, String> = emptyMap(),
        consHits: Map<CallExpr, String> = emptyMap(),
        dictSubHits: Map<CallExpr, List<String>> = emptyMap(),
        namedArgHits: Map<CallExpr, List<Expr>> = emptyMap(),
        sugarHits: Map<CallExpr, List<Expr>> = emptyMap(),
        awaitHits: Map<CallExpr, Boolean> = emptyMap(),
        channelUsed: Boolean = false,
        mutexUsed: Boolean = false,
        atomicUsed: Boolean = false,
        flowUsed: Boolean = false,
        rangeHits: Map<CallExpr, String> = emptyMap(),
        rangeUsed: Boolean = false,
        receiveSmartHits: Map<CallExpr, Boolean> = emptyMap(),
        flowEmitHits: Map<CallExpr, Boolean> = emptyMap(),
        hktKindPosHits: Map<CallExpr, Int> = emptyMap(),
        funRefTargets: Map<Expr, String> = emptyMap(),
        funRefFree: Set<Expr> = emptySet(),
        lambdaDestructKeys: Map<Expr, List<List<String>?>> = emptyMap(),
        delegatedImpls: Map<ImplDecl, List<FunDecl>> = emptyMap(),
        deepCopyHits: Map<Node, Boolean> = emptyMap(),
        listMethodHits: Map<CallExpr, String> = emptyMap(),
    ): String {
        dicts = dictHits
        this.moduleHits = moduleHits
        consDicts = consHits
        dictSubArgs = dictSubHits
        this.delegatedImpls = delegatedImpls
        namedArgOrders = namedArgHits
        sugarArgs = sugarHits
        this.awaitHits = awaitHits
        this.channelUsed = channelUsed
        this.mutexUsed = mutexUsed
        this.atomicUsed = atomicUsed
        this.flowUsed = flowUsed
        this.rangeHits = rangeHits
        this.rangeUsed = rangeUsed
        this.receiveSmartHits = receiveSmartHits
        this.flowEmitHits = flowEmitHits
        this.hktKindPosHits = hktKindPosHits
        this.funRefTargets = funRefTargets
        this.funRefFree = funRefFree
        this.lambdaDestructKeys = lambdaDestructKeys
        this.deepCopyHits = deepCopyHits
        this.listMethodHits = listMethodHits
        theoremJsNames.clear()
        for ((p, fa) in units) for (e in fa.entries) {
            val fd = (e as? DeclEntry)?.decl as? FunDecl ?: continue
            if ("theorem" in fd.annotations) theoremJsNames += moduleJsName(p, fd.name)
        }
        currentPrefix = ""
        structFields.clear()
        overloadsByUnit.clear()
        for ((_, fa) in units) for (e in fa.entries) if (e is DeclEntry) {
            val s = e.decl as? StructDecl ?: continue
            structFields[s.name] = s.fields.map { it.name }
        }
        // HKT（《高阶类型.md》HKT-D2）：收集 kind 类型类名（theory 含 arity>0 形参）——genDict 按此决定
        // 字典方法签名是否含 __self（kind 类型类 self 显式在形参表，签名无 __self）
        kindTraits.clear()
        for ((_, fa) in units) for (e in fa.entries) if (e is DeclEntry) {
            val c = e.decl as? ClassDecl ?: continue
            if (c.theory.any { it is TypeParam && it.arity > 0 }) kindTraits += c.name
        }
        for ((p, fa) in units) overloadsByUnit[p] = collectOverloads(fa)
        // 收集构造子名：prelude + 全部模块 enum；tuple 形参位置键带模块前缀
        ctorNames.clear()
        ctorOwnerCounts.clear()
        ctorNames += listOf("true", "false", "null", "None", "Some")
        nullaryCtors.clear()
        nullaryCtors += listOf("None")   // prelude 零参构造子（Some 有参不含；true/false/null 走字面量）
        listParamPos.clear()
        listCtorSet.clear()   // v2.0 基本库：数组化 List（枚举名 List 的构造子集合，见字段注释）
        for ((p, fa) in units) for (e in fa.entries) if (e is DeclEntry) {
            when (val ed = e.decl) {
                is EnumDecl -> {
                    if (ed.name == "List") for (c in ed.ctors) {
                        // 数组化 List 只认 Nil()/Cons(T, List[T]) 形态（v2.0 基本库约定）——
                        // 用户自定义 `enum List { A(), B() }`（构造子非 Nil/Cons 形态）不受影响
                        if ((c.name == "Nil" && c.fields.isEmpty()) || (c.name == "Cons" && c.fields.size == 2))
                            listCtorSet += c.name
                    }
                    ed.ctors.forEach { ctorNames += it.name; if (it.fields.isEmpty()) nullaryCtors += it.name
                        ctorOwnerCounts.merge(it.name, 1, Int::plus) }
                }
                is FunDecl -> ed.params.forEachIndexed { i, pp ->
                    if ("list" in pp.annotations) listParamPos[moduleJsName(p, ed.name)] = i
                }
                else -> {}
            }
        }

        // 先生成主体到缓冲；是否注入结构相等助手由 needEqPreScan 预扫描决定
        sb.setLength(0); indent = 0
        line("(() => {")
        indent++
        // prelude 枚举工厂（Optional/Null 不在用户源码里声明，需内置生成）
        line("const None = () => ({ tag: \"None\" });")
        line("const Some = (a0) => ({ tag: \"Some\", 0: a0 });")
        // v3.0：toStr——枚举纯 tag 对象（{tag: 'D'}，单键）取 tag 名成串；list/struct 其余保持 String()
        line("const __dshtoStr = (v) => {")
        line("  if (v === null || v === undefined) return String(v);")
        line("  if (Array.isArray(v)) return \"[\" + v.map(__dshtoStr).join(\",\") + \"]\";")
        line("  if (typeof v === \"object\" && Object.keys(v).length === 1 && \"tag\" in v) return String(v.tag);")
        line("  return String(v);")
        line("};")
        // `is` 类型测的结构判定（v2.1 由空安全 `?`/`>:` 迁移）：基本类型用 typeof，枚举/具名用 tag，函数 typeof===
        // JS 不记录类型信息 → 结构判定（与 == 结构相等同源）；v1 纯结构、零 tag 名义区分。
        line("const __isa = (v, t) => {")
        line("  if (t === \"Nat\" || t === \"Int\" || t === \"Rat\") return typeof v === \"number\";")
        line("  if (t === \"Str\") return typeof v === \"string\";")
        line("  if (t === \"Bool\") return typeof v === \"boolean\";")
        line("  if (t === \"Null\") return v === null || (v && v.tag === \"Null\");")
        line("  if (t === \"(fn)\") return typeof v === \"function\";")
        // v2.0 基本库（内建 JS 实现）：数组化 List 的类型判定 = Array.isArray（List 在 JS 层是数组，无 tag）
        line("  if (t === \"List\") return Array.isArray(v);")
        line("  return v !== null && typeof v === \"object\" && v.tag === t;")
        line("};")
        // 结构相等助手（决策 32/53）：仅在源码用到 ==/!= 时注入，保持"未用则零运行时"
        if (units.any { (_, fa) -> needEqPreScan(fa) }) {
            line("const __eq = (a, b) => {")
            line("  if (a === b) return true;")
            line("  if (typeof a !== \"object\" || typeof b !== \"object\" || a === null || b === null) return false;")
            line("  if (a.tag !== undefined && a.tag !== b.tag) return false;")
            line("  const ka = Object.keys(a), kb = Object.keys(b);")
            line("  if (ka.length !== kb.length) return false;")
            line("  return ka.every(k => __eq(a[k], b[k]));")
            line("};")
        }
        androidUsed = units.any { (_, fa) -> androidPreScan(fa) }
                // 安卓桥接兜底（android doc/安卓标准库.md）：仅在用到 @android 内建时注入。
        // node/桌面：log 打到 console，其余空操作；webview 内由原生注入的 __dshAndroid 接管。
        if (androidUsed) {
            line("const __dshAndroid = (kind, args) => {")
            line("  if (kind === \"log\") console.log(...args);")
            line("  return null;")
            line("};")
        }
        // root doc《副作用与可变性控制》85-144：可变视图深拷贝助手——绑定/赋值触发 __dc(value)。
        // 仅在源码用到可变视图拷贝时注入（编译器按 binderNeedsCopy 留痕 deepCopyHits）。
        if (deepCopyHits.isNotEmpty()) {
            line("const __dc = (v) => {")
            line("  if (v === null || typeof v !== \"object\") return v;")
            line("  if (Array.isArray(v)) return v.map(__dc);")
            line("  const o = {};")
            line("  for (const k in v) o[k] = __dc(v[k]);")
            line("  return o;")
            line("};")
        }
        // v2.0 异步（异步 §5.4）：Channel 小运行时——唯一运行时组件（缓冲/握手/关闭状态）。
        // 无缓冲（cap=0）时 send/receive 同步握手：等待队列互相配对；缓冲时进队列。
        if (channelUsed) {
            line("class Channel {")
            line("  constructor(cap = 0) { this.cap = cap; this.queue = []; this.senders = []; this.receivers = []; this.closed = false; }")
            line("  async send(v) {")
            line("    if (this.closed) return false;")
            line("    const r = this.receivers.shift();")
            line("    if (r) { r.res({ tag: \"Some\", 0: v }); return true; }")
            line("    if (this.queue.length < this.cap) { this.queue.push(v); return true; }")
            line("    return new Promise((res) => { this.senders.push({ v, res }); });")
            line("  }")
            line("  async receive() {")
            line("    if (this.queue.length > 0) {")
            line("      const v = this.queue.shift();")
            line("      const s = this.senders.shift();")
            line("      if (s) { this.queue.push(s.v); s.res(true); }")
            line("      return { tag: \"Some\", 0: v };")
            line("    }")
            line("    const s = this.senders.shift();")
            line("    if (s) { s.res(true); return { tag: \"Some\", 0: s.v }; }")
            line("    if (this.closed) return { tag: \"None\" };")
            line("    return new Promise((res) => { this.receivers.push({ res }); });")
            line("  }")
            line("  close() { this.closed = true; }")
            line("  isClosed() { return this.closed; }")
            line("}")
        }
        // v2.0 异步（root doc《异步》）：Mutex 小运行时——异步互斥锁，防数据竞争。
        // JS 单线程，锁的意义是阻止 async 协程在临界区内互相穿插。
        // lock() 若被占让出（等待队列）；unlock() 唤醒下一个等待者或释放锁；
        // withLock(f) 持锁执行 f() 并在退出时释放（含异常路径）。
        if (mutexUsed) {
            line("class Mutex {")
            line("  constructor() { this._locked = false; this._waiters = []; }")
            line("  async lock() {")
            line("    if (!this._locked) { this._locked = true; return; }")
            line("    return new Promise((res) => this._waiters.push(res));")
            line("  }")
            line("  async unlock() {")
            line("    if (this._waiters.length) { const w = this._waiters.shift(); w(); }")
            line("    else this._locked = false;")
            line("  }")
            line("  async withLock(f) { await this.lock(); try { return await f(); } finally { await this.unlock(); } }")
            line("}")
        }
        // v2.0 异步（root doc《异步》）：Flow 冷流小运行时——惰性冷流（collect 时执行 body 生成器）。
        // gen 是 async generator 函数；collect(f) 逐值调 f；map(g) 返回派生冷流。
        if (flowUsed) {
            line("const __flow = (gen) => ({ gen,")
            line("  collect: async (f) => { for await (const v of gen()) await f(v); },")
            line("  map: (g) => __flow(async function* () { for await (const v of gen()) yield g(v); })")
            line("});")
        }
        // v3.0 特性 3：Range 小运行时——惰性区间（第二个运行时组件，与 Channel 同层）。
        // 不预生成元素：in/contains O(1) 闭区间判定；map/fold 迭代；toList/length 按需展开。
        if (rangeUsed) {
            line("const __range = (lo, hi) => ({ lo, hi,")
            line("  contains: (x) => lo <= x && x <= hi,")
            line("  length: () => Math.max(0, hi - lo + 1),")
            line("  toList: () => Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => lo + i),")
            line("  map: (f) => Array.from({ length: Math.max(0, hi - lo + 1) }, (_, i) => f(lo + i)),")
            line("  fold: (init, f) => { let acc = init; for (let i = lo; i <= hi; i++) acc = f(i, acc); return acc; },")
            line("});")
        }
        for ((p, fa) in units) {
            currentPrefix = p
            for (e in fa.entries) if (e is DeclEntry) genDecl(e.decl)
        }
        val mains = units.flatMap { (_, fa) -> fa.entries.filterIsInstance<StmtEntry>() }
        if (mains.isNotEmpty()) {
            blank(); comment("主序列")
            for (e in mains) genStmt(e.stmt)
        }
        // O4（决策 70）：具名 `fun main()`（0 形参）自动作为入口调用；P0：main 可位于任意模块，全局唯一由 MultiModule 检查
        val mainUnit = units.firstOrNull { (_, fa) ->
            fa.entries.filterIsInstance<DeclEntry>().any {
                val fn = it.decl as? FunDecl; fn != null && fn.name == "main" && fn.params.isEmpty() && fn.body != null
            }
        }
        if (mainUnit != null) line("${moduleJsName(mainUnit.first, "main")}();")
        indent--
        line("})();")
        val body = sb.toString()
        sb.setLength(0)
        line("\"use strict\";")
        sb.append(body)
        return sb.toString()
    }

    /**
     * 预扫描：源码里是否出现 `==`/`!=`（决定是否注入结构相等助手）。
     * 用类成员函数而非局部函数——局部函数不能互相前向引用（表达式遍历需调语句遍历，反之亦然）。
     */
    
/** 安卓内建兜底桥接名集合（android doc/安卓标准库.md）。*/
private val ANDROID_CALL_SPECIAL: Set<String> = setOf(
    "log", "toast", "layout",
    "onCreate", "onStart", "onResume", "onPause", "onStop", "onDestroy", "onRestart",
    "androidRecompose",
)

/** 安卓桥接预扫描：源码里是否调用了 @android 内建（决定是否注入 __dshAndroid 兜底桩）。 */
private fun androidPreScan(file: FileAst): Boolean {
    for (e in file.entries) {
        val d = (e as? DeclEntry)?.decl ?: continue
        if (d is FunDecl && d.body != null && exprHasAndroidCall(d.body!!)) return true
    }
    return false
}

private fun exprHasAndroidCall(e: Expr): Boolean = when (e) {
    is CallExpr -> {
        val n = when (val c = e.callee) {
            is NameRef -> c.name
            is InstExpr -> (c.target as? NameRef)?.name
            else -> null
        }
        if (n != null && n in ANDROID_CALL_SPECIAL) true
        else exprHasAndroidCall(e.callee) || e.args.any { exprHasAndroidCall(it) } ||
            e.namedArgs.values.any { exprHasAndroidCall(it) }
    }
    is BlockExpr -> e.stmts.any { stmtHasAndroidCall(it) }
    is LambdaExpr -> exprHasAndroidCall(e.body)
    is StructCtorExpr -> e.assigns.any { exprHasAndroidCall(it.second) }
    is BinExpr -> exprHasAndroidCall(e.left) || exprHasAndroidCall(e.right)
    is UniExpr -> exprHasAndroidCall(e.operand)
    is IfExpr -> exprHasAndroidCall(e.cond) || exprHasAndroidCall(e.thenBlock) ||
        (e.elseBlock != null && exprHasAndroidCall(e.elseBlock!!))
    is FieldExpr -> exprHasAndroidCall(e.target)
    is VarExpr -> exprHasAndroidCall(e.value)
    else -> false
}

private fun stmtHasAndroidCall(s: Stmt): Boolean = when (s) {
    is VarStmt -> exprHasAndroidCall(s.value)
    is ExprStmt -> exprHasAndroidCall(s.expr)
    is AssignStmt -> exprHasAndroidCall(s.target) || exprHasAndroidCall(s.value)
    is ReturnStmt -> s.expr != null && exprHasAndroidCall(s.expr!!)
    is UncheckedStmt -> stmtHasAndroidCall(s.inner)
    else -> false
}
private fun needEqPreScan(file: FileAst): Boolean {
        eqHit = false
        for (e in file.entries) when (e) {
            is DeclEntry -> walkDeclEq(e.decl)
            is StmtEntry -> walkStmtEq(e.stmt)
        }
        return eqHit
    }

    private fun walkExprEq(e: Expr?) {
        if (e == null || eqHit) return
        when (e) {
            is BinExpr -> { if (e.op == "==" || e.op == "!=") eqHit = true; walkExprEq(e.left); walkExprEq(e.right) }
            is UniExpr -> walkExprEq(e.operand)
            is CallExpr -> { walkExprEq(e.callee); e.args.forEach { walkExprEq(it) } }
            is InstExpr -> { walkExprEq(e.target); e.terms.forEach { walkExprEq(it) } }
            is FieldExpr -> walkExprEq(e.target)
            is SymbolCallExpr -> {
                if (e.op == "==" || e.op == "!=") eqHit = true   // 前缀形式也要触发助手注入
                e.args.forEach { walkExprEq(it) }
            }
            is LambdaExpr -> walkExprEq(e.body)
            is BlockExpr -> e.stmts.forEach { walkStmtEq(it) }
            is IfExpr -> { walkExprEq(e.cond); walkExprEq(e.thenBlock); e.elseBlock?.let { walkExprEq(it) } }
            is WhenExpr -> { e.subject?.let { walkExprEq(it) }; e.arms.forEach { walkExprEq(it.body); walkExprEq(it.guard); it.cond?.let { c -> walkExprEq(c) } } }
            is TupleExpr -> e.items.forEach { walkExprEq(it) }
            is QuantExpr -> walkExprEq(e.body)
            is VarExpr -> walkExprEq(e.value)
            is AnonFunExpr -> walkExprEq(e.body)
            is ByExpr -> { walkExprEq(e.target); e.props.forEach { walkExprEq(it) } }
            else -> {}
        }
    }

    private fun walkStmtEq(s: Stmt) {
        when (s) {
            is VarStmt -> walkExprEq(s.value)
            is AssignStmt -> { walkExprEq(s.target); walkExprEq(s.value) }
            is ExprStmt -> walkExprEq(s.expr)
            is UncheckedStmt -> walkStmtEq(s.inner)
            is ReturnStmt -> walkExprEq(s.expr)
            else -> {}
        }
    }

    private fun walkDeclEq(d: Decl) {
        when (d) {
            is FunDecl -> walkExprEq(d.body)
            is ClassDecl -> d.members.forEach { walkExprEq(it.body) }
            is ImplDecl -> d.members.forEach { walkExprEq(it.body) }
            else -> {}
        }
    }

    // ---------- 声明 ----------

    private fun genDecl(decl: Decl) {
        when (decl) {
            is FunDecl -> if ("theorem" !in decl.annotations) genFun(decl)   // @theorem 定理函数编译期擦除（仅供证明位）
            is StructDecl -> {
                // O3（决策 69）：字段带声明默认值时，JS 工厂用参数默认值补齐——省略实参即取默认
                val ps = decl.fields.joinToString(", ") { f ->
                    f.default?.let { "${mangle(f.name)} = ${expr(it)}" } ?: mangle(f.name)
                }
                val obj = decl.fields.joinToString(", ") { "${mangle(it.name)}: ${mangle(it.name)}" }
                line("const ${moduleJsName(currentPrefix, decl.name)} = ($ps) => ({ tag: ${q(decl.name)}, $obj });")
            }
            is EnumDecl -> for (c in decl.ctors) {
                if (c.name == "true" || c.name == "false") continue   // Bool 用 JS 字面量
                // v2.0 基本库（内建 JS 实现）：数组化 List 的构造子不生成 tag 对象工厂——
                // List 在 JS 层是数组（Nil→[]、Cons(h,t)→[h,...t]），工厂由调用点/模式特判承接
                if (c.name in listCtorSet) continue
                // v2.1（限制解开）：工厂名带枚举前缀 `E__T`（E::T 消歧），裸名唯一时才生成裸名别名
                val enc = moduleJsName(currentPrefix, decl.name + "__" + c.name)
                if (c.fields.isEmpty()) {
                    line("function $enc() { return { tag: ${q(c.name)} }; }")
                    if (ctorOwnerCounts.getOrDefault(c.name, 0) <= 1) line("const ${moduleJsName(currentPrefix, c.name)} = $enc;")
                } else {
                    val ps = c.fields.indices.joinToString(", ") { "a$it" }
                    val obj = c.fields.indices.joinToString(", ") { "$it: a$it" }
                    line("function $enc($ps) { return { tag: ${q(c.name)}, $obj }; }")
                    if (ctorOwnerCounts.getOrDefault(c.name, 0) <= 1) line("const ${moduleJsName(currentPrefix, c.name)} = $enc;")
                }
            }
            is ClassDecl -> decl.members.forEach { if (it.body != null) genFun(it) }
            // 决策 60（T5）：impl 成员生成字典对象，方法体收隐藏字典参数 d
            is ImplDecl -> if (decl.trait != null) genDict(decl)   // 无类型类 impl 已脱糖为自由函数（Parser 阶段），零残留
            is TypeAliasDecl -> {}   // 纯类型层，零输出（别名在 checker 展开）
            is PropDefDecl -> {}   // 自定义命题（根文档）：编译期声明，零输出
        }
    }

    /** impl → 类型类字典对象：`const dict_<Trait>_<Type> = { m: function (d, __self, args…) { … } };`
     *  （O2，决策 68；第四轮：块体方法用 `function` 形式展平语句，让 return 真正退出方法）
     *  P0（M6）：字典名带声明模块前缀（与 Checker resolveDict 的 dictNameOf 同参数） */
    private fun genDict(impl: ImplDecl) {
        val dn = dictNameOf(impl.trait!!.name, impl.self, currentPrefix)
        line("const $dn = {")
        indent++
        val fields = structFields[(impl.self as? NamedType)?.name ?: ""] ?: emptyList()
        // v3.0 特性 2：委托 impl 用 checker 展开成员（含转发方法）
        val members = delegatedImpls[impl] ?: impl.members
        for ((i, m) in members.withIndex()) {
            val body = m.body ?: continue
            // 隐藏首参 d（字典自身，供体内递归分发）。零参类型类加隐式接收者 __self（决策 68）；
            // kind 类型类（HKT-D2）self 已显式在形参表（map(f, fa) 的 fa 位），签名无 __self——(d, f, fa...)。
            val ps = if (impl.trait!!.name in kindTraits)
                (listOf("d") + m.params.map { mangle(it.name) }).joinToString(", ")
            else
                (listOf("d", "__self") + m.params.map { mangle(it.name) }).joinToString(", ")
            val comma = if (i == members.lastIndex) "" else ","
            // 字段裸用重写上下文：字段名 − 体内一切绑定名（保守：存在同名局部绑定时整体不重写，
            // 宁可 JS 报未定义也不产出静默错值）
            val savedFields = methodFields; val savedLocals = methodLocals
            methodFields = fields.toSet()
            methodLocals = m.params.map { it.name }.toSet() + collectBinders(body)
            val savedDict = dictMethod
            dictMethod = true
            if (body is BlockExpr) {
                // 块体方法展平成真正的函数：语句位 return 直接退出方法（第四轮）
                line("${m.name}: function ($ps) {")
                indent++
                genBlockBody(body)
                indent--
                line("}$comma")
            } else {
                line("${m.name}: ($ps) => ${expr(body)}$comma")
            }
            dictMethod = savedDict
            methodFields = savedFields; methodLocals = savedLocals
        }
        indent--
        line("};")
    }

    /** O2：收集子树里所有会绑定名字的位置（var 语句/表达式、lambda 形参、when 绑定与模式变量）。
     *  类成员函数而非局部函数——互递归不能前向引用（工程规范 I-13）。 */
    private fun collectBinders(e: Expr): Set<String> {
        val out = LinkedHashSet<String>()
        binderExpr(e, out)
        return out
    }

    private fun binderPat(p: Pattern, out: MutableSet<String>) {
        when (p) {
            is PatBind -> out.add(p.name)
            is PatIs -> p.bind?.let { out.add(it) }
            is PatCtor -> p.args.forEach { binderPat(it, out) }
            is PatTuple -> p.items.forEach { binderPat(it, out) }
            else -> {}
        }
    }

    private fun binderStmt(s: Stmt, out: MutableSet<String>) {
        when (s) {
            is VarStmt -> { s.destruct?.let { out += it } ?: out.add(s.name); binderExpr(s.value, out) }
            is AssignStmt -> binderExpr(s.value, out)
            is ExprStmt -> binderExpr(s.expr, out)
            is UncheckedStmt -> binderStmt(s.inner, out)
            else -> {}
        }
    }

    private fun binderExpr(x: Expr, out: MutableSet<String>) {
        when (x) {
            is BlockExpr -> x.stmts.forEach { binderStmt(it, out) }
            is IfExpr -> { binderExpr(x.cond, out); x.thenBlock.stmts.forEach { binderStmt(it, out) }; x.elseBlock?.stmts?.forEach { binderStmt(it, out) } }
            is WhenExpr -> {
                x.subjectBinding?.let { out.add(it) }
                x.subject?.let { binderExpr(it, out) }
                x.arms.forEach { a -> binderPat(a.pattern, out); a.guard?.let { binderExpr(it, out) }; binderExpr(a.body, out) }
            }
            is LambdaExpr -> { out.addAll(x.params); binderExpr(x.body, out) }
            is VarExpr -> { out.add(x.name); binderExpr(x.value, out) }
            is TupleExpr -> x.items.forEach { binderExpr(it, out) }
            is StructCtorExpr -> x.assigns.forEach { binderExpr(it.second, out) }
            is BinExpr -> { binderExpr(x.left, out); binderExpr(x.right, out) }
            is UniExpr -> binderExpr(x.operand, out)
            is CallExpr -> { binderExpr(x.callee, out); x.args.forEach { binderExpr(it, out) } }
            is InstExpr -> { binderExpr(x.target, out); x.terms.forEach { binderExpr(it, out) } }
            else -> {}
        }
    }

    private fun genFun(fn: FunDecl) {
        val body = fn.body ?: return   // 纯签名不生成
        // P6（决策 82）：带约束泛型函数的隐藏字典槽参数——`fun f[T: Show]` → f(d_Show_T, ...)
        // v2.1：Any 是默认类型类（恒满足、零方法无字典）——不生成槽
        val slots = fn.theory.filterIsInstance<TypeParam>().filter { it.constraint != null && it.constraint != "Any" }   // v2.1：Any 零字典不设槽
            .map { "d_${it.constraint}_${it.name}" }
        // P4（v2.1）：解构参数的 JS 形参用合成名 __dtN，函数体开头 const 拆解分量
        val ps = (slots + fn.params.mapIndexed { i, p -> if (p.destruct != null) "__dt$i" else mangle(p.name) }).joinToString(", ")
        // v2.0 重载：本模块内同名 >1 的函数按首参标签唯一化（`core_collection__map$List`），与 Checker 侧 moduleHits 同名规则一致
        val base = moduleJsName(currentPrefix, fn.name)
        val jsName = if (overloadsByUnit[currentPrefix]?.contains(fn.name) == true) "$base\$${fnTag(fn)}" else base
        // v2.0 异步（决策 93）：@async 函数与 main（天然 @async，异步 §5.2）生成 `async function`——体内挂起点（awaitHits）生成 await
        val asyncKw = if ("async" in fn.annotations || fn.name == "main") "async " else ""
        line("${asyncKw}function $jsName($ps) {")
        indent++
        // P4（v2.1）：解构分量在函数体入口解绑。结构体值是命名键对象（x/y/...），
        // 元组（与枚举构造子）是下标对象（0/1/...），按声明类型选键。
        fn.params.forEachIndexed { i, p ->
            val destruct = p.destruct
            if (destruct != null) {
                val isTuple = p.type is TupleType
                val keys = if (isTuple) emptyList() else (p.type as? NamedType)?.let { structFields[it.name] } ?: emptyList()
                destruct.forEachIndexed { j, nm ->
                    val acc = if (isTuple) "[$j]" else (if (j < keys.size) ".${keys[j]}" else "[$j]")
                    line("const ${mangle(nm)} = __dt$i$acc;")
                }
            }
        }
        when (body) {
            is BlockExpr -> genBlockBody(body)
            else -> line("return ${expr(body)};")
        }
        indent--
        line("}")
    }

    /** v2.0：收集模块内「同名且首参类型不同」的函数名（重载组）——与 Checker 的 E-DUP-DECL 放行判据一致 */
    private fun collectOverloads(fa: FileAst): Set<String> {
        val seen = HashMap<String, String>()
        val out = HashSet<String>()
        for (e in fa.entries) if (e is DeclEntry) {
            val fd = e.decl as? FunDecl ?: continue
            val tag = fd.params.firstOrNull()?.type?.render() ?: ""
            val prev = seen.putIfAbsent(fd.name, tag)
            if (prev != null && prev != tag) out += fd.name
        }
        return out
    }

    /** 块体展平进真正的 JS 函数/对象方法：语句逐条发射。
     *  v1.1 Kotlin 化：块式函数**不隐式返回尾表达式**——返回值只来自显式 return；
     *  无 return（且省略/Null 返回类型）时补 `return null`。具名函数显式返回类型时，
     *  checker 已强制全路径 return（diverges），故非发散尾表达式只按语句发射、值丢弃。 */
    private fun genBlockBody(body: BlockExpr) {
        val tail = body.stmts.lastOrNull() as? ExprStmt
        for (s in body.stmts) if (s !== tail) genStmt(s)
        when {
            tail == null -> if (body.stmts.lastOrNull() !is ReturnStmt) line("return null;")
            diverges(tail.expr) -> genStmt(tail)   // 尾 if 全路径 return：展平，各分支已 return
            else -> { genStmt(tail); line("return null;") }   // 尾表达式按语句发射（值丢弃）
        }
    }

    /** 全路径 return 的 if 展平成真正的 JS 语句（决策 75：提前 return 直接退出函数）。
     *  分支内语句统一交 genStmt——其 ExprStmt 分派会递归处理嵌套的同型 if。 */
    private fun ifStmtFlatten(e: IfExpr) {
        line("if (${expr(e.cond)}) {")
        indent++
        e.thenBlock.stmts.forEach { genStmt(it) }
        indent--
        val elseBlock = e.elseBlock
        if (elseBlock != null) {
            line("} else {")
            indent++
            elseBlock.stmts.forEach { genStmt(it) }
            indent--
        }
        line("}")
    }

    // ---------- 语句 ----------

    /** root doc《副作用与可变性控制》：绑定/赋值值是否需深拷贝包装（可变视图语义） */
    private fun dcWrap(key: Any, valueJs: String): String =
        if (deepCopyHits[key] == true) "__dc($valueJs)" else valueJs

    private fun genStmt(s: Stmt) {
        when (s) {
            // 根文档《手动证明》：因为-所以 / 非真即假 / 分类讨论 / 策略 是纯证明构造——不产生运行时代码
            is BTSStmt, is WhenPropStmt, is StrategyStmt, is IfPropStmt, is ExistIntroStmt, is ExistsElimStmt, is FunStmt -> {}
            is VarStmt -> {
                val destruct = s.destruct
                if (destruct != null) {
                    val kw = if ("mut" in s.annotations) "let" else "const"
                    line("$kw [${destruct.joinToString(", ") { mangle(it) }}] = ${dcWrap(s, expr(s.value))};")
                } else {
                    val kw = if ("mut" in s.annotations) "let" else "const"
                    val initJs = expr(s.value)
                    line("$kw ${mangle(s.name)} = ${dcWrap(s, initJs)};")
                }
            }
            is AssignStmt -> line("${expr(s.target)} = ${dcWrap(s, expr(s.value))};")
            // 命题逃逸：擦除为注释，可 grep 审计
            is AxiomStmt -> s.props.forEach { comment("axiom: ${tag(it)}") }
            is ByStmt -> s.props.forEach { comment("by: ${tag(it)}") }
            is UncheckedStmt -> { comment("unchecked"); genStmt(s.inner) }
            is ExprStmt -> {
                // 决策 75：语句位 if 一律展平成真正的 JS 控制语句（与检查器给它的 flow=true 对齐）；
                // 分支里的 return 因此能真正退出函数。值位 if（var 初值/尾表达式）仍走 expr→IIFE。
                val x = s.expr
                if (x is IfExpr) ifStmtFlatten(x)
                else if (x is WhenExpr) whenStmtFlatten(x)   // M4 同款：含 ifis 脱糖形态，提前 return 能退出函数
                else line("${expr(x)};")
            }
            // 第四轮：显式 return 语句发射为真 return（仅在函数体/块体方法的展平语境出现）
            is ReturnStmt -> line(s.expr?.let { "return ${expr(it)};" } ?: "return;")
        }
    }

    // ---------- 表达式 ----------

    private fun expr(e: Expr): String = when (e) {
        is IntLit -> e.value
        is FloatLit -> e.value   // P10（决策 86）：浮点字面量原文直出（JS Number，IEEE double）
        is StrLit -> q(e.value)
        is NameRef -> when {
            e.name == "true" || e.name == "false" || e.name == "null" -> e.name   // JS 字面量
            // v2.1：E::A 复合构造子名 → 枚举前缀工厂名 E__T（裸名唯一时才用裸名别名）
            e.name.contains("::") -> {
                val full = e.name.replace("::", "__")
                val bn = e.name.substringAfterLast("::")
                when {
                    bn in nullaryCtors && bn in listCtorSet -> "[]"
                    bn in nullaryCtors -> "${mangle(full)}()"
                    else -> mangle(full)
                }
            }
            // v2.0 基本库：数组化 List 的零参构造子值引用 → 空数组（工厂已跳过生成，不能调 core_collection__Nil()）
            e.name in nullaryCtors && e.name in listCtorSet -> "[]"
            e.name in nullaryCtors -> "${moduleHits[e] ?: mangle(e.name)}()"       // 零参构造子值引用→调用
            // P0（M8）：全局引用按 Checker 留痕的 mangle 名；局部变量/字段不留痕
            moduleHits.containsKey(e) -> moduleHits.getValue(e)
            // O2（决策 68）：方法体内裸用字段名 → __self.字段（被局部绑定遮蔽者除外）
            e.name in methodFields && e.name !in methodLocals -> "__self.${mangle(e.name)}"
            // P6（决策 82）：字典方法体内显式 `self`（隐式接收者，决策 68）→ __self 参数
            e.name == "self" && dictMethod && e.name !in methodLocals -> "__self"
            else -> mangle(e.name)
        }
        ReturnSym -> "__ret"
        is ParamRefExpr -> "/* $" + "$" + e.index + " */"   // $n 参数引用（决策 65）：命题层专用，产物以注释占位
        TopExpr -> "true"
        BotExpr -> "false"
        is UniExpr -> "(${unop(e.op)}${expr(e.operand)})"
        is BinExpr -> bin(e)
        is TypeTestExpr -> "(__isa(${expr(e.target)}, \"${e.type.name}\"))"
        // v2.0 异步（决策 93，异步 §5.3）：Task { … } → 惰性任务对象。start() 惰性创建 async 函数并执行；
        // join() 返回 Promise（await 在调用点）；isAlive() 简化为「是否已 start」。
        is TaskExpr -> taskJs(e)
        is FlowExpr -> flowJs(e)
        is SymbolCallExpr -> symbolCall(e)
        is FieldExpr -> {
            // v3.0（完美函数类型）：方法路径作值（xs.map / x.m 无括号）→ 绑定 self 的闭包；
            // 其余走 P0 跨模块限定引用留痕名 / 字段读取
            val ref = funRefTargets[e]
            if (ref != null) {
                // v4：类型类 impl 方法（字典方法）作值——字典名带点号（dict_X.m）：闭包首参补 dict 自身
                val di = ref.indexOf('.')
                if (di > 0) "(...a) => $ref(${ref.substring(0, di)}, ${expr(e.target)}, ...a)"
                else "(...a) => $ref(${expr(e.target)}, ...a)"
            } else moduleHits[e] ?: "${expr(e.target)}.${mangle(e.name)}"
        }
        // P6（决策 95）：`o::f` 方法引用 → 绑定 self 的闭包 `(…a) => f(o, …a)`
        is FunRefExpr -> {
            val jsName = funRefTargets[e] ?: "${expr(e.target)}.${mangle(e.name)}"
            val tgt = expr(e.target)
            // v3.0（完美函数类型）：自由函数引用（o::f 中 f 是全局函数）不注入接收者
            if (e in funRefFree) "(...a) => $jsName(...a)"
            else "(...a) => $jsName($tgt, ...a)"
        }
        is CallExpr -> call(e)
        is InstExpr -> expr(e.target)   // 实例化/供给整体擦除，只留被调名
        is LambdaExpr -> {
            // P4（v2.1）：`(a, b)` 解构参数——合成形参 + 块体开头 const 拆解（分量按下标取）
            val jsParams = e.params.mapIndexed { i, x -> if (x.startsWith("(")) "__dt$i" else mangle(x) }.joinToString(", ")
            val dtBind = e.params.withIndex().filter { (_, x) -> x.startsWith("(") }.joinToString("") { (i, x) ->
                val keys = lambdaDestructKeys[e]?.getOrNull(i)
                x.substring(1, x.length - 1).split(",").mapIndexed { j, nm ->
                    val acc = if (keys != null && j < keys.size) "__dt$i.${keys[j]}" else null
                    // v2.1（限制解开）：结构体值是命名键对象、元组是数组——静态已知键用字段键（.x），
                    // 键未知（无注解 lambda）双兜底：对象有字段键走 .x，否则回落下标 [j]。
                    if (acc == null) "const ${mangle(nm)} = __dt$i[$j]; "
                    else "const ${mangle(nm)} = $acc !== undefined ? $acc : __dt$i[$j]; "
                }.joinToString("")
            }
            val body = e.body
            if (dtBind.isEmpty()) {
                if (body is BlockExpr) "($jsParams) => ${lambdaBodyStr(body)}"
                else "($jsParams) => ${expr(body)}"
            } else {
                val tail = if (body is BlockExpr) lambdaBodyStr(body) else "return ${expr(body)};"
                "($jsParams) => { $dtBind $tail }"
            }
        }
        is TupleExpr -> "[" + e.items.joinToString(", ") { expr(it) } + "]"   // 元组→JS 数组（决策 61）
        is StructCtorExpr -> structCtorJs(e)   // O3（决策 69）：命名字段构造→位置实参工厂调用
        is LinearBlockExpr -> blockIIFE(e.block)
        is BlockExpr -> blockIIFE(e)

        // 线性作用域（第十二卷 §10.1 LV-ERASE-BLOCK）：`%` 是纯编译期分析标记，生成代码与普通块逐字节相同
        is LinearBlockExpr -> expr(e.block)
        is IfExpr -> ifExpr(e)
        is WhenExpr -> whenExpr(e)
        is QuantExpr -> expr(e.body)    // 量词命题擦除
        is VarExpr -> varExprJs(e)
        is AnonFunExpr -> anonFun(e)
        is IsPatExpr -> throw RuntimeException("IsPatExpr 只应出现在 when 模式里，不应作为表达式 codegen")
        is ByExpr -> expr(e.target)     // by 后缀擦除，只留目标值
    }

    /** var 表达式：擦除为 IIFE，内部 const 绑定并返回初值（声明即表达式） */
    private fun varExprJs(e: VarExpr): String {
        val kw = if ("mut" in e.annotations) "let" else "const"
        return "(() => { $kw ${mangle(e.name)} = ${dcWrap(e, expr(e.value))}; return ${mangle(e.name)}; })()"
    }

    private fun bin(e: BinExpr): String {
        // 命题层中缀（= : u系 逻辑）在值上下文擦除为 true（它们只在命题位出现，不进 JS 值）
        // v3.0：区间成员 `x in lo..hi` → 闭区间比较
        if (e.op == "in") {
            val rr = e.right
            if (rr is BinExpr && rr.op == "..")
                return "(${expr(rr.left)} <= ${expr(e.left)} && ${expr(e.left)} <= ${expr(rr.right)})"
            return "true"   // 语义层已拦；兜底
        }
        if (e.op == "..") return "__range(${expr(e.left)}, ${expr(e.right)})"   // v3.0 特性 3：惰性 Range（非 List 数组）
        if (e.op == "^") return "Math.pow(${expr(e.left)}, ${expr(e.right)})"   // 根文档：`^` 幂
        val op = when (e.op) {
            "+", "-", "*", "/", "%", "<", ">", "<=", ">=" -> e.op
            // `==` 是结构相等（决策 32/53）：struct 全字段相等即相等，禁重写
            "==" -> return "__eq(${expr(e.left)}, ${expr(e.right)})"
            "!=" -> return "(!__eq(${expr(e.left)}, ${expr(e.right)}))"
            "!", "¬" -> "!"
            "&", "∧" -> "&&"
            "|", "∨" -> "||"
            "=", ":", "->", "→", "<->", "↔" -> return "true"  // 纯命题，擦除
            else -> return "true"   // 自定义符号中缀（两参解糖）：命题，擦除
        }
        return "(${expr(e.left)} $op ${expr(e.right)})"
    }

    /**
     * 前缀符号调用 `==(a,b)`、`<(1,2)`（决策 32：`==`/`!=` 是函数，禁中缀，只能前缀调用）。
     * `==`/`!=` 走结构相等助手；比较符生成中缀运算。
     */
    private fun symbolCall(e: SymbolCallExpr): String {
        val a = e.args.map { expr(it) }
        return when (e.op) {
            "==" -> if (a.size == 2) "__eq(${a[0]}, ${a[1]})" else "false"
            "!=" -> if (a.size == 2) "(!__eq(${a[0]}, ${a[1]}))" else "false"
            "<", ">", "<=", ">=" -> if (a.size == 2) "(${a[0]} ${e.op} ${a[1]})" else "false"
            else -> "undefined"   // 未收录的前缀符号：不应出现（解析器 symbolCallStart 限定）
        }
    }

    /** v2.0 异步（决策 93，异步 §5.3）：Task { … } → 惰性任务对象。
     *  start() 惰性建 async 函数执行体并持有 Promise；join() 返回该 Promise；isAlive() 是否已 start。 */
    private fun taskJs(e: TaskExpr): String {
        val bodyJs = taskBodyStr(e.body)
        return "(() => {\n" +
            "  let promise = null;\n" +
            "  return {\n" +
            "    start: () => { promise = (async () => {\n$bodyJs\n    })(); },\n" +
            "    join: () => promise,\n" +
            "    isAlive: () => promise !== null,\n" +
            "  };\n" +
            "})()"
    }

    /** Task 体 → async 函数体字符串（语句逐条 + return 尾表达式；无尾时 return null） */
    private fun taskBodyStr(b: BlockExpr): String {
        val tail = b.stmts.lastOrNull() as? ExprStmt
        val lines = ArrayList<String>()
        for (s in b.stmts) if (s !== tail) { val t = stmtStr(s); if (t.isNotEmpty()) lines += "    $t" }
        val ret = if (tail != null) "    return ${expr(tail.expr)};" else "    return null;"
        return (lines + ret).joinToString("\n")
    }

    /** v2.0 异步（root doc《异步》）：`Flow { … }` → __flow(async generator 函数)。
     *  体是代码块（冷流）；emit(v) 生成 `yield v`，其余语句逐条生成。 */
    private fun flowJs(e: FlowExpr): String {
        val bodyJs = flowBodyStr(e.body)
        return "__flow(async function* () {\n$bodyJs\n})"
    }

    /** Flow 体 → async generator 函数体字符串：emit(v)（flowEmitHits 留痕）→ `yield v`，其余语句原样。 */
    private fun flowBodyStr(b: BlockExpr): String {
        val lines = ArrayList<String>()
        for (s in b.stmts) {
            if (s is ExprStmt && s.expr is CallExpr && flowEmitHits[s.expr as CallExpr] == true) {
                val c = s.expr as CallExpr
                val v = if (c.args.isEmpty()) "undefined" else expr(c.args[0])
                lines += "    yield $v;"
            } else {
                val t = stmtStr(s)
                if (t.isNotEmpty()) lines += "    $t"
            }
        }
        return lines.joinToString("\n")
    }

    /** v2.0 异步：内建 IO/sleep 挂起点 —— 检查器若登记本调用为挂起点则包 `await`（非挂起点原样） */
    private fun awaited(e: CallExpr, js: String): String =
        if (awaitHits[e] == true) "await $js" else js

    private fun unop(op: String): String = when (op) {
        "-", "!", "¬" -> op
        else -> "!"
    }

    private fun call(e: CallExpr): String {
        // 供给尖括号已在 InstExpr 擦除；此处 callee 可能是 NameRef 或已擦除的 InstExpr
        val callee = e.callee
        val name = when (callee) {
            is NameRef -> callee.name
            is InstExpr -> (callee.target as? NameRef)?.name ?: return "undefined"
            is FieldExpr -> callee.name   // O2（决策 68）：接收者调用 o.m(...) 的方法名
            else -> null
        }
        // 修复（#3）：@theorem 定理函数运行时擦除——值级调用 → null（编译期已注入后件事实）
        if (name != null) {
            val js = moduleHits[callee] ?: (callee as? NameRef)?.let { mangle(it.name) }
            if (js != null && js in theoremJsNames) return "null"
        }
        // P51：in 为普通函数——中缀 a in b 解析为 in(a, b)；值代码生成同原 BinExpr 语义
        // x in lo..hi → 闭区间比较；Range 变量成员由语义层拦（V1），兜底 true
        if (name == "in" && e.args.size == 2) {
            val rr = e.args[1]
            if (rr is BinExpr && rr.op == "..")
                return "(${expr(rr.left)} <= ${expr(e.args[0])} && ${expr(e.args[0])} <= ${expr(rr.right)})"
            return "true"
        }
        // 构造子/枚举名：Bool 的 true/false 特判
        if (name == "true") return "true"
        if (name == "false") return "false"
        // v2.0 基本库（内建 JS 实现）：数组化 List 的构造调用——Nil() → []、Cons(h,t) → [h, ...t]
        // （List 在 JS 层是数组；语义层 enum 声明与类型检查完全不动，纯表示变换）
        if (name != null && name in listCtorSet) {
            when (e.args.size) {
                0 -> return "[]"
                2 -> return "[${expr(e.args[0])}, ...(${expr(e.args[1])})]"
            }
        }
        // v2.0 异步（异步 §5.4）：Channel 创建 `Channel<T>()` / `Channel<T>(cap)` → new Channel(cap)
        if (name == "Channel")
            return "new Channel(${if (e.args.isEmpty()) "0" else expr(e.args[0])})"
        // v2.0 异步（root doc《异步》）：Mutex 创建 `Mutex()` → new Mutex()
        if (name == "Mutex" && e.args.isEmpty())
            return "new Mutex()"
        // 安卓内建（android doc/安卓标准库.md）：@android 无体声明的编译期 JS 桥接——
        // 桌面/node 走 __dshAndroid 兜底注入桩；webview 内由 KT 端注入实现。
        if (name != null && name in ANDROID_CALL_SPECIAL) {
            androidUsed = true
            val a = e.args.joinToString(", ") { expr(it) }
            return "__dshAndroid(\"" + name + "\", [" + a + "])"
        }
        if (name == "println" || name == "print") {
            // 修复（#8）：print 与 toStr 统一走 __dshtoStr（列表打印为 [] 形式，无空格）
            val a = e.args.joinToString(", ") { "__dshtoStr(${expr(it)})" }
            return "console.log($a)"
        }
        // P3 IO（v1.0 计划 §5）：node 原语，内联 require('fs')（node 模块缓存，无性能问题）
        // P5（决策 81）：readFile/writeFile/readLine 返回 Result[_, Str]——try/catch 包 Ok/Err（core/collection/result.subl）
        // v2.0 异步（异步 §6 阶段 6）：IO 标 @async——同步实现 + await 挂起点（await 非 Promise 值无害，但保持产物语义一致）
        if (name == "readLine" && e.args.isEmpty())
            return awaited(e, "(() => { try { const b = Buffer.alloc(4096); const n = require('fs').readSync(0, b, 0, 4096, null); if (n <= 0) return core_collection__Err(\"EOF\"); return core_collection__Ok(b.slice(0, n).toString('utf8').trim()); } catch (x) { return core_collection__Err(x.message); } })()")
        if (name == "readFile" && e.args.size == 1)
            return awaited(e, "(() => { try { return core_collection__Ok(require('fs').readFileSync(${expr(e.args[0])}, 'utf8')); } catch (x) { return core_collection__Err(x.message); } })()")
        if (name == "writeFile" && e.args.size == 2)
            return awaited(e, "(() => { try { require('fs').writeFileSync(${expr(e.args[0])}, ${expr(e.args[1])}); return core_collection__Ok(null); } catch (x) { return core_collection__Err(x.message); } })()")
        // v2.0 标准库补全：IO 扩展——appendFile/readDir 返回 Result（同 readFile 的 try/catch 包 Ok/Err）
        if (name == "appendFile" && e.args.size == 2)
            return awaited(e, "(() => { try { require('fs').appendFileSync(${expr(e.args[0])}, ${expr(e.args[1])}); return core_collection__Ok(null); } catch (x) { return core_collection__Err(x.message); } })()")
        if (name == "fileExists" && e.args.size == 1)
            return "require('fs').existsSync(${expr(e.args[0])})"
        if (name == "readDir" && e.args.size == 1)
            return awaited(e, "(() => { try { return core_collection__Ok(require('fs').readdirSync(${expr(e.args[0])})); } catch (x) { return core_collection__Err(x.message); } })()")
        if (name == "timeNow" && e.args.isEmpty()) return "Date.now()"
        if (name == "rand" && e.args.isEmpty()) return "Math.random()"
        if (name == "getArgs" && e.args.isEmpty())
            // v2.0 基本库（内建 JS 实现）：List 数组化后 getArgs 直接返回 JS 数组（List[Str] 表示 = 数组）
            return "process.argv.slice(2)"
        // v2.0 异步（异步 §6 阶段 6）：sleep(ms) 显式延时——Promise + setTimeout，恒为挂起点
        if (name == "sleep" && e.args.size == 1)
            return awaited(e, "new Promise(r => setTimeout(r, ${expr(e.args[0])}))")
        // ---- core.async 内建运行时层（@root 无体签名 → 编译期 JS 特判；全部 @async 挂起点）----
        // 内部局部名一律 __ 前缀（与 __dc/__flow 等运行时约定同层），避免内联实参表达式被 IIFE 内同名 const 遮蔽（TDZ）。
        // delay：sleep 的语义包装（显式延时，毫秒）。
        if (name == "delay" && e.args.size == 1)
            return awaited(e, "new Promise(r => setTimeout(r, ${expr(e.args[0])}))")
        // runBoth：先启动 f（不阻塞），主路径继续 g，join 等 f 收尾。
        if (name == "runBoth" && e.args.size == 2) {
            val f0 = expr(e.args[0]); val g0 = expr(e.args[1])
            return awaited(e, "(async () => { const __pf = Promise.resolve().then(() => ($f0)()); await Promise.resolve(); await ($g0)(); await __pf; })()")
        }
        // runSeq：先做完 f 再做 g（串行语义）。
        if (name == "runSeq" && e.args.size == 2) {
            val f0 = expr(e.args[0]); val g0 = expr(e.args[1])
            return awaited(e, "(async () => { await ($f0)(); await ($g0)(); })()")
        }
        // Flow 收集三件套：迭代冷流生成器（.gen()）。collectToList 发送序保持成表；collectCount 计数；collectAll 谓词短路。
        if (name == "collectToList" && e.args.size == 1) {
            val fl = expr(e.args[0])
            return awaited(e, "(async () => { const __out = []; for await (const __v of ($fl).gen()) __out.push(__v); return __out; })()")
        }
        if (name == "collectCount" && e.args.size == 1) {
            val fl = expr(e.args[0])
            return awaited(e, "(async () => { let __n = 0; for await (const __v of ($fl).gen()) __n = __n + 1; return __n; })()")
        }
        if (name == "collectAll" && e.args.size == 2) {
            val fl = expr(e.args[0]); val p0 = expr(e.args[1])
            return awaited(e, "(async () => { for await (const __v of ($fl).gen()) { if (! ($p0)(__v)) return false; } return true; })()")
        }
        // Mutex 临界区：withMutex = m.withLock(f)；bumpCounter/bumpPair 自增计数器（读-改-写复合操作）。
        if (name == "withMutex" && e.args.size == 2) {
            val m0 = expr(e.args[0]); val f0 = expr(e.args[1])
            return awaited(e, "($m0).withLock($f0)")
        }
        if (name == "bumpCounter" && e.args.size == 2) {
            val m0 = expr(e.args[0]); val c0 = expr(e.args[1])
            return awaited(e, "(async () => { await ($m0).withLock(() => { ($c0).n = ($c0).n + 1; }); })()")
        }
        if (name == "bumpPair" && e.args.size == 4) {
            val a0 = expr(e.args[0]); val a1 = expr(e.args[1]); val a2 = expr(e.args[2]); val a3 = expr(e.args[3])
            return awaited(e, "(async () => { await ($a0).withLock(() => { ($a2).n = ($a2).n + 1; }); await ($a1).withLock(() => { ($a3).n = ($a3).n + 1; }); })()")
        }
        // Channel 配套四件套：channelFromList（容量取列表大小，发完即关）、sendAll（逐一发送）、
        // channelDrain（收到关闭为止，FIFO）、channelPipe（src 收一个往 dst 发一个，src 关则关 dst）。
        if (name == "channelFromList" && e.args.size == 1) {
            val xs = expr(e.args[0])
            return awaited(e, "(async () => { const __xs = $xs; const __ch = new Channel(__xs.length); for (const __v of __xs) await __ch.send(__v); __ch.close(); return __ch; })()")
        }
        if (name == "sendAll" && e.args.size == 2) {
            val ch = expr(e.args[0]); val xs = expr(e.args[1])
            return awaited(e, "(async () => { for (const __v of ($xs)) await ($ch).send(__v); })()")
        }
        if (name == "channelDrain" && e.args.size == 1) {
            val ch = expr(e.args[0])
            return awaited(e, "(async () => { const __out = []; for (;;) { const __v = await ($ch).receive(); if (__v.tag === \"None\") break; __out.push(__v[0]); } return __out; })()")
        }
        if (name == "channelPipe" && e.args.size == 2) {
            val src = expr(e.args[0]); val dst = expr(e.args[1])
            return awaited(e, "(async () => { for (;;) { const __v = await ($src).receive(); if (__v.tag === \"None\") break; await ($dst).send(__v[0]); } ($dst).close(); })()")
        }
        // P1 字符串内建（v1.0 计划 §3）：JS 原生映射，与 print 同款特判（在内建之上、模块前缀留痕之前）
        if (name == "concat" && e.args.size == 2)
            return "(${expr(e.args[0])} + ${expr(e.args[1])})"
        if (name == "length" && e.args.size == 1)
            return "(${expr(e.args[0])}).length"
        if (name == "charAt" && e.args.size == 2)
            return "((${expr(e.args[0])})[${expr(e.args[1])}] ?? \"\")"
        if (name == "substring" && e.args.size == 3)
            return "(${expr(e.args[0])}).slice(${expr(e.args[1])}, ${expr(e.args[2])})"
        if (name == "strCmp" && e.args.size == 2) {
            val x = expr(e.args[0]); val y = expr(e.args[1])
            return "($x < $y ? -1 : ($x > $y ? 1 : 0))"
        }
        if (name == "toStr" && e.args.size == 1)
            // v3.0：枚举值 = 纯 tag 对象（{tag: 'D'}，单键）→ 取 tag 名成串（__dshtoStr 顶层 helper，await 表达式安全）
            return "__dshtoStr(${expr(e.args[0])})"
        if (name == "parseNat" && e.args.size == 1)
            return "(() => { const m = String(${expr(e.args[0])}).match(/^(0|[1-9][0-9]*)$/); return m ? Some(Number(m[0])) : None(); })()"
        // v2.0 标准库补全：字符串扩展——JS 原生映射（strSplit 返回 JS 数组 = 数组化 List[Str]）
        if (name == "strUpper" && e.args.size == 1) return "(${expr(e.args[0])}).toUpperCase()"
        if (name == "strLower" && e.args.size == 1) return "(${expr(e.args[0])}).toLowerCase()"
        if (name == "strTrim" && e.args.size == 1) return "(${expr(e.args[0])}).trim()"
        if (name == "startsWith" && e.args.size == 2) return "(${expr(e.args[0])}).startsWith(${expr(e.args[1])})"
        if (name == "endsWith" && e.args.size == 2) return "(${expr(e.args[0])}).endsWith(${expr(e.args[1])})"
        if (name == "strReplace" && e.args.size == 3)
            return "((${expr(e.args[0])}).split(${expr(e.args[1])})).join(${expr(e.args[2])})"
        if (name == "strSplit" && e.args.size == 2) return "(${expr(e.args[0])}).split(${expr(e.args[1])})"
        if (name == "strIndexOf" && e.args.size == 2) {
            val s = expr(e.args[0]); val p = expr(e.args[1])
            return "(() => { const i = ($s).indexOf($p); return i === -1 ? None() : Some(i); })()"
        }
        // P10 浮点数 Rat（决策 86，候选 A：IEEE double via JS Number）：JS 原生映射
        if (name == "toRat" && e.args.size == 1)
            return "Number(${expr(e.args[0])})"
        if (name == "toReal" && e.args.size == 1)
            return "Number(${expr(e.args[0])})"
        if (name == "parseRat" && e.args.size == 1)
            return "(() => { const v = Number(String(${expr(e.args[0])})); return Number.isNaN(v) ? None() : Some(v); })()"
        if (name == "abs" && e.args.size == 1) return "Math.abs(${expr(e.args[0])})"
                if (name == "floor" && e.args.size == 1) return "Math.floor(${expr(e.args[0])})"
        if (name == "ceil" && e.args.size == 1) return "Math.ceil(${expr(e.args[0])})"
        if (name == "pow" && e.args.size == 2) return "Math.pow(${expr(e.args[0])}, ${expr(e.args[1])})"
        if (name == "panic") return "(() => { throw new Error('panic: unreachable') })()"
        // v2.0 标准库补全：Math 扩展
        if (name == "mathMin" && e.args.size == 2) return "Math.min(${expr(e.args[0])}, ${expr(e.args[1])})"
        if (name == "mathMax" && e.args.size == 2) return "Math.max(${expr(e.args[0])}, ${expr(e.args[1])})"
        if (name == "mathClamp" && e.args.size == 3)
            return "Math.min(Math.max(${expr(e.args[0])}, ${expr(e.args[1])}), ${expr(e.args[2])})"
        if (name == "mathRound" && e.args.size == 1) return "Math.round(${expr(e.args[0])})"
        if (name == "mathSin" && e.args.size == 1) return "Math.sin(${expr(e.args[0])})"
        if (name == "mathCos" && e.args.size == 1) return "Math.cos(${expr(e.args[0])})"
        if (name == "mathTan" && e.args.size == 1) return "Math.tan(${expr(e.args[0])})"
        // v2.0 基本库（内建 JS 实现）：JSON —— 编译成 JS 原生 JSON.parse / 对象·数组访问；失败返回 None（Optional 包装）
        // jsonParse 解析失败（语法错/非法 JSON）→ None()，不抛异常——与语言"无运行时异常"哲学一致
        // 临时变量名用 __jv + 计数器，避免实参恰叫 v/o 时 TDZ（const v = v）
        if (name == "jsonParse" && e.args.size == 1)
            return "(() => { try { return Some(JSON.parse(${expr(e.args[0])})); } catch (x) { return None(); } })()"
        if (name == "jsonGet" && e.args.size == 2) {
            val j = expr(e.args[0]); val k = expr(e.args[1]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; const v = ($n == null) ? undefined : $n[$k]; return (v === undefined ? None() : Some(v)); })()"
        }
        if (name == "jsonAt" && e.args.size == 2) {
            val j = expr(e.args[0]); val i = expr(e.args[1]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; const v = ($n == null) ? undefined : $n[$i]; return (v === undefined ? None() : Some(v)); })()"
        }
        if (name == "jsonLen" && e.args.size == 1) {
            val j = expr(e.args[0]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; if ($n == null) return None(); if (Array.isArray($n)) return Some($n.length); if (typeof $n === \"object\") return Some(Object.keys($n).length); return None(); })()"
        }
        if (name == "jsonStr" && e.args.size == 1) {
            val j = expr(e.args[0]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; return (typeof $n === \"string\") ? Some($n) : None(); })()"
        }
        if (name == "jsonNum" && e.args.size == 1) {
            val j = expr(e.args[0]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; return (typeof $n === \"number\") ? Some($n) : None(); })()"
        }
        if (name == "jsonBool" && e.args.size == 1) {
            val j = expr(e.args[0]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; return (typeof $n === \"boolean\") ? Some($n) : None(); })()"
        }
        if (name == "jsonIsNull" && e.args.size == 1) return "(${expr(e.args[0])} === null)"
        if (name == "jsonIsArr" && e.args.size == 1) return "Array.isArray(${expr(e.args[0])})"
        if (name == "jsonToStr" && e.args.size == 1) return "JSON.stringify(${expr(e.args[0])})"
        // v2.0 基本库：JSON 数组 → List[Json]（List 数组化零转换）；非数组 → None
        if (name == "jsonToList" && e.args.size == 1) {
            val j = expr(e.args[0]); val n = "__jv${subjCounter++}"
            return "(() => { const $n = $j; return Array.isArray($n) ? Some($n) : None(); })()"
        }
        // v2.0 基本库（内建 JS 实现）：**List 方法特判成 JS 原生数组方法**——
        // P9：List 方法映射改走 checker 留痕（listMethodHits：调用点已由 Checker 判定为 core 标准库数组化 List 方法），
        // 不再在 codegen 硬编码模块前缀字符串比较——仅命中已留痕的方法名才走原生数组方法。
        // 数组化 List = JS 数组：map→.map、filter→.filter、fold→.reduce、size→.length……语义同旧（函数式、无原地修改）。
        val listMethodName = listMethodHits[e]
        if (name != null && listMethodName == name) {
            val eArgs = sugarArgs[e] ?: e.args   // H3：点号方法糖的接收者/实参在 sugarArgs（xs.map(f) → [xs, f]），裸调用在 e.args
            val xs = if (eArgs.isNotEmpty()) expr(eArgs[0]) else "undefined"
            when (name) {
                // —— 元信息：O(1) 原生 ——
                "size", "listLength" -> if (eArgs.size == 1) return "($xs).length"
                "isEmpty" -> if (eArgs.size == 1) return "($xs).length === 0"
                // —— 访问：Optional 包装（缺失键返回 None，无运行时异常）——
                "head" -> if (eArgs.size == 1) return "(() => { const a = $xs; return a.length ? Some(a[0]) : None(); })()"
                "last" -> if (eArgs.size == 1) return "(() => { const a = $xs; return a.length ? Some(a[a.length - 1]) : None(); })()"
                "elementAt" -> if (eArgs.size == 2) {
                    val i = expr(eArgs[1])
                    return "(() => { const a = $xs; const i = $i; return i >= 0 && i < a.length ? Some(a[i]) : None(); })()"
                }
                // —— 函数式变换：返回新数组（不可变，无副作用）——
                "map" -> if (eArgs.size == 2) return "($xs).map(${expr(eArgs[1])})"
                "filter" -> if (eArgs.size == 2) return "($xs).filter(${expr(eArgs[1])})"
                "flatMap" -> if (eArgs.size == 2) return "($xs).flatMap(${expr(eArgs[1])})"
                "reverse" -> if (eArgs.size == 1) return "($xs).slice().reverse()"
                "append" -> if (eArgs.size == 2) return "($xs).concat(${expr(eArgs[1])})"
                "take" -> if (eArgs.size == 2) return "($xs).slice(0, ${expr(eArgs[1])})"
                "drop" -> if (eArgs.size == 2) return "($xs).slice(${expr(eArgs[1])})"
                "fold" -> if (eArgs.size == 3) {
                    // fold(xs, init, f) 中 f: (T, U) => U、累加顺序 f(元素, acc) —— JS reduce 回调是 (acc, 元素)，
                    // 包一层翻转参数序，保持语言 fold 语义不变。f 是 lambda 表达式文本，须包括号后调用 `($f)(...)`，
                    // 否则生成 `(acc, x) => (x, acc) => (acc + x)(x, acc)` 被解析成 lambda 直接调用。
                    val init = expr(eArgs[1]); val f = expr(eArgs[2])
                    return "($xs).reduce((acc, x) => ($f)(x, acc), $init)"
                }
                // —— 元素查询/统计：语义直译 ——
                "contains" -> if (eArgs.size == 2) return "($xs).includes(${expr(eArgs[1])})"
                "count" -> if (eArgs.size == 2) return "($xs).filter(${expr(eArgs[1])}).length"
                // —— v2.0 基本库补全（2026-10-10）：any/all/indexOf/slice/sortBy ——
                "any" -> if (eArgs.size == 2) return "($xs).some(${expr(eArgs[1])})"
                "all" -> if (eArgs.size == 2) return "($xs).every(${expr(eArgs[1])})"
                "indexOf" -> if (eArgs.size == 2) {
                    val x = expr(eArgs[1])
                    return "(() => { const i = ($xs).indexOf($x); return i === -1 ? None() : Some(i); })()"
                }
                "slice" -> if (eArgs.size == 3) return "($xs).slice(${expr(eArgs[1])}, ${expr(eArgs[2])})"
                "sortBy" -> if (eArgs.size == 2) { val f = expr(eArgs[1]); return "($xs).slice().sort((a, b) => ($f)(a, b) ? -1 : 1)" }
                "joinToString" -> if (eArgs.size == 2) return "($xs).map(x => String(x)).join(${expr(eArgs[1])})"
                "sum" -> if (eArgs.size == 1) return "($xs).reduce((a, b) => a + b, 0)"
                "forEach" -> if (eArgs.size == 2) { val f = expr(eArgs[1]); return "($xs).forEach(x => ($f)(x))" }
            }
        }
        // v3.0：setOf（@list 变长实参）→ SetCons 链（core 标准库 Set 是结构枚举：SetCons(h, t)）
        if (name == "setOf" && moduleHits[callee] != null) {
            val modP = moduleHits.getValue(callee).substringBeforeLast("__setOf")
            val nil = if (modP.isEmpty()) "SetNil" else "${modP}__SetNil"
            val cons = if (modP.isEmpty()) "SetCons" else "${modP}__SetCons"
            var acc = "$nil()"
            for (i in e.args.indices.reversed()) acc = "$cons(${expr(e.args[i])}, $acc)"
            return acc
        }
        // listOf（@list 变长实参）→ JS 数组字面量（List 是数组形态：[] / [h, ...t]）
        if (name == "listOf" && moduleHits[callee] != null) {
            return "[" + e.args.joinToString(", ") { expr(it) } + "]"
        }
        // 元组构造（决策 62）：prelude 内建，无用户声明对应
        if (name == "emptyTuple") return "[]"
        if (name == "singleTuple") return "[${e.args.joinToString(", ") { expr(it) }}]"
        // @list 形参调用（Kotlin vararg）：从该形参位置起的所有实参打包成 JS 数组字面量（List 为数组形态）。
        val lp = if (name != null) (listParamPos[moduleHits[callee] ?: moduleJsName(currentPrefix, name)] ?: -1) else -1
        if (lp >= 0 && e.args.size >= lp) {
            val fixed = e.args.take(lp).joinToString(", ") { expr(it) }
            val dropped = e.args.drop(lp)
            val packed = "[" + dropped.joinToString(", ") { expr(it) } + "]"
            val argsJs = listOf(fixed, packed).filter { it.isNotEmpty() }.joinToString(", ")
            val calleeJs = when (callee) {
                is NameRef -> if (callee.name.contains("::")) mangle(callee.name.replace("::", "__"))
                    else moduleHits[callee] ?: mangle(callee.name)
                is FieldExpr -> moduleHits[callee] ?: "${expr(callee.target)}.${mangle(callee.name)}"
                is InstExpr -> expr(callee.target)
                else -> expr(callee)
            }
            val callJs = "$calleeJs($argsJs)"
            val withAwait = if (awaitHits[e] == true) "await $callJs" else callJs
            return if (receiveSmartHits[e] == true) "($withAwait)[0]" else withAwait
        }
        // 决策 60/68（O2）：类型类方法调用按调用点查字典分发，方法体签名为 (d, __self, args…)。
        // 点号形态 o.m(args) 与自由式 m(o, args) 统一补齐隐藏字典 d 与接收者 __self。
        // P0：限定方法 `core.show(p)` 用 MODULE_METHOD_MARKER 标记——首实参即接收者（自由形态语义）。
        // P6（决策 82）：约束槽方法调用 `x.show()`（x: T, T: Show）→ `d_Show_T.show(d_Show_T, x)`
        // v3.0 特性 3：Range 内建方法——成员访问 `recv.name(args...)`（__range 对象自带方法）
        if (rangeHits[e] != null && callee is FieldExpr) {
            val recv = expr(callee.target)
            val ajs = e.args.joinToString(", ") { expr(it) }
            return recv + "." + name + "(" + ajs + ")"
        }
        val consSlot = consDicts[e]
        if (consSlot != null && name != null) {
            val selfJs = if (callee is FieldExpr) expr(callee.target)
                else if (e.args.isEmpty()) "undefined" else expr(e.args[0])
            val argsJs = if (callee is FieldExpr) e.args.drop(1) else e.args
            val ra2 = argsJs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
            val all = listOf(consSlot, selfJs) + (if (ra2.isEmpty()) emptyList() else listOf(ra2))
            return "$consSlot.${mangle(name)}(${all.joinToString(", ")})"
        }
        val dn = dicts[e]
        if (dn != null && name != null) {
            // HKT（HKT-D2）点号式 `xs.map(f)`：字典签名 (d, f, fa) 无 __self——接收者 xs 在 kind 应用位
            // （fa），实参照形参序 [f, xs]；直接 `dn.map(dn, f, xs)`，不做首参位 selfJs。
            val hktPos = hktKindPosHits[e]
            if (hktPos != null && callee is FieldExpr) {
                val arr = e.args.toMutableList().apply { add(hktPos.coerceIn(0, size), callee.target) }
                val ra = arr.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
                return "$dn.${mangle(name)}($dn, $ra)"
            }
            val selfJs: String; val realArgs: List<Expr>
            if (moduleHits[callee] == MODULE_METHOD_MARKER) { selfJs = expr(e.args[0]); realArgs = e.args.drop(1) }
            else if (callee is FieldExpr) { selfJs = expr(callee.target); realArgs = e.args }
            else {
                if (e.args.isEmpty()) return "$dn.${mangle(name)}($dn, undefined)"
                selfJs = expr(e.args[0]); realArgs = e.args.drop(1)
            }
            val ra = realArgs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
            val all = listOf(dn, selfJs) + if (ra.isEmpty()) emptyList() else listOf(ra)
            return "$dn.${mangle(name)}(${all.joinToString(", ")})"
        }
        // P8（决策 84）：命名参数调用点用检查后规范化的实参列表（按形参声明顺序重排）
        val genArgs: List<Expr> = sugarArgs[e] ?: (namedArgOrders[e] ?: e.args)
        val args = genArgs.joinToString(", ") { if (it is VarExpr) varExprJs(it) else expr(it) }
        val calleeJs = when (callee) {
            // v2.1：E::A 复合构造子名绕过留痕直取裸名（moduleHits 可能存了 mangle 全名）
            is NameRef -> if (callee.name.contains("::")) mangle(callee.name.substringAfterLast("::"))
                else moduleHits[callee] ?: mangle(callee.name)
            is FieldExpr -> moduleHits[callee] ?: "${expr(callee.target)}.${mangle(callee.name)}"   // 非方法字段（函数值字段）调用
            is InstExpr -> expr(callee.target)
            else -> expr(callee)
        }
        // P6（决策 82）：带约束泛型函数调用——前插实际字典实参（f(dict_Show_Nat, xs)）。
        // 仅 NameRef/InstExpr 直接定位（FieldExpr 字段值是函数值，无约束函数概念）。
        val preDicts = if (callee is FieldExpr) "" else (dictSubArgs[e] ?: emptyList()).joinToString(", ")
        val allArgs = listOf(preDicts, args).filter { it.isNotEmpty() }.joinToString(", ")
        val callJs = "$calleeJs($allArgs)"
        // v2.0 异步（决策 93）：挂起点调用（@async 体内调 @async 函数）生成 `await fn(...)`
        val withAwait = if (awaitHits[e] == true) "await $callJs" else callJs
        // v2.0 异步（CH-5）：receive 智能转换——本地未关闭通道的 receive 返回 T，运行时解包 Some 值
        return if (receiveSmartHits[e] == true) "($withAwait)[0]" else withAwait
    }

    /** O3（决策 69）：命名字段构造 `A(name = e)` → 位置实参工厂调用，缺省字段传 undefined 取 JS 参数默认值；
     *  工厂名带当前模块前缀（StructCtorExpr 恒为同模块裸名引用，P0） */
    private fun structCtorJs(e: StructCtorExpr): String {
        val fields = structFields[e.struct] ?: emptyList()
        val given = e.assigns.associate { (n, v) -> n to expr(v) }
        val positional = fields.map { given[it] ?: "undefined" }
        return "${moduleJsName(currentPrefix, e.struct)}(${positional.joinToString(", ")})"
    }

    private fun blockIIFE(e: BlockExpr): String = blockStr(e)

    /** 语句转单行字符串（供 IIFE 内联；var/assign/expr 足够，axiom/by 擦除为空） */
    private fun stmtStr(s: Stmt): String = when (s) {
        is BTSStmt, is WhenPropStmt, is StrategyStmt, is IfPropStmt, is ExistIntroStmt, is ExistsElimStmt, is FunStmt -> ""
        is VarStmt -> s.destruct?.let { d ->
            "${if ("mut" in s.annotations) "let" else "const"} [${d.joinToString(", ") { mangle(it) }}] = ${dcWrap(s, expr(s.value))};"
        } ?: "${if ("mut" in s.annotations) "let" else "const"} ${mangle(s.name)} = ${dcWrap(s, expr(s.value))};"
        is AssignStmt -> "${expr(s.target)} = ${dcWrap(s, expr(s.value))};"
        is ExprStmt -> "${expr(s.expr)};"
        is UncheckedStmt -> stmtStr(s.inner)
        is AxiomStmt, is ByStmt -> ""
        // 第四轮：值位块的语句串化——语义层已禁止值位 return（E-RETURN-OUTSIDE），
        // 此分支是防御性正确语义（IIFE 内 return 即该块的值）
        is ReturnStmt -> s.expr?.let { "return ${expr(it)};" } ?: "return null;"
    }

    private fun ifExpr(e: IfExpr): String {
        val cond = expr(e.cond)
        val th = blockStr(e.thenBlock)
        val el = e.elseBlock?.let { blockStr(it) }
        return if (el != null) "($cond ? $th : $el)" else "($cond ? $th : null)"
    }

    /** 块 → JS 表达式（IIFE 字符串） */
    private fun blockStr(e: BlockExpr): String {
        val tail = e.stmts.lastOrNull() as? ExprStmt
        val lines = ArrayList<String>()
        for (s in e.stmts) if (s !== tail) { val t = stmtStr(s); if (t.isNotEmpty()) lines += t }
        val ret = if (tail != null) "return ${expr(tail.expr)};" else "return null;"
        return "(() => {\n" + (lines + ret).joinToString("\n") { "  $it" } + "\n})()"
    }

    // ---------- v1.1：lambda 语句体（Kotlin 风格：块内可 return / var / 嵌套 if） ----------

    /** lambda 块体 → JS 箭头函数体 `{ … }`：尾表达式转 return（lambda 默认返回最后表达式） */
    private fun lambdaBodyStr(body: BlockExpr): String {
        val lines = ArrayList<String>()
        for (i in body.stmts.indices) {
            val s = body.stmts[i]
            val isTail = i == body.stmts.lastIndex
            when {
                isTail && s is ExprStmt && !diverges(s.expr) -> lines += "  return ${expr(s.expr)};"
                isTail && s is ExprStmt && diverges(s.expr) -> lambdaStmtToLines(s, lines, "  ")
                isTail && s is ReturnStmt -> lambdaStmtToLines(s, lines, "  ")
                else -> lambdaStmtToLines(s, lines, "  ")
            }
        }
        return if (lines.isEmpty()) "{}" else "{\n" + lines.joinToString("\n") + "\n}"
    }

    /** lambda 体内单条语句 → 缩进行（if 展平为真语句，return 原样发射） */
    private fun lambdaStmtToLines(s: Stmt, out: MutableList<String>, ind: String) {
        when (s) {
            is BTSStmt, is WhenPropStmt, is StrategyStmt, is IfPropStmt, is ExistIntroStmt, is ExistsElimStmt, is FunStmt -> {}
            is VarStmt -> {
                val destruct = s.destruct
                if (destruct != null)
                    out += "$ind${if ("mut" in s.annotations) "let" else "const"} [${destruct.joinToString(", ") { mangle(it) }}] = ${dcWrap(s, expr(s.value))};"
                else out += "$ind${if ("mut" in s.annotations) "let" else "const"} ${mangle(s.name)} = ${dcWrap(s, expr(s.value))};"
            }
            is AssignStmt -> out += "$ind${expr(s.target)} = ${dcWrap(s, expr(s.value))};"
            is ExprStmt -> if (s.expr is IfExpr) lambdaIfLines(s.expr as IfExpr, out, ind)
                else if (s.expr is WhenExpr) lambdaWhenLines(s.expr as WhenExpr, out, ind)   // 含 ifis 脱糖形态：return 穿透 lambda
                else out += "$ind${expr(s.expr)};"
            is ReturnStmt -> out += "$ind${s.expr?.let { "return ${expr(it)};" } ?: "return;"}"
            is UncheckedStmt -> lambdaStmtToLines(s.inner, out, ind)
            is AxiomStmt, is ByStmt -> {}
        }
    }

    /** lambda 体内全路径 return 的 if：展平为真 JS if（分支 return 穿透 lambda） */
    private fun lambdaIfLines(e: IfExpr, out: MutableList<String>, ind: String) {
        out += "$ind if (${expr(e.cond)}) {"
        e.thenBlock.stmts.forEach { lambdaStmtToLines(it, out, "$ind  ") }
        val elseBlock = e.elseBlock
        if (elseBlock != null) {
            out += "$ind} else {"
            elseBlock.stmts.forEach { lambdaStmtToLines(it, out, "$ind  ") }
        }
        out += "$ind}"
    }

    /** 语句位 when（含 ifis 脱糖形态 `if (o is Some(e)) …`）展平为真 JS if 链——决策 75 的 when 版：
     *  主题 const + 顺序 else-if 短路链（不可达分支才落到链尾），模式绑定 const 在分支内，
     *  分支体的提前 return 因此能真正退出函数（值位 when 仍走 whenExpr 的 IIFE）。
     *  守卫与 whenExpr 同处理：守卫可能引用模式绑定 → 先绑定、再判守卫，守卫失败落下一分支。 */
    private fun whenStmtFlatten(e: WhenExpr) {
        val subject = e.subject
        val subjJs = if (subject == null) "" else e.subjectBinding?.let { mangle(it) } ?: "__s${subjCounter++}"
        if (subject != null) line("const $subjJs = ${expr(subject)};")
        var started = false
        var brace = false
        for (arm in e.arms) {
            val conds = ArrayList<String>()
            val binds = ArrayList<Pair<String, String>>()
            if (subject == null) arm.cond?.let { conds += expr(it) } else matchJs(arm.pattern, subjJs, conds, binds)
            val guard = arm.guard
            val condPart = conds.joinToString(" && ") { "($it)" }.ifEmpty { null }
            if (condPart == null && guard == null && !started) {
                armBodyFlat(arm, binds)   // 退化：首分支即兜底（主题 when），无条件直发
                return
            }
            if (guard != null && binds.isNotEmpty()) {
                // 先绑定、再判守卫：守卫失败落下一分支（与 whenExpr 的结构对齐）
                line(if (started) "} else if ($condPart) {" else "if ($condPart) {")
                brace = true
                indent++
                binds.forEach { line("const ${mangle(it.first)} = ${it.second};") }
                line("if (${expr(guard)}) {")
                indent++
                armBodyFlat(arm, emptyList())
                indent--
                line("}")
                indent--
            } else {
                val cond = when {
                    condPart == null -> null                 // 兜底
                    guard == null -> condPart
                    else -> "$condPart && (${expr(guard)})"
                }
                line(when {
                    cond == null -> "} else {"
                    !started -> "if ($cond) {"
                    else -> "} else if ($cond) {"
                })
                brace = true
                indent++
                armBodyFlat(arm, binds)
                indent--
            }
            started = true
        }
        if (brace) line("}")
    }

    /** whenStmtFlatten 的分支体：模式绑定（已汇集）+ 分支语句逐条经 genStmt（嵌套 if/when 递归展平） */
    private fun armBodyFlat(arm: WhenArm, binds: List<Pair<String, String>>) {
        binds.forEach { line("const ${mangle(it.first)} = ${it.second};") }
        val body = arm.body
        if (body is BlockExpr) body.stmts.forEach { genStmt(it) } else line("${expr(body)};")
    }

    /** lambda 体内语句位 when（含 ifis 脱糖形态）展平为真 JS if 链——lambdaWhenLines 的语句流版本 */
    private fun lambdaWhenLines(e: WhenExpr, out: MutableList<String>, ind: String) {
        val subject = e.subject
        val subjJs = if (subject == null) "" else e.subjectBinding?.let { mangle(it) } ?: "__s${subjCounter++}"
        if (subject != null) out += "$ind const $subjJs = ${expr(subject)};"
        var started = false
        for (arm in e.arms) {
            val conds = ArrayList<String>()
            val binds = ArrayList<Pair<String, String>>()
            if (subject == null) arm.cond?.let { conds += expr(it) } else matchJs(arm.pattern, subjJs, conds, binds)
            val guard = arm.guard
            val condPart = conds.joinToString(" && ") { "($it)" }.ifEmpty { null }
            if (condPart == null && guard == null && !started) {
                binds.forEach { out += "$ind const ${mangle(it.first)} = ${it.second};" }
                armBodyLambda(arm, out, ind)
                return
            }
            if (guard != null && binds.isNotEmpty()) {
                out += "$ind${if (started) "} else if ($condPart) {" else "if ($condPart) {"}"
                binds.forEach { out += "$ind  const ${mangle(it.first)} = ${it.second};" }
                out += "$ind  if (${expr(guard)}) {"
                armBodyLambda(arm, out, "$ind    ")
                out += "$ind  }"
            } else {
                val cond = when {
                    condPart == null -> null
                    guard == null -> condPart
                    else -> "$condPart && (${expr(guard)})"
                }
                out += "$ind" + when {
                    cond == null -> "} else {"
                    !started -> "if ($cond) {"
                    else -> "} else if ($cond) {"
                }
                binds.forEach { out += "$ind  const ${mangle(it.first)} = ${it.second};" }
                armBodyLambda(arm, out, "$ind  ")
            }
            started = true
        }
        if (started) out += "$ind}"
    }

    private fun armBodyLambda(arm: WhenArm, out: MutableList<String>, ind: String) {
        val body = arm.body
        if (body is BlockExpr) body.stmts.forEach { lambdaStmtToLines(it, out, ind) }
        else out += "$ind${expr(body)};"
    }

    private fun whenExpr(e: WhenExpr): String {
        // v3.0（无主题 when）：subject 为空时按条件链生成（cond 进入条件）；否则先绑定主题
        val sname = "__s${subjCounter++}"
        val subject = e.subject
        val subjectBinding = e.subjectBinding
        val subjJs = if (subject == null) "true"
            else if (subjectBinding != null) mangle(subjectBinding) else sname
        val lines = ArrayList<String>()
        if (subject != null)
            lines += if (subjectBinding != null) "const ${mangle(subjectBinding)} = ${expr(subject)};"
                     else "const $sname = ${expr(subject)};"
        var closed = false
        for (arm in e.arms) {
            val conds = ArrayList<String>()
            val binds = ArrayList<Pair<String, String>>()
            if (e.subject == null) {
                arm.cond?.let { conds += expr(it) }   // 无主题：布尔条件进条件位
            } else matchJs(arm.pattern, subjJs, conds, binds)
            val body = arm.body
            val bodyJs = if (body is BlockExpr) blockStr(body) else expr(body)
            val bindJs = binds.joinToString("") { "const ${mangle(it.first)} = ${it.second}; " }
            val condStr = if (conds.isEmpty()) null else conds.joinToString(" && ") { "($it)" }
            val guard = arm.guard
            if (condStr == null && guard == null) {   // 无守卫的兜底分支：直接 return
                lines += "$bindJs return $bodyJs;"
                closed = true
                break
            }
            // 有守卫：守卫可能引用模式绑定变量（如 `m if !=(m,0)`），故必须**先绑定、再判守卫**——
            // 结构 `if (cond) { binds; if (guard) return body; }`（旧版把守卫并进外层条件会用到未绑定的 m）。
            when {
                guard != null && bindJs.isNotEmpty() ->
                    lines += "if (${condStr ?: "true"}) { $bindJs if (${expr(guard)}) return $bodyJs; }"
                guard != null ->
                    lines += "if (${condStr ?: "true"} && (${expr(guard)})) { return $bodyJs; }"
                else ->
                    lines += "if ($condStr) { $bindJs return $bodyJs; }"
            }
        }
        if (!closed) lines += "return null;"   // 穷尽时不可达，语义层已保证
        return "(() => {\n" + lines.joinToString("\n") { "  $it" } + "\n})()"
    }

    /**
     * 模式 → JS 条件与字段绑定（路径式，支持嵌套）：
     * `Some(Some(x))` → cond `s.tag==="Some" && s[0].tag==="Some"`，bind `x = s[0][0]`。
     * 裸名若是已知构造子按标签比较，否则按绑定变量（与语义层 patternBoundNames 同规则）。
     */
    private fun matchJs(p: Pattern, path: String, conds: MutableList<String>, binds: MutableList<Pair<String, String>>) {
        when (p) {
            is PatElse -> {}
            is PatCond -> {}   // v3.0：无主题条件分支不参与模式匹配
            is PatBind -> if (p.name != "_") binds += p.name to path
            is PatLit -> conds += "$path === ${expr(p.value)}"
            is PatIs -> {
                conds += isCond(p.type, path)
                p.bind?.let { binds += it to path }
            }
            is PatTuple -> {
                // 元组值即 JS 数组：长度相等 + 分量逐个匹配（决策 61）
                conds += "(Array.isArray($path) && $path.length === ${p.items.size})"
                p.items.forEachIndexed { i, ip -> matchJs(ip, "$path[$i]", conds, binds) }
            }
            is PatCtor -> {
                // v2.1：`E::A` 复合构造子名归一化为裸名（工厂函数与 tag 均为裸名）
                val cn = p.name.substringAfterLast("::")
                // v2.0 基本库（内建 JS 实现）：数组化 List 的模式解构——Nil → 空数组、Cons(h,t) → [0]/slice(1)
                if (cn in listCtorSet) {
                    if (p.args.isEmpty()) {
                        conds += "(Array.isArray($path) && $path.length === 0)"
                    } else {
                        conds += "(Array.isArray($path) && $path.length >= 1)"
                        p.args.forEachIndexed { i, ap -> matchJs(ap, if (i == 0) "$path[0]" else "$path.slice(1)", conds, binds) }
                    }
                    return
                }
                if (p.args.isEmpty()) {
                    if (cn in ctorNames) when (cn) {
                        "true", "false" -> conds += "$path === ${cn}"
                        "null" -> conds += "$path === null"
                        else -> conds += "$path.tag === ${q(cn)}"
                    } else binds += cn to path   // 绑定变量
                } else {
                    conds += "$path.tag === ${q(cn)}"
                    // root doc《模式匹配》：结构体解构 `is T(a,b)`——结构体对象字段按**名字**存储
                    // （工厂 `{ tag, fieldName: ... }`），枚举构造子按位置存储；这里按结构体字段名访问。
                    val sfields = structFields[cn]
                    if (sfields != null) {
                        p.args.forEachIndexed { i, ap -> if (i < sfields.size) matchJs(ap, "$path.${mangle(sfields[i])}", conds, binds) }
                    } else {
                        p.args.forEachIndexed { i, ap -> matchJs(ap, "$path[$i]", conds, binds) }
                    }
                }
            }
        }
    }

    private fun isCond(t: Type, subjJs: String): String {
        if (t.name in setOf("Nat", "Int", "Rat", "Str", "Bool")) {
            return when (t.name) {
                "Nat", "Int", "Rat" -> "(typeof $subjJs === \"number\")"
                "Str" -> "(typeof $subjJs === \"string\")"
                else -> "(typeof $subjJs === \"boolean\")"
            }
        }
        // root doc《模式匹配》：`is T` 对枚举/结构体的结构判定——__isa 按 tag 判名义类型
        // （结构体/枚举工厂均产出 { tag, ... }；List 特判 Array.isArray，见 __isa）。
        return "__isa($subjJs, ${q(t.name)})"
    }

    private fun anonFun(e: AnonFunExpr): String {
        val ps = e.params.joinToString(", ") { mangle(it.name) }
        val body = e.body
        return if (body is BlockExpr) {
            val tail = body.stmts.lastOrNull() as? ExprStmt
            val lines = ArrayList<String>()
            for (s in body.stmts) if (s !== tail) { val t = stmtStr(s); if (t.isNotEmpty()) lines += t }
            val ret = if (tail != null) "return ${expr(tail.expr)};" else "return null;"
            "($ps) => {\n" + (lines + ret).joinToString("\n") { "  $it" } + "\n}"
        } else "($ps) => ${expr(body ?: NameRef("null"))}"
    }

    // ---------- 工具 ----------

    /** 命题项的可读标签（axiom/by 审计注释用） */
    private fun tag(e: Expr): String = when (e) {
        is NameRef -> e.name
        is IntLit -> e.value
        is FloatLit -> e.value
        is StrLit -> q(e.value)
        ReturnSym -> "$"
        is InstExpr -> "${tag(e.target)}<${e.terms.joinToString(", ") { tag(it) }}>"
        is BinExpr -> "${tag(e.left)} ${e.op} ${tag(e.right)}"
        is UniExpr -> "${e.op}${tag(e.operand)}"
        is CallExpr -> "${tag(e.callee)}(${e.args.joinToString(", ") { tag(it) }})"
        is QuantExpr -> "${e.q} ${e.names.joinToString(",")}. ${tag(e.body)}"
        else -> "?"
    }

    /** 名字改写：避开 JS 关键字/保留字，符号名转合法标识符 */
    /** 名字 → 合法 JS 标识符（保留字加 $ 前缀；符号名转 op_xxxx）。与 checker 共用同一实现。 */
    private fun mangle(n: String): String = jsMangle(n)

    /** 字符串字面量转 JS：U+0000–U+001F 控制字符与 U+2028/2029（JS 行终止符）一律 \uXXXX 转义（审查报告 §2.10）。 */
    private fun q(s: String): String {
        val esc = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
            .replace(Regex("[\\u0000-\\u001f\\u2028\\u2029]")) { m ->
                "\\u" + m.value[0].code.toString(16).padStart(4, '0')
            }
        return "\"" + esc + "\""
    }

    private fun line(s: String) { sb.append("  ".repeat(indent)).append(s).append("\n") }
    private fun blank() { sb.append("\n") }
    private fun comment(s: String) { sb.append("  ".repeat(indent)).append("// ").append(s).append("\n") }
}
