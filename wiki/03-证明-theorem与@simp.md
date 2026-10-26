卷名：03 · 证明 —— theorem 与 @simp
主题域：`@theorem` 定理函数声明、`@simp` 化简策略、`@classic`/ `@axiom` 注解的证明层语义，及其与 `axiom` 语句、∵∴ 链的分工
事实来源：_facts/_langfacts.md §4/§6；frontend/.../parser/Parser.kt（parseEntry :194、parseAnnotations :261、parseFun :267、parseTheory :339、parseStmt :631、策略语句 :653、parseAxiomStmt :706、parseBTS :683）；compiler/.../checker/Symbols.kt（:183-187）、Types.kt（:355-361）、Checker.kt（:408-418、:632-699、:761-766、:1442-1510）、CallCheck.kt（:743、:898-943）、Diffusion.kt（:250-367）、Prop.kt（:416）、JsCodeGen.kt（:126、:203-207、:576、:1042-1046）；core/logic/logic.subl、core/classic/classic.subl；compiler/src/test CheckerTest.kt（:1433-1449、:1491-1499、:839-848）；并用 build/libs/sublimation.jar 对 _facts_tmp 下若干探针文件做了 check / compile / run-script 实测

# 03 · 证明 —— theorem 与 @simp

> **一句话**：`@theorem` 把一个命题函数升格为「可命名、可复用、可被全局搜索的定理」；`@simp[命题]` 是手动调用的算术化简策略；`@classic` 与 `@axiom` 是两个证明层开关——前者开通经典逻辑通道，后者豁免后件自证。它们与 `axiom` 语句、`∵ … ∴` 链各司其职，混用是本节最大的坑。

## 语法

### `@theorem` 声明

`@theorem` 是**函数注解**（不是关键字），写在 fun 声明之前，其余部分与普通函数完全同形：

```subl
@theorem
fun leqTrans[a, b, c]: Null <(a <= b) & (b <= c) → (a <= c)>

@theorem
fun myLEM[@q]: Null <q ∨ ¬q>

@theorem
fun plusComm(a: Nat, b: Nat): <a + b = b + a>      // 返回类型可省略：: <post> 等价于 : Null <post>

@theorem
fun Triv[p]: Null <p> { axiom p }                   // 也可以有体（体仍须证明后件）
```

| 部分 | 形态 | 说明 |
|---|---|---|
| 注解 | `@theorem` | parseAnnotations（:261）吃 `@` + 标识符序列；可与其他注解叠写（`@axiom @theorem`、`@classic @theorem` 均合法） |
| theory 头 | `[a, b]` / `[@p]` / `[F[_]: Foldable]` | parseTheory（:339）：裸名=类型/项元变量，`@p`=命题元变量，可带 kind 与类型类约束 |
| 值形参 | `(a: Nat, b: Nat)` | 形参名在定理搜索中同样是元变量 |
| 上文 | `<p>`（`:` 前） | 调用方必须已证明 |
| 返回类型 | `Null`（可省略） | 省略时 `: <post>` 直接写后件 |
| 下文 | `<q>`（返回类型后） | 后件：定理的「结论」 |
| 体 | `= expr` 或 `{ 语句… }` 或无体 | 两形态互斥（:298-305）；无体=签名即定理 |

### `@simp` 策略语句

```subl
@simp[(3 * 2 + 6) = 12]          // 语句位：@ + 策略名 + [命题]
@simp[3 * 2 < 10]
@subst[b = a]                    // 兄弟策略：等式/等价改写
```

文法（parseStmt :653-662）：语句位置遇到 `@` 且后随 IDENT + `[` → `StrategyStmt(策略名, 命题)`。策略名是**普通标识符**，不限于 `simp`——但 checker 只认 `simp` 与 `subst`（Checker.kt:687-691），其余一律判失败。工程模式（.subl）里策略语句必须写在函数体内；脚本模式（.subs）可写顶层。

### `@classic` / `@axiom` 注解

```subl
@classic
fun raa[@p, @q]<¬p → q, ¬p → ¬q>: Null { ∴ p }

@axiom
fun size[F[_]: Foldable, A](fa: F[A]): Nat
    <$ = fold(fa, 0, { acc, _ => acc + 1 })>
    = fold(fa, 0, { acc, _ => acc + 1 })
```

两者都是 fun 声明上的注解（parseEntry :196-205 接收 `@ann fun`），无独立语法。`@classic` 也可与 `@theorem` 叠写——core/classic/classic.subl 的 `tertiumNonDatur` 即为 `@classic @theorem`。

### 与 axiom 语句、∵∴、by 的语法对照

| 构造 | 形态 | 出现位置 |
|---|---|---|
| axiom 语句 | `axiom p1, p2, …` | 顶层 + 函数内（parseAxiomStmt :706） |
| ∵∴ 链 | `∵ form, p₁, p₂ ∴ conclusion` | 函数内（parseBTS :683）；裸 `∴ q` 反糖为 `∵ ⊤ ∴ q` |
| by 表达式 | `expr by [q]` | 表达式位后缀（:1027）；语句式 `by [q]` 已移除（t30，E-BY-REMOVED，:652 仍解析） |
| 策略语句 | `@simp[命题]` | 函数内 / 脚本顶层 |

## 语义

### 1. `@theorem`：登记进定理空间

判据只有一条——**注解里含 `theorem`**：

$$\frac{\text{"theorem"} \in \text{decl.annotations}}{\text{decl} \in \text{syms.theorems}} \quad (\text{THEOREM-REG},\ \text{Diffusion.kt:340})$$

反之，未标注的纯 + Null 函数**不进**定理空间（isTheoremFun 注释明示；Symbols.kt:185 只在标注时登记）。登记是全局的：模块模式下跨文件/跨模块的定理经可见路径汇入（Diffusion.kt:351-353）。core/logic/logic.subl 的整套等式/蕴含/量词定理、core/classic/classic.subl 的排中律定理都由此机制供用户代码搜索。

**搜索**（searchTheorems，Diffusion.kt:346-367）在每条证明义务的出口运行（Diffusion.kt:336：前件/后件/伴生命题/构造位一律受益）：

- 把定理后件（post）与目标做**结构合一**：theory 参数（TypeParam/PropParam 名）+ 函数形参名是元变量，可绑定到目标对应位置；
- 常量归约：后件里的名字若可由全局事实归为字面量，归约后再合一一次；
- 深度默认两跳，超限报 `E-THEOREM-SEARCH-DEPTH`（**警告**，不阻塞）。

**调用点**与普通函数完全同语义：上文 `<p>` 强校验（`E-PRE-UNPROVEN`，CallCheck.kt:921-925），下文 `<q>` 照常注入调用方 Γ（CallCheck.kt:940-943）。因此定理可以「当程序调用」——`plusComm(1, 2)` 之后，`need()<1 + 2 = 2 + 1>` 的前件自动满足（CheckerTest:1491-1499 实测）。

**体的义务**：`@theorem` **不豁免**后件自证——有体的定理仍须从体证出下文，否则 `E-POST-UNPROVEN`（Checker.kt:413-418；实测：`@theorem fun bad(): <$ = 999> = 998` 报错）。豁免要靠 `@axiom`。标注也不要求纯度或 Null 返回：`@theorem @impure`、`@theorem fun t(): Nat = 7` 均通过 check（实测）。

**运行时**：

$$\frac{\text{"theorem"} \in \text{decl.annotations}}{\text{JsCodeGen 不生成该函数，值级调用翻译为 null}} \quad (\text{THEOREM-ERASE},\ \text{JsCodeGen.kt:576/1042-1046})$$

注意这只发生在 **JS 代码生成**侧：解释器（`run`/`run-script`）照常求值定理函数的体——实测 `@theorem fun t(): Nat = 7` 在 run-script 下打印 `7`，compile 后同一调用变成 `null`。Types.kt:356 注释称「有体定理照常生成运行时代码」，与 JsCodeGen.kt:576 的实现冲突——实测有体定理同样被擦除，以实测为准（旧注释口径已过时）。

### 2. `@simp`：手动调化简器

`StrategyStmt` 在 Checker.kt:684-698 的分派：

$$\frac{\text{ArithProve.proves}(\Gamma,\ \text{goal}) = \text{true}}{\Gamma \mathrel{+}= \text{goal},\ \text{D-STRATEGY}} \qquad \frac{\text{ArithProve 判 false 或形态不支持}}{\texttt{E-STRATEGY-FAILED}\ (\text{错误，阻塞编译})}$$

- `simp` 委托给 ArithProve 的**线性算术**证明器（Checker.kt:1444-1446）：区间、常量折叠、不等式。它只懂数字比较/等式——`@simp[p]` 里 `p` 即便已由 `axiom p` 注入 Γ 也证不出（实测：`axiom p` + `@simp[p]` 报 `E-STRATEGY-FAILED`，渲染为 `无法证明 p`）。它不是「再试一次自动证明器」，而是**定向的算术通道**。
- `subst` 是莱布尼茨代换（Checker.kt:1477-1510）：用 Γ 里的 `=`/`==` 等式事实对目标做项级深度替换、用 `↔` 等价事实做子命题替换，BFS 改写直至命中事实库（另承认平凡自反等式）；步数与节点体积上限都是 400，防膨胀型等式指数爆炸。
- 未知策略名（如 `@bogus[1 = 1]`）落到 else 分支判 false，同样报 `E-STRATEGY-FAILED`（实测）。
- `@simp` 作为**声明注解**（`@simp fun f() …`）解析通过但 checker 不读，无任何语义效果（实测通过 check；它是「策略语句」而非注解，别写错位置）。

### 3. `@classic`：经典逻辑开关

挂载判据（Symbols.kt:186-187、Types.kt:360-361）：**文件内任一 fun 声明的注解含 `classic`** 即 `syms.classicMounted = true`。三个消费点：

| 消费点 | 行为 | 未挂载时 |
|---|---|---|
| `if[p] { … } else { … }` 排中律 | 两支各注入 `p`/`¬p`，公共结论溢出块外（Checker.kt:701-724） | `E-NEED-CLASSIC` **警告**（不阻塞） |
| 排中律前向实例化 | 形状为 `p ∨ ¬p` 的可见定理对命题池候选注入 `A ∨ ¬A`（Diffusion.kt:250-262） | 通道直接 return，不启用 |
| 激进模式反证法 | `Γ ∪ {¬goal}` 饱和出 `⊥` ⇒ goal（Diffusion.kt:330-333） | 绝不启用（健全性看守） |

注意挂载只认 **fun 声明**：实测 `@classic struct S(n: Nat)` 之后的 `if[p]` 照样报 `E-NEED-CLASSIC`（struct 分支不置位 classicMounted）。core.classic 的 `tertiumNonDatur[p]: Null <p ∨ ¬p>` 是现成的排中律形态定理，配合前向实例化使用。

### 4. `@axiom`：信任函数签名

函数注解（Checker.kt:408-413）：**只豁免「后件自证」**——下文签名即公理，编译器信任而不跑自动证明，并给 `D-AXIOM-POST` 补充诊断（`-v` 可见）。**上文不豁免**：调用点仍强校验前件（CheckerTest:839-848 的测试注释明示；实测 `@axiom @theorem` 叠写通过、单独 `@theorem` 不可证后件报错）。

$$\frac{\text{"axiom"} \in \text{fn.annotations}}{\text{跳过 E-POST-UNPROVEN（后件信任）},\quad \text{上文 } <p> \text{ 仍在调用点强校验}} \quad (\text{AXIOM-TRUST})$$

标准库典型用法：机器不可证的语义性 post（如 `size($) = fold(...)`、归纳两支的 `fold` 行为）——`@axiom` 放行，责任由作者在注释里写明依据（见 core 与 CheckerTest:784-807）。

### 5. 与 axiom 语句 / ∵∴ 链的分工

| 构造 | 结论从哪来 | 是否需证 | 作用域 | 运行时 |
|---|---|---|---|---|
| `axiom p1, p2` | 作者直接声明 | **免证**注入 Γ | 当前函数/块（顶层声明则入模块事实） | 语句求值为 Null |
| `@axiom fun` | 函数下文签名 | 后件免证，**上文仍需证** | 调用点注入 | 函数体正常生成 |
| `@theorem fun` | 定理后件 | 有体时体须证后件 | **全局定理空间**（可搜索） | **JS 擦除**，调用→null；解释器照常求值 |
| `∵ p₁, p₂ ∴ q` | Γ 推导 | 前提**必证**（`E-PREMISE-UNPROVEN`），结论须推出（`E-CANNOT-PROVE`） | 结论注入当前 Γ | 语句求值为 Null |
| `expr by [q]` | 断言（t30） | **须证**（`E-CANNOT-PROVE`） | 不注入 | by 后缀擦除，只留目标值 |

一句话分工：**axiom 是「我说它是」的局部信任（t30 后唯一的免验证入口）；by 断言是「请你查 Γ」的表达式位义务；@axiom 是「签名说的算」的函数级信任；@theorem 是「全局可搜的名片」；∵∴ 是「请你查 Γ」的语句位证明义务**。

## 示例

### 例 1：定理搜索（纯靠合一证明）

```subl
@theorem fun gT[a, b]: Null <a = b>      // 后件 a = b：a/b 是元变量

@impure fun main() {
    ∴ s = t                               // Γ 里没有 s = t——定理搜索合一命中（实测通过）
}
```

### 例 2：程序风格——定理调用注入下文

```subl
@theorem fun plusComm(a: Nat, b: Nat): <a + b = b + a>

fun need()<1 + 2 = 2 + 1>: Null { }

@impure fun main() {
    plusComm(1, 2)                        // 调用注入下文 1 + 2 = 2 + 1
    need()                                // 前件自动满足（实测 check 通过）
}
```

### 例 3：@simp 算术化简（工程模式写函数内）

```subl
@impure fun main() {
    @simp[(3 * 2 + 6) = 12]               // 常量折叠成立（实测通过）
    @simp[3 * 2 < 10]                     // 不等式成立（实测通过）
    @simp[1 = 2]                          // 报 E-STRATEGY-FAILED（渲染 =<1, 2>）
}
```

### 例 4：@subst 等式改写

```subl
@impure fun main() {
    axiom a = b
    @subst[b = a]                         // 改写 b → a 得 a = a（自反等式承认）→ 证出（实测通过）
}
```

### 例 5：@classic 挂载与 if[p] 排中律

```subl
@classic
@impure fun main() {
    var y = 2
    if[y = 2] { ∴ y = 2 } else { ∴ y = 2 }   // 两支公共结论溢出块外（实测通过）
}
```

### 例 6：@axiom 信任不可证后件

```subl
fun spec(n: Nat): Nat <$ = 999> = n        // 报 E-POST-UNPROVEN（体值 n 证不出 $ = 999）

@axiom
fun spec2(n: Nat): Nat <$ = 999> = n       // 通过；-v 可见 D-AXIOM-POST（实测）
```

### 例 7：axiom 语句 + MP 闭包 + ∵∴ 链

```subl
@impure fun main() {
    axiom p ∧ (p → q)                     // 免证注入：MP 闭包自动产出 q
    ∵ p ∴ q                               // 前提 p 在 Γ，结论 q 已闭包产出——直通（实测通过）
}
```

## 陷阱

1. **`@classic` 只在 fun 声明上挂载**——`@classic struct S(…)` 解析通过但 `classicMounted` 不置位（登记分支只在 FunDecl，Symbols.kt:186-187）；实测 struct 上标注后 `if[p]` 照报 `E-NEED-CLASSIC`。想开经典逻辑，给一个 fun 加注解
2. **`@simp` 只懂数字**：等式/不等式/区间走 ArithProve；原子命题 `p`（哪怕已在 Γ 里）、含值变量的复合目标一律 `E-STRATEGY-FAILED`。它不是「加强版自动证明器」，别拿它当万能钥匙
3. **`@simp[…]` 是语句，不是注解**：语句位 `@策略名[命题]`（parseStmt :653）；写在声明头的 `@simp fun f()` 无语义效果。反过来注解 `@classic`/`@axiom` 写在语句位会被解析器当成策略名（也判失败）
4. **定理元变量必须在原子直接项位**：实测 `@theorem fun commT(a: Nat, b: Nat): <a + b = b + a>` 配 `∴ x + 2 = 2 + x` 证不出——合一器不解构复合项（CallExpr 内部），元变量只能出现在 PAtom 的直接 term 位（core/logic/logic.subl 的依据注释同此）。把定理写成 `<a = b>` 形而非 `<a + b = …>` 形
5. **定理搜索两跳封顶**：超出报 `E-THEOREM-SEARCH-DEPTH`（警告，不阻塞）。`by solve` 加深为路线图，当前别依赖深层链式定理
6. **JS 与解释器行为不一致**：compile 后 `@theorem` 函数整体擦除、值级调用恒为 `null`（JsCodeGen.kt:576/1045）；`run`/`run-script` 照常求值体（实测 `t(): Nat = 7` 打印 7）。跨后端复用定理函数取值时心里要有数——约定上定理只用于证明位
7. **`@theorem` 不豁免后件证明**：有体的定理仍报 `E-POST-UNPROVEN`（实测）；要豁免请加 `@axiom`（两者可叠写，实测通过）
8. **`@theorem` 不要求纯/Null**：`@theorem @impure`、非 Null 返回类型都能过 check——但注意陷阱 6，JS 侧调用恒 null，这类「带值的定理」跨后端必然踩坑
9. **`E-NEED-CLASSIC` 是警告不是错误**：没挂 `@classic` 的 `if[p]` 照样编译通过，只是排中律通道没开（两支结论不会溢出）——别拿「编译过了」当「经典逻辑生效」
10. **axiom 语句免证但作用域局部**：`axiom p` 只注入当前函数/块；跨函数复用要么再写一次，要么升格成 `@theorem`（全局可搜）。t30 后免验证供给只剩 axiom 一条路（旧 `by [q]` 供给已删，by 断言不注入 Γ）——信任边界由作者守住，Γ 被污染不会报警
11. **旧文档口径过时**：Types.kt:356 注释「有体定理照常生成运行时代码」与 JsCodeGen.kt:576 实现冲突，实测有体 `@theorem` 同样被擦除；遇冲突以代码与实测为准
12. **策略失败是硬错误**：`E-STRATEGY-FAILED` 阻塞编译（error 级），不像 `E-NEED-CLASSIC` 是警告；`∵ … ∴` 证不出给 `E-CANNOT-PROVE`（激进模式下也不放宽）
13. **跨模块定理可见性：待核**——同一模块树内跨文件搜索已实测通过；用户模块引用 core 标准库定理的可见性规则（visiblePaths/allSymbols 与 .settings 的交互）未在本次实测，引用前请自行验证
