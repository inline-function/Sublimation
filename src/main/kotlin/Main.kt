package sugared.functor

import sugared.functor.ast.AstPrinter
import sugared.functor.ast.tokenLine
import sugared.functor.checker.MultiModule
import sugared.functor.checker.checkFile
import sugared.functor.codegen.JsCodeGen
import sugared.functor.lexer.Lexer
import sugared.functor.parser.parseSource
import java.io.File
import kotlin.system.exitProcess

const val USAGE = """sublimationc — Sublimation 编译器（M1 词法 + M2 语法 + M3 语义 + M4 代码生成）
用法:
  parse <file.subl>        词法+语法分析，打印语法树
  tokens <file.subl>       打印 token 流（含邻接标记）
  check <file.subl>        语义分析（弥散/证明义务/纯度/可变性/impl/when 穷尽），诊断分级输出
  check <file.subl> -v     同时输出补充级诊断（类型推导结果等）
  compile <file.subl> [-o out.js]   语义通过后生成 JS（默认打印到标准输出）
  explain <CODE>           打印诊断码的含义与修复建议（如 E-TYPE-MISMATCH / W-NON-EXHAUSTIVE）
"""

/** 报错增强（2026-10-11）：诊断码解释表——`explain <CODE>` 子命令读取 */
val EXPLAIN: Map<String, String> = mapOf(
    "E-TYPE-MISMATCH" to "类型不匹配：表达式的推导类型与预期的类型不一致。检查函数返回类型、实参类型与标注。",
    "E-UNBOUND-NAME" to "未定义的名字：符号在当前作用域/可见模块中不存在。检查拼写、是否漏了模块前缀（a.b）、局部变量是否在作用域内。",
    "E-ARITY" to "实参个数与形参不符：调用给了过多/过少的实参。",
    "E-NAMED-ARG" to "命名实参冲突：同一参数既按位置又按名字给出，或函数无该名字参数（写函数名？p 或 (p,) 打包元组）。",
    "E-DUP-DECL" to "重复声明：同一名字（函数/结构体/类型类/impl）在本模块声明了多次。",
    "E-MISSING-RETURN" to "块函数未在所有路径 return：Kotlin 语义要求显式返回（表达式体请改用 `= 表达式`）。",
    "E-RETURN-TYPE" to "return 的表达式类型与函数声明的返回类型不符。",
    "E-FUN-NO-BODY" to "函数缺少函数体：只有 @root 白名单内建才允许无体声明（E-ROOT-NOT-ALLOWED 说明该名不在白名单）。",
    "E-NON-SEALED-MATCH" to "作为表达式的 when 没有密封：非穷尽 when 的值恒为 Null。补全分支或加 else。",
    "E-NON-EXHAUSTIVE" to "警告：when 对枚举/构造子主题未穷尽，运行时会落入 else/丢失分支。补全剩余构造子。",
    "E-PRE-UNPROVEN" to "函数调用未证明前件（pre 条件）：需 unchecked.axiom 或供给链证明。",
    "E-POST-UNPROVEN" to "函数体未证明后件（post 条件）：编译器以体末返回值推导 $，检查证明义务。",
    "E-NON-STRUCTURAL-REC" to "递归停止性存疑：纯函数自递归须有至少一个实参是某形参的语法子项（when 解构链）。用 unchecked 放行或改写为结构递归。",
    "E-IMMUT-ASSIGN" to "对不可变变量赋值：声明加 @mut 或在纯度逃逸下赋值（unchecked）。",
    "E-ASSIGN-TARGET" to "赋值目标不是变量：赋值左侧必须是局部变量名。",
    "E-IMPL-INCOMPLETE" to "impl 未实现类型类全部成员（或成员签名不匹配 E-IMPL-MISMATCH / 多实现了 E-IMPL-EXTRA）。",
    "E-NO-INSTANCE" to "类型类实例缺失：该类型的 impl 未声明，或约束字典无法解析。",
    "E-IMPURE-CALL" to "纯上下文调用了非纯函数：@unpure 函数须在 @unpure 函数/unchecked 中调用，或持有 pure<f> 事实。",
    "E-ANY-CALL" to "涉及 Any 的调用推导失效：Any 只作形参类型，请手动标注或改用具体类型（决策 59）。",
    "E-KIND-MISS-MATCH" to "kind 不匹配：类型构造子实参个数与声明不符（HKT [F[_]] 一参、[F[_,_]] 两参）。",
    "E-NEED-ASYNC" to "异步调用需要异步上下文：readFile/readLine/writeFile/appendFile/readDir/sleep 须在 @async 函数/Task 体/main 中调用。",
    "E-NOT-STARTED" to "Task 未启动：t.start() 未调，或 start 之后才能 await/join（查 Task 生命周期）。",
    "E-CHANNEL-CLOSED" to "通道已关闭后操作：close 后的 send/receive 违反纪律（CH-4 单端单次）。",
    "E-TUPLE-VARARG" to "@tuple 形参只收元组字面量：裸变量请先用 (x,) 打包（决策 61）。",
    "E-TUPLE-DESTRUCT" to "元组解构初值不是元组，或分量个数与元组元素数不符（E-TUPLE-ARITY）。",
    "E-ROOT-NOT-ALLOWED" to "@root 白名单外的名字不允许：@root 只用于内建 JS 实现名（见 Types.kt ROOT_WHITELIST）。",
    "E-DEEP-SATURATION" to "命题推理饱和深度超限：证明义务过于复杂，简化为若干小步或加中间 by/axiom。",
    "W-UNUSED" to "变量/参数声明后从未使用（_ 前缀可抑制）：删除或加 _ 前缀。",
    "W-UNREACHABLE" to "不可达代码：return/Nothing 之后的语句不会执行。",
    "W-DIV-ZERO" to "字面量除以零：检查除数为 0/Nat 0 的写法。",
    "H-ROOT-BUILTIN" to "提示：你写的名字在白名单中，可用 @root 声明成无体内建函数。",
    "H-FIELD-SHADOW" to "提示：参数名遮蔽了 self 的同名字段，体内裸用该名将指向参数。",
    "H-PARAM-REF" to "提示：函数体内用 \$n 引用参数——请直接使用参数名。",
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
    val path = File(args[1])
    if (!path.exists()) { System.err.println("[错误] 文件不存在: ${args[1]}"); exitProcess(2) }
    val verbose = args.contains("-v")
    try {
        // P0（M7）：目录即模块——check/compile 接受目录（编译入口 = 根 /，扫全树）
        if (path.isDirectory) {
            if (cmd != "check" && cmd != "compile") {
                System.err.println("[错误] 目录模式仅支持 check / compile：$cmd")
                exitProcess(2)
            }
            val mm = MultiModule(path)
            val bag = mm.checkAll()
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
                if (oi >= 0 && oi + 1 < args.size) {
                    val target = File(args[oi + 1])
                    target.parentFile?.mkdirs()
                    target.writeText(js)
                    println("[完成] 已生成 ${target.path}（${js.length} 字节）")
                } else print(js)
            }
            return
        }
        val src = path.readText()
        when (cmd) {
            "parse" -> println(AstPrinter.toTreeString(parseSource(src, path.name)))
            "tokens" -> Lexer(src, path.name).tokenize().forEach { println(tokenLine(it)) }
            "check" -> {
                val bag = checkFile(parseSource(src, path.name))
                val out = bag.report(includeSupplement = verbose, source = src)   // 报错增强：源码片段上下文
                if (out.isNotEmpty()) println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过"); exitProcess(1) }
                println("[完成] 语义检查通过")
            }
            "compile" -> {
                val ast = parseSource(src, path.name)
                val chk = sugared.functor.checker.Checker(ast)
                val bag = chk.run()
                val out = bag.report(includeSupplement = verbose, source = src)   // 报错增强：源码片段上下文
                if (out.isNotEmpty()) System.err.println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过，拒绝生成 JS"); exitProcess(1) }
                val js = JsCodeGen().generate(ast, chk.dictHits, chk.moduleHits, chk.consHits, chk.dictSubHits, chk.namedArgOrder, chk.methodSugarArgs, chk.asyncAwaitHits, chk.channelUsed, chk.receiveSmartHits, chk.hktKindPosHits)   // 单文件：约束槽/字典实参/命名参数/方法糖留痕同源
                val oi = args.indexOf("-o")
                if (oi >= 0 && oi + 1 < args.size) {
                    val target = File(args[oi + 1])
                    target.parentFile?.mkdirs()
                    target.writeText(js)
                    println("[完成] 已生成 ${target.path}（${js.length} 字节）")
                } else print(js)
            }
            else -> { System.err.println(USAGE); exitProcess(2) }
        }
    } catch (e: RuntimeException) {
        System.err.println("[错误] ${e.message}")
        exitProcess(1)
    }
}
