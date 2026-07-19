package dev.kstep.express.validation

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.semantic.ExpressEntity

/**
 * One WHERE-rule domain rule to check, decoupled from [dev.kstep.express.semantic.ExpressWhereRule] so
 * callers outside `dev.kstep.express.semantic` (e.g. `kstep-core`) can supply rules without depending on
 * the semantic-model package.
 */
data class WhereRuleSpec(
    val label: String?,
    val expressionText: String,
    val sourceLine: Int = 0,
)

/** A WHERE rule that evaluated to `false` against the supplied attribute values. */
data class WhereRuleViolation(
    val entityName: String,
    val ruleLabel: String?,
    val expressionText: String,
    val sourceLine: Int,
)

/**
 * Validates instance attribute values against WHERE-rule specs. No public signature here
 * accepts or returns an ANTLR parse-tree type or a `dev.kstep.express.grammar.*` type — only
 * the [validate] overload taking `(entityName, rules, attributeValues)` is meant for callers
 * outside `dev.kstep.express`, keeping their dependency on this module free of any need for
 * `antlr4-runtime` on their own compile classpath.
 *
 * Each call re-parses `rule.expressionText` — no caching. At V1's scale (six entities, at
 * most one WHERE rule each) this is fine; a future contributor should check actual need
 * before adding a cache rather than adding one reflexively.
 *
 * Two distinct, deliberately non-overlapping failure channels:
 *  - An *unsatisfied* rule (evaluates to `false`) is a non-throwing [WhereRuleViolation]
 *    entry in the returned list — expected, recoverable, structured data.
 *  - A rule using a construct outside the supported subset, or one that is not itself
 *    syntactically valid re-parsed in isolation, makes [ExpressParserFactory.parseExpression]
 *    or [WhereRuleExpressionBuilder.build] *throw* ([dev.kstep.express.ExpressSyntaxException] /
 *    [UnsupportedWhereExpressionException]) — this propagates straight through [validate]
 *    uncaught. Never converted into a violation, never swallowed.
 */
object WhereRuleValidator {
    fun validate(
        entity: ExpressEntity,
        attributeValues: Map<String, WhereRuleValue>,
    ): List<WhereRuleViolation> =
        validate(
            entity.name,
            entity.whereRules.map { WhereRuleSpec(it.label, it.expressionText, it.sourceLine) },
            attributeValues,
        )

    fun validate(
        entityName: String,
        rules: List<WhereRuleSpec>,
        attributeValues: Map<String, WhereRuleValue>,
    ): List<WhereRuleViolation> =
        rules.mapNotNull { rule ->
            val tree = ExpressParserFactory.parseExpression(rule.expressionText)
            val expression = WhereRuleExpressionBuilder.build(tree)
            val satisfied = WhereRuleEvaluator.evaluate(expression, attributeValues)
            if (satisfied) {
                null
            } else {
                WhereRuleViolation(entityName, rule.label, rule.expressionText, rule.sourceLine)
            }
        }
}
