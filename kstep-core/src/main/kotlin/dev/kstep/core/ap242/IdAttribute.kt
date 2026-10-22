package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.IdAttribute
import dev.kstep.generated.ap242v1.IdAttributeSelect

private const val ENTITY_NAME = "id_attribute"

/**
 * Ergonomic wrapper over the codegen-generated [IdAttribute] (ADR-0023): attaches an
 * `attribute_value : identifier` to the item selected by `identified_item`. Both attributes are mandatory. The
 * attached value is what the derived `get_id_value` function of the target reads back -- see the
 * generated `id` member function that takes a population scope.
 *
 * The real schema's `IdAttributeSelect` has more members than the V1 excerpt models (see
 * `ap242-v1-entities.exp`); only the modelled ones can be selected here.
 */
class IdAttributeBuilder internal constructor() {
    // Presence sentinel, like the other builders: a still-null value at build time is a structural violation.
    var attributeValue: String? = null
    var identifiedItem: IdAttributeSelect? = null
}

fun idAttribute(block: IdAttributeBuilder.() -> Unit = {}): ValidationResult<IdAttribute> {
    val builder = IdAttributeBuilder().apply(block)
    val violations =
        buildList {
            if (builder.attributeValue == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "attribute_value"))
            if (builder.identifiedItem == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "identified_item"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            IdAttribute(attributeValue = builder.attributeValue!!, identifiedItem = builder.identifiedItem!!),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
