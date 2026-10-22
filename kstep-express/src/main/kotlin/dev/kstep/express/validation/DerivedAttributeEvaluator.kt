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
 * Since ADR-0023 the real AP242 DERIVE initializers are evaluable, but only with an instance-graph
 * context: `get_name_value(SELF)` and siblings (with [SchemaFunctionIntrinsicSet.AP242]) need the
 * population, and `next_assembly_usage_occurrence`'s two-hop `SELF\entity.attr\entity.attr`
 * chain ([WhereRuleExpression.QualifiedChain]) needs the referenced instances. Without a context
 * (or without the intrinsics) those expressions are still reported as unsupported/failing, never
 * guessed. A plain `SELF\entity.attr` is a [WhereRuleExpression.QualifiedSelfAttribute].
 *
 * `SIZEOF`/`USEDIN` stay unsupported in DERIVE initializers (the builder is called without
 * `allowInstanceGraphFunctions`).
 *
 * Re-parses [expressionText] on every call, no caching — same scale rationale as
 * [WhereRuleValidator]: at V1's scale there is nothing to cache against yet.
 */
object DerivedAttributeEvaluator {
    fun evaluate(
        expressionText: String,
        attributeValues: Map<String, WhereRuleValue>,
    ): WhereRuleValue = evaluate(expressionText, attributeValues, null)

    /**
     * As above, with an instance-graph [context] (needed by chains and schema-function intrinsics)
     * and the [intrinsics] to recognise. A result of [WhereRuleValue.Unset] is EXPRESS `?`.
     * Throws [IndeterminateDerivedValueException] when the population is incomplete.
     */
    fun evaluate(
        expressionText: String,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext?,
        intrinsics: SchemaFunctionIntrinsicSet = SchemaFunctionIntrinsicSet.NONE,
    ): WhereRuleValue {
        val tree = ExpressParserFactory.parseExpression(expressionText)
        val expression =
            WhereRuleExpressionBuilder.build(
                tree,
                WhereRuleExpressionBuilder.BuildOptions(
                    allowGroupQualifiedSelf = true,
                    schemaFunctionIntrinsics = intrinsics,
                ),
            )
        return WhereRuleEvaluator.evaluateToValue(expression, attributeValues, context)
    }

    /**
     * Convenience overload unwrapping [ExpressDerivedAttribute]'s `Explicit`/`Redeclared`
     * split — both carry `expressionText` identically, so evaluation doesn't care which shape
     * it is.
     */
    fun evaluate(
        derivedAttribute: ExpressDerivedAttribute,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext? = null,
        intrinsics: SchemaFunctionIntrinsicSet = SchemaFunctionIntrinsicSet.NONE,
    ): WhereRuleValue =
        evaluate(
            when (derivedAttribute) {
                is ExpressDerivedAttribute.Explicit -> derivedAttribute.expressionText
                is ExpressDerivedAttribute.Redeclared -> derivedAttribute.expressionText
            },
            attributeValues,
            context,
            intrinsics,
        )
}
