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
"""

fun main(args: Array<String>) {
    if (args.size < 2) { System.err.println(USAGE); exitProcess(2) }
    val cmd = args[0]
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
                val out = bag.report(includeSupplement = verbose)
                if (out.isNotEmpty()) println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过"); exitProcess(1) }
                println("[完成] 语义检查通过")
            }
            "compile" -> {
                val ast = parseSource(src, path.name)
                val chk = sugared.functor.checker.Checker(ast)
                val bag = chk.run()
                val out = bag.report(includeSupplement = verbose)
                if (out.isNotEmpty()) System.err.println(out)
                if (bag.hasError) { System.err.println("[错误] 语义检查未通过，拒绝生成 JS"); exitProcess(1) }
                val js = JsCodeGen().generate(ast, chk.dictHits, chk.moduleHits, chk.consHits, chk.dictSubHits, chk.namedArgOrder, chk.methodSugarArgs)   // 单文件：约束槽/字典实参/命名参数/方法糖留痕同源
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
