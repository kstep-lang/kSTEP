package dev.kstep.tests

import dev.kstep.core.ap242.ApplicationContextBuilder
import dev.kstep.core.ap242.ApprovalBuilder
import dev.kstep.core.ap242.ApprovalStatusBuilder
import dev.kstep.core.ap242.NextAssemblyUsageOccurrenceBuilder
import dev.kstep.core.ap242.OrganizationBuilder
import dev.kstep.core.ap242.PersonAndOrganizationBuilder
import dev.kstep.core.ap242.PersonBuilder
import dev.kstep.core.ap242.ProductBuilder
import dev.kstep.core.ap242.ProductContextBuilder
import dev.kstep.core.ap242.ProductDefinitionBuilder
import dev.kstep.core.ap242.ProductDefinitionContextBuilder
import dev.kstep.core.ap242.ProductDefinitionFormationBuilder
import dev.kstep.core.ap242.applicationContext
import dev.kstep.core.ap242.approval
import dev.kstep.core.ap242.approvalStatus
import dev.kstep.core.ap242.nextAssemblyUsageOccurrence
import dev.kstep.core.ap242.organization
import dev.kstep.core.ap242.person
import dev.kstep.core.ap242.personAndOrganization
import dev.kstep.core.ap242.product
import dev.kstep.core.ap242.productContext
import dev.kstep.core.ap242.productDefinition
import dev.kstep.core.ap242.productDefinitionContext
import dev.kstep.core.ap242.productDefinitionFormation
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
import kotlin.reflect.KFunction
import kotlin.reflect.KMutableProperty1
import kotlin.reflect.KType
import kotlin.reflect.full.memberProperties

/**
 * M2 Welle 10 (`kstep-core` rebuilt as an ergonomic wrapper over the codegen-generated AP242
 * types) — this test's **purpose changed** from what it was M2 Welle 8–9: `kstep-core` no longer
 * hand-authors any of the twelve entities' *shapes* at all (`dev.kstep.generated.ap242v1.*`,
 * compiled from `ap242-v1-entities.exp` via `ExpressKotlinCodeGenerator`, is now the only place
 * the shape is declared) — a form-drift guard comparing two independently-hand-maintained shapes
 * is no longer meaningful, because there is only one shape left; the Kotlin compiler itself
 * already guards it (a schema change that adds/removes/retypes an attribute changes the
 * generated constructor, and every `dev.kstep.core.ap242` builder referencing the old shape
 * simply stops compiling).
 *
 * What the compiler does **not** catch is a different, still-real drift risk this test now
 * guards instead: a builder that quietly stops exposing one of the generated attributes as a
 * settable `var` (or drops the positional identity parameter), or one that invents an extra
 * property with no counterpart in the real schema at all — both leave the entity impossible (or
 * silently wrong) to build correctly through the DSL without breaking compilation anywhere.
 * Concretely, for every one of the twelve entities:
 *
 * > `{positional parameters of the builder function} ∪ {mutable ("var") properties of its
 * > Builder class}`, mapped through [NamingConventions.toPropertyName], must be **exactly** the
 * > same *name set* as `ResolvedEntity.flattenedAttributes`, and each shared name's coarse
 * > *category* (primitive string / entity reference / aggregation) must match.
 *
 * **Deliberately out of scope, and why:** real EXPRESS *optionality* is NOT cross-checked here.
 * Every `dev.kstep.core.ap242` builder property is nullable regardless of whether the real
 * attribute is `OPTIONAL` — this is the deliberate, pervasive "nullable-as-presence-sentinel"
 * convention (see e.g. `ProductBuilder.name`'s KDoc): a still-`null` *mandatory* attribute at
 * `build()` time is a structural [dev.kstep.core.DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE]/
 * `_REFERENCE` violation, while a still-`null` genuinely-*optional* one is legitimate and passed
 * straight through to the generated constructor — but both are represented by the exact same
 * nullable Kotlin type at the builder level. The distinction lives in each `build()` function's
 * *runtime logic* (which attributes it structurally checks), not in any reflectable type shape,
 * so it cannot be mechanically verified here without re-executing every builder against every
 * attribute — which [Ap242DslTest] already does, per entity, for the mandatory-attribute cases
 * that matter most (every entity has at least one dedicated "X never set fails with M-00x" test).
 * `UNIQUE`/`DERIVE`/`INVERSE` clause enforcement, and WHERE-rule *content* beyond what
 * [Ap242DslTest] already exercises, remain out of scope here too, unchanged from prior waves.
 *
 * [ACCEPTED_DIVERGENCES] — the M2 Welle 8/9 allowlist of hand-authored-vs-real shape gaps — is
 * now `emptyList()`: eleven of the thirteen entries it used to carry are resolved outright (the
 * codegen-generated shape *is* the real shape, by construction); the remaining two
 * (`SimplifiedEntityRefToString` for `approval.status`/`person_and_organization.the_person`+
 * `the_organization` — now correctly entity-typed — and the invented `approval.authorized_by`
 * — now removed entirely) are gone, not carried forward. The type is kept (rather than deleted)
 * so a future, *genuine* divergence — e.g. a deliberate ergonomic simplification introduced on
 * purpose — has a documented place to go, per the same "explicit allowlist, not silent drift"
 * philosophy this test has followed since M2 Welle 8.
 */
class Ap242CoreSchemaConsistencyTest :
    StringSpec({
        val schema = Ap242V1CodeGen.loadSchema()
        val resolvedEntities = InheritanceResolver.resolve(schema)
        val definedTypes = schema.definedTypes.associateBy { it.name.lowercase() }

        ENTITY_WRAPPERS.forEach { (entityName, wrapper) ->
            "kstep-core's $entityName wrapper exposes exactly the real attribute set, correctly categorized" {
                expectedShape(entityName, resolvedEntities, definedTypes) shouldBe actualShape(wrapper)
            }
        }

        "the allowlist is empty — every M2 Welle 8/9 divergence is resolved by building on the generated types" {
            ACCEPTED_DIVERGENCES shouldBe emptyList()
        }

        "an extra, undocumented property in a builder makes the affected comparison fail loudly" {
            // Meta-test proving the guard actually bites, without mutating any real kstep-core
            // class: a synthetic "actual" shape with one extra, undocumented field must NOT
            // compare equal to the real product's expected shape.
            val expected = expectedShape("product", resolvedEntities, definedTypes)
            val mutatedActual = expected + ("fabricatedField" to AttrCategory.PRIMITIVE_STRING)
            (expected == mutatedActual) shouldBe false
        }

        "realCategoryOf throws for a type category this drift test does not yet classify" {
            shouldThrow<IllegalStateException> {
                realCategoryOf(IntegerType, definedTypes)
            }
        }
    })

private enum class AttrCategory { PRIMITIVE_STRING, ENTITY_REF, AGGREGATION }

/** Kept as a documented, currently-empty extension point — see the class KDoc's final paragraph. */
private sealed interface AcceptedDivergence {
    val entity: String
    val attribute: String
    val reason: String
}

private val ACCEPTED_DIVERGENCES: List<AcceptedDivergence> = emptyList()

/** One entity's (Builder class, top-level builder function) pair, reflected below. */
private data class EntityWrapper(
    val builderClass: KClass<*>,
    val builderFunction: KFunction<*>,
)

private val ENTITY_WRAPPERS: Map<String, EntityWrapper> =
    mapOf(
        "application_context" to EntityWrapper(ApplicationContextBuilder::class, ::applicationContext),
        "product_context" to EntityWrapper(ProductContextBuilder::class, ::productContext),
        "product_definition_context" to
            EntityWrapper(ProductDefinitionContextBuilder::class, ::productDefinitionContext),
        "approval_status" to EntityWrapper(ApprovalStatusBuilder::class, ::approvalStatus),
        "person" to EntityWrapper(PersonBuilder::class, ::person),
        "organization" to EntityWrapper(OrganizationBuilder::class, ::organization),
        "product" to EntityWrapper(ProductBuilder::class, ::product),
        "product_definition_formation" to
            EntityWrapper(ProductDefinitionFormationBuilder::class, ::productDefinitionFormation),
        "product_definition" to EntityWrapper(ProductDefinitionBuilder::class, ::productDefinition),
        "next_assembly_usage_occurrence" to
            EntityWrapper(NextAssemblyUsageOccurrenceBuilder::class, ::nextAssemblyUsageOccurrence),
        "approval" to EntityWrapper(ApprovalBuilder::class, ::approval),
        "person_and_organization" to EntityWrapper(PersonAndOrganizationBuilder::class, ::personAndOrganization),
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
                    "on one of the twelve entities)",
            )
    }

/** Classifies a `kstep-core` reflected [KType] (builder parameter or property) into the same coarse category. */
private fun actualCategoryOf(kType: KType): AttrCategory {
    val classifier = kType.classifier
    return when {
        classifier == String::class -> AttrCategory.PRIMITIVE_STRING
        classifier == List::class || classifier == Set::class -> AttrCategory.AGGREGATION
        classifier is KClass<*> && classifier.qualifiedName?.startsWith("dev.kstep.generated.ap242v1.") == true ->
            AttrCategory.ENTITY_REF
        else ->
            error(
                "Ap242CoreSchemaConsistencyTest: kstep-core wrapper type '$kType' is not classified by " +
                    "actualCategoryOf -- extend it before trusting the comparison again",
            )
    }
}

/** The real, expected shape for [entityName]: Kotlin property name -> coarse category, no allowlist transform. */
private fun expectedShape(
    entityName: String,
    resolvedEntities: Map<String, ResolvedEntity>,
    definedTypes: Map<String, ExpressDefinedType>,
): Map<String, AttrCategory> {
    val resolved =
        requireNotNull(resolvedEntities[entityName]) {
            "Ap242CoreSchemaConsistencyTest: no resolved entity '$entityName' -- check ENTITY_WRAPPERS / " +
                "ap242-v1-entities.exp are still in sync"
        }
    return resolved.flattenedAttributes.associate { resolvedAttribute ->
        val attribute = resolvedAttribute.attribute
        val kotlinName = NamingConventions.toPropertyName(attribute.name)
        kotlinName to realCategoryOf(attribute.declaredType, definedTypes)
    }
}

/**
 * `kstep-core`'s actual exposed attribute set for [wrapper]: every positional (non-`block`)
 * parameter of the builder function, plus every mutable property of the Builder class — the
 * union is exactly what a script author can set before calling `.getOrThrow()`/pattern-matching
 * the [dev.kstep.core.ValidationResult].
 */
private fun actualShape(wrapper: EntityWrapper): Map<String, AttrCategory> {
    val identityParams =
        wrapper.builderFunction.parameters
            .filter { it.name != null && it.name != "block" }
            .associate { requireNotNull(it.name) to actualCategoryOf(it.type) }

    val mutableProperties =
        wrapper.builderClass.memberProperties
            .filterIsInstance<KMutableProperty1<Any, *>>()
            .associate { it.name to actualCategoryOf(it.returnType) }

    return identityParams + mutableProperties
}
