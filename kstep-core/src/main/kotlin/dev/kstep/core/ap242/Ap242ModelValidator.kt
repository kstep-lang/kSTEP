package dev.kstep.core.ap242

import dev.kstep.core.DslViolation
import dev.kstep.express.validation.AppliedAssumption
import dev.kstep.express.validation.InstanceEvaluationContext
import dev.kstep.express.validation.InverseAttributeEvaluator
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.WhereRuleValidator

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
     */
    val suppressedFindingCount: Int = 0,
) {
    val isValid: Boolean get() = findings.isEmpty()
}

/**
 * Validates the constraints of an AP242 V1 population that depend on the instance graph
 * (ADR-0022): WHERE rules using `SIZEOF(USEDIN(...))` and `INVERSE` attribute bounds. Local
 * rules stay with the builders; nothing is reported twice. The population is the transitive
 * closure of [validate]'s `roots` over entity references.
 *
 * Failures of the population itself (limits, unknown instance types) and unsupported schema
 * constructs propagate as exceptions, like the other kSTEP validators' second failure channel.
 */
object Ap242ModelValidator {
    fun validate(
        roots: Collection<Any>,
        limits: ReverseReferenceIndex.Limits = ReverseReferenceIndex.Limits(),
    ): ModelValidationResult {
        val index = ReverseReferenceIndex.build(roots, Ap242InstanceDescriber, limits)
        val findings = mutableListOf<InstanceFinding>()
        val assumptions = mutableListOf<AppliedAssumption>()
        for (instance in index.instances()) {
            val description = checkNotNull(index.describe(instance))
            val entityName = description.entityName
            val rules = Ap242V1SchemaContext.graphRules[entityName].orEmpty()
            val inverses = Ap242V1SchemaContext.inverseAttributes[entityName].orEmpty()
            if (rules.isEmpty() && inverses.isEmpty()) continue
            val context = InstanceEvaluationContext(instance, entityName, index, Ap242V1SchemaContext.roleResolver)
            val violations = mutableListOf<DslViolation>()
            if (rules.isNotEmpty()) {
                val report =
                    WhereRuleValidator.validatePrepared(
                        entityName,
                        rules,
                        description.attributeValues,
                        context,
                    )
                violations += report.violations.map { it.toDslViolation() }
                assumptions += report.assumptions
            }
            for (inverse in inverses) {
                val evaluation =
                    InverseAttributeEvaluator.evaluate(
                        entityName,
                        inverse,
                        Ap242V1SchemaContext.resolvedEntities,
                        context,
                    )
                if (!evaluation.withinBounds) violations += inverseBoundViolation(entityName, evaluation)
            }
            if (violations.isNotEmpty()) findings += InstanceFinding(instance, entityName, violations)
        }
        return ModelValidationResult(findings, assumptions, index.instanceCount)
    }
}
