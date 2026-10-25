# 01 · 基础 —— lambda 与闭包
主题域：lambda 表达式（大括号 lambda / 零参 / 隐式单参 / 解构参数 / 尾随 lambda）、函数类型、闭包捕获与深拷贝语义
事实来源：_facts/_langfacts.md（语言事实表）；源码引用 interpreter/src/main/kotlin/sugared/functor/interpreter/Evaluator.kt、frontend/src/main/kotlin/sugared/functor/parser/Parser.kt、compiler/src/main/kotlin/sugared/functor/codegen/JsCodeGen.kt（行号见文中标注，均为本次直接读取）；全部行为经 build/libs/sublimation.jar 实测（脚本模式 run-script、工程模式 compile→node，临时探针置于 _facts_tmp、验后已删）。

## 语法

### 总形

```
{ 参数1, 参数2, … => 体 }
```

- 参数表与体以 `=>` 分隔。修正案后 `->` 全位非法（lexer 的 ARROW token 仅供诊断），程序位一律 `=>`；命题蕴含位才用 Unicode `→`。
- 体可以是单表达式，也可以是语句序列（`var` / `return` / 赋值 / 多句，分号分隔），**末表达式即返回值**；仅一条表达式语句时退化为表达式体（Parser.kt:1684-1712）。
- v1.1 的反斜杠 lambda `\(x) => x` 已移除，BACKSLASH 不再解析（Parser.kt:1680）。

### 五种参数形态

| 形态 | 写法 | 说明（实测） |
|---|---|---|
| 多参 | `{ a, b => a + b }` | 参数表为逗号分隔的名字；无类型标注的 lambda 也可全局推导 |
| 零参 | `{ => 7 }` | 箭头不可省；实参个数由 Checker 按期望类型核对 |
| 隐式单参 | `{ x + 1 }` | 等价 `{ _ => x + 1 }`：形参名固定为 `_`，体内以 `_` 引用形参；其余名字按词法作用域捕获外层 |
| 解构参数 | `{ (a, b) => a * 100 + b }` | 接收**单个元组/List 实参**并拆分量；Parser 把参数名编码为 `"(a,b)"`（Parser.kt:434-449） |
| 尾随 lambda | `f(a) { … }` | 糖为 `f(a, { … })`——追加为**最后一个位置实参**（Parser.kt:1206-1216，补充码 D-TRAILING） |

大括号被识别为 lambda 的判定（防止与代码块混淆）：

- `braceIsLambda`（Parser.kt:1721-1743）：深度 1 内首个 `=>` 之前**只允许名字与逗号**（解构小括号配对除外）；混入其它 token（如 `{ 1 …… }` 的数字）即非 lambda，故 when 分支体 `{ 1 -> x }` 不会被误吃。
- `braceHasImplicitBody`（Parser.kt:1747-1760）：深度 1 内**无 `=>` 且无 `:`** 时，`{ 表达式 }` 即隐式单参 lambda（`:` 是类型测/类型标注，出现即排除隐式体）。

### 尾随 lambda 的适用范围

- 仅当 callee 已是「调用结果」：`apply2(5) { _ * 10 }`、`xs.map { x => x * 10 }`、`(1..3).map { _ * 2 }`，乃至零参调用 `callTwice() { => 7 }` 都成立（均实测）。
- `Task { … }` / `Flow { … }` 的体是任务/冷流的**代码块**，不是 lambda（异步决策 93）；字面量 / 运算符结果后的 `{`（如 `1 {`）是 ∃ 消去块的边界，也不是尾随 lambda（v3.0 特性 7）。

### 函数类型与函数值

```
(Nat) => Nat            一元函数类型
(Nat, Str) => Null      多元
() => Null              零参函数类型
[T](T) => T             多态函数类型（[T] 是理论参数前缀，Parser.kt:485-501）
@impure (Nat) => Nat    带纯度注解的函数类型（@impure 只能修饰函数类型，Parser.kt:514-518）
```

类型位的 `(` 后有 `=>` 是函数类型，否则是元组类型 `(T, U)`（Parser.kt:566-584）。需要类型参数的**函数值**用匿名函数表达式 `fun [T](x: T): T = x`（Parser.kt:839-857；体同样禁止 `= {…}` 混写：单表达式去括号、块体去等号）。具名函数的形参表也支持元组解构 `fun f((a, b): Point)`（Parser.kt:382-400），与 lambda 解构参数同族。具名函数可直接作函数值传递（`var h: (Nat) => Nat = inc`，实测 `h(4)` 得 5）。

## 语义

### 求值模型

解释器把 lambda 求值为闭包值：`evalLambda`（Evaluator.kt:838-853）以 `ClosureFun`（Evaluator.kt:833-836）持有创建点的帧 `frame`。调用时创建子帧 `ScriptFrame(frame)`、绑定形参（bindLambdaParams，Evaluator.kt:822-831）、求值体；体内 `return` 抛出的 `ReturnException` 被 lambda 自身的调用包装捕获（Evaluator.kt:842-844）——**lambda 内 return 返回该 lambda 本身，不影响外围函数**。

### 形参绑定与 arity

- 解构参数按元组/List 分量**下标**绑定：实参须是 List 值（元组在值模型里是 ListV，元组字面量 `(3, 4)`、`listOf(1, 2)`、`Cons…Nil` 均可，实测 `pair((3, 4))` 得 304）；分量不足的位置绑 `Null`（实测 `(a, b, c)` 收两元列表 → `c = null`）；实参不是 List 则**全部分量**为 Null。
- 参数个数在 Checker 按期望函数类型核对：`{ => 9 }` 传给 `(Nat) => Nat`、`{ _ + 1 }` 传给 `() => Nat`，均报 `E-TYPE-MISMATCH「lambda 有 N 个参数，期望类型要求 M 个」`（实测）。运行时零参 lambda 其实会忽略多余实参——但这类程序过不了检查期。

### 纯度

- **读取 / 写入外部 @mut 变量**的 lambda 被推导为 impure：赋给纯函数类型标注报 `E-TYPE-MISMATCH「标注了 ()=>Nat，但初值是 impure ()=>Nat」`（实测），类型位须写 `@impure () => Nat`。
- 捕获 `@ref`（共享只读视图，写法 `@ref var r: Nat = n`——注意 @ref 不是类型位注解）**不**致 impure（实测：捕获 @ref 变量的 lambda 可赋纯函数类型）。

### 闭包捕获：直播 vs 快照（核心语义）

闭包缺省持有**创建点的活帧**——当绑定不触发拷贝时（如纯目标 `var f = { => n }`），外层变量的后续改动对闭包**可见**（实测：`n = 100` 后 `f()` 得 100）。当函数值流经一个触发拷贝的绑定时，`deepCopy` 对 `ClosureFun` 调用 `copyCapture()`（Evaluator.kt:145-158 的 Fun 分支 → :845）：把 lambda 在 `copiedFrame(frame)` 上**重建**——沿父链逐层收集全部变量与局部函数，逐值深拷（保留 @mut/@ref 标注，外层优先、内层同名覆盖，Evaluator.kt:128-143），闭包由此拿到快照（实测：`@mut var g = { => m }` 后 `m = 100`，`g()` 仍得 1）。

触发拷贝的四条款（`bindCopy`，Evaluator.kt:104-119；与 Checker `binderNeedsCopy` 同一谓词）：

$$\frac{\text{tgt} @\text{mut} \land (\neg\,\text{tgt} @\text{ref} \lor \text{src 纯})}{\text{拷贝}} \qquad \frac{\text{src} @\text{mut} \land \text{tgt 纯}}{\text{拷贝}} \qquad \frac{\text{src} @\text{ref} \land \neg\,\text{tgt} @\text{ref}}{\text{拷贝}} \quad (\text{可变拷贝四条款})$$

即：拷贝 ⟺ （目标含 @mut 且（不带 @ref 或源纯））或（源含 @mut 且目标纯）或（源带 @ref 且目标不带 @ref）。深拷对函数值**递归穿透**：嵌套闭包的捕获一并快照（实测见示例 5）。注意 _langfacts.md 的「创建时深拷贝」是对这一机制的简化表述——准确说是「**函数值被深拷时，其捕获环境一并深拷**」，纯传递永远共享活帧。

**JS 编译侧的既定边界**：JS 闭包不透明，深拷助手 `__dc` 对函数值早退透传（`typeof v !== "object"`，JsCodeGen.kt:325-332）；注释 :322-323 明言「修正案要求的深拷贝闭包捕获由脚本模式 Evaluator 的 ClosureFun 分支实现，编译模式不变」。同一份源码经 `compile` 生成的 JS 中闭包**一律直播捕获**——实测同程序解释器输出 1、node 输出 100，属两执行模式的确定差异而非 bug。跨模式移植时不应依赖快照，而应让被捕获变量本身不可变。

### 类型推导（要点）

- 带期望类型 `(A) => B` 检查 lambda 时参数按 `A` 绑定、体按 `B` 核对；无期望时返回类型由体反推（脚本模式经全局推导，实测 `var myAdd = { a, b => a + b }` 可用）。
- 满足多态函数类型位 `[T](T) => T` 时 lambda 继承期望类型的理论参数、保持多态（实测 `var id: [T](T) => T = { _ }` 得 7）。
- 初值为 lambda / 匿名函数的变量允许体内引用自身（letrec，实测 `var fact: (Nat) => Nat = { n => if (n <= 1) then 1 else n * fact(n - 1) }` 得 120）。
- 命题 / 上下文系统对 lambda 帧的弥散边界（外层下文命题是否进 lambda 体）**待核**。

## 示例

示例 1 · 五种参数形态（脚本模式 .subs，逐行实测）：

```
var myAdd = { a, b => a + b }            // 多参（无标注，全局推导）
unchecked print(myAdd(3, 4))             // 7
var z = { => 7 }                          // 零参
unchecked print(z())                      // 7
var n = 10
var inc = { _ + n }                       // 隐式单参：_ 是形参，n 是捕获
unchecked print(inc(5))                   // 15
var pair = { (a, b) => a * 100 + b }      // 解构参数（元组实参）
unchecked print(pair((3, 4)))             // 304
var xs = Cons(1, Cons(2, Cons(3, Nil())))
unchecked print(xs.map { x => x * 10 })   // 尾随 lambda：[10, 20, 30]
unchecked print((1..3).map { _ * 2 })     // [2, 4, 6]
var f: (Nat) => Nat = { _ + 1 }           // 函数类型标注（双向推导）
unchecked print(f(9))                     // 10
```

示例 2 · 零参、多态函数类型与零参尾随：

```
var g: () => Nat = { => 7 }
unchecked print(g())                      // 7
var id: [T](T) => T = { _ }               // 多态函数类型位
unchecked print(id(7))                    // 7
fun callTwice(f: () => Nat): Nat = f()
unchecked print(callTwice() { => 7 })     // 零参 lambda 也可尾随：7
var ids: [T](T) => T = fun [T](x: T): T = x   // 匿名函数表达式（带类型参数）
unchecked print(ids(8))                   // 8
```

示例 3 · 语句体、return 与递归 lambda：

```
var f = { x => var t = x * 2; t + 1 }     // 语句体：末表达式为返回值
unchecked print(f(3))                     // 7
var abs2 = { x => if (x > 0) { return x * 2 } else { return 0 } }
unchecked print(abs2(3))                  // 6（return 返回该 lambda 本身）
var fact: (Nat) => Nat = { n => if (n <= 1) { 1 } else { n * fact(n - 1) } }
unchecked print(fact(5))                  // 120（letrec 递归 lambda）
```

示例 4 · 捕获：快照 vs 直播（两模式对照）：

```
@mut var n: Nat = 10
@mut var f: @impure () => Nat = { => n }  // @mut 目标 → copyCapture 深拷捕获（四条款第 1 条）
n = 20
unchecked print(f())                      // 解释器：10（快照）
                                          // 同代码 compile 成 JS：20（__dc 对函数透传，直播捕获）
```

示例 5 · 嵌套闭包的深拷穿透（**须各闭包都已 @mut 绑定**，原因见陷阱 9）：

```
@mut var m: Nat = 1
@mut var g: @impure () => Nat = { => m }
@mut var h: @impure () => Nat = { => g() } // h 的捕获里含函数值 g，深拷一并穿透
m = 99
unchecked print(h())                      // 1（g 随 h 的拷贝被再快照，其捕获定格在 1）
```

示例 6 · impure 捕获与 @ref 捕获的对照：

```
@mut var n: Nat = 10
var f: @impure () => Nat = { => n }       // 捕获 @mut → 必须写 @impure
n = 20
unchecked print(f())                      // 20（纯目标绑定，直播捕获）
@mut var k: Nat = 5
@ref var r: Nat = k                       // @ref 是变量注解，不是类型位注解
var p: () => Nat = { => r }               // 捕获 @ref 不致 impure
unchecked print(p())                      // 5
```

## 陷阱

1. **`{ 7 }` 不是零参 lambda**。大括号内无 `=>` 时被解析为**隐式单参** lambda（形参 `_`），实参被忽略：`var k = { 7 }` 后 `k(99)` 得 7（实测）。零参必须写 `{ => 7 }`。
2. **显式参数 lambda 体内写 `_` 报 E-UNBOUND-NAME**：`{ x => _ + 1 }` 中 `_` 不是形参（形参是 `x`）。`_` 只在隐式单参体或显式 `{ _ => … }` 中合法（实测）。
3. **`->` 非法**：`{ x -> x }` 报「期望表达式，实际 '->'」。程序位（lambda、when 分支、函数类型、零参 lambda）一律 `=>`；`→` 只用于命题蕴含。
4. **arity 由 Checker 卡死**：零参 lambda 传给 `(Nat) => Nat`、隐式单参传给 `() => Nat` 都报 `E-TYPE-MISMATCH「lambda 有 N 个参数，期望类型要求 M 个」`——别指望运行时「零参忽略实参」的宽松。
5. **解构参数只吃 List/元组序对值**：实参须是 ListV（`(3, 4)` / `listOf(…)` / `Cons…Nil`）；非 List 实参 → 全部分量为 Null，分量不足的位置也是 Null。结构体实参不走 lambda 解构（具名函数形参 `(a, b): Point` 的结构体键解构是 codegen 另一条路径，待核）。
6. **捕获 @mut 变量 → impure**：赋纯函数类型标注报 `E-TYPE-MISMATCH「初值是 impure」`，改为 `@impure (T) => U` 或去掉标注；捕获 `@ref` 变量不受影响（均实测）。
7. **快照与否取决于绑定目标，而非「一律拷贝」**：纯目标 `var f = { => n }` 直播捕获（外层修改对闭包可见）；只有触发四条款的绑定（`@mut var f = …`、源 @mut 绑给纯目标、源 @ref 绑给非 @ref 目标）才深拷快照。把闭包一律当快照、或一律当引用，都会误判。
8. **编译模式（JS）没有捕获快照**：`__dc` 对函数值透传是既定边界（JsCodeGen.kt:322-323），`compile` 出的 JS 中闭包一律直播捕获——需要跨模式一致时，让被捕获变量本身不可变。
9. **解释器 copyCapture 的递归边界（实测栈溢出）**：若作用域里**先有纯绑定的闭包**（`var f = { => m }`，共享活帧），此后**任何**对该帧中闭包的深拷都会无限递归——`@mut var g = { => m }`、乃至 `@mut var h = f`，都会在 `copiedFrame → deepCopy(f) → f.copyCapture → copiedFrame(活帧含 f) → …` 处 StackOverflowError（Evaluator.kt:138/156/845）。规避：需要快照的闭包一律用 @mut 绑定（其捕获帧是已冻结的拷贝，见示例 5），或干脆只捕获不可变值。编译模式（JS）无此问题。
10. **lambda 内 `return v` 的类型代价**：含 return 的 lambda 体被 Checker 判为 Null——有 `(Nat) => Nat` 之类返回类型标注时报 `E-TYPE-MISMATCH「lambda 体类型 Null 与期望返回 Nat 不符」`（实测）；无标注或返回类型为 Null 时可用，且 return 只退出该 lambda 本身。
11. **尾随 lambda 的三个边界**：只在「调用结果」后成立；`Task { … }` / `Flow { … }` 的体是代码块而非 lambda；字面量后的 `{` 是 ∃ 消去块边界。零参调用 `f() { … }` 可以接尾随 lambda（实测）。
12. **lambda 变量名别撞内建 / 标准库函数**：脚本模式实测 `var add = { a, b => a + b }` 后 `add(3, 4)` 被解析到内建集合函数 `add`，报 `E-TYPE-MISMATCH「实参 0 Nat 与形参 Set[Nat] 不符」`；给 lambda 变量改名（如 `myAdd`）即正常。
