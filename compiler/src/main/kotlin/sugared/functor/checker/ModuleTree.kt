package sugared.functor.checker

import sugared.functor.ast.Expr
import sugared.functor.ast.FieldExpr
import sugared.functor.ast.NameRef
import sugared.functor.ast.QualifiedType
import java.io.File

/**
 * 模块树（P0 模块系统，决策 76——落地文件；决策正文见《草案思路.md》）。
 *
 * 约定：
 * - **目录即模块**：每个目录（含根 `/`）是一个模块；同模块内所有 `.subl` 文件共享一个 `Symbols`。
 * - 模块的 absPath = 相对根的 "/" 连接路径；根 = `""`（唯一键）。
 * - settings 文件：`<模块名>.settings`（模块名 = 目录名），逐行 `mount <路径(从根起)> [as <别名>]`。
 * - 根模块没有 settings 文件（P0 限制，见《模块系统.md》）；根模块符号不加 mangle 前缀（用户拍板 Q4a）。
 * - I-20：可见性是"计算出来的集合"，不是查表的布尔——每次符号查找都要携带当前模块的完整可见路径集合。
 */

data class ModuleNode(
    val name: String,                 // 目录名；根 = ""
    val absPath: String,              // 相对根的 "/" 连接路径；根 = ""（唯一键）
    val dir: File,
    val children: MutableMap<String, ModuleNode> = LinkedHashMap(),
    val files: MutableList<File> = ArrayList(),
    var settings: ModuleSettings? = null,
) {
    /** 全树模块（含自己），预序 */
    fun allModules(): List<ModuleNode> {
        val out = ArrayList<ModuleNode>()
        fun go(n: ModuleNode) { out += n; n.children.values.forEach { go(it) } }
        go(this)
        return out
    }
}

data class ModuleSettings(val mounts: List<MountDecl>)

/** `mount <path> [as <alias>]`；targetAbsPath 为从根起的绝对路径（如 "core/math"） */
data class MountDecl(val targetAbsPath: String, val alias: String?)

/** 非源码/构建产物目录：扫描时忽略（外加隐藏目录） */
private val IGNORED_DIRS = setOf(
    "build", "out", "target", "node_modules",
    ".git", ".gradle", ".idea", ".kotlin", ".gradle",
)

/** 标准库模块名（决策 78 改名：stdlib → core）：编译入口下以该名识别标准库包目录。 */
const val STDLIB_MODULE = "core"

/** 标准库包目录候选（相对编译入口根，按优先级）：
 *  - `core/`：core 统一标准库的包目录惯例（《模块系统》"core 作为 <根>/core/ 包目录存在"）；
 *  - `test/core/`：备选布局（test 目录下托管标准库的惯例布局），命中即嫁接。【sublimation 仓库布局：标准库位于仓库根 <仓库根>/core/，编译入口取仓库根即可识别】
 *  命中后一律按 `core` 名嫁接到模块树根（见 graftStdlib），其内部 settings 的
 *  `mount core.xxx` 完整包名挂载按根可达。 */
val STDLIB_DIR_CANDIDATES = listOf("core", "test/core")

/** 从根目录递归扫描，构建模块树。忽略隐藏目录与 IGNORED_DIRS；只收集 `.subl` 文件。
 *  扫描后嫁接标准库包：根下无 `core/` 而存在 `test/core/` 时，将其重挂为根的 `core` 子树。 */
fun scanModuleTree(root: File): ModuleNode {
    fun scan(dir: File, absPath: String, name: String): ModuleNode {
        val node = ModuleNode(name, absPath, dir)
        for (f in dir.listFiles()?.sortedBy { it.name } ?: emptyList()) {
            if (f.isDirectory) {
                if (f.name.startsWith(".") || f.name in IGNORED_DIRS) continue
                val childAbs = if (absPath.isEmpty()) f.name else "$absPath/${f.name}"
                val child = scan(f, childAbs, f.name)
                node.children[child.name] = child
            } else if (f.name.endsWith(".subl")) {
                node.files += f
            }
        }
        return node
    }
    val tree = scan(root, "", "")
    graftStdlib(tree)
    return tree
}

/** 嫁接标准库包：`test/core/` → 根的 `core` 子模块（重算 absPath 为 `core/...`）。
 *  根已有 `core` 子目录（编译入口即 core 的父目录）时不動。 */
private fun graftStdlib(root: ModuleNode) {
    if (STDLIB_MODULE in root.children) return
    val test = root.children["test"] ?: return
    val core = test.children[STDLIB_MODULE] ?: return
    if (!isStdlibPackage(core)) return   // 用户的 test/core 普通模块不劫持
    test.children.remove(STDLIB_MODULE)
    root.children[STDLIB_MODULE] = repathModule(core, STDLIB_MODULE)
}

/** core 标准库包形态：只含子包目录、无直接 .subl 源文件。用户自己的 `core/`（目录下直接有源文件）
 *  保持普通模块语义，不被当作标准库（M5 类测试里的用户 core 模块照常工作）。 */
private fun isStdlibPackage(node: ModuleNode): Boolean =
    node.files.isEmpty() && node.children.isNotEmpty()

/** 子树重算 absPath（嫁接标准库用；settings 随后由 validate() 统一重载）。 */
private fun repathModule(node: ModuleNode, absPath: String): ModuleNode {
    val children = LinkedHashMap<String, ModuleNode>()
    for ((n, c) in node.children) children[n] = repathModule(c, "$absPath/$n")
    return ModuleNode(node.name, absPath, node.dir, children, ArrayList(node.files))
}

/** 读取模块的 settings 文件；文件不存在（或根模块）→ 空 mounts。语法错报 E-PARSE-SETTINGS。 */
fun loadSettings(node: ModuleNode, bag: DiagBag): ModuleSettings {
    if (node.name.isEmpty()) return ModuleSettings(emptyList())   // 根模块无 settings（P0 限制）
    val f = File(node.dir, "${node.name}.settings")
    if (!f.exists()) return ModuleSettings(emptyList())
    return try { parseSettingsFile(f) }
    catch (e: IllegalArgumentException) {
        bag.error("E-PARSE-SETTINGS", "", "settings 语法错：${e.message}")
        ModuleSettings(emptyList())
    }
}

/** 极简行式解析：每行 `mount <path> [as <alias>]`，忽略空行与 `#` 注释；语法错抛异常由调用方报 E-PARSE-SETTINGS。
 *  路径写法两等价：斜杠形 `core/math` 与**点分完整包名形 `core.math`**（core 统一标准库的挂载惯例）；
 *  点分形在此归一化为斜杠形（目录名按约定不含点；斜杠形保持原样）。 */
fun parseSettingsFile(file: File): ModuleSettings {
    val mounts = ArrayList<MountDecl>()
    var lineNo = 0
    for (raw in file.readLines()) {
        lineNo++
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) continue
        fun fail(msg: String): Nothing =
            throw IllegalArgumentException("${file.name}:$lineNo—$msg：'$line'")
        val parts = line.split(Regex("\\s+"))
        if (parts[0] != "mount") fail("settings 只支持 `mount <路径> [as <别名>]` 一个指令")
        if (parts.size < 2) fail("mount 缺少路径")
        val raw0 = parts[1].trim('/')
        // 点分完整包名（core.math）→ 斜杠形（core/math）；已是斜杠形原样保留
        val path = raw0.replace('.', '/')
        if (path.isEmpty() || path.split('/').any { it.isEmpty() }) fail("非法路径")
        var alias: String? = null
        if (parts.size >= 3) {
            if (parts.size != 4 || parts[2] != "as") fail("期望 `as <别名>`")
            alias = parts[3]
        }
        mounts += MountDecl(path, alias)
    }
    return ModuleSettings(mounts)
}

/** 模块绝对路径 → 合法 JS 标识符前缀（根为空 → 无前缀；用户拍板 Q4a）。
 *  P0 文档钉死的规则：trim('/') → '/'→'_' → 非法字符→'_'。 */
fun mangleModule(absPath: String): String =
    absPath.trim('/').replace('/', '_').replace(Regex("[^A-Za-z0-9_]"), "_")

/** 名字转合法 JS 标识符（避开保留字、符号名转 op_xxxx）。与 JsCodeGen 共用同一实现。 */
fun jsMangle(n: String): String {
    val jsReserved = setOf(
        "function", "return", "var", "let", "const", "class", "new", "this",
        "typeof", "in", "of", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "delete", "void", "null", "true", "false", "instanceof",
    )
    // $ 在 JS 标识符里合法（安卓注解处理器生成的合成名 recomposion$Box$1 直接保留，便于 KT 端注入识别）
    val base = if (n.all { it.isLetterOrDigit() || it == '_' || it == '$' }) n
               else "op_" + n.toCharArray().joinToString("") { "%04x".format(it.code) }
    return if (base in jsReserved) "\$$base" else base
}

/** 带模块前缀的 JS 顶层名字：前缀为空（根）时不加双下划线分隔。 */
fun moduleJsName(absPath: String, name: String): String {
    val p = mangleModule(absPath)
    val m = jsMangle(name)
    return if (p.isEmpty()) m else "${p}__$m"
}

/** v2.0 重载（决策 92 配套）：函数的 JS 后缀标签——按首参类型基名区分同名重载（0 参 → v0）。
 *  重载注册已保证首参 render 不同，故基名在重载组内唯一；声明侧与调用侧（moduleHits）共用同一规则。 */
fun fnTag(fn: sugared.functor.ast.FunDecl): String = when (val t = fn.params.firstOrNull()?.type) {
    null -> "v0"
    is sugared.functor.ast.NamedType -> t.name
    is sugared.functor.ast.TupleType -> "tup"
    is sugared.functor.ast.FunType -> "fn"
    else -> "v0"
}

/** 限定方法调用的 codegen 标记（moduleHits 值）：限定方法走字典分发、首实参即接收者。 */
const val MODULE_METHOD_MARKER = "\u0000module_method"

/**
 * 模块系统驱动（P0）：负责 settings 校验、挂载图环检测、I-20 可见路径集合计算。
 * 校验错误写入外面的 DiagBag。
 */
class ModuleTreeDriver(val tree: ModuleNode, val bag: DiagBag) {
    private val nodesByPath: Map<String, ModuleNode> =
        tree.allModules().associateBy { it.absPath }
    private val visibleCache = HashMap<String, Set<String>>()
    private var cycleReported = false

    init { validate() }

    fun nodeOf(absPath: String): ModuleNode? = nodesByPath[absPath]

    /** 校验：加载全部 settings、挂载完整包名展开、挂载目标存在性、本地名冲突、挂载链成环。 */
    private fun validate() {
        for (m in tree.allModules()) m.settings = loadSettings(m, bag)
        expandPackageMounts()   // 挂载完整包名：多段目标的前缀包隐式挂载（须在本地名冲突检查前完成）
        for (m in tree.allModules()) {
            val lname = m.absPath.ifEmpty { "/" }
            val localNames = LinkedHashMap<String, String>()   // 本地可见名 → 描述
            m.children.keys.forEach { localNames.putIfAbsent(it, "子目录") }
            for (md in m.settings!!.mounts) {
                val t = nodesByPath[md.targetAbsPath]
                if (t == null) {
                    bag.error("E-UNBOUND-NAME", "", "模块 $lname 的 settings 挂载了不存在的模块路径 '${md.targetAbsPath}'")
                    continue
                }
                val ln = md.alias ?: t.name
                val prev = localNames.putIfAbsent(ln, "挂载 ${md.targetAbsPath}")
                if (prev != null)
                    bag.error("E-DUP-DECL", "", "模块 $lname 内本地名 '$ln' 冲突：$prev 与 挂载 ${md.targetAbsPath}")
            }
        }
        injectCoreMounts()   // P2（决策 78，v4 改名）：core 标准库隐式挂载——必须在环检测前完成
        cycleDetect()
    }

    /** 挂载完整包名（core 统一标准库）：目标为多段路径的挂载，其前缀包一并隐式挂载。
     *  `mount core.math` 后 `core` 也成为本地可见模块名——限定调用 `core.math.gcd(...)` 可达，
     *  与按目录名的 `math.gcd(...)` 等价；前缀本地名已被占用（子目录/别名/其他挂载目标）则跳过。 */
    private fun expandPackageMounts() {
        for (m in tree.allModules()) {
            val base = m.settings?.mounts ?: continue
            val extras = LinkedHashMap<String, MountDecl>()
            val occupied = m.children.keys.toMutableSet()
            occupied += base.mapNotNull { it.alias ?: nodesByPath[it.targetAbsPath]?.name }
            for (md in base) {
                val segs = md.targetAbsPath.split('/')
                if (segs.size < 2) continue
                val pref = StringBuilder()
                for (i in 0 until segs.size - 1) {
                    if (pref.isNotEmpty()) pref.append('/')
                    pref.append(segs[i])
                    val pn = pref.toString()
                    val node = nodesByPath[pn] ?: break
                    if (node.name in occupied) continue
                    extras[pn] = MountDecl(pn, null)
                    occupied += node.name
                }
            }
            if (extras.isNotEmpty()) m.settings = ModuleSettings(base + extras.values)
        }
    }

    /**
     * P2（决策 78，v4 改名：stdlib → core）：core 标准库隐式挂载（用户拍板 B——隐式挂载的标准库模块）。
     * 标准库是 core 包（`<根>/core/` 或 `<根>/test/core/`，见 STDLIB_DIR_CANDIDATES）：为所有非 core 自身的
     * 模块追加 `mount core` 及其全部子包（`mount core.collection` 等）——core 包内符号（List/Ok/Err、
     * size/filter 等裸名、Functor/Foldable 类型类）跨模块查找需整包可达。注入的挂载不经本地名冲突检查
     * （子目录名优先于挂载名，visibleChild 先查子目录）；本地同名占用时仅跳过对应子包挂载。
     */
    private fun injectCoreMounts() {
        val corePkg = nodesByPath[STDLIB_MODULE] ?: return
        if (!isStdlibPackage(corePkg)) return   // 非标准库形态的 core（用户模块）不隐式挂载
        val pkgs = corePkg.allModules()   // core 根 + 全部子包
        for (m in tree.allModules()) {
            // core 自身与子包不注入（自挂会成环：core 的子包经 core.settings 互引）
            if (m.absPath == STDLIB_MODULE || m.absPath.startsWith("$STDLIB_MODULE/")) continue
            val base = m.settings?.mounts ?: emptyList()
            val localNames = m.children.keys.toMutableSet()
            for (md in base) localNames += md.alias ?: nodesByPath[md.targetAbsPath]?.name ?: continue
            val adds = pkgs.filter { it.name !in localNames }.map { MountDecl(it.absPath, null) }
            if (adds.isNotEmpty()) m.settings = ModuleSettings(base + adds)
        }
    }

    /** 挂载图 DFS 找环（决策 76：挂载链成环报 E-CIRCULAR-MOUNT） */
    private fun cycleDetect() {
        val state = HashMap<String, Int>()   // 0=未访问 1=栈中 2=完成（未访问键为 null，与 0 同等处理）
        val stack = ArrayList<String>()
        fun dfs(p: String) {
            state[p] = 1; stack += p
            for (md in nodesByPath[p]?.settings?.mounts ?: emptyList()) {
                val q = md.targetAbsPath
                if (q !in nodesByPath) continue
                when (state[q]) {
                    null, 0 -> dfs(q)
                    1 -> if (!cycleReported) {
                        val cyc = stack.subList(stack.indexOf(q), stack.size) + q
                        bag.error("E-CIRCULAR-MOUNT", "", "挂载链成环：${cyc.joinToString(" -> ")}")
                        cycleReported = true
                    }
                }
            }
            stack.removeAt(stack.lastIndex); state[p] = 2
        }
        for (p in nodesByPath.keys) if (state[p] == null) dfs(p)
    }

    /** 挂载图的传递闭包（"收编即全收"，用户拍板 Q2）：T ∪ 其后续挂载可达项；DFS 沿挂载声明走。 */
    private fun mountReach(t: ModuleNode, visiting: LinkedHashSet<String>): Set<String> {
        if (t.absPath in visiting) return emptySet()   // 环已另行报错，此处截断避免死循环
        visiting += t.absPath
        val out = LinkedHashSet<String>()
        out += t.absPath
        for (md in t.settings?.mounts ?: emptyList()) {
            val q = nodesByPath[md.targetAbsPath] ?: continue
            out += mountReach(q, visiting)
        }
        visiting.remove(t.absPath)
        return out
    }

    private fun ancestorPathOf(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i < 0) "" else p.substring(0, i)
    }

    /** p 是否是 from 的严格后代（from 自身不算；根模块 from="" 时任何非空路径都算后代）。 */
    private fun isDescendant(from: String, p: String): Boolean =
        if (from.isEmpty()) p.isNotEmpty() else p.startsWith(from + "/")

    /**
     * I-20：from 模块的完整可见路径集合 = 自身 ∪ 后代 ∪ 自身及其所有祖先的挂载可达（传递）。
     * 挂载只向下流淌（祖先的挂载对后代可见），不上溯（后代的挂载不对祖先可见）。
     */
    fun visiblePathsOf(from: String): Set<String> {
        visibleCache[from]?.let { return it }
        val out = LinkedHashSet<String>()
        val start = nodesByPath[from] ?: return emptySet()
        fun addDesc(n: ModuleNode) { out += n.absPath; n.children.values.forEach { addDesc(it) } }
        addDesc(start)
        var cur: ModuleNode? = start
        while (cur != null) {
            for (md in cur.settings?.mounts ?: emptyList()) {
                val q = nodesByPath[md.targetAbsPath] ?: continue
                out += mountReach(q, LinkedHashSet())
            }
            cur = if (cur.absPath.isEmpty()) null else nodesByPath[ancestorPathOf(cur.absPath)]
        }
        visibleCache[from] = out
        return out
    }

    /** v3.0：裸自由函数跨模块可见集合 = 本模块 + 自身及祖先**挂载**的传递可达（不含 addDesc 后代子树）。
     *  语义：Kotlin 默认导入只在 core/挂载间透传——root 裸调字模块函数仍不可见（M3 兄弟/子不可见约束保持）。 */
    fun bareVisibleOf(from: String): Set<String> {
        val start = nodesByPath[from] ?: return emptySet()
        val out = LinkedHashSet<String>()
        out += start.absPath
        // v4 默认导入：根级 core 标准库包（非挂载形态）= 全局库，任何模块裸名可见（Kotlin 默认导入语义）；
        // core 是包目录，默认导入覆盖整包（core 根 + 全部子包）；用户自己的 core 模块不算
        nodesByPath[STDLIB_MODULE]?.takeIf { isStdlibPackage(it) }
            ?.let { corePkg -> out += corePkg.allModules().map { it.absPath } }
        var cur: ModuleNode? = start
        while (cur != null) {
            for (md in cur.settings?.mounts ?: emptyList()) {
                val q = nodesByPath[md.targetAbsPath] ?: continue
                out += mountReach(q, LinkedHashSet())
            }
            cur = if (cur.absPath.isEmpty()) null else nodesByPath[ancestorPathOf(cur.absPath)]
        }
        return out
    }

    /**
     * 从 from 模块视角，名字 → 直接可达的子模块。解析顺序（P0 引用路径规则：相对当前模块视角）：
     * 1. 当前模块的**自身子目录**（一层）；
     * 2. 自身及**所有祖先**的**直接挂载**（别名优先，其次目录名）——挂载向下流淌，深层的 project/data 也用 core；
     * 3. **传递挂载**：from 完整可见集合中、按目录名匹配的模块（project→core→foo 时 project 也见 foo）。
     * 注意：不查祖先的子目录——兄弟默认不可见（M3）；本地名与挂载名冲突已在 validate 报 E-DUP-DECL。
     */
    fun visibleChild(from: String, name: String): ModuleNode? {
        var m = nodesByPath[from] ?: return null
        m.children[name]?.let { return it }                       // 1. 自身子目录
        val directTargets = LinkedHashSet<String>()
        var cur: ModuleNode? = m
        while (cur != null) {
            for (md in cur.settings?.mounts ?: emptyList()) {
                val t = nodesByPath[md.targetAbsPath] ?: continue
                directTargets += t.absPath
                if (md.alias == name || (md.alias == null && t.name == name)) return t   // 2. 直接挂载
            }
            cur = if (cur.absPath.isEmpty()) null else nodesByPath[ancestorPathOf(cur.absPath)]
        }
        // 3. 传递挂载：目录名匹配的可见模块（排除已直接挂载者——别名已定名，防止绕过别名）
        // P28：分两段——先精确后代、再挂载传递——同名时后代优先，返回结果不再依赖 visiblePathsOf 构建序
        val visible = visiblePathsOf(from)
        for (p in visible) {
            if (p == from || !isDescendant(from, p)) continue
            val n = nodesByPath[p] ?: continue
            if (n.name == name) return n
        }
        for (p in visible) {
            if (p == from || p in directTargets || isDescendant(from, p)) continue
            val n = nodesByPath[p] ?: continue
            if (n.name == name) return n
        }
        return null
    }
}

// =====================================================================
// 跨模块限定引用（P0）：`a.b.c` → 名字链在模块树上的解析 + 符号定性
// =====================================================================

enum class QSymKind { FUN, CTOR, STRUCT, ENUM, CLASS, METHOD, MISSING }

data class QSym(val syms: Symbols, val name: String, val kind: QSymKind)

data class QChain(val node: ModuleNode, val symbol: QSym)

/** 目标模块 Symbols 内按名字定性。FUN 用 findFun（含内建 print/emptyTuple 等），其余查各自表。 */
private fun qualifyIn(s: Symbols, name: String): QSym = when {
    s.funs.containsKey(name) || s.findFun(name) != null -> QSym(s, name, QSymKind.FUN)
    s.ctors.containsKey(name) -> QSym(s, name, QSymKind.CTOR)
    s.structs.containsKey(name) -> QSym(s, name, QSymKind.STRUCT)
    s.enums.containsKey(name) -> QSym(s, name, QSymKind.ENUM)
    s.classes.containsKey(name) -> QSym(s, name, QSymKind.CLASS)
    s.methods.containsKey(name) || name in s.traitMethods -> QSym(s, name, QSymKind.METHOD)
    else -> QSym(s, name, QSymKind.MISSING)
}

/**
 * 解析纯名字链的跨模块限定：`a.b.c` → 模块链 [a,b]（相对当前模块可见）+ 符号 c。
 * 返回 null = 不是模块限定——首名字是局部变量（O2 字段优先）、或首段不是当前模块的可见子模块/挂载，
 * 或链含非名字节点。调用方回落原值层逻辑。
 */
internal fun Checker.resolveQChain(e: Expr, f: Frame): QChain? {
    if (moduleTree == null) return null
    val rev = ArrayList<String>()
    var cur = e
    while (true) {
        when (cur) {
            is NameRef -> { rev += cur.name; break }
            is FieldExpr -> { rev += cur.name; cur = cur.target }
            else -> return null
        }
    }
    if (rev.size < 2) return null
    val segs = rev.asReversed()
    if (f.lookupVar(segs[0]) != null) return null        // 值层优先（O2 字段优先）
    var node = moduleTree.nodeOf(modulePath) ?: return null
    for (i in 0 until segs.size - 1) {
        node = moduleTree.visibleChild(node.absPath, segs[i]) ?: return null
    }
    val name = segs.last()
    val s = allSymbols[node.absPath] ?: return null
    return QChain(node, qualifyIn(s, name))
}

/** 限定类型的模块解析：`c.Point` → c 模块节点（相对当前模块可见）。返回 null = 不可见/不存在。 */
internal fun Checker.resolveTypeModule(t: QualifiedType): ModuleNode? {
    if (moduleTree == null) return null
    var node = moduleTree.nodeOf(modulePath) ?: return null
    for (seg in t.module) {
        node = moduleTree.visibleChild(node.absPath, seg) ?: return null
    }
    return node
}
