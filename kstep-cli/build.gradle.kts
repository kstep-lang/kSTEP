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
    // Kotlin scripting's per-invocation compile step (kstep-script's KStepScriptHost, used by
    // `kstep export`) is the dominant cost of that command's runtime -- capping the JIT tiering
    // level shortens that one-shot compile without needing steady-state peak throughput, the
    // same tradeoff kUML's CLI makes for its own script-heavy commands.
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1")
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
    // `kstep export` (M2 Welle 6): KStepScriptHost/KStepScriptOutcome for compiling and running
    // *.kstep.kts scripts. Transitively brings kotlin-compiler-embeddable onto this module's
    // runtimeClasspath (and so into installDist's lib/) -- expected, not a regression, see
    // kstep-script's own KDoc and the wave's plan for why.
    implementation(project(":kstep-script"))
    // Renders `kstep export --output json`'s structured result/error document. kstep-mcp
    // already depends on this transitively (via mcp-kotlin-sdk-server), but only as
    // `implementation`, so kstep-cli needs its own explicit declaration to use it directly --
    // same reasoning as the kotlinx-coroutines-core dependency above.
    implementation(libs.kotlinx.serialization.json)
}
