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
    // GlbWriter (docs/adr/ADR-0016-gltf-glb-export.adoc) builds its glTF JSON document via
    // buildJsonObject, not a handwritten string escaper -- asset.extras carries script-supplied,
    // attacker-reachable text, and correct JSON escaping is a security property worth getting
    // from a maintained library rather than reimplementing (see that ADR's Decision section on
    // why this is a deliberate, narrow exception to this module's prior "zero new external
    // dependencies" stance -- ADR-0011). No new artifact enters kstep-cli's build: this
    // coordinate is already on its runtimeClasspath via kstep-mcp/kstep-script and declared
    // explicitly there for the same "used directly, not just transitively" reason as here.
    implementation(libs.kotlinx.serialization.json)
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
