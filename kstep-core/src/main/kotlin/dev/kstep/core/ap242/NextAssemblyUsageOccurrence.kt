package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import dev.kstep.generated.ap242v1.ProductDefinition

private const val ENTITY_NAME = "next_assembly_usage_occurrence"

/**
 * Ergonomic wrapper over the codegen-generated [NextAssemblyUsageOccurrence] (kSTEP M2 Welle 10
 * — see [Product]'s equivalent doc note). `id`/`name` are mandatory; `description` (inherited
 * from `product_definition_relationship`) and `reference_designator` (inherited from
 * `assembly_component_usage`) are both genuinely `OPTIONAL` in the real schema.
 * `relating_product_definition`/`related_product_definition` are mandatory references.
 *
 * Pre-Welle-10, this builder enforced a synthesized WHERE rule requiring a non-blank
 * `reference_designator` — an over-constraint on what the real schema leaves `OPTIONAL`,
 * carried over from an earlier fixture. That rule is dropped entirely in this wave, not
 * relabeled: `reference_designator` may now be left unset, matching the real AP242 entity.
 * `kstep-mcp`'s UNIQUE UR1 enforcement (`(reference_designator, relating_product_definition)`
 * uniqueness) is updated accordingly to treat two unset `reference_designator`s as
 * non-conflicting — EXPRESS's `<>` is not satisfied by two unknown values, so UR1 does not
 * apply when either side is unset (see `NextAssemblyUsageOccurrenceTool`'s scan).
 */
class NextAssemblyUsageOccurrenceBuilder internal constructor() {
    // Nullable purely as an internal "was it set" presence sentinel (see Product.name's
    // long-standing equivalent doc note): `name` is a non-OPTIONAL `label` inherited from
    // `product_definition_relationship` with no WHERE rule of its own, so a still-null value at
    // build() time is a structural violation (KSTEP-M-002), never a legitimate empty value.
    var name: String? = null
    var description: String? = null
    var relatingProductDefinition: ProductDefinition? = null
    var relatedProductDefinition: ProductDefinition? = null
    var referenceDesignator: String? = null
}

fun nextAssemblyUsageOccurrence(
    id: String,
    block: NextAssemblyUsageOccurrenceBuilder.() -> Unit = {},
): ValidationResult<NextAssemblyUsageOccurrence> {
    val builder = NextAssemblyUsageOccurrenceBuilder().apply(block)

    // Collects all structural violations rather than stopping at the first — this entity is
    // the deliberate "multiple simultaneous violations in one call" proof (missing refs and a
    // missing name can show up together).
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

    return if (structuralViolations.isEmpty()) {
        ValidationResult.Valid(
            NextAssemblyUsageOccurrence(
                id = id,
                name = builder.name!!,
                description = builder.description,
                relatingProductDefinition = builder.relatingProductDefinition!!,
                relatedProductDefinition = builder.relatedProductDefinition!!,
                referenceDesignator = builder.referenceDesignator,
            ),
        )
    } else {
        ValidationResult.Invalid(structuralViolations)
    }
}
