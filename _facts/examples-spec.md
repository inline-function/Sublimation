# examples/ 重写规格（22 个文件，每个须通过 `check` 无错、用当前文法 =>/→/axiom）

通用要求：文件头三行注释（文件名、覆盖特性、预期 stdout）；只用修正案后文法；每个例子可独立解释执行（或属模块组合）；避免死代码演示（未定义行为不得出现）。

| # | 文件 | 覆盖点 |
|---|------|--------|
| 1 | 特性巡礼.subl | 综合门面：fun+when+类型+命题+输出 |
| 2 | 变量与绑定.subl | var/不可变/元组解构/字符串插值 |
| 3 | 值与字面量.subl | Nat/Int/Rat/Str/Bool/元组/转义/插值 |
| 4 | 函数与调用形式.subl | 括号调用/中缀糖 a f b/符号前缀 <(/运算符前缀 +(/尾随 lambda/默认参 |
| 5 | lambda与闭包.subl | 两形态/零参 { => x }/隐式参 _/捕获深拷贝演示 |
| 6 | 类型系统.subl | 类型别名 type F[a]/联合 Nat\|Str/泛型/类型推断 |
| 7 | 枚举与结构体.subl | enum/struct/@mut 字段/构造子 |
| 8 | 模式匹配.subl | 构造子 arm/is 解构/守卫/模板 when/命题 when/区间 arm (0..59) => |
| 9 | Optional与Result.subl | map/getOrElse/unwrapOr/when 解构/Err 传播 |
| 10 | 集合标准库.subl | List/Set/Map+fold/map/filter/sortBy/zip/toSet |
| 11 | 命题基础.subl | 原子/蕴含 →/等价 <->/<p ∨ ¬p>/量词 ∀∃ |
| 12 | 手动证明.subl | ∵∴ 链/多前提逗号/by [q]/不可证报错演示 |
| 13 | 公理化.subl | axiom 语句(顶层+函数内)/∀ 实例化/弥散 |
| 14 | 自动证明.subl | 算术通道(≤/=/传递)/激进模式命题维 |
| 15 | 上下文弥散.subl | 上文/下文/$/$n/companion |
| 16 | 可变性控制.subl | @mut/@ref/拷贝 4 规则逐例/别名 vs 深拷 |
| 17 | 副作用与unchecked.subl | @impure/print/unchecked 语句局部豁免 |
| 18 | 异步与并发.subl | Task/Flow/mutex（core 支持范围内） |
| 19 | 类型类.subl | typeclass 声明/impl/约束式泛型 |
| 20 | 脚本模式.subl | 顶层语句脚本（.subs 风格；宿主调用） |
| 21a | 模块系统/主.subl + 21b | 模块系统/tool/工具.subl | import/跨文件函数/模块组合 |
| 22 | JSON内建.subl | JSON 值构造与访问（stdlib 范围内） |

验证：写完后统一跑 `check examples/`（目录即模块）+ 抽样 run-script；错误由我统一修或回退代理。
