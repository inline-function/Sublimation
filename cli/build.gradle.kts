plugins {
    kotlin("jvm") version "2.1.20"
    application
}

application {
    mainClass.set("sugared.functor.MainKt")
    // 注解处理器（.subs 脚本经 Evaluator 递归执行）偶发深帧 StackOverflow：放宽线程栈。
    applicationDefaultJvmArgs = listOf("-Xss16m")
}
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":frontend"))
    implementation(project(":compiler"))
    implementation(project(":interpreter"))
    implementation(project(":language-service"))
    implementation(project(":annotationproc"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Fat jar (含 Kotlin stdlib 与全部模块), 独立运行不依赖 gradle。
// 产物仍落在仓库根 build/libs/sublimation.jar，兼容原 README 的目录约定。
val rootBuildDir = rootProject.layout.buildDirectory
tasks.register<Jar>("fatJar") {
    archiveFileName.set("sublimation.jar")
    destinationDirectory.set(rootBuildDir.dir("libs"))
    manifest { attributes["Main-Class"] = "sugared.functor.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
