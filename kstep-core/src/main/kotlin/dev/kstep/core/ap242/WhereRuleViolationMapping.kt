package dev.kstep.core.ap242

import dev.kstep.core.DslViolation
import dev.kstep.core.DslViolationCodes
import dev.kstep.express.validation.WhereRuleViolation

internal fun WhereRuleViolation.toDslViolation(): DslViolation =
    DslViolation(
        code = DslViolationCodes.WHERE_RULE_NOT_SATISFIED,
        entityName = entityName,
        ruleLabel = ruleLabel,
        expressionText = expressionText,
        message = "WHERE rule ${ruleLabel ?: "(unlabeled)"} not satisfied: $expressionText",
    )

internal fun missingMandatoryReferenceViolation(
    entityName: String,
    attributeName: String,
): DslViolation =
    DslViolation(
        code = DslViolationCodes.MISSING_MANDATORY_REFERENCE,
        entityName = entityName,
        ruleLabel = null,
        expressionText = null,
        message = "required attribute '$attributeName' of entity '$entityName' was never set",
    )
