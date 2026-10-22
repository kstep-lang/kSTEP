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
    /** Instances (by identity) whose referrers are partly invisible; see [DerivedValueScope.isComplete]. */
    incomplete: Collection<Any> = emptyList(),
    val acyclicLimits: AcyclicRelationshipEvaluator.Limits = AcyclicRelationshipEvaluator.Limits(),
    /** Shared by all contexts of one validation run so that the acyclic walks together stay bounded. */
    val acyclicBudget: AcyclicRelationshipEvaluator.Budget =
        AcyclicRelationshipEvaluator.Budget(acyclicLimits.maxTotalVisited),
) {
    /** The population view for derived values (`get_*_value`) and the acyclic check. */
    val scope: DerivedValueScope = index.asDerivedValueScope(incomplete)

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
    /**
     * `true` when part of the evaluation could not be decided because the population is incomplete
     * (see [DerivedValueScope.isComplete]). [value] is then not a verdict: the caller must report
     * neither a violation nor a pass.
     */
    val indeterminate: Boolean = false,
    /** Human-readable evidence, e.g. the cycle path of a failed acyclic check. */
    val details: List<String> = emptyList(),
    /** `true` when a failed [value] was caused by a cycle found by the acyclic intrinsic. */
    val cycleFound: Boolean = false,
)
