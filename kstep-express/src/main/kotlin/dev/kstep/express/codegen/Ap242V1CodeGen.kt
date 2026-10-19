package dev.kstep.express.codegen

import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.TypeSpec
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
 *
 * Only ENUMERATION/SELECT TYPEs *reachable* from the emitted entities are written out
 * ([DefinedTypeScope.REACHABLE_FROM_EMITTED_ENTITIES], enforced here regardless of what the caller
 * passes); the full TYPE list still serves as the resolution map. The bundled extraction has none
 * that are reachable (see `EXPECTED_EMITTED_DEFINED_TYPES`). Every generated top-level type name is
 * also checked against [KSTEP_CORE_RESERVED_CLASS_NAMES], the hand-written types of `dev.kstep.core`
 * and `dev.kstep.core.ap242`, which import the generated package explicitly and would otherwise
 * be silently shadowed or become ambiguous.
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
    val SUPPORT_ENTITY_NAMES =
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
        /** Original EXPRESS names (declaration order) of the ENUMERATION/SELECT TYPEs written to the file. */
        val emittedDefinedTypeNames: List<String> = emptyList(),
        /**
         * DERIVE attributes outside the supported expression subset (ADR-0021), deduplicated by
         * declaring entity and attribute: they are named limitations, not generated properties.
         */
        val unsupportedDerivedAttributes: List<UnsupportedDerivedAttribute> = emptyList(),
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

    /**
     * The [CodeGenOptions] used by the `generateExpressKotlin` Gradle task (via [main]): an
     * `internal` constructor with `@ConsistentCopyVisibility`, since — as of kSTEP M2 Welle 10
     * — the generated file is compiled directly into `kstep-core`'s own source set and must be
     * unconstructable from outside it (see `dev.kstep.core.ap242`'s builder functions, the only
     * sanctioned construction path). [generate]/[generateAndWrite]'s own default stays plain
     * (`CodeGenOptions()`, public constructor) so callers outside that one wiring — in
     * particular `Ap242V1CodeGenTest`'s per-entity assertions predating this option — are
     * unaffected unless they opt in explicitly.
     */
    val CORE_MODULE_OPTIONS =
        CodeGenOptions(constructorVisibility = KModifier.INTERNAL, consistentCopyVisibility = true)

    /**
     * Top-level type names hand-written in kstep-core's `dev.kstep.core` and `dev.kstep.core.ap242`
     * packages (plus their `*Kt` file facades), which import `dev.kstep.generated.ap242v1.*`
     * explicitly; a generated type with one of these names would shadow/ambiguate silently there.
     * Kept in sync by Ap242V1CodeGenTest's classpath scan.
     */
    val KSTEP_CORE_RESERVED_CLASS_NAMES: Set<String> =
        setOf(
            "ValidationResult",
            "DslViolation",
            "DslViolationCodes",
            "ProductBuilder",
            "ProductDefinitionBuilder",
            "ProductDefinitionFormationBuilder",
            "NextAssemblyUsageOccurrenceBuilder",
            "ApprovalBuilder",
            "ApprovalStatusBuilder",
            "PersonBuilder",
            "OrganizationBuilder",
            "PersonAndOrganizationBuilder",
            "ProductContextBuilder",
            "ProductDefinitionContextBuilder",
            "ApplicationContextBuilder",
            // Population-level validation (ADR-0022).
            "Ap242ModelValidator",
            "Ap242InstanceDescriber",
            "Ap242V1SchemaContext",
            "InstanceFinding",
            "ModelValidationResult",
            // File facades of the hand-written Kotlin files.
            "ValidationResultKt",
            "DslViolationKt",
            "ApplicationContextKt",
            "ApprovalKt",
            "ApprovalStatusKt",
            "NextAssemblyUsageOccurrenceKt",
            "OrganizationKt",
            "PersonKt",
            "PersonAndOrganizationKt",
            "ProductKt",
            "ProductContextKt",
            "ProductDefinitionKt",
            "ProductDefinitionContextKt",
            "ProductDefinitionFormationKt",
            "WhereRuleViolationMappingKt",
        )

    fun generate(
        packageName: String = DEFAULT_PACKAGE_NAME,
        options: CodeGenOptions = CodeGenOptions(),
    ): Outcome = generate(loadSchema(), TARGET_ENTITY_NAMES, SUPPORT_ENTITY_NAMES, packageName, options)

    fun generate(
        schema: ExpressSchema,
        targetEntityNames: List<String>,
        supportEntityNames: List<String>,
        packageName: String,
        options: CodeGenOptions,
        reservedClassNames: Set<String> = KSTEP_CORE_RESERVED_CLASS_NAMES,
    ): Outcome {
        // Forced like definedTypeScope below: the real AP242 DERIVEs are all outside the supported
        // subset, so STRICT would make every target entity throw.
        val effectiveOptions =
            options.copy(derivedAttributeMode = DerivedAttributeMode.GENERATE_SUPPORTED_REPORT_UNSUPPORTED)
        val definedTypesByLowerName = schema.definedTypes.associateBy { it.name.lowercase() }
        val resolvedEntities: Map<String, ResolvedEntity> = InheritanceResolver.resolve(schema)

        val skipped = mutableMapOf<String, String>()
        val generated = mutableListOf<String>()
        targetEntityNames.forEach { name ->
            val resolved =
                requireNotNull(resolvedEntities[name]) {
                    "target entity '$name' not found in the supplied schema"
                }
            try {
                ExpressKotlinCodeGenerator.generateEntityType(
                    resolved,
                    packageName,
                    definedTypesByLowerName,
                    effectiveOptions,
                )
                generated += name
            } catch (e: CodeGenException) {
                skipped[name] = e.message ?: "codegen limitation with no message"
            }
        }

        val emittedNames = (generated + supportEntityNames).toSet()
        val filteredSchema = schema.copy(entities = schema.entities.filter { it.name in emittedNames })
        // Scope is forced: only reachable TYPEs are emitted, whatever the caller's options say.
        val scopedOptions = effectiveOptions.copy(definedTypeScope = DefinedTypeScope.REACHABLE_FROM_EMITTED_ENTITIES)
        val fileResult =
            ExpressKotlinCodeGenerator.generateFileWithReport(
                filteredSchema,
                packageName,
                resolvedEntities,
                scopedOptions,
            )
        val fileSpec = fileResult.fileSpec

        fileSpec.typeSpecs.forEach { typeSpec ->
            val name = checkNotNull(typeSpec.name)
            if (name in reservedClassNames) {
                throw CodeGenException(
                    "AP242 codegen: generated ${kindOf(typeSpec)} '$name' collides with a hand-written kstep-core " +
                        "type of the same name (dev.kstep.core / dev.kstep.core.ap242), which imports the " +
                        "generated package explicitly; rename/adjust before emitting",
                )
            }
        }
        val emittedDefinedTypeNames =
            schema.definedTypes
                .filter { dt ->
                    (dt.enumerationItems != null || dt.selectItems != null) &&
                        fileSpec.typeSpecs.any { it.name == NamingConventions.toClassName(dt.name) }
                }.map { it.name }
        val unsupportedDerived = fileResult.unsupportedDerived.distinctBy { it.entity to it.attribute }
        return Outcome(fileSpec, generated, skipped, emittedDefinedTypeNames, unsupportedDerived)
    }

    private fun kindOf(typeSpec: TypeSpec): String =
        when {
            typeSpec.kind == TypeSpec.Kind.INTERFACE -> "SELECT type (sealed interface)"
            typeSpec.isEnum -> "ENUMERATION type"
            else -> "entity or wrapper class"
        }

    fun generateAndWrite(
        outputDir: File,
        packageName: String = DEFAULT_PACKAGE_NAME,
        options: CodeGenOptions = CodeGenOptions(),
    ): Outcome {
        val outcome = generate(packageName, options)
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

// The bundled extraction has no ENUMERATION/SELECT TYPE reachable from the twelve emitted entities
// (only STRING aliases). If that changes, the generated kstep-core file changes -- fail loudly.
private val EXPECTED_EMITTED_DEFINED_TYPES = emptySet<String>()

// The six real AP242 V1 DERIVE clauses, all outside the supported expression subset (function calls
// over USEDIN / a two-hop entity chain, see ADR-0021). Named limitations, deliberately not
// properties: if the codegen learns to translate one, this set must shrink on purpose.
private val EXPECTED_UNSUPPORTED_DERIVED =
    setOf(
        "product_definition.name",
        "application_context.id",
        "application_context.description",
        "person_and_organization.name",
        "person_and_organization.description",
        "next_assembly_usage_occurrence.product_definition_occurrence_id",
    )

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
    val outcome = Ap242V1CodeGen.generateAndWrite(outputDir, options = Ap242V1CodeGen.CORE_MODULE_OPTIONS)

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
    outcome.unsupportedDerivedAttributes.forEach {
        println("  skipped-derive: ${it.entity}.${it.attribute} -- ${it.reason}")
    }
    check(
        outcome.unsupportedDerivedAttributes.map { "${it.entity}.${it.attribute}" }.toSet() ==
            EXPECTED_UNSUPPORTED_DERIVED,
    ) {
        "generateExpressKotlin: expected unsupported DERIVE attributes $EXPECTED_UNSUPPORTED_DERIVED but got " +
            "${outcome.unsupportedDerivedAttributes.map { "${it.entity}.${it.attribute}" }} -- the supported DERIVE " +
            "subset changed, update EXPECTED_UNSUPPORTED_DERIVED in Ap242V1CodeGen.kt deliberately if this is intended"
    }
    check(outcome.skipped.keys == EXPECTED_SKIPPED) {
        "generateExpressKotlin: expected to skip $EXPECTED_SKIPPED but skipped ${outcome.skipped.keys}"
    }
    check(outcome.emittedDefinedTypeNames.toSet() == EXPECTED_EMITTED_DEFINED_TYPES) {
        "generateExpressKotlin: expected to emit defined types $EXPECTED_EMITTED_DEFINED_TYPES but emitted " +
            "${outcome.emittedDefinedTypeNames} -- the generated kstep-core file changed, update " +
            "EXPECTED_EMITTED_DEFINED_TYPES in Ap242V1CodeGen.kt deliberately if this is intended"
    }
}
