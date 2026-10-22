plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`: ProfileRegion, ProfilePoint, OcctShape and the region Feature types appear in this
    // module's public signatures (SolvedSketch.toRegion/extrude/extrudeFeature), and `.kstep.kts`
    // scripts reach them through the script classpath.
    api(project(":kstep-geometry"))
    // `implementation`: no constraint type leaks into the public API (the DSL has its own error
    // types), so scripts only ever need `dev.kstep.sketch.*`. kstep-constraints ships the PlaneGCS
    // JNI bridge; this module itself contains no native code.
    implementation(project(":kstep-constraints"))
    implementation(libs.kotlin.logging.jvm)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
