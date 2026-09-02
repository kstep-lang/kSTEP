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
    // `kstep export`/`kstep render`) is the dominant cost of that command's runtime -- capping
    // the JIT tiering level shortens that one-shot compile without needing steady-state peak
    // throughput, the same tradeoff kUML's CLI makes for its own script-heavy commands.
    // -Djava.awt.headless=true: `kstep render --format png` rasterizes via
    // java.awt.image.BufferedImage/Graphics2D -- this CLI never opens a window, and setting this
    // BEFORE GraphicsEnvironment is ever touched avoids it probing for (and potentially failing
    // on) a display server on a headless machine.
    applicationDefaultJvmArgs = listOf("-XX:TieredStopAtLevel=1", "-Djava.awt.headless=true")
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
    // `kstep render` (headless-preview-rendering wave, see
    // docs/adr/ADR-0011-headless-preview-rendering.adoc): RenderCommand.kt calls
    // OcctKernel.availability()/OcctShape.triangulate directly (not just transitively through
    // kstep-script's api chain), and TriangleSvgWriter/TriangleRasterizer/TextCardRenderer/
    // RenderLimits from kstep-render -- both declared explicitly here for the same reason
    // kotlinx-coroutines-core is above (this module uses them directly, not just transitively).
    implementation(project(":kstep-geometry"))
    implementation(project(":kstep-render"))
    // Main.kt sets KotlinLoggingConfiguration.logStartupMessage = false as its very first
    // statement -- see that assignment's KDoc for why. Declared explicitly here (not just
    // transitively via kstep-script/kstep-geometry's own `implementation` deps, which do NOT
    // expose it to this module's compile classpath) because Main.kt references the type
    // directly.
    implementation(libs.kotlin.logging.jvm)
    // Renders `kstep export --output json`/`kstep render --output json`'s structured
    // result/error document. kstep-mcp already depends on this transitively (via
    // mcp-kotlin-sdk-server), but only as `implementation`, so kstep-cli needs its own explicit
    // declaration to use it directly -- same reasoning as the kotlinx-coroutines-core dependency
    // above.
    implementation(libs.kotlinx.serialization.json)
}
