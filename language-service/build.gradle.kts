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
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
    testLogging {
        showStandardStreams = true
    }
}
