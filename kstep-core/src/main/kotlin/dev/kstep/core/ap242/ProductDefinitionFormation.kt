package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult

private const val ENTITY_NAME = "product_definition_formation"

/**
 * `dev.kstep.express` AP242-subset `product_definition_formation` entity — `id`/`description`
 * `STRING`, `of_product`/`product`. The one V1 entity with **no** WHERE rule (see
 * `ap242-subset.exp`'s deliberate rule-free case) — building it therefore only ever checks
 * the structural `of_product` presence, never a `KSTEP-W-001` violation.
 *
 * The constructor is `internal` — see [Product]'s equivalent doc note for why: only the
 * [productDefinitionFormation] builder function may produce an instance, so it always passes
 * through structural validation first. `@ConsistentCopyVisibility` keeps the generated
 * `copy()` `internal` too.
 */
@ConsistentCopyVisibility
data class ProductDefinitionFormation internal constructor(
    val id: String,
    val description: String,
    val ofProduct: Product,
)

class ProductDefinitionFormationBuilder internal constructor() {
    var description: String = ""
    var ofProduct: Product? = null
}

fun productDefinitionFormation(
    id: String,
    block: ProductDefinitionFormationBuilder.() -> Unit = {},
): ValidationResult<ProductDefinitionFormation> {
    val builder = ProductDefinitionFormationBuilder().apply(block)

    val violations =
        buildList {
            if (builder.ofProduct == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "of_product"))
        }

    return if (violations.isEmpty()) {
        ValidationResult.Valid(ProductDefinitionFormation(id, builder.description, builder.ofProduct!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
