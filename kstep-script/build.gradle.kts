plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`, not `implementation`: these two modules' types must be resolvable *inside*
    // `.kstep.kts` scripts via `dependenciesFromCurrentContext(wholeClasspath = true)`
    // (KStepScriptCompilationConfiguration's defaultImports reference dev.kstep.core.*,
    // dev.kstep.core.ap242.*, and dev.kstep.step21.*), not just from this module's own code.
    api(project(":kstep-core"))
    api(project(":kstep-step21"))

    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.logging.jvm)
}
