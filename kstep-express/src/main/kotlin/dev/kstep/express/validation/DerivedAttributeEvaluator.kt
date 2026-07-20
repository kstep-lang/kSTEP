package dev.kstep.express.validation

import dev.kstep.express.ExpressParserFactory
import dev.kstep.express.semantic.ExpressDerivedAttribute

/**
 * Evaluates a `DERIVE` attribute's initializer expression against instance attribute values,
 * by reusing [WhereRuleExpressionBuilder]/[WhereRuleEvaluator] rather than a second parser/
 * evaluator: DERIVE's initializer and WHERE's `domainRule` are both the identical `expression`
 * grammar production, so a DERIVE expression re-parses and walks exactly like a WHERE-rule
 * expression does. Only the top-level contract differs — a WHERE rule must reduce to a
 * boolean, a DERIVE initializer may reduce to any [WhereRuleValue] — which is exactly what
 * [WhereRuleEvaluator.evaluateToValue] (as opposed to [WhereRuleEvaluator.evaluate]) exists
 * for.
 *
 * Real AP242 DERIVE initializers (`get_name_value(SELF)`, and
 * `next_assembly_usage_occurrence`'s two-hop `SELF\entity.attr\entity.attr` chain) are outside
 * [WhereRuleExpressionBuilder]'s supported subset by construction and correctly throw
 * [UnsupportedWhereExpressionException] — that is this wave's expected, complete outcome for
 * those three real clauses, not a gap. (Cosmetic wart, deliberately not fixed: the builder's
 * `unsupported(...)` messages literally say "WHERE-rule expression uses ..." even when
 * triggered from here — renaming that shared, generic wording for a DERIVE-specific case is
 * unjustified scope creep; no test in this codebase depends on the exact wording.)
 *
 * Re-parses [expressionText] on every call, no caching — same scale rationale as
 * [WhereRuleValidator]: at V1's scale there is nothing to cache against yet.
 */
object DerivedAttributeEvaluator {
    fun evaluate(
        expressionText: String,
        attributeValues: Map<String, WhereRuleValue>,
    ): WhereRuleValue {
        val tree = ExpressParserFactory.parseExpression(expressionText)
        val expression = WhereRuleExpressionBuilder.build(tree)
        return WhereRuleEvaluator.evaluateToValue(expression, attributeValues)
    }

    /**
     * Convenience overload unwrapping [ExpressDerivedAttribute]'s `Explicit`/`Redeclared`
     * split — both carry `expressionText` identically, so evaluation doesn't care which shape
     * it is.
     */
    fun evaluate(
        derivedAttribute: ExpressDerivedAttribute,
        attributeValues: Map<String, WhereRuleValue>,
    ): WhereRuleValue =
        evaluate(
            when (derivedAttribute) {
                is ExpressDerivedAttribute.Explicit -> derivedAttribute.expressionText
                is ExpressDerivedAttribute.Redeclared -> derivedAttribute.expressionText
            },
            attributeValues,
        )
}
