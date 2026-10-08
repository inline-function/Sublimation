# Sublimation（凝华）

**把类型系统与程序逻辑融合的编程语言。Kotlin 手写编译器，零第三方依赖，源码直接编译为 JavaScript。**

语言名取自物理现象「凝华」——气态不经液态直接成固态。正如本文的命题不经运行时、直接在编译期析出为程序的静态保证。

> ✅ v1.0 完成：全部 P0–P11 落盘，285 项测试全绿

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
├── src/test/kotlin/          285 项测试
├── stdlib/                   标准库（.subl 自举：List/Result）
├── examples/                 P11 压测示例（json-parser 241 行、v1-showcase 巡礼等）
├── show/                     特性展示目录（自包含，双击 run.bat 即可演示）
└── doc/                      设计文档（语言简介/语法/模块系统/任务链等）
```

## 演示：`show/`

`show/` 是一个**自包含**的特性展示包（含编译器 fat jar），拷走整个目录即可对外演示：

```
show/
├── run.bat          双击：编译 show 目录 → show.js，运行并写 show.log
├── show.subl        展示源码（模块挂载/集合高阶/类型类/命题/命名参数）
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

当前 **285 项全绿**：词法 / 语法 / 语义 / 类型推理 / 模块树 / 端到端 / 诊断码覆盖面体检。

## 文档

| 文档 | 内容 |
|---|---|
| [doc/语言简介.md](doc/语言简介.md) | 定位、核心特性、模块系统、v1.0 进度 |
| [doc/快速上手.md](doc/快速上手.md) | 新人向心智模型 |
| [doc/语言语法.md](doc/语言语法.md) | 详细文法 |
| [doc/模块系统.md](doc/模块系统.md) | P0 模块系统设计 |
| [doc/工程规范.md](doc/工程规范.md) | 不变量 I-1 ~ I-24 |
| [doc/v1-压测报告.md](doc/v1-压测报告.md) | P11 验收经验 |