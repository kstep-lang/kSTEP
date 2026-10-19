package dev.kstep.express.validation

/**
 * The AST of the supported WHERE-rule expression subset — comparisons, `SELF.attribute`
 * references, `AND`/`OR`/`NOT` boolean combinators, string/integer/real literals, and the
 * single-argument `EXISTS(<attribute>)` presence check (kSTEP M2 Welle 10), and — only when
 * [WhereRuleExpressionBuilder.BuildOptions.allowInstanceGraphFunctions] is set — the
 * instance-graph form `SIZEOF(USEDIN(SELF, '<role>'))` (ADR-0022) — plus, behind their own opt-in
 * options, multi-hop `SELF\a.x\b.y` chains and the fixed AP242 schema-function intrinsics (ADR-0023). This is deliberately not a
 * general EXPRESS expression AST: constructs outside this subset (other built-in/user-defined
 * function calls, aggregate/set operations, `QUERY`, ...) never reach this type —
 * [WhereRuleExpressionBuilder] throws [UnsupportedWhereExpressionException] for them instead of
 * modeling them.
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
     * `SELF\entity.attributeName` -- only produced when
     * [WhereRuleExpressionBuilder.BuildOptions.allowGroupQualifiedSelf] is set (DERIVE initializers, ADR-0021); plain WHERE rules never see it. [WhereRuleEvaluator]
     * resolves it by [name] alone, exactly like [SelfAttribute]: whether [entity] really is the
     * current entity or one of its supertypes is a static schema check done by the codegen, not an
     * evaluation-time concern.
     */
    data class QualifiedSelfAttribute(
        val entity: String,
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

    /** The whole-instance `SELF` — only ever produced as the first argument of `USEDIN`. */
    data object SelfInstance : WhereRuleExpression

    /**
     * `USEDIN(SELF, '<role>')` — a BAG of the usages of the current instance in [roleText]
     * (`SCHEMA.ENTITY.ATTRIBUTE`, or `''` for any role). Needs an instance-graph context to evaluate.
     */
    data class UsedIn(
        val target: WhereRuleExpression,
        val roleText: String,
    ) : WhereRuleExpression

    /** `SIZEOF(aggregate)` — in the supported subset the operand is always a [UsedIn]. */
    data class SizeOf(
        val operand: WhereRuleExpression,
    ) : WhereRuleExpression

    /** One hop of a [QualifiedChain]: narrow the current value to [group] (when non-null), then read [attribute]. */
    data class ChainLink(
        val group: String?,
        val attribute: String,
    )

    /**
     * `SELF\a.x\b.y` (ADR-0023, only with
     * [WhereRuleExpressionBuilder.BuildOptions.allowGroupQualifiedSelf]): follows the entity- or
     * SELECT-typed attribute `x` of SELF (declared by `a`), narrows the value to entity `b`, reads its
     * attribute `y`. The result is EXPRESS `?` ([WhereRuleValue.Unset]) when the value is not an
     * instance of `b`. Always exactly the pairs of (group qualifier, attribute qualifier), in order.
     */
    data class QualifiedChain(
        val links: List<ChainLink>,
    ) : WhereRuleExpression

    /**
     * `get_name_value(SELF)` / `get_description_value(SELF)` / `get_id_value(SELF)` — only with
     * [WhereRuleExpressionBuilder.BuildOptions.schemaFunctionIntrinsics] [SchemaFunctionIntrinsicSet.AP242].
     * [function] is the lower-case function name, a key of [SchemaFunctionIntrinsics.ATTRIBUTE_VALUE_FUNCTIONS].
     */
    data class GetAttributeValue(
        val function: String,
    ) : WhereRuleExpression

    /**
     * The exact AP242 form `acyclic_product_definition_relationship(SELF,
     * [SELF\product_definition_relationship.related_product_definition], '<specificRelation>')` — only
     * with [SchemaFunctionIntrinsicSet.AP242]. [specificRelation] is the verbatim `SCHEMA.ENTITY` text.
     */
    data class AcyclicCall(
        val specificRelation: String,
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

/**
 * `true` iff evaluating this expression needs an instance graph (it contains [WhereRuleExpression.UsedIn]
 * or [WhereRuleExpression.SizeOf]). Recursion is bounded by the builder's nesting limit.
 */
fun WhereRuleExpression.requiresInstanceGraph(): Boolean = requiresInstanceGraph(0)

private const val MAX_REQUIRES_GRAPH_DEPTH = 512

private fun WhereRuleExpression.requiresInstanceGraph(depth: Int): Boolean {
    if (depth > MAX_REQUIRES_GRAPH_DEPTH) {
        throw UnsupportedWhereExpressionException("expression nesting exceeds $MAX_REQUIRES_GRAPH_DEPTH levels")
    }
    return when (this) {
        is WhereRuleExpression.UsedIn,
        is WhereRuleExpression.SizeOf,
        is WhereRuleExpression.SelfInstance,
        is WhereRuleExpression.QualifiedChain,
        is WhereRuleExpression.GetAttributeValue,
        is WhereRuleExpression.AcyclicCall,
        -> true
        is WhereRuleExpression.Comparison ->
            left.requiresInstanceGraph(depth + 1) ||
                right.requiresInstanceGraph(depth + 1)
        is WhereRuleExpression.And -> left.requiresInstanceGraph(depth + 1) || right.requiresInstanceGraph(depth + 1)
        is WhereRuleExpression.Or -> left.requiresInstanceGraph(depth + 1) || right.requiresInstanceGraph(depth + 1)
        is WhereRuleExpression.Not -> operand.requiresInstanceGraph(depth + 1)
        is WhereRuleExpression.SelfAttribute,
        is WhereRuleExpression.QualifiedSelfAttribute,
        is WhereRuleExpression.Exists,
        is WhereRuleExpression.StringLiteral,
        is WhereRuleExpression.IntegerLiteral,
        is WhereRuleExpression.RealLiteral,
        -> false
    }
}
