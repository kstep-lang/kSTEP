plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.koog.prompt.executor.anthropic.client)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.logging.jvm)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.slf4j.simple)
}

val testsResourcesDir: String = rootProject.file("kstep-tests/src/test/resources").absolutePath

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("kstep.tests.resources", testsResourcesDir)
    // DEBUG as default level proves what would leak if Koog's own loggers were not silenced; AiLogging.quiet() is
    // what Main applies (ADR-0056).
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "debug")
    systemProperty("org.slf4j.simpleLogger.log.ai.koog", "warn")
    systemProperty("org.slf4j.simpleLogger.log.io.ktor", "warn")
    systemProperty("org.slf4j.simpleLogger.log.dev.kstep.desktop.ai", "info")
    systemProperty("kstep.ai.sources", file("src/main/kotlin/dev/kstep/desktop/ai").absolutePath)
}

tasks.test {
    filter { excludeTestsMatching("*LiveKeychain*") }
}

// Not part of `check`: touches the real OS keychain (ADR-0056).
tasks.register<Test>("liveKeychainTest") {
    useJUnitPlatform()
    testClassesDirs =
        sourceSets.test
            .get()
            .output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter { includeTestsMatching("dev.kstep.desktop.ai.keys.LiveKeychainContractTest") }
}
