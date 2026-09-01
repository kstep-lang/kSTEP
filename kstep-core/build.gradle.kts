import org.gradle.api.attributes.Attribute

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // `api`, not `implementation`: as of kSTEP M2 Welle 10, dev.kstep.core.ap242's builder
    // functions and ValidationResult<T> return types expose dev.kstep.generated.ap242v1.* and
    // dev.kstep.express.validation.* types directly in this module's own public API surface
    // (e.g. `fun product(...): ValidationResult<Product>` where Product is now the *generated*
    // class this module compiles in, and WhereRuleSpec/WhereRuleValue appear in every builder
    // file) — an `implementation` dependency would make those types unresolvable to any
    // downstream consumer (kstep-step21, kstep-mcp, kstep-script) that only declares a
    // dependency on kstep-core itself, not transitively on kstep-express too.
    api(project(":kstep-express"))
}

// Consumes kstep-express's `ap242GeneratedSources` consumable configuration (see that module's
// build.gradle.kts) and adds its output directory to this module's own main source set, so the
// twelve generated dev.kstep.generated.ap242v1.* data classes (internal constructors, per
// Ap242V1CodeGen.CORE_MODULE_OPTIONS) compile as part of kstep-core itself — the only way any
// of them can be constructed from outside this module is then through the validating
// dev.kstep.core.ap242 builder functions in this module's own hand-authored sources. See
// docs/adr/ADR-0004 for the full rationale.
//
// Deliberately a consumable/resolvable configuration pair, not `project(":kstep-express").sourceSets...`
// or `project(":kstep-express").tasks.named("generateExpressKotlin")` reached into directly —
// that cross-project reach-through pattern is Gradle-9/Isolated-Projects-hostile and breaks
// under the configuration cache (see kUML's own documented experience with this class of
// problem). A resolvable configuration with a matching attribute automatically carries the
// upstream task dependency instead.
val ap242ArtifactTypeAttribute: Attribute<String> = Attribute.of("dev.kstep.artifact", String::class.java)
// A pure `resolvable` configuration (Gradle's role-based configuration model) cannot have
// dependencies declared against it directly (canBeDeclared = false by design) — only a
// `dependencyScope` configuration can. `ap242GeneratedSources` extends that scope, so it both
// resolves the dependency graph reachable from `ap242GeneratedSourcesDeps` AND carries the
// `dev.kstep.artifact` attribute match that selects kstep-express's `ap242GeneratedSources`
// consumable configuration specifically (not its default jar/classes variant).
val ap242GeneratedSourcesDeps = configurations.dependencyScope("ap242GeneratedSourcesDeps").get()
val ap242GeneratedSources =
    configurations
        .resolvable("ap242GeneratedSources") {
            extendsFrom(ap242GeneratedSourcesDeps)
            attributes { attribute(ap242ArtifactTypeAttribute, "ap242-generated-sources") }
        }.get()
dependencies {
    add(ap242GeneratedSourcesDeps.name, project(":kstep-express"))
}
kotlin.sourceSets.main {
    kotlin.srcDir(ap242GeneratedSources)
}

// KotlinPoet-formatted generated code does not follow ktlint's style rules (and should not be
// hand-edited to match them) — analogous to kstep-express's own exclude for ANTLR's
// build/generated-src/antlr. Excludes by path fragment so it also covers the copy Gradle
// resolves ap242GeneratedSources into, not just kstep-express's original build/generated/expressKotlin.
ktlint {
    filter {
        exclude { entry -> entry.file.path.contains("generated") }
    }
}
