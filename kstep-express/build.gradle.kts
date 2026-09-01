import org.gradle.api.attributes.Attribute

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
// artifact under build/generated/expressKotlin/main. Deliberately NOT added to *this module's
// own* sourceSet — as of kSTEP M2 Welle 10, the output is instead exposed below as the
// `ap242GeneratedSources` consumable configuration and consumed by kstep-core, which compiles
// it directly into its own source set (internal-constructor `CodeGenOptions`, see
// Ap242V1CodeGen.CORE_MODULE_OPTIONS) so that dev.kstep.core.ap242's builder functions become
// the only way to construct one of the twelve generated AP242 entities. Wiring the output into
// *this* module's compileKotlin would still create classes with the same simple names as
// kstep-core's old hand-authored equivalents once existed; see docs/adr/ADR-0004 for the full
// rationale and kstep-core/build.gradle.kts for the consumer side.
val ap242OutputDir = layout.buildDirectory.dir("generated/expressKotlin/main")

val generateExpressKotlin by
    tasks.registering(JavaExec::class) {
        group = "code generation"
        description = "Regenerates the six V1 AP242 entities from the real-schema extraction " +
            "(dev.kstep.express.codegen.Ap242V1CodeGen) as a build artifact."
        dependsOn(tasks.named("classes"))
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("dev.kstep.express.codegen.Ap242V1CodeGenKt")

        outputs.dir(ap242OutputDir)
        // Scoped deletion of exactly this task's own output directory (never a broader path,
        // and never derived from schema/entity-name input) before every run, so a stale file
        // from a previous schema revision can't silently linger alongside fresh output.
        doFirst {
            val dir = ap242OutputDir.get().asFile
            dir.deleteRecursively()
            dir.mkdirs()
        }
        args(ap242OutputDir.get().asFile.absolutePath)
    }

// Wiring the task's execution (not its output) into `check` guarantees generateExpressKotlin
// keeps running — and its EXPECTED_GENERATED/EXPECTED_SKIPPED regression guard (see
// Ap242V1CodeGen.kt) keeps being checked — on every `./gradlew clean check`, instead of only
// ever being run manually and going stale unnoticed.
tasks.named("check") {
    dependsOn(generateExpressKotlin)
}

// Exposes generateExpressKotlin's output directory as a Gradle "artifact transfer" consumable
// configuration, so kstep-core can pull it into its own compileKotlin source set (via a
// matching `resolvable` configuration, see kstep-core/build.gradle.kts) without kstep-core ever
// reaching across into kstep-express's `sourceSets`/`tasks` directly — that cross-project
// `project(":x").sourceSets`/`tasks` access pattern is Gradle-9/Isolated-Projects-hostile and
// breaks under the configuration cache; a consumable/resolvable configuration pair carries the
// task dependency automatically and is the supported cross-project-artifact idiom instead.
val ap242ArtifactTypeAttribute: Attribute<String> = Attribute.of("dev.kstep.artifact", String::class.java)
val ap242GeneratedSources =
    configurations
        .consumable("ap242GeneratedSources") {
            attributes { attribute(ap242ArtifactTypeAttribute, "ap242-generated-sources") }
        }.get()
artifacts {
    add(ap242GeneratedSources.name, ap242OutputDir) {
        builtBy(generateExpressKotlin)
    }
}
