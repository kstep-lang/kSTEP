package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult

private const val ENTITY_NAME = "product_definition_formation"

/**
 * `dev.kstep.express` AP242-subset `product_definition_formation` entity — `id`/`description`
 * `STRING`, `of_product`/`product`. The one V1 entity with **no** WHERE rule (see
 * `ap242-subset.exp`'s deliberate rule-free case) — building it therefore only ever checks
 * the structural `of_product` presence, never a `KSTEP-W-001` violation.
 *
 * **Honesty note (M2 Welle 8 — codegen reconciliation, refined M2 Welle 9):** the real AP242
 * `product_definition_formation` (`ap242-v1-entities.exp` lines 154–160) also declares
 * `UNIQUE UR1: id, of_product`. `kstep-core` itself enforces no `UNIQUE` rule for any entity —
 * UNIQUE is inherently cross-instance, and this layer's builders are pure, single-instance
 * constructors with no visibility into other instances — but that is not an open gap for
 * *this* rule specifically: at the `kstep-mcp`/`EntityStore` level, UR1 is satisfied by
 * construction, because the store already keys every `product_definition_formation` by that
 * same `id`, so the composite `(id, of_product)` key can never collide without `id` itself
 * colliding first. Proven by `KStepMcpServerTest`, not just asserted; see the README's
 * divergence table for the full rationale, contrasted there with
 * `next_assembly_usage_occurrence`'s real, store-enforced `UNIQUE UR1`, whose fields exclude
 * the store key and so do need a scan. `description` follows the pervasive "`OPTIONAL text` as
 * empty-default `String`" convention (see [Product]'s equivalent note) rather than a nullable
 * `String?`. Pinned by `dev.kstep.tests.Ap242CoreSchemaConsistencyTest`; see the README's
 * Roadmap "codegen reconciliation" entry for the full deferral rationale.
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
