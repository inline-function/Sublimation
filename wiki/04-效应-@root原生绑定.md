# @root 原生绑定：无体内建声明机制

> wiki/04-效应-@root原生绑定 · 主题域：效应与内建声明（_langfacts §6/§7）
> 事实来源：_langfacts.md §6/§7；compiler/.../functor/checker/Types.kt（builtinimpure :50、builtinPure :145、ROOT_WHITELIST :277、findFun :379、CHANNEL/Mutex/FLOW_BUILTINS :300）、Symbols.kt（registerPureFacts :16、无体守卫 :246–268）、Checker.kt（checkFun 仅体存在时调用 :217）、CallCheck.kt（checkFnCall :690、E-IMPURE-CALL :755、E-NEED-ASYNC :769）、codegen/JsCodeGen.kt（genFun 体判 :704、userFns :218、awaited :1025、按名特判 call() :1126 起）；interpreter/.../Interpreter.kt（@root 条目过滤 :246、宿主注册 registerStdlib :287）、Evaluator.kt（callFunction 查找顺序 :507、invokeFunDecl 空体 :594）；行为经 build/libs/sublimation.jar 实测探针验证（check / compile -o / run-script，临时探针置于 _facts_tmp，验后已删除）

`@root` 是**函数声明位**的注解，作用只有一个：声明「这个函数签名对应编译器内建实现，源码不必写体」。它把一个白名单内的名字与编译器两套内建表（`builtinimpure`/`builtinPure`，Types.kt）对接：检查器按用户写出的签名做调用方核对，代码生成器按名字把调用点特判翻译为原生 JS（不生成函数定义），解释器则按脚本模式宿主函数表独立对齐。它与 `@impure` 的关系容易误解：`@root` 只声明**实现来源**，不声明效应——纯度等语义属性由内建表决定，而调用点效应闸门读的又是用户声明上的注解，两边并不同源（见语义节）。

## 语法

```subl
@root fun timeNow(): Nat                       // 无体：没有 = expr，也没有 { … }
@impure @root fun print(v: Any): Null          // 与 @impure 同行叠加
@impure @async
@root fun readFile(p: Str): Result[Str, Str]   // 也可分行连写（标准库形态）
@root fun toStr[T](n: T): Str                  // 允许类型参数（泛型内建）
```

注解序列由 parseAnnotations 吃掉连续的 `@ 名`（Parser.kt:261），**没有参数、没有括号**；`@root` 只是名字为 `root` 的普通注解，其含义全部由检查器与代码生成器事后赋予。顶层 parseEntry（Parser.kt:194）只允许注解后跟 `fun`/`struct`/`class`：

| 位置 | 结果 |
|---|---|
| `@root fun …` | 唯一与 @root 机制相关的形态 |
| `@root struct` / `@root class` | 语法放行，但 @root 语义不认（struct/class 注解由注解处理器解释，如 `@view`） |
| `@root enum` / `@root impl` / `@root type` | 直接语法错（实测报「期望顶层只能写声明……实际 '@'」） |
| 函数体内 | parseStmt 的 `@` 分支只通向 `var` 与策略调用，`@root` 走不到 |

**无体形态**是 @root 的存在理由：`E-FUN-NO-BODY` 对普通函数报错「函数 f 没有函数体。普通函数必须写出实现；内建函数请标注 @root（编译器对白名单开放）」，而 `@root`（以及 `@host`、`@android`、无体定理函数）豁免该错误。反过来，**写了函数体的 @root 不报任何错**——注解悄悄退化为无效标记（见陷阱 2）。

四种「无体声明机制」对比（@root 的同构兄弟们）：

| 机制 | 有效场景 | 白名单 | 纯度归属 | 实现来源 |
|---|---|---|---|---|
| `@root` | 工程与脚本通用 | ROOT_WHITELIST（66 名） | 内建表覆盖用户注解 | codegen 按名 JS 特判 |
| `@host` | 脚本模式 | 无门（任意名） | 宿主注册时的签名 | 宿主函数表（Evaluator 回调） |
| `@android` | 注解处理器场景 | ANDROID_WHITELIST | 用户注解（仅 @android 触发接管） | JsCodeGen.androidCall 桥接 |
| 无体定理函数 | 通用 | 无门 | — | 命题层（运行时擦除为 null） |

**白名单**（ROOT_WHITELIST，Types.kt:277）共 66 个名字，恰为内建两表名字再扣除 `panic` 与 `in`（表头注释声称「名字全集 = builtinimpure + builtinPure 的键」，与实际表不符——`panic`/`in` 在 builtinPure 中登记却不可 @root，实测 `@root fun panic(): Nothing` 报 E-ROOT-NOT-ALLOWED）：

| 类别 | 名字 |
|---|---|
| 副作用入口（表标 @impure） | `print` `readLine` `readFile` `writeFile` `getArgs` `sleep` `appendFile` `fileExists` `readDir` `timeNow` `rand` |
| core.async 运行时层（@impure @async） | `delay` `runBoth` `runSeq` `collectToList` `collectCount` `collectAll` `withMutex` `bumpCounter` `bumpPair` `channelFromList` `sendAll` `channelDrain` `channelPipe` |
| 纯内建：元组 | `emptyTuple` `singleTuple` |
| 纯内建：字符串 | `concat` `length` `charAt` `substring` `strCmp` `toStr` `parseNat` `strUpper` `strLower` `strTrim` `startsWith` `endsWith` `strReplace` `strSplit` `strIndexOf` |
| 纯内建：数值 / Math | `toRat` `toReal` `parseRat` `abs` `floor` `ceil` `pow` `mathMin` `mathMax` `mathClamp` `mathRound` `mathSin` `mathCos` `mathTan` |
| 纯内建：JSON | `jsonParse` `jsonGet` `jsonAt` `jsonLen` `jsonStr` `jsonNum` `jsonBool` `jsonIsNull` `jsonIsArr` `jsonToStr` `jsonToList` |

同模块内同名重复声明（首参形态相同时）照常 **E-DUP-DECL**（实测两个 `@root fun timeNow(): Nat` 报重复；零参函数首参 render 相同，故无重载豁免）。

## 语义

### 无体豁免的守卫链

无体函数的检查在 checkDeclTypes（Symbols.kt:247 起）按顺序分流，先命中先生效：

$$\frac{\texttt{body}(f)=\bot \qquad f \text{ 是定理函数}}{\text{放行（签名即公理）}} \ (\text{theorem}) \qquad \frac{\texttt{body}(f)=\bot \qquad \texttt{host} \in ann(f)}{\text{放行（宿主注入体）}} \ (\text{host})$$

$$\frac{\texttt{body}(f)=\bot \qquad \texttt{root} \notin ann(f) \qquad \texttt{android} \notin ann(f)}{\textbf{E-FUN-NO-BODY}} \ (\text{无体}) \qquad \frac{\texttt{root} \in ann(f) \qquad f.\texttt{name} \notin \text{ROOT_WHITELIST}}{\textbf{E-ROOT-NOT-ALLOWED}} \ (\text{白名单门})$$

全过则发提示 **H-ROOT-BUILTIN**：「@root 函数 f 由编译器内建 JS 实现，调用点直接翻译为原生 JS（不生成函数定义）」。注意 @android 分支在 @root 之前（同一 `body == null` 分支内的 when 顺序），`@android @root` 同时存在时按安卓白名单与提示处理。

### 纯度归属：内建表覆盖用户注解

注册 `pure<f>` 命题时（registerPureFacts，Symbols.kt:16–29），若名字在内建两表中，**用表的注解、丢弃用户注解**：

$$\frac{\texttt{name} \in \text{dom}(\texttt{builtinimpure} \cup \texttt{builtinPure})}{\texttt{pure<name>} \text{ 的注册与否以内建表为准，用户 } ann(f) \text{ 不参与}} \ (\text{R-PURITY})$$

动机见源码注释：否则 `@root fun readFile(...)`（用户忘写 @impure）会被注册 `pure<readFile>`，给非纯 IO 函数开出错误纯度证明（错误的 `:∵` 证明链由此而来）。实测：`@impure @root fun length(s: Str): Nat` 照常注册 `pure<length>`（用户 @impure 被表覆盖），纯函数中调用 `length` 不报 E-IMPURE-CALL。跨模块纯度补全（Symbols.kt:33 起）走同一覆盖逻辑。

### 效应闸：读的却是用户声明（不对称）

调用检查（checkFnCall，CallCheck.kt:690）拿到的 `fn` 是 findCallable 的解析结果，而 `findFun`（Types.kt:379）先查本模块符号表、再回退内建表——**用户写的 @root 声明把内建表顶掉了**。于是 E-IMPURE-CALL（CallCheck.kt:755）与 E-NEED-ASYNC（:769）判读的都是**用户声明上的注解**：

$$\frac{\texttt{root} \in ann(f) \qquad \texttt{impure} \notin ann(f)}{\Gamma \vdash \text{纯作用域调用 } f \text{ 不触发 E-IMPURE-CALL}} \ (\text{R-GATE-USER}) \qquad \frac{\texttt{async} \notin ann(f)}{\text{调用点不登记挂起点，不插 await}} \ (\text{R-ASYNC-USER})$$

即：白名单内的非纯内建，若用户声明漏写 `@impure`，纯函数里也能调用（实测 `@root fun delay(ms: Nat): Null` 在纯 `main` 中通过检查）。注册侧用表、闸门侧用声明，两处不同源——这是 @root 机制最大的语义不对称，也是多数陷阱的根源。`pure<f>` 供给放行（`axiom pure<f>` 或 `by [pure<f>]`）在 @root 声明上同样有效：因为闸门读用户声明，若用户未写 @impure，根本无需供给。

### 签名与体内检查

- **签名归用户声明**：调用点的 arity、参数类型、默认值、返回类型都按用户写的 @root 声明核对（内建表签名只在用户未声明时作回退，且点号方法路径 `s.length` 按 builtinPure 首参匹配，Checker.kt:983）。实测 `@root fun print(): Null` 后调用 `print("hi")` 报 E-ARITY（想要 0~0 个实参）。
- **体内检查一律跳过**：checkFun 只在 `body != null` 时调用（Checker.kt:217），所以 E-IMPURE-REQUIRED（@mut 形参须 @impure）、返回路径、停机递归（E-NON-STRUCTURAL-REC）等**体内规则对无体 @root 声明全部不生效**——实测 `@root fun bumpCounter[T](m: Mutex, c: @mut T): Null` 不带 @impure 也通过（对比：core/async/mutex.subl 的真声明带 `@impure @async`）。

### 代码生成

genFun 第一句 `val body = fn.body ?: return`（JsCodeGen.kt:704）：无体 @root 声明**不生成任何 JS 函数定义**。调用点在 `call()` 里按名字特判直译（JsCodeGen.kt:1126 起），且**只认名字**——不区分调用方解析到的是内建表还是用户声明（仅安卓内建有 `name !in userFns` 保护，@root 名字没有这层保护）：

| 名字 | 生成 JS |
|---|---|
| `print` | `console.log(__dshtoStr(实参))` |
| `timeNow` | `Date.now()` |
| `rand` | `Math.random()` |
| `readFile(p)` | `await (() => { try { return Ok(require('fs')...) } catch … })()` |
| `delay(ms)` | `await new Promise(r => setTimeout(r, ms))`（await 由 awaited() 按登记补包，JsCodeGen.kt:1025） |
| `channelFromList(xs)` | 含 `await` 的 IIFE，内置 send/close 序列 |
| `length(s)` | `(s).length` |

core.async 一族另有**运行时置位**：checkFnCall 在调用点把名字归入 CHANNEL_BUILTINS / MUTEX_BUILTINS / FLOW_BUILTINS，codegen 据此注入 Channel/Mutex/`__flow` 小运行时 class（CallCheck.kt:694–700）——这一层与 @root 白名单同源（Types.kt:300 注释：「用户自定义同名函数不参与特判」），但**置位只看名字、不看注解**。

### 解释器宿主对齐（脚本模式）

脚本模式的生效路径与编译模式**完全独立**，分三股（Interpreter.kt）：

1. **过滤**：加载标准库时把 @root 条目整体剔除（loadStdlibEntries，:246）——脚本模式下内建改由宿主函数表 + checker 内建表提供，留着会与注入的 @host 声明撞 E-DUP-DECL；
2. **宿主表**：registerStdlib（:287）登记 `print`、`toStr` 与字符串、转换、Math 一族的**宿主实现**（concat/length/charAt/substring/strCmp/strUpper/strLower/strTrim/startsWith/endsWith/strReplace/strSplit/strIndexOf/parseNat/parseRat/toRat/abs/floor/ceil/pow/mathMin/mathMax/mathRound/apply）。纯内建的其余成员（emptyTuple/singleTuple/mathClamp/mathSin/mathCos/mathTan/toReal/json 全族）与全部非纯内建（IO/时间/随机/异步）在解释器侧**没有宿主实现**（事实表 §7 记「print/printable 输出」，但全库检索 `printable` 仅见于事实表自身、实现里只有 `print`——待核）；
3. **求值**：callFunction（Evaluator.kt:507）按 `in`/`listOf`/`setOf` 特判 → **宿主表** → 帧内声明 → 类型类字典 的顺序查找；命中用户的无体 @root 声明时，invokeFunDecl 对 `body == null` **静默返回 Null**（Evaluator.kt:594；函数值路径 funDeclAsValue 则对 @host 抛「宿主函数未注册实现」，两条路径行为不同）。

于是脚本模式下 @root 声明只是「声明文档」：名字与宿主表重名（print/length 等）直接 E-DUP-DECL；宿主未实现的名字（如 timeNow），声明后调用得到 `null`（静默），不声明则运行时报「未定义的函数」（两者均已实测）。脚本模式检查器同样执行白名单门（实测 `@root fun oops(): Nat` 在 .subs 中报 E-ROOT-NOT-ALLOWED）。

## 示例

最简合法声明（与 core/time/time.subl 一致），须在 @impure 上下文调用：

```subl
@root fun timeNow(): Nat

@impure fun main() { print(timeNow()) }
// 生成：async function main() { console.log(__dshtoStr(Date.now())); return null; }
```

非纯内建的完整声明形态——照标准库逐字抄注解（如 core/io/io.subl 的 `@impure @async` + `@root fun readFile(p: Str): Result[Str, Str]`；Result 解构用到 core.collection 的 Ok/Err 构造子，须在挂载 core 的工程里写）：

```subl
@impure @async
@root fun sleep(ms: Nat): Null

@impure @async
fun pause(): Null {
  sleep(500)
  print("done")
}

@impure fun main() { pause() }
```

实测生成 `async function pause() { await new Promise(r => setTimeout(r, 500)); console.log(__dshtoStr("done")); return null; }`——`await` 由「被调者声明了 @async + 调用方 @async」登记后由 codegen 补包（awaited()，JsCodeGen.kt:1025）。

core.async 运行时层的声明与调用（调用即置位对应小运行时；此处用户签名与内建表回退签名相异，本模块内以用户声明为准）：

```subl
@impure @async
@root fun channelPipe[T](src: Channel[T], dst: Channel[T]): Null

@impure @async
fun pipe(src: Channel[Nat], dst: Channel[Nat]): Null {
  channelPipe(src, dst)
}
```

纯内建与脚本模式——无需声明，两表兜底：

```subl
fun greet(who: Str): Str { concat("hi ", who) }   // 工程模式：builtinPure 解析签名
print("hello")                                    // 脚本模式顶层：宿主 print 直接输出
```

## 陷阱

1. **白名单外即报错，哪怕是真内建**：`panic`、`in` 在 builtinPure 中有签名却不在 ROOT_WHITELIST，`@root fun panic(): Nothing` 报 E-ROOT-NOT-ALLOWED。它们由语言层直接特判（`panic()` 表达式、中缀 `in`→in(a,b) 调用），不开放给用户声明。
2. **@root + 函数体 = 死代码**：`@root fun timeNow(): Nat = 7` 检查期不报任何错（无体守卫整体在 `body == null` 分支内），代码生成却仍按名特判——生成的 `function timeNow() { return 7; }` 永不被调用，调用点翻译成 `Date.now()`。@root 对有体函数完全无效（连非白名单名字都不报错）。
3. **漏写 @impure 击穿效应闸**：`@root fun delay(ms: Nat): Null`（缺 @impure）可在纯函数里调用——E-IMPURE-CALL 读用户声明而非内建表（实测通过）。修法只有一条：照标准库把注解抄全；或改用 `unchecked` 语句级豁免（仅一次性、不推荐）。
4. **漏写 @async 丢 await**：`await` 由检查器在判定「被调者 @async 且调用方 async」后登记（asyncAwaitHits），codegen 只补包。@root 声明漏 @async 时调用点直译为 `new Promise(...)` 而无 await（实测在 `main` 中漂浮 Promise、延时失效）。正确形态是 `@impure @async` 连 `@root`（实测生成 `await new Promise(...)`）。
5. **签名自由、后果自负**：checker 按用户声明核对调用（漏参/错型报 E-ARITY/E-TYPE-MISMATCH），但 codegen 按名翻译不看签名——返回值与声明不符时静默放行。`@root fun print(): Null` 声明零参、调用 `print("hi")` 会被 E-ARITY 拦下（算件幸事）。
6. **同名用户函数被编译期「截胡」**：`fun length(s: Str): Nat = 3`（有体、非 @root）能通过检查并生成函数定义，但调用点仍按名特判为 `(s).length`——用户体永不执行。@root 名字空间没有 userFns 保护（那是安卓内建专享的）。
7. **脚本模式 @root 只是声明文档**：与宿主表重名（print/length/toStr…）报 E-DUP-DECL；宿主无实现的（timeNow/rand/IO/异步/JSON 全族）——声明则调用静默返回 `null`（invokeFunDecl 空体路径），不声明则报「未定义的函数」。脚本里用内建的正确姿势是**不声明直接调**（checker 内建表 + 宿主表双兜底）。
8. **用户 @impure 写在纯内建上无效**：`@impure @root fun length(s: Str): Nat` 仍注册 `pure<length>`、纯函数可调用——注册侧内建表覆盖用户，闸门侧又只读用户声明。双向都别指望注解按预期生效，记住「表管注册、声明管闸门」。
9. **体内规则对无体声明不生效**：E-IMPURE-REQUIRED（@mut 形参强 @impure）等只在 checkFun 内，而 checkFun 需要 body。无体 @root 声明可带 @mut 形参而不带 @impure 通过（bumpCounter 形态实测）。
10. **白名单是编译器实现清单，不是能力宣言**：名字进白名单意味着「codegen 有按名特判」（E-ROOT-NOT-ALLOWED 措辞即「这个签名对应编译期内建 JS 实现」）；未落地的能力（如 core/net 的 httpGet）反而**不能用 @root**——它们以 `= panic()` 的有体占位函数存在。工程结论：@root 声明只用于让本模块的调用链类型可见，照 core/ 标准库逐字抄写即可。
