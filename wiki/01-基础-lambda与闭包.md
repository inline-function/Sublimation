# 01 · 基础 · lambda 与闭包

> **卷名**：基础（第 1 卷）　**主题域**：lambda 与闭包——大括号 lambda、函数类型、闭包捕获与深拷贝
> **事实来源**：`_facts/_langfacts.md` 语言事实表 ＋ 下列源文件（行号为近似定位，以代码语义为准）：
> `frontend/.../parser/Parser.kt`（lambda 文法：433-450、483-583、1206-1221、1680-1764）、
> `compiler/.../checker/Checker.kt`（lambda 类型与纯度：536-543、608-620、773-785、913-916、1080-1145）、
> `compiler/.../checker/Diffusion.kt:7`、`compiler/.../codegen/JsCodeGen.kt`（`__dc` 边界 319-332、lambda 生成 898-919）、
> `interpreter/.../Evaluator.kt`（bindCopy 104-119、copiedFrame 128-143、deepCopy 145-158、bindLambdaParams 819-831、ClosureFun/evalLambda 833-872）。
> 本条目所有代码示例均以 `build/libs/sublimation.jar` 实测通过（脚本模式 `run-script`、工程模式 `compile`）。

## 语法

### 总形

```
{ 参数1, 参数2, … => 体 }
```

- 参数表与体以 `=>` 分隔。修正案后 `->` 全位非法（lexer 的 ARROW token 仅供诊断），程序位一律 `=>`；命题蕴含位才用 Unicode `→`。
- 体可以是单表达式，也可以是语句序列（`var` / `return` / 赋值 / 多句），**末表达式即返回值**（Parser.kt:1682-1715）。
- 旧的 v1.1 反斜杠 lambda `\(x) => x` 已移除，BACKSLASH 不再解析（Parser.kt:1680）。

### 五种参数形态

| 形态 | 写法 | 说明 / 等价展开 |
|---|---|---|
| 多参 | `{ a, b => a + b }` | 参数表为逗号分隔的名字 |
| 零参 | `{ => 7 }` | 参数表为空，箭头不可省 |
| 隐式单参 | `{ x + 1 }` | 等价 `{ _ => x + 1 }`：形参名固定为 `_`，体内以 `_` 引用形参；其余名字按词法作用域捕获外层 |
| 解构参数 | `{ (a, b) => a * 100 + b }` | 接收**单个元组实参**并拆分量；Parser 把参数名编码为 `"(a,b)"`（Parser.kt:433-450） |
| 尾随 lambda | `f(a) { … }` | 糖为 `f(a, { … })`——括号后的大括号 lambda 追加为**最后一个位置实参**（Parser.kt:1206-1213，补充码 D-TRAILING） |

大括号被识别为 lambda 的判定（防止与代码块混淆）：

- `braceIsLambda`（Parser.kt:1721-1745）：深度 1 内首个 `=>` 之前**只允许名字与逗号**（解构括号配对除外）；混入其他 token（如 `{ 1 -> x }` 的数字）即非 lambda。
- `braceHasImplicitBody`（Parser.kt:1747-1764）：深度 1 内**无 `=>` 且无 `:`** 时，`{ 表达式 }` 即隐式单参 lambda。
- 尾随 lambda 只允许跟在「可调用结果」之后；字面量 / 运算符结果后的 `{` 不是尾随 lambda（v3.0 特性 7：`1 {` 是 ∃ 消去块的边界），`Task { … }` / `Flow { … }` 的体是代码块而非 lambda（异步决策 93，Parser.kt:1216-1221）。

### 函数类型

```
(Nat) => Nat            一元函数类型
(Nat, Str) => Null      多元
() => Null              零参函数类型
[T](T) => T             多态函数类型（[T] 是理论参数前缀，Parser.kt:483-521）
@impure (Nat) => Nat    带纯度注解的函数类型（@impure 只能修饰函数类型，Parser.kt:513-519）
```

v3.0（完美函数类型）起函数类型携带纯度字段 `FunType.purity`：体内含 impure 调用或捕获 @mut 变量的 lambda 类型为 impure。函数类型还可携带上下文命题（`(T)<…> => Null` 形态，Checker.kt:1112-1116 注入 lambda 体作前提事实；上下文系统细节见相关条目，**待核**）。

## 语义

### 求值模型

- 解释器把 lambda 求值为闭包值：`ClosureFun`（Evaluator.kt:833-854）持有创建点的帧 `frame`。调用时创建子帧 `ScriptFrame(frame)`、绑定形参、求值体；体内 `return` 抛出 `ReturnException` 被捕获后作为返回值——**lambda 内 return 返回该 lambda 本身**（Checker.kt:773-781），值位 return 非法。
- 解构参数在解释器侧按元组拆分（`bindLambdaParams`，Evaluator.kt:819-831）：形参名 `"(a,b)"` 对应单个元组实参，按位置拆出分量；元组在值模型里是 ListV（元组→JS 数组，决策 61）。结构体解构则由 Checker 记录字段键 `lambdaDestructKeys` 供 codegen 按字段取（Checker.kt:1091-1111）。
- 作用域：闭包沿父链捕获外层变量；命题 / 上下文的弥散会把块事实回溢父帧，但 **lambda 帧隔离**（Diffusion.kt:7）——lambda 子帧不参与块事实向父帧的回溢（更精确的扩散边界**待核**）。

### 纯度

- lambda 体内发生 impure 调用、或**读取 / 写入外部捕获的 @mut 变量**，都把该 lambda 推导为 impure（Checker.kt:608-620 写、913-916 读、1082/1142 定型为 `FunType.purity="impure"`）。
- 体无 impure 调用的纯 lambda 登记自然命题 `pure<{ … }>`（Checker.kt:1140-1141），可被证明系统消费。
- 类型层后果：把捕获 @mut 变量的 lambda 绑定到**纯**函数类型标注会报 E-TYPE-MISMATCH「初值是 impure」；须改写为 `@impure (Nat) => Nat` 或去掉标注。捕获 @ref（共享只读视图）变量**不**致 impure。

### 闭包捕获的深拷贝（核心语义）

闭包值缺省持有**创建点的活帧**（live frame）：当绑定不触发拷贝时（如纯目标 `var f = { _ + n }`），外层变量的后续改动对闭包**可见**。当函数值流经一个触发拷贝的绑定时，`deepCopy` 对 `ClosureFun` 调用 `copyCapture()`（Evaluator.kt:145-158、845）：把 lambda 在 `copiedFrame(frame)` 上**重建**——沿父链逐层收集全部变量与局部函数，逐值深拷（保留 @mut/@ref 标注，外层优先、内层同名覆盖，Evaluator.kt:128-143）。深拷对函数值递归穿透，**嵌套闭包的捕获一并快照**。这就是「闭包捕获在创建（绑定）时深拷贝」的实现机制。

触发拷贝的四条款（`bindCopy`，Evaluator.kt:104-119；与 Checker `binderNeedsCopy` 同一谓词）：

$$\frac{\text{tgt}@{mut}\ \land\ (\neg\,\text{tgt}@{ref}\ \lor\ \text{src 纯})}{\text{拷贝}} \qquad \frac{\text{src}@{mut}\ \land\ \text{tgt 纯}}{\text{拷贝}} \qquad \frac{\text{src}@{ref}\ \land\ \neg\,\text{tgt}@{ref}}{\text{拷贝}} \quad (\text{可变拷贝四条款})$$

即：拷贝 ⟺ （目标含 @mut 且（不带 @ref 或源纯））或（源含 @mut 且目标纯）或（源带 @ref 且目标不带 @ref）。典型情形：

- `@mut var f = { _ + n }`：目标 @mut、源纯 → **深拷捕获**（创建即快照）；
- `var p = g`（g 是 @mut 函数变量）：源 @mut、目标纯 → **再次深拷**；
- `var f = { _ + n }`（纯目标、纯源）：不拷贝，闭包**直播捕获**（实测 `n = 20` 后 `f(0) == 20`）。

**JS 编译侧的既定边界**：JS 闭包不透明，深拷助手 `__dc` 对函数值早退透传（`typeof v !== "object"`，JsCodeGen.kt:325-332）；注释 322-332 明言「修正案要求的深拷贝闭包捕获由脚本模式 Evaluator 的 ClosureFun 分支实现，**编译模式不变**」。因此同一份源码经 `compile` 生成的 JS 中，闭包**一律直播捕获**——两种模式行为存在差异（见示例 4、陷阱 6）。

### 类型推导

- 双向推导（决策 55/57）：带期望类型 `(A) => B` 检查 lambda 时，参数按 `A` 绑定、体按 `B` 核对；参数个数不符报 E-TYPE-MISMATCH（Checker.kt:1081-1089）。
- 无期望时参数为合成占位，返回类型由体反推（如 `map` 的 `U` 只能由 lambda 体推出）；链式泛型靠定点迭代收敛（CallCheck.kt:801-838）。
- 满足多态函数类型位（`[T](T) => T`）时，lambda **继承期望类型的理论参数**、保持多态（Checker.kt:1134-1137）。
- 递归函数值（letrec）：初值是 lambda / 匿名函数时，允许体内引用自身（Checker.kt:536-543）；有类型标注时标注类型作为 expect 参与双向推导。

## 示例

**示例 1 · 五种参数形态**（脚本模式，逐行实测）：

```
var myAdd = { a, b => a + b }            // 多参
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
unchecked print(xs.fold(0) { acc, x => acc + x })   // 6
var f: (Nat) => Nat = { _ + 1 }           // 函数类型标注（双向推导）
unchecked print(f(9))                     // 10
```

**示例 2 · 零参 / 多态函数类型**：

```
var g: () => Nat = { => 7 }
unchecked print(g())                      // 7
var id: [T](T) => T = { _ }               // 多态函数类型
unchecked print(id(7))                    // 7
fun callTwice(f: () => Nat): Nat = f()
unchecked print(callTwice() { => 7 })     // 零参 lambda 也可尾随：7
```

**示例 3 · 语句体、return 与递归 lambda**：

```
var f = { x => var t = x * 2; t + 1 }     // 语句体：末表达式为返回值
unchecked print(f(3))                     // 7
var abs2 = { x => if (x > 0) { return x * 2 } else { return 0 } }
unchecked print(abs2(3))                  // 6（return 返回该 lambda）
var fact: (Nat) => Nat = { n => if (n <= 1) { 1 } else { n * fact(n - 1) } }
unchecked print(fact(5))                  // 120（letrec 递归 lambda）
```

**示例 4 · 捕获：快照 vs 直播（两模式对比）**：

```
@mut var n = 10
@mut var f = { _ + n }                    // @mut 目标 → 创建时深拷捕获（四条款第 1 条）
n = 20
unchecked print(f(0))                     // 解释器：10（快照）
                                          // 同代码 compile 成 JS：20（__dc 对函数透传，直播捕获）
```

**示例 5 · 嵌套闭包与结构体捕获**（深拷逐值穿透，实测）：

```
@mut var m = 1
@mut var g = { => m }
@mut var h = { => g() }                   // h 的捕获里含函数值 g
m = 99
unchecked print(h())                      // 1（g 的捕获被一并深拷）
@mut struct Pt(@mut x: Nat, @mut y: Nat)
@mut var p = Pt(1, 2)
@mut var k = { => p }
p.x = 99
unchecked print(k())                      // Pt(x=1, y=2)（字段级深拷）
```

**示例 6 · impure 函数类型**（捕获 @mut 变量）：

```
@mut var n = 10
var f: @impure (Nat) => Nat = { _ + n }   // 捕获 @mut → lambda 为 impure
n = 20
unchecked print(f(0))                     // 20（纯目标绑定，直播捕获）
```

## 陷阱

1. **`{ 7 }` 不是零参 lambda**。大括号内无 `=>` 时被解析为**隐式单参** lambda（形参 `_`），实参被忽略：`var k = { 7 }; k(99)` 得 7。零参必须写 `{ => 7 }`。
2. **显式参数 lambda 体内写 `_` 报 E-UNBOUND-NAME**：`{ x => _ + 1 }` 中 `_` 不是形参（形参是 `x`）。`_` 只在隐式单参体或显式 `{ _ => … }` 中合法。
3. **`->` 非法**：`{ x -> x }` 报「期望表达式，实际 '->'」。程序位（lambda、when 分支、函数类型、零参 lambda）一律 `=>`；`→` 只用于命题蕴含。
4. **纯函数类型标注 ＋ 捕获 @mut 变量 → E-TYPE-MISMATCH**：`var f: (Nat) => Nat = { _ + n }`（n 为 @mut）报「初值是 impure (Nat)=>Nat」。改为 `@impure (Nat) => Nat` 或去掉标注；捕获 @ref 变量不受影响。
5. **捕获是否快照取决于绑定目标，而非「一律拷贝」**：纯目标 `var f = { _ + n }` 是直播捕获（外层修改对闭包可见）；只有触发四条款的绑定（`@mut var f = …`、源 @mut 绑给纯目标、源 @ref 绑给非 @ref 目标）才深拷快照（解释器行为）。
6. **编译模式（JS）没有捕获快照**：`__dc` 对函数值透传是既定边界，`compile` 出的 JS 中闭包一律直播捕获。需要跨模式一致时，应让被捕获变量本身不可变（纯变量），而不是依赖快照。
7. **尾随 lambda 的三个边界**：只有可调用结果后能接尾随 lambda；`Task { … }` / `Flow { … }` 的体是代码块（不是 lambda）；字面量后的 `{`（如 `1 {`）是 ∃ 消去块边界。
8. **when 分支的 `=>` 不是 lambda**：`when (x) { is Some(v) => { … } }` 中 `=>` 分隔模式与分支体；`braceIsLambda` 的判定保证 `{ 1 -> x }` 类形态不被误吃为尾随 lambda。
9. **形参遮蔽外层同名变量**：`var n = 10; var f = { n => n + 1 }; f(5)` 得 6。
10. **实参个数错误报 E-ARITY**：`var add = { a, b => a + b }; add(1)` 报「想要 2~2 个实参」。零参 lambda 用 `z()` 调用。
11. **变量名与内建 / 标准库函数同名会撞调用解析**：脚本模式实测 `var add = { a, b => a + b }` 后调用 `add(3, 4)` 被解析到内建 `add`（报「实参 0 Nat 与形参 Set[T] 不符」）；给 lambda 变量改名（如 `myAdd`）即正常。
12. **命题弥散不进 lambda**：外层调用的下文命题会弥散进后续作用域，但 lambda 帧隔离（Diffusion.kt:7）——不要期望在 lambda 体内自动捡到外层的下文事实（精确扩散边界**待核**）。
