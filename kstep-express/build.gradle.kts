plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    antlr
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    antlr(libs.antlr4)
    implementation(libs.antlr4.runtime)
    implementation(libs.kotlinpoet)
}

tasks.generateGrammarSource {
    arguments = arguments +
        listOf(
            "-visitor",
            "-package",
            "dev.kstep.express.grammar",
            "-long-messages",
        )
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(tasks.generateGrammarSource)
}

// The ANTLR plugin adds build/generated-src/antlr/main (generated Java, not
// Kotlin) to the main source set. ktlint only lints .kt/.kts files, so these
// generated sources are irrelevant to it, but Gradle still flags an implicit,
// undeclared dependency because the ktlint tasks read that directory as part
// of the main source set. Make the dependency explicit.
ktlint {
    filter {
        exclude { entry -> entry.file.path.contains("generated-src") }
    }
}

tasks
    .matching { it.name.startsWith("runKtlintCheckOver") || it.name.startsWith("runKtlintFormatOver") }
    .configureEach {
        dependsOn(tasks.generateGrammarSource)
    }
