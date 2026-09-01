package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.ApplicationContext
import dev.kstep.generated.ap242v1.ProductContext

private const val ENTITY_NAME = "product_context"

/**
 * Ergonomic wrapper over the codegen-generated [ProductContext] (kSTEP M2 Welle 10 — see
 * [ApplicationContext]'s equivalent doc note). Mandatory `name`/`discipline_type` labels, plus a
 * mandatory `frame_of_reference : application_context` reference. No WHERE rule on the real
 * entity, so none is modeled here.
 *
 * A [product]'s `frame_of_reference : SET [1:?] OF product_context` is satisfied by one or more
 * [ProductContext] instances built through this function — see `docs/adr/ADR-0004`'s "explicit
 * helper, never a silent default" rationale for why `kstep-core` requires the script to build
 * (or reuse) one explicitly rather than conjuring a placeholder behind the scenes.
 */
class ProductContextBuilder internal constructor() {
    var name: String? = null
    var frameOfReference: ApplicationContext? = null
    var disciplineType: String? = null
}

fun productContext(block: ProductContextBuilder.() -> Unit = {}): ValidationResult<ProductContext> {
    val builder = ProductContextBuilder().apply(block)
    val violations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
            if (builder.frameOfReference == null) {
                add(missingMandatoryReferenceViolation(ENTITY_NAME, "frame_of_reference"))
            }
            if (builder.disciplineType == null) {
                add(missingMandatoryAttributeViolation(ENTITY_NAME, "discipline_type"))
            }
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            ProductContext(
                name = builder.name!!,
                frameOfReference = builder.frameOfReference!!,
                disciplineType = builder.disciplineType!!,
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
