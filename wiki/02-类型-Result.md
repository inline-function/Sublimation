# Result

> wiki/02-类型-Result · 主题域：Result[T, E] / Ok / Err / mapOk / mapErr / flatMapOk / unwrapOr / 错误传播
> 事实来源：_langfacts.md §2/§3/§4/§6/§7/§8；core/collection/result.subl（53 行全文）；core/collection/typeclass.subl（:6/10 kind 限制）；core/collection/optional.subl（:64 toResult）；core/collection/list.subl（:74-83 前置条件 + panic 兜底先例）；core/io/io.subl（P5 决策 81）；core/net/net.subl（panic 占位）；compiler/src/main/.../Checker.kt（:181-187 prelude）、CallCheck.kt（:91/382/399-427 方法糖）、WhenCheck.kt（:105-189 穷尽性）、Types.kt（:180 panic 内建）、JsCodeGen.kt（:1132-1146 IO 生成、:1256 panic）；interpreter/src/main/.../Evaluator.kt（:286/460/468-469/766-775）；examples/错误处理.subl；compiler/src/test/.../ModuleE2ETest.kt（:224-309 P3/P5/v2 用例）、CheckerTest.kt（:24/813-818/1594）；boring doc 06-效应与可变性.md、09-运行时与代码生成.md（值模型/深拷）；同卷 02-类型-枚举.md、02-类型-Optional.md（机制参照）

Result 是标准库的错误处理容器：一个**普通二参枚举** `Result[T, E]`，`Ok(v)` 承载成功值、`Err(e)` 承载失败值。语言没有异常、没有 try/catch——可失败操作把失败编码进返回类型，调用方拿到的是「成功或失败」两个值之一，必须显式拆开（决策 81，P5）。P5 落实后，core 的 IO 全家族（readFile / writeFile / readLine / appendFile / readDir）统一返回本容器（core/io/io.subl:5；JsCodeGen.kt:1132-1146 在宿主层用 try/catch 把失败包成 Err，源码侧不可见）。

## 语法

### 声明

标准库声明只有四行（core/collection/result.subl:7-10）：

```subl
enum Result[T, E] {
    Ok(T)
    Err(E)
}
```

- 两个类型参数走方括号：`T` 是成功载荷、`E` 是错误载荷；两个构造子各带一个位置字段（无字段名、无默认值，与枚举通用文法一致）
- Result **不是 Checker 预注册枚举**：prelude 只注册 Bool/Null/Optional（Checker.kt:181-187）。Result 由 core.collection 提供——工程/目录模式随标准库可见，单文件脚本模式需按 examples/错误处理.subl 的做法自行内联声明

### 构造与类型书写

```subl
var r : Result[Nat, Str] = Ok(42)          // 显式实例化：类型实参走方括号
var bad : Result[Nat, Str] = Err("bad")    // Err 载荷须与 E 同型
var path = Result::Ok(42)                  // 路径形态 E::A(x)（同枚举通用文法）
var io : Result[Null, Str] = Ok(null)      // writeFile 的成功值是 Null
```

### when 解构

解构与枚举通用规则一致，带载荷分支用 `is`（core 标准库的统一写法）：

```subl
fun describe(r: Result[Nat, Str]): Str = when(r) {
    is Ok(v) => toStr(v)                  // 绑定成功值
    is Ok(_) => "ok"                      // 通配：只要是 Ok，不取值
    is Err(e) => concat("ERR: ", e)       // 绑定错误值
}
```

分支分隔符是 `=>`（修正案后 `->` 全位非法，程序位一律 `=>`）。`Ok`/`Err` 各带一个字段，两支全裸绑定/通配即穷尽（WhenCheck.kt:184-189 的绑定式覆盖判定）——result.subl 里全部 when 都只有这两支、没有 else；漏掉一支会触发 W-NON-EXHAUSTIVE 且值类型退化为 Null（WhenCheck.kt:155-158）。

### 工具函数

全部是**同名自由函数**（result.subl:13-52），声明在 core.collection：

| 函数 | 签名 | 作用 |
|---|---|---|
| isOk | `fun isOk[T, E](r: Result[T, E]): Bool` | 判定 Ok |
| isErr | `fun isErr[T, E](r: Result[T, E]): Bool` | 判定 Err |
| unwrapOr | `fun unwrapOr[T, E](r: Result[T, E], d: T): T` | 解包兜底 |
| mapOk | `fun mapOk[T, E, U](r: Result[T, E], f: (T) => U): Result[U, E]` | 成功路径映射 |
| mapErr | `fun mapErr[T, E, F](r: Result[T, E], f: (E) => F): Result[T, F]` | 错误路径映射 |
| flatMapOk | `fun flatMapOk[T, E, U](r: Result[T, E], f: (T) => Result[U, E]): Result[U, E]` | 成功路径链式 |
| toList | `fun toList[T, E](r: Result[T, E]): List[T]` | 转成 0/1 元列表 |

调用形态两种皆可——决策 68 / O2 接收者糖：`o.m(args)` 与 `m(o, args)` 双形态，方法糖找「同名自由函数」且其首参类型与接收者匹配（CallCheck.kt:91、382、399-427）：

```subl
var n = unwrapOr(bad, 0)          // 自由式
var n2 = bad.unwrapOr(0)          // 接收者式（mapOk/mapErr/flatMapOk 同理）
var m = mapOk(r, { x => x + 1 })
var m2 = r.mapOk({ x => x + 1 })
```

## 语义

Result 的全部行为由一条视角推导：**Ok 与 Err 是两条互斥通道**，每个工具函数只作用于其中一条，另一条原样透传。

$$\frac{r = \mathrm{Ok}(v)}{\mathrm{unwrapOr}(r, d) = v} \ (\text{Ok 透传}) \qquad \frac{r = \mathrm{Err}(e)}{\mathrm{unwrapOr}(r, d) = d} \ (\text{Err 兜底，} e \text{ 丢弃})$$

$$\frac{r = \mathrm{Ok}(v)}{\mathrm{mapOk}(r, f) = \mathrm{Ok}(f(v))} \qquad \frac{r = \mathrm{Err}(e)}{\mathrm{mapOk}(r, f) = \mathrm{Err}(e)} \ (f\text{ 不执行})$$

$$\frac{r = \mathrm{Ok}(v)}{\mathrm{flatMapOk}(r, f) = f(v)} \qquad \frac{r = \mathrm{Err}(e)}{\mathrm{flatMapOk}(r, f) = \mathrm{Err}(e)} \ (\text{Err 短路})$$

mapErr 是 mapOk 的对偶：`Err(e) ↦ Err(f(e))`、`Ok(v) ↦ Ok(v)`，只变换 E 不变换 T（result.subl:38-41）。

- **unwrapOr**：返回类型是 **T**（不是 Result）——它是拆箱动作，默认值 `d : T` 必须与成功载荷同型；Err 时错误值 e 被丢弃。它是替代已移除的 `?:` 空替代语法的错误侧版本
- **mapOk / flatMapOk**：Functor / Monad 的语义等价物。但 Result 是二阶类型（T*E 双参数），套不进 `F[_]` 一阶 kind——typeclass.subl:6/10 明确 List/Optional/Set 有 Functor/Foldable/Monad 实例而 Result 没有，成功路径映射走这对姐妹函数（result.subl:3-4）
- **flatMapOk 的 Err 短路即错误传播**：链上任意一步已是 Err，后续 f 全部跳过，错误原样带到链尾（result.subl:43-47）
- **mapErr 是边界翻译器**：错误类型在层间不一致时用它统一（如本层 `Str` 原因翻译成上层自定义错误类型），T 通道不动
- **toList**：`Ok(v) ↦ Cons(v, Nil())`、`Err(e) ↦ Nil()`——把「可能失败」折叠成「可能为空」，跨进 List 通道
- **值模型**：解释器以 `ResultV(isOk, value, error)` 一体承载，`Ok`/`Err` 构造子与模式匹配都在求值器特判（Evaluator.kt:468-469、766-775）；JS 侧生成 `core_collection__Ok`/`Err`（JsCodeGen.kt:1132-1146）；Result 值用 `==` 按载荷结构相等（boring doc 09-运行时与代码生成.md:69）
- **可变性**：Result 是不可变容器；赋给 @mut 目标时按通用拷贝规则**深拷并递归复制载荷**（boring doc 06-效应与可变性.md:376、09:146 的 deepCopy 子句），自身不带来额外所有权语义
- **异步边界**：IO 家族全部 @impure @async（io.subl:13-31），须在 async 上下文调用（main 天然 async、Task 体天然 async，io.subl:3-6）；纯函数体内直接调用会被 async-in-sync 闸拦下（langfacts §6）

错误传播链的典型分工：

| 链上位置 | 手段 | 何时用 |
|---|---|---|
| 链中每一环 | flatMapOk | 下一步还可能失败，失败原样上抛 |
| 模块/层边界 | mapErr | 把本层 E 翻译成上层的错误类型 |
| 链尾终端 | unwrapOr / when 解构 | 兜底默认值，或 Ok/Err 分别善后 |
| 理论不可达位 | panic() | 前置条件已证明排除的位置（见陷阱 7） |

panic 是内建函数 `fun panic(): Nothing`（Types.kt:180），JS 侧生成 `throw new Error('panic: unreachable')`（JsCodeGen.kt:1256）——它不是 Err，不参与 Result 通道。core 自身的用法先例：`get(xs, i)` 用上文前置条件 `<i < size(xs)>` 把越界排除在类型之外，体内 `None => panic()` 兜底（list.subl:79-83）——把「调用方证明过的前提」与「Result 的运行时失败」分开安置。

Result 与证明通道：isOk/isErr 是返回 Bool 的**值层**谓词，Result 自身不参与命题语义；与命题层的接触点是通用机制——构造子项可出现在命题等式位（CheckerTest.kt:1594 的 `∴ Some(x) = Some(y)` 即此形态；Ok/Err 同为枚举构造子，工程模式下标准库可见时同理 `待核`），事实登记用独立 axiom 语句：

```subl
fun needsOkPath()<okPath>: Null { }        // 上文：调用方须供给 okPath

@impure
fun main() {
    axiom okPath                           // 独立 axiom 语句注入作用域（修正案后文法）
    needsOkPath()
}
```

## 示例

构造、解构、兜底（对应 examples/错误处理.subl 的预期输出 42 / 0 / isOk=true / ERR: bad）：

```subl
@impure
fun main() {
    var r : Result[Nat, Str] = Ok(42)
    var bad : Result[Nat, Str] = Err("bad")

    print(unwrapOr(r, 0))                       // 42（Ok 透传）
    print(unwrapOr(bad, 0))                     // 0（Err 兜底）
    print(isOk(r))                              // true
    when(bad) {
        is Ok(v) => { print(v) }
        is Err(e) => { print(concat("ERR: ", e)) }   // ERR: bad
    }
}
```

成功路径映射——mapOk 的 U 由 lambda 返回类型反推（ModuleE2ETest.kt:300-309 的验收形态，输出 6）：

```subl
@impure
fun main() {
    writeFile("p5-mr.txt", "hello!")                 // 失败也是 Result[Null, Str]
    var r = mapOk(readFile("p5-mr.txt"), { x => x.length })   // Result[Nat, Str]
    print(unwrapOr(r, 0))
}
```

错误传播：flatMapOk 链式短路——任一步 Err 即整体短路，错误透传到链尾（result.subl:43-47 的语义直读）：

```subl
// 两个 Result 任一为 Err，和就不算出来；两路成功才相加
fun addBoth(a: Result[Nat, Str], b: Result[Nat, Str]): Result[Nat, Str] =
    flatMapOk(a, { x => flatMapOk(b, { y => Ok(x + y) }) })
```

mapErr 在边界翻译错误（IO 调用须留在 async 上下文，故置于 main）：

```subl
@impure
fun main() {
    var r = mapErr(readFile("cfg.txt"), { msg => concat("读取失败: ", msg) })
    when(r) {
        is Ok(text) => { print(text) }
        is Err(reason) => { print(reason) }          // 读取失败: <宿主错误信息>
    }
}
```

从 Optional 接入错误通道（optional.subl:64 的 toResult：None 升级为带原因的失败）：

```subl
fun lookup(xs: List[Nat], i: Nat): Result[Nat, Str] =
    toResult(elementAt(xs, i), "下标越界")           // None → Err("下标越界")
```

IO 的 Err 分支善后（ModuleE2ETest.kt:278-285「读不存在的文件」用例）：

```subl
@impure
fun main() {
    var r = readFile("C:/no/such/subl-file.txt")
    when(r) {
        is Ok(_) => print("ok")
        is Err(e) => print("err")                    // 实际命中此分支
    }
}
```

单文件脚本模式不挂标准库时，按 examples/错误处理.subl 内联声明（工具函数同理自写）：

```subl
enum Result[T, E] { Ok(T), Err(E) }

fun unwrapOr[T, E](r: Result[T, E], d: T): T = when(r) {
    is Ok(v) => v
    is Err(_) => d
}
```

## 陷阱

1. **`->` 全位非法**：when 分支写 `is Ok(v) -> v` 会被 lexer 的 ARROW 诊断拦截（修正案后 `->` 仅供诊断）。程序位——when 分支、lambda、函数类型——一律 `=>`；命题蕴含才用 Unicode `→`。
2. **没有异常、没有 try/catch、也没有 `?` / `?:` / `?.`**：语言层空安全糖已随修正案移除（optional.subl:3-4），异常机制从未存在（JsCodeGen.kt:1135 的 try/catch 是 IO 内建的宿主层实现细节）。失败只能显式处理：when 解构、unwrapOr 兜底或 flatMapOk 上抛——不存在「忘记处理也让它炸上去」的隐式通道。
3. **漏 Err 分支 → W-NON-EXHAUSTIVE + 类型退化**：`when(r) { is Ok(v) => v }` 覆盖不全，发 W-NON-EXHAUSTIVE 警告且 when 表达式值类型退化为 Null（WhenCheck.kt:155-158）——穷尽性是提示不是要求，但退化后拿到的类型不可用。只写 `if (isOk(r))` 不解构同样拿不到错误值 e。
4. **unwrapOr 的默认值必须是 T**：签名 `d: T`（result.subl:25）——给 `Result[Nat, Str]` 配 `"zero"` 默认值类型不符；Err 时 e 被丢弃，兜底动作本身看不到原因。想保留原因就别用 unwrapOr，用 when。
5. **mapOk 与 mapErr 各管一条通道**：mapOk 换 T 不换 E、mapErr 换 E 不换 T，两侧都变得组合。lambda 本身返回 Result 时必须用 flatMapOk——`mapOk(r, { x => Ok(x + 1) })` 得到嵌套的 `Result[Result[Nat, E], E]`（mapOk 不展平）。
6. **Result 没有类型类方法**：`r.map(f)` / `r.flatMap(g)` / `r.fold(...)` 都不存在——二阶类型套不进 `F[_]`（typeclass.subl:6/10）。容器映射只能 mapOk/mapErr/flatMapOk；List/Optional 的类型类方法名不要惯性套用。
7. **panic 不是 Err**：`panic(): Nothing`（Types.kt:180）在宿主层 throw（JsCodeGen.kt:1256），绕过 Result 通道、源码无法捕获。它只用于「前置条件已证明、此位置理论不可达」的兜底（list.subl:79-83）与未实现占位（core/net/net.subl 的 httpGet 等签名登记为 `= panic()`，调用即抛）。把 panic 当错误返回值用是语义错误。
8. **Err 载荷类型要对上**：`Result[Nat, Str]` 的 Err 只能放 Str；多层组合时错误类型不一致是常见卡点——用 mapErr 在边界统一到上层错误类型。
9. **IO 函数是 @async @impure**：readFile/writeFile/readLine/appendFile/readDir 须在 async 上下文调用（main 天然 async，io.subl:3-6）；纯函数体内直接调用会被 async-in-sync 闸拦下（langfacts §6）。
10. **脚本模式不挂标准库**：Result 不是 prelude 枚举（Checker.kt:181-187 只注册 Bool/Null/Optional），`.subs` 单文件模式拿不到 `Result` 与 mapOk/unwrapOr——按 examples/错误处理.subl 内联声明；工程/目录模式才随 core 可见。
11. **`=` 是命题等式、`==` 才是值比较**：比较两个 Result 值用 `r == Ok(42)`（载荷结构相等）；把 `=` 写在值位会被运行时拒绝——「命题等式 '=' 是编译期概念，不参与运行时求值」（Evaluator.kt:286）。
12. **旧 axiom 形态已废除**：给 Result 相关命题登记事实用独立 `axiom p1, p2, …` 语句（顶层与函数内皆可），`unchecked.axiom[p]` 会报错引导（langfacts §2/§4）。
13. **深拷贝会穿透载荷**：把 Result 赋给 @mut 目标按通用拷贝规则深拷（langfacts §6），Ok 内的载荷（含结构体/列表）被递归复制——指望共享同一份载荷的别名预期会落空。
14. **toResult 是单向的**：Optional → Result 有库函数（optional.subl:64），反向 Result → Optional 没有库函数，需 when 解构并自行丢弃 e；别找不存在的 `toOptional`。
15. **net 模块的 Result 是占位**：core/net/net.subl 的 httpGet/httpPost/socketConnect 签名返回 Result 但体是 `panic()`——能力未落地（net.subl:3-5），按返回类型写的传播代码检查得过、运行即抛。
