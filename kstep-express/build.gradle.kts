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

// Runs ExpressKotlinCodeGenerator (via dev.kstep.express.codegen.Ap242V1CodeGen) against the
// real-schema six-V1-entity extraction and writes the generated Kotlin source as a plain build
// artifact under build/generated/expressKotlin/main. Deliberately NOT added to any sourceSet —
// two of the six entities' generated classes (Product, ProductDefinition) reference support
// types (ProductContext, ProductDefinitionContext) that this task does not itself generate (see
// Ap242V1CodeGen.kt), so the output does not compile standalone; wiring it into this module's
// own compileKotlin would break `check`. Reconciling generated output with kstep-core's
// hand-authored types remains separate future work (see README's Roadmap).
val generateExpressKotlin by
    tasks.registering(JavaExec::class) {
        group = "code generation"
        description = "Regenerates the six V1 AP242 entities from the real-schema extraction " +
            "(dev.kstep.express.codegen.Ap242V1CodeGen) as a build artifact."
        dependsOn(tasks.named("classes"))
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("dev.kstep.express.codegen.Ap242V1CodeGenKt")

        val outputDir = layout.buildDirectory.dir("generated/expressKotlin/main")
        outputs.dir(outputDir)
        // Scoped deletion of exactly this task's own output directory (never a broader path,
        // and never derived from schema/entity-name input) before every run, so a stale file
        // from a previous schema revision can't silently linger alongside fresh output.
        doFirst {
            val dir = outputDir.get().asFile
            dir.deleteRecursively()
            dir.mkdirs()
        }
        args(outputDir.get().asFile.absolutePath)
    }

// Wiring the task's execution (not its output) into `check` guarantees generateExpressKotlin
// keeps running — and its EXPECTED_GENERATED/EXPECTED_SKIPPED regression guard (see
// Ap242V1CodeGen.kt) keeps being checked — on every `./gradlew clean check`, instead of only
// ever being run manually and going stale unnoticed.
tasks.named("check") {
    dependsOn(generateExpressKotlin)
}
