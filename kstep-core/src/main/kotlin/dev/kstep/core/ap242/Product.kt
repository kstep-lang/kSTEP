package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.Product
import dev.kstep.generated.ap242v1.ProductContext

private const val ENTITY_NAME = "product"

// `kstep_wr1`, NOT a real AP242 WR1 — this entity's real schema declares no WHERE rule at all.
// A kSTEP-only ergonomic guard against a blank id, carried forward unchanged (just relabeled,
// see docs/adr/ADR-0004) from before this wave's codegen-generated-types rebuild, when it also
// wasn't real. Kept under the `kstep_` prefix specifically so it is never again mistaken for a
// genuine schema-derived rule the way an unprefixed `wr1` was before this wave.
private val WHERE_RULES = listOf(WhereRuleSpec(label = "kstep_wr1", expressionText = "SELF.id <> ''"))

/**
 * Ergonomic wrapper over the codegen-generated [Product] (kSTEP M2 Welle 10 — `kstep-core` no
 * longer hand-authors this entity's shape at all, only its validating builder; see
 * `docs/adr/ADR-0004-core-on-generated-types.adoc`). `id`/`name` are mandatory `identifier`/
 * `label`; `description` is genuinely `OPTIONAL text`. `frame_of_reference : SET [1:?] OF
 * product_context` is mandatory AND non-empty — a still-`null` [ProductBuilder.frameOfReference]
 * is [dev.kstep.core.DslViolationCodes.MISSING_MANDATORY_REFERENCE], an explicitly-assigned
 * empty set is the distinct [dev.kstep.core.DslViolationCodes.AGGREGATION_BOUND_VIOLATED] — see
 * that code's KDoc for why the two are not the same violation.
 *
 * The set is defensively copied into a [LinkedHashSet] when building the generated [Product]:
 * `LinkedHashSet` (not a plain `HashSet`) to keep iteration order deterministic — see
 * `Part21Writer`'s "byte-identical output for the same object graph" guarantee, which a
 * hash-order-dependent `Set` would silently break — and copied (not aliased) for the same
 * reason [Person]'s `List` properties are: a caller-retained mutable reference must not be able
 * to retroactively mutate an already-built, supposedly immutable [Product].
 */
class ProductBuilder internal constructor() {
    // Nullable purely as an internal "was it set" presence sentinel — see Product.name's
    // long-standing equivalent doc note (predates this wave).
    var name: String? = null
    var description: String? = null
    var frameOfReference: Set<ProductContext>? = null
}

fun product(
    id: String,
    block: ProductBuilder.() -> Unit = {},
): ValidationResult<Product> {
    val builder = ProductBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
            when {
                builder.frameOfReference == null ->
                    add(missingMandatoryReferenceViolation(ENTITY_NAME, "frame_of_reference"))
                builder.frameOfReference!!.isEmpty() ->
                    add(aggregationBoundViolation(ENTITY_NAME, "frame_of_reference", "[1:?]", 0))
            }
        }

    val attributeValues =
        mapOf(
            "id" to WhereRuleValue.StringValue(id),
            "name" to WhereRuleValue.StringValue(builder.name ?: ""),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(
            Product(
                id = id,
                name = builder.name!!,
                description = builder.description,
                frameOfReference = LinkedHashSet(builder.frameOfReference!!),
            ),
        )
    } else {
        ValidationResult.Invalid(violations)
    }
}
