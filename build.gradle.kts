allprojects {
    group = "sugared.functor"
    version = "1.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// 测试工作目录统一指向仓库根，使 tests 内的相对 File("stdlib") / File("examples/...") 可解析。
// 注意：workingDir 须在各模块的 tasks.test { } 内以 rootDir 设置（root/subprojects 的 lazy configureEach 会被 KGP 重置）。
