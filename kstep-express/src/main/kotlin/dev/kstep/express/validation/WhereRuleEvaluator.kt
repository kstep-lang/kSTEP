package dev.kstep.express.validation

/**
 * Evaluates a [WhereRuleExpression] AST against a bag of instance attribute values. Purely
 * a tree-walking interpreter over the already-built, already-restricted-to-the-supported-
 * subset AST — [WhereRuleExpressionBuilder] is what rejects unsupported constructs, this
 * object only ever sees expressions it can fully evaluate (or reports a data problem for,
 * via [WhereRuleEvaluationException]: a missing attribute, a non-boolean top-level result,
 * or an incompatible-type comparison).
 */
object WhereRuleEvaluator {
    private const val MAX_EVALUATION_DEPTH = 128

    /** Evaluates [expression], requiring the top-level result to be boolean (as any WHERE-rule domainRule must be). */
    fun evaluate(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
    ): Boolean {
        val result = evaluateValue(expression, attributeValues, 0)
        val booleanResult =
            result as? WhereRuleValue.BooleanValue
                ?: throw WhereRuleEvaluationException(
                    "a WHERE-rule domain rule must evaluate to a boolean, but evaluated to $result",
                )
        return booleanResult.value
    }

    private fun evaluateValue(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
        depth: Int,
    ): WhereRuleValue {
        if (depth > MAX_EVALUATION_DEPTH) {
            throw WhereRuleEvaluationException(
                "WHERE-rule expression evaluation exceeds $MAX_EVALUATION_DEPTH levels of nesting",
            )
        }
        return when (expression) {
            is WhereRuleExpression.Comparison -> {
                val left = evaluateValue(expression.left, attributeValues, depth + 1)
                val right = evaluateValue(expression.right, attributeValues, depth + 1)
                WhereRuleValue.BooleanValue(evaluateComparison(expression.operator, left, right))
            }
            is WhereRuleExpression.And -> {
                val left = requireBoolean(evaluateValue(expression.left, attributeValues, depth + 1))
                val right = requireBoolean(evaluateValue(expression.right, attributeValues, depth + 1))
                WhereRuleValue.BooleanValue(left && right)
            }
            is WhereRuleExpression.Or -> {
                val left = requireBoolean(evaluateValue(expression.left, attributeValues, depth + 1))
                val right = requireBoolean(evaluateValue(expression.right, attributeValues, depth + 1))
                WhereRuleValue.BooleanValue(left || right)
            }
            is WhereRuleExpression.Not -> {
                val operand = requireBoolean(evaluateValue(expression.operand, attributeValues, depth + 1))
                WhereRuleValue.BooleanValue(!operand)
            }
            is WhereRuleExpression.SelfAttribute ->
                attributeValues[expression.name]
                    ?: throw WhereRuleEvaluationException(
                        "attribute '${expression.name}' referenced by SELF is not present in the supplied value bag",
                    )
            is WhereRuleExpression.StringLiteral -> WhereRuleValue.StringValue(expression.value)
            is WhereRuleExpression.IntegerLiteral -> WhereRuleValue.IntegerValue(expression.value)
            is WhereRuleExpression.RealLiteral -> WhereRuleValue.RealValue(expression.value)
        }
    }

    private fun requireBoolean(value: WhereRuleValue): Boolean =
        (value as? WhereRuleValue.BooleanValue)?.value
            ?: throw WhereRuleEvaluationException("expected a boolean operand for AND/OR/NOT but got $value")

    private fun evaluateComparison(
        operator: ComparisonOperator,
        left: WhereRuleValue,
        right: WhereRuleValue,
    ): Boolean {
        val comparison = compareValues(left, right)
        return when (operator) {
            ComparisonOperator.LESS_THAN -> comparison < 0
            ComparisonOperator.LESS_THAN_OR_EQUAL -> comparison <= 0
            ComparisonOperator.GREATER_THAN -> comparison > 0
            ComparisonOperator.GREATER_THAN_OR_EQUAL -> comparison >= 0
            ComparisonOperator.EQUAL -> comparison == 0
            ComparisonOperator.NOT_EQUAL -> comparison != 0
        }
    }

    // EXPRESS permits comparing INTEGER and REAL values numerically (INTEGER is, for
    // comparison purposes, a subtype of REAL) — promote instead of type-erroring on a
    // mixed-numeric-kind comparison, even though none of the ap242-subset fixture's own
    // rules currently need it.
    private fun compareValues(
        left: WhereRuleValue,
        right: WhereRuleValue,
    ): Int =
        when {
            left is WhereRuleValue.StringValue && right is WhereRuleValue.StringValue ->
                left.value.compareTo(
                    right.value,
                )
            left is WhereRuleValue.BooleanValue && right is WhereRuleValue.BooleanValue ->
                left.value.compareTo(
                    right.value,
                )
            isNumeric(left) && isNumeric(right) -> asDouble(left).compareTo(asDouble(right))
            else ->
                throw WhereRuleEvaluationException(
                    "cannot compare $left with $right — incompatible value kinds",
                )
        }

    private fun isNumeric(value: WhereRuleValue): Boolean =
        value is WhereRuleValue.IntegerValue || value is WhereRuleValue.RealValue

    private fun asDouble(value: WhereRuleValue): Double =
        when (value) {
            is WhereRuleValue.IntegerValue -> value.value.toDouble()
            is WhereRuleValue.RealValue -> value.value
            else -> error("asDouble called with a non-numeric value $value")
        }
}
