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
 * **Honesty note (M2 Welle 8 — codegen reconciliation):** this shape is `kstep-core`'s
 * deliberately ergonomic approximation of the real AP242 `approval` entity
 * (`ap242-v1-entities.exp` lines 202–205: `status : approval_status; level : label;` — nothing
 * else), not that entity itself. Two divergences, both pinned by
 * `dev.kstep.tests.Ap242CoreSchemaConsistencyTest` so neither can drift further without a
 * deliberate allowlist edit:
 * - The real `status` is typed the entity `approval_status`, not `STRING` — modeled here as
 *   [String] as a deliberate simplification.
 * - [authorizedBy] (`authorized_by`) **does not exist on the real AP242 `approval` at all**. It
 *   is a kSTEP-invented convenience linkage to [PersonAndOrganization], carried over from the
 *   `ap242-subset.exp` parser/codegen test fixture — a legitimate fixture for exercising
 *   entity-typed attribute resolution, but not a real-schema attribute. Removing it here alone
 *   (while leaving `status` a bare `String`) would only mint a *third*, still-incoherent shape
 *   matching neither the fixture nor the real schema, so it is scheduled for removal together
 *   with entity-typed `status` modeling in a future, milestone-scoped wave — see the README's
 *   Roadmap "codegen reconciliation" entry for the full deferral rationale.
 *
 * `wr1: SELF.level <> ''` is also a **synthesized** ergonomic WHERE rule, not a real one: the
 * real AP242 `approval` entity has no WHERE rule at all.
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
