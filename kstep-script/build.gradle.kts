plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`, not `implementation`: these modules' types must be resolvable *inside*
    // `.kstep.kts` scripts via `dependenciesFromCurrentContext(wholeClasspath = true)`
    // (KStepScriptCompilationConfiguration's defaultImports reference dev.kstep.core.*,
    // dev.kstep.core.ap242.*, dev.kstep.step21.*, dev.kstep.geometry.*, and dev.kstep.shape.*),
    // not just from this module's own code.
    api(project(":kstep-core"))
    api(project(":kstep-step21"))
    // kSTEP headless-preview-rendering wave (see
    // docs/adr/ADR-0011-headless-preview-rendering.adoc): KStepModel.shape(...) registers a
    // dev.kstep.shape.ShapeAssignment, so this module's public API now surfaces that type
    // directly. Transitively brings kstep-geometry's OcctShape/OcctKernel/TriangleMesh types
    // along too (kstep-shape declares that as `api` itself) -- exactly what a script needs to
    // build and register geometry in one module dependency.
    api(project(":kstep-shape"))

    implementation(libs.kotlin.scripting.common)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.logging.jvm)
}
