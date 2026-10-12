# Sublimation（凝华）

<p align="center">
  <img src="brand/5-HT/语言图标.png" alt="Sublimation（凝华）语言图标" width="170"/>
</p>

**把类型系统与程序逻辑融合的编程语言。Kotlin 手写编译器，零第三方依赖，源码直接编译为 JavaScript。**

语言名取自物理现象「凝华」——气态不经液态直接成固态。正如本文的命题不经运行时、直接在编译期析出为程序的静态保证。

> ✅ v3.0：上下文系统 / 定理函数与定理库 / 容器与类型类体系，452 项测试全绿

---

## 吉祥物：芙芙莎明（5-HT）

<table>
<tr>
<td width="140" valign="top">
<img src="brand/5-HT/表情包/灵感.jpg" alt="芙芙莎明 · 灵感" width="120"/>
</td>
<td valign="top">
<strong>芙芙莎明（5-HT）</strong>是 Sublimation项目 的吉祥物：桃发黑眼圈、粗框眼镜、头顶 ∀ 发光发夹，胸前挂着 λ 工牌与苯环；函数式、定理证明、药理学三元素合一。完整形象设定与表情包见 <code>brand/5-HT</code>。其使用许可与项目本体不同，详见 <code>TRADEMARK.md</code> 。
</td>
</tr>
</table>

## 核心特性

- **上下文弥散与自动证明**——函数类型携带前置/后置命题（伴生命题）：调用前必须满足什么、调用后能保证什么，写进类型本身。`∵` 从事实推导、`+∵` 验证上下文中成立后注入事实、`∴` 求证断言；`when(n in _)` 的区间穷尽由编译器收集关键表达式（如 0,10,11,20,21,30）并应用定理自动证明，无需手工补丁。> **手动证明与异步专题已封存**（路线重规划）：证明语句失败降级为警告、不阻塞主流程；异步代码维持现状。`doc/根文档-上下文系统与定理证明器.md` 为最高权威（未经作者许可不得修改）。
- **定理函数**——`@theorem` 显式标注的定理（无副作用、不修改参数、返回 `Null`）在**编译器擦除**，只供证明位（`∴` 合一/前件），值级调用报 `E-THEOREM-VALUE-CALL`（封存期：降级为警告，程序风格允许 @theorem 公理像代码一样调用、调用即注入其下文，可驱动后续前件）；**只有 `@theorem` 标注的函数才是定理函数**——非 @theorem 的函数（即使纯+Null）不是定理、不进定理空间，代码照常生成。`∵`/`∴` 语句是调用定理的语法糖：定理函数要**定理名+上文**（前件）、无下文；`∵`/`∴` 要**上文+下文**（目标）、不要定理名。预置定理库 `core.logic` 与 `core.logic.classic`（均已 `@theorem`、擦除）。
- **数学记号一等公民**——源码直接写 `∈` `≥` `≤`、`∀` `∃`、`n ∈ (0..30)`；每个自然数恒有自然命题 `n ≥ 0`；结构相同的表达式自动自反（`1 + 2 = 1 + 2` 可证，`1 + 2 = 3` 不自动证）。
- **目录即模块**——一个目录 = 一个模块，`<模块名>.settings` 显式挂载（`mount core/logic`）；根目录下的 `stdlib/` 隐式挂载；跨模块定理搜索沿可见路径展开。
- **类型类与扩展方法**——`class Show[T] { fun show(): Str }` + `impl Show for Grade { ... }` 按实参类型解析字典；`impl List[a] { ... }`（没有实现类型类的 impl）为扩展方法，成员脱糖为带 `self` 首参的自由函数，承担多形态（同作用域同名函数仅允许一个）。方法糖 `x.f()` 优先同名自由函数、其次类型类字典。
- **容器体系**——`List`/`Set`/`Map`/`Optional`/`Result` 与 Tuple 族统一实现 `Collection`（`size`/`isEmpty`/`contains`），并提供函数式 API：`map`/`filter`/`flatMap`/`forEach`/`fold`/`any`/`all`/`head`/`last`/`zip`/`reverse`…；`listOf(1,2,3)`（`@tulpe` 可变元组）与 `emptyList()` 便捷构造。
- **空安全**——可空值用 `Optional[T]`（`Some`/`None`）显式表示，配合安全调用与自动解构；没有裸 `null` 穿透类型系统。
- **纯度受控**——函数默认纯；副作用显式标注 `@impure`，就地修改显式标注 `@mut`；不方便标时可以 `unchecked` 逃逸。上下文（命题、`∴`、守卫）中的表达式必须纯。
- **真模式匹配 `when`**——解构、嵌套构造子、守卫、穷尽性判定，含区间模板 `when(n in _) { (0..10) -> ... }`（Java 式 switch 已全盘废弃）。
- **异步（v2.0）**——`@async` 函数 + `Task { ... }` 并发 + `Channel` 通信；唯一运行时组件是 Channel 小运行时。
- **高阶类型 HKT**——`Functor[F[_]]` kind 形参；kind 在类声明期检查。
- **可读后端**——源到源生成纯 JavaScript，`node` 直接运行。

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
parse <file.subl>        词法+语法分析，打印语法树
tokens <file.subl>       打印 token 流
check <file.subl> [-v]   语义分析（上下文弥散/证明义务/纯度/可变性/impl/when 穷尽）
compile <file.subl> [-o out.js]   语义通过后生成 JS（默认打印到标准输出）
check/compile <目录> [-o out.js]  目录模式 = 模块系统入口
```

### 示例

Hello World（IO 需要 `@impure`）：

```subl
@impure
fun main() {
    print("Hello, Sublimation!")
}
```

纯函数默认；局部可变绑定用 `@mut var`；区间穷尽由编译器证明：

```subl
fun double(x: Nat): Nat = x * 2

fun demo(n: Nat)<n ∈ (0..30)>: Nat {
    +∵ 15 ∈ (0..30)   // ∵/∵+ 非公理注入：必须上下文中成立，否则 E-CANNOT-PROVE
    @mut var r = 0
    when(n in _) {              // 编译器证明这三个区间覆盖 (0..30)
        (0 .. 10)  -> { r = 1 }
        (11 .. 20) -> { r = 2 }
        (21 .. 30) -> { r = 3 }
    }
    return r
}
```

类型类与容器：

```subl
class Show[T] { fun show(): Str }

@impure
fun main() {
    var xs = listOf(1, 2, 3)
    print(xs.map({ x -> x + 1 }).size())     // 2
    print(xs.isEmpty())                      // false
}
```

`unchecked` 逃逸与命题注入：

```subl
fun giveP(): Null <p> { unchecked.axiom[p] }   // 无条件令命题 p 成立
```

## 目录结构

```
├── src/main/kotlin/          编译器本体（Kotlin）
│   └── sugared/functor/           lexer/ parser/ checker/ codegen/ ast/
├── src/test/kotlin/          452 项测试（词法/语法/语义/类型推理/模块树/端到端/诊断）
├── stdlib/                   标准库（.subl 自举：List/Set/Map/Optional/Result/Tuple +
│                                        Collection 类型类 + core.logic / core.logic.classic 定理库）
├── examples/                 示例集（见 examples/说明.md）
├── show/                     v2.0 应用示例：读 students.json → 统计成绩（自包含）
├── doc/                      设计文档（设计哲学/术语表/定理函数/上下文/类型类与结构体…）
└── wiki/                     公开特性文档（Home=快速上手 + 空安全/上下文/类型类/副作用/高阶类型/证明…）
```

## 测试

```bat
gradlew.bat test --rerun-tasks
```

当前 **452 项全绿**：词法 / 语法 / 语义 / 类型推理 / 模块树 / 端到端 / 诊断码覆盖面体检。

## 文档

| 文档 | 内容 |
|---|---|
| [wiki/Home.md](wiki/Home.md) | **快速上手**：编译与语法基础 |
| [wiki/](wiki/) | 公开特性：空安全 / 上下文与自动证明 / 类型类与扩展方法 / 容器 / 副作用与可变性 / 高阶类型 / 手动证明 / 模块系统 / 数学记号与定理库 |
| [doc/设计哲学.md](doc/设计哲学.md) | 语言设计取舍 |
| [doc/伴生命题与命题系统.md](doc/伴生命题与命题系统.md) | 上下文系统核心 |
| [doc/定理函数.md](doc/定理函数.md) | 定理判据与定理库 |
| [doc/类型类与结构体.md](doc/类型类与结构体.md) | 类型类 / 扩展方法 / 结构体 / 容器 |
| [doc/术语表.md](doc/术语表.md) | 术语收拢 |
| [doc/纯函数与副作用.md](doc/纯函数与副作用.md) | 纯度与标注纪律 |

> 历史设计草案（v2 空安全/异步/副作用等）保留在 `doc/` 同目录，顶部均标注状态，现行语义以 `wiki/` 与上表 `doc/` 文档为准；内部决策日志位于 `doc2/`，不随公共仓库发布。