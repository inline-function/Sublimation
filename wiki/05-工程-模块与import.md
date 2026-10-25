# 模块与 import（目录即模块）

> wiki/05-工程-模块与import · 主题域：模块系统——目录即模块（根 mount 全树）、`.settings` 挂载、跨模块限定引用、跨模块构造子（Ok/Err/Some/Nil）、MultiModule 检查、脚本模式互不共享与工程模式单 main、core 标准库布局
> 事实来源：_langfacts.md §2/§7/§8；compiler/src/main/kotlin/sugared/functor/checker/ModuleTree.kt、MultiModule.kt、Checker.kt、CallCheck.kt；frontend/src/main/kotlin/sugared/functor/parser/Parser.kt；cli/src/main/kotlin/sugared/functor/Main.kt；interpreter/src/main/kotlin/sugared/functor/interpreter/Interpreter.kt；core/ 下全部 *.settings 与子包布局；examples/模块系统/；compiler/src/test/.../ModuleTreeTest.kt、ModuleE2ETest.kt；本会话对 build/libs/sublimation.jar 的实测探针（java -jar … check/compile/run-script + node 实跑，验后已删）

本语言没有源码级的 import 关键字——解析器里不存在 import 产生式（Parser.kt:631-680 的 parseStmt 与 parseEntry 均无该分支，全仓检索无 ImportStmt）。跨模块组合由三件事承担：**目录结构**（目录即模块）、**模块的 `.settings` 文件里的 `mount` 指令**（这就是「import」的实际落点）、以及**限定引用 `a.b.c`**。本条目按这一实际形态书写；_langfacts.md §2 把 import 列为语句种类与代码不符（标 **待核**，见陷阱 1）。

## 语法

### 工程布局：目录即模块

- 每个目录（含编译入口的根 `/`）是一个模块；目录内所有 `.subl` 文件属于该模块，同模块文件按序合并为一个 FileAst、共享同一份符号表（ModuleTree.kt:13-16；MultiModule.kt:44-56）
- 模块的绝对路径 = 相对根的 `/` 连接路径，根模块为空串 `""`（唯一键）；根模块符号在 JS 产物里**不加模块前缀**（用户拍板 Q4a）
- 扫描递归进行，忽略隐藏目录与 `build`、`out`、`target`、`node_modules`、`.git`、`.gradle`、`.idea`、`.kotlin`（ModuleTree.kt:43-46）；只收集 `.subl` 文件，`.subs` 是脚本模式的文件、不参与工程扫描
- 工程模式顶层只能写声明（fun/struct/enum/class/impl/type/def）与 `axiom` 语句，执行代码必须放进 `fun main()`（Parser.kt:195-208）；main 须零形参（E-MAIN-PARAMS）

### settings 文件：挂载指令

每个**非根**模块可以在自己目录下放一个与目录同名的 `<模块名>.settings` 文件（模块名 = 目录名，ModuleTree.kt:104-113）：

```text
# 每行一条指令；空行与 # 注释忽略（ModuleTree.kt:118-142）
mount <路径> [as <别名>]
```

- 路径有两种等价写法：斜杠形 `core/math` 与**点分完整包名形** `core.math`（点分形在解析期归一为斜杠形；按约定目录名不含点）
- 挂载目标是从根起的绝对路径，如 `mount core.collection`
- 根模块**没有** settings 文件（P0 限制，ModuleTree.kt:105）

### 跨模块引用的书写

```subl
tool.triple(4)                    // 限定调用：名字链 a.b.c（FieldExpr 链）
core.collection.isOk(r)           // 多段限定：先走可见子模块/挂载，再取符号
var p : c.Point = c.Point(1, 2)   // 限定类型同理（resolveTypeModule，ModuleTree.kt:445-452）
Result::Ok(42)                    // 同名构造子歧义时用枚举路径消歧（H-AMBIGUOUS-CTOR）
```

裸名（`isOk`、`Ok`、`Nil` …）不做限定也能解析——Checker 会在当前模块及其可见模块集合里回退查找（详见语义节）。可见集合是**计算出来的**（I-20），不是查表的布尔（ModuleTree.kt:17），这一点常被误读成「有默认 import」。

### CLI 形态

```text
check   <文件.subl | 目录>            # 目录 = 工程：根 / 扫全树，跑 MultiModule 语义检查
compile <文件.subl | 目录> -o out.js  # 同上，检查通过后合并生成单个 JS
run-script <文件.subs … | 目录>       # 脚本模式：每个 .subs 独立执行（无需 main）
explain <诊断码>                      # 查 E-CIRCULAR-MOUNT / E-UNBOUND-NAME 等含义
```

目录模式只支持 `check`/`compile`（Main.kt:242-247）；`run-script` 给目录且目录无 `.subs` 时，退化为「按 .subl 工程（fun main 入口）解释执行」（Main.kt:30、372-413）——注意这与编译器目录模式不是一回事（陷阱 2）。

### core 标准库布局

`core` 是统一标准库的**包目录**：只含子包目录、不含直接 `.subl` 源文件才算标准库包（`isStdlibPackage`，ModuleTree.kt:91-94）；用户自己的 `core/`（目录下直接有源文件）保持普通模块语义。编译入口下候选路径为 `core` 或 `test/core`（ModuleTree.kt:51-56）。子包（16 个，本仓实测）：

| 包 | 内容要点 | 包内 settings |
|---|---|---|
| collection | List/Optional/Result/Set/Map/Tuple/typeclass | 仅注释（无挂载，无跨包依赖） |
| math / number | 自然数算术 / Num 类型类 | math 为叶子包，无挂载 |
| logic / classic | 命题工具 / 经典逻辑 | `mount core.math` |
| string / json / regex / encoding | 字符串、JSON、正则、编码 | `mount core.collection` |
| io / net / time / random | IO、网络、时间、随机 | `mount core.collection` |
| real | Real 高精度小数 | `mount core.collection` |
| async | Task/Flow/Channel/Mutex/atomic | `mount core.collection` |

标准库内部互引走 settings 显式挂载（如 core/string/string.settings：`mount core.collection`）；用户工程对 core 的引用走**隐式挂载**（语义节）。

## 语义

### 可见性：四条规则 + 一个默认导入

`visibleChild(from, name)` 按「自身子目录 → 自身及祖先的直接挂载（别名优先）→ 传递挂载」三段解析（ModuleTree.kt:365-392），由此涌现出四条规则：

1. **父→子可见**：根模块可以直接 `tool.triple(4)`（子目录是一层可见名）
2. **子→父不可见**：子模块引用父模块函数报 E-UNBOUND-NAME（实测报文：没找到函数「top」）
3. **兄弟默认不可见**：兄弟 a、b 互引必须 mount（实测报文：没找到「b」）
4. **挂载向下流淌**：祖先的 mount 对所有后代可见，后代的 mount 不对祖先可见（ModuleTree.kt:315-318）

挂载的传递闭包是「收编即全收」（用户拍板 Q2）：mount 一个模块，它自身及其后续挂载可达的模块全部进入可见集合（`mountReach`，ModuleTree.kt:292-304）。特别地 `mount core.math` 会把前缀包 `core` 也隐式挂为本地名（`expandPackageMounts`，ModuleTree.kt:223-245），故限定调用 `core.math.gcd(...)` 与按目录名的 `math.gcd(...)` 等价。

裸自由函数另有一套更窄的集合 `bareVisibleOf`（ModuleTree.kt:339-356）：本模块 + 自身及祖先**挂载**的传递可达（**不含**子树），再加上 **core 默认导入**——编译入口下存在 core 标准库包时，任何模块对 core 根及全部子包的裸名可见（Kotlin 默认导入语义）。所以根模块裸调子模块函数不可见、但裸调 `size`/`isOk` 可见。

### core 隐式挂载

编译入口下识别到 core 标准库包后，`injectCoreMounts`（ModuleTree.kt:254-267）为所有非 core 自身的模块追加 `mount core` 及其全部子包——core 包内符号（List/Ok/Err 裸名、Functor/Foldable 类型类）跨模块查找因此整包可达；注入的挂载不经本地名冲突检查（子目录名优先于挂载名）。

### MultiModule 检查流程

`MultiModule(root)`（MultiModule.kt:23-67）驱动整个工程检查：

1. **解析**：每个 .subl → AST，同模块文件按序合并为一个 FileAst
2. **阶段 A**：全部模块先收集符号到全域 `allSymbols`（供交叉引用与类型可解析性）
3. **阶段 B**：类型可解析性 + impl 完整性 + pure 事实（此时全树符号已齐）
4. **阶段 C**：声明体与顶层语句检查
5. **入口唯一性**：整个工程有且仅有一个零参 `fun main()`——多个模块声明 main 报 `E-DUP-DECL`（决策 70；MultiModule.kt:128-143）
6. **诊断吸收**：各模块 Checker 的诊断并入总袋；补充级诊断只保留根模块的（core 等挂载模块内部的推导细节视为噪声，`dropSupplementsOutside("")`）

注解处理器插在阶段 A 与 B/C 之间：任一模块被改写 → 全树重建 Checker 并统一重跑 B/C（MultiModule.kt:85-119）。

### 挂载校验与诊断

ModuleTreeDriver 在 init 时校验（ModuleTree.kt:196-218）：

| 诊断 | 触发条件（实测确认） |
|---|---|
| E-PARSE-SETTINGS | settings 文件语法错（非 mount 指令、缺路径、非法 as 形态） |
| E-UNBOUND-NAME | 挂载了不存在的模块路径；或引用了不可见模块的名字/函数/构造子 |
| E-DUP-DECL | 本地名冲突：子目录名与挂载别名/挂载目标名撞车；或多个模块声明 main |
| E-CIRCULAR-MOUNT | 挂载链成环（DFS 检测，只报首个环，ModuleTree.kt:270-290） |

### 跨模块构造子与限定解析

- 限定引用 `a.b.c`：`resolveQChain`（ModuleTree.kt:421-442）把名字链的一段段经 `visibleChild` 走模块树，末段在目标模块符号表里按 fun/ctor/struct/enum/class/method 定性（`qualifyIn`）；链首若是局部变量则回落值层（O2 字段优先）
- 裸构造子：本地符号表未命中时，`CallCheck`（CallCheck.kt:170-181）沿 `visiblePaths` 逐模块查 ctors/structs——所以**裸 Ok/Err/Some/Nil 的可见性取决于对应模块是否被（隐式或显式）挂载**：Ok/Err 在 core.collection.result，Nil 在 core.collection.list，Some/None 是 prelude 内建（Checker.kt:181-187 每模块注册 Optional）
- 内建 `Some`/`None`/`null`/`true`/`false` 在 JS 里处于根作用域，不加模块前缀（CallCheck.kt:632-634），避免 core 模块内 `Some(x)` 被错生成为 `core_collection__Some`

### 合并编译（M8）

`generateJs()`（MultiModule.kt:150-197）把全部模块合成**一个 JS 文件**：顶层名字按 `mangleModule(absPath)` 加前缀（trim('/') → '/'→'_' → 非法字符→'_'，ModuleTree.kt:146-147），根模块无前缀、非根为 `路径__名字`（`tool__triple`、`core_collection__isOk`，实测确认）；构造子形如 `data__Result__Ok`，同名唯一时再导出短名 `data__Ok`。入口调用即调用带前缀的 main 函数（JsCodeGen.kt:407、413）——main 可位于任意模块，但全树唯一。单文件编译另有入口 `generateSingle`（MultiModule.kt:204-228），不带模块前缀。

### 脚本模式语义

- 每个 `.subs` 独立执行：顶层语句直接跑、无需 main、**互不共享用户声明**（每个脚本独立注入 host/stdlib，Interpreter.kt:126-133）
- `run-script <目录>`：目录下每个 `.subs` 各自独立运行（不合并，Interpreter.kt:139-148）；无 `.subs` 时退化为工程模式 `runDir`——**只合并目录顶层** `.subl`（`dir.listFiles` 非递归，Interpreter.kt:120-124），以单个 main 为入口
- 脚本目录与工程目录是两条路：脚本互不可见是设计；工程的模块树合并是编译器目录模式（`check`/`compile`）的能力，解释器 `runDir` 不做全树扫描

## 示例

### 例 1：父→子限定调用（examples/模块系统，实测通过）

```text
模块系统/
├─ 主.subl
└─ tool/工具函数.subl
```

```subl
// tool/工具函数.subl：子模块 tool；父→子可见，子→父不可见
fun triple(x: Nat): Nat = x * 3
fun square(x: Nat): Nat = x * x
```

```subl
// 主.subl（根模块）
fun main() {
    unchecked print(tool.triple(4))   // 12：跨模块限定调用
    unchecked print(tool.square(3))   // 9
}
```

```text
java -jar build/libs/sublimation.jar compile examples/模块系统 -o out.js && node out.js
# 实测：12 / 9；JS 中根 main 无前缀，子模块为 tool__triple / tool__square
```

### 例 2：兄弟模块经 settings 挂载（含别名）

```text
probe/
├─ main.subl
├─ main.settings     # mount data（别名写法：mount data as d）
├─ data/data.subl
└─ tool/tool.subl
```

```subl
// data/data.subl
enum Result[T, E] { Ok(T) Err(E) }
fun ok42(): Result[Nat, Str] = Ok(42)
fun isOk[r, e](r: Result[r, e]): Bool = when(r) { is Ok(_) => true; is Err(_) => false }
```

```subl
// main.subl：限定调用其函数与构造子（实测通过）
fun main() {
    unchecked print(data.isOk(data.Ok(7)))   // true；JS: data__Ok = data__Result__Ok
}
```

### 例 3：core 默认导入与裸构造子

编译入口下有 core 标准库包（本仓即 `sublimation/core`）时，无需任何 settings：

```subl
// main.subl（工程根模块）：裸 Ok 与裸 isOk 都来自 core.collection
fun main() {
    unchecked print(isOk(Ok(7)))                      // true（默认导入 + 裸构造子回退）
    unchecked print(core.collection.isOk(Err("x")))   // false（全限定写法等价）
}
```

```text
# 实测：check / compile 均通过；JS 生成 core_collection__isOk、core_collection__Result__Ok
# 反之，编译入口下没有 core 包时，裸 Ok(1) 报 E-UNBOUND-NAME：没找到函数「Ok」
```

### 例 4：点分包名与斜杠路径等价

```text
# 某 .settings：两行等价（ModuleTree.kt:115-117；ModuleTreeTest.kt:251-262）
mount core.math
mount core/math
```

多段挂载还会把前缀包隐式挂上：`mount core.math` 之后 `core` 也是本地可见模块名，且 `core.math.gcd(...)` 与 `math.gcd(...)` 等价。

### 例 5：脚本模式互不共享

```subl
// one.subs
fun hi(): Str = "hello"
unchecked print(hi())     // hello
```

```subl
// two.subs：看不到 one.subs 的声明
unchecked print(hi())     // E-UNBOUND-NAME：没找到函数「hi」
```

```text
# 实测：run-script one.subs two.subs
# [脚本] one.subs → hello；[脚本] two.subs → 脚本类型检查失败
```

## 陷阱

1. **没有源码 import 语句**。在工程顶层写 `import …` 会以「顶层只能写声明」被解析期拒绝（Parser.kt:208-212）；_langfacts.md §2 把 import 列为语句种类，与解析器实现不符（**待核**）。跨模块组合只能用 mount + 限定引用。
2. **`run-script <工程目录>` 不是 MultiModule**。解释器工程模式（`runDir`）只合并目录顶层 `.subl`、不递归子目录：对 examples/模块系统 跑 run-script 会报没找到「tool」（实测）。多模块工程要经 `compile <目录> -o out.js` + node；`check <目录>` 才做全树挂载/可见性检查。
3. **兄弟/子→父默认不可见**。可见性只向下（父→子、祖先挂载→后代）；兄弟间、子对父必须经 settings mount，否则 E-UNBOUND-NAME。
4. **根模块没有 settings**（P0 限制）。根无法 mount 任意模块；要复用非子树的模块，须把双方置于共同子目录下、由上层模块挂载，或依赖 core 默认导入（**待核**：根模块挂载能力是否计划补齐）。
5. **core 默认导入依赖编译入口布局**。`check`/`compile` 的入口目录下须存在 core 包目录（或 test/core）才生效；对临时小工程裸 `Ok`/`Nil` 会报 E-UNBOUND-NAME（实测），此时需自挂载或把 core 纳入入口。
6. **settings 文件名必须等于目录名**。`loadSettings` 按 `<目录名>.settings` 查找（ModuleTree.kt:106）；core/number 的实际文件是 `num.settings`，与其目录名 number 不匹配，该文件里写的挂载不生效（目前只有注释，实际靠 core 隐式挂载兜底）。
7. **main 全树唯一**。两个模块各写 `fun main()` → E-DUP-DECL（「多个模块声明了入口 main：, sub」——根模块名渲染为空串，读诊断时注意）。
8. **挂载成环只报第一个**。E-CIRCULAR-MOUNT 用 DFS 且全局只报一次（`cycleReported`，ModuleTree.kt:280-284）；修完一个环后要复查剩余 settings。
9. **同名构造子跨模块取末者**（P0 限制，CallCheck.kt:633）。多个枚举声明同名构造子时编译器给 H-AMBIGUOUS-CTOR 提示，用 `Result::Ok` 形态消歧。
10. **忽略目录里的 .subl 被静默跳过**。build/out/target/node_modules 及隐藏目录不进模块树；把源码放进这些目录不会报错，但符号不可见。
11. **E-UNBOUND-NAME 文案按查找类别浮动**：模块名报「拼写错了？还是忘了声明，或其所在模块没 mount？」，函数/构造子报「没找到函数「X」…（explain E-UNBOUND-NAME 看详细）」，且其内置解释明确提示跨模块构造子（如 Ok/Err）要检查是否 mount 了对应模块——定位时先分清缺的是模块挂载还是声明本身。
