package dev.kstep.core.ap242

import dev.kstep.core.ValidationResult
import dev.kstep.generated.ap242v1.ApprovalStatus

private const val ENTITY_NAME = "approval_status"

/**
 * Ergonomic wrapper over the codegen-generated [ApprovalStatus] (kSTEP M2 Welle 10 — see
 * [ApplicationContext]'s equivalent doc note). One mandatory `name` label, no WHERE rule.
 *
 * An [approval]'s `status : approval_status` is satisfied by one instance built through this
 * function — the real AP242 `approval` entity types `status` as this entity, not a bare
 * `STRING`, correcting `kstep-core`'s pre-Welle-10 deliberate simplification (see the old
 * `Approval.kt` KDoc, superseded by this wave).
 */
class ApprovalStatusBuilder internal constructor() {
    var name: String? = null
}

fun approvalStatus(block: ApprovalStatusBuilder.() -> Unit = {}): ValidationResult<ApprovalStatus> {
    val builder = ApprovalStatusBuilder().apply(block)
    val violations =
        buildList {
            if (builder.name == null) add(missingMandatoryAttributeViolation(ENTITY_NAME, "name"))
        }
    return if (violations.isEmpty()) {
        ValidationResult.Valid(ApprovalStatus(name = builder.name!!))
    } else {
        ValidationResult.Invalid(violations)
    }
}
