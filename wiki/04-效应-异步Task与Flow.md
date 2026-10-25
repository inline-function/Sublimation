# 效应·异步 Task 与 Flow

> wiki/04-效应-异步Task与Flow · 主题域：@async 注解 / Task 惰性任务 / Flow 冷流（emit·collect·map）/ runBoth·runSeq·delay / collectAll / 自动 await / async-in-sync 闸 / core.async 模块边界 / IO 家族 @async（P5 决策 81）
> 事实来源：_langfacts.md §2/§3/§6/§7/§8；core/async/async.subl（15 行全文）、task.subl（8）、channel.subl（18）、mutex.subl（16）、flow.subl（17）；core/io/io.subl（41）；frontend/.../Parser.kt（:1206-1225 Task/Flow 解析特判）；compiler/.../Checker.kt（:88 curFunAsync、:316 main 天然 @async、:1236-1244 TaskExpr、:1246-1262 FlowExpr）、CallCheck.kt（:185-191 emit、:194-210 Channel、:217-222 Mutex、:226-305 内建挂起方法闸、:765-774 async-in-sync 闸）、Types.kt（:12-16 BASE_TYPES、:56-117 IO+async 内建签名、:278-295 白名单）、MultiModule.kt（:160 留痕合并）；codegen/.../JsCodeGen.kt（:718 async function、:379-431 小运行时注入、:980-1019 taskJs/flowJs、:1155-1215 内建特判）；interpreter/.../Evaluator.kt（:268 脚本模式不支持）；root doc/异步.md（旧文档，含 `->` 旧文法处已按修正案改写）；examples/异步与并发.subl、examples/日常-异步任务.subl；compiler/src/test/.../ModuleE2ETest.kt（:470-535、:748-790、:1050-1090）、DiagCoverageTest.kt（:252-272）；实测探针经 build/libs/sublimation.jar（check/compile）+ node v24 验证

Sublimation 的异步是**编译目标决定模型的异步**：因为目标是 JS，语言不做操作系统线程，只暴露协程级原语——惰性任务 `Task { … }`、冷流 `Flow { … }`、互斥锁 `Mutex()` 与有缓冲通道 `Channel[T](cap)`，外加 `runBoth` / `runSeq` 两个组合子与 `delay`。所有挂起点由编译器**自动 await**——语言没有 `await` 关键字，也没有 `async { }` 块表达式；异步性由 `@async` 注解标记并在以下四种「天然 @async 上下文」中合法传播：`main` 函数体、`@async` 函数体、`Task { …}` 体、`Flow { … }` 体（Checker.kt:316、:1240、:1252）。同步函数触碰任何挂起点会被 **async-in-sync 闸**拦下（CallCheck.kt:769，E-NEED-ASYNC）。

## 语法

### 注解与异步上下文

```subl
@async fun fetch(n: Nat): Str = …          // 声明异步函数（通常与 @impure 同列）
@impure @async fun fetch(n: Nat): Str = …  // 常见形态：异步必有挂起点，挂起点多为 impure
```

`@async` 是行为层注解（langfacts §6）：它不改变函数类型——`(Nat) => Str` 仍是它——只告诉检查器「体内调用挂起点合法」，并告诉 codegen 生成 `async function`（JsCodeGen.kt:718）。四种异步上下文：

| 上下文 | 来源 | 备注 |
|---|---|---|
| `main` 函数 | 天然 @async（Checker.kt:316） | 无需注解即可调异步函数；但仍需 `@impure` 才能调 print |
| `@async` 函数 | 注解 | 调用方须同样处于异步上下文（传染性，决策 93） |
| `Task { … }` 体 | 天然 @async（Checker.kt:1240） | 体是代码块，不是 lambda |
| `Flow { … }` 体 | 天然 @async（Checker.kt:1252） | 同上；`emit` 仅在此合法 |

### Task：创建与驱动

```subl
var t = Task {                        // TaskExpr（Parser.kt:1215-1219）：块表达式，不启动
    print("child")
    42                                // 尾表达式 → Task[Nat] 的元素类型
}
var a0 = t.isAlive()                  // Bool：是否已 start
t.start()                             // 启动（非阻塞）；返回 Null
var j  = t.join()                     // 挂起等待，取回尾表达式值（类型 = T）
```

- `Task { … }` 是解析器特判：跟在名字 `Task` 后的大括号是**代码块**而非尾随 lambda（Parser.kt:1209 的排除条件、:1215），所以体内写语句与尾表达式，不写形参
- `Task` / `Channel` / `Mutex` / `Flow` 都是 prelude 内建类型（BASE_TYPES，Types.kt:12-16），无需声明、无需挂标准库即可在单文件工程里用
- 方法只有三个：`start()` / `join()` / `isAlive()`（CallCheck.kt:236-243）；`main` 天然 @async，故示例中可直接 `t.join()`

### Flow：创建、派生与消费

```subl
var fl = Flow { emit(1); emit("a") }  // FlowExpr（Parser.kt:1221-1224）：冷流，不执行
fl.collect({ v => print(v) })         // 挂起：逐值消费（方法，返回 Null）
var f2 = fl.map({ x => x * 10 })      // 派生新冷流（不立即执行）
var xs = collectToList(fl)            // 自由函数：List[T]，发送序保持
var n  = collectCount(fl)             // 自由函数：Nat
var ok = collectAll(fl, { v => v > 0 })   // 自由函数：Bool，谓词全称判定
```

`emit(v)` 只在 `Flow { … }` 体内合法——检查器靠 `flowElemStack` 栈识别（CallCheck.kt:185-191）；在流体外写 `emit(1)` 会按普通函数解析，落 `E-UNBOUND-NAME`（实测）。Flow 的元素类型由体内所有 `emit` 的值类型 join 而成，无 emit 则 `Flow[Null]`（Checker.kt:1259-1262）。

### 组合子与收集函数（core.async 声明）

| 声明（core/async/async.subl、flow.subl） | 类型 | 作用 |
|---|---|---|
| `runBoth(f: () => Null, g: () => Null): Null` | @impure @async @root | 先启动 f（不阻塞），主路径继续 g，join 等 f 收尾 |
| `runSeq(f: () => Null, g: () => Null): Null` | @impure @async @root | 先做完 f 再做 g（串行） |
| `delay(ms: Nat): Null` | @impure @async @root | 显式延时（毫秒） |
| `collectToList[T](fl: Flow[T]): List[T]` | @impure @async @root | 发送序保持成表 |
| `collectCount[T](fl: Flow[T]): Nat` | @impure @async @root | 计数发射值个数 |
| `collectAll[T](fl: Flow[T], p: (T) => Bool): Bool` | @impure @async @root | 谓词全称判定，首个 false 短路 |
| `withMutex(m: Mutex, f: () => Null): Null` | @impure @async @root | 临界区包装 = `m.withLock(f)` |

全部是 `@root` 无体签名：实现在 codegen 编译期按名特判（JsCodeGen.kt:1155-1215），名字进 `ROOT_WHITELIST`（Types.kt:278-291，async 内建共 14 个，DiagCoverageTest.kt:252-272 逐一白名单验收）——用户同名函数不参与特判。相邻的 Channel 家族（`channelFromList` / `sendAll` / `channelDrain` / `channelPipe`）与 Mutex 计数器（`bumpCounter` / `bumpPair`）同属本模块，文法见 `Channel[T](cap)` 与 `Mutex()` 构造。

### IO 家族（P5 决策 81）

core/io/io.subl 把可失败的 IO 全标 `@impure @async` 且返回 `Result[_, Str]`：

| 函数 | 注解 | 返回 |
|---|---|---|
| `readLine()` | @impure @async | `Result[Str, Str]`（EOF/失败 → Err） |
| `readFile(p: Str)` | @impure @async | `Result[Str, Str]` |
| `writeFile(p: Str, c: Str)` | @impure @async | `Result[Null, Str]` |
| `appendFile` / `readDir` | @impure @async | `Result[Null, Str]` / `Result[List[Str], Str]` |
| `sleep(ms: Nat)` | @impure @async | `Null`（恒为挂起点） |
| `print` / `fileExists` / `getArgs` | @impure | 非 @async，无挂起 |

## 语义

### async-in-sync 闸与自动 await

调用点是否合法由一条规则 closing（CallCheck.kt:769）：

$$\frac{f \text{ 标有 } @async \qquad \neg\,\mathrm{curFunAsync} \qquad \mathrm{uncheckedDepth} = 0}{\text{E-NEED-ASYNC（给调用方加 } @async\text{）}} \ (\text{async-in-sync})$$

$$\frac{f \text{ 标有 } @async \qquad \mathrm{curFunAsync}}{\text{调用点是挂起点，codegen 自动插入 await}} \ (\text{D-AUTO-AWAIT})$$

没有 `await` 关键字：调用异步函数即 `await`（root doc《异步》原文，旧文法 `f : @impure ()=>Null` 按修正案改写为 `f: () => Null`）。生成的 JS 形态：`@async` 函数与 `main` 一律 `async function`，被登记为挂起点的调用前补 `await`（JsCodeGen.kt:718、awaited()）。

**闸门不对称**：函数调用闸（:769）查 `uncheckedDepth == 0`，故 `unchecked tick()` 可在同步函数里编译通过——但 codegen 不会补 await，同步函数本身也不是 `async function`，实参拿到的是 **Promise**（实测：`y=[object Promise]`）；而 `t.join()` / `ch.send()` / `ch.receive()` / `m.lock()` / `m.withLock()` / `fl.collect()` 这六个内建挂起方法不查 unchecked（:232-299 直接按 `curFunAsync` 判定），`unchecked` 也逃不掉。

### Task：惰性任务

$$\frac{}{\texttt{Task\ }\{b\}\ \text{求值为任务对象，} b \text{ 不执行}} \ (\text{TASK-1，仅创建不启动}) \qquad \frac{t.\texttt{start}()}{b \text{ 排入异步执行，返回 Promise 被持有}}$$

$$\frac{t \text{ 未 start} }{\text{E-NOT-STARTED}} \ (\text{TASK-7}) \qquad \frac{t.\texttt{join}() \text{ 在异步上下文}}{\text{挂起并返回 } b \text{ 的尾表达式值}}$$

任务对象的 JS 形态是三个闭包（JsCodeGen.kt:980-997）：`start` 惰性创建并执行 `async () => { …body… }`，`join` 返回持有的 Promise（故调用点的 await 由外层异步上下文补），`isAlive` 仅判「是否已 start」。类型 `Task[T]` 的 T 取块尾表达式类型（无尾则 Null，Checker.kt:1243）；**Task 体天然 @async 但不天然 @impure**——纯函数体内的 Task 块里调 print 仍报 E-IMPURE-CALL（实测；纯度沿 `pure` 形参透传，与异步正交）。

### runBoth / runSeq：调度组合

$$\frac{}{\texttt{runBoth}(f, g) \Rightarrow \text{先 } \texttt{then}\!-\!\text{启动 } f,\ \text{让出一拍后 } g,\ \text{最后 join } f} \qquad \frac{}{\texttt{runSeq}(f, g) \Rightarrow \texttt{await}\, f() \text{ 再 } \texttt{await}\, g()}$$

两者都**在调用点整体挂起**（外层异步上下文 await 整个组合），返回 `Null`；生成的 JS 是内联 async IIFE（JsCodeGen.kt:1189-1196）。runBoth 的并发是 JS 协程级：f 与 g 在首个挂起点处交错，单线程下无数据竞争，但仍需 Mutex 避免 `@mut` 状态在临界区内被穿插。

### Flow：冷流

$$\frac{}{\texttt{Flow }\{b\} \Rightarrow \_\_\text{flow}(\texttt{async function*}\ (b))} \ (\text{创建零执行}) \qquad \frac{c \in \{\texttt{collect},\ \text{collectToList},\ \text{collectCount},\ \text{collectAll}\}}{b \text{ 作为生成器被迭代}} \ (\text{冷})$$

$$\frac{\mathrm{collectAll}(fl, p) \quad \exists v \<: fl,\ \neg p(v)}{\mathrm{false}\ (\text{短路，后续不求值})} \quad \frac{\mathrm{collectAll}(fl, p) \quad fl \text{ 为空}}{\mathrm{true}\ (\text{空真值，实测})}$$

流体被翻译为 JS async generator：`emit(v)` → `yield v`（flowEmitHits 留痕，JsCodeGen.kt:1008-1019），其余语句原样；`map(g)` 返回 `__flow(async function* () { for await (v of gen()) yield g(v) })`——派生的仍是冷流，再 collect 才执行。collect 方法与三个收集自由函数都挂起（CallCheck.kt:296-303），故 `fl.collect(f)` 不能写在同步函数里。

### 纯度、类型与模块边界

- **@async ⊕ @impure 正交**：`@async` 管挂起点合法性，`@impure` 管副作用准入；异步函数常二者同列，但 `Task` 体内调 `@impure` 函数仍要看外围 `pure`
- **模块边界**：core/async 是**一个目录模块**（工程模式 = 目录即模块，langfacts §8），内含 async.subl / task.subl / channel.subl / mutex.subl / flow.subl 五个单元；flow.subl 自述「core.async.flow」（flow.subl:1）并声明**依赖：无**，而 async.subl / mutex.subl / channel.subl 依赖 core.collection。所谓「core/flow」不是一个独立顶层模块——从根模块可写 `core.async.collectCount(fl)` 全限定调用（实测），裸名经可见性同样可达。task.subl 无任何声明：`Task { … }` 是内建语法而非函数（task.subl:6）
- **可移植性边界**：解释器（脚本模式 .subs）不支持异步——`TaskExpr` / `FlowExpr` 直接抛「异步特性不在脚本中支持」（Evaluator.kt:268）；异步只能走工程模式 compile 到 JS

## 示例

Task 创建/启动/汇合（实测输出顺序：child task / alive=false,true / j=42）：

```subl
@impure
fun main() {
    var t = Task {
        print("child task")
        42
    }
    var a0 = t.isAlive()              // false：尚未 start
    t.start()                          // 非阻塞启动
    var a1 = t.isAlive()              // true
    var j = t.join()                   // 挂起取回 42（main 天然 @async）
    print(concat("alive=", toStr(a0)))
    print(concat("alive2=", toStr(a1)))
    print(concat("j=", toStr(j)))
}
```

`@async` 函数 + 自动 await + IO 家族 Result（P5 决策 81）：

```subl
@impure @async
fun loadNum(p: Str): Nat {
    var r = readFile(p)                // @async：调用即 await；Result[Str, Str]
    return when(r) {
        is Ok(text) => text.length
        is Err(_) => 0
    }
}

@impure @async
fun main() {
    var n = loadNum("cfg.txt")         // main 天然 @async，普通调用语句即 await
    print(concat("len=", toStr(n)))
    sleep(5)                           // 恒为挂起点
    print("done")
}
```

Flow 的冷性、collect 与 collectAll 短路（实测输出：10 / 20 / nothing / ecount=0 / emptyAll=true / list=[1,2,3]）：

```subl
@impure @async
fun main() {
    var fl = Flow { emit(1); emit(2) }
    var f2 = fl.map({ x => x * 10 })   // 派生冷流，尚不执行
    f2.collect({ y => print(concat("v=", toStr(y))) })     // 10 / 20

    var lazy = Flow { print("nothing") }                   // 体未被消费，但此处也不执行
    print(concat("ecount=", toStr(collectCount(lazy))))    // 0；"nothing" 在 collect 时才打印

    var e = Flow { }
    print(concat("emptyAll=", toStr(collectAll(e, { v => false }))))   // true（空真值）
    print(concat("list=", toStr(collectToList(fl))))       // [1,2,3] 发送序保持
}
```

组合子与临界区（对应 ModuleE2ETest.kt:1053 的端到端验收，输出 A / B / X / Y / locked / counter=3 / done）：

```subl
@mut struct Counter(@mut n: Nat)

@impure @async
fun main() {
    runSeq({ print("A") }, { print("B") })       // 串行：A 先 B 后
    runBoth({ print("X") }, { print("Y") })      // 并发：先启动 X，主路径跑 Y，join 收尾
    delay(1)                                     // 显式让出一拍

    var m = Mutex()
    var c: @mut Counter = Counter(0)
    bumpCounter(m, c)                            // 读-改-写复合操作（+1）
    bumpPair(m, m, c, c)                         // 双锁配对（+2）
    withMutex(m, { print(concat("locked n=", toStr(c.n))) })   // 3
    print("done")
}
```

异步上下文的传染性——同步 `helper` 调 `@async tick` 报 E-NEED-ASYNC：

```subl
@impure @async fun tick(): Nat = 1

fun helper(): Nat {
    var x = tick()                    // [错误] E-NEED-ASYNC：给调用方加 @async
    return x
}
```

把消费拆进 `@async` 函数、再由 Task 驱动（ModuleE2ETest.kt:781 的验收形态）：

```subl
@impure @async
fun consume(fl: Flow[Nat]): Null {
    fl.collect({ x => print(x) })
}

@impure
fun main() {
    var fl = Flow { emit(7); emit(8) }
    var t = Task { consume(fl) }      // Task 体天然 @async，可调 @async 函数
    t.start()
    t.join()
}
```

## 陷阱

1. **没有 `await` 关键字，也没有 `async { }` 块**：写 `await(f())` 会把 `await` 当普通名字解析，落 `E-UNBOUND-NAME`（实测）；写 `async { … }` 会按名字 `async` 解析后跟消去块，同样报错。异步性只能来自 `@async` 函数 / `main` / `Task { }` / `Flow { }` 四种上下文。
2. **同步函数调 @async 函数 → E-NEED-ASYNC**（CallCheck.kt:769）：闸门可被 `unchecked` 逃逸，但那是编译期让步——同步函数不是 `async function`，拿到的实参是 **Promise** 而非值（实测 `y=[object Promise]`）。正确做法是给调用方加 `@async`，或把调用搬进 `Task { }`。
3. **六个内建挂起方法不吃 `unchecked`**：`t.join()` / `ch.send()` / `ch.receive()` / `m.lock()` / `m.unlock()` / `m.withLock()`（`fl.collect()` 同理）直接按 `curFunAsync` 判定，不查 `uncheckedDepth`（CallCheck.kt:232-299，实测 `unchecked t.join()` 仍报 E-NEED-ASYNC）。
4. **`join` 前必须 `start`**：函数体内无 `t.start()` 就 `t.join()` 报 `E-NOT-STARTED`（TASK-7，CallCheck.kt:238-241）。顺序由同一函数体内语句顺序静态判定，跨函数/分支的 start 不计。
5. **runBoth/runSeq 的回调是普通 lambda**：签名是 `f: () => Null`——lambda 本体不天然 `async`，体内出现挂起点（`sleep` / `delay` / `collect` / IO 等）时，codegen 会在**非 async 箭头函数**里生成 `await`，产出非法 JS（node 抛 `await is only valid in async functions`，实测）。耗时操作放进 `Task { … }`，回调内只放非挂起动作。`withMutex(m, f)` 的 `f` 同型同因（mutex.subl 的 `withLock(f)` 文档形态 `f : @impure ()=>Null` 属旧文法写法，按 `f: () => Null` 读）。
6. **Flow 是冷的，`collectAll` 空流为真**：只构造不消费，流体一行也不执行（连其中的 print 都不跑）；`Flow { }` 对任意谓词 `collectAll` 返回 `true`、`collectToList` 返回 `[]`（实测）。依赖副作用的流必须被消费。
7. **`emit` 只在 Flow 体内合法**：识别靠 `flowElemStack`（CallCheck.kt:185-191）；体外 `emit(1)` 报 `E-UNBOUND-NAME` 而非「emit 未定义」之类提示——别在普通函数里写 emit 期望延迟生效。嵌套 Flow 用栈区分（Checker.kt:113），但跨函数边界的 emit（helper 里写 emit 供外层流用）不被识别，待核是否有引导诊断。
8. **脚本模式（.subs）不支持异步**：`Task { }` / `Flow { }` 在解释器里直接抛「异步特性不在脚本中支持」（Evaluator.kt:268）。异步必须走工程模式（目录 + `compile`）落 JS 跑；`run-script` 的 .subs 顶层语句既不能定义 Task 也不能 collect。
9. **main 天然 @async，但不天然 @impure**：`fun main() { sleep(1) }` 会因 `sleep` 的 @impure 在纯作用域报 E-IMPURE-CALL；异步入口照例写 `@impure fun main()` 或 `@impure @async fun main()`（两形态均实测通过）。
10. **IO 家族返回 Result，不是原始值**（P5 决策 81）：`readFile` / `writeFile` / `readLine` / `appendFile` / `readDir` 一律 `Result[_, Str]`，必须 when 解构或 unwrapOr；且它们全标 @async，同步函数里调用先撞 E-NEED-ASYNC。`print` / `fileExists` / `getArgs` / `timeNow` 不是 @async，无挂起。
11. **`delay` 与 `sleep` 同形异主**：两者生成完全相同的 `new Promise(r => setTimeout(r, n))`（JsCodeGen.kt:1163-1172），`delay` 在 core.async、`sleep` 在 core.io（io.subl 的「线程」节）；语义无差别，按模块归属选用即可，别当成两种机制。
12. **Channel/Mutex 不是本条目主角但同闸**：`Channel` 创建必须写类型参数（`Channel[Nat](4)`，否则 E-TYPE-MISSING）；`receive()` 在本地未关闭通道上智能转换解包为 `T`，参数位/未知通道保守返回 `Optional[T]`（CH-5，CallCheck.kt:256-268）；`close()` 后 `send()` 报 E-CHANNEL-CLOSED（CH-6）。详见 root doc/异步.md 与 examples/异步与并发.subl。
13. **Task 体不天然 impure**：纯函数里的 `Task { print(…) }` 报 E-IMPURE-CALL（实测）——`@async` 上下文只放开挂起点闸门，不开 impure 闸（langfacts §6 的 unchecked 三闸分工）。异步任务体照例需要 `unchecked` 或 `@impure` 外围。
14. **`runBoth` 不保证 f 先完成**：它只保证「先启动 f、再开始 g、两者都完成才返回」；f 与 g 谁先出结果取决于各自的首个挂起点。要严格顺序用 `runSeq`，要等任务结果用 `Task` + `join`。
15. **模块边界易混淆**：`core/async/flow.subl` 不是独立模块 `core.flow`——它是 `core.async` 目录模块的一个单元（flow.subl:1 自述 core.async.flow）。跨模块全限定写 `core.async.collectCount(fl)`、裸名经可见性解析（实测）；`Task` 则连库函数都不是（task.subl 无声明，是内建语法）。
16. **旧文档文法需换读**：root doc/异步.md 与 mutex.subl 注释里的 `f : @impure ()=>Null`、`withLock(f : @impure ()=>Null)` 是修正案前写法；当前文法函数类型是 `() => Null`、命题蕴含是 `→`、程序位一律 `=>`（writer-contract 唯一准绳）。
