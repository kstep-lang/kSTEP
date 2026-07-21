package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "product"
private val WHERE_RULES = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.id <> ''"))

/**
 * `dev.kstep.express` AP242-subset `product` entity — `id`, `name`, `description`, all `STRING`.
 * `id` and `name` are non-`OPTIONAL` (`identifier`/`label`); `description` is `OPTIONAL text`
 * (`ap242-v1-entities.exp` line 138) — an empty `description` is legal EXPRESS, not a gap.
 *
 * **Honesty note (M2 Welle 8 — codegen reconciliation):** the real AP242 `product`
 * (`ap242-v1-entities.exp` lines 135–140) also declares a mandatory
 * `frame_of_reference : SET [1:?] OF product_context`, which `kstep-core` **omits entirely** —
 * an aggregation-of-entity attribute this hand-authored layer does not yet model. `description`
 * is represented as a non-null [String] defaulting to `""` rather than a nullable `String?`, the
 * pervasive "`OPTIONAL text`/`label` as empty-default string" convention used throughout
 * `kstep-core`. `wr1: SELF.id <> ''` is a **synthesized** ergonomic WHERE rule — the real
 * `product` entity has no WHERE rule of its own. All three are pinned by
 * `dev.kstep.tests.Ap242CoreSchemaConsistencyTest`; see the README's Roadmap
 * "codegen reconciliation" entry for the full deferral rationale.
 *
 * The constructor is `internal` so the only way to obtain an instance from outside this module is the
 * [product] builder function, which always runs WHERE-rule validation first — a public constructor (and its
 * generated `copy()`) would let callers construct or mutate an instance that violates its own WHERE rule
 * without ever surfacing a [dev.kstep.core.DslViolation]. `@ConsistentCopyVisibility` keeps the generated
 * `copy()` `internal` too — without it `copy()` defaults to `public` regardless of the constructor's
 * visibility, reopening the exact bypass this constructor closes.
 */
@ConsistentCopyVisibility
data class Product internal constructor(
    val id: String,
    val name: String,
    val description: String,
)

class ProductBuilder internal constructor() {
    // Nullable purely as an internal "was it set" presence sentinel — same rationale as
    // Approval.authorizedBy, generalized from entity references to a mandatory primitive:
    // `name` is a non-OPTIONAL `label` with no WHERE rule of its own, so a still-null value at
    // build() time is a structural violation (KSTEP-M-002), never a legitimate empty value. An
    // explicitly-assigned empty string, by contrast, is a legal EXPRESS value here and must stay
    // Valid — see missingMandatoryAttributeViolation's KDoc.
    var name: String? = null
    var description: String = ""
}

/**
 * Builds a [Product], running WHERE-rule validation ([WHERE_RULES]) against the built
 * values plus a presence check for the mandatory [ProductBuilder.name] attribute. Never throws
 * for a validation failure — returns [ValidationResult.Invalid] with structured
 * [dev.kstep.core.DslViolation]s instead.
 */
fun product(
    id: String,
    block: ProductBuilder.() -> Unit = {},
): ValidationResult<Product> {
    val builder = ProductBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
        }

    val attributeValues =
        mapOf(
            "id" to WhereRuleValue.StringValue(id),
            "name" to WhereRuleValue.StringValue(builder.name ?: ""),
            "description" to WhereRuleValue.StringValue(builder.description),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(Product(id, builder.name!!, builder.description))
    } else {
        ValidationResult.Invalid(violations)
    }
}
