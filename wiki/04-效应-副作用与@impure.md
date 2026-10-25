# 副作用与 @impure：效应声明与纯度传播

> wiki/04-效应-副作用与@impure · 主题域：效应与纯度（_langfacts §6；命题基础 §4）
> 事实来源：_langfacts.md §4/§6；compiler/.../checker/ 下 Types.kt（builtinimpure :49 起）、Symbols.kt（registerPureFacts :11 起、跨模块补全 :30 起）、Checker.kt（checkFun :291、E-IMPURE-REQUIRED :294、isPureValue :812、lambda 纯度跟踪 :1083–1144）、CallCheck.kt（函数值纯度闸 :479、停机闸 :747、impure 调用闸 :753–764、E-NEED-ASYNC :769）、Diffusion.kt（containsImpureCall :144、结构相等豁免 :307）、Parser.kt（parseAnnotations :261、类型位 @impure :516、parseStmt :631 起）、Ast.kt（FunType.purity :156）；行为经 build/libs/sublimation.jar 实测探针验证（check / run-script，临时探针位于 _facts_tmp，验后已删除）

`@impure` 是函数的**效应声明**：作者借此声明「本函数可能产生副作用」。它是行为层注解（不改类型、不产生运行时检查），唯一的编译期后果是驱动**纯度闸**——纯作用域内不得调用非纯函数。纯度不是挂在函数上的布尔标志，而是一条命题 `pure<f>`：上下文（Γ）里持有 `pure<f>` 时，即便 `f` 标了 `@impure`，编译器也把该调用当纯调用放行。与 `unchecked` 的分工是本文的核心：`unchecked <语句>` 是**按语句的检查豁免**（机制），`@impure` 是**函数级的效应声明**（契约），两者不可互相替代。

## 语法

```subl
@impure fun 名字(参数): 返回类型 { 体 }            // 函数声明注解（可跨行）
@impure @async fun sleep(ms: Nat): Null {}         // 与其它注解叠加（@async 正交）
fun run(f: @impure () => Null): Null { f() }       // 函数类型位注解（借用效应）
```

注解序列由 parseAnnotations 吃掉连续的 `@名`（Parser.kt:261）；顶层只允许注解后跟 `fun`/`struct`/`class`（Parser.kt:196 起），脚本模式才回退到语句位。

**函数类型位**的 `@impure`（Parser.kt:513 起）：只能修饰函数类型，写成 `f: @impure (A) => Null`；修饰其它类型直接语法错（「类型位注解只支持 @mut / @atomic / @impure」）。它落在 `FunType.purity`（Ast.kt:156），渲染为 `impure (A) => Null`。

**宿主内建函数**是 `@impure` 语义的内建来源（Types.kt builtinimpure :49，注释称「@impure 语义的唯一来源」）：

| 类别 | 函数 |
|---|---|
| IO / 参数 | `print`、`readLine`、`readFile`、`writeFile`、`appendFile`、`fileExists`、`readDir`、`getArgs` |
| 时间 / 随机 | `timeNow`、`rand` |
| 异步 / 并发（`@impure @async`） | `sleep`、`delay`、`runBoth`、`runSeq`、`collectToList`、`collectCount`、`collectAll`、`withMutex`、`bumpCounter`、`bumpPair`、`channelFromList`、`sendAll`、`channelDrain`、`channelPipe` |
| 安卓桥（annotationproc 侧） | `log`、`toast`、`layout`、`onCreate` 等（AndroidBuiltins.kt） |

用户函数的纯度只由**自己的注解决定**；`@root` 函数（无体内建声明）疑似由内建表判定，用户注解不参与（Symbols.kt:19 起）。

**不能标注的位置**：

- **局部函数**：函数体内的 `@impure fun local(…)` 是语法错（parseStmt 的 `@` 分支只通向 `var` 与策略调用，见 Parser.kt:653；实测报「期望 var，实际 'fun'」）。效应声明只在顶层函数与 impl 成员上有效。
- **参数位**：参数可变语义用类型 `@mut T` 表达（不是注解）；类型位只认 `@mut`/`@atomic`/`@impure`。

**三种「绕开纯度」形态对比**：

| 形态 | 层级 | 性质 |
|---|---|---|
| `@impure fun f` | 函数声明 | 声明「我有副作用」，体按非纯检查；效应沿调用链向上传播 |
| `unchecked <语句>` | 单条语句 | 一次性豁免该语句的可变性/纯度/停机闸（uncheckedDepth++） |
| `axiom pure<f>` / `by [pure<f>]` | 命题层 | 向 Γ 供给 `pure<f>`，使对应非纯调用在纯作用域合法 |

## 语义

### 默认纯，声明非纯

未标 `@impure` 的函数即纯函数，函数体按**纯作用域**检查（Checker.kt:291）；标了 `@impure` 则体按非纯检查：

$$\frac{\texttt{impure} \notin ann(f)}{\Gamma \vdash body(f)\ \text{按纯作用域检查}} \quad (\text{默认纯}) \qquad \frac{\texttt{impure} \in ann(f)}{\Gamma \vdash body(f)\ \text{按非纯作用域检查}} \quad (\text{@impure})$$

强制条款：参数类型含 `@mut T` 的函数**必须**标 `@impure`（Checker.kt:293），否则 **E-IMPURE-REQUIRED**——「函数 f 的参数是 @mut 可变类型，必须给函数标上 @impure」。

### pure<f> 命题：注册与调用闸

决策 34：每个未标 `@impure` 的具名函数，编译器自动注册自然命题 `pure<f>` 进全局 Γ（Symbols.kt:26）；跨模块可见的纯函数按裸名一并注册，**同名函数在任一可见模块有 @impure 定义则整名不注册**（保守拦截，Symbols.kt:31 起）。调用闸只对非纯被调者生效：

$$\frac{\texttt{impure} \in ann(f) \qquad ctx.\text{pure} \qquad \texttt{uncheckedDepth} = 0 \qquad \Gamma \nvdash pure\langle f\rangle}{\textbf{E-IMPURE-CALL}} \quad (\text{具名调用闸，CallCheck.kt:755})$$

放行因此有三条路：**调用方非纯**（外层函数自己标 `@impure`）、**uncheckedDepth > 0**（语句被 `unchecked` 包住）、**Γ ⊢ pure<f>**（命题供给）。方法路径（`c.bump()`）的纯度原子统一按方法名注册与查询（O2，CallCheck.kt:760）。

函数值另有一道闸（v3.0「完美函数类型」，CallCheck.kt:479）：

$$\frac{type(v) = \texttt{impure}\,(A) {\Rightarrow}\, B \qquad ctx.\text{pure} \qquad \texttt{uncheckedDepth} = 0 \qquad \neg borrowed(v)}{\textbf{E-IMPURE-CALL}} \quad (\text{函数值闸})$$

其中 `borrowed` 指被调函数值恰是**本函数声明的 @impure 函数值形参**（CallCheck.kt:483）——形参即显式效应能力，纯函数也能调用它；这就是函数类型位 `@impure` 的「借用效应」。

### 纯度传播：lambda 与匿名函数

lambda 字面量的纯度不是写的，是**推导**的（Checker.kt:1083 起）：进入 lambda 时重置 `curLambdaImpure`，体内遇到以下任一情形就置位——

- 调用 `@impure` 函数或纯度为 impure 的函数值（CallCheck.kt:754、:480）；
- 修改**外层捕获**的 `@mut` 变量（当前帧未声明但沿父链可变，Checker.kt:615）。

体结束未置位则 lambda 类型为纯、并向外层帧注入自然命题 `pure<λ>`（Checker.kt:1141）；置位则 `FunType.purity = "impure"`，该函数值再在纯作用域被调用即触发上一条的函数值闸。匿名函数（AnonFunExpr）走同一机制（Checker.kt:1311 起）。

### 事实层后果

- **不注入绑定等式**：`var x = one()` 时，`isPureValue` 对含 `@impure` 调用的调用表达式返回 false，故 `x = one()` 的相等事实不入 Γ（Checker.kt:819）。
- **结构相等豁免**：证明目标 `a = b`、`a < b` 等二元原子若**任一侧含 @impure 调用**（containsImpureCall，Diffusion.kt:144），不参与自反/结构相等，只能命中 Γ 中**显式注入**的同形事实（Diffusion.kt:307）。
- **下文照常弥散**：`@impure` 函数声明的下文（`<$ = 1>`）在调用点照常注入 Γ（CallCheck.kt:940）——结果值「不确定」不等于「声明的后件不被信任」。

### 与 unchecked 的分工

`unchecked <语句>` 把被包语句的纯度/可变性/停机闸**整体降级**（Checker.kt:767：进入时 uncheckedDepth++，语句以 pure=false 检查）；它是**词法作用域内一次性**的豁免，不改变任何函数的效应身份。`@impure` 恰好相反：它不豁免任何检查，只是把函数**声明**为非纯——代价是纯上下文不能再 plainly 调用它，效应义务沿调用链向上传递。

### 联动与边界

- **停机闸**：纯函数自递归须有实参在「变小」，否则 **E-NON-STRUCTURAL-REC**，其诊断直接建议「给函数加 `@impure`」（CallCheck.kt:747）——`@impure` 是该闸的唯一文档化逃逸。
- **与 @async 正交**：`@impure` 不豁免 E-NEED-ASYNC（CallCheck.kt:769 只看 `async` 注解与 `curFunAsync`；实测 `@impure fun caller() { delay(1) }` 照报 E-NEED-ASYNC）。反之 `@async` 也不蕴含 `@impure`。
- **脚本模式**：`.subs` 顶层等价一个隐式非纯入口（`topPure = !scriptMode`，Checker.kt:209），顶层可直接调用非纯函数；注意 `check` 命令按工程模式解析（顶层只能写声明），脚本模式行为只在 `run-script` / LSP 下出现。
- **全局推导**：`run-script --global-inference` 下停机检查失败的函数自动降级标 `@impure` 并发补充级 **D-AUTO-IMPURE**（Interpreter GlobalInference.kt）。
- **编译期性**：`@impure` 只存在于检查器；解释器与 JS 生成不读它，不产生运行时拦截。

### 诊断码速查

| 检查位 | 义务 | 诊断 |
|---|---|---|
| 声明期 | 参数含 `@mut T` 须标 `@impure` | E-IMPURE-REQUIRED |
| 纯作用域·具名调用 | 非纯函数调用须：调用方非纯 / unchecked / Γ⊢pure<f> | E-IMPURE-CALL |
| 纯作用域·函数值 | impure 函数值调用须：非纯 / unchecked / 借用形参 | E-IMPURE-CALL（文案带函数类型） |
| 纯函数·自递归 | 至少一个实参递减（或标 `@impure`） | E-NON-STRUCTURAL-REC |
| 异步 | 调 `@async` 函数须 @async 上下文（@impure 不能豁免） | E-NEED-ASYNC |
| 全局推导 | 停机失败降级 @impure | D-AUTO-IMPURE（补充级） |
| 安卓桥 | `@android` 函数建议标 `@impure` | H-ANDROID-BUILTIN（提示） |

## 示例

声明与三路放行（实测 `check` 通过；镜像 examples/特性巡礼.subl :100 一带）：

```subl
@impure fun side(): Null {}                // 声明：本函数有副作用

fun pureCaller()<pure<side>>: Null {       // 把放行义务写进上文 → 转嫁给调用方
    side()
}

fun run(f: @impure () => Null): Null {     // 借用效应：@impure 形参即效应能力
    f()
}

@impure
fun main(): Null {
    axiom pure<side>                       // 供给 1：axiom 命题
    side()                                 // 放行 1：调用方自身非纯
    pureCaller()                           // 放行 2：上文由上一条 axiom 供给
    run({ => side() })                     // 放行 3：lambda 喂给借用形参
    by [pure<side>]                        // 供给 2：by 直接供给
    side()
    unchecked print("done")                // 供给 3 的反面：unchecked 一次性豁免
}
```

纯作用域内的拒绝形态（实测诊断）：

```subl
@impure fun side(): Null {}
fun main(): Null {
    side()                  // E-IMPURE-CALL: 纯作用域内调用非纯函数 side（用 unchecked 前缀，或在上下文供给 pure<side>）
}
```

lambda 纯度推导（实测 `run-script` 输出 `0` / `2`）：

```subl
@impure fun side(): Nat { return 0 }
var f = { => side() }       // 推导：f : impure () => Nat
var g = { 1 + 1 }           // 体纯：g : (…) => Nat，且 pure<{1 + 1}> 入 Γ
print(toStr(f()))           // 脚本顶层非纯，调用 impure 函数值无碍
print(toStr(g()))
```

函数值闸与借用形参的对照（实测诊断）：

```subl
@impure fun side(): Nat { return 0 }
fun main(): Null {
    var f = { => side() }   // 体内即报 E-IMPURE-CALL（lambda 继承调用方的纯度）
    f()                     // E-IMPURE-CALL: 纯作用域内调用非纯函数值 impure ()=>Nat
}
```

脚本模式的顶层非纯（实测 `run-script` 输出 `ok`）：

```subs
@impure fun side(): Null {}
side()                      // 顶层即隐式非纯入口，免 unchecked
print("ok")
```

强制标注与停机逃逸：

```subl
@impure fun take(m: @mut Nat): Nat { return 0 }   // 有 @mut 参数：@impure 是强制项
@impure fun loop(n: Nat): Nat { return loop(n) }  // 停机闸对 @impure 失效（放弃停机检查的代价）
```

## 陷阱

- **`@impure` 不是 `unchecked`**：`unchecked` 只豁免它包住的那一条语句，不改变函数身份；实测 `unchecked var f = { => side() }` 之后，`f()` 仍报 E-IMPURE-CALL。反过来，`@impure` 不豁免任何检查——它只是声明，纯函数调用它仍报错，须自己声明、`unchecked` 或供给 `pure<f>`。
- **局部函数不能标 `@impure`**：函数体内 `@impure fun local(…)` 直接语法错（parseStmt 不通）；要写非纯局部函数只能靠外层函数标 `@impure` 或 `unchecked` 包裹。局部函数体目前也不进声明体检查通道（实测类型错漏检），别把效应审查寄托在它身上（待核：后续版本行为）。
- **`pure<f>` 是欠债，不是免单**：把 `<pure<f>>` 写进上文只是把证明义务转嫁给调用方——实测调用方不供给即报 E-PRE-UNPROVEN。三层逃逸（`axiom` / `by` / 弥散来的事实）都得在调用方作用域里真正成立。
- **`@mut` 参数强制 `@impure`，且只此一家**：E-IMPURE-REQUIRED 只认 `MutType` 参数；`@ref` 不能写在类型位（类型位只有 @mut/@atomic/@impure），可变形参语义一律走 `@mut T`。
- **impure 结果不进等式推理**：`var x = one()` 不注入 `x = one()`，`∵ x = 1` 之类的目标对 impure 源证不出；但作者**声明的下文** `<$ = 1>` 照常弥散（一个靠自动事实，一个靠声明信任，两条通道别混）。
- **同名异模块保守拦截**：某名字在任一可见模块有 `@impure` 定义，则跨模块的 `pure<f>` 整名不注册——裸名调用一律按非纯拦下，编译器不猜你调的是哪一个。
- **`pure<λ>` 按字面结构匹配**：`fun need()<pure<{1 + 1}>>` 只被**同字面**的 lambda 满足；实测 `{ => 1 + 1 }`（零参）与 `{ 1 + 1 }`（隐式单参）注入的命题互不匹配——换 lambda 形态就证不出。
- **具名 impure 函数作值传递不带走纯度**：实测 `apply(side)`（`apply(f: () => Nat)` 为纯函数）通过——只有 lambda 字面量被推导纯度，具名函数的 `@impure` 不随值流动。把 impure 具名函数喂给纯高阶函数是当前的审查缺口，自己心里要有数（实测行为）。
- **`@impure` 豁免不了别的闸**：E-NEED-ASYNC 照报（`@async` 与 `@impure` 正交）；E-PRE-UNPROVEN 等证明义务归命题层，与效应声明无关。
- **停机闸的「`@impure` 逃逸」是放弃停机检查**：E-NON-STRUCTURAL-REC 建议加 `@impure`，代价是编译器不再检查该函数终止——函数可能真不停；脚本全局推导的 D-AUTO-IMPURE 是同一降级的自动版。
- **`check` 与 `run-script` 的纯度口径不同**：`check` 一律按工程模式（顶层只能写声明，`fun main` 体默认纯）；脚本顶层「免 unchecked」只在 `run-script` / LSP 下成立。跨平台脚本别把顶层副作用习惯带进工程文件。
```
