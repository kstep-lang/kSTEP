package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.NameAttribute
import dev.kstep.generated.ap242v1.NameAttributeSelect

private const val ENTITY_NAME = "name_attribute"

/**
 * Ergonomic wrapper over the codegen-generated [NameAttribute] (ADR-0023): attaches an
 * `attribute_value : label` to the item selected by `named_item`. Both attributes are mandatory. The
 * attached value is what the derived `get_name_value` function of the target reads back -- see the
 * generated `name` member function that takes a population scope.
 *
 * The real schema's `NameAttributeSelect` has more members than the V1 excerpt models (see
 * `ap242-v1-entities.exp`); only the modelled ones can be selected here.
 */
class NameAttributeBuilder internal constructor() {
    // Presence sentinel, like the other builders: a still-null value at build time is a structural violation.
    var attributeValue: String? = null
    var namedItem: NameAttributeSelect? = null
}

fun nameAttribute(block: NameAttributeBuilder.() -> Unit = {}): ValidationResult<NameAttribute> {
    val builder = NameAttributeBuilder().apply(block)
    val violations =
        buildList {
            if (builder.attributeValue == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "attribute_value"))
            if (builder.namedItem == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "named_item"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            NameAttribute(attributeValue = builder.attributeValue!!, namedItem = builder.namedItem!!),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
