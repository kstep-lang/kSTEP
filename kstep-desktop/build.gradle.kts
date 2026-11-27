import org.gradle.process.CommandLineArgumentProvider
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.kotlin.jvm) // NOT multiplatform -- kSTEP is a plain-JVM project throughout.
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ktlint)
    application
}

kotlin {
    jvmToolchain(21) // JDK policy: kSTEP as a whole stays on 21 (see CLAUDE.md's JDK-Versions-Policy).
}

application {
    mainClass.set("dev.kstep.desktop.MainKt")
}

dependencies {
    implementation(project(":kstep-viewer"))
    // ImportWorkerSupervisor/ImportWorkerLaunch/DesktopRenderWorker (ADR-0051). Not published, so public-for-desktop is
    // fine; it also puts the script compiler on the desktop's classpath, which no desktop source may use (see
    // DesktopArchitectureTest): scripts are only ever evaluated in the worker subprocess.
    implementation(project(":kstep-cli"))
    implementation(project(":kstep-desktop-ai"))
    implementation(project(":kstep-geometry"))
    implementation(project(":kstep-render"))
    implementation(libs.kotlin.logging.jvm)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.material3)
    // Same logging backend as kstep-viewer and kstep-cli (slf4j-simple is the repo standard, no logback).
    runtimeOnly(libs.slf4j.simple)
    // ExportNamingParityTest compares the desktop's file-name rules with the script's (ADR-0063); test code only.
    testImplementation(project(":kstep-script"))
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

/**
 * Hands the runtime class path of :kstep-cli to the worker subprocess (system property
 * `kstep.desktop.workerClasspath`), so the script compiler does not see Compose/Skiko jars (`wholeClasspath = true`).
 * An argument provider instead of a lambda: nothing here captures the build script (configuration cache).
 */
abstract class WorkerClasspathArg : CommandLineArgumentProvider {
    @get:Classpath
    abstract val cp: ConfigurableFileCollection

    override fun asArguments(): Iterable<String> = listOf("-Dkstep.desktop.workerClasspath=" + cp.asPath)
}

// A resolvable configuration of THIS project (resolving another project's runtimeClasspath is unsafe): the runtime
// variant of :kstep-cli, i.e. its jar plus everything it needs to run, without the desktop's Compose jars.
val workerRuntime: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME)) }
}
dependencies { workerRuntime(project(":kstep-cli")) }

val workerClasspathArg =
    objects.newInstance<WorkerClasspathArg>().apply {
        cp.from(workerRuntime)
    }

// The native bridge overrides are plain strings read once at configuration time (no script references in lambdas).
val occtBridgeLibrary: String? = providers.gradleProperty("kstep.occt.bridge.library").orNull
val planeGcsBridgeLibrary: String? = providers.gradleProperty("kstep.planegcs.bridge.library").orNull
val sandboxRequire: String = providers.gradleProperty("kstep.sandbox.require").getOrElse("false")
val occtRequire: String = providers.gradleProperty("kstep.occt.require").getOrElse("false")
val fixturesDir: String = file("src/test/resources").absolutePath
val desktopBench: String = providers.gradleProperty("kstep.desktop.bench").getOrElse("false")
val warmWorkerMode: String? = providers.gradleProperty("kstep.desktop.warmWorker").orNull
val packagedApp: String? = providers.gradleProperty("kstep.desktop.packagedApp").orNull

tasks.named<JavaExec>("run") {
    jvmArgumentProviders.add(workerClasspathArg)
    // The warm render worker's kill switch (ADR-0081): -Pkstep.desktop.warmWorker=off|spare|reuse.
    warmWorkerMode?.let { jvmArgs("-Dkstep.desktop.warmWorker=$it") }
    occtBridgeLibrary?.let { jvmArgs("-Dkstep.occt.bridge.library=$it") }
    planeGcsBridgeLibrary?.let { jvmArgs("-Dkstep.planegcs.bridge.library=$it") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgumentProviders.add(workerClasspathArg)
    systemProperty("java.awt.headless", "true")
    // The MCP server logs method, status and tool names at DEBUG, never tokens or script text (ADR-0095): the tests
    // turn that level on to check it.
    systemProperty("org.slf4j.simpleLogger.log.dev.kstep.desktop.mcp", "debug")
    systemProperty("kstep.occt.require", occtRequire)
    systemProperty("kstep.sandbox.require", sandboxRequire)
    systemProperty("kstep.desktop.fixtures", fixturesDir)
    systemProperty("kstep.desktop.bench", desktopBench)
    // The packaged-app tests (ADR-0082) only run when a built kSTEP.app is handed over; they skip themselves otherwise.
    packagedApp?.let { systemProperty("kstep.desktop.packagedApp", it) }
    // Only with an explicit -P: the bridge case of the packaged-app tests (and the forwarded worker property).
    occtBridgeLibrary?.let { systemProperty("kstep.occt.bridge.library", it) }
    dependsOn(":kstep-cli:jar")
}

// ---------------------------------------------------------------------------------------------------------------------
// Packaging the desktop as a macOS app (ADR-0082): plain jlink + jpackage, NOT Compose's nativeDistributions (its jlink
// strips bin/java, which the worker needs; it also adds its own `run` task and renames jars by content hash). Not
// attached to check/assemble/build: only `packageDmg` / `createDistributable` run it. macOS-only, JVM-only (no native
// bridge in the jars: -Pkstep.native.exclude=true is mandatory), unsigned (ad hoc, see the ADR).
// ---------------------------------------------------------------------------------------------------------------------

/** Writes the jar file names of the worker class path, one per line, in resolution order (the manifest of the worker lib). */
abstract class WorkerManifestTask : DefaultTask() {
    @get:Classpath
    abstract val cp: ConfigurableFileCollection

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun write() {
        val names = cp.files.map { it.name }
        val bad = names.filter { !it.endsWith(".jar") }
        if (bad.isNotEmpty()) throw GradleException("the worker class path has non-jar entries: $bad")
        val dupes = names.groupBy { it }.filterValues { it.size > 1 }.keys
        if (dupes.isNotEmpty()) throw GradleException("duplicate worker jar file names: $dupes")
        manifest.get().asFile.writeText(names.joinToString("\n", postfix = "\n"))
    }
}

/** Common inputs of the packaging tasks. */
abstract class PackagingTask : DefaultTask() {
    @get:Input
    abstract val macos: Property<Boolean>

    @get:Input
    abstract val jdkHome: Property<String>

    @get:Inject
    abstract val execOps: ExecOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    protected fun requireMacos() {
        if (!macos.get()) throw GradleException("desktop packaging is macOS-only in this wave (ADR-0082)")
    }

    protected fun tool(name: String): String = File(jdkHome.get(), "bin/$name").absolutePath

    protected fun runTool(vararg command: String): String {
        val out = ByteArrayOutputStream()
        val result =
            execOps.exec {
                commandLine(*command)
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        if (result.exitValue != 0) {
            throw GradleException("${File(command.first()).name} failed (exit ${result.exitValue}):\n$out")
        }
        return out.toString()
    }
}

/** jlink the runtime of the app. Never `--strip-native-commands`: the worker needs bin/java (ADR-0082, S1). */
abstract class JlinkRuntimeTask : PackagingTask() {
    @get:Input
    abstract val baseModules: ListProperty<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun link() {
        requireMacos()
        val modules = baseModules.get().sorted()
        logger.lifecycle("jlink modules (${modules.size}): ${modules.joinToString(",")}")
        val out = output.get().asFile
        fs.delete { delete(out) }
        runTool(
            tool("jlink"),
            "--add-modules",
            modules.joinToString(","),
            "--strip-debug",
            "--no-header-files",
            "--no-man-pages",
            "--output",
            out.absolutePath,
        )
    }
}

/** jpackage: `app-image` of the desktop with the bundled runtime and the worker folder as app content. */
abstract class AppImageTask : PackagingTask() {
    @get:Input
    abstract val nativeExclude: Property<String>

    @get:Input
    abstract val appVersion: Property<String>

    @get:Input
    abstract val mainJar: Property<String>

    @get:InputDirectory
    abstract val inputDir: DirectoryProperty

    @get:InputDirectory
    abstract val runtimeDir: DirectoryProperty

    @get:InputDirectory
    abstract val workerDir: DirectoryProperty

    @get:OutputDirectory
    abstract val destDir: DirectoryProperty

    @TaskAction
    fun create() {
        requireMacos()
        if (nativeExclude.get() != "true") {
            throw GradleException(
                "desktop packaging is the JVM-only line: run with -Pkstep.native.exclude=true (ADR-0082)",
            )
        }
        val dest = destDir.get().asFile
        fs.delete { delete(dest) }
        dest.parentFile.mkdirs()
        runTool(
            tool("jpackage"),
            "--type",
            "app-image",
            "--name",
            "kSTEP",
            "--dest",
            dest.absolutePath,
            "--input",
            inputDir.get().asFile.absolutePath,
            "--main-jar",
            mainJar.get(),
            "--main-class",
            "dev.kstep.desktop.MainKt",
            "--runtime-image",
            runtimeDir.get().asFile.absolutePath,
            "--app-content",
            workerDir.get().asFile.absolutePath,
            // $APPDIR is expanded by the launcher, not by a shell: it must arrive as a literal.
            "--java-options",
            "-Dkstep.desktop.workerLibDir=\$APPDIR/../kstep-worker",
            "--app-version",
            appVersion.get(),
            "--mac-package-identifier",
            "dev.kstep.desktop",
            "--mac-package-name",
            "kSTEP",
            "--vendor",
            "kSTEP",
        )
    }
}

/** jpackage: the dmg around the verified app image. */
abstract class DmgTask : PackagingTask() {
    @get:Input
    abstract val appVersion: Property<String>

    @get:InputDirectory
    abstract val appDir: DirectoryProperty

    @get:OutputDirectory
    abstract val destDir: DirectoryProperty

    @TaskAction
    fun dmg() {
        requireMacos()
        val dest = destDir.get().asFile
        fs.delete { delete(dest) }
        dest.mkdirs()
        runTool(
            tool("jpackage"),
            "--type",
            "dmg",
            "--app-image",
            File(appDir.get().asFile, "kSTEP.app").absolutePath,
            "--name",
            "kSTEP",
            "--app-version",
            appVersion.get(),
            "--dest",
            dest.absolutePath,
        )
    }
}

/** Runs scripts/desktop/verify-desktop-app.sh on the app image. */
abstract class VerifyAppTask : PackagingTask() {
    @get:InputDirectory
    abstract val appDir: DirectoryProperty

    @get:InputFile
    abstract val script: RegularFileProperty

    @TaskAction
    fun verify() {
        requireMacos()
        val app = File(appDir.get().asFile, "kSTEP.app")
        val out = ByteArrayOutputStream()
        val result =
            execOps.exec {
                environment("JAVA_HOME", jdkHome.get())
                commandLine("bash", script.get().asFile.absolutePath, app.absolutePath)
                standardOutput = out
                errorOutput = out
                isIgnoreExitValue = true
            }
        logger.lifecycle(out.toString())
        if (result.exitValue != 0) throw GradleException("verify-desktop-app.sh failed (exit ${result.exitValue})")
    }
}

/** jpackage on macOS rejects a leading 0: 0.MINOR.PATCH becomes MINOR.PATCH.0 (0.5.0 -> 5.0.0), see ADR-0082. */
fun macAppVersion(version: String): String {
    val parts = version.split('.').map { it.toIntOrNull() ?: 0 }
    val (major, minor, patch) = Triple(parts.getOrElse(0) { 0 }, parts.getOrElse(1) { 0 }, parts.getOrElse(2) { 0 })
    return if (major == 0) "${maxOf(minor, 1)}.$patch.0" else "$major.$minor.$patch"
}

val packagingIsMacos: Boolean =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .startsWith("mac")
val packagingNativeExclude: String = providers.gradleProperty("kstep.native.exclude").getOrElse("false")
val packagingVersion: String = macAppVersion(project.version.toString())
val packagingDir = layout.buildDirectory.dir("packaging")
val packagingJdk: Provider<String> =
    javaToolchains
        .launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.asFile.absolutePath }

// The JDK modules the app and the worker get (a fixed list: `jdeps --print-module-deps` cannot analyse this class path,
// it aborts on the modular kotlinx-coroutines-reactive jar whose optional org.reactivestreams module is absent). The list
// covers the Kotlin script compiler, AWT/Skiko, the AI client's TLS (jdk.crypto.ec), zip file systems and charsets.
// A script sees only these modules; a missing one surfaces as a script error, not as a crash (ADR-0082).
val packagingBaseModules =
    listOf(
        "java.base",
        "java.compiler",
        "java.datatransfer",
        "java.desktop",
        "java.instrument",
        "java.logging",
        "java.management",
        "java.naming",
        "java.net.http",
        "java.prefs",
        "java.scripting",
        "java.sql",
        "java.xml",
        "jdk.accessibility",
        "jdk.charsets",
        "jdk.crypto.ec",
        "jdk.httpserver",
        "jdk.management",
        "jdk.unsupported",
        "jdk.zipfs",
    )

val workerManifest =
    tasks.register<WorkerManifestTask>("desktopWorkerManifest") {
        group = "packaging"
        cp.from(workerRuntime)
        manifest.set(packagingDir.map { it.file("manifest/worker-classpath.txt") })
    }

val desktopWorkerLib =
    tasks.register<Sync>("desktopWorkerLib") {
        group = "packaging"
        duplicatesStrategy = DuplicatesStrategy.FAIL
        from(workerRuntime)
        from(workerManifest.flatMap { it.manifest })
        into(packagingDir.map { it.dir("kstep-worker") })
    }

// Flat, and without the worker jars: jpackage puts every jar below --input on the launcher class path.
val desktopAppInput =
    tasks.register<Sync>("desktopAppInput") {
        group = "packaging"
        duplicatesStrategy = DuplicatesStrategy.FAIL
        from(tasks.named("jar"))
        from(configurations.named("runtimeClasspath"))
        into(packagingDir.map { it.dir("input") })
    }

val desktopRuntimeImage =
    tasks.register<JlinkRuntimeTask>("desktopRuntimeImage") {
        group = "packaging"
        macos.set(packagingIsMacos)
        jdkHome.set(packagingJdk)
        dependsOn(desktopAppInput, desktopWorkerLib)
        baseModules.set(packagingBaseModules)
        output.set(packagingDir.map { it.dir("runtime") })
    }

val createDistributable =
    tasks.register<AppImageTask>("createDistributable") {
        group = "packaging"
        description = "Builds build/packaging/app/kSTEP.app (macOS, JVM-only: needs -Pkstep.native.exclude=true)."
        macos.set(packagingIsMacos)
        jdkHome.set(packagingJdk)
        nativeExclude.set(packagingNativeExclude)
        appVersion.set(packagingVersion)
        mainJar.set(tasks.named<Jar>("jar").flatMap { it.archiveFileName })
        inputDir.set(packagingDir.map { it.dir("input") })
        runtimeDir.set(packagingDir.map { it.dir("runtime") })
        workerDir.set(packagingDir.map { it.dir("kstep-worker") })
        destDir.set(packagingDir.map { it.dir("app") })
        dependsOn(desktopAppInput, desktopWorkerLib, desktopRuntimeImage)
    }

val verifyDesktopApp =
    tasks.register<VerifyAppTask>("verifyDesktopApp") {
        group = "packaging"
        macos.set(packagingIsMacos)
        jdkHome.set(packagingJdk)
        appDir.set(createDistributable.flatMap { it.destDir })
        script.set(rootProject.layout.projectDirectory.file("scripts/desktop/verify-desktop-app.sh"))
    }

tasks.register<DmgTask>("packageDmg") {
    group = "packaging"
    description = "Builds build/packaging/dmg/kSTEP-<version>.dmg from the verified app (macOS, unsigned, JVM-only)."
    macos.set(packagingIsMacos)
    jdkHome.set(packagingJdk)
    appVersion.set(packagingVersion)
    appDir.set(createDistributable.flatMap { it.destDir })
    destDir.set(packagingDir.map { it.dir("dmg") })
    dependsOn(verifyDesktopApp)
}
