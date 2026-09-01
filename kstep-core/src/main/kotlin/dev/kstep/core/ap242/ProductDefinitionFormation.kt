package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductDefinitionFormation

private const val ENTITY_NAME = "product_definition_formation"

/**
 * Ergonomic wrapper over the codegen-generated [ProductDefinitionFormation] (kSTEP M2 Welle 10
 * — see [Product]'s equivalent doc note). The one V1 entity with **no** WHERE rule at all (real
 * or synthesized) — building it therefore only ever checks the structural `of_product`
 * presence, never a `KSTEP-W-001` violation.
 *
 * The real AP242 `product_definition_formation` also declares `UNIQUE UR1: id, of_product`.
 * `kstep-core` itself enforces no `UNIQUE` rule for any entity — UNIQUE is inherently
 * cross-instance and this layer's builders are pure, single-instance constructors with no
 * visibility into other instances — but at the `kstep-mcp`/`EntityStore` level, UR1 is
 * satisfied by construction, because the store already keys every
 * `product_definition_formation` by that same `id`, so the composite `(id, of_product)` key can
 * never collide without `id` itself colliding first (unchanged from before this wave — see
 * `KStepMcpServerTest`).
 */
class ProductDefinitionFormationBuilder internal constructor() {
    var description: String? = null
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
        ValidationResult.Valid(
            ProductDefinitionFormation(id = id, description = builder.description, ofProduct = builder.ofProduct!!),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
