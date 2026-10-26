卷名：05 · 工程 —— CLI（sublimationc 命令行）
主题域：用户视角的编译器命令行——六类子命令（parse / tokens / check / compile / run-script / explain）与全局旗标（-p / -A / -v / -o / --prover=aggressive 系列 / --global-inference）、目录即模块的传参语义、退出码与输出流
事实来源：_facts/_langfacts.md §8；cli/src/main/kotlin/sugared/functor/Main.kt（USAGE:18–40、调度 main():178–370、runScriptCmd():374–413、EXPLAIN 表:69–176、激进旗标解析:43–65）；annotationproc/.../BuiltinProcessors.kt（DEFAULT_DIR）；并经 build/libs/sublimation.jar（2026-10-25 构建，OpenJDK 17）实测，实测输出见「示例」节

# 05 · 工程 —— CLI（sublimationc 命令行）

> **一句话**：一个 jar、六个子命令、一把旗标——`sublimationc` 把词法、语法、语义、证明、JS 生成、脚本执行和诊断查询全部暴露在命令行上；传文件还是传目录，决定了编译器把参数当作「单文件」还是「整棵模块树」。

USAGE（Main.kt:18–40）是命令行界面的完整契约，全表即事实。本文所有实测均在仓库根目录以 `java -jar build/libs/sublimation.jar …` 完成；`run` 这个名字在 USAGE 中并不存在（见「陷阱」-1）。

## 语法

### 调用总格式

```
java -jar sublimation.jar <子命令> <文件|目录> [旗标…]
java -jar sublimation.jar explain <诊断码>
```

- 无参调用直接打印 USAGE 到 stderr 并以退出码 2 结束（实测）。
- 旗标是「全局位置参数」：出现在子命令之后的任意位置都生效（解析器对 args 全量扫描，Main.kt:194–230）；没有 GNU 风格的 `--` 分隔约定。
- 路径参数与旗标靠「是否以 `-` 开头」区分（Main.kt:239），因此文件名本身不能以 `-` 开头。

### 子命令表（USAGE 原文为准）

| 子命令 | 参数 | 作用 |
|---|---|---|
| `parse` | `<file.subl>` | 词法 + 语法分析，打印语法树 |
| `tokens` | `<file.subl>` | 打印 token 流（含邻接标记 space=） |
| `check` | `<file.subl \| dir>` | 语义分析（弥散 / 证明义务 / 纯度 / 可变性 / impl / when 穷尽），诊断分级输出 |
| `compile` | `<file.subl \| dir> [-o out.js] [-p …]` | 语义通过后生成 JS，默认打印到标准输出 |
| `run-script` | `<file.subs \| dir> [<file2.subs> …]` | 脚本模式解释执行：顶层语句直接运行（无需 main）；多条脚本互不共享 |
| `explain` | `<CODE>` | 打印诊断码的含义与修复建议，不需要源文件 |

`check` / `compile` 接受目录（目录即模块，编译入口为根 `/`，扫全树）；`parse` / `tokens` 只吃单文件，给目录会被拒（实测见示例 7）。

### 全局旗标表

| 旗标 | 形态 | 作用 |
|---|---|---|
| `-p <path>` | 文件或目录，可多次 | 附加注解处理器；`.subs` 文件=处理器（文件主名=处理器名=关注的注解），目录装载其全部 `.subs` |
| `-A key=value` | 可多次 | 传给注解处理器的编译参数；安卓 APT 用它收工具路径。`-A key`（无 =）也接受，值为空串 |
| `-v` | 布尔 | check 同时输出补充级诊断（类型推导结果等）；run-script 会把它过滤丢弃 |
| `-o out.js` | 仅 compile | JS 写入文件（父目录自动创建）；省略则打印到标准输出 |
| `--global-inference` | 仅 run-script | 开全局类型推导（显式标注 > 构造点 > 使用点 > 默认 Any）；编译模式不受影响 |
| `--target=node\|browser\|wasm` | compile/check | 编译目标（默认 node 向后兼容）。node=自执行脚本；browser/wasm=S-1 装载模型——产物导出 `globalThis.__subl.main`（宿主 eval 后调用、不自执行），平台调用走宿主桥单入口 `globalThis.__subl.d(op, args)`；wasm 无同步宿主通道，`readFile` 等同步 IO 与 core/io 模块编译期拒绝（E-PLATFORM-UNAVAILABLE）。-A/SfA 安卓路线默认 node，不受影响 |
| `--prove-verbose` | 激进模式 | 提高进度输出频率 |
| `--prove-dump=<路径>` | 激进模式 | 诊断报告路径，默认 `sublimation-prover-dump.md`，仅中断时写（USAGE；未实测——待核） |
| `--prove-interval=<2s/500ms>` | 激进模式 | 进度节流间隔，默认 5 秒；源码 parseIntervalMs 另接受 `m` 后缀与裸数字 |

同义长旗标（源码 Main.kt:207/222，USAGE 未列）：`-p` = `--processor`，`-A` = `--processor-arg`。

## 语义

### 各子命令做什么

- **parse**：只跑词法 + 语法，打印缩进风格语法树。成功即退出，不做语义检查，也不跑注解处理器。
- **tokens**：打印 token 流，每行 `<类型>\t<文本>\t<行>:<列>\tspace=<前是否空格>`。邻接标记（precededBySpace）是中缀糖 `a f b` 判定的依据，所以 tokens 不只是调试视图。
- **check**：parse + 注解处理器改写 + 全套语义检查。报告按严重级输出（[错误]/[警告]/[提示]/[补充]），带源码片段上下文；`-v` 决定要不要放出补充级（D- 族）。有错误则拒绝通过。
- **compile**：check 全通过后才生成 JS；单文件诊断输出到 **stderr**、JS 输出到 **stdout**（与 check 分流不同，见「陷阱」-6）。生成后还有处理器「编译后阶段」（runPostCompile）：其诊断只报告不阻断。
- **run-script**：脚本模式，Checker 类型检查 + 解释器直接求值。多个参数逐一执行，每个脚本独立（互不共享名字）；目录参数先找 `.subs`（每个独立跑），一个都没有就退化为 .subl 工程模式（`fun main()` 入口）。
- **explain**：纯查表，不读源文件；入参经 `uppercase()` 归一化（小写也能查）。未收录的码列出现有全部码并退出 2。

### 目录即模块的传参语义

- `check <dir>` / `compile <dir>`：目录 = 模块，以根 `/` 为编译入口扫描全树（MultiModule）。实测 `check examples/模块系统`（含子目录 tool/）输出 `[完成] 模块检查通过`。
- 非 check/compile 的子命令收到目录参数会被拒绝，错误信息明确指向替代方案：`[错误] 目录模式仅支持 check / compile（脚本目录请用 run-script）`（实测）。
- `run-script <dir>` 的语义相反：它按 `.subs` 聚合，只有「无 .subs」时才按工程（main）执行——两个方向不要混。
- 工程模式的一次「跑通」推荐路径是 `compile <dir> -o out.js && node out.js`（examples/模块系统/主.subl 文件头注释自述）；`run-script <dir>` 的工程回退在跨模块限定调用上会失败（见「陷阱」-2）。

### -p 与 -A 的语义

- 处理器流水线**先于**语义检查跑：变换后的 AST 才是检查对象（Main.kt:250–254、295–297），所以 `@allMut` 引入的 `@mut` 对 checker 可见。
- 编译器默认还从**当前工作目录**下的 `annotation processer/` 装载内建处理器（BuiltinProcessors.DEFAULT_DIR）；`-p` 是「追加」而非「替代」。目录不存在时静默跳过（空列表、不报错）——这埋了 cwd 依赖的坑（「陷阱」-4）。
- `-A` 的键值在 `=` 处切分，无 `=` 时 value 记为空串；键可重复给出（LinkedHashMap 保留给出序）。这些值经 ProcessorPipeline 传给处理器脚本与安卓 APT。
- 处理器脚本在编译时由解释器解释执行，可通过 `hint()/diag()/processorError()/processorWarn()` 回吐诊断（对应 H-PROCESSOR-SCRIPT / D-PROCESSOR-SCRIPT / E-PROCESSOR-SCRIPT / W-PROCESSOR-SCRIPT）。

### -v 的语义

`-v` 只控制诊断报告的 `includeSupplement`：打开后补充级（D- 族）一并输出，如 `D-TYPE-INFERRED: var x 推导为 Nat`、`D-NAT-PROP`。它不影响检查的严格程度，也不影响 compile 是否生成 JS。

### 激进模式系列

`--prover=aggressive` 只作用于 compile/check（对 run-script/parse/tokens 无效）。语义是「更彻底的搜索（可能很慢甚至不停机），出错前先尽力证出；健全性底线不变——绝不猜测」。Ctrl-C 中断时置中断位，搜索循环在下一个检查点抛 ProverInterrupted，main 捕获后写诊断报告并以 130 退出（Main.kt:359–365；此路径未实测——待核）。

### 退出码与输出流（实测）

| 退出码 | 含义 | 实测触发 |
|---|---|---|
| 0 | 成功 | 全部正常命令 |
| 1 | 语义/运行失败 | check/compile 诊断有错误；run-script 工程回退类型检查失败；处理器装载失败（目录模式） |
| 2 | CLI 用法错误 | 无参；未知子命令（含 `run`）；文件/处理器路径不存在；目录模式子命令非法；explain 未收录码 |
| 130 | 激进证明被 Ctrl-C 中断 | 仅源码（Main.kt:365）——待核 |

`check` 的报告走 stdout；单文件 `compile` 的诊断走 stderr、JS 走 stdout（实测见示例 5/6）。

## 示例

以下均为实测输出（仓库根目录、OpenJDK 17、jar 构建于 2026-10-25）。示例源文件：

```sublimation
// demo.subl
axiom 非负: ∀n. n ≥ 0

@impure
fun greet(name: Str): Str {
  return "hi " + name
}

fun double(x: Nat): Nat = x + x
```

**1) tokens**（token 流含邻接标记；截前 12 行）：

```
$ java -jar build/libs/sublimation.jar tokens demo.subl
IDENT	axiom	1:1	space=false
IDENT	非负	1:7	space=true
COLON	:	1:9	space=false
FORALL	∀	1:11	space=true
IDENT	n	1:12	space=false
DOT	.	1:13	space=false
IDENT	n	1:15	space=true
SYMBOL	≥	1:17	space=true
INT	0	1:19	space=true
AT	@	3:1	space=true
IDENT	impure	3:2	space=false
FUN	fun	4:1	space=true
```

**2) parse**（语法树）：

```
File
  stmt
    axiom[(: 非负 ∀ n . (≥ n 0))]
  fun greet(name: Str): Str @impure
    block
      return
        bin +
          str "hi "
          name name
  fun double(x: Nat): Nat
    bin +
      name x
      name x
```

**3) check 出错 + 源码片段上下文**（bad.subl 里 `var s: Str = 42`）：

```
$ java -jar build/libs/sublimation.jar check bad.subl
[错误][2:3] E-TYPE-MISMATCH: var s 标注了 Str，但初值是 Nat，两边类型不一致
  2 |   var s: Str = 42
  |   ^
[错误] 语义检查未通过        ← stderr，退出码 1
```

**4) check -v 放出补充级**（infer.subl：`var x = 1`）：

```
$ java -jar build/libs/sublimation.jar check infer.subl -v
[补充][3:5] D-NAT-PROP: 注入自然命题：`x ≥ 0`（Nat 变量自动成立）
  3 |     var x = 1
  |   ^
[补充][3:5] D-TYPE-INFERRED: var x 推导为 Nat
  3 |     var x = 1
  |   ^
[完成] 语义检查通过
```

**5) compile 单文件**：JS 进 stdout；若有错误则「拒绝生成 JS」且诊断进 stderr（实测同 bad.subl，退出码 1）。

**6) compile 目录 + -o**：

```
$ java -jar build/libs/sublimation.jar compile examples/模块系统 -o out.js
[完成] 已生成 out.js（1143 字节）
```

**7) 目录模式越界被拒**：

```
$ java -jar build/libs/sublimation.jar tokens examples/模块系统
[错误] 目录模式仅支持 check / compile（脚本目录请用 run-script）：tokens      ← 退出码 2
```

**8) run-script 三种参数形态**：

```
$ java -jar build/libs/sublimation.jar run-script hello.subs
[脚本] hello.subs
hi
9                      ← 注意 1 + 2 * 3 左结合得 9（无优先级）
[完成] 脚本执行结束

$ java -jar build/libs/sublimation.jar run-script playground
[脚本] 字符串与数学.subs
拆分=[a,b,c]
sqrt(16)=4
[脚本] 形状.subs
…（目录下每个 .subs 独立跑，互不共享）

$ java -jar build/libs/sublimation.jar run-script proj/      # 目录里只有 main.subl
[工程] proj
42
[完成] 脚本执行结束
```

**9) explain**（大小写不敏感；未收录码列全表）：

```
$ java -jar build/libs/sublimation.jar explain w-prop-unbound
W-PROP-UNBOUND — 分类讨论的析取前提不在上下文中。—— when[p] 语句要求先注入或证出析取式
（如 p ∨ q），否则分类讨论无据。用 axiom p ∨ q 或前置证明注入。

$ java -jar build/libs/sublimation.jar explain E-NOPE
[错误] 未收录诊断码 E-NOPE（现有：D-AUTO-AWAIT / D-AUTO-IMPURE / …）      ← 退出码 2
```

**10) 激进模式**（小文件上即刻返回）：

```
$ java -jar build/libs/sublimation.jar check proof.subl --prover=aggressive --prove-verbose --prove-interval=1s --prove-dump=dump.md
[警告][5:3] W-PROP-UNBOUND: 分类讨论的析取前提 (p | q) 不在 Γ 中
[警告][5:3] W-WHEN-NO-COMMON: 分类讨论各分支没有共同的结论
[完成] 语义检查通过
```

**11) -p 与 -A**：`check demo/allmut/main.subl -p "annotation processer/allMut.subs" -A sdkPath=/opt/sdk -A debug` —— 处理器与 -A 全部被接受，check 通过、compile 照常产 JS（实测）。

## 陷阱

1. **`run` 不是子命令。** USAGE 里没有它；实测 `run demo.subl` 直接回退到 USAGE、退出码 2。「跑工程」的两条正路是 `run-script <dir>`（无 .subs 时按 main 执行）与 `compile -o out.js && node out.js`。
2. **`run-script` 的工程回退不全等同于编译模式。** 实测 `run-script examples/模块系统` 落入工程回退后报 `[错误] 脚本类型检查失败: [E-UNBOUND-NAME] 没找到 tool`（退出码 1）——解释器的工程回退解不了跨模块限定调用 `tool.triple(4)`；该示例文件头注释自己推荐 compile + node。
3. **`run-script` 不按扩展名挑文件。** 给它一个 `.subl` 文件也按脚本跑（实测 `run-script examples/空安全.subl` 输出 `[脚本] 空安全.subl` 后空转结束——顶层全是声明，无语句可执行）。要脚本就老实用 `.subs`。
4. **内建处理器依赖当前工作目录。** 内建处理器默认目录是相对路径 `annotation processer/`；从别的 cwd 调用同一个 jar 时该目录找不到即静默放弃。实测在 `C:/Temp` 下 `check demo/allmut/main.subl` 因 `@allMut` 未生效而报 `E-NOT-MUT: 字段 x 不可变`（退出码 1）。对策：从仓库根调用，或显式 `-p` 指到处理器目录。
5. **`-p` 路径不存在是硬错误（退出码 2）**，且检查前就失败；别指望它「静默忽略」。`-A` 的键解析很宽松（`-A debug` = 值空串），拼错键名不会报错，只在处理器侧才暴露。
6. **输出流不统一。** check 的报告在 stdout，单文件 compile 的诊断在 stderr（JS 在 stdout）——在 CI 里分离采集日志时别接错管子。目录模式的 check/compile 报告则在 stdout。
7. **`--prover=aggressive` 可能不停机。** USAGE 自述「可能很慢甚至不停机」；Ctrl-C 可中断并写 dump（退出码 130——待核）。它只作用于 compile/check，且没有中间档。小文件上它可能毫无存在感（示例 10 即刻返回），判断「是否生效」要看诊断而非耗时。
8. **`--global-inference` 只对 run-script 有效**（USAGE 明示「编译模式不受影响」）；同理 `-v` 在 run-script 下被过滤丢弃。旗标张冠李戴不会报错，只会无声无效。
9. **退出码 2 ≠ 语义错误。** 2 全是 CLI 层面的用法错误（无参、未知命令、路径不存在、目录模式越界、explain 未收录），1 才是「编译器看懂了你的命令但没通过」。脚本里按 1 判失败、按 2 判命令行拼错。
10. **`parse` / `tokens` 不跑任何语义检查、也不跑处理器。** 它们通过不代表 check 通过；反过来 check 失败时用它们定位是词法/语法层还是语义层的问题。