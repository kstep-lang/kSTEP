plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.kstep.cli.MainKt")
}

dependencies {
    implementation(project(":kstep-mcp"))
    // kstep-mcp declares this as `implementation`, not `api`, so it isn't exposed on kstep-cli's
    // compile classpath transitively (Gradle's implementation/api split hides implementation deps
    // from downstream consumers even for project(...) dependencies). kstep-cli's Main.kt calls
    // runBlocking { runStdioServer() } directly, so it needs kotlinx-coroutines-core on its own
    // compile classpath too. Version pinned to what's already resolved transitively across this
    // build (see kstep-mcp's runtimeClasspath) -- not a new artifact entering the dependency
    // graph, just the same one declared explicitly where it's used directly.
    implementation(libs.kotlinx.coroutines.core)
}
