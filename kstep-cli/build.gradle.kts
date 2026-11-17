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
    // SLF4J backend of the application (kstep-mcp, a published library, deliberately declares none).
    runtimeOnly(libs.slf4j.simple)
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
    // OcctKernel.availability() directly (not just transitively) for its `--output json`
    // `putOcct`, and reads GlbWriteResult fields for the same JSON document -- both declared
    // explicitly here for the same reason kotlinx-coroutines-core is above (this module uses
    // them directly, not just transitively).
    implementation(project(":kstep-geometry"))
    implementation(project(":kstep-constraints"))
    implementation(project(":kstep-render"))
    // `kstep import` (ADR-0030): StepImporter/StepImportValidation live in kstep-shape.
    implementation(project(":kstep-shape"))
    // kstep-asciidoc/kstep-preview extraction (see docs/adr/ADR-0019-kstep-asciidoc.adoc): `api`,
    // not `implementation` -- RenderFormat/PreviewRenderer/PreviewOutcome/PreviewWriter appear in
    // CliCommand.Render's public signature and RenderCommand.kt's own public functions, and
    // `kstep-tests`' CliMainTest constructs CliCommand.Render/CliCommand.Asciidoc directly.
    api(project(":kstep-preview"))
    api(project(":kstep-docs:kstep-asciidoc"))
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

// kstep-cli's own (small) test sourceset: planExportGeometry/countPlacedUsages are `internal`,
// and kstep-tests only sees this module's public API (same reasoning as kstep-shape).
dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kstep.expectedVersion", project.version.toString())
}

// Bakes the project version into the jar so `kstep --version` works from any launcher (IDE, tests,
// installDist, native archive) without reading a manifest.
val generateVersionResource =
    tasks.register("generateVersionResource") {
        val versionString = project.version.toString()
        val outDir = layout.buildDirectory.dir("generated/version-resource")
        inputs.property("version", versionString)
        outputs.dir(outDir)
        doLast {
            val dir = outDir.get().asFile
            dir.mkdirs()
            dir.resolve("kstep-cli-version.properties").writeText("version=$versionString\n")
        }
    }
sourceSets.main {
    resources.srcDir(generateVersionResource)
}

// ---------------------------------------------------------------------------------------------
// Opt-in native runtime archive (ADR-0050): kstep-<version>-<platform>-native.zip, host platform only.
// NOT part of assemble/check/release. Build with:
//   ./gradlew clean :kstep-cli:nativeRuntimeZip -Pkstep.native.embedInJar=false \
//       -Pkstep.occt.require=true -Pkstep.planegcs.require=true --no-build-cache
// ---------------------------------------------------------------------------------------------
val nativeHostOs: String = System.getProperty("os.name").orEmpty()
val nativeHostArch: String = System.getProperty("os.arch").orEmpty()
val nativeIsLinux = nativeHostOs.contains("Linux", ignoreCase = true)
val nativeIsMac = nativeHostOs.contains("Mac", ignoreCase = true)
val nativeIsX64 = nativeHostArch in setOf("amd64", "x86_64")
val nativeIsArm64 = nativeHostArch in setOf("aarch64", "arm64")
val nativePlatformId: String? =
    when {
        nativeIsLinux && nativeIsX64 -> "linux-x86-64"
        nativeIsMac && nativeIsArm64 -> "macos-aarch64"
        else -> null
    }
val nativeLibExt = if (nativePlatformId == "macos-aarch64") "dylib" else "so"
val nativeEmbedInJar =
    providers
        .gradleProperty("kstep.native.embedInJar")
        .map { it != "false" }
        .orElse(true)
        .get()
val nativeExcludeFlag = providers.gradleProperty("kstep.native.exclude").orNull == "true"
val nativeDirName = nativePlatformId ?: "unsupported"
val occtBridgeFile =
    project(":kstep-geometry").layout.buildDirectory.file("native/$nativeDirName/libkstep_occt_bridge.$nativeLibExt")
val planeGcsBridgeFile =
    project(":kstep-constraints").layout.buildDirectory.file(
        "native/$nativeDirName/libkstep_planegcs_bridge.$nativeLibExt",
    )
val nativeRootDir = "kstep-${project.version}-${nativePlatformId ?: "unsupported"}-native"

val generateNativeLauncher =
    tasks.register<Copy>("generateNativeLauncher") {
        val startScriptsTask = tasks.named<CreateStartScripts>("startScripts")
        dependsOn(startScriptsTask)
        val classpathNames = startScriptsTask.map { t -> t.classpath!!.files.map { it.name } }
        val jvmArgs = application.applicationDefaultJvmArgs.joinToString(" ") { "\"$it\"" }
        val mainClassName = application.mainClass
        val platform = nativePlatformId ?: "unsupported"
        inputs.property("jvmArgs", jvmArgs)
        inputs.property("platform", platform)
        from("src/native-dist/bin/kstep")
        into(layout.buildDirectory.dir("native-dist/bin"))
        doFirst {
            // expand() cannot be used: the launcher is full of shell $-variables. Tokens are @NAME@.
        }
        filter { line ->
            line
                .replace("@PLATFORM@", platform)
                .replace("@DEFAULT_JVM_OPTS@", jvmArgs)
                .replace("@MAIN_CLASS@", mainClassName.get())
                .replace("@CLASSPATH@", classpathNames.get().joinToString(":") { "\$APP_HOME/lib/$it" })
        }
        inputs.property("classpath", classpathNames)
    }

tasks.register<Zip>("nativeRuntimeZip") {
    group = "distribution"
    description =
        "Builds the opt-in native runtime archive (CLI + OCCT/PlaneGCS bridges as plain files; host platform only)."
    val platform = nativePlatformId
    if (platform != null) {
        archiveFileName.set("kstep-${project.version}-$platform-native.zip")
        destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    }
    val startScriptsTask = tasks.named<CreateStartScripts>("startScripts")
    dependsOn(
        generateNativeLauncher,
        startScriptsTask,
        ":kstep-geometry:compileOcctBridge",
        ":kstep-constraints:compilePlaneGcsBridge",
    )
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    into(nativeRootDir) {
        from(generateNativeLauncher) { into("bin") }
        from(startScriptsTask.map { it.classpath!! }) { into("lib") }
        from(occtBridgeFile) { into("lib/native/${platform ?: "unsupported"}") }
        from(planeGcsBridgeFile) { into("lib/native/${platform ?: "unsupported"}") }
        from("src/native-dist/NATIVE-README.txt")
        from(rootProject.file("LICENSE"))
        from(rootProject.file("NOTICE"))
    }
    eachFile {
        permissions { unix(if (path.endsWith("/bin/kstep")) "rwxr-xr-x" else "rw-r--r--") }
    }
    val occtPath = occtBridgeFile.map { it.asFile }
    val gcsPath = planeGcsBridgeFile.map { it.asFile }
    val embed = nativeEmbedInJar
    val excluded = nativeExcludeFlag
    val hostDesc = "$nativeHostOs/$nativeHostArch"
    doFirst(
        object : Action<Task> {
            override fun execute(t: Task) {
                if (platform == null) {
                    throw GradleException(
                        "native runtime distribution supports linux-x86-64 and macos-aarch64 only (host: $hostDesc)",
                    )
                }
                if (embed) {
                    throw GradleException(
                        "nativeRuntimeZip requires -Pkstep.native.embedInJar=false: otherwise the bridges are also " +
                            "embedded inside the kstep-geometry/kstep-constraints jars (the archive must ship " +
                            "them only as plain files under lib/native/$platform/).",
                    )
                }
                for (f in listOf(occtPath.get(), gcsPath.get())) {
                    if (!f.isFile || f.length() == 0L) {
                        throw GradleException(
                            "nativeRuntimeZip: ${f.path} missing -- the bridge was not built on this host " +
                                "(platform=$platform, kstep.native.exclude=$excluded, jni_md.h present?). " +
                                "Build with -Pkstep.occt.require=true -Pkstep.planegcs.require=true.",
                        )
                    }
                }
            }
        },
    )
}
