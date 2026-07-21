package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "product_definition"
private val WHERE_RULES = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.id <> ''"))

/**
 * `dev.kstep.express` AP242-subset `product_definition` entity — `id`/`description`/`STRING`,
 * `formation`/`product_definition_formation`.
 *
 * **Honesty note (M2 Welle 8 — codegen reconciliation):** the real AP242 `product_definition`
 * (`ap242-v1-entities.exp` lines 142–152) also declares a mandatory
 * `frame_of_reference : product_definition_context`, which `kstep-core` **omits entirely**, plus
 * a `DERIVE name : label` this class does not surface (derived attributes have no `kstep-core`
 * representation at all yet, an unrelated, broader gap). `description` follows the pervasive
 * "`OPTIONAL text` as empty-default `String`" convention (see [Product]'s equivalent note) rather
 * than a nullable `String?`. `wr1: SELF.id <> ''` is a **synthesized** ergonomic WHERE rule — the
 * real rule (`WR1: SIZEOF(USEDIN(...)) <= 1`) is outside the supported WHERE-expression subset.
 * All pinned by `dev.kstep.tests.Ap242CoreSchemaConsistencyTest`; see the README's Roadmap
 * "codegen reconciliation" entry for the full deferral rationale.
 *
 * The constructor is `internal` — see [Product]'s equivalent doc note for why: only the
 * [productDefinition] builder function may produce an instance, so it always passes through
 * WHERE-rule and missing-reference validation first. `@ConsistentCopyVisibility` keeps the
 * generated `copy()` `internal` too.
 */
@ConsistentCopyVisibility
data class ProductDefinition internal constructor(
    val id: String,
    val description: String,
    val formation: ProductDefinitionFormation,
)

class ProductDefinitionBuilder internal constructor() {
    var description: String = ""
    var formation: ProductDefinitionFormation? = null
}

fun productDefinition(
    id: String,
    block: ProductDefinitionBuilder.() -> Unit = {},
): ValidationResult<ProductDefinition> {
    val builder = ProductDefinitionBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.formation == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "formation"))
        }

    val attributeValues =
        mapOf(
            "id" to WhereRuleValue.StringValue(id),
            "description" to WhereRuleValue.StringValue(builder.description),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(ProductDefinition(id, builder.description, builder.formation!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
