plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api, not implementation: KStepScriptOutcome appears in PreviewOutcome.ScriptFailed's
    // public signature, so kstep-cli can keep routing it through its unchanged
    // printExportError path. Also transitively exposes kstep-shape/kstep-geometry/kstep-core/
    // kstep-step21 (kstep-script itself declares those as `api`).
    api(project(":kstep-script"))
    // api: RenderFormat/GlbWriteResult appear in PreviewRequest/PreviewOutcome signatures.
    api(project(":kstep-render"))
    // api: OcctAvailability appears in PreviewOutcome.Rendered.
    api(project(":kstep-geometry"))
    // implementation: Part21Writer is used only inside PreviewSummary's private helpers.
    implementation(project(":kstep-step21"))
    implementation(libs.kotlin.logging.jvm)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("kstep.occt.require", providers.gradleProperty("kstep.occt.require").getOrElse("false"))
}
