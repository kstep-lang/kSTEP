package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.ProductDefinitionContext

private const val ENTITY_NAME = "product_definition_context"

/**
 * Ergonomic wrapper over the codegen-generated [ProductDefinitionContext] (kSTEP M2 Welle 10 —
 * see [ApplicationContext]'s equivalent doc note). Mandatory `name`/`life_cycle_stage` labels,
 * plus a mandatory `frame_of_reference : application_context` reference. No WHERE rule on the
 * real entity, so none is modeled here.
 *
 * A [productDefinition]'s `frame_of_reference : product_definition_context` is satisfied by one
 * instance built through this function — see [ProductContext]'s equivalent doc note.
 */
class ProductDefinitionContextBuilder internal constructor() {
    var name: String? = null
    var frameOfReference: ApplicationContext? = null
    var lifeCycleStage: String? = null
}

fun productDefinitionContext(
    block: ProductDefinitionContextBuilder.() -> Unit = {},
): ValidationResult<ProductDefinitionContext> {
    val builder = ProductDefinitionContextBuilder().apply(block)
    val violations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
            if (builder.frameOfReference == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "frame_of_reference"))
            }
            if (builder.lifeCycleStage == null) {
                add(missingMandatoryAttributeViolation(ENTITY_NAME, "life_cycle_stage"))
            }
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            ProductDefinitionContext(
                name = builder.name!!,
                frameOfReference = builder.frameOfReference!!,
                lifeCycleStage = builder.lifeCycleStage!!,
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
