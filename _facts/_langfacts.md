# 语言事实表（_langfacts.md）

来源：本会话对实现的直接编辑与审计（修正案落实提交 9beb63b 之后）；行号为近似定位，以代码语义为准。

## 1 词法（frontend/.../lexer/Lexer.kt，224 行）
- token 种类：IDENT/SYMBOL/INT/FLOAT/STR/操作符/标点/EOF；反引号名 `任意字符` → IDENT（不含换行与反引号）；Unicode 名（中文_α）→ IDENT
- 单字符算符：+ - * / % ^ = < > & | ! ；多字符：`<->` IFF、`=>` DARROW、`==` EQ、`!=` NEQ、`<=` LE、`>=` GE、`->` ARROW（**非法，仅供诊断**）、`∃!` EXISTS1、`..` RANGE、`::` COLON2、`+∵` PLUS_BECAUSE
- Unicode 逻辑单 token：∀ FORALL、∃ EXISTS、∧ LAND、∨ LOR、¬ LNOT、→ IMPLIES、↔ EQUIV、⊤ TOP、⊥ BOT、∵ BECAUSE、∴ THEREFORE
- 空白：空格/制表/换行均跳过（**换行不是 token**，语句边界由“中缀糖同行+前后空格”等规则涌现）；precededBySpace 记录在 token 上
- `$n` 粘连为单 token（$ 后紧跟数字）；`$` 返回值符号；字符串支持 `\n \t \'` 转义与 `$name`/`${expr}` 插值
- 注释：`//` 行注释（ Lexer.kt:77 附近跳过到换行）

## 2 文法（frontend/.../parser/Parser.kt，1724 行）
- 入口：parseFile → 工程模式（parseEntry：fun/type/enum/struct/impl/axiom 顶层级）；脚本模式（脚本语句直接顶层）
- 语句种类：fun/var/axiom（顶层+函数内）/unchecked/when/if/∀引入/∃语句/∵∴/return/by/typedef/impl/enum/struct/import/表达式语句
- 表达式层级（全部左结合、**无优先级**，想要优先级必须加括号）：parseExpr 中缀循环（binops 集合 ≈ Parser.kt:50-56：算术+比较+逻辑 Unicode+IMPLIES+EQUIV+IFF+`=`+`:`+`in`	TokenName…）；parseUnary（一元 -/¬/!）；初等项
- 中缀糖 D-5：`a f b`（f 前后均带空格、同行、右操作数为合法表达式起始，排除 `->` `,` `}` `]` `;` `)` `=` `return`）→ CallExpr(f, [a, b])
- 符号前缀调用：`<(1,2)`（symbolCallStart = LT/GT/EQ/NEQ/LE/GE，后随 LPAREN）→ SymbolCallExpr，只取位置实参（Parser.kt:1353）
- 运算符前缀调用：`+(1,2)`（opPrefixKinds = PLUS/STAR/SLASH/PERCENT/AMP/PIPE/IFF，后随 LPAREN）→ 普通 CallExpr（Parser.kt:1359）
- lambda（G-34）：`{ 参数, … => 体 }`；零参 `{ => 7 }`；隐式单参 `{ x + 1 }`（_ 引用形参）；解构参数 `(a, b)`；尾随 lambda `f(a) {…}` → 追加末位实参
- 零参函数类型：`() => Null`；函数类型 `(T) => U`、`[T](T) => T`
- when：值 when（模式/条件 arm `p => body`、守卫 `if`）；模板 when `when(t in _)`；命题 when `when[p ∨ q] { p => {…} }`（parser 有 parseWhenProp）；分支位的蕴含须加括号（stopArrow 机制：stopIdents、空格 LPAREN 也停）
- 量词：`∀n. body`、`∃ x = t . P`（引入）、`∃ x . P { }`（消去）；ASCII 同义 forall/exists/exists1（Parser.kt:1349）
- 角表（后缀 `<…>`）：实例化/显式供给/上下文约束；`¬前空格` 约束（如 `<p>` 后不接空格才作后缀）；angle 上下文中 `>` 作中缀须前空格且有操作数（Parser.kt:1028）
- axiom 语句：`axiom p1, p2, …`（canStartAxiomProp 允许 IDENT/SYMBOL/INT/FLOAT/STR/LPAREN/LNOT/BANG/TOP/BOT/MINUS/FORALL/EXISTS/EXISTS1 起始；旧 `unchecked.axiom[p]` 报错引导）
- unchecked 语句：`unchecked <语句>` → UncheckedStmt（parseUnchecked Parser.kt:796）；`unchecked.axiom[…]` 与 `unchecked.<其它>` 均报错
- by：`expr by [q]` → ByExpr（命题证明依据）

## 3 类型系统（Checker.kt + TypeInfer.kt + Ast.kt）
- 类型形态：`T`（不可变）、`@mut T`（唯一所有权可变）、`@ref T`（共享只读视图）、`@ref @mut T`（共享可变=别名）；函数类型 `(T) => U` / 零参 `() => U`；类型参数 `[T]`；类型别名 `type F[a] = T[a]`（带泛型头）；联合 `Nat | Str`；类型类 typeclass（约束式泛型）；Optional/Result 为标准库枚举
- 枚举/结构体：`enum E { A(), B(T, E) }`；`@mut struct T(@mut n: Nat)`（字段可 @mut）；构造 `Cons(h, t)`
- isMut/isRef 工具于 Checker；赋值闸 E-IMMUT-ASSIGN 仅当 !isMut(lhs) 且 uncheckedDepth==0（Checker.kt:612）
- Optional Some 视图已删除（修正案）；`o.getOr...` 系列为方法/标准库形态

## 4 命题与证明（Prop.kt + Checker.kt + ArithProve.kt）
- 规范化：PAtom 承载原子（0 元 `p`、参数化 `valid<7>`、比较 `$ == 7` → PAtom("==",[$,7])）；BinExpr 归一 ASCII⇄Unicode（`∈`→`in`、`<=`↔`≤`、`!=`↔`≠`）在命题层
- Γ：allFacts 全域事实；分离 MP 闭包（`p ∧ (p → q)` 饱和产 q）；量词 ∀ 实例化（命题池全局供给）
- axiom 语句注入对应作用域；`∵ … ∴ q` 证明义务（E-CANNOT-PROVE / E-PREMISE-UNPROVEN / W-PROP-UNBOUND）；`by [q]` 直接供给
- 算术通道 ArithProve：差分图（`a ≤ b`→边）、常量折叠、等式链代换（`= 双向边 + 传递`）
- 激进模式（CLI 标志）：命题维实例化（`∀p` 的 p 分派命题池穷举）

## 5 上下文系统
- Frame/FunType：函数检查期局部帧；上文 `<p>`（调用方须供给）/下文 `<$ = 7>`（体内兑现后弥散进作用域）；`$` 返回值、`$n` 第 n 形参（1 起，仅前件/后件位）
- 作用域弥散：下文命题调用后进入作用域供后续消费；companion 变量机制；排除集控制扩散范围

## 6 效应与可变性（Evaluator + Checker + JsCodeGen）
- **拷贝 4 条规则**（binderNeedsCopy/bindCopy 同一谓词）：拷贝 ⟺ （目标含 @mut 且（不带 @ref 或源纯））或（源含 @mut 且目标纯）或（源带 @ref 且目标不带 @ref）；例：`@mut T ← T` 深拷、`@mut T ← @mut T` 深拷、`@ref @mut T ← @mut T` 别名共享（`b.n = 1` 后 `a.n` 也变）、`@ref T ← T` 共享只读、`T ← @ref T` 深拷
- 函数闭包捕获的变量在 lambda 创建时**深拷贝**（解释器：ClosureFun.copyCapture + copiedFrame 沿父链逐值拷；JS 侧 `__dc` 对函数**穿透**为既定边界）
- unchecked 机制：UncheckedStmt → uncheckedDepth++（Checker.kt:767）；闸门：E-IMMUT-ASSIGN（:612）、pure 传播（:777/:790/:798 及 :1112/:1151/:1158/:1167）、CallCheck 的 Any 准入（:485）、impure 调用闸（:747）、async-in-sync 闸（:769）；解释器直接执行 inner（Evaluator.kt:194）；JS 生成注 `// unchecked` 后透传（JsCodeGen.kt:822）
- 注解效果（行为层）：@impure（容许副作用调用）、@mut/@ref（类型层所有权）、@theorem（证明义务函数）、@classic/@simp、@root（原生内置声明）、@atomic、@android/@view（安卓注解处理器识别）
- 异步：core 下 async/mutex/flow 标准库（Task 冷流、runBoth/runSeq、withMutex、collectAll）

## 7 求值与代码生成
- 解释器：大步求值 Evaluator（924 行）；ScriptValue 值模型（Int/Str/结构/函数/Null…）；宿主函数注册；print/printable 输出；panic/unwrapOr 错误路径
- JS 代码生成：JsCodeGen（1797 行）；模块拼接；lambda 翻译；`__dc` 深拷边界；命题以断言形态落地
- 脚本模式：.subs 每文件独立执行（互不共享、无需 main）；工程模式 = 目录即模块（根 / 扫全树）

## 8 模块与工具链
- CLI（Main.kt 402 行）：`check <文件|目录>`（parse+语义检查；目录=模块全树）、`compile <文件|目录> -o <out>`（+JS 生成）、`run`（subl 工程解释执行）、`run-script <路径|目录>`（.subs 脚本）；`-p <处理器>`、`-A key=value`、`-v`（诊断+supplement）、aggressive 标志（激进证明）
- 注解处理器（annotationproc）：AnnotationScanner 扫描 + 改写流水线（AndroidViewEdits 等）；@allMut 等 APT 引入注解先于 B/C 检查跑
- 标准库 core/：logic（list/set/map/optional/result/typeclass/real…）、classic、async、mutex、flow 等 .subl
- 诊断体系：E-（错误）/W-（警告）/D-（提示）三族；cli 有全量 explain 表；关键码如 E-IMMUT-ASSIGN、E-CANNOT-PROVE、E-PREMISE-UNPROVEN、W-PROP-UNBOUND、E-MAIN-PARAMS、E-RETURN-OUT-OF-BLOCK 等
- 测试规模：frontend 65、compiler 536、interpreter 52、cli/language-service 各若干；examples 由 ParserTest/CheckerTest/E2E 引用

## 9 待补（抽取代理若回报则覆盖本表）
- 精确行号复核、诊断码全表（~100 条）、JsCodeGen 细节约定、标准库逐函数签名、LspAnalysis 行为（当前他人正在重写本文件）
