package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.express.validation.WhereRuleSpec
import dev.kstep.express.validation.WhereRuleValidator
import dev.kstep.express.validation.WhereRuleValue

private const val ENTITY_NAME = "approval"
private val WHERE_RULES = listOf(WhereRuleSpec(label = "wr1", expressionText = "SELF.level <> ''"))

/**
 * `dev.kstep.express` AP242-subset `approval` entity — `status`/`STRING`, `level`/`STRING`,
 * `authorized_by`/`person_and_organization`.
 *
 * `level` is `STRING`, not `INTEGER`: the real AP242 MIM's `approval.level` attribute is typed
 * `label` (a `TYPE label = STRING;` alias, see `ap242-v1-entities.exp` and
 * `dev.kstep.express.codegen.Ap242V1CodeGen`), which this fixture and builder now match — a
 * correctness fix from M1 Welle 4, which had originally (Welle 3) assumed `INTEGER` without a
 * real schema to check against.
 *
 * The constructor is `internal` — see [Product]'s equivalent doc note for why: only the
 * [approval] builder function may produce an instance, so it always passes through
 * WHERE-rule and missing-reference validation first. `@ConsistentCopyVisibility` keeps the
 * generated `copy()` `internal` too.
 */
@ConsistentCopyVisibility
data class Approval internal constructor(
    val status: String,
    val level: String,
    val authorizedBy: PersonAndOrganization,
)

class ApprovalBuilder internal constructor() {
    var level: String = ""

    // Nullable purely as an internal "was it set" presence sentinel: authorized_by is not an
    // EXPRESS OPTIONAL attribute, so a still-null reference at build() time is a structural
    // violation (KSTEP-M-001), never a legitimate empty value. A dummy placeholder instance
    // would be worse — it would hide "never set" behind a value that looks valid.
    var authorizedBy: PersonAndOrganization? = null
}

fun approval(
    status: String,
    block: ApprovalBuilder.() -> Unit = {},
): ValidationResult<Approval> {
    val builder = ApprovalBuilder().apply(block)

    val structuralViolations =
        buildList {
            if (builder.authorizedBy == null) add(missingMandatoryReferenceViolation(ENTITY_NAME, "authorized_by"))
        }

    val attributeValues =
        mapOf(
            "status" to WhereRuleValue.StringValue(status),
            "level" to WhereRuleValue.StringValue(builder.level),
        )
    val whereRuleViolations =
        WhereRuleValidator.validate(ENTITY_NAME, WHERE_RULES, attributeValues).map { it.toDslViolation() }

    val violations = structuralViolations + whereRuleViolations
    return if (violations.isEmpty()) {
        ValidationResult.Valid(Approval(status, builder.level, builder.authorizedBy!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
