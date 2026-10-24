package sugared.functor

import sugared.functor.ast.AstPrinter
import sugared.functor.annotationproc.ProcessorPipeline
import sugared.functor.annotationproc.BuiltinProcessors
import sugared.functor.ast.tokenLine
import sugared.functor.checker.MultiModule
import sugared.functor.checker.checkFile
import sugared.functor.checker.generateSingle
import sugared.functor.lexer.Lexer
import sugared.functor.interpreter.InterpreterError
import sugared.functor.interpreter.Interpreter
import sugared.functor.parser.parseSource
import sugared.functor.parser.parseSourceWithNotes
import java.io.File
import kotlin.system.exitProcess

const val USAGE = """sublimationc — Sublimation 编译器（M1 词法 + M2 语法 + M3 语义 + M4 代码生成）
用法:
  parse <file.subl>        词法+语法分析，打印语法树
  tokens <file.subl>       打印 token 流（含邻接标记）
  check <file.subl>        语义分析（弥散/证明义务/纯度/可变性/impl/when 穷尽），诊断分级输出
  check <file.subl> -v     同时输出补充级诊断（类型推导结果等）
  -p <processor.subs|dir>  附加注解处理器（可多次）；目录装载其全部 .subs
  -A key=value             传给注解处理器的编译参数（可多次；安卓 APT 用它收工具路径）

            处理器即 Sublimation 脚本（文件名=处理器名=关注的注解），编译时由解释器解释执行
  compile <file.subl> [-o out.js] [-p ...]   语义通过后生成 JS（默认打印到标准输出）
  run-script <file.subs> [<file2.subs> ...]  脚本模式解释执行：顶层语句直接运行（无需 main）；多条脚本互不共享
  run-script <dir>            运行目录下所有 .subs 脚本（每个独立运行）；无 .subs 时按 .subl 工程(main)执行
  --global-inference         run-script 可选旗标：开启全局类型推导（省略类型/可变性/效应标注时自动填充；
             优先级：显式标注 > 构造点 > 使用点 > 默认 Any；脚本模式专属，编译模式不受影响）
  explain <CODE>           打印诊断码的含义与修复建议（如 E-TYPE-MISMATCH / W-NON-EXHAUSTIVE）
  compile <project-dir> --prover=aggressive   激进模式：更彻底的搜索（可能很慢甚至不停机），
             出错前先尽力证出（健全性底线不变——绝不猜测；Ctrl-C 中断并生成诊断报告）
  --prover=aggressive       激进证明开关（只作用于 compile/check；§3 不做中间档）
  --prove-verbose          提高进度输出频率
  --prove-dump=<路径>      诊断报告路径（默认 sublimation-prover-dump.md，仅中断时写）
  --prove-interval=<2s/500ms> 进度节流间隔（默认 5 秒）
"""

/** 激进模式旗标解析（idea doc/激进模式.md §6） */
private data class ProveFlags(
    val aggressive: Boolean, val verbose: Boolean, val intervalMs: Long, val dumpPath: String?
)

private fun parseProveFlags(args: Array<String>): ProveFlags {
    val aggressive = args.any { it == "--prover=aggressive" }
    val verbose = args.contains("--prove-verbose")
    val dump = args.firstOrNull { it.startsWith("--prove-dump=") }?.substringAfter("=")
    val interval = args.firstOrNull { it.startsWith("--prove-interval=") }?.let { parseIntervalMs(it.substringAfter("=")) }
        ?: 5_000L
    return ProveFlags(aggressive, verbose, interval, dump)
}

private fun parseIntervalMs(s: String): Long {
    val t = s.trim()
    val n: () -> Long? = { t.dropLast(1).toLongOrNull() }
    return when {
        t.endsWith("ms") -> (t.dropLast(2).toLongOrNull() ?: 5_000L)
        t.endsWith("s") -> (n() ?: 5L) * 1_000
        t.endsWith("m") -> (n() ?: 5L) * 60_000
        else -> t.toLongOrNull() ?: 5_000L
    }
}

/** 报错增强（2026-10-11）：诊断码解释表——`explain <CODE>` 子命令读取 */
/** 报错增强（新手友好）：诊断码解释表——`explain <CODE>` 子命令读取 */
val EXPLAIN: Map<String, String> = mapOf(
    "E-TYPE-CONFLICT" to "全局类型推导的构造点类型冲突。—— 同一个参数/字段在多个构造点给出了不同的具体类型（比如 f(1) 又 f(\"s\")、A(1) 又 A(\"x\")）。冲突就是承诺失败：检查各处使用是否一致，或在该参数/字段上显式标注类型。",
    "E-TYPE-MISMATCH" to "类型不匹配。—— 你给的值的类型跟期望的不一样。检查函数声明里参数/返回值的类型标注，确保你用对了。例如函数要 Nat 你给了 Str。",
    "E-UNBOUND-NAME" to "名字 `xxx` 没定义。—— 拼写错了？忘了先 `var xxx = …` 声明？或者它在别的模块里，需要先 `mount 模块名` 才能用。如果是跨模块构造子（如 Ok/Err），检查是否 mount 了对应模块。",
    "E-ARITY" to "实参个数不对。—— 调函数时传的参数个数跟声明的不一样（多了或少了）。检查函数声明和调用处。",
    "E-NAMED-ARG" to "命名实参冲突。—— 同一个参数既按位置传了又按名字传了，或者函数压根没有这个参数名。检查参数名拼写。",
    "E-DUP-DECL" to "重复声明。—— 名字 `xxx` 在这个模块里声明了不止一次。改名，或者把两份声明合并。",
    "E-MISSING-RETURN" to "缺 return。—— 块体函数（用 `{ … }` 的）必须显式写 `return`。如果只需要一个值，用表达式体 `= 值`（不带花括号）就行。",
    "E-RETURN-TYPE" to "return 的类型跟声明不符。—— `return` 后面表达式的类型，跟函数声明里写的返回类型不一样。两边对齐。",
    "E-FUN-NO-BODY" to "函数缺函数体。—— 函数声明了但没有实现。只有编译器内建的名字才允许无体（用 `@root` 声明）。如果不想自己实现，检查是否拼错了内建名字。",
    "D-AXIOM-POST" to "补充：函数标了 @axiom，下文签名即公理。—— 编译器直接信任，不做自动证明；若下文与实际行为不符需你自己负责。",
    "D-BTS" to "补充：因为-所以语句成立。—— ∵ 注入的事实支撑 ∴ 结论，该结论已注入上下文可供后续使用。",
    "D-WHEN-PROP" to "补充：when[p ∨ q] 分类讨论得出了所有分支共有的结论。—— 这些结论已注入上下文。",
    "D-STRATEGY" to "补充：by 策略证出了目标。—— 你写的 @策略名 成功证明了这个命题。",
    "W-PROP-UNBOUND" to "分类讨论的析取前提不在上下文中。—— when[p] 语句要求先注入或证出析取式（如 p ∨ q），否则分类讨论无据。用 axiom p ∨ q 或前置证明注入。",
    "W-WHEN-NO-COMMON" to "when[p ∨ q] 各分支没有共同结论。—— 分类讨论语句的意义在于各支得出同一结论；若各支结论不同，该语句不产生可用事实。",
    "E-CANNOT-PROVE" to "编译器证不出这个命题。—— ∴ 的目标在当前上下文里证不出。在前面用 ∵ 注入事实、换用 axiom 显式声明公理，或检查命题是否写错。",
    "E-PREMISE-UNPROVEN" to "前提条件没证明。—— 你写的 ∵ 前提编译器证不出；前提必须是上下文已知的事实。先证明它，或用 axiom 显式声明公理。",
    "E-CTX-UNPROVEN" to "调用函数值前须证明其上下文命题。—— 该函数值携带上下文条件，调用前必须先证出它。用 ∵ 注入事实或换用普通函数。",
    "E-STRATEGY-FAILED" to "by 策略无法证明目标。—— 你指定的 @策略 未能证出该命题。检查策略名是否正确，或改用其他策略/手动分解。",
    "E-PROCESSOR-LOAD" to "注解处理器加载失败。—— 处理器脚本加载或执行报错，详细信息见附带的错误消息。检查 .subs 脚本语法。",
    "H-PROCESSOR-SCRIPT" to "提示：注解处理器脚本输出消息。—— 处理器脚本通过 hint() 主动输出的信息。",
    "D-PROCESSOR-SCRIPT" to "补充：注解处理器脚本输出消息。—— 处理器脚本通过 diag() 主动输出的信息。",
    "E-NON-SEALED-MATCH" to "when 用作表达式但没有兜底。—— 你把 when 的结果赋给了变量，但分支不穷尽，值可能是 Null。补全所有构造子分支，或者加 `else`。",
    "W-NON-EXHAUSTIVE" to "when 可能没走完所有情况。—— 枚举/构造子类型的 when 少了一些分支，运行时可能没分支可走。把剩余构造子补上。如果是区间 when，检查端点是否衔接无缝。",
    "E-PRE-UNPROVEN" to "函数调用的前置条件没证明。—— 函数声明了上文 `<条件>`，调用前必须证明这个条件成立。你可以在调用前用 `+∵ 条件` 注入事实，或者换个调用参数让编译器能自动证出。",
    "E-CONSTRAINT-VIOLATED" to "调用违反了函数的约束表。—— `fun f()<score <= 100>` 的形参比较条件由调用点实参承担；编译器已反证你的实参不满足。换一个满足条件的实参，或放宽声明里的约束。",
    "W-CONSTRAINT-UNPROVEN" to "函数约束表的条件编译器没证出。—— `fun f()<score <= 100>` 形参条件由实参承担；若实参是运行时才确定的变量，编译器证不出来，只给警告。换成字面量或先注入事实可消除。",
    "E-POST-UNPROVEN" to "函数体的后置条件没证明。—— 函数声明了下文 `<\$条件>`，你的函数体必须保证返回值满足这个条件。检查 return 的值，必要时用 `by` 策略辅助证明。",
    "D-AUTO-IMPURE" to "全局类型推导自动标注 @impure。—— 脚本模式下某函数自递归但找不到递减的参数（停机检查失败），已降级为 @impure 放行（用户自担「可能不停」风险）；该函数的效应会向上传播。想精确控制就显式标注返回类型（doc §7.6 逃生舱）。",
    "E-NON-STRUCTURAL-REC" to "纯函数的递归停止性存疑。—— 纯函数自递归时，必须有一个参数在「变小」（比如列表解构：对子列表递归）。如果不是这种模式，加上 `@impure` 允许它（编译器不会检查 @impure 函数的停机性）。",
    "E-IMMUT-ASSIGN" to "给不可变的变量赋值了。—— `xxx` 声明时没标 `@mut`，不允许重新赋值。要么改成 `@mut var xxx = …`，要么换个新变量名。",
    "E-ASSIGN-TARGET" to "赋值目标不是变量。—— 赋值号左边必须是变量名（或可变字段）。检查你是不是把函数调用、表达式写到了左边。",
    "E-NOT-MUT" to "字段修改需要可变视图。—— 你要修改结构体字段 `xxx`，但当前持有的是不可变视图。字段声明加 `@mut`，并且变量类型前加 `@mut T`（如 `var a: @mut T = …`）。",
    "E-IMPURE-REQUIRED" to "参数带 @mut 必须标 @impure。—— 函数的参数类型带有 `@mut T`，说明这函数可能修改传入的可变状态。函数声明必须加 `@impure`。",
    "E-IMPL-INCOMPLETE" to "impl 没实现完整。—— `impl C for T` 必须实现类型类 C 里的全部方法，且签名要一致。检查少了哪个方法，或者方法签名（参数类型/返回类型）是否对得上。",
    "E-NO-INSTANCE" to "类型类实例缺失。—— 你想调用 `.f()`，但类型 `T` 没有实现对应的类型类。写一个 `impl 类型类 for T { … }` 来实现它。",
    "E-IMPURE-CALL" to "纯函数里调了有副作用的函数。—— 纯函数不能调有副作用的函数（如 print/readLine）。要么把外层函数标 `@impure`，要么去掉这个副作用调用。",
    "E-ANY-CALL" to "类型推导信息不足。—— 编译器推导不出这个表达式的具体类型（推导成了 Any）。手动标注类型，例如 `var x: Nat = …`。",
    "E-NEED-ASYNC" to "异步调用需要在异步上下文。—— `readFile` 这类函数只能在 `@async` 标记的函数里用。给外层函数加 `@async`，或者把它放进 `main()`（main 天然是 async）。",
    "E-NOT-STARTED" to "Task 还没启动。—— 在 `await`/`join` 之前要先调 `t.start()` 启动 Task。",
    "E-CHANNEL-CLOSED" to "Channel 已关闭。—— `close()` 之后的 send/receive 无效。把操作移到 close 之前。",
    "E-TUPLE-DESTRUCT" to "元组解构不对。—— 解构的初值不是元组，或者解构的变量个数跟元组元素数不一致。对齐个数。",
    "E-ROOT-NOT-ALLOWED" to "@root 只能用于编译器内建名字。—— 你写的名字不在白名单里，不能用 `@root`。去掉 `@root`，或者检查是不是拼错了内建函数名。",
    "E-DEEP-SATURATION" to "证明义务太复杂，编译器饱和了。—— 这个命题推理太深，自动证明器试完了所有路径都不行。把大结论拆成几个小步骤，先 `∴` 中间结论，再推最终结论。",
    "W-UNUSED" to "声明后从没用过。—— 变量/参数 `xxx` 声明了但哪个地方都没用到。删掉它，或者改名加 `_` 前缀表示故意不用。",
    "W-UNREACHABLE" to "不可达代码。—— return 之后的语句永远执行不到。删掉或上移。",
    "W-DIV-ZERO" to "字面量除以零。—— 编译器检测到 `/0`，检查除数。",
    "H-ROOT-BUILTIN" to "提示：你写的名字是编译器内建名，可以用 `@root` 声明成无体内建函数。",
    "H-FIELD-SHADOW" to "提示：参数名遮蔽了 self 的同名字段。函数体内裸写这个名字时指向参数而不是字段。换个参数名更清楚。",
    "H-PARAM-REF" to "提示：函数体内用 \$数字 引用参数——直接用参数名更可读。",
    "D-TYPE-INFERRED" to "补充：变量类型是编译器推导出来的。你没写类型标注时，编译器自动推断（如 var x = 1 → x 是 Nat）。用 -v 查看所有补充。",
    "D-METHOD-SUGAR" to "补充：方法糖展开。`.f(…)` 是自由函数 `f(o, …)` 的简写，编译器自动把它展开成自由函数调用。",
    "D-AUTO-AWAIT" to "补充：异步调用自动 await。编译器检测到 @async 函数调用，会自动等待它完成（不用写 await）。",
    "D-DEEP-COPY" to "补充：发生了深拷贝。绑定/赋值涉及 @mut 类型时，编译器会自动复制一份，新变量是独立副本（改它不影响源）。",
    "D-NAT-PROP" to "补充：注入了自然命题。Nat 类型的变量自动成立 n ≥ 0，可直接用于自动证明。",
    "D-INFIX" to "补充：中缀糖展开。`a f b` 与 `f(a, b)` 等价，编译器把它转成自由函数调用。",
    "D-TRAILING" to "补充：尾随 lambda。`f(a) { … }` 等价 `f(a, { … })`，lambda 作为最后一个实参传入。",
    "D-BECAUSE-PROVED" to "补充：命题成立、已注入上下文。`+…` 引入的事实会进入后续证明的上下文。",
    "D-THEREFORE" to "补充：命题证明成立。`∴ …` 求证成功，结果注入后续上下文。",
    "D-PLUSBECAUSE-PROVED" to "补充：命题成立、已注入上下文。`+∵ …` 引入的事实会进入后续证明的上下文。",
    "W-CANNOT-PROVE" to "警告：命题没证出来。`∵/∴/+∵` 里的命题在当前已知事实下推不出，检查是不是漏了前提，或换个能自动证的写法。",
    "D-BY" to "补充：用了 `by` 证明策略辅助证明。编译器按你给的策略展开命题。",
    "D-EXIST-INTRO" to "补充：`∃` 引入成立。你给出了满足性质的实例，编译器确认存在性。",
    "D-LOCAL-FUN" to "补充：局部函数展开。`f = { x -> … }` 内部作为自由函数处理（等价 ∀ 引入）。",
    "E-AMBIGUOUS-INSTANCE" to "错误：一个类型对同一方法有多个 impl 实现，编译器分不清用哪个。让它们之间存在父子类型类关系，或删掉多余的 impl。",
    "E-ANY-STRUCT" to "错误：结构体里有 Any 类型成员，编译器推导不出具体类型。手动标注该成员的具体类型。",
    "E-BY-UNBOUND" to "错误：`by` 委托引用了一个不存在的字段。检查字段名，或确认要委托的类型是结构体。",
    "E-DEFAULT-PARAM" to "错误：函数/方法形参不允许默认值。只有 struct 字段支持默认值。",
    "E-DESTRUCT" to "错误：参数解构失败。解构只支持元组或结构体，且分量个数要对上。",
    "E-DUP-FIELD" to "错误：构造结构体时同一个字段传了两次。删掉重复的那个。",
    "E-FIELD-MISSING" to "错误：构造结构体时少了某个字段，且它没有默认值。补上这个字段。",
    "E-FUTURE-FUN" to "错误：引用了尚未定义的函数（前向引用）。把函数定义移到使用之前。",
    "E-IF-CONCLUSION-MISMATCH" to "错误：`if[p]` 的 then 分支和 else 分支推导出的结论不一致。排中律要求两支结论相同。",
    "E-IMPL-EXTRA" to "错误：impl 里实现了一个类型类里没有的方法。删掉它，或用 `@new` 标注这是新增方法。",
    "E-IMPL-MISMATCH" to "错误：impl 方法的签名和类型类声明的不一致。逐项核对参数类型和返回类型。",
    "E-KIND-MISMATCH" to "错误：类型构造子用错了。像 `List` 这种类型必须带类型参数（`List[Nat]`），不能裸用；参数个数也要对。",
    "E-MAIN-PARAMS" to "错误：入口函数 main 不能有形参或类型参数。写成 `fun main() { … }`。",
    "E-NEED-CLASSIC" to "错误：`if[p]` 需要先挂载排中律。在文件里任一声明加 `@classic` 注解即可。",
    "E-NO-METHOD" to "错误：类型上找不到这个方法。检查拼写，或写对应类型的 impl。",
    "E-NO-SELF" to "错误：方法调用缺接收者。写成 `接收者.方法(…)` 或 `方法(接收者, …)`。",
    "E-NON-PATTERN-HKT" to "错误：类型参数嵌套了类型构造子（如 `F[G[Nat]]`）。类型构造子的实参必须是具体类型。",
    "E-PARAM-REF" to "错误：匿名函数参数引用越界。函数只有 N 个形参，你却引用了第 N+1 个（如 '$1', '$2' 等）。",
    "E-PARAM-REF-IN-BODY" to "错误：函数体里用了非法的匿名参数引用。用参数名代替。",
    "E-PROP-AMBIGUOUS" to "错误：命题有歧义，编译器无法唯一确定。给命题加括号或显式类型标注。",
    "E-PROP-UNBOUND" to "错误：前提命题不在当前已知事实里。先用 `∵` 或 `∃` 引入它。",
    "E-QUANT-UNPROVEN" to "错误：`∃` 引入的实例满足不了目标性质。检查实例能否推出目标命题。",
    "E-RETURN-OUTSIDE" to "错误：return 用在了非法位置。return 只能出现在具名函数体或 lambda 体的语句流里。",
    "E-STRUCT-MUT-PROP" to "错误：结构体声明了普惠成立的性质，但引用了 @mut 可变字段，无法保证恒成立。",
    "E-STRUCT-PROP-UNPROVEN" to "错误：结构体的普惠性质在构造时证不出来。检查构造实参是否满足性质。",
    "E-THEOREM-SEARCH-DEPTH" to "错误：定理搜索超过深度限制。把大结论拆成小步骤，逐步证明。",
    "E-TUPLE-ARITY" to "错误：解构的分量个数和元组元素个数不一致。数一下左边变量和右边元素。",
    "E-TYPE-MISSING" to "错误：类型推导不出来，需要显式标注类型。写明类型参数或类型标注。",
    "H-AMBIGUOUS-CTOR" to "提示：构造子名有歧义，多个枚举都有这个构造子。用 `枚举::构造子` 明确指定。",
    "H-EMPTY-DECL" to "提示：这个声明是空的，可以省略花括号写得更简洁。",
    "H-ARITH-PRECEDENCE" to "提示：语言不做运算符优先级（一律左结合）。混用 `+` 和 `*` 这类不同优先级时，实际计算顺序可能和你直觉不同。给想要的子表达式加括号明确本意。",
    "E-LEX" to "词法错误：源码里有编译器不认识的字符/token。检查写错的符号、未闭合的字符串或插值，以及混入的全角标点（如中文括号）。",
    "E-PARSE" to "语法错误：代码不符合语言文法。根据报错位置检查缺失的括号、关键字或表达式。",
    "E-CHECK" to "语义分析内部错误：编译器在类型检查这个文件时抛出了意外异常。这通常是编译器自身的 bug，请保留源码并反馈给开发者。",
    "E-CIRCULAR-MOUNT" to "模块挂载成环：settings 里的 mount 链形成了循环（A 挂载 B，B 又挂载 A）。检查各模块的 `.settings` 文件，把挂载关系改成无环的树状结构。",
    "E-PARSE-SETTINGS" to "settings 文件语法错：模块的 `.settings` 文件只支持 `mount <路径> [as <别名>]` 一行一个指令。检查拼写与格式。",
    "E-PROCESSOR-ERROR" to "注解处理器执行出错：自定义注解处理器在运行时抛出了异常。检查处理器实现的健壮性。",
    "E-PROCESSOR-LOOP" to "注解处理器循环不收敛：处理器反复改写代码超过最大轮数仍未稳定。检查处理器是否在条件不满足时无限自我触发。",
    "E-PROCESSOR-SCRIPT" to "注解处理器脚本主动报错：处理器脚本里调了 processorError(msg)。检查脚本里报错的分支条件——通常是被处理的注解用法不符合处理器预期。",
    "E-PROCESSOR-EDIT" to "注解处理器的结构性编辑源码片段解析失败：脚本调了 setFunBody/prependStmts/addDecl/replaceDecl，但给的源码片段不是合法的函数体/语句/声明。该条编辑被丢弃（其余编辑照常生效），请按报错里的语法信息修正片段。",
    "W-PROCESSOR-SCRIPT" to "注解处理器脚本警告：脚本调了 processorWarn(msg)。这是处理器给作者的提示，不影响编译通过。",
    "W-PROCESSOR-EDIT" to "处理器的注解编辑没命中目标：脚本想给某个结构体/函数/字段加减注解，但按名字找不到目标。检查脚本里的名字是否拼错了（编辑不存在的名字只报警告，其余编辑照常生效）。",
)

fun main(args: Array<String>) {
    if (args.isEmpty()) { System.err.println(USAGE); exitProcess(2) }
    val cmd = args[0]
    // 报错增强：explain <CODE> —— 打印诊断码解释（无需源文件）
    if (cmd == "explain") {
        if (args.size < 2) { System.err.println(USAGE); exitProcess(2) }
        val code = args[1].uppercase()
        val text = EXPLAIN[code]
        if (text == null) {
            System.err.println("[错误] 未收录诊断码 $code（现有：${EXPLAIN.keys.sorted().joinToString(" / ")}）")
            exitProcess(2)
        }
        println("$code — $text")
        return
    }
    if (args.size < 2) { System.err.println(USAGE); exitProcess(2) }
    val verbose = args.contains("-v")
    // 激进模式（idea doc/激进模式.md §6）：--prover=aggressive 只作用于 compile/check
    val pf = parseProveFlags(args)
    // §8.1：Ctrl-C → 置中断位，搜索循环在下一个 checkAbort 抛 ProverInterrupted，由 main 的 catch 写报告 + halt(130)
    if (pf.aggressive) {
        Runtime.getRuntime().addShutdownHook(Thread { sugared.functor.checker.AggressiveRuntime.interrupt() })
    }
    // -p <路径>（可多次）：附加注解处理器脚本（.subs 文件或目录）
    val processorPaths = ArrayList<java.io.File>()
    run {
        val rest = args.toList()
        var i = 1
        while (i < rest.size) {
            if (rest[i] == "-p" || rest[i] == "--processor") {
                if (i + 1 >= rest.size) { System.err.println("[错误] -p 后面要跟处理器路径"); exitProcess(2) }
                val f = java.io.File(rest[i + 1])
                if (!f.exists()) { System.err.println("[错误] 注解处理器路径不存在: ${rest[i + 1]}"); exitProcess(2) }
                processorPaths += f
                i += 2
            } else i++
        }
    }
        // -A key=value / -A key（可多次）：传给注解处理器的编译参数（安卓 APT 用它收 SDK/构建工具路径）
        val processorOptions = LinkedHashMap<String, String>()
        run {
            val rest = args.toList()
            var i = 1
            while (i < rest.size) {
                if (rest[i] == "-A" || rest[i] == "--processor-arg") {
                    if (i + 1 >= rest.size) { System.err.println("[错误] -A 后面要跟 key 或 key=value"); exitProcess(2) }
                    val kv = rest[i + 1]
                    val eq = kv.indexOf("=")
                    if (eq < 0) processorOptions[kv] = "" else processorOptions[kv.substring(0, eq)] = kv.substring(eq + 1)
                    i += 2
                } else i++
            }
        }

    val extraProcessors = ProcessorPipeline.loadProcessorFiles(processorPaths)
    try {
        // 脚本模式：支持多条脚本路径 / 一个目录，每个 .subs 独立执行（互不共享，无需 main）
        if (cmd == "run-script") {
            runScriptCmd(args.drop(1).filter { it != "-v" })
                return
        }
        val pathStr = args.drop(1).firstOrNull { !it.startsWith("-") } ?: args[1]
        val path = File(pathStr)
        if (!path.exists()) { System.err.println("[错误] 文件不存在: ${args[1]}"); exitProcess(2) }
        // P0（M7）：目录即模块——check/compile 接受目录（编译入口 = 根 /，扫全树）
        if (path.isDirectory) {
            if (cmd != "check" && cmd != "compile") {
                System.err.println("[错误] 目录模式仅支持 check / compile（脚本目录请用 run-script）：$cmd")
                exitProcess(2)
            }
            val mm = MultiModule(path, aggressive = pf.aggressive, proveVerbose = pf.verbose,
                proveIntervalMs = pf.intervalMs, proveDumpPath = pf.dumpPath)
            // 注解处理器先于 B/C 检查跑（脚本模式解释执行）：变换后的模块才是检查对象
            //（如 @allMut 引入的 @mut）；-p 追加的处理器也一并装载。processModules 内部统一重跑阶段 A/B/C
            val loadErrs = ProcessorPipeline.loadErrorsToBag(extraProcessors)
            if (loadErrs.hasError) { println(loadErrs.report()); System.err.println("[错误] 注解处理器装载失败"); exitProcess(1) }
            val pbag = mm.processModules(ProcessorPipeline.builtinModuleTransformer(extraProcessors, processorOptions))
            val bag = mm.checkAll()
            bag.absorb(pbag)
            val report = bag.report(includeSupplement = verbose)
            if (report.isNotEmpty()) println(report)
            if (bag.hasError) {
                System.err.println("[错误] 语义检查未通过")
                exitProcess(1)
            }
            if (cmd == "check") {
                println("[完成] 模块检查通过")
            } else {
                val js = mm.generateJs()
                val oi = args.indexOf("-o")
                val targetFile: File? = if (oi >= 0 && oi + 1 < args.size) File(args[oi + 1]) else null
                if (targetFile != null) {
                    targetFile.parentFile?.mkdirs()
                    targetFile.writeText(js)
                    println("[完成] 已生成 ${targetFile.path}（${js.length} 字节）")
                } else print(js)
                // V2 编译后阶段：处理器收尾（安卓 APT 在此构建 APK）；诊断只报告不阻断。
                val postBag = ProcessorPipeline.runPostCompile(
                    BuiltinProcessors.all() + extraProcessors.processors,
                    ProcessorPipeline.PostCompileCtx(
                        finalAst = mm.finalAst(), js = js, outputPath = targetFile, projectDir = path,
                        options = processorOptions,
                    ) { d -> when (d.severity) { sugared.functor.checker.Severity.ERROR -> bag.error(d.code, d.pos, d.message); sugared.functor.checker.Severity.WARN -> bag.warn(d.code, d.pos, d.message); else -> bag.hint(d.code, d.pos, d.message) } },
                )
                val postReport = postBag.report()
                if (postReport.isNotEmpty()) println(postReport)
                if (postBag.hasError) System.err.println("[警告] 注解处理器编译后阶段出错")
            }
            return
        }
        val src = path.readText()
        when (cmd) {
            "parse" -> println(AstPrinter.toTreeString(parseSource(src, path.name)))
            "tokens" -> Lexer(src, path.name).tokenize().forEach { println(tokenLine(it)) }
            "check" -> {
                val pr = parseSourceWithNotes(src, path.name)
                var ast = pr.ast
                // 注解处理器（脚本模式解释执行）先跑：变换后的 AST 才是检查对象（与 compile 同序）
                val (ast2, pbag) = ProcessorPipeline.runBuiltin(ast, extraProcessors)
                if (ast2 !== ast) ast = ast2
                val bag = if (pf.aggressive) checkFile(ast, aggressive = true, proveVerbose = pf.verbose,
                    proveIntervalMs = pf.intervalMs, proveDumpPath = pf.dumpPath) else checkFile(ast)
                for ((p0, m0) in pr.hints) bag.hint("H-ARITH-PRECEDENCE", p0, m0)
                for ((c0, p1, m1) in pr.supplements) bag.supplement(c0, p1, m1)
                bag.absorb(pbag)
                val out = bag.report(includeSupplement = verbose, source = src)   // 报错增强：源码片段上下文
                if (out.isNotEmpty()) println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过"); exitProcess(1) }
                println("[完成] 语义检查通过")
            }
            "compile" -> {
                val pr = parseSourceWithNotes(src, path.name)
                var ast = pr.ast
                // 注解处理器（脚本模式解释执行）先跑：变换后的 AST 才是检查对象（@allMut → @mut 等）
                val (ast2, pbag) = ProcessorPipeline.runBuiltin(ast, extraProcessors, processorOptions)
                if (ast2 !== ast) ast = ast2
                val chk = sugared.functor.checker.Checker(ast,
                    aggressive = pf.aggressive, proveVerbose = pf.verbose,
                    proveIntervalMs = pf.intervalMs, proveDumpPath = pf.dumpPath)
                val bag = chk.run()
                for ((p0, m0) in pr.hints) bag.hint("H-ARITH-PRECEDENCE", p0, m0)
                for ((c0, p1, m1) in pr.supplements) bag.supplement(c0, p1, m1)
                bag.absorb(pbag)
                val out = bag.report(includeSupplement = verbose, source = src)   // 报错增强：源码片段上下文
                if (out.isNotEmpty()) System.err.println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过，拒绝生成 JS"); exitProcess(1) }
                // 处理器改了 AST → 重建带留痕的 Checker；否则沿用原 Checker
                val finalChk = if (ast !== pr.ast) sugared.functor.checker.Checker(ast,
                    aggressive = pf.aggressive, proveVerbose = pf.verbose,
                    proveIntervalMs = pf.intervalMs, proveDumpPath = pf.dumpPath).also { it.run() } else chk
                val js = generateSingle(finalChk, ast)   // 单文件：留痕字段经 compiler 模块公开入口传递（跨模块 internal 边界）
                val oi = args.indexOf("-o")
                if (oi >= 0 && oi + 1 < args.size) {
                    val target = File(args[oi + 1])
                    target.parentFile?.mkdirs()
                    target.writeText(js)
                    println("[完成] 已生成 ${target.path}（${js.length} 字节）")
                } else print(js)
                // V2 编译后阶段：处理器收尾（安卓 APT 在此构建 APK）；诊断只报告不阻断。
                val targetFile: File? = if (oi >= 0 && oi + 1 < args.size) File(args[oi + 1]) else null
                val postBag = ProcessorPipeline.runPostCompile(
                    BuiltinProcessors.all() + extraProcessors.processors,
                    ProcessorPipeline.PostCompileCtx(
                        finalAst = ast, js = js, outputPath = targetFile, projectDir = path.parentFile ?: File("."),
                        options = processorOptions,
                    ) { d -> when (d.severity) { sugared.functor.checker.Severity.ERROR -> bag.error(d.code, d.pos, d.message); sugared.functor.checker.Severity.WARN -> bag.warn(d.code, d.pos, d.message); else -> bag.hint(d.code, d.pos, d.message) } },
                )
                val postReport = postBag.report()
                if (postReport.isNotEmpty()) println(postReport)
                if (postBag.hasError) System.err.println("[警告] 注解处理器编译后阶段出错")

            }
            "run-script" -> {
                // 脚本模式：Checker 类型检查 + 解释器直接求值（§6 执行模型）
                val globalInference = args.any { it == "--global-inference" }
                val interp = Interpreter(globalInference = globalInference)
                interp.run(src, path.name)
                println("[完成] 脚本执行结束")
            }
            else -> { System.err.println(USAGE); exitProcess(2) }
        }
    } catch (e: sugared.functor.checker.ProverInterrupted) {
        // §8.1：Ctrl-C → 诊断报告 + 退出码 130（halt 绕过其余 shutdown 处理，立即退出）
        val s = sugared.functor.checker.AggressiveRuntime.active
        val report = s?.dumpPath ?: "sublimation-prover-dump.md"
        s?.writeReport(report)
        System.err.println("[激进证明] 已中断——诊断报告写入 $report")
        Runtime.getRuntime().halt(130)
    } catch (e: RuntimeException) {
        System.err.println("[错误] ${e.message}")
        exitProcess(1)
    }
}

/** 脚本模式入口：每个参数可以是 .subs 文件或目录；目录下每个 .subs 各自独立运行。
 *  `--global-inference`：开启全局类型推导（idea doc/全局类型推导.md）——省略的类型/可变性/效应自动填充。 */
private fun runScriptCmd(paths: List<String>) {
    val globalInference = paths.any { it == "--global-inference" }
    val targets = paths.filter { it != "--global-inference" }
    if (targets.isEmpty()) { System.err.println(USAGE); exitProcess(2) }
    val interp = Interpreter(globalInference = globalInference)
    var ranAny = false
    for (p in targets) {
        val f = File(p)
        if (!f.exists()) {
            System.err.println("[错误] 路径不存在: " + p)
            exitProcess(2)
        }
        if (f.isDirectory) {
            val subs = interp.subsIn(f)
            if (subs.isNotEmpty()) {
                for (s in subs) {
                    println("[脚本] " + s.name)
                    interp.runScript(s)
                    interp.inferNotes.forEach { n -> println("[提示] [" + n.code + "] " + n.message) }
                    ranAny = true
                }
            } else {
                // 目录下没有 .subs：按 .subl 工程（fun main() 入口）解释执行（daily 等编译程序）
                println("[工程] " + f.path)
                interp.runDir(f)
                ranAny = true
            }
        } else {
            println("[脚本] " + f.name)
            interp.runScript(f)
            interp.inferNotes.forEach { n -> println("[提示] [" + n.code + "] " + n.message) }
            ranAny = true
        }
    }
    if (!ranAny) {
        System.err.println("[错误] 未找到可运行的脚本")
        exitProcess(1)
    }
    println("[完成] 脚本执行结束")
}
