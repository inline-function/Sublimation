# unchecked 语句（局部检查豁免）

> wiki/04-效应-unchecked语句 · 主题域：`unchecked <语句>` 关键字语句——修正案后唯一保留形态，对可变性与纯度类检查闸门作**词法局部**豁免（`uncheckedDepth` 为机制实现细节）
> 事实来源：_facts/_langfacts.md §2/§6（行号线索，经源码复核）；Lexer.kt:43、Parser.kt:650/796-803/1478/1749、Ast.kt:262/350/371、Checker.kt:47/612/767/777/790/798/1078/1119/1158/1165/1174、CallCheck.kt:485/676-678/747/755-764/769、Evaluator.kt:194、JsCodeGen.kt:462/515/557/676/822/1520/1587、GlobalInference.kt:170/751/886、LspAnalysis.kt:660/1081/1920-1930、AnnotationScanner.kt:86、AndroidViewEdits.kt:115/231；条目内全部行为经 `build/libs/sublimation.jar` 实测（check / compile + node / run-script）对齐

`unchecked` 是**检查器闸门的局部开关**：写在一条语句前面，这条语句就不再受可变性与纯度类检查约束。它不是类型、不是注解、不改变任何函数的效应身份——编译期一过，运行时只剩一行可 grep 的注释（JsCodeGen.kt:822）。修正案之后它只剩关键字语句这一种形态；旧的 `unchecked.axiom[p]` 已废止，公理注入改由独立的 `axiom` 语句承担（见 [公理语句](03-证明-公理语句axiom.md)）。

与同族机制的正交分工：

| 机制 | 层次 | 作用 | 是否注入事实 |
|------|------|------|--------------|
| `axiom p` | 证明层 | 把命题不经证明注入 Γ | 是（Γ += p） |
| `unchecked <语句>` | 效应/可变层 | 关闭闸门，放过检查 | 否 |
| `@impure` | 声明层 | 把函数**声明**为非纯（效应义务沿调用链上传） | 否 |
| `pure<f>` 上下文供给 | 证明层 | 证出 `pure<f>` 则该调用视为纯 | 是 |

## 语法

```subl
unchecked <语句>                  // 唯一形态：关键字 + 恰好一条语句
```

`unchecked` 是词法关键字（Lexer.kt:43 关键字表），语句分派在 `parseStmt` 命中（Parser.kt:650），由 `parseUnchecked`（Parser.kt:796-803）消费关键字后递归 `parseStmt`——因此**被前缀的语句种类不限**（以下形态全部实测 check 通过）：

```subl
unchecked x = 2                   // 赋值语句
unchecked var r = gcd(b, a % b)   // var 声明
unchecked print(x)                // 表达式语句（@impure 调用）
unchecked return 0                // return 语句
unchecked if (b) { x = 1 }        // if 语句（豁免覆盖分支块内的赋值）
unchecked when (n) { 1 => { x = 1 } }  // when 语句（实测运行输出 2）
unchecked axiom p                 // 公理语句（合法；但通常多此一举，见语义节）
```

**文法约束（实测核对）**

- **只能前缀语句，不能前缀表达式**：右值位写 `var x = unchecked 1` 立刻 ParseFailure（实测「期望表达式，实际'unchecked'」）。`unchecked` 是语句修饰符，不是表达式运算符。
- **只覆盖紧跟的一条语句**：`unchecked var y = 5` 之后的 `y = y + 1` **不**在豁免内（实测随即报 E-IMMUT-ASSIGN）。
- **合法位置**：函数体/任意语句块内的语句位；脚本模式（`.subs`，`run-script`）的顶层语句位（实测 `unchecked print("script-top")` 直接执行输出）。工程模式（`.subl`）顶层只放声明——顶层写 `unchecked` 实测报「期望顶层只能写声明，执行代码请放进 `fun main()`」。
- **旧形态废止**：`unchecked.axiom[p]` 与 `unchecked.<其它>`（如 `unchecked.print(1)`）一律 ParseFailure（实测语句位报通用语法错误「期望表达式，实际'.'」）。`parseUnchecked` 里预埋的两条旧形引导句（Parser.kt:798/800）判据在 `pos++`（:801）**之前**求值——`peek(DOT)` 永远落在 `unchecked` 自身 token 上，两条引导句是死代码、从未触发（代码读证 + 实测报错消息吻合）。修法：公理写独立 `axiom p` 语句，见 [公理语句](03-证明-公理语句axiom.md)。
- **块形态的真相**：`unchecked { … }` 检查期照常按「豁免覆盖 inner」处理（实测 check 通过）；但花括号在表达式语句位会被 `braceHasImplicitBody`（Parser.kt:1749，经 parsePrimary:1341 收割）当成**隐式单参 lambda**，因此运行时它只创建一个即弃闭包而**不执行体**（实测：`var n = 1` 加 `unchecked { n = 2 }` 后 `n` 仍为 1；compile 产物里是 `(_) => { … };` 一句）。详见陷阱。

## 语义

### 机制总览：一个深度计数器

语义层只有一个状态：Checker 的可变字段 `uncheckedDepth`（Checker.kt:47）。进入 `UncheckedStmt` 时 `++`，检查完 inner 立即 `--`（Checker.kt:767）：

$$\frac{\texttt{unchecked}\ s \ \text{出现于语句位} \qquad G \in \{\text{E-IMMUT-ASSIGN},\ \text{pure 传播},\ \text{E-IMPURE-CALL},\ \text{E-NON-STRUCTURAL-REC},\ \text{E-NEED-ASYNC}\}}{G \ \text{在}\ s\ \text{的词法范围内关闭（}\mathrm{uncheckedDepth} > 0\text{）}} \quad (\text{局部豁免})$$

$$\frac{\texttt{unchecked}\ s \qquad s' \ \text{位于}\ s\ \text{的 inner 之外}}{\text{所有闸门对}\ s'\ \text{照常开启}} \quad (\text{单语句词法性})$$

- 语句的**值与类型**就是 inner 的值与类型（原样返回 inner 的检查结果）；控制流分析（`stmtHasReturn`/`stmtDiverges`）也按 inner 归约（Ast.kt:350/371）
- 计数是**词法局部**的：inner 里的嵌套 lambda/块继承它（体内 pure 位一律按 `uncheckedDepth > 0 ⇒ pure = false` 传播，Checker.kt:1119/1158/1165），出了 inner 立即恢复
- `unchecked` **不向 Γ 注入任何命题**、不改类型、不改代码生成——它是纯编译期开关；LSP 给 E-IMPURE-CALL 的自动修复就是在行首插入 `unchecked `（LspAnalysis.kt:1920-1930，测试断言「应在语句前插 unchecked」）

### 穿透点盘点：38 = 25 + 13

机制虽简单，`UncheckedStmt` 节点却贯穿前端到注解处理器的整条管线（grep 盘点，行号为复核时点）：

**25 个 UncheckedStmt AST 处理点**（全部「穿透 inner」——按内层语句归约）：

| 管线 | 位置 | 行为 |
|------|------|------|
| Parser | Parser.kt:802、1478 | 构造节点；`containsIsPatStmt` 递归 inner |
| Ast | Ast.kt:262、350、371 | 节点定义（「纯度/可变性逃逸」）；`stmtHasReturn`/`stmtDiverges` 按 inner 判定 |
| AstPrinter | AstPrinter.kt:84、176 | 树形 `Tree("unchecked", [inner])` / 纯文本 `unchecked <inner>` |
| Checker | Checker.kt:767 | 唯一对语义有影响的一处：`uncheckedDepth++` 后递归 |
| Evaluator | Evaluator.kt:194 | 直接执行 inner（`evalStmt(stmt.inner, frame)`） |
| JsCodeGen | JsCodeGen.kt:462、515、557、676、822、1520、1587 | 7 处全按 inner 归约（视图突变/安卓调用/等式漫游/binder/串化/lambda 体）；`genStmt` 生成 `// unchecked` 注释后透传 inner |
| GlobalInference | GlobalInference.kt:170、751、886 | 全局推导 3 处：`stmt`/`walkForPosts`/重写 `copy(inner = …)` |
| LSP | LspAnalysis.kt:660、1081 | 符号漫游与语义视图按 inner 递归（另有 1715/1920-1930 的诊断解释表与快速修复，非分派位） |
| annotationproc | AnnotationScanner.kt:86；AndroidViewEdits.kt:115、231 | 扫描递归 inner；改写流水线**保留 UncheckedStmt 包壳**（重写 inner 后重新包回） |
| 测试 | ParserTest.kt:332 | 「unchecked 纯度逃逸」用例 |

**13 个 `uncheckedDepth` 闸门判据**（代码里不出现节点名，但读这个计数器）：

| 闸门 | 位置 | 判据摘录 | 豁免后效果 |
|------|------|----------|----------|
| E-IMMUT-ASSIGN（不可变重绑定） | Checker.kt:612 | `!isMut(lhs) && uncheckedDepth == 0` | 不可变变量可再赋值（⚠ JS 目标见陷阱 1） |
| 纯度传播 pure→false | Checker.kt:767、777、790、798、1078、1119、1158、1165、1174 | `if (uncheckedDepth > 0) false else pure` | 体内表达式一律按非纯上下文检查（递归 lambda 体/块） |
| E-IMPURE-CALL（impure **函数值**调用） | CallCheck.kt:485 | `pure && uncheckedDepth == 0 && !borrowed` | 非纯函数值可在「纯作用域」调用；`borrowed` 准入：被调函数值是本函数声明的 `@impure` 形参（显式效应能力）时本来就放行 |
| E-IMPURE-CALL（impure **具名函数**调用） | CallCheck.kt:755-764 | `"impure" in fn.annotations && pure`（unchecked 把 pure 置 false，判据随之失配） | 调用 @impure 函数不再要求证出 `pure<f>` |
| E-NON-STRUCTURAL-REC（纯函数停机性） | CallCheck.kt:747 | `uncheckedDepth == 0`（另有非方法路径等条件） | 纯函数自递归可不做「参数变小」 |
| E-NEED-ASYNC（async-in-sync） | CallCheck.kt:769 | `"async" in fn.annotations && !curFunAsync && uncheckedDepth == 0` | 同步函数体里可直接调 @async 函数（⚠ 拿到 Promise，见陷阱 6） |

> **关于「Any 准入」**：事实表 §6 把 CallCheck.kt:485 标为「Any 准入」，经源码与实测复核，:485 实为 **impure 函数值调用闸**。`uncheckedDepth` 确为「决策 59」而引入（Checker.kt:46 注释「Any 结构体准入判据」），但现行实现里 Any 字段实参是**无条件接受**的（决策 59：`fp.type.name == "Any"` 即放行，CallCheck.kt:676-678，不读 `uncheckedDepth`）：`struct P(v: Any)` 的 `P(1)` 构造实测**不需要** unchecked。旧审查报告引述过的 `E-ANY-STRUCT` 闸在当前实现中已不存在（何时移除：待核）。

### 豁免的边界（实测）

`unchecked` 只关以上闸门，**不关任何其它检查**。以下实测全部仍报错：

- E-UNBOUND-NAME（`unchecked zzz = 1`：赋值目标未定义——名字解析在闸门之前，Checker.kt:611 先于 :612）
- E-TYPE-MISMATCH（`unchecked var x : Str = 1`，同时照常附带 W-UNUSED）
- E-NOT-MUT（字段写权限：`unchecked p.n = 2`——字段须 @mut 且接收者持 @mut 视图，`checkMutFieldWrite` Checker.kt:859-872 不读 `uncheckedDepth`，见 [@mut](02-类型-@mut.md)）
- 证明义务（E-CANNOT-PROVE / E-PREMISE-UNPROVEN）与结构体伴生命题（`checkStructProps` 无 `uncheckedDepth` 分支，见 [结构体](02-类型-结构体.md)）
- W- 警告族（W-UNUSED 等）

### 与 axiom 的分工（修正案核心）

修正案前 `unchecked.axiom[p]` 把「检查豁免」与「事实注入」混在一个形态里；修正案一刀两断：

- `axiom p1, p2, …`：**事实注入**——命题经 `PropLogic.fromExpr` 规范化后 `f.inject` 进当前作用域 Γ（Checker.kt:761），返回 Null；不过任何闸门
- `unchecked <语句>`：**检查豁免**——只动 `uncheckedDepth`，不向 Γ 注入任何命题

二者正交、可叠加：`unchecked axiom p` 合法（实测 check 通过），但语义上多此一举——要事实写 `axiom`，要逃逸写 `unchecked`。同理，`axiom pure<g>` 也能让一次 impure 调用「变纯」（实测：先写 `axiom pure<g>` 语句再调用 g()，check 通过），但它向 Γ 注入了一条**可能不成立**的事实（g 明明标了 @impure）；`unchecked` 只闭眼前的闸、不撒谎。

### 运行时零成本与可审计性

- 解释器：`evalStmt(UncheckedStmt) → evalStmt(inner)`（Evaluator.kt:194），语义与去掉 `unchecked` 完全相同
- JS 生成：只多一行 `// unchecked` 注释，inner 原样透传（JsCodeGen.kt:822）
- 审计：任意产物 JS 里 `grep "// unchecked"` 即可找齐全部逃逸点——**但注意陷阱 1：闭闸不等于 codegen 会配合**

## 示例

以下示例均经实测（check 通过；带运行标注的另经 run-script 或 compile + node 执行）。

**例 1：不可变变量重绑定（E-IMMUT-ASSIGN 豁免）**

```subl
// probe.subs（脚本模式，run-script 实测输出 3）
var n = 0
unchecked n = n + 1
unchecked n = n + 2
unchecked print(n)          // 3
```

注意：此例只在**解释器**里安全。compile 到 JS 会因 const 绑定崩溃（实测 `TypeError: Assignment to constant variable.`，见陷阱 1）。重绑定的**正路**是 [@mut](02-类型-@mut.md)：

```subl
fun main() {
  @mut var n = 0
  n = n + 1
  n = n + 2
  unchecked print(n)        // 3（JS 目标实测通过：let 绑定 + 裸赋值）
}
```

**例 2：@impure 调用豁免（最常见用法）**

```subl
@impure fun greet(name: Str): Null {
  print("hello " + name)    // greet 自身非纯，体内不受纯度闸约束
}

fun main() { unchecked greet("world") }
```

去掉 `unchecked` 报 E-IMPURE-CALL「纯作用域内调用非纯函数 greet（用 unchecked 前缀，或在上下文供给 pure<greet>）」。**三条更窄的替代路径**（均实测）：调用方自己标 `@impure`（`@impure fun main() { greet("world") }` 通过）；上下文注入 `axiom pure<greet>`（通过）；跨模块时由模块系统注册的 `pure<f>` 事实自动放行（见 [模块系统](05-工程-模块与import.md)）。

**例 3：impure 函数值调用（CallCheck.kt:485 闸）**

```subl
@impure fun g(): Nat = 1

fun main() {
  unchecked var f = { => g() }   // lambda 在 unchecked 内创建：体内 impure 调用一并豁免
  unchecked print(f())           // f 的类型是 impure () => Nat，调用也靠 unchecked 放行
}
```

实测运行输出 `1`。注意 lambda 的 **impure 类型并没有被洗白**（Checker 在 lambda 体内遇到 impure 调用时无条件标记 `curLambdaImpure`，CallCheck.kt:754 不读 `uncheckedDepth`）——`unchecked` 只闭了**调用点**的闸。

**例 4：async-in-sync 豁免（E-NEED-ASYNC）**

```subl
@async fun g(): Nat = 1

fun h(): Nat {
  unchecked g()            // 同步上下文里直接调 @async 函数：编译通过
  return 0
}

fun main() { unchecked print("x") }
```

去掉 `unchecked` 报「调用 @async 函数 g 需 @async 上下文（给调用方函数 h 加 @async 注解）」。**但编译通过 ≠ 得到结果**：codegen 只在 @async 上下文自动插 await（CallCheck.kt:771-773），同步函数里拿到的是 Promise——实测 `unchecked var t = g(); unchecked print(t)` 的 JS 产物输出 `[object Promise]`（见 [异步 Task 与 Flow](04-效应-异步Task与Flow.md)）。

**例 5：纯函数停机性豁免（E-NON-STRUCTURAL-REC）**

```subl
fun f(n: Nat): Nat {
  unchecked f(n)           // 自递归参数不「变小」也放行（编译器不再检查停机性）
  return 0
}

fun main() { unchecked print("x") }
```

仅当确认递归会停（或本就不指望编译器证明）时使用；正路是让某个实参严格递减，或给函数加 `@impure`（本例仅演示检查豁免，真调用会无限递归）。

**例 6：脚本模式顶层（无需 main）**

```subl
// probe.subs
var n = 1
unchecked n = 2
unchecked print(n)         // run-script 实测输出 2
```

**例 7：与 axiom 正交叠加**

```subl
fun main() {
  axiom even, odd          // 事实注入：证明层用
  unchecked print("ok")    // 检查豁免：效应层用
}
```

## 陷阱

- **JS 目标里，E-IMMUT-ASSIGN 豁免会炸**（最重要）：`unchecked x = 2` 闭的是**编译期**闸门，但 codegen 按类型决定绑定方式——不可变 `var` 生成 `const`，于是产物里就是 `const x = 1; … x = 2;`，node 实测抛 `TypeError: Assignment to constant variable.`（解释器无此问题，因为 Evaluator 没有 const 概念）。**重绑定的正路永远是 `@mut`**；`unchecked` 救急只在解释器链路（`run-script`/脚本）里成立。
- **`unchecked { … }` 不是块，是即弃 lambda**：花括号在表达式语句位被 `braceHasImplicitBody`（Parser.kt:1749）收成隐式单参 lambda——检查期豁免照常生效，运行时却只创建闭包、**体不执行**（实测 `n` 不变；JS 产物 `(_) => { … };`）。**多条语句请逐条加 `unchecked`**，不要包花括号。（此 quirk 与 `unchecked` 无关——裸 `{ … }` 语句本身即如此，但 `unchecked` 的「豁免整块」直觉最容易让人踩上去。）
- **豁免只到分号为止**：`unchecked var y = 5` 后的 `y = y + 1` 照报 E-IMMUT-ASSIGN——作用域是「一条语句」而非「一个段落」。
- **旧 `unchecked.axiom[p]` 已废，且报错消息不带引导**：实测只有通用的「期望表达式，实际'.'」（parseUnchecked 预埋的引导句是死代码，见语法节）。公理写 `axiom p`，逃逸写 `unchecked <语句>`，别再混写。
- **不能前缀表达式**：`fun f(): Nat = unchecked 1` 解析失败（实测「期望表达式，实际'unchecked'」）；函数体右值位没有 `unchecked`。想豁免请改块体 `{ unchecked …; return v }`。
- **async 逃逸不等于 await**：`unchecked g()` 闭掉 E-NEED-ASYNC，但 codegen 只在 @async 上下文插 await——同步调用方拿到的是 **Promise**（实测 `[object Promise]`）。此外 `t.join()`/`ch.send()`/`ch.receive()`/`m.lock()`/`m.withLock()`/`fl.collect()` 等内建挂起方法**连 unchecked 也逃不掉**（直接按 `curFunAsync` 判定，见 [异步 Task 与 Flow](04-效应-异步Task与Flow.md) 实测）。
- **lambda 的纯度跟着创建点走，不跟着调用点走**：lambda 体内遇到 impure 调用会被无条件标记 impure（CallCheck.kt:754 不读 `uncheckedDepth`）。于是 `unchecked print(f())` 只放开调用闸；若 `f = { => g() }` 在 unchecked 之外创建，lambda 体内那个 `g()` 照报 E-IMPURE-CALL——逃逸覆盖**创建点**，洗不白**类型**。
- **豁免的不是全部检查**：类型核对（E-TYPE-MISMATCH）、名字解析（E-UNBOUND-NAME）、字段写权限（E-NOT-MUT）、证明义务（E-CANNOT-PROVE / E-PREMISE-UNPROVEN）、结构体伴生命题、W- 警告族**全部照常**。`unchecked` 救不了非法构造，只放过可变性与纯度闸。
- **「Any 准入」已是历史痕迹**：旧文档/注释里「Any 结构体须 unchecked 创建」的说法在现行实现中不成立——Any 字段实参无条件接受（CallCheck.kt:676-678），构造 `struct P(v: Any)` 无需 unchecked（实测）；`uncheckedDepth` 的「决策 59」注释只是它最初被引入时的用途。
- **优先用更窄的替代**：E-IMPURE-CALL 的正路是给函数加 `@impure`（效应义务显式化）、在上下文供给 `pure<f>`（决策 54），或跨模块时依赖模块系统注册的纯度事实；跨模块调用因 P0 无跨模块 pure 事实才被迫用 `unchecked`。它是最后手段——产物里那行 `// unchecked` 注释就是要你 grep 得出来、复审得过来。
