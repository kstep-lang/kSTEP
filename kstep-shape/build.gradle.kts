plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // All three `api`, not `implementation`: ProductDefinition, OcctShape, Part21Header and
    // Part21Document all appear in this module's own public signatures (ShapeAssignment,
    // Ap242ShapeExporter.export) -- with `implementation` they would be unresolvable for
    // kstep-tests, exactly the problem kstep-core/build.gradle.kts already documents for
    // kstep-express.
    api(project(":kstep-core"))
    api(project(":kstep-step21"))
    api(project(":kstep-geometry"))
    implementation(libs.kotlin.logging.jvm)

    // kstep-shape gets its own (small) test sourceset rather than living entirely in
    // kstep-tests, because Ap242GeometryExtraction is `internal`, not public -- kstep-tests only
    // ever depends on this module's public API (implementation(project(":kstep-shape"))), so it
    // cannot see that object. This module's own `test` sourceset can, via the Kotlin Gradle
    // plugin's default main/test associated compilation -- same reasoning as
    // kstep-geometry/build.gradle.kts (OcctNativeLibrary.resolveLibraryPath) and
    // kstep-constraints/build.gradle.kts (PlaneGcsNativeLibrary.resolveLibraryPath).
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
