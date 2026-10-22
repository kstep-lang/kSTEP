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

// The MCP server reports its version to clients; it reads it from the jar manifest so it can never drift
// from the project version (it used to be a hard-coded "0.1.0", also in the 0.1.1 release).
tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "kstep-mcp",
            "Implementation-Version" to project.version.toString(),
        )
    }
}
