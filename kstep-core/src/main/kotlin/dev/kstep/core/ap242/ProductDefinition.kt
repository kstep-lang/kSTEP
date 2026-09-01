package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.generated.ap242v1.ProductDefinitionContext
import dev.kstep.generated.ap242v1.ProductDefinitionFormation

private const val ENTITY_NAME = "product_definition"

// `kstep_wr1`, NOT a real AP242 WR1 — see Product.kt's equivalent doc note. The real WR1
// (`SIZEOF(USEDIN(...)) <= 1`) stays outside the supported WHERE-expression subset.
private val WHERE_RULES = listOf(WhereRuleSpec(label = "kstep_wr1", expressionText = "SELF.id <> ''"))

/**
 * Ergonomic wrapper over the codegen-generated [ProductDefinition] (kSTEP M2 Welle 10 — see
 * [Product]'s equivalent doc note). `id` is mandatory; `description` is genuinely `OPTIONAL
 * text`. `formation : product_definition_formation` and `frame_of_reference :
 * product_definition_context` are both mandatory single references (not aggregations, unlike
 * [Product.frameOfReference]) — a still-`null` value for either is
 * [dev.kstep.core.DslViolationCodes.MISSING_MANDATORY_REFERENCE].
 */
class ProductDefinitionBuilder internal constructor() {
    var description: String? = null
    var formation: ProductDefinitionFormation? = null
    var frameOfReference: ProductDefinitionContext? = null
}

fun productDefinition(
    id: String,
    block: ProductDefinitionBuilder.() -> Unit = {},
): ValidationResult<ProductDefinition> {
    val builder = ProductDefinitionBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.formation == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "formation"))
            if (builder.frameOfReference == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "frame_of_reference"))
            }
        }

    val attributeValues = mapOf("id" to WhereRuleValue.StringValue(id))
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            ProductDefinition(
                id = id,
                description = builder.description,
                formation = builder.formation!!,
                frameOfReference = builder.frameOfReference!!,
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
