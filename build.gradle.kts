plugins {
    kotlin("jvm") version "2.1.20"
    application
}

group = "sugared.functor"
version = "1.1-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("sugared.functor.MainKt")
}

// Fat jar（含 Kotlin stdlib）——供 `java -jar` 独立运行，不依赖 gradle。
tasks.register<Jar>("fatJar") {
    archiveFileName.set("sublimation.jar")
    manifest { attributes["Main-Class"] = "sugared.functor.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
