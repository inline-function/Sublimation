plugins {
    kotlin("jvm") version "2.1.20"
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "sugared.functor.lsp.LspServer"
}

dependencies {
    implementation(project(":frontend"))
    implementation(project(":compiler"))
    // 脚本模式（.subs）：复用解释器的全局类型推导（GlobalInference），不引入运行时求值
    implementation(project(":interpreter"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
    testLogging {
        showStandardStreams = true
    }
}
