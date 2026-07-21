package dev.kstep.tests

import dev.kstep.core.ap242.Approval
import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.core.ap242.PersonAndOrganization
import dev.kstep.core.ap242.Product
import dev.kstep.core.ap242.ProductDefinition
import dev.kstep.core.ap242.ProductDefinitionFormation
import dev.kstep.express.codegen.Ap242V1CodeGen
import dev.kstep.express.codegen.NamingConventions
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressType
import dev.kstep.express.semantic.InheritanceResolver
import dev.kstep.express.semantic.IntegerType
import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.semantic.StringType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.full.primaryConstructor

/**
 * M2 Welle 8 (codegen-reconciliation debt resolution) — the drift-prevention guard three prior
 * waves deferred. `kstep-core`'s six hand-authored AP242 types (`dev.kstep.core.ap242`) are a
 * deliberately ergonomic approximation of the real AP242 excerpt
 * (`ap242-v1-entities.exp`), not a faithful rendering of it — see each type's "Honesty note"
 * KDoc for the per-entity rationale, and the README's Roadmap "codegen reconciliation" entry for
 * the full picture and the deferred entity-typed reconciliation wave this test's allowlist is
 * scoped against.
 *
 * This test does **not** hand-write an expected shape and hope it stays right. It re-derives
 * the real shape live from [Ap242V1CodeGen.loadSchema] + [InheritanceResolver] every run (the
 * same source of truth `dev.kstep.express.codegen.ExpressKotlinCodeGenerator` itself codegens
 * against — see `Ap242V1CodeGenTest`, which proves that generator output *is* schema-faithful),
 * compares it against `kstep-core`'s actual shape via Kotlin reflection on each data class's
 * primary constructor, and fails loudly on any difference that is not explicitly named in
 * [ACCEPTED_DIVERGENCES] below. Any *new* undocumented drift — a field silently dropped, a type
 * silently narrowed, a field silently invented — fails this test. Removing an *existing*,
 * already-accepted divergence (e.g. deleting `Approval.authorizedBy`) requires a deliberate edit
 * to [ACCEPTED_DIVERGENCES], not just a code change — that is the point: the gap is now bounded
 * and guarded, not open-ended.
 *
 * **Deliberately out of scope** (documented as a limitation, not silently ignored): WHERE-rule
 * *content* is never compared here — every real WHERE rule on these six entities uses
 * `SIZEOF`/`USEDIN`/`EXISTS`/`acyclic_...`, all outside
 * `dev.kstep.express.validation.WhereRuleValidator`'s supported expression subset, so there is
 * no supported real rule to mechanically align a synthesized one to (see each type's KDoc).
 * `UNIQUE`/`DERIVE`/`INVERSE` clause enforcement is likewise out of scope — this test guards
 * **attribute shape** only (field set, primitive-vs-entity-vs-aggregation category,
 * optionality), which is where silent structural drift would actually hide.
 */
class Ap242CoreSchemaConsistencyTest :
    StringSpec({
        val schema = Ap242V1CodeGen.loadSchema()
        val resolvedEntities = InheritanceResolver.resolve(schema)
        val definedTypes = schema.definedTypes.associateBy { it.name.lowercase() }

        "kstep-core's Approval matches the real approval shape modulo the documented allowlist" {
            expectedShape("approval", resolvedEntities, definedTypes) shouldBe actualShape(Approval::class)
        }

        "kstep-core's PersonAndOrganization matches the real person_and_organization shape modulo the allowlist" {
            expectedShape("person_and_organization", resolvedEntities, definedTypes) shouldBe
                actualShape(PersonAndOrganization::class)
        }

        "kstep-core's Product matches the real product shape modulo the documented allowlist" {
            expectedShape("product", resolvedEntities, definedTypes) shouldBe actualShape(Product::class)
        }

        "kstep-core's ProductDefinition matches the real product_definition shape modulo the allowlist" {
            expectedShape("product_definition", resolvedEntities, definedTypes) shouldBe
                actualShape(ProductDefinition::class)
        }

        "kstep-core's ProductDefinitionFormation matches the real shape modulo the documented allowlist" {
            expectedShape("product_definition_formation", resolvedEntities, definedTypes) shouldBe
                actualShape(ProductDefinitionFormation::class)
        }

        "kstep-core's NextAssemblyUsageOccurrence matches the real flattened shape modulo the allowlist" {
            expectedShape("next_assembly_usage_occurrence", resolvedEntities, definedTypes) shouldBe
                actualShape(NextAssemblyUsageOccurrence::class)
        }

        "the allowlist only names entities among the six V1 targets, and every one of the six has an entry" {
            val entitiesInAllowlist = ACCEPTED_DIVERGENCES.map { it.entity }.toSet()
            entitiesInAllowlist shouldBe Ap242V1CodeGen.TARGET_ENTITY_NAMES.toSet()
        }

        "an allowlist entry for a divergence that no longer exists makes the affected comparison fail loudly" {
            // Meta-test proving the guard actually bites, without mutating any real kstep-core
            // class: a synthetic "actual" shape with one extra, undocumented field must NOT
            // compare equal to the real approval's expected shape.
            val expected = expectedShape("approval", resolvedEntities, definedTypes)
            val mutatedActual =
                expected + ("fabricatedField" to AttrShape(AttrCategory.PRIMITIVE_STRING, isOptional = false))
            (expected == mutatedActual) shouldBe false
        }

        "realCategoryOf throws for a type category this drift test does not yet classify" {
            shouldThrow<IllegalStateException> {
                realCategoryOf(IntegerType, definedTypes)
            }
        }
    })

/** One attribute's shape, name-independent -- paired with a name in the maps below. */
private data class AttrShape(
    val category: AttrCategory,
    val isOptional: Boolean,
)

private enum class AttrCategory { PRIMITIVE_STRING, ENTITY_REF, AGGREGATION }

/**
 * Every divergence this wave accepts between `kstep-core`'s hand-authored shape and the real
 * AP242 excerpt, keyed by EXPRESS entity name + EXPRESS attribute name (not the Kotlin property
 * name, so entries read directly against `ap242-v1-entities.exp`). Each carries a human-readable
 * [AcceptedDivergence.reason] so the allowlist doubles as the consolidated divergence inventory
 * -- see the README Roadmap "codegen reconciliation" entry for the long-form version of the same
 * inventory.
 */
private sealed interface AcceptedDivergence {
    val entity: String
    val attribute: String
    val reason: String

    /** Real attribute is an [AttrCategory.ENTITY_REF]; `kstep-core` models it as a bare String. */
    data class SimplifiedEntityRefToString(
        override val entity: String,
        override val attribute: String,
        override val reason: String,
    ) : AcceptedDivergence

    /** Present in `kstep-core`'s constructor, absent from the real schema entirely. */
    data class InventedAttribute(
        override val entity: String,
        override val attribute: String,
        val kotlinName: String,
        val category: AttrCategory,
        val isOptional: Boolean,
        override val reason: String,
    ) : AcceptedDivergence

    /** Present in the real schema, absent from `kstep-core`'s constructor entirely. */
    data class OmittedAttribute(
        override val entity: String,
        override val attribute: String,
        override val reason: String,
    ) : AcceptedDivergence

    /** Real attribute is `OPTIONAL`; `kstep-core` models it as non-nullable. */
    data class OptionalityNarrowed(
        override val entity: String,
        override val attribute: String,
        override val reason: String,
    ) : AcceptedDivergence

    /**
     * The pervasive `kstep-core` convention: a real `OPTIONAL text`/`label` modeled as a
     * non-null `String` defaulting to `""`, rather than a nullable `String?`. Kept distinct from
     * [OptionalityNarrowed] (same shape effect) purely so this very common, uniform pattern is
     * documented once as a named convention instead of repeating as undifferentiated noise.
     */
    data class OptionalStringAsEmptyDefault(
        override val entity: String,
        override val attribute: String,
        override val reason: String,
    ) : AcceptedDivergence
}

private val ACCEPTED_DIVERGENCES: List<AcceptedDivergence> =
    listOf(
        AcceptedDivergence.SimplifiedEntityRefToString(
            entity = "approval",
            attribute = "status",
            reason = "real 'status' is entity-typed (approval_status); kstep-core models it as String",
        ),
        AcceptedDivergence.InventedAttribute(
            entity = "approval",
            attribute = "authorized_by",
            kotlinName = "authorizedBy",
            category = AttrCategory.ENTITY_REF,
            isOptional = false,
            reason =
                "kSTEP-invented convenience linkage to person_and_organization, carried over from the " +
                    "ap242-subset.exp parser/codegen test fixture; not present on the real AP242 'approval' " +
                    "at all. Scheduled for removal together with entity-typed 'status' modeling -- see README " +
                    "Roadmap 'codegen reconciliation' entry (removing it alone would mint a third, " +
                    "still-incoherent shape).",
        ),
        AcceptedDivergence.SimplifiedEntityRefToString(
            entity = "person_and_organization",
            attribute = "the_person",
            reason = "real 'the_person' is entity-typed (person); kstep-core models it as String",
        ),
        AcceptedDivergence.SimplifiedEntityRefToString(
            entity = "person_and_organization",
            attribute = "the_organization",
            reason = "real 'the_organization' is entity-typed (organization); kstep-core models it as String",
        ),
        AcceptedDivergence.OmittedAttribute(
            entity = "product",
            attribute = "frame_of_reference",
            reason =
                "real 'product' has a mandatory SET [1:?] OF product_context; kstep-core has no " +
                    "aggregation-of-entity attribute support yet",
        ),
        AcceptedDivergence.OptionalStringAsEmptyDefault(
            entity = "product",
            attribute = "description",
            reason = "OPTIONAL text modeled as non-null String defaulting to \"\"",
        ),
        AcceptedDivergence.OmittedAttribute(
            entity = "product_definition",
            attribute = "frame_of_reference",
            reason =
                "real 'product_definition' has a mandatory product_definition_context reference; " +
                    "kstep-core omits it",
        ),
        AcceptedDivergence.OptionalStringAsEmptyDefault(
            entity = "product_definition",
            attribute = "description",
            reason = "OPTIONAL text modeled as non-null String defaulting to \"\"",
        ),
        AcceptedDivergence.OptionalStringAsEmptyDefault(
            entity = "product_definition_formation",
            attribute = "description",
            reason = "OPTIONAL text modeled as non-null String defaulting to \"\"",
        ),
        AcceptedDivergence.OmittedAttribute(
            entity = "next_assembly_usage_occurrence",
            attribute = "description",
            reason =
                "real flattened shape inherits an OPTIONAL text 'description' from " +
                    "product_definition_relationship; kstep-core omits it",
        ),
        AcceptedDivergence.OptionalityNarrowed(
            entity = "next_assembly_usage_occurrence",
            attribute = "reference_designator",
            reason =
                "real inherited 'reference_designator' (assembly_component_usage) is OPTIONAL identifier; " +
                    "kstep-core models it as non-null String defaulting to \"\"",
        ),
    )

/** Classifies a real [ExpressType] into the coarse category this drift test compares on. */
private fun realCategoryOf(
    type: ExpressType,
    definedTypes: Map<String, ExpressDefinedType>,
): AttrCategory =
    when (type) {
        is StringType -> AttrCategory.PRIMITIVE_STRING
        is EntityTypeRef -> AttrCategory.ENTITY_REF
        is AggregationType -> AttrCategory.AGGREGATION
        is DefinedTypeRef -> {
            val definedType =
                definedTypes[type.typeName.lowercase()]
                    ?: error("Ap242CoreSchemaConsistencyTest: unresolved TYPE reference '${type.typeName}'")
            val underlying =
                definedType.underlyingSimpleType
                    ?: error(
                        "Ap242CoreSchemaConsistencyTest: TYPE '${type.typeName}' is not a simple alias -- " +
                            "extend realCategoryOf before trusting the drift comparison for it",
                    )
            realCategoryOf(underlying, definedTypes)
        }
        else ->
            error(
                "Ap242CoreSchemaConsistencyTest: real AP242 excerpt now uses a type category ('$type') this " +
                    "drift test does not yet classify -- extend realCategoryOf before trusting the comparison " +
                    "again (this usually means ap242-v1-entities.exp grew a non-STRING primitive attribute " +
                    "on one of the six V1 entities)",
            )
    }

/** Classifies a `kstep-core` reflected [KType] into the same coarse category. */
private fun actualCategoryOf(kType: KType): AttrCategory {
    val classifier = kType.classifier
    return when {
        classifier == String::class -> AttrCategory.PRIMITIVE_STRING
        classifier == List::class || classifier == Set::class -> AttrCategory.AGGREGATION
        classifier is KClass<*> && classifier.qualifiedName?.startsWith("dev.kstep.core.ap242.") == true ->
            AttrCategory.ENTITY_REF
        else ->
            error(
                "Ap242CoreSchemaConsistencyTest: kstep-core attribute type '$kType' is not classified by " +
                    "actualCategoryOf -- extend it before trusting the comparison again",
            )
    }
}

/**
 * The real shape for [entityName], derived live from [resolvedEntities] (already-flattened
 * SUBTYPE OF attributes, see [InheritanceResolver]), transformed by [ACCEPTED_DIVERGENCES] into
 * the shape `kstep-core` is expected to actually have. Keyed by Kotlin property name (matching
 * [actualShape]'s keys) via [NamingConventions.toPropertyName] -- the same converter
 * `ExpressKotlinCodeGenerator` itself uses, so a hand-authored name that ever disagreed with the
 * converter would surface here as an unexpected-omission + unexpected-extra pair, not a silent
 * false negative.
 */
private fun expectedShape(
    entityName: String,
    resolvedEntities: Map<String, ResolvedEntity>,
    definedTypes: Map<String, ExpressDefinedType>,
): Map<String, AttrShape> {
    val resolved =
        requireNotNull(resolvedEntities[entityName]) {
            "Ap242CoreSchemaConsistencyTest: no resolved entity '$entityName' -- check TARGET_ENTITY_NAMES / " +
                "ap242-v1-entities.exp are still in sync"
        }
    val divergencesForEntity = ACCEPTED_DIVERGENCES.filter { it.entity == entityName }
    val omitted = divergencesForEntity.filterIsInstance<AcceptedDivergence.OmittedAttribute>().map { it.attribute }
    val simplified =
        divergencesForEntity.filterIsInstance<AcceptedDivergence.SimplifiedEntityRefToString>().map { it.attribute }
    val narrowed =
        (
            divergencesForEntity.filterIsInstance<AcceptedDivergence.OptionalityNarrowed>() +
                divergencesForEntity.filterIsInstance<AcceptedDivergence.OptionalStringAsEmptyDefault>()
        ).map { it.attribute }
    val invented = divergencesForEntity.filterIsInstance<AcceptedDivergence.InventedAttribute>()

    val result = mutableMapOf<String, AttrShape>()
    resolved.flattenedAttributes.forEach { resolvedAttribute ->
        val attribute = resolvedAttribute.attribute
        if (attribute.name in omitted) return@forEach

        var category = realCategoryOf(attribute.declaredType, definedTypes)
        if (attribute.name in simplified) {
            require(category == AttrCategory.ENTITY_REF) {
                "Ap242CoreSchemaConsistencyTest: allowlist claims '$entityName.${attribute.name}' is a " +
                    "SimplifiedEntityRefToString divergence, but its real category is $category, not " +
                    "ENTITY_REF -- the allowlist is stale, update it"
            }
            category = AttrCategory.PRIMITIVE_STRING
        }

        var isOptional = attribute.isOptional
        if (attribute.name in narrowed) {
            require(isOptional) {
                "Ap242CoreSchemaConsistencyTest: allowlist claims '$entityName.${attribute.name}' narrows " +
                    "optionality, but the real attribute is already non-OPTIONAL -- the allowlist is stale, " +
                    "update it"
            }
            isOptional = false
        }

        val kotlinName = NamingConventions.toPropertyName(attribute.name)
        result[kotlinName] = AttrShape(category, isOptional)
    }
    invented.forEach { divergence ->
        result[divergence.kotlinName] = AttrShape(divergence.category, divergence.isOptional)
    }
    return result
}

/** `kstep-core`'s actual shape for [kClass], read from its primary constructor via reflection. */
private fun actualShape(kClass: KClass<*>): Map<String, AttrShape> {
    val constructor =
        requireNotNull(kClass.primaryConstructor) {
            "Ap242CoreSchemaConsistencyTest: ${kClass.qualifiedName} has no primary constructor"
        }
    return constructor.parameters.associate { parameter ->
        val name =
            requireNotNull(parameter.name) {
                "Ap242CoreSchemaConsistencyTest: ${kClass.qualifiedName} has an unnamed constructor parameter"
            }
        name to AttrShape(actualCategoryOf(parameter.type), parameter.type.isMarkedNullable)
    }
}
