# Sublimation（凝华）

<p align="center">
  <img src="brand/5-HT/语言图标.png" alt="Sublimation（凝华）语言图标" width="170"/>
</p>

**把类型系统与程序逻辑融合的编程语言。Kotlin 手写多模块编译器，零第三方依赖，源码直接编译为 JavaScript；同时内置脚本模式解释器。**

语言名取自物理现象「凝华」——气态不经液态直接成固态。正如本文的命题不经运行时、直接在编译期析出为程序的静态保证。

> ✅ v4：when 分支分隔符 `=>` / 类型别名（含泛型头 `type F[a] = T[a]`）/ 标准库统一为仓库根 `core/` 包，674 项测试全绿

---

## 吉祥物：芙芙莎明（5-HT）

<table>
<tr>
<td width="140" valign="top">
<img src="brand/5-HT/表情包/灵感.jpg" alt="芙芙莎明 · 灵感" width="120"/>
</td>
<td valign="top">
<strong>芙芙莎明（5-HT）</strong>是 Sublimation 项目 的吉祥物：桃发黑眼圈、粗框眼镜、头顶 ∀ 发光发夹，胸前挂着 λ 工牌与苯环；函数式、定理证明、药理学三元素合一。完整形象设定与表情包见 <code>brand/5-HT</code>。其使用许可与项目本体不同，详见 <code>TRADEMARK.md</code>。
</td>
</tr>
</table>

## 核心特性

- **真模式匹配 `when`**——解构、嵌套构造子、守卫、穷尽性判定，含区间模板 `when(n in _) { (0..10) => ... }`（分支分隔符 `=>`；Java 式 switch 已全盘废弃）。命题层的**分类讨论语句** `when[p ∨ q] { p => { … } q => { …} }` 按条件成分支。
- **类型别名（决策30）**——`type MyNat = Nat` 具名别名、`type NatOpt = Optional[Nat]` 参数化别名，以及泛型头 `type Box[a] = Optional[a]`：使用点 `Box[Nat]` 按实参替换展开为 `Optional[Nat]`（多参数 `type Dict[k, v] = Map[k, v]` 同理）。
- **上下文弥散与自动证明**——函数类型携带前置/后置命题（伴生命题）：调用前必须满足什么、调用后能保证什么，写进类型本身（上文不满足报 `E-PRE-UNPROVEN`）。手动证明按根文档《手动证明》的「因为-所以语句」`∵ 形式, 前提 ∴ 结论` 组织：`@simp`/`@subst` 等策略驱动策略证明器、`if[p]`/`when[p ∨ q]` 命题层控制流、`∃ x = e . 命题` 存在语句、`witness<命题>()` 显式供给；`when(n in _)` 的区间穷尽由编译器收集关键表达式（如 0,10,11,20,21,30）并应用定理自动证明，无需手工补丁。`root doc/上下文系统.md` 与 `root doc/手动证明.md` 为最高权威（未经作者许可不得修改）。
- **定理函数**——`@theorem` 显式标注的定理（无副作用、不修改参数、返回 `Null`）在**编译器擦除为 Null**，供证明位（`∴` 合一/前件）；可作普通函数调用，值固定为 Null（上下文推导中为空操作）为警告，程序风格允许 @theorem 公理像代码一样调用、调用即注入其下文，可驱动后续前件）；**只有 `@theorem` 标注的函数才是定理函数**——非 @theorem 的函数（即使纯+Null）不是定理、不进定理空间，代码照常生成。预置定理库 `core/logic` 与 `core/logic/classic`（均已 `@theorem`、擦除）。
- **数学记号一等公民**——源码直接写 `∈` `≥` `≤`、`∀` `∃`、`n ∈ (0..30)`；每个自然数恒有自然命题 `n ≥ 0`；结构相同的表达式自动自反（`1 + 2 = 1 + 2` 可证，`1 + 2 = 3` 不自动证）。
- **目录即模块**——一个目录 = 一个模块，`<模块名>.settings` 显式挂载（`mount core/logic`）；`core/` 标准库包隐式自动挂载（裸名 + 全限定名皆可；标准库位于仓库根 `core/`）；跨模块定理搜索沿可见路径展开。
- **类型类与扩展方法**——`class Show[T] { fun show(): Str }` + `impl Show for Grade { ... }` 按实参类型解析字典；`impl List[a] { ... }`（没有实现类型类的 impl）为扩展方法，成员脱糖为带 `self` 首参的自由函数。方法糖 `x.f()` 优先同名自由函数、其次类型类字典。
- **容器体系**——`List`/`Set`/`Map`/`Optional`/`Result` 与 Tuple 族统一实现 `Collection`（`size`/`isEmpty`/`contains`），并提供函数式 API：`map`/`filter`/`flatMap`/`forEach`/`fold`/`any`/`all`/`head`/`last`/`zip`/`reverse`…；`listOf(1,2,3)`（`@list` 变长实参，Kotlin vararg 语义）与 `emptyList()` 便捷构造。
- **Optional 容器**——可能缺失的值用 `Optional[T]`（`Some`/`None`）显式表示，`when` 解构或 `map`/`getOrElse` 等方法取值；没有裸 `null` 穿透类型系统，也不存在 `T?` 语法糖与 `Some` → `T` 隐式解包。
- **纯度受控**——函数默认纯；副作用显式标注 `@impure`，就地修改显式标注 `@mut`（`@mut struct`/`@mut var`/`@ref` 别名语义）；不方便标时可以 `unchecked` 逃逸。上下文（命题、`∴`、守卫）中的表达式必须纯。
- **异步（v2.0）**——`@async` 函数 + `Task { ... }` 并发 + `Channel`/`Flow` 通信；唯一运行时组件是 Channel 小运行时。
- **高阶类型 HKT**——`Functor[F[_]]` kind 形参；kind 在类声明期检查。
- **可读后端**——源到源生成纯 JavaScript，`node` 直接运行；不想生成代码时可用脚本模式（`run-script`）让解释器直接求值顶层语句。

## 快速开始

### 构建编译器

```bat
gradlew.bat fatJar
```

产出 `build/libs/sublimation.jar`（fat jar，含 Kotlin stdlib），随后可脱离 gradle 独立使用：

```bat
java -jar build/libs/sublimation.jar compile hello.subl -o hello.js
node hello.js
```

开发期也可直接跑：

```bat
gradlew.bat run --args="compile hello.subl"
```

### 命令行

```
parse <file.subl>          词法+语法分析，打印语法树
tokens <file.subl>         打印 token 流（含邻接标记）
check <file.subl> [-v]     语义分析（弥散/证明义务/纯度/可变性/impl/when 穷尽）
compile <file.subl> [-o out.js] [-p <processor.subs|dir>]   语义通过后生成 JS
run-script <file.subs|dir>  脚本模式解释执行：顶层语句直接运行（无需 main）
explain <CODE>             打印诊断码含义与修复建议（如 E-TYPE-MISMATCH）
```

`-p` 可多次附加注解处理器（处理器即 `.subs` 脚本，文件名=处理器名=关注的注解）；
`compile/check` 传**目录**时按模块系统入口处理（目录=模块，自动挂载入口下的 `core/`）。

### 语法速览

Hello World（IO 需要 `@impure`）：

```subl
@impure
fun main() {
    print("Hello, Sublimation!")
}
```

`when` 分支用 `=>`，区间穷尽由编译器证明：

```subl
fun double(x: Nat): Nat = x * 2

fun demo(n: Nat)<n ∈ (0..30)>: Nat {
    @mut var r = 0
    when(n in _) {                 // 编译器证明这三个区间覆盖 (0..30)
        (0 .. 10)  => { r = 1 }
        (11 .. 20) => { r = 2 }
        (21 .. 30) => { r = 3 }
    }
    return r
}
```

类型别名（具名 / 参数化 / 泛型头）：

```subl
type Count = Nat

type Opt = Optional[Nat]
fun unwrap(x: Opt): Nat = when(x) { is Some(v) => v None => 0 }

type Box[a] = Optional[a]        // 使用点 Box[Nat] 展开为 Optional[Nat]
```

类型类与容器：

```subl
class Show[T] { fun show(): Str }

@impure
fun main() {
    var xs = listOf(1, 2, 3)
    print(xs.map({ x => x + 1 }).size())     // 3
    print(xs.isEmpty())                      // false
}
```

`unchecked` 逃逸与命题注入：

```subl
fun giveP(): Null <p> { unchecked.axiom[p] }   // 无条件令命题 p 成立
```

## 目录结构

```
├── frontend/                 词法/语法（Lexer/Parser/Ast）
├── compiler/                 语义分析（Types/Checker/WhenCheck/CallCheck）+ JsCodeGen
├── interpreter/              脚本模式解释器
├── language-service/         LSP 语言服务
├── annotationproc/           注解处理器运行时
├── cli/                      命令行入口（fatJar 产出 sublimation.jar）
├── core/                     标准库 core 包（模块子目录：collection/string/math/logic/
│                            io/json/random/regex/time/real/net/async/classic/encoding…）
├── examples/                 示例集（见 examples/说明.md，逐特性 + 小工具）
├── show/                     v4 演示：编译器/（compile + apt → show.js，node 运行）与
│                            解释器/（run-script 脚本模式直接求值）两个自包含目录
├── wiki/                     公开特性 wiki（与 root doc 同步现行语义）
├── root doc/                 根文档·最高权威（设计决策与语义基准，禁止 AI 修改）
├── brand/                    吉祥物 5-HT 形象与表情包（许可见 TRADEMARK.md）
├── playground/ daily/ demo/  实验脚本与历史产物
├── prove_matrix/             命题证明基准
├── boring doc/ idea doc/ docs/   设计草案与构思存档
```

## 测试

```bat
gradlew.bat test
```

当前 **674 项**：词法 / 语法 / 语义 / 类型推理 / 模块树 / 端到端 / 诊断码覆盖面体检。

## 文档

| 文档 | 内容 |
|---|---|
| [wiki/Home.md](wiki/Home.md) | **快速上手**：编译与语法基础 |
| [wiki/](wiki/) | 公开特性：样式规范 / 模式匹配 / 上下文系统 / 结构体与类型类（含类型别名）/ 副作用与可变性 / 空安全 / 容器 / 异步 / 模块系统 / 注解处理器 / 报错与诊断 / 术语表 |
| [root doc/上下文系统.md](root doc/上下文系统.md) | 上下文与定理证明·根文档（最高权威，禁止 AI 修改） |
| [root doc/副作用与可变性控制.md](root doc/副作用与可变性控制.md) | 副作用与可变性根文档 |
| [root doc/空安全系统.md](root doc/空安全系统.md) | 空安全根文档 |
| [root doc/异步.md](root doc/异步.md) | 异步根文档 |
| [root doc/模式匹配.md](root doc/模式匹配.md) | 模式匹配根文档 |
| [examples/说明.md](examples/说明.md) | 示例索引（每个示例头部注释标明预期输出） |

> 现行语义以 `wiki/`（公开特性）与 `root doc/`（根文档·最高权威）为准。
