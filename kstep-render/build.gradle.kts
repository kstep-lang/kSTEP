plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // api, not implementation: TriangleMesh appears in the public signature of
    // IsometricProjection.project (this module's mesh package, moved here from kstep-viewer in
    // this wave -- see docs/adr/ADR-0011-headless-preview-rendering.adoc).
    api(project(":kstep-geometry"))
    implementation(libs.kotlin.logging.jvm)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // TriangleRasterizerTest/RenderOcctPipelineTest rasterize via java.awt.image.BufferedImage +
    // Graphics2D -- plain JDK, no display server required.
    systemProperty("java.awt.headless", "true")
    // Same OCCT-required guard as kstep-tests/kstep-viewer -- RenderOcctPipelineTest is
    // OCCT-gated the same way OcctFeatureOperationsTest is; see README 'Building'.
    systemProperty("kstep.occt.require", providers.gradleProperty("kstep.occt.require").getOrElse("false"))
}
