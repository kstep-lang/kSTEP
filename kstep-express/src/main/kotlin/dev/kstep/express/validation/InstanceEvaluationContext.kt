package dev.kstep.express.validation

/**
 * The instance-graph context needed to evaluate `USEDIN`/`SIZEOF` (ADR-0022): the instance
 * being validated ([self], which must be part of [index]), the [index] over the population and
 * the [roleResolver] for role strings.
 */
class InstanceEvaluationContext(
    val self: Any,
    val selfEntity: String,
    val index: ReverseReferenceIndex,
    val roleResolver: UsedInRoleResolver,
) {
    init {
        require(index.contains(self)) {
            "SELF (${clipForMessage(selfEntity)}) is not part of the supplied reverse-reference index"
        }
    }
}

/**
 * An assumption the evaluator had to make to evaluate a rule — returned to the caller, never
 * only documented. Currently: `USEDIN` over an entity the schema excerpt does not model is
 * evaluated as the empty set.
 */
data class AppliedAssumption(
    val entityName: String,
    val ruleLabel: String?,
    val roleText: String,
    val reason: String,
)

/** A boolean WHERE-rule result together with the assumptions made while evaluating it. */
data class WhereRuleEvaluationResult(
    val value: Boolean,
    val assumptions: List<AppliedAssumption>,
)
