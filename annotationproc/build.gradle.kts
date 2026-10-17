plugins {
    kotlin("jvm") version "2.1.20"
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":frontend"))
    implementation(project(":compiler"))
    implementation(project(":interpreter"))
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    workingDir = rootDir
}
