plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    group = "dev.kstep"
    version = "0.1.0-SNAPSHOT"
}
