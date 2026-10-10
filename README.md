# Sublimation（凝华）

<p align="center">
  <img src="doc/icon.png" alt="Sublimation（凝华）" width="200"/>
</p>

**把类型系统与程序逻辑融合的编程语言。Kotlin 手写编译器，零第三方依赖，源码直接编译为 JavaScript。**

语言名取自物理现象「凝华」——气态不经液态直接成固态。正如本文的命题不经运行时、直接在编译期析出为程序的静态保证。

> ✅ v2.0 完成：空安全 / 可变性控制 / 异步 + HKT 高阶类型，350 项测试全绿

---

## 核心特性

- **上下文弥散（命题系统）**——函数类型携带前置/后置命题，调用前必须满足什么、调用后能保证什么，写进类型本身。用户写普通代码，编译器自动推理，命题像气体一样在作用域里弥散，无需显式传递。
- **目录即模块（P0）**——一个目录 = 一个模块，`<模块名>.settings` 显式挂载；根目录下的 `stdlib/` 隐式挂载给所有模块。
- **类型类**——Haskell 的拼写、Rust 的机制：`class Show[T] { fun show(): Str }` + `impl Show for Grade { ... }`，按实参类型解析型类字典分发。
- **真模式匹配 `when`**——解构、嵌套构造子、守卫、穷尽性判定（Java 式 switch 已全盘废弃）。
- **纯度受控**——函数默认且只能为纯；禁止调用非纯内建、禁止写外部变量，除非用 `unchecked` 逃逸。`unchecked.axiom[q]` 直接令命题 q 成立。
- **一切运算符皆函数**——`1 + 1` 等价于 `+(1,1)`，二元函数即可中缀；支持自定义运算符与两参函数中缀调用。
- **数值类型**——`Nat`（自然数）/ `Int`（整数）/ `Rat`（有理数），不丢精度的实数/复数类型留名待续。
- **零运行时库**——源到源生成纯 JavaScript，`node` 直接运行。
- **递归停机判定（P9）**——纯函数自递归须满足结构子项约束，否则编译期拒绝（`unchecked` 逃逸放行）。
- **空安全（v2.0）**——`T?` 可空类型 + `?:` Elvis + `?T` 类型测 + `is T` 智能转换自动收窄。
- **可变性控制（v2.0）**——`@mut` 显式可变 + `immutable<T>` 不可变命题 + `untouch<param>` 形参只读保证。
- **异步（v2.0）**——`@async` 函数 + `Task` 并发 + `Channel` 通信 + 自动 `await` 挂起点；`main` 天然 async。
- **高阶类型 HKT（v2.0）**——`[F[_]]` kind 形参、`impl Functor for List[a]` 字典占位、`F[A]~List[Nat]` kind 合一（详见《高阶类型.md》）。

## 快速开始

### 构建编译器

```bat
gradlew.bat fatJar
```

产出 `build/libs/sublimation.jar`（fat jar，含 Kotlin stdlib），随后可脱离 gradle 独立使用：

```bat
java -jar build/libs/sublimation.jar check hello.subl
```

开发期也可直接跑：

```bat
gradlew.bat run --args="check hello.subl"
```

### 命令行

```
parse <file.subl>        词法+语法分析，打印语法树
tokens <file.subl>       打印 token 流
check <file.subl> [-v]   语义分析（弥散/证明义务/纯度/可变性/impl/when 穷尽）
compile <file.subl> [-o out.js]   语义通过后生成 JS（默认打印到标准输出）
check/compile <目录> [-o out.js]  目录模式 = 模块系统入口（P0）
```

### 示例

命题弥散——调用后 p 自动进入作用域，编译器检索到并通过：

```subl
fun giveP(): Null <p> { unchecked.axiom[p] }   // 下文给出命题 p
fun takeP()<p>: Null {}                         // 上文要求命题 p

fun main() {
    giveP()          // 调用后 p 弥散进作用域
    takeP()          // 编译期在作用域中检索到 p，通过
}
```

模式匹配 + 守卫 + 类型类：

```subl
enum Grade { A(), B(), C(), D() }

fun gradeOf(n: Rat): Grade = when(n) {
    _ if n >= 90.0 -> A()
    _ if n >= 80.0 -> B()
    _ if n >= 70.0 -> C()
    _ -> D()
}

class Show[T] { fun show(): Str }
impl Show for Grade {
    fun show(): Str = when(self) {
        A() -> "优秀"
        B() -> "良好"
        C() -> "及格"
        D() -> "挂科"
        _ -> "?"
    }
}

@unpure fun main() {
    print(gradeOf(95.0).show())
}
```

## 目录结构

```
├── src/main/kotlin/          编译器本体（Kotlin）
│   ├── sugared/functor/lexer/     M1 词法
│   ├── sugared/functor/parser/    M2 语法
│   ├── sugared/functor/checker/   M3 语义（弥散/合一/模块树/类型类）
│   ├── sugared/functor/codegen/   M4 JS 代码生成
│   └── sugared/functor/ast/       AST 定义
├── src/test/kotlin/          350 项测试（8 个测试类）
├── stdlib/                   标准库（.subl 自举：List/Set/Map/Result）
├── examples/                 示例集（按特性拆分的单文件示例，中文文件名 + 头部注预期输出，见 examples/说明.md；含 模块系统/ 多模块）
├── show/                     v2.0 应用示例：读 students.json → 统计成绩（自包含，双击 run.bat）
└── doc/                      设计文档（语言简介/语法/模块系统/空安全/异步/HKT 等）
```

## 演示：`show/`

`show/` 是一个**自包含**的 v2.0 应用示例包（含编译器 fat jar）：读入 `students.json`（学生姓名 + 语数英成绩）→ 纯 .subl JSON parser 解析 → 统计每人总分/平均分、班级各科平均分、最高/最低总分。拷走整个目录即可对外演示：

```
show/
├── run.bat          双击：编译 show 目录 → show.js，运行并写 show.log
├── show.subl        主程序（readFile → 内建 JSON 解析 → 统计 → 输出）
├── students.json    数据文件（班级/教师/学生语数英成绩）
├── stdlib/          隐式挂载的标准库
└── sublimation.jar  编译器 fat jar
```

```bat
show\run.bat          # 编译 + 运行，产物 show.js + show.log
show\run.bat run      # 仅运行现有的 show.js
```

## 测试

```bat
gradlew.bat test --rerun-tasks
```

当前 **350 项全绿**（8 个测试类）：词法 / 语法 / 语义 / 类型推理 / 模块树 / 端到端 / 诊断码覆盖面体检。

## 文档

| 文档 | 内容 |
|---|---|
| [doc/语言简介.md](doc/语言简介.md) | 定位、语言立场、编译管线、当前进度 |
| [doc/快速上手.md](doc/快速上手.md) | 新人向心智模型 |
| [doc/语言语法.md](doc/语言语法.md) | 详细文法 |
| [doc/模块系统.md](doc/模块系统.md) | 目录即模块、挂载、可见性 |
| [doc/上下文系统.md](doc/上下文系统.md) | 命题弥散、智能转换泛化 |
| [doc/形式化规范.md](doc/形式化规范.md) | 语言核心与已实现语义的规范 |
| [doc/空安全系统.md](doc/空安全系统.md) | v2.0 空安全（T?/?:/>:/?T） |
| [doc/副作用与可变性控制.md](doc/副作用与可变性控制.md) | 纯度规范与 @mut/immutable/untouch |
| [doc/异步.md](doc/异步.md) | v2.0 异步（@async/Task/Channel） |
| [doc/高阶类型.md](doc/高阶类型.md) | v2.0 HKT（kind/字典分发/kind 合一） |
| [doc/样式规范.md](doc/样式规范.md) | 代码风格与书写规范 |
| [doc/术语表.md](doc/术语表.md) | 专门术语收拢 |

> 内部文档（决策日志《草案思路》、阶段蓝图《v1.0 补全计划》、P11 压测报告等）位于 `doc2/`，不随公共仓库发布。