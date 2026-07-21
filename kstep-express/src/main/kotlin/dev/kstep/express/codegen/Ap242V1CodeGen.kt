package dev.kstep.express.codegen

import com.squareup.kotlinpoet.FileSpec
import dev.kstep.express.semantic.ExpressSchema
import dev.kstep.express.semantic.ExpressSemanticModelBuilder
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.ResolvedEntity
import java.io.File

/**
 * Regenerates the six V1 AP242 entities from the real-schema extraction bundled at
 * [SCHEMA_RESOURCE_PATH] (see that file's header comment for provenance). Backs both the
 * `generateExpressKotlin` Gradle task (see kstep-express/build.gradle.kts, via [main] below)
 * and `kstep-tests`' `Ap242V1CodeGenTest`, which drives [generate] directly for per-entity
 * assertions the Gradle task's own success/failure counts can't express.
 */
object Ap242V1CodeGen {
    const val SCHEMA_RESOURCE_PATH = "/dev/kstep/express/codegen/ap242-v1-entities.exp"
    const val DEFAULT_PACKAGE_NAME = "dev.kstep.generated.ap242v1"

    /** The six V1 entities this wave's real-schema regeneration targets and reports on. */
    val TARGET_ENTITY_NAMES =
        listOf(
            "product",
            "product_definition",
            "product_definition_formation",
            "next_assembly_usage_occurrence",
            "approval",
            "person_and_organization",
        )

    // Not part of TARGET_ENTITY_NAMES (which tracks the six V1 entities this wave reports on)
    // but emitted alongside whichever of them succeed, because a target entity's *generated*
    // Kotlin references them as an attribute type:
    //   - approval_status (approval.status), person / organization
    //     (person_and_organization.the_person / .the_organization)
    //   - product_context (product.frame_of_reference : SET [1:?] OF product_context) and
    //     product_definition_context (product_definition.frame_of_reference), both SUBTYPE OF
    //     application_context_element and so, as of the SUBTYPE OF inheritance-flattening wave,
    //     code-generate cleanly (previously blocked -- see git history / README for the prior
    //     "dangling reference" limitation)
    //   - application_context (product_context.frame_of_reference /
    //     product_definition_context.frame_of_reference, both inherited from
    //     application_context_element)
    // Emitting all of them means every one of the six target entities' generated Kotlin is now
    // self-contained: no dangling class references anywhere in the emitted file. The three pure
    // SUBTYPE OF ancestors that exist in ap242-v1-entities.exp *only* to make
    // next_assembly_usage_occurrence's attribute flattening resolve (product_definition_relationship,
    // product_definition_usage, assembly_component_usage) are deliberately NOT listed here: nothing
    // in the emitted file references them as an attribute type, so emitting them would add classes
    // with no incoming reference -- they stay resolver-only ancestors, not emitted Kotlin.
    private val SUPPORT_ENTITY_NAMES =
        listOf(
            "approval_status",
            "person",
            "organization",
            "product_context",
            "product_definition_context",
            "application_context",
        )

    data class Outcome(
        val fileSpec: FileSpec,
        val generatedEntityNames: List<String>,
        val skipped: Map<String, String>,
    )

    fun loadSchema(): ExpressSchema {
        val source =
            requireNotNull(Ap242V1CodeGen::class.java.getResourceAsStream(SCHEMA_RESOURCE_PATH)) {
                "schema resource '$SCHEMA_RESOURCE_PATH' not found on classpath"
            }.bufferedReader().use { it.readText() }
        return ExpressSemanticModelBuilder.build(source).schemas.single()
    }

    // Tries each target entity independently via generateEntityType (not the all-or-nothing
    // generateFile) so a single throwing entity can't prevent the others from generating (a
    // real codegen limitation -- e.g. an unsupported attribute type -- could still make one of
    // the six throw in the future, even though none currently do). The successes are re-run
    // through generateFile at the end only to reuse its duplicate-class-name guard and produce
    // one coherent FileSpec; every entity name fed into it there already passed
    // generateEntityType once above, so that second pass never throws.
    //
    // Inheritance is resolved once, up front, against the *unfiltered* schema (resolvedEntities)
    // -- not recomputed later against the filtered schema passed to generateFile. This matters:
    // next_assembly_usage_occurrence's SUBTYPE OF chain reaches product_definition_relationship /
    // product_definition_usage / assembly_component_usage, none of which end up in
    // filteredSchema's entity list (see SUPPORT_ENTITY_NAMES above), so resolving against the
    // filtered schema would throw "does not resolve to a known entity". Resolving once against
    // the full schema and threading the same resolvedEntities map through both generateEntityType
    // calls and the final generateFile call avoids that.
    fun generate(packageName: String = DEFAULT_PACKAGE_NAME): Outcome {
        val schema = loadSchema()
        val definedTypesByLowerName = schema.definedTypes.associateBy { it.name.lowercase() }
        val resolvedEntities: Map<String, ResolvedEntity> = InheritanceResolver.resolve(schema)

        val skipped = mutableMapOf<String, String>()
        val generated = mutableListOf<String>()
        TARGET_ENTITY_NAMES.forEach { name ->
            val resolved =
                requireNotNull(resolvedEntities[name]) {
                    "target entity '$name' not found in $SCHEMA_RESOURCE_PATH"
                }
            try {
                ExpressKotlinCodeGenerator.generateEntityType(resolved, packageName, definedTypesByLowerName)
                generated += name
            } catch (e: CodeGenException) {
                skipped[name] = e.message ?: "codegen limitation with no message"
            }
        }

        val emittedNames = (generated + SUPPORT_ENTITY_NAMES).toSet()
        val filteredSchema = schema.copy(entities = schema.entities.filter { it.name in emittedNames })
        val fileSpec = ExpressKotlinCodeGenerator.generateFile(filteredSchema, packageName, resolvedEntities)
        return Outcome(fileSpec, generated, skipped)
    }

    fun generateAndWrite(
        outputDir: File,
        packageName: String = DEFAULT_PACKAGE_NAME,
    ): Outcome {
        val outcome = generate(packageName)
        outputDir.mkdirs()
        outcome.fileSpec.writeTo(outputDir)
        return outcome
    }
}

private val EXPECTED_GENERATED =
    setOf(
        "product",
        "product_definition",
        "product_definition_formation",
        "next_assembly_usage_occurrence",
        "approval",
        "person_and_organization",
    )
private val EXPECTED_SKIPPED = emptySet<String>()

// Deliberately plain println, not kotlin-logging (kSTEP M2 Welle 2 scope decision): this main()
// IS the generateExpressKotlin Gradle task's own console report, the same category of output as
// `./gradlew build`'s own build summary -- not diagnostic/operational logging. Converting it would
// bury task output behind log-level filtering instead of matching Gradle's own console output.

/**
 * Entry point for the `generateExpressKotlin` Gradle task (see kstep-express/build.gradle.kts).
 * A top-level `main`, not an object method, because `JavaExec` needs a JVM entry point on the
 * module's own runtime classpath, and this module has no application/shadow plugin to expose
 * one otherwise.
 *
 * Asserts the observed success/skip sets against [EXPECTED_GENERATED]/[EXPECTED_SKIPPED] and
 * fails loudly (non-zero exit via the uncaught [IllegalStateException]) if they ever drift —
 * e.g. a regression that breaks one of the six that currently succeed, or a future codegen
 * enhancement/regression that changes which entities generate. Since the SUBTYPE OF
 * inheritance-flattening wave, all six V1 entities generate and EXPECTED_SKIPPED is empty.
 * Silently accepting a changed set would let `generateExpressKotlin` (wired into `check`, see
 * build.gradle.kts) keep reporting green while this wave's documented real-schema codegen
 * boundary quietly moved.
 */
fun main(args: Array<String>) {
    require(args.size == 1) { "usage: Ap242V1CodeGenKt <outputDir>" }
    val outputDir = File(args[0])
    val outcome = Ap242V1CodeGen.generateAndWrite(outputDir)

    println(
        "generateExpressKotlin: generated ${outcome.generatedEntityNames.size} of " +
            "${Ap242V1CodeGen.TARGET_ENTITY_NAMES.size} target entities into ${outputDir.absolutePath}",
    )
    outcome.generatedEntityNames.forEach { println("  generated: $it") }
    outcome.skipped.forEach { (name, reason) -> println("  skipped:   $name -- $reason") }

    check(outcome.generatedEntityNames.toSet() == EXPECTED_GENERATED) {
        "generateExpressKotlin: expected to generate $EXPECTED_GENERATED but generated " +
            "${outcome.generatedEntityNames.toSet()} -- the real-schema codegen boundary changed, " +
            "update EXPECTED_GENERATED/EXPECTED_SKIPPED in Ap242V1CodeGen.kt deliberately if this is intended"
    }
    check(outcome.skipped.keys == EXPECTED_SKIPPED) {
        "generateExpressKotlin: expected to skip $EXPECTED_SKIPPED but skipped ${outcome.skipped.keys}"
    }
}
