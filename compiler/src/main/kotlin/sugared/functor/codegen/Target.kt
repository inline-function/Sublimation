package sugared.functor.codegen

/**
 * 编译目标平台（t35，SfW Phase 1 依赖）。
 *
 * 设计依据：《安卓标准库设计》§4.2 的 Target 层构想；成稿时该节未落，
 * 实际形状由 SfW 仓《平台边界.md》§4 与《路线选型.md》§8 的探针结论（t34）决定：
 *  - --target=node（默认，向后兼容）：产物是 node 自执行脚本——IO 直连 require('fs')、
 *    有 main 时末尾自执行 main()。
 *  - --target=browser：S-1 装载模型——抑制 main() 自执行，把入口导出到
 *    globalThis.__subl.main（宿主已实现兼容路径）；平台调用走宿主桥单入口
 *    globalThis.__subl.d(op, args)。
 *  - --target=wasm：同 S-1 装载模型；同步 IO（readFile 等）在编译期拒绝
 *    （E-PLATFORM-UNAVAILABLE——wasm 单线程无同步宿主调用通道），其余平台调用走宿主桥。
 *
 * 宿主桥单入口 d 的命名是 t35 落地用的临时约定：若三线（SfA/SfG/SfW）宿主与处理器
 * 白名单统一命名空间，须由队长统筹三线联动修改，编译器侧再跟进（t35 任务约束）。
 *
 * 安卓侧既有行为不受影响：-A/SfA 路线不传 --target（默认 node），core/android
 * 等模块按 MODULE_AVAILABILITY 允许。
 */
enum class Target(val cliName: String) {
    NODE("node"),
    BROWSER("browser"),
    WASM("wasm");

    companion object {
        /** 默认目标（向后兼容：不传 --target 时一切如旧） */
        val DEFAULT = NODE

        /** CLI --target= 取值解析；非法取值抛 IllegalArgumentException（CLI 层转错误退出 2） */
        fun parse(raw: String?): Target {
            val s = raw?.trim()?.lowercase() ?: return DEFAULT
            return entries.firstOrNull { it.cliName == s }
                ?: throw IllegalArgumentException("未知 target：" + s + "（可选：node / browser / wasm）")
        }
    }
}

/**
 * 平台能力与可用性表（t35）：
 *  - BRIDGE_OPS：走宿主桥 globalThis.__subl.d(op, args) 的平台调用名；
 *  - WASM_REJECTED：wasm 下编译期直接拒绝的平台调用（同步 IO 等）；
 *  - MODULE_AVAILABILITY：模块级 target 可用性表（core 子包到可用 target 集合；未列 = 全 target 可用）。
 */
object Platform {
    /** 宿主桥 op 名。println/print 在 browser 上仍用 console.log（宿主有 console），
     *  在 wasm 上经桥（wasm 宿主未必提供 console）。 */
    val BRIDGE_OPS: Set<String> = setOf(
        "println", "print", "readLine", "readFile", "writeFile", "appendFile",
        "readDir", "fileExists", "getArgs", "sleep",
    )

    /** wasm 编译期拒绝的平台调用——无同步宿主调用通道。与 MODULE_AVAILABILITY 的 core/io 一致。 */
    val WASM_REJECTED: Set<String> = setOf(
        "readLine", "readFile", "writeFile", "appendFile", "readDir", "fileExists",
    )

    /** 模块级 target 可用性表：core 子包在哪个 target 可用（未列 = 全 target）。
     *  - core/io：node 直连 fs；browser 走宿主桥；wasm 无同步 IO。
     *  - core/net：原生套接字，仅 node。
     *  - core/android（SfA 仓，经 mount 挂载）：-A 路线默认 node，兼容保留。 */
    val MODULE_AVAILABILITY: Map<String, Set<Target>> = mapOf(
        "core/io" to setOf(Target.NODE, Target.BROWSER),
        "core/net" to setOf(Target.NODE),
        "core/android" to setOf(Target.NODE),
    )

    /** 模块路径是否在当前 target 可用。
     *  modulePath 为相对编译根的 "/" 连接路径（根模块 = ""，子模块如 "core/io"）——
     *  MultiModule 的模块键即此形式；按首段取 "core/<pkg>" 匹配可用性表。 */
    fun moduleAvailable(modulePath: String, target: Target): Boolean {
        if (!modulePath.startsWith("core/")) return true
        val pkg = modulePath.substringAfter("core/").substringBefore('/')
        return MODULE_AVAILABILITY["core/" + pkg]?.contains(target) ?: true
    }
}
