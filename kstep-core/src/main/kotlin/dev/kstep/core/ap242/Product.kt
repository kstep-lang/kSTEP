package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "product"
private val WHERE_RULES = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.id <> ''"))

/**
 * `dev.kstep.express` AP242-subset `product` entity — `id`, `name`, `description`, all `STRING`, none `OPTIONAL`.
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
    var name: String = ""
    var description: String = ""
}

/**
 * Builds a [Product], running WHERE-rule validation ([WHERE_RULES]) against the built
 * values. Never throws for a validation failure — returns [ValidationResult.Invalid] with
 * structured [dev.kstep.core.DslViolation]s instead.
 */
fun product(
    id: String,
    block: ProductBuilder.() -> Unit = {},
): ValidationResult<Product> {
    val builder = ProductBuilder().apply(block)
    val attributeValues =
        mapOf(
            "id" to WhereRuleValue.StringValue(id),
            "name" to WhereRuleValue.StringValue(builder.name),
            "description" to WhereRuleValue.StringValue(builder.description),
        )
    val violations = WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(Product(id, builder.name, builder.description))
    } else {
        ValidationResult.Invalid(violations)
    }
}
