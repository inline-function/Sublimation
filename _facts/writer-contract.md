# 重写代理契约（所有写作代理共用 preamble）

## 语言状态（唯一准绳）
- 修正案后状态：`->` 全位非法（lexer 出 ARROW 仅供诊断）；命题蕴含只用 Unicode `→`（IMPLIES）；程序位（lambda、when 分支、函数类型、零参 lambda）只用 `=>`；等价 `<->`；区间 `..`
- `unchecked.axiom[p]` 已废除 → 独立 `axiom p1, p2, ...` 语句（顶层与函数内通用，返回 Null）
- 线性作用域 `%{}` 已删除（LinearScope/LinearBlockExpr 不存在）
- 可变拷贝 4 条规则：拷贝 ⟺ （目标含 @mut 且（不带 @ref 或源纯））或（源含 @mut 且目标纯）或（源带 @ref 且目标不带 @ref）；深拷贝含函数闭包捕获（JS 侧 __dc 函数穿透为既定边界）
- `unchecked <语句>` 关键字形态保留，uncheckedDepth 为机制实现细节
- 零参 lambda：`{ => 7 }`；隐式单参 `{ x + 1 }`（_ 引用形参）

## 事实来源优先级
1. `_facts/_langfacts.md`（语言事实表）
2. 源代码（引用 file:line）
3. 旧文档（仅作结构参照；凡与 1/2 冲突一律以 1/2 为准并在草稿中按新事实写）
4. 不确定 → 在文中显式标 `待核`，绝不推测或编造

## 体例
- UTF-8 无 BOM；标题层级从 # 开始；禁止抄录大段旧文
- 所有代码示例必须用当前文法（=>/→/axiom），且是可运行的形态
- 每篇卷首三行：卷名 / 主题域 / 事实来源声明（本表 + 引用的源文件清单）
- boring doc 用判断式 LaTeX：$$\frac{前提}{结论} \quad (\text{规则名})$$，多前提 \qquad，中文 \text{...}
- wiki 百科条目固定四节：语法 / 语义 / 示例 / 陷阱
- 不要 git 操作；不要触碰 _facts 之外的他人正在编辑的文件（root doc、language-service、annotationproc）
- 输出写到自己被指定的目标文件；读代码文件时若文件 > 1500 行用 offset/limit 分段，别整读
