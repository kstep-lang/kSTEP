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
    // ShapeCanvas now consumes dev.kstep.render.mesh.MeshProjection from there instead of
    // owning the projection math itself.
    implementation(project(":kstep-render"))
    implementation(libs.kotlin.logging.jvm)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // Test-only (never reaches kstep-cli's runtimeClasspath, which is what ADR-0012's Security
    // section's "no new external dependency" actually guards -- see that ADR's "Resource leak /
    // supply chain" row): the officially-supported Compose Desktop UI-testing harness
    // (runDesktopComposeUiTest, performMouseInput { drag()/doubleClick() }, performKeyInput {
    // pressKey(...) }), used by ViewerCanvasInteractionWiringTest to drive ShapeCanvas's real
    // gesture-detection pipeline instead of either calling CameraInteraction's pure functions
    // directly or hand-rolling raw ImageComposeScene.sendPointerEvent sequences (which turned out
    // NOT to reliably reproduce a real double-click/drag's exact event sequence -- press/button
    // state and event timing that MouseInjectionScope's own click()/doubleClick() already get
    // right).
    testImplementation(libs.compose.ui.test.junit4)
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
