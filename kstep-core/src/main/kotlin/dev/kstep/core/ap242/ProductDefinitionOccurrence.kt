package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.ProductDefinitionOccurrence
import dev.kstep.generated.ap242v1.ProductDefinitionOrReference

/**
 * Ergonomic wrapper over the codegen-generated [ProductDefinitionOccurrence] (ADR-0023). In the real
 * schema only `id` is mandatory; `name`, `description` and `definition` are `OPTIONAL`. `definition` is the
 * SELECT `product_definition_or_reference` of the product definition (or occurrence) that this occurrence
 * is an occurrence of.
 *
 * A `product_definition_occurrence` is **not** a supertype of `next_assembly_usage_occurrence`: it is a
 * member of the SELECT that types the two ends of a `product_definition_relationship`, so it can stand as
 * the `related_product_definition` of a usage, which is what gives the usage a
 * `product_definition_occurrence_id` (and makes `UNIQUE UR2` bite).
 *
 * The only real WHERE rule kept here (WR2: `EXISTS(definition) OR SIZEOF(USEDIN(... CONFIGURATION_DESIGN.DESIGN)) > 0`)
 * depends on the population and is checked by [Ap242ModelValidator], never by a single-instance builder.
 * The omitted parts of the entity (`quantity`, WR1, WR3, `descendant_occurrences`, `child_occurrences`)
 * are listed in `ap242-v1-entities.exp`'s header.
 */
class ProductDefinitionOccurrenceBuilder internal constructor() {
    var name: String? = null
    var description: String? = null
    var definition: ProductDefinitionOrReference? = null
}

fun productDefinitionOccurrence(
    id: String,
    block: ProductDefinitionOccurrenceBuilder.() -> Unit = {},
): ValidationResult<ProductDefinitionOccurrence> {
    val builder = ProductDefinitionOccurrenceBuilder().apply(block)
    return ValidationResult.Valid(
        ProductDefinitionOccurrence(
            id = id,
            name = builder.name,
            description = builder.description,
            definition = builder.definition,
        ),
    )
}
