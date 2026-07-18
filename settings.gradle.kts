pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "kSTEP"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "kstep-core",
    "kstep-express",
    "kstep-step21",
    "kstep-cli",
    "kstep-tests",
)
