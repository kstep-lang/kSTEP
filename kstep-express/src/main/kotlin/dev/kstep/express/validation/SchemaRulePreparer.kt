package dev.kstep.express.validation

import dev.kstep.express.semantic.ResolvedEntity
import dev.kstep.express.semantic.ResolvedWhereRule

/**
 * Prepares every WHERE rule of a resolved schema once (ADR-0023). A rule outside the supported
 * expression subset is **never dropped**: the whole preparation fails with an
 * [IllegalStateException] naming entity, rule label and the first unsupported construct, because a
 * silently missing rule would turn into silently missing validation.
 */
object SchemaRulePreparer {
    fun prepareAll(
        resolvedEntities: Collection<ResolvedEntity>,
        intrinsics: SchemaFunctionIntrinsicSet,
        schemaLabel: String,
    ): Map<ResolvedWhereRule, PreparedWhereRule> {
        val prepared = LinkedHashMap<ResolvedWhereRule, PreparedWhereRule>()
        for (entity in resolvedEntities) {
            for (rule in entity.flattenedWhereRules) {
                if (rule in prepared) continue
                val spec = WhereRuleSpec(rule.rule.label, rule.rule.expressionText, rule.rule.sourceLine)
                when (val outcome = WhereRuleValidator.prepareForInstanceGraph(spec, intrinsics)) {
                    is PreparationOutcome.Prepared -> prepared[rule] = outcome.rule
                    is PreparationOutcome.Unsupported ->
                        throw IllegalStateException(
                            "$schemaLabel rule ${rule.declaringEntity}.${rule.rule.label ?: "(unlabeled)"} is " +
                                "outside the supported WHERE-rule subset: ${clipForMessage(outcome.reason, 400)}",
                        )
                }
            }
        }
        return prepared
    }
}
