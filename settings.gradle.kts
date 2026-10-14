plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "sublimation"

include(":frontend")
include(":compiler")
include(":interpreter")
include(":language-service")
include(":annotationproc")
include(":cli")
