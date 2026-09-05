plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api: RenderFormat/PreviewRequest/PreviewOutcome surface through AsciidocOptions and
    // AsciidocRewriter's return types, which kstep-cli's CliCommand.Asciidoc/AsciidocCommand.kt
    // consume directly. DELIBERATELY no dependency on :kstep-cli -- kstep-cli depends on THIS
    // module to expose the subcommand, so the reverse edge would be a cycle. Enforced by
    // AsciidocModuleBoundaryTest.
    api(project(":kstep-preview"))
    // No `kotlin-logging` dependency here (unlike most modules, see the Kotlin-code-wide
    // logging convention): this module is deliberately I/O-/diagnostics-free at the library
    // level -- every failure surfaces to the caller through ProcessFailure/warnings return
    // values instead (see AsciidocProcessor.kt, AsciidocCommand.kt), never a log line.
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("kstep.occt.require", providers.gradleProperty("kstep.occt.require").getOrElse("false"))
}
