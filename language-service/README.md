# Sublimation 语言服务（LSP）

Sublimation 的 Language Server Protocol 实现，零第三方依赖，仅复用编译器前端
（`lexer` / `parser` / `checker` / `ast`）。通过 stdio + JSON-RPC 2.0 与编辑器通信。

## 模块分层

| 文件 | 职责 |
|------|------|
| `LspProtocol.kt` | LSP 协议数据类型（JSON-RPC + Position/Range/Diagnostic/Hover/Completion/DocumentSymbol/Highlight/SignatureHelp/FoldingRange/TextEdit 等）与位置换算 |
| `LspAnalysis.kt` | 桥接层：每次 didOpen/didChange 重新 tokenize→parse→check，缓存 AST + 诊断；向上暴露诊断、符号索引、hover、definition、completion、references、highlight、signatureHelp、foldingRange、rename、workspaceSymbol |
| `LspServer.kt` | JSON-RPC stdio 服务器：Content-Length 分帧、极简 JSON 解析/序列化（`Json`）、请求/通知分发 |
| `LspExtension.kt` | 扩展点：第三方（如安卓插件）注册「附加 stdlib 目录 + 处理器目录 + 开关」，语言服务据此并入检查/补全/跳转，自身不含任何平台专属硬编码 |
| `LspPlugin.kt` | 插件框架：plugin.json 清单（id/version/minCoreVersion…）+ 插件根目录发现加载 + 极简 JSON 解析（MiniJson） |

## 已支持能力

- **诊断**：`textDocument/publishDiagnostics`（错误/警告/提示/补充 四级映射）
- **悬停** `textDocument/hover`（符号签名 + 关键字文档 + prelude 内建文档）
- **跳转** `textDocument/definition`
- **类型定义跳转** `textDocument/typeDefinition`（struct/enum/class/type 别名）
- **补全** `textDocument/completion`（关键字 snippet + 全符号 + prelude 内建类型/函数/core 标准库符号 + `\xxx` Unicode 符号，如 `\forall`→∀、`\!=`→≠ + 成员上下文：`.`/`::` 后按 receiver 类型过滤字段/方法/构造子）
- **大纲** `textDocument/documentSymbol`（层级：顶层声明 → 字段/构造子/参数/方法/局部变量）
- **引用** `textDocument/references`（声明 + 全部 NameRef/字段/构造子引用）
- **高亮** `textDocument/documentHighlight`
- **签名** `textDocument/signatureHelp`（文本扫描定位调用点 + 实参下标）
- **折叠** `textDocument/foldingRange`（花括号配对，跳过字符串/行注释）
- **重命名** `textDocument/prepareRename` + `textDocument/rename`
- **跨文档** `workspace/symbol`
- **语义高亮** `textDocument/semanticTokens/full` + `/range`（关键字/类型/函数/参数/字段/构造子/变量/数字/字符串/运算符 十类；range 增量按区间过滤）
- **格式化** `textDocument/formatting`（注解独占一行、4 空格缩进、花括号/分号/逗号空格、注释保真、空块合并）
- **灯泡** `textDocument/codeAction`（全角符号 → 半角，如 `（`→`(`）
- **实现跳转** `textDocument/implementation`（光标在类型名 → 所有 `impl ... for 该类型`）
- **内联提示** `textDocument/inlayHint`（调用点的参数名提示，如 `f(1)` → `f(a: 1)`）

## 扩展点（LSP Extension）

第三方插件（如 CandyIDE 的安卓工程支持）通过统一扩展点向语言服务贡献平台能力，语言服务核心不再出现任何平台专属硬编码：

- **接口**（`LspExtension`）：`name`（唯一标识，用于注销与缓存归属）、`stdlibDir`（附加标准库目录，递归收录 `.subl`）、`processorsDir`（注解处理器目录，收录 `.subs`）、`isEnabled()`（是否生效；不生效则一切贡献都不并入）。
- **注册**：宿主侧编程式注册——`LspAnalysis.registerExtension(ext)` / `unregisterExtension(name)` / `registeredExtensions()`；LSP 客户端也可在 `initialize` 的 `initializationOptions.extensions` 里声明（`{name, stdlibDir, processorsDir, enabled}` 数组），服务器启动时自动注册。
- **生命周期**：注册/注销即时生效——下一次 didOpen/didChange 全量重分析时按最新生效集合装配。每个扩展在注册表内按 `name` 拥有独立缓存槽（stdlib 条目 / 符号索引 / 处理器实例），同一 `name` 重复注册会替换旧实例并丢弃其缓存；注销即释放。
- **贡献语义**：生效扩展的 stdlib 条目并入 Checker 输入（与编译链「跑处理器后检查」同构）、`definition` 兜底定位、补全符号；处理器目录按 `@interested` 声明运行，多轮（maxRounds=5）合并/回拆。

## 插件框架（LspPlugin）

在 LspExtension 扩展点之上的**清单形态**插件层（V1）：「一个插件 = 一个目录 + 一份 `plugin.json`」。
安卓/SfW 等平台支持由插件方（如 SfA）产出与维护，语言服务核心不绑定任何平台。

- **清单（plugin.json）**：`id`（唯一标识 = 扩展名）、`version`（独立 semver）、`displayName`、`author`/`description`（可选展示）、
  `minCoreVersion`（语言核心最低版本，宿主据此 gate 不兼容插件）、`stdlibDir`/`processorsDir`（相对插件目录或绝对路径）、
  `enabled`（缺省 true）、`apiVersion`（缺省 1，超过 `CURRENT_API_VERSION` 拒绝）、`contributes`（保留字段，V1 解析但不解释）。
  未知键保留在 `raw` 中向前兼容。
- **加载与发现**：`LspPluginLoader.load(插件根目录, coreVersion?)` 扫描各子目录，按目录名排序（结果确定）；
  清单缺失/非法 JSON/id 空/apiVersion 过高/minCoreVersion 不达标的目录**跳过并记 stderr**（容错降级：一个坏插件不影响其它）。返回
  `LspPluginManifest` + `LspExtension`，经 `LspAnalysis.loadPlugins` 注册。进程外宿主经 LSP `initialize` 的
  `initializationOptions.pluginsDir`（配 `coreVersion`）加载。
- **生命周期与缓存**：加载即注册（同名替换，沿用扩展注册表按 name 的槽缓存；stdlib 条目/符号索引/处理器）；
  注销（`unregisterExtension(id)`）立即丢弃缓存。插件目录内容变更须**重新 `loadPlugins`** 刷新。
- **线程模型**：与 LSP 服务器一致，单线程 stdio 循环内加载/注册/访问；插件贡献是纯数据快照，无并发问题。
- **V1 贡献点（纯数据、无代码执行）**：生效插件的 stdlib 顶层声明 → ① 并入 Checker 输入（与编译链同构）
  ② definition 兜底定位 ③ 补全项 ④ 悬停签名（markdown 代码块）⑤ signatureHelp 签名兜底。
  处理器目录的 `.subs` 一样跑注解处理器引擎（但执行由注解处理器宿主承担，与编译链职责一致）。
- **V2+ 路线（未实现）**：`contributes.diagnosticFilters`（按诊断码/消息过滤，如安卓工程抑制特定告警）、
  插件级 hover 长文档、进程外插件宿主（IPC）。按需求再上，避免过度设计。

清单示例：

```json
{ "id": "android", "version": "1.0.0", "displayName": "Sublimation for Android",
  "minCoreVersion": "1.1", "stdlibDir": "stdlib", "processorsDir": "procs", "enabled": true }
```

## 关键设计

1. **容错分析**：tokenize/parse/check 任一步失败都降级为诊断（E-LEX / E-PARSE / E-CHECK），不阻断其它能力。
2. **全 AST 遍历器**（`walkExpr`/walkStmt`/walkPattern`/walkFunDecl`）：一次性采集「声明」（局部 var / 参数 / 字段 / 构造子 / 方法 / 局部 fun）与「引用」（NameRef / FieldExpr / SafeCallExpr / FunRefExpr / StructCtorExpr / PatCtor），供所有查询复用。
3. **顶层 vs 局部**：`SymbolRef.topLevel` 区分大纲符号与局部符号——大纲只展示顶层，其余仅参与 hover/补全/引用；`SymbolRef.parent` 记录父声明名，用于层级大纲。
4. **零依赖 JSON**：手写递归下降解析器 + 序列化器（`LspServer.Json`），转义规则覆盖引号/反斜杠/控制符/Unicode。
5. **prelude**：载入时注入内建类型（`BASE_TYPES`）+ 内建函数（复用 `builtinPure/builtinimpure` 真实签名）+ 可选 core 标准库（`test/core`）顶层符号；仅参与补全/悬停，不参与 definition（避免虚假位置）。

## 已知限制（后续轮次）

- 符号索引为**名字匹配**（未做作用域消歧、类型感知）；同名符号取首个命中。
- 跨模块（MultiModule）诊断/符号解析尚未接入，当前每文档独立分析。
- 成员补全的 receiver 类型来自 **checker 类型推导器**（`Checkers.inferredVars`：局部 var/解构绑定）+ 参数/字段显式类型；但链式 `foo().bar()` 的**表达式中间类型**尚未导出（checker 判定留痕未全量留存）。
- signatureHelp 未处理字符串内 `(`；inlayHint 的类型提示仅覆盖 `var` 声明（未做函数返回类型/参数位内联）。
- rename 仅当前文档（未跨文件）。
- 未接入增量同步（当前全量同步）。

## 运行

```bat
java -cp build/libs/sublimation.jar sugared.functor.lsp.LspServerKt
```

编辑器侧配置示例（VS Code LSP 客户端）：

```json
{ "command": "java", "args": ["-cp", "sublimation.jar", "sugared.functor.lsp.LspServerKt"] }
```

## 测试

`src/test/kotlin/sugared/functor/lsp/LspAnalysisTest.kt` —— 覆盖诊断转换、JSON 往返、符号索引、
hover/definition/completion、references/highlight、signatureHelp、foldingRange、rename、workspaceSymbol、
扩展点注册/注销与生效切换、插件清单解析/加载与贡献点（补全/悬停/签名兜底）。
