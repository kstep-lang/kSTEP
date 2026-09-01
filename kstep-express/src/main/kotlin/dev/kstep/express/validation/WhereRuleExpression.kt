package dev.kstep.express.validation

/**
 * The AST of the supported WHERE-rule expression subset — comparisons, `SELF.attribute`
 * references, `AND`/`OR`/`NOT` boolean combinators, string/integer/real literals, and the
 * single-argument `EXISTS(<attribute>)` presence check (kSTEP M2 Welle 10). This is
 * deliberately not a general EXPRESS expression AST: constructs outside this subset
 * (`SIZEOF()`, other built-in/user-defined function calls, aggregate/set operations, `QUERY`,
 * ...) never reach this type — [WhereRuleExpressionBuilder] throws
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

    /**
     * `EXISTS(attributeName)` / `EXISTS(SELF.attributeName)` — true iff the referenced OPTIONAL
     * attribute currently has a value ([WhereRuleValue.Unset] evaluates to `false` here and
     * nowhere else; every other use of an `Unset` attribute value is a
     * [WhereRuleEvaluationException]). The only `EXISTS()` shape kSTEP supports is a single,
     * direct attribute reference as the sole argument — `EXISTS(a.b)`, `EXISTS(f())`, or a
     * multi-argument call all remain outside the supported subset.
     */
    data class Exists(
        val attribute: String,
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
