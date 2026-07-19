plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":kstep-core"))
    implementation(project(":kstep-step21"))
    implementation(libs.mcp.kotlin.sdk.server)
    implementation(libs.kotlin.logging.jvm)
    runtimeOnly(libs.slf4j.simple)
}
