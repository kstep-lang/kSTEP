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
    implementation(libs.kotlin.logging.jvm)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // ViewerRasterTest/ViewerOcctPipelineTest rasterize via java.awt.image.BufferedImage +
    // Graphics2D -- plain JDK, no display server required.
    systemProperty("java.awt.headless", "true")
    // Same OCCT-required guard as kstep-tests -- ViewerOcctPipelineTest is OCCT-gated the same
    // way OcctFeatureOperationsTest is; see README 'Building'.
    systemProperty("kstep.occt.require", providers.gradleProperty("kstep.occt.require").getOrElse("false"))
}

// No compose.desktop { application { nativeDistributions { ... } } } in this wave --
// packaging (jpackage) is Folge-Welle V-10, see docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc.
