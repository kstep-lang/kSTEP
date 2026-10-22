package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.DescriptionAttribute
import dev.kstep.generated.ap242v1.DescriptionAttributeSelect

private const val ENTITY_NAME = "description_attribute"

/**
 * Ergonomic wrapper over the codegen-generated [DescriptionAttribute] (ADR-0023): attaches an
 * `attribute_value : text` to the item selected by `described_item`. Both attributes are mandatory. The
 * attached value is what the derived `get_description_value` function of the target reads back -- see the
 * generated `description` member function that takes a population scope.
 *
 * The real schema's `DescriptionAttributeSelect` has more members than the V1 excerpt models (see
 * `ap242-v1-entities.exp`); only the modelled ones can be selected here.
 */
class DescriptionAttributeBuilder internal constructor() {
    // Presence sentinel, like the other builders: a still-null value at build time is a structural violation.
    var attributeValue: String? = null
    var describedItem: DescriptionAttributeSelect? = null
}

fun descriptionAttribute(block: DescriptionAttributeBuilder.() -> Unit = {}): ValidationResult<DescriptionAttribute> {
    val builder = DescriptionAttributeBuilder().apply(block)
    val violations =
        buildList {
            if (builder.attributeValue == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "attribute_value"))
            if (builder.describedItem == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "described_item"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            DescriptionAttribute(attributeValue = builder.attributeValue!!, describedItem = builder.describedItem!!),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
