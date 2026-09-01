package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue
import dev.kstep.generated.ap242v1.Approval
import dev.kstep.generated.ap242v1.ApprovalStatus

private const val ENTITY_NAME = "approval"

// `kstep_wr1`, NOT a real AP242 WR1 — see Product.kt's equivalent doc note. The real `approval`
// entity (`status : approval_status; level : label;`) declares no WHERE rule at all.
private val WHERE_RULES = listOf(WhereRuleSpec(label = "kstep_wr1", expressionText = "SELF.level <> ''"))

/**
 * Ergonomic wrapper over the codegen-generated [Approval] (kSTEP M2 Welle 10 — see [Product]'s
 * equivalent doc note). `status : approval_status` and `level : label` are both mandatory.
 *
 * Two divergences from the real schema that this wave **resolves**, closing gaps the pre-Welle-10
 * hand-authored `Approval` deliberately, honestly left open (see git history):
 * - `status` is now typed the real entity [ApprovalStatus], not a bare `String` simplification.
 * - The invented `authorized_by : person_and_organization` linkage — never part of the real
 *   AP242 `approval` entity, carried over from an old parser/codegen test fixture — is removed
 *   entirely, not replaced. It has no real-schema counterpart to reconcile it against.
 */
class ApprovalBuilder internal constructor() {
    var status: ApprovalStatus? = null
    var level: String? = null
}

fun approval(block: ApprovalBuilder.() -> Unit = {}): ValidationResult<Approval> {
    val builder = ApprovalBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.status == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "status"))
            if (builder.level == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "level"))
        }

    // Only evaluated once `level` is actually set — a still-null level is already fully
    // reported by MISSING_MANDATORY_ATTRIBUTE above, and re-checking `"" <> ''` against the
    // same missing value would only double-report the identical "you left level unset"
    // mistake under two different violation codes.
    val whereRuleViolations =
        builder.level?.let { level ->
            val attributeValues = mapOf("level" to WhereRuleValue.StringValue(level))
            WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }
        } ?: emptyList()

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(Approval(status = builder.status!!, level = builder.level!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
