plugins {
    alias(libs.plugins.kotlin.jvm) // NOT multiplatform -- kSTEP is a plain-JVM project throughout.
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21) // JDK policy: kSTEP as a whole stays on 21 (see CLAUDE.md's JDK-Versions-Policy).
}

application {
    mainClass.set("dev.kstep.viewer.ui.MainKt")
}

dependencies {
    implementation(project(":kstep-geometry"))
    // Mesh/rasterizer/SVG code moved out of this module into kstep-render in kSTEP's
    // headless-preview-rendering wave (see docs/adr/ADR-0011-headless-preview-rendering.adoc) --
    // ShapeCanvas now consumes dev.kstep.render.mesh.IsometricProjection from there instead of
    // owning the projection math itself.
    implementation(project(":kstep-render"))
    implementation(libs.kotlin.logging.jvm)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // ViewerCanvasZeroSizeTest exercises Compose's ImageComposeScene -- plain JDK, no display
    // server required, but still benefits from a headless AWT/Graphics2D environment.
    systemProperty("java.awt.headless", "true")
}

// No compose.desktop { application { nativeDistributions { ... } } } in this wave --
// packaging (jpackage) is Folge-Welle V-10, see docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc.
