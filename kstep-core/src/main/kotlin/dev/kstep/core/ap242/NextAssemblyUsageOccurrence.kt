package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "next_assembly_usage_occurrence"
private val WHERE_RULES =
    listOf(
        WhereRuleSpec(
            label = "wr1",
            expressionText = "(SELF.id <> '') AND (SELF.reference_designator <> '')",
        ),
    )

/**
 * `dev.kstep.express` AP242-subset `next_assembly_usage_occurrence` entity — `id`/`name`/
 * `reference_designator`/`STRING`, `relating_product_definition`/`related_product_definition`
 * both `product_definition`.
 *
 * **Honesty note (M2 Welle 8 — codegen reconciliation):** the real
 * `next_assembly_usage_occurrence`'s flattened SUBTYPE OF chain (`assembly_component_usage` →
 * `product_definition_usage` → `product_definition_relationship`, see `ap242-v1-entities.exp`
 * lines 165–200) also carries an inherited `description : OPTIONAL text`
 * (`product_definition_relationship`), which `kstep-core` **omits entirely**. The inherited
 * `reference_designator : OPTIONAL identifier` (`assembly_component_usage`) is present but its
 * optionality is **narrowed**: `kstep-core` models it as a non-null [String] defaulting to `""`
 * rather than a nullable `String?`. `wr1: (SELF.id <> '') AND (SELF.reference_designator <> '')`
 * is a **synthesized** ergonomic WHERE rule — the real WR1 (`acyclic_product_definition_relationship(...)`)
 * is outside the supported WHERE-expression subset. All three are pinned by
 * `dev.kstep.tests.Ap242CoreSchemaConsistencyTest`; see the README's Roadmap
 * "codegen reconciliation" entry for the full deferral rationale.
 *
 * The constructor is `internal` — see [Product]'s equivalent doc note for why: only the
 * [nextAssemblyUsageOccurrence] builder function may produce an instance, so it always passes
 * through WHERE-rule and missing-reference validation first. `@ConsistentCopyVisibility` keeps
 * the generated `copy()` `internal` too.
 */
@ConsistentCopyVisibility
data class NextAssemblyUsageOccurrence internal constructor(
    val id: String,
    val name: String,
    val relatingProductDefinition: ProductDefinition,
    val relatedProductDefinition: ProductDefinition,
    val referenceDesignator: String,
)

class NextAssemblyUsageOccurrenceBuilder internal constructor() {
    // Nullable purely as an internal "was it set" presence sentinel (see Product.name's
    // equivalent doc note): `name` is a non-OPTIONAL `label` inherited from
    // `product_definition_relationship` with no WHERE rule of its own, so a still-null value at
    // build() time is a structural violation (KSTEP-M-002), never a legitimate empty value.
    var name: String? = null
    var relatingProductDefinition: ProductDefinition? = null
    var relatedProductDefinition: ProductDefinition? = null
    var referenceDesignator: String = ""
}

fun nextAssemblyUsageOccurrence(
    id: String,
    block: NextAssemblyUsageOccurrenceBuilder.() -> Unit = {},
): ValidationResult<NextAssemblyUsageOccurrence> {
    val builder = NextAssemblyUsageOccurrenceBuilder().apply(block)

    // Collects all structural violations rather than stopping at the first — this entity is
    // the deliberate "multiple simultaneous violations in one call" proof (missing refs, a
    // missing name, and a failing WHERE rule can all show up together).
    val structuralViolations =
        buildList {
            if (builder.relatingProductDefinition == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "relating_product_definition"))
            }
            if (builder.relatedProductDefinition == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "related_product_definition"))
            }
            if (builder.name == null) {
                add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
            }
        }

    val attributeValues =
        mapOf(
            "id" to WhereRuleValue.StringValue(id),
            "name" to WhereRuleValue.StringValue(builder.name ?: ""),
            "reference_designator" to WhereRuleValue.StringValue(builder.referenceDesignator),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            NextAssemblyUsageOccurrence(
                id = id,
                name = builder.name!!,
                relatingProductDefinition = builder.relatingProductDefinition!!,
                relatedProductDefinition = builder.relatedProductDefinition!!,
                referenceDesignator = builder.referenceDesignator,
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
