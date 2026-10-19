package dev.kstep.core.ap242

import dev.kstep.core.DslViolation
import dev.kstep.core.DslViolationCodes
import dev.kstep.express.validation.AcyclicRelationshipEvaluator
import dev.kstep.express.validation.AppliedAssumption
import dev.kstep.express.validation.InstanceEvaluationContext
import dev.kstep.express.validation.InverseAttributeEvaluator
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.UniqueRuleEvaluator
import dev.kstep.express.validation.WhereRuleValidator
import java.util.IdentityHashMap

/** The graph-level violations found on one instance. */
class InstanceFinding(
    val instance: Any,
    val entityName: String,
    val violations: List<DslViolation>,
) {
    override fun toString(): String = "InstanceFinding($entityName, ${violations.size} violation(s))"
}

/** The outcome of [Ap242ModelValidator.validate]. */
class ModelValidationResult(
    val findings: List<InstanceFinding>,
    /** Every assumption made while evaluating (never empty when an unmodelled-entity USEDIN was evaluated). */
    val assumptions: List<AppliedAssumption>,
    val instanceCount: Int,
    /**
     * Findings withheld because the instance is referenced by something that could not be
     * reconstructed (opaque, rejected or skipped on read): its referrers are invisible to the
     * index, so a USEDIN/INVERSE verdict would be unreliable. `0` outside `Part21Reader`.
     * A cycle found by the acyclic check and a UNIQUE violation are facts about the visible population
     * and are never withheld.
     */
    val suppressedFindingCount: Int = 0,
    /**
     * Rules that could not be decided at all because the population is incomplete around the instance
     * (e.g. the acyclic check walking through a node with invisible referrers and finding no cycle).
     * They are neither findings nor passes (ADR-0023).
     */
    val indeterminateCount: Int = 0,
) {
    val isValid: Boolean get() = findings.isEmpty()
}

/**
 * Validates the constraints of an AP242 V1 population that depend on the instance graph
 * (ADR-0022, ADR-0023): WHERE rules using `SIZEOF(USEDIN(...))` or the acyclic-relationship check
 * (including those inherited from supertypes), `INVERSE` attribute bounds and `UNIQUE` rules. Local
 * rules stay with the builders; nothing is reported twice. The population is the transitive
 * closure of [validate]'s `roots` over entity references.
 *
 * Failures of the population itself (limits, unknown instance types) and unsupported schema
 * constructs propagate as exceptions, like the other kSTEP validators' second failure channel.
 */
object Ap242ModelValidator {
    /**
     * @param incomplete instances (by identity) whose referrers are partly invisible (see
     *   [ModelValidationResult.suppressedFindingCount]); `Part21Reader` passes those referenced by
     *   instances it could not reconstruct.
     */
    fun validate(
        roots: Collection<Any>,
        limits: ReverseReferenceIndex.Limits = ReverseReferenceIndex.Limits(),
        incomplete: Collection<Any> = emptyList(),
    ): ModelValidationResult {
        val index = ReverseReferenceIndex.build(roots, Ap242InstanceDescriber, limits)
        val incompleteSet = IdentityHashMap<Any, Unit>().also { map -> incomplete.forEach { map[it] = Unit } }
        val byInstance = IdentityHashMap<Any, MutableList<DslViolation>>()
        val assumptions = mutableListOf<AppliedAssumption>()
        var suppressed = 0
        var indeterminate = 0
        // One budget for the whole run: every usage walks its ancestors, so N usages could otherwise cost N*(V+E).
        val acyclicBudget = AcyclicRelationshipEvaluator.Budget()
        for (instance in index.instances()) {
            val description = checkNotNull(index.describe(instance))
            val entityName = description.entityName
            val rules = Ap242V1SchemaContext.graphRules[entityName].orEmpty()
            val inverses = Ap242V1SchemaContext.inverseAttributes[entityName].orEmpty()
            if (rules.isEmpty() && inverses.isEmpty()) continue
            val context =
                InstanceEvaluationContext(
                    instance,
                    entityName,
                    index,
                    Ap242V1SchemaContext.roleResolver,
                    incomplete,
                    acyclicBudget = acyclicBudget,
                )
            val kept = mutableListOf<DslViolation>()
            var withheld = false
            if (rules.isNotEmpty()) {
                val report =
                    WhereRuleValidator.validatePrepared(
                        entityName,
                        rules,
                        description.attributeValues,
                        context,
                    )
                assumptions += report.assumptions
                indeterminate += report.indeterminateCount
                for (violation in report.violations) {
                    // A cycle among visible instances is a fact; a USEDIN cardinality verdict is not,
                    // while an invisible referrer may still exist.
                    if (!violation.cycle && incompleteSet.containsKey(instance)) {
                        withheld = true
                    } else {
                        kept += violation.toDslViolation()
                    }
                }
            }
            for (inverse in inverses) {
                val evaluation =
                    InverseAttributeEvaluator.evaluate(
                        entityName,
                        inverse,
                        Ap242V1SchemaContext.resolvedEntities,
                        context,
                        Ap242V1SchemaContext.definedTypes,
                    )
                if (!evaluation.withinBounds) {
                    if (incompleteSet.containsKey(instance)) {
                        withheld = true
                    } else {
                        kept += inverseBoundViolation(entityName, evaluation)
                    }
                }
            }
            if (withheld) suppressed++
            if (kept.isNotEmpty()) byInstance.getOrPut(instance) { mutableListOf() }.addAll(kept)
        }

        val unique =
            UniqueRuleEvaluator.evaluate(
                index,
                Ap242V1SchemaContext.resolvedEntities,
                Ap242V1SchemaContext.roleResolver,
                Ap242V1SchemaContext.INTRINSICS,
                incomplete,
            )
        indeterminate += unique.indeterminateCount
        for (violation in unique.violations) {
            for (instance in violation.instances) {
                val entityName = checkNotNull(index.entityNameOf(instance))
                byInstance.getOrPut(instance) { mutableListOf() } += uniqueRuleViolation(entityName, violation)
            }
        }

        val findings =
            index.instances().mapNotNull { instance ->
                byInstance[instance]?.let { InstanceFinding(instance, checkNotNull(index.entityNameOf(instance)), it) }
            }
        return ModelValidationResult(findings, assumptions, index.instanceCount, suppressed, indeterminate)
    }

    private fun uniqueRuleViolation(
        entityName: String,
        violation: UniqueRuleEvaluator.Violation,
    ): DslViolation {
        val label = violation.rule.rule.label ?: "(unlabeled)"
        val fields = violation.fieldValues.joinToString(", ") { (name, value) -> "$name=$value" }
        return DslViolation(
            code = DslViolationCodes.UNIQUE_RULE_VIOLATED,
            entityName = entityName,
            ruleLabel = label,
            expressionText =
                violation.rule.rule.referencedAttributes
                    .joinToString(", "),
            message =
                "UNIQUE rule ${violation.rule.declaringEntity}.$label violated: ${violation.instances.size} " +
                    "instances share ($fields)",
        )
    }
}
