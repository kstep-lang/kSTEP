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
    implementation(libs.antlr4.runtime)
    testImplementation(libs.kotlinpoet)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mcp.kotlin.sdk.server)
    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.slf4j.simple)
    // kotlin-reflect: Ap242CoreSchemaConsistencyTest inspects kstep-core data classes'
    // primary-constructor parameters (name/type/nullability) via kotlin-reflect to compare
    // their shape against the real AP242 schema -- not needed by any other test in this module.
    testImplementation(libs.kotlin.reflect)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
