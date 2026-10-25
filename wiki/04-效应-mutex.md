# mutex（异步互斥锁）

> wiki/04-效应-mutex · 主题域：Mutex / lock / unlock / withLock / withMutex / bumpCounter / bumpPair / 异步临界区
> 事实来源：_langfacts.md §6/§7/§8；core/async/mutex.subl（16 行全文）、core/async/{async,task,flow,channel}.subl、core/io/io.subl（P5 决策 81）；root doc/异步.md；compiler/src/main/.../checker/Types.kt（:13/112-124/286/301）、CallCheck.kt（:208-212/267-296/698-699）、ModuleTree.kt（:250-266 隐式挂载）；compiler/src/main/.../codegen/JsCodeGen.kt（:99/361-378/1069-1071/1183-1195）；compiler/src/test/.../ModuleE2ETest.kt（:624-700/1050-1085）；本会话用 build/libs/sublimation.jar + node 24 的实测探针（临时目录 _facts_tmp，验后删除）

mutex 是 sublimation 的异步互斥锁：一把**非重入**的协程级锁，用来把跨挂起点的「读-改-写」等复合操作包成临界区，防止多个异步协程在执行途中互相穿插。它不属于解释器，而是 core.async 的 `@root` 内建——声明在 core/async/mutex.subl，行为由编译器在生成 JS 时注入的运行时 class 实现（JsCodeGen.kt:361-378）。使用 mutex 的程序只能走 `compile` → JS → node 路径。

## 语法

### 总览

| 形态 | 签名 | 性质 |
|---|---|---|
| `Mutex()` | 创建：无类型参数、无构造实参 | 内建类型 `Mutex`（Types.kt:13） |
| `m.lock()` | `Mutex => Null` | 挂起；底层加锁 |
| `m.unlock()` | `Mutex => Null` | 挂起；底层解锁 |
| `m.withLock(f)` | `(Mutex, () => Null) => Null` | 挂起；临界区包装，退出即解锁 |
| `withMutex(m, f)` | `(Mutex, () => Null) => Null` | `@impure @async @root`；等价 `m.withLock(f)` |
| `bumpCounter(m, c)` | `[T](Mutex, T) => Null` | `@impure @async @root`；计数器 `c.n` 原地 +1 |
| `bumpPair(m1, m2, c1, c2)` | `[T](Mutex, Mutex, T, T) => Null` | `@impure @async @root`；两次配对自增 |

mutex.subl 全文只有三条无体声明（core/async/mutex.subl:9-16；注释在 :3-8）：

```subl
@impure @async
@root fun withMutex(m: Mutex, f: () => Null): Null
@impure @async
@root fun bumpCounter[T](m: Mutex, c: T): Null
@impure @async
@root fun bumpPair[T](m1: Mutex, m2: Mutex, c1: T, c2: T): Null
```

`@root` 表示「签名对应编译期内建 JS 实现，代码生成按名字特判」（白名单见 Types.kt:286；H-ROOT-BUILTIN 补充级诊断会在调用点提示）。对象方法 `lock`/`unlock`/`withLock` 没有声明文件——它们由 checker 的方法分支认（CallCheck.kt:267-296），由注入的运行时 class 提供。

### 创建与类型书写

```subl
var m = Mutex()              // 唯一创建形态：无类型参数（mutex.subl:3）
fun f(m: Mutex): Null { … }  // 类型位直接写 Mutex（0 个类型参数，异于 Channel[Nat]）
```

- `Mutex` 是 checker 预注册的内建类型（Types.kt:13），即使不挂载 core.async 也能在类型位与调用位使用（实测）
- 调用 `Mutex()` 会置位 `mutexUsed`，通知代码生成注入运行时 class（CallCheck.kt:208-212）

### f 的三种合格形态

```subl
m.withLock(fun() { c.n = c.n + 1 })   // 匿名函数（E2E 用例形态）
m.withLock({ _x => c.n = c.n + 1 })   // 隐式单参 lambda
m.withLock(myCrit)                    // 函数值（@async 函数引用也可，实测可行）
```

零参 lambda `{ => … }` 在 `withLock` 方法位可用（方法位只作表达式级检查）；但传给自由函数 `withMutex` 时会报 **E-TYPE-MISMATCH**「lambda 有 0 个参数，期望类型要求 1 个」——即便工程已挂载 core.async、stdlib 声明写的是 `f: () => Null`（实测；机制见[陷阱](#陷阱)第 3 条）。实用建议：给 `withMutex` 一律传单参形态。

### 调用语境

`lock`/`unlock`/`withLock` 以及三个自由函数**全是挂起操作**：只有 @async 上下文能调用——@async 函数体、`Task { … }` 体（天然 async）、`main`（天然 async；root doc/异步.md:10-12）。同步函数里调用即 **E-NEED-ASYNC**：

```subl
@impure fun helper(m: Mutex): Null {
    m.lock()          // E-NEED-ASYNC：m.lock() 是挂起操作，需 @async 上下文（实测）
}
```

## 语义

### 一条编译走廊

mutex 没有解释器实现：checker 认类型与挂起约束，代码生成为 JS 注入运行时。链路：

1. 类型位/调用位的 `Mutex` 走内建类型表（Types.kt:13）
2. `Mutex()` 创建或 `withMutex`/`bumpCounter`/`bumpPair` 调用置位 `mutexUsed`（CallCheck.kt:208-212/698-699）
3. 代码生成在输出 JS 顶部注入 `class Mutex`（JsCodeGen.kt:99/361-378）；`Mutex()` 译为 `new Mutex()`（JsCodeGen.kt:1069-1071）；`withMutex(m, f)` 译为 `(m).withLock(f)`，`bumpCounter` 译为锁内 `.n = .n + 1`，`bumpPair` 译为顺序两次加锁自增（JsCodeGen.kt:1183-1195）

### 锁的运行时行为（标准库实现即事实）

注入的运行时 class（JsCodeGen.kt:366-378，逐字引用）：

```js
class Mutex {
  constructor() { this._locked = false; this._waiters = []; }
  async lock() {
    if (!this._locked) { this._locked = true; return; }
    return new Promise((res) => this._waiters.push(res));
  }
  async unlock() {
    if (this._waiters.length) { const w = this._waiters.shift(); w(); }
    else this._locked = false;
  }
  async withLock(f) { await this.lock(); try { return await f(); } finally { await this.unlock(); } }
}
```

- **互斥的真正含义**：JS 单线程，锁的意义不是防 OS 线程抢占，而是阻止 async 协程在临界区内互相穿插（JsCodeGen.kt:362 注释）。只要「读-改-写」两步之间夹着挂起点（`delay`、IO、另一个 @async 调用），两协程就会交错丢失更新——这是 mutex 唯一真正必要的场景，也是示例 3 的对照基础
- **unlock 是 hand-off**：有等待者时直接把锁交给队首等待者，不置 `_locked = false`；无等待者才释放
- **withLock 是 try/finally**：临界区抛异常也会解锁；`lock()/unlock()` 手工配对没有这层保护
- **非重入**：锁不记录持有者。同一协程第二次 `lock()` 会被推进 `_waiters` 永久挂起（实测：Task 内 `m.lock(); m.lock()` 后 join 永不完成）
- **unlock 不配对静默**：无锁状态下 `unlock()` 只是把 `_locked` 置 false，无任何诊断（实测：空锁连解两次正常返回）

### 预制件语义

`bumpCounter(m, c)` 与 `bumpPair(m1, m2, c1, c2)` 是「读-改-写」的官方示例：代码生成固定翻译成对计数器 `.n` 字段的锁内自增（JsCodeGen.kt:1188-1195）。传入的 `@mut` 计数器**原地被修改**（实测：`Counter(0)` 经 `bumpCounter` 后 `n=1`，再经 `bumpPair(m, m, c, c)` 后 `n=3`，与 E2E 一致）；`bumpPair` 的两次自增顺序执行，同一把锁传两次不会死锁（第一次 withLock 的 finally 已解锁）。`withMutex(m, f)` 与 `m.withLock(f)` 完全等价（JsCodeGen.kt:1184-1186）。

### 类型与效应规则

$$\frac{\Gamma \vdash m : \text{Mutex} \qquad \Gamma \vdash f : () \Rightarrow \text{Null} \qquad \text{调用处于 @async 上下文}}{\Gamma \vdash \texttt{withMutex}(m, f) : \text{Null} \quad (\texttt{@impure @async, 挂起点})} \quad (\text{M-Call})$$

$$\frac{\Gamma \vdash m : \text{Mutex} \qquad \text{上下文非 @async} \qquad \texttt{uncheckedDepth} = 0}{\texttt{E-NEED-ASYNC}} \quad (\text{E-NEED-ASYNC})$$

- 三个自由函数标注 `@impure @async`：调用它们需要 @async 上下文，且**自动 await**——语言没有 `await` 关键字，@async 调用点由检查器标记、代码生成插入 await（D-AUTO-AWAIT；详见[函数定义](01-基础-函数定义.md)）
- `@impure` 使其不能在纯函数体内直接调用（需 `unchecked` 或上下文供给 `pure<f>`，见 _langfacts §6）
- 异步性会传染：调用了挂起原语的函数自身必须标 `@async`

### 与异步家族的配合

- **Task**（core/async/task.subl:4-10）：并发载体。`Task { … }` 创建未启动任务，`start()` 异步执行（体天然 async），`join()` 挂起等结果。mutex 的典型用法就是「多个 Task 竞争一个 @mut 状态，临界区用锁包住」
- **Channel**（core/async/channel.subl）：协程间传值。常与 mutex 组合成「工作者从通道取活、累加结果写回共享计数器」的形态（见示例 4）；注意参数位通道的 `receive()` 返回 `Optional[T]`，要 `when` 解包
- **Flow**（core/async/flow.subl）：惰性冷流，`collect` 时才执行。流本身不引入共享可变状态——mutex 与 Flow 正交，但流的消费端若要写共享 @mut 状态，同样要进临界区
- **IO 家族**（core/io/io.subl，P5 决策 81）：`readLine`/`readFile`/`writeFile`/`appendFile`/`readDir` 全部 `@impure @async` 且返回 `Result`。IO 是最常见的挂起点来源：「读文件-改-写回」这类复合操作一旦跨 IO 挂起点，就需要 mutex 串行化
- **@atomic**（root doc/异步.md:19-24）：`@atomic` 注解保证单次读写原子化（Nat/Int/Rat/Str、容器可标）。分工：`@atomic` 管**单变量单次读写**；mutex 管**跨挂起点的复合操作**。单变量自增 `n = n + 1` 若两步间无挂起点，`@atomic` 即够；若中间夹着 IO 或 delay，只能上锁

## 示例

以下四个示例均在本会话用 `compile` + node 实测通过（输出逐行标注）。

### 示例 1：withLock 临界区（两 Task 竞争）

```subl
@mut struct Counter(@mut n: Nat)

@impure @async
fun lockedInc(m: Mutex, c: @mut Counter): Null {
    m.withLock(fun() {
        var old = c.n
        c.n = old + 1
    })
}

@impure @async
fun twoWorkers(m: Mutex, c: @mut Counter): Null {
    var t1 = Task { lockedInc(m, c) }
    var t2 = Task { lockedInc(m, c) }
    t1.start()
    t2.start()
    t1.join()
    t2.join()
}

@impure fun main() {
    var m = Mutex()
    var c: @mut Counter = Counter(0)
    twoWorkers(m, c)
    print("n=" + toStr(c.n))     // n=2
}
```

### 示例 2：自由函数与预制件

```subl
@impure fun main() {
    var m = Mutex()
    var c: @mut Counter = Counter(0)
    bumpCounter(m, c)                      // n: 0 → 1
    bumpPair(m, m, c, c)                   // n: 1 → 3（顺序两次，同锁不死锁）
    withMutex(m, { _x => c.n = c.n + 1 })  // n: 3 → 4（单参 lambda 形态）
    print("n=" + toStr(c.n))               // n=4
}
```

### 示例 3：跨挂起点的竞争（mutex 存在的理由）

读与写之间夹一个 `delay(1)` 挂起点，两 Task 各跑 5 轮：

```subl
@impure @async
fun unsafeBump(c: @mut Counter): Null {
    var old = c.n
    delay(1)
    c.n = old + 1          // 两协程读到同一个 old → 丢更新
}

@impure @async
fun manualBump(m: Mutex, c: @mut Counter): Null {
    m.lock()               // 手工临界区：夹住跨挂起点的读-改-写
    var old = c.n
    delay(1)
    c.n = old + 1
    m.unlock()
}
```

实测输出（各两 Task × 5 轮）：无锁 `n=5`（丢了 5 次更新），加锁 `n=10`。注意本例不能用 `withLock` 写——临界区体内含 `delay` 这个 @async 调用会触发生成器陷阱（见陷阱第 2 条）。

### 示例 4：Channel + Task + 共享累加器

```subl
@impure @async
fun worker(ch: Channel[Nat], m: Mutex, total: @mut Counter, jobs: Nat): Null {
    if (jobs > 0) {
        var x = ch.receive()        // 参数位通道 → Optional[Nat]
        when (x) {
            is Some(v) => {
                m.withLock(fun() {
                    var old = total.n
                    total.n = old + v
                })
            }
            None => {}
        }
        worker(ch, m, total, jobs - 1)
    }
}

@impure @async
fun sumAnyOrder(xs: List[Nat], m: Mutex, total: @mut Counter): Null {
    var ch: Channel[Nat] = Channel<Nat>(8)
    sendAll(ch, xs)
    ch.close()
    var t1 = Task { worker(ch, m, total, 3) }
    var t2 = Task { worker(ch, m, total, 3) }
    t1.start()
    t2.start()
    t1.join()
    t2.join()
}

@impure fun main() {
    var m = Mutex()
    var total: @mut Counter = Counter(0)
    sumAnyOrder(Cons(1, Cons(2, Cons(3, Nil()))), m, total)
    print("total=" + toStr(total.n))   // total=6（两工作者任意交错，结果恒为 6）
}
```

## 陷阱

1. **同把锁 lock 两次 = 永久死锁**。锁不记录持有者（运行时只有 `_locked` 与等待队列），同一协程重入 `m.lock()` 会把自己挂进等待队列，无人唤醒。实测：Task 内两次 `lock()` 后 `join()` 永不返回。跨函数传递时尤其小心——`a(m)` 内部又调了持同一把锁的 `b(m)` 就会死锁。重入需求目前没有解法，只能重构（拆锁、或把内层调用移出临界区）
2. **临界区 lambda 体内不能直接调 @async 函数**（当前实现的生成器边界）。`m.withLock(fun() { step() })` 里若 `step` 是 @async 函数，lambda 会被译成非 async 的 JS 箭头函数而体内出现 `await`——node 加载即 `SyntaxError: await is only valid in async functions`，**整份脚本无法启动**（实测；非 mutex 独有，runBoth/runSeq 的内联 lambda 同样中招，但锁场景最容易撞上）。规避：临界区只写同步的读-改-写；要跨挂起点就改用 `m.lock()`/`m.unlock()` 手工管理（如示例 3），或把异步函数**以函数值**传入（`m.withLock(step)` 实测可行——运行时会 `await f()`）
3. **零参 lambda 传 `withMutex` 触发 E-TYPE-MISMATCH**。stdlib 声明写的是 `f: () => Null`，但调用点走的回退签名把 f 当单参（Types.kt:114-117 的 `FunType([Null])`），报「lambda 有 0 个参数，期望类型要求 1 个」；用户自定义 `fun g(f: () => Null)` 则照常接受零参 lambda（实测对照）。机制细节（为何内建表签名压过模块声明）**待核**；实操上给 `withMutex` 传 `{ _x => … }` 或 `fun() { … }` 即可；`withLock` 方法位不受此限
4. **mutex 只存在于 compile → JS 路径**。解释器没有 Mutex 运行时：`run-script` 的 .subl 工程模式（目录无 .subs 时按 main 解释执行）在 `Mutex()` 处直接报「未定义的函数『Mutex』」（实测）。开发流程必须是 `compile -o out.js` 再跑 node
5. **.subs 脚本模式用不了**。脚本是同步解释、无异步上下文：`.subs` 里调 `withMutex` 报 `E-NEED-ASYNC`「调用 @async 函数 withMutex 需 @async 上下文」（实测；错误文案提到 main，但脚本无 main——此处只是复用同一诊断文本，**待核**）。`Mutex()` 创建本身在脚本类型检查中能过，但没有运行时意义
6. **unlock 不配对没有任何提示**。空锁 unlock 只清标志位；忘了解锁则后续所有 `lock()` 永久挂起（表现为程序「卡死」而非报错）。尽量用 `withLock`（try/finally 免疫异常路径漏解锁），只在临界区必须夹挂起点时才手工配对
7. **`Mutex<Nat>()` 不报错但也没用**。stdlib 与 checker 都按 0 类型参数处理，传入的类型实参被静默忽略（实测：检查通过，生成 `new Mutex()`）。写出来不报错，最容易掩盖笔误或误解
8. **持锁跨挂起点 = 整段串行化**。`withLock` 内的 `delay`/IO 会让所有竞争者排队等完。锁粒度宜小：先在锁内取快照、锁外做耗时异步、再回锁内提交（此模式下两段都只能是同步代码或函数值，参考陷阱第 2 条）
9. **`@mut` 计数器传给 bump 系列是原地修改**。`bumpCounter(m, c)` 要求 c 持有可变视图（`@mut Counter`），实参按拷贝判据处理后写回的是同一个对象（实测 n 确实前进）。若误传纯视图 `T`，自增发生在深拷上、原值不变——这属于[拷贝规则](02-类型-拷贝规则.md)的通用行为，但 mutex 计数场景最易踩
10. **锁不是线程同步原语**。目标平台是 JS 单线程事件循环，不存在真并行；把 mutex 当作「防协程穿插」的花括号，而不是「防多核竞争」的锁。语言未提供任何跨进程/跨线程同步

### 相邻条目

- [函数定义](01-基础-函数定义.md)：@async、@impure、E-NEED-ASYNC 与自动 await
- [@mut](02-类型-@mut.md) / [拷贝规则](02-类型-拷贝规则.md)：临界区里被保护的可变状态
- 同卷（计划条目）：异步 Task 与 Flow、副作用与 @impure、unchecked 语句、@root 原生绑定
