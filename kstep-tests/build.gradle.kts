plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kstep-express"))
    implementation(project(":kstep-core"))
    implementation(project(":kstep-step21"))
    implementation(project(":kstep-script"))
    implementation(project(":kstep-mcp"))
    implementation(project(":kstep-cli"))
    implementation(project(":kstep-geometry"))
    implementation(libs.antlr4.runtime)
    testImplementation(libs.kotlinpoet)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mcp.kotlin.sdk.server)
    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.slf4j.simple)
    // kotlin-logging: OcctBridgeSmokeTest logs a diagnostic line with the resolved
    // OcctAvailability when -Pkstep.occt.require isn't set, matching every other module's
    // logging convention (see kstep-mcp/kstep-script) rather than a raw println.
    testImplementation(libs.kotlin.logging.jvm)
    // kotlin-reflect: Ap242CoreSchemaConsistencyTest inspects kstep-core data classes'
    // primary-constructor parameters (name/type/nullability) via kotlin-reflect to compare
    // their shape against the real AP242 schema -- not needed by any other test in this module.
    testImplementation(libs.kotlin.reflect)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // OcctBridgeSmokeTest reads this at runtime: when "true", the test suite hard-fails if the
    // native OCCT bridge is NOT available (dev.kstep.geometry.OcctKernel.availability() reports
    // Unavailable), instead of silently skipping the OCCT-gated tests. Off by default so
    // ./gradlew check stays green on a machine without the OCCT dev packages installed; pass
    // -Pkstep.occt.require=true on a machine that must guarantee OCCT is actually present. See
    // README 'Building' and docs/adr/ADR-0005-occt-jni-bridge.adoc.
    systemProperty("kstep.occt.require", providers.gradleProperty("kstep.occt.require").getOrElse("false"))
}
