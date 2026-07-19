package dev.kstep.core

/**
 * A single structured validation failure from building a kSTEP DSL entity, analogous to
 * kUML's `KUML-E-xxx` structured errors. [ruleLabel]/[expressionText] are only populated for
 * [DslViolationCodes.WHERE_RULE_NOT_SATISFIED] violations; a structural violation
 * ([DslViolationCodes.MISSING_MANDATORY_REFERENCE]) leaves them `null`.
 */
data class DslViolation(
    val code: String,
    val entityName: String,
    val ruleLabel: String?,
    val expressionText: String?,
    val message: String,
)

object DslViolationCodes {
    const val WHERE_RULE_NOT_SATISFIED = "KSTEP-W-001"
    const val MISSING_MANDATORY_REFERENCE = "KSTEP-M-001"
}
