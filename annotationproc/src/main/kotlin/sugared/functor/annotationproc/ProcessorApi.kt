package sugared.functor.annotationproc

import sugared.functor.ast.DeclEntry
import sugared.functor.ast.FileAst
import sugared.functor.ast.FunDecl
import sugared.functor.ast.StructDecl
import sugared.functor.checker.Diag
import sugared.functor.checker.Severity
import sugared.functor.interpreter.Interpreter
import sugared.functor.interpreter.ScriptType

/** 注解处理器的脚本 API 框架（root doc/注解处理器.md 11 第二版：处理器用 Sublimation 脚本写，
 * 由解释器解释执行）。
 *
 * 对接形态：[register] 把下列 host 函数注入 [Interpreter]，处理器脚本（.subs 顶层语句）直接调用它们。
 * 脚本执行期不改 AST——编辑函数只累积 [AnnotationEdit]，由 [applyAnnotationEdits] 在处理器收尾时
 * 以「保持 AST 不可变」的方式重建（root doc 2/3.2 核心契约：输入只读、输出新 AST）。
 *
 * 查询（只读，基于 ProcessorInput.ast）：
 *   structNames(): List[Str]                    全部结构体名（声明序）
 *   structsWithAnnotation(a: Str): List[Str]    带注解 a 的结构体名
 *   structAnnotations(n: Str): List[Str]        结构体的注解列表
 *   structFields(n: Str): List[Str]             结构体字段名（声明序）
 *   fieldAnnotations(s: Str, f: Str): List[Str] 字段的注解列表
 *   funNames(): List[Str]                       全部函数名（含 @root/@host 无体声明）
 *   funsWithAnnotation(a: Str): List[Str]       带注解 a 的函数名
 *   funAnnotations(n: Str): List[Str]           函数的注解列表
 *
 * 编辑（累积指令；重复增删幂等——收敛后不再产生变化，配合引擎多轮自然停机）：
 *   addStructAnnotation(n: Str, a: Str)         / removeStructAnnotation(n: Str, a: Str)
 *   addFieldAnnotation(s: Str, f: Str, a: Str)  / removeFieldAnnotation(s: Str, f: Str, a: Str)
 *   addFunAnnotation(n: Str, a: Str)            / removeFunAnnotation(n: Str, a: Str)
 *
 * 诊断（把脚本想说的话写进编译诊断袋）：
 *   processorError(m: Str) / processorWarn(m: Str) / processorHint(m: Str) / processorInfo(m: Str)
 *
 * 查询函数的返回值是脚本的值（Kotlin List 对应脚本 List）；不存在的名字返回空列表，
 * 不抛异常（脚本模式容错优先，处理器作者用 processorWarn 报告异常情况）。
 *
 * 结构性编辑（root doc/注解处理器.md：处理器是 AST→AST 变换；源码片段由宿主在收尾时解析嫁接，
 * 见 [AstEdit]——覆盖完整 AST 修改能力，区别于注解增删）：
 *   setFunBody(name: Str, src: Str)               替换函数体（块体「{ … }」或表达式体）
 *   prependStmts(name: Str, stmts: List[Str])     语句片段插到函数块体最前（表达式体脱糖成 return 块）
 *   addDecl(src: Str)                            追加一条新声明（fun/struct/enum/class/impl/type）
 *   replaceDecl(name: Str, src: Str)             整体替换命名声明
 * 幂等约定：结构性编辑需脚本自守（同一轮重复发同体替换天然幂等——AST 深度相等即停机；
 * prependStmts 请配注解标记，见 wiki 注解处理器页「编辑的幂等与收敛」）。
 */

/** 一条注解编辑指令：对「某声明的注解」或「某结构体字段的注解」做增/删。 */
internal data class AnnotationEdit(
    val kind: EditTarget,        // STRUCT / FUN
    val owner: String,           // 结构体名或函数名
    val field: String?,          // 非 null 时作用于结构体的字段；null 时作用于声明本身
    val annotation: String,
    val add: Boolean,            // true=增注解，false=删注解
)

internal enum class EditTarget { STRUCT, FUN }

object ProcessorApi {

    /** 把脚本 API 注入解释器。edits（注解）/ astEdits（结构）/ diags 由调用方提供（一次处理器运行的累积区）。 */
    internal fun register(
        interp: Interpreter,
        input: ProcessorInput,
        edits: MutableList<AnnotationEdit>,
        astEdits: MutableList<AstEdit>,
        diags: MutableList<Diag>,
    ) {
        val structs = declsOf<StructDecl>(input.ast)
        val funs = declsOf<FunDecl>(input.ast)

        fun structByName(n: String) = structs.firstOrNull { it.name == n }
        fun funByName(n: String) = funs.firstOrNull { it.name == n }

        // ---------- 查询（只读）----------
        interp.register("structNames", retType = ScriptType.ListT(ScriptType.Str)) {
            structs.map { it.name }
        }
        interp.register("structHasAnnotation", "name" to ScriptType.Str, "annotation" to ScriptType.Str,
            retType = ScriptType.Bool) { args ->
            val st = structByName(args[0] as String)
            (args[1] as String) in (st?.annotations ?: emptyList())
        }
        interp.register("structsWithAnnotation", "annotation" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val a = args[0] as String
            structs.filter { a in it.annotations }.map { it.name }
        }
        interp.register("structAnnotations", "name" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val annos: List<String> = structByName(args[0] as String)?.annotations ?: emptyList()
            annos
        }
        interp.register("structFields", "name" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val names: List<String> = structByName(args[0] as String)?.fields?.map { it.name } ?: emptyList()
            names
        }

        // ---------- 编译参数（CLI `-A key=value`；安卓 APT 用它收 SDK/构建工具路径） ----------
        interp.register("option", "key" to ScriptType.Str, retType = ScriptType.Str) { args ->
            input.options[args[0] as String] ?: ""
        }
        interp.register("optionOr", "key" to ScriptType.Str, "default" to ScriptType.Str,
            retType = ScriptType.Str) { args ->
            input.options[args[0] as String] ?: (args[1] as String)
        }
        interp.register("optionKeys", retType = ScriptType.ListT(ScriptType.Str)) {
            input.options.keys.toList()
        }

        // ---------- 宿主执行力（V2：构建额外产物的处理器需要） ----------
        // runShell 执行外部命令，返回 "exit|stdout|stderr"；非零退出不抛异常，由脚本自决。
        interp.register("runShell", "cmd" to ScriptType.Str, retType = ScriptType.Str) { args ->
            val c = args[0] as String
            try {
                val proc = ProcessBuilder("cmd", "/c", c)
                    .redirectErrorStream(false).directory(java.io.File(".")).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val err = proc.errorStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                "$code|$out|$err"
            } catch (ex: Exception) {
                "-1||" + (ex.message ?: ex.toString())
            }
        }
        interp.register("writeFile", "path" to ScriptType.Str, "content" to ScriptType.Str) { args ->
            val f = java.io.File(args[0] as String)
            try {
                f.parentFile?.mkdirs(); f.writeText(args[1] as String, kotlin.text.Charsets.UTF_8); null
            } catch (ex: Exception) {
                diags += Diag(Severity.ERROR, "E-PROCESSOR-IO", "", "writeFile 失败 " + f.path + "：" + (ex.message ?: "")); null
            }
        }
        interp.register("readFile", "path" to ScriptType.Str, retType = ScriptType.Str) { args ->
            val f = java.io.File(args[0] as String)
            try { f.readText(kotlin.text.Charsets.UTF_8) } catch (ex: Exception) {
                diags += Diag(Severity.WARN, "W-PROCESSOR-IO", "", "readFile 失败 " + f.path + "：" + (ex.message ?: "")); ""
            }
        }
        interp.register("fileExists", "path" to ScriptType.Str, retType = ScriptType.Bool) { args ->
            java.io.File(args[0] as String).exists()
        }


        // ---------- V2 安卓 APK 构建（宿主能力；工具路径经 options 传入） ----------
        interp.register(
            "buildAndroidApk",
            "appDir" to ScriptType.Str, "jsPath" to ScriptType.Str, "outDir" to ScriptType.Str,
            retType = ScriptType.Str,
        ) { args ->
            val appDir = java.io.File(args[0] as String)
            val jsPath = java.io.File(args[1] as String)
            val outDir = java.io.File(args[2] as String)
            try {
                // 1) JS 产物拷进 app 的 assets（WebView 加载 app.js）
                val assets = java.io.File(appDir, "app/src/main/assets")
                assets.mkdirs()
                val target = java.io.File(assets, "app.js")
                val js = jsPath.readText(kotlin.text.Charsets.UTF_8)
                val boot = "try{__dshBoot();}catch(e){}\n"
                val tail = "\ntry{ if (typeof main === 'function') main(); }catch(e){ if(__dshAndroid) __dshAndroid('log',[String(e),3]); }\n"
                target.writeText(boot + js + tail, kotlin.text.Charsets.UTF_8)
                // 2) gradlew assembleDebug
                val isWin = System.getProperty("os.name").startsWith("Windows")
                val cmd = if (isWin) "cmd /c gradlew.bat :app:assembleDebug --console=plain"
                    else "./gradlew :app:assembleDebug --console=plain"
                val proc = ProcessBuilder(*cmd.split(" ").toTypedArray())
                    .directory(appDir).redirectErrorStream(true).start()
                val out = proc.inputStream.bufferedReader().use { it.readText() }
                val code = proc.waitFor()
                if (code != 0) return@register "error|build failed: " + out.take(2000)
                // 3) APK 拷到 outDir
                val apk = java.io.File(appDir, "app/build/outputs/apk/debug/app-debug.apk")
                outDir.mkdirs()
                val dest = java.io.File(outDir, apk.name)
                apk.copyTo(dest, overwrite = true)
                "ok|" + dest.path
            } catch (ex: Exception) {
                "error|" + (ex.message ?: ex.toString())
            }
        }

// ---------- 安卓视图处理器支撑（android doc/安卓标准库.md） ----------
        interp.register("fieldDeclText", "name" to ScriptType.Str, "field" to ScriptType.Str,
            retType = ScriptType.Str) { args ->
            val st = structByName(args[0] as String)
            val fp = st?.fields?.firstOrNull { it.name == args[1] as String }
            if (fp == null) "" else fieldDeclText(fp)
        }
        interp.register("structHasFieldList", "name" to ScriptType.Str,
            retType = ScriptType.Bool) { args ->
            val st = structByName(args[0] as String)
            (st != null && st.fields.isNotEmpty())
        }
        interp.register("viewCtorSites", retType = ScriptType.ListT(ScriptType.Str)) { _ ->
            val views = structs.filter { it.isView() }.map { it.name }.toSet()
            val sites = collectViewCtorSites(input.ast, views)
            sites.map { it.funName + "|" + it.viewName + "|" + it.ordinal }
        }
        interp.register("rewriteViewCtors") { _ ->
            val views = structs.filter { it.isView() }.map { it.name }.toSet()
            astEdits += AstEdit.RewriteViewCtors(views); null
        }
        interp.register("injectViewStruct", "name" to ScriptType.Str) { args ->
            astEdits += AstEdit.InjectViewFields(args[0] as String); null
        }
        interp.register("fieldAnnotations", "struct" to ScriptType.Str, "field" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val st = structByName(args[0] as String)
            val fs: List<sugared.functor.ast.Param> = st?.fields ?: emptyList()
            val f = fs.firstOrNull { it.name == args[1] as String }
            val fAnnos: List<String> = f?.annotations ?: emptyList()
            fAnnos
        }
        interp.register("funNames", retType = ScriptType.ListT(ScriptType.Str)) {
            funs.map { it.name }
        }
        interp.register("funsWithAnnotation", "annotation" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val a = args[0] as String
            funs.filter { a in it.annotations }.map { it.name }
        }
        interp.register("funHasBody", "name" to ScriptType.Str,
            retType = ScriptType.Bool) { args ->
            val fn = funs.firstOrNull { it.name == args[0] as String }
            (fn?.body != null)
        }
        interp.register("funHasAnnotation", "name" to ScriptType.Str, "ann" to ScriptType.Str,
            retType = ScriptType.Bool) { args ->
            val fn = funs.firstOrNull { it.name == args[0] as String }
            (fn != null && (args[1] as String) in fn.annotations)
        }
        interp.register("funAnnotations", "name" to ScriptType.Str,
            retType = ScriptType.ListT(ScriptType.Str)) { args ->
            val annos: List<String> = funByName(args[0] as String)?.annotations ?: emptyList()
            annos
        }

        // ---------- 编辑（累积指令）----------
        interp.register("addStructAnnotation", "name" to ScriptType.Str, "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.STRUCT, args[0] as String, null, args[1] as String, add = true)
            null
        }
        interp.register("removeStructAnnotation", "name" to ScriptType.Str, "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.STRUCT, args[0] as String, null, args[1] as String, add = false)
            null
        }
        interp.register("addFieldAnnotation", "struct" to ScriptType.Str, "field" to ScriptType.Str,
            "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.STRUCT, args[0] as String, args[1] as String, args[2] as String, add = true)
            null
        }
        interp.register("removeFieldAnnotation", "struct" to ScriptType.Str, "field" to ScriptType.Str,
            "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.STRUCT, args[0] as String, args[1] as String, args[2] as String, add = false)
            null
        }
        interp.register("addFunAnnotation", "name" to ScriptType.Str, "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.FUN, args[0] as String, null, args[1] as String, add = true)
            null
        }
        interp.register("removeFunAnnotation", "name" to ScriptType.Str, "annotation" to ScriptType.Str) { args ->
            edits += AnnotationEdit(EditTarget.FUN, args[0] as String, null, args[1] as String, add = false)
            null
        }

        // ---------- 结构性编辑（源码片段由宿主收尾解析嫁接，见 applyAstEdits） ----------
        interp.register("setFunBody", "name" to ScriptType.Str, "src" to ScriptType.Str) { args ->
            astEdits += AstEdit.SetFunBody(args[0] as String, args[1] as String); null
        }
        interp.register("prependStmts", "name" to ScriptType.Str,
            "stmts" to ScriptType.ListT(ScriptType.Str)) { args ->
            @Suppress("UNCHECKED_CAST")
            astEdits += AstEdit.PrependStmts(args[0] as String, args[1] as List<String>); null
        }
        interp.register("addDecl", "src" to ScriptType.Str) { args ->
            astEdits += AstEdit.AddDecl(args[0] as String); null
        }
        interp.register("replaceDecl", "name" to ScriptType.Str, "src" to ScriptType.Str) { args ->
            astEdits += AstEdit.ReplaceDecl(args[0] as String, args[1] as String); null
        }

        // ---------- 诊断 ----------
        interp.register("processorError", "message" to ScriptType.Str) { args ->
            diags += Diag(Severity.ERROR, "E-PROCESSOR-SCRIPT", "", args[0] as String); null
        }
        interp.register("processorWarn", "message" to ScriptType.Str) { args ->
            diags += Diag(Severity.WARN, "W-PROCESSOR-SCRIPT", "", args[0] as String); null
        }
        interp.register("processorHint", "message" to ScriptType.Str) { args ->
            diags += Diag(Severity.HINT, "H-PROCESSOR-SCRIPT", "", args[0] as String); null
        }
        interp.register("processorInfo", "message" to ScriptType.Str) { args ->
            diags += Diag(Severity.SUPPLEMENT, "D-PROCESSOR-SCRIPT", "", args[0] as String); null
        }
    }
}

/** FileAst 里某类声明的列表（声明序）。 */
private inline fun <reified D : sugared.functor.ast.Decl> declsOf(ast: FileAst): List<D> =
    ast.entries.mapNotNull { (it as? DeclEntry)?.decl as? D }

/**
 * 把一批注解编辑不可变地作用到 AST 上。
 *
 * - 增注解幂等：注解已存在则视作已兑现（不产生实际变化，引擎据此判定本轮无变化而停机）；
 * - 目标不存在（结构体/函数/字段名拼错）记一条 W-PROCESSOR-EDIT 诊断，其余编辑继续作用；
 * - 无任何编辑命中时原样返回 [ast]（引用相等，引擎据此跳过重查）。
 */
internal fun applyAnnotationEdits(
    ast: FileAst,
    edits: List<AnnotationEdit>,
    diags: MutableList<Diag> = mutableListOf(),
): FileAst {
    if (edits.isEmpty()) return ast
    val matched = HashSet<AnnotationEdit>()
    val out = ArrayList<sugared.functor.ast.Entry>()
    for (e in ast.entries) {
        val decl = (e as? DeclEntry)?.decl
        val entry = when (decl) {
            is StructDecl -> applyStructEdits(e, decl, edits, matched)
            is FunDecl -> applyFunEdits(e, decl, edits, matched)
            else -> e
        }
        out += entry
    }
    for (ed in edits) {
        if (ed !in matched)
            diags += Diag(Severity.WARN, "W-PROCESSOR-EDIT", "",
                "处理器编辑未命中：" + (if (ed.field != null) "字段 " + ed.owner + "." + ed.field else "声明 " + ed.owner) +
                    " 的注解 " + ed.annotation + "（目标不存在？）")
    }
    return FileAst(out)
}

/** 结构体编辑：声明级注解（field == null）+ 字段级注解（field != null），返回新条目。 */
private fun applyStructEdits(
    entry: sugared.functor.ast.Entry,
    decl: StructDecl,
    edits: List<AnnotationEdit>,
    matched: HashSet<AnnotationEdit>,
): sugared.functor.ast.Entry {
    val mine = edits.filter { it.kind == EditTarget.STRUCT && it.owner == decl.name }
    if (mine.isEmpty()) return entry
    val newAnnos = decl.annotations.toMutableList()
    for (ed in mine.filter { it.field == null }) {
        matched += ed
        applyOne(newAnnos, ed)
    }
    var fieldsChanged = false
    val newFields = decl.fields.map { fp ->
        val fldEdits = mine.filter { it.field == fp.name }
        if (fldEdits.isEmpty()) fp else {
            fieldsChanged = true
            val fAnnos = fp.annotations.toMutableList()
            for (ed in fldEdits) { matched += ed; applyOne(fAnnos, ed) }
            fp.copy(annotations = fAnnos.toList())
        }
    }
    return sugared.functor.ast.DeclEntry(decl.copy(annotations = newAnnos.toList(), fields = newFields))
}

/** 函数编辑：声明级注解，返回新条目。 */
private fun applyFunEdits(
    entry: sugared.functor.ast.Entry,
    decl: FunDecl,
    edits: List<AnnotationEdit>,
    matched: HashSet<AnnotationEdit>,
): sugared.functor.ast.Entry {
    val mine = edits.filter { it.kind == EditTarget.FUN && it.owner == decl.name && it.field == null }
    if (mine.isEmpty()) return entry
    val newAnnos = decl.annotations.toMutableList()
    for (ed in mine) { matched += ed; applyOne(newAnnos, ed) }
    return sugared.functor.ast.DeclEntry(decl.copy(annotations = newAnnos.toList()))
}


/** 字段声明的源码文本（注解 + 名字 + 类型），供 .subs 脚本重组结构体声明。 */
private fun fieldDeclText(fp: sugared.functor.ast.Param): String {
    val annos = if (fp.annotations.isEmpty()) "" else fp.annotations.joinToString(" ") { "@" + it } + " "
    return annos + fp.name + ": " + fp.type.render()
}
/** 幂等地增/删一条注解。 */
private fun applyOne(annos: MutableList<String>, ed: AnnotationEdit) {
    if (ed.add) { if (ed.annotation !in annos) annos += ed.annotation }
    else annos.remove(ed.annotation)
}
