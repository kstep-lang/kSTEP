package dev.kstep.express.validation

/**
 * The AST of the supported WHERE-rule expression subset — comparisons, `SELF.attribute`
 * references, `AND`/`OR`/`NOT` boolean combinators, and string/integer/real literals. This
 * is deliberately not a general EXPRESS expression AST: constructs outside this subset
 * (`EXISTS()`, `SIZEOF()`, other function calls, aggregate/set operations, `QUERY`, ...)
 * never reach this type — [WhereRuleExpressionBuilder] throws
 * [UnsupportedWhereExpressionException] for them instead of modeling them.
 */
sealed interface WhereRuleExpression {
    data class Comparison(
        val operator: ComparisonOperator,
        val left: WhereRuleExpression,
        val right: WhereRuleExpression,
    ) : WhereRuleExpression

    data class And(
        val left: WhereRuleExpression,
        val right: WhereRuleExpression,
    ) : WhereRuleExpression

    data class Or(
        val left: WhereRuleExpression,
        val right: WhereRuleExpression,
    ) : WhereRuleExpression

    data class Not(
        val operand: WhereRuleExpression,
    ) : WhereRuleExpression

    /** `SELF.attributeName`, or the equivalent bare `attributeName` (see grammar-ambiguity note in the builder). */
    data class SelfAttribute(
        val name: String,
    ) : WhereRuleExpression

    data class StringLiteral(
        val value: String,
    ) : WhereRuleExpression

    data class IntegerLiteral(
        val value: Long,
    ) : WhereRuleExpression

    data class RealLiteral(
        val value: Double,
    ) : WhereRuleExpression
}

enum class ComparisonOperator {
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    EQUAL,
    NOT_EQUAL,
}
