# Sublimation

声明式设计验证语言（受 Rust / Kotlin 启发）：**写程序 = 写命题 + 写实现**，编译器验证两者一致。

- 程序位 `=>`（lambda / when 分支 / 函数类型 / 零参 lambda）与命题位 `→`（蕴含）双通道
- 独立 `axiom p1, p2, …` 语句（顶层与函数内通用）、`∵ … ∴ q` 手动证明链、Γ 自动闭包与算术通道
- 可变拷贝 **4 条规则**：`@mut` / `@ref` 两注解决定深拷 vs 共享；闭包捕获在创建时深拷
- `unchecked <语句>`：局部检查豁免（机制全栈穿透，行为有据可查）
- 目录即模块；脚本模式（`.subs`）与工程模式（`.subl`）两套执行形态

## 快速开始

```text
./gradlew build                       # 构建（frontend/compiler/interpreter/cli/language-service/annotationproc）
sublimationc check examples/          # 一个目录 = 一个模块，单次全树语义检查
sublimationc run examples/特性巡礼.subl
sublimationc run-script core/logic/list.subl
sublimationc compile core/logic -o logic.js
sublimationc explain E-IMMUT-ASSIGN    # 诊断码含义与修复建议
```

命令全表与处理器流水线见 [`docs/构建与工具链.md`](docs/构建与工具链.md)。

## 文档地图（按深度排序）

| 层 | 目录 | 角色 |
| --- | --- | --- |
| 语言手册 | [`root doc/`](root%20doc/) | 面向语言使用者的 8 篇主题手册 |
| 百科条目 | [`wiki/`](wiki/) | 每个概念一页：**语法 / 语义 / 示例 / 陷阱** 四节固定 |
| 工程文档 | [`docs/`](docs/) | 面向维护者：构建工具链、模块布局、贡献指南、诊断码、安卓标准库设计 |
| 形式化卷 | [`boring doc/`](boring%20doc/) | 形式化文法 + 形式化语义（重写中，旧卷仍在 git 历史） |
| 例子 | [`examples/`](examples/) | 可运行的 `.subl` 示例（`特性巡礼.subl` 为一文件综合门面） |
| 标准库 | [`core/`](core/) | logic / classic / async / mutex / flow 等 `.subl` 实现 |
| 决策溯源 | `idea doc/` | 设计决策记录（本地，不入库） |

## 目录结构

| 目录 | 内容 |
| --- | --- |
| `frontend/` | M1 词法 + M2 语法 + AST |
| `compiler/` | M3 语义检查 + 证明 + 类型推导，M4 JS 代码生成（含 `e2e`） |
| `interpreter/` | 大步求值 + 宿主函数注册；闭包捕获深拷的完整实现侧 |
| `cli/` | 命令入口与诊断码解释表 |
| `language-service/` | LSP 分析 |
| `annotationproc/` | 注解处理器流水线（处理器 = Sublimation 脚本） |
| `core/` | 标准库 `.subl` |
| `brand/` `demo/` `playground/` `show/` | 辅助与演示工程 |

## 测试

```text
./gradlew :compiler:test        # 语义 + e2e 回归（530+ 例）
./gradlew :interpreter:test     # 解释器回归
```

> 增量污染伪装失败（`NoClassDefFoundError` / 莫名越界）时，先 `:interpreter:clean` 再跑。

> `examples/日常-*.subl` 为协作数据（未入库），其 e2e 引用在 pristine checkout 里会失败——已知，非回归。

## 安卓目标线

WebView 桥 + 原生 MD 视图（不使用 Compose）的安卓壳工程路线：注解处理器形态见 [`root doc/注解处理器.md`](root%20doc/)，标准库与工程审查细节将迁入 `docs/`。
