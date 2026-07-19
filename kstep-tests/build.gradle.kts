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
    implementation(project(":kstep-mcp"))
    implementation(libs.antlr4.runtime)
    testImplementation(libs.kotlinpoet)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mcp.kotlin.sdk.server)
    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.mcp.kotlin.sdk.client)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
