# 公理语句（axiom）

> wiki/03-证明-公理语句axiom · 主题域：axiom 独立公理语句（顶层+函数内）、量词命题、返回 Null、旧 unchecked.axiom 废止
> 事实来源：_facts/_langfacts.md §2/§4；Parser.kt:195-212、631-717、796-803、1340-1370；Checker.kt:204-211、290-295、408-418、655-683、761-767；Diffusion.kt:10-42；Evaluator.kt:185-196；JsCodeGen.kt:819-822；另经 CLI 实测核对（build/libs/sublimation.jar，2026-10-25 构建）

公理语句是证明层的「信任入口」：把命题**不经证明**直接注入当前作用域的事实集合 Γ。修正案之前它写作 `unchecked.axiom[p]`，如今是独立语句 `axiom p1, p2, …`，顶层与函数内通用，语句固定返回 Null。与它对照：`∵…∴q` 是「证明义务」（必须证出），`axiom` 是「直接成立」（无需证出）。

## 语法

```subl
axiom p                              // 单条 0 元原子
axiom p1, p2, valid<7>               // 多条，逗号分隔（尾随逗号仅 }/;/EOF 前允许）
axiom n = 1                          // 等式原子（命题位 = 是等式谓词，不是赋值）
axiom p ∧ (p → q)                    // 逻辑组合：命题蕴含用 →
axiom ∀ n. valid<n>                  // 全称量词；∃、∃! 同理，ASCII forall/exists/exists1 同义
```

**合法位置**

- 语句位：函数体/任意语句块内（parseStmt → parseAxiomStmt，Parser.kt:647、706-717）
- 顶层：工程模式（.subl）顶层唯一被放行的执行语句就是 axiom（parseEntry Parser.kt:208 的特例）；脚本模式（.subs，run-script）则一切顶层语句皆可
- 命题起始白名单（canStartAxiomProp，Parser.kt:701-704）：标识符、符号字面量、整数/浮点/字符串字面量、`(`、`¬`、`!`、`⊤`、`⊥`、一元 `-`、`∀`、`∃`、`∃!`。 `axiom` 后跟这些 token 之一才按公理语句解析，否则退化为普通表达式语句（见陷阱）

**量词命题形式**

```subl
axiom ∀ n. n = n                     // 单变量
axiom ∀ a b. (a + b) = (b + a)       // 多变量（空格分隔；逗号 ∀ a, b. 兼容）
axiom ∃ x. x = 1                     // 存在量词（命题层 QuantExpr）
axiom ∃! y. y = 1                    // 唯一存在
```

注意：语句级的 `∃ x = t . P`（见证引入）是**另一种语句**（ExistIntroStmt）；写在 axiom 里时它是量词**表达式**，注入 Γ 后形如 `∃x. =<x, 1>`（实测 Γ 渲染）。

**文法约束**

- `axiom` 是语句而非子表达式：`var x = axiom p` 之类的写法解析失败（parseAxiomStmt 只从语句位进入）
- 每条命题按完整表达式解析（parseExpr），括号、角表实例化 `valid<7>`、中缀逻辑组合皆可，**无优先级、全左结合**（复杂命题加括号）
- 修正案后文法：程序位（lambda、when 分支 `p => body`、函数类型 `() => Null`）用 `=>`；命题位蕴含只用 Unicode `→`

## 语义

### 注入即成立，免证明义务

- 每条命题经 `PropLogic.fromExpr` 规范化后 `f.inject` 进入**当前帧**的 Γ（Checker.kt:761）；不跑 proveOrDeep、不产生任何 E-CANNOT-PROVE 义务
- 语句类型为 `Null`（Checker.kt:761），解释器求值为 `ScriptValue.Null`（Evaluator.kt:191）
- JS 生成擦除为审计注释 `// axiom: <命题>`（JsCodeGen.kt:820），不产生断言或运行时代码——命题只在编译期存在
- 平凡命题被丢弃：`Frame.inject` 跳过 `PTop`（Diffusion.kt:40），`axiom ⊤` 是空操作

### 作用域（帧语义）

- 函数体从 `Frame(null)` 起步（Checker.kt:295）：**顶层 axiom 的事实不进入任何函数体**。实测：顶层 `axiom p` + 函数内 `∴ p` 报 E-CANNOT-PROVE 且 Γ 为空——函数里要用自己的 axiom
- 函数内：axiom 事实对**后续语句**可见（`∴ q`、调用点上文供给、when 析取前提等都能消费）
- 块弥散：if 值块内的 axiom 事后在外层作用域可见（实测通过）；命题 when 各支用独立帧 `Frame(f)`，只把各支 `∴` 的**公共结论**回注外层（Checker.kt:666-679），支内的 axiom 不外泄
- lambda 帧隔离：lambda 体内的 axiom 不外泄到外层作用域（实测 E-CANNOT-PROVE）

### Γ 的消费通道

注入 Γ 之后，公理事实与自动事实同权参与全部推理：

- **MP 闭包**：`axiom p` + `axiom p → q` ⇒ Γ 饱和产出 `q`，`∴ q` 直通（实测通过）
- **∀ 实例化**：`axiom forall n. valid<n>` 可供给 `need7()<valid<7>>` 的调用点上文（实测通过）
- **算术通道与等式链**：`axiom x = y` 经构造子同余推出 `Some(x) = Some(y)`（仓库测试 自动证明 07）
- **矛盾爆炸**：`axiom 1 = 1` + `axiom ¬<1 = 1>` ⇒ 任意结论可证（仓库测试 自动证明 08）
- **命题参数供给**：`@q` 命题参数的函数可由 0 元原子公理自动供给；候选多于一条时报 E-PROP-AMBIGUOUS（见陷阱）

### 与相邻机制的区别

| 机制 | 语法 | 语义 |
| --- | --- | --- |
| axiom 语句 | `axiom p1, p2` | 命题直接注入 Γ，免证明（本条目） |
| `unchecked <语句>` | `unchecked var x = 1` | 包裹**单条**语句，uncheckedDepth++ 放开可变赋值等闸门（Checker.kt:767）；公理改写后它与 axiom 无关 |
| `@axiom` 注解 | `@axiom fun f(): <…>` | 函数级「签名即公理」：下文由作者声明、编译器信任，跳过 E-POST-UNPROVEN（Checker.kt:408-413） |
| `by [q]` | `expr by [q]` | t30 后：表达式位**断言**（proveOrDeep 核对，证不出报 E-CANNOT-PROVE）；语句式 `by [q]` 已移除（E-BY-REMOVED），免验证供给只留本条目的 axiom |

## 示例

例 1 — 脚本模式顶层（根文档《手动证明》形态，run-script 实测通过）：

```subl
// axiom.subs（脚本模式：顶层语句直接执行，无需 main）
axiom n = 1
∴ n = 1
print("ok")
```

例 2 — 函数内公理兑现自己的下文（仓库测试 上下文弥散声明）：

```subl
fun giveP(): Null <p> { axiom p }          // 声明下文 <p>，体内 axiom p 直接兑现
fun takeP()<p>: Null { }
@impure fun main() { giveP()
takeP() }                                  // 弥散：giveP 的下文供 takeP 消费
```

例 3 — MP 闭包（实测通过）：

```subl
@impure fun main() {
    axiom p
    axiom p → q
    ∴ q
}
```

例 4 — 全称实例化供给调用前件（实测通过）：

```subl
fun need7()<valid<7>>: Null { }
@impure fun main() {
    axiom forall n. valid<n>
    need7()                                // ∀n. valid<n> 实例化 n=7 供给上文 valid<7>
}
```

例 5 — 分类讨论的析取前提与公共结论（构造后实测通过）：

```subl
@impure fun main() {
    axiom p ∨ q                          // 提供 when 的析取前提
    axiom p → r
    axiom q → r
    when[p ∨ q] { p => { ∴ r } q => { ∴ r } }
    ∴ r                                  // 两支公共结论 r 回注 Γ
}
```

注意各分支必须证出**同一个**结论才会回注外层（两支结论不同时报 W-WHEN-NO-COMMON）。

例 6 — 量词命题与多条写法（实测注入 Γ）：

```subl
@impure fun main() {
    axiom ∃ x. x = 1
    axiom ∃! y. y = 1
    axiom ∀ a b. (a + b) = (b + a)
}
// Γ: ∃x. =<x, 1>； ∃!y. =<y, 1>； ∀a,b. =<(+ a b), (+ b a)>
```

例 7 — 编译产物（compile 实测，命题擦除为注释可 grep 审计）：

```js
async function main() {
    // axiom: p
    // axiom: p → q
    return null;
}
```

## 陷阱

**旧写法与文法类**

- ❌ 非法：`unchecked.axiom[p]`——已废止（Parser.kt:797 注释）。实测报通用句法错误 `[语法] 期望表达式，实际'.'`（定位在点号处）；源码 Parser.kt:799 预设的引导文案因校验时游标尚未越过 `unchecked` 而不会触发（代码审读，非实测文案）
- ❌ 非法：`axiom p -> q`——命题位 ASCII 箭头在修正案后全位非法（lexer 出 ARROW 仅供诊断）；命题蕴含只写 `p → q`；程序位的 `=>` 也不是蕴含
- ❌ 非法（解析失败）：`var x = axiom p`——axiom 只能出现在语句位，不能作子表达式

**语义类**

- 顶层公理不进函数体：函数帧是 `Frame(null)`，`axiom p` 写在顶层后，函数内 `∴ p` 仍证不出（实测 E-CANNOT-PROVE，Γ 为空）。公理要写在要用它的函数里
- 喂给 when[p ∨ q] 的公理只解决**析取前提**（否则 W-PROP-UNBOUND）；两支必须各自证出同一结论才会回注外层 Γ，否则报警告 W-WHEN-NO-COMMON（实测：两支分别 `∴ p` 与 `∴ q` 时报警告但不报错）
- 工程模式（单文件 check/compile）顶层除了声明只允许 axiom：`∴ q` 之类的顶层语句报「顶层只能写声明，执行代码请放进 fun main()」。根文档的 `axiom n = 1 / ∴ n = 1` 组合是**脚本模式**（run-script）形态；脚本模式同样执行证明检查（实测 `∴ 1 = 2` 报 E-CANNOT-PROVE）
- 尾随逗号会吞掉下一行：`axiom q1, q2,` 的尾逗号只在后随 `}`/`;`/EOF 时终止（Parser.kt:710-715）；换行不是 token。若下一行是新语句，下行的 `axiom` 关键字会被当作普通名字并入第一条 axiom 的命题表，其后的表达式则脱锚成为悬空表达式语句（实测：`axiom q1, q2,` 后跟 `axiom ∀ n. n = n`，报 `n` 未绑定的 E-UNBOUND-NAME）。多条公理之间不要写尾随逗号
- 裸 `axiom`（后随非命题起始 token，如 `axiom` 单独成句或 `axiom => …`）不触发公理语句分支，退化为普通表达式语句，按未绑定名字报 E-UNBOUND-NAME「没找到 axiom」（实测）
- 命题参数歧义（实测报错）：

  ```subl
  fun w[@q]<q>: Null { }
  @impure fun main() {
      axiom alpha
      axiom beta
      w()                // E-PROP-AMBIGUOUS：「命题参数 @q 有 2 个 0 元候选，请显式供给」
  }
  ```

  改法：显式供给 `w<alpha>()`，或只留一条候选公理
- `axiom` 是软关键字（词法层就是普通 IDENT）：判定全靠「语句位 + 后随命题起始 token」的组合。因此 `axiom` 只在**语句开头**才是公理关键字；出现在表达式位（如 `f(axiom)` 实参、`var s = axiom` 初值）时只是个未绑定的名字，按 E-UNBOUND-NAME 处理（实测同款错误）

**机制混淆类**

- `unchecked var x = 1` 只放开**被包裹那一条**语句的闸门：后续的 `x = 2` 仍按 E-IMMUT-ASSIGN 拒收（实测）。公理改写后 unchecked 与 axiom 已无组合关系
- `@axiom` 注解（函数级，信任下文 post）与 axiom 语句（命题级，注入 Γ）是两套机制，勿混用（Checker.kt:408-413 vs 761）
- 可靠性提醒：axiom 是唯一的信任逃逸口——注入的命题不经验证即生效，矛盾公理会让证明器爆炸出任意结论（例：仓库测试 自动证明 08）。请只用于外部事实/数学前提，不要用来绕过证不出的义务

**风格类**

- 命题无优先级、全左结合：`1 + 2 = 2 + 1` 实际按 `((1 + 2) = 2) + 1` 解析（左结合 `=` 再接 `+ 1`）；复杂命题一律加括号（实测报错渲染可见此结构）
- `=` 在命题位是等式谓词：`axiom n = 1` 不是赋值，而是注入「n = 1」这一事实（对照程序位赋值 `n = 1` 语句）
