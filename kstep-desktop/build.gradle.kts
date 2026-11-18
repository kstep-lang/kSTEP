import org.gradle.process.CommandLineArgumentProvider

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
val occtRequire: String = providers.gradleProperty("kstep.occt.require").getOrElse("false")
val fixturesDir: String = file("src/test/resources").absolutePath

tasks.named<JavaExec>("run") {
    jvmArgumentProviders.add(workerClasspathArg)
    occtBridgeLibrary?.let { jvmArgs("-Dkstep.occt.bridge.library=$it") }
    planeGcsBridgeLibrary?.let { jvmArgs("-Dkstep.planegcs.bridge.library=$it") }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgumentProviders.add(workerClasspathArg)
    systemProperty("java.awt.headless", "true")
    systemProperty("kstep.occt.require", occtRequire)
    systemProperty("kstep.desktop.fixtures", fixturesDir)
    dependsOn(":kstep-cli:jar")
}

// No compose.desktop { nativeDistributions }, no jpackage: packaging is a follow-up wave (D1b, ADR-0051).
