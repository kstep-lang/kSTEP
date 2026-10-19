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
    ): Boolean = evaluate(expression, attributeValues, null).value

    /**
     * As [evaluate], but with an optional instance-graph [context] enabling `SIZEOF(USEDIN(...))`
     * (ADR-0022) and returning the assumptions made on the way (an unmodelled-entity `USEDIN`
     * evaluated as the empty set). Without a [context], an expression containing `USEDIN` fails
     * loudly. [ruleLabel] only labels the reported assumptions.
     */
    fun evaluate(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext?,
        ruleLabel: String? = null,
    ): WhereRuleEvaluationResult {
        val trace = EvaluationTrace(context, ruleLabel)
        val result = evaluateValue(expression, attributeValues, trace, 0)
        val booleanResult =
            result as? WhereRuleValue.BooleanValue
                ?: throw WhereRuleEvaluationException(
                    "a WHERE-rule domain rule must evaluate to a boolean, but evaluated to $result",
                )
        return WhereRuleEvaluationResult(
            value = booleanResult.value,
            assumptions = trace.assumptions.toList(),
            indeterminate = trace.indeterminate,
            details = trace.details.toList(),
            cycleFound = trace.cycleFound,
        )
    }

    /**
     * Evaluates [expression] to its raw [WhereRuleValue], without [evaluate]'s top-level
     * boolean requirement — a DERIVE initializer's result is a value (string/integer/real/
     * boolean), not a domain-rule pass/fail. Reuses the same private tree-walk [evaluate]
     * itself uses, seeded at depth 0 exactly like [evaluate] does; this is a thin additional
     * public entry point, not a second interpreter.
     */
    fun evaluateToValue(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
    ): WhereRuleValue = evaluateToValue(expression, attributeValues, null)

    fun evaluateToValue(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
        context: InstanceEvaluationContext?,
    ): WhereRuleValue = evaluateValue(expression, attributeValues, EvaluationTrace(context, null), 0)

    // Per-call, never shared: holds the optional graph context and collects assumptions.
    private class EvaluationTrace(
        val context: InstanceEvaluationContext?,
        val ruleLabel: String?,
    ) {
        val assumptions = mutableListOf<AppliedAssumption>()
        var indeterminate = false
        var cycleFound = false
        val details = mutableListOf<String>()
    }

    private fun evaluateValue(
        expression: WhereRuleExpression,
        attributeValues: Map<String, WhereRuleValue>,
        trace: EvaluationTrace,
        depth: Int,
    ): WhereRuleValue {
        if (depth > MAX_EVALUATION_DEPTH) {
            throw WhereRuleEvaluationException(
                "WHERE-rule expression evaluation exceeds $MAX_EVALUATION_DEPTH levels of nesting",
            )
        }
        return when (expression) {
            is WhereRuleExpression.Comparison -> {
                val left = evaluateValue(expression.left, attributeValues, trace, depth + 1)
                val right = evaluateValue(expression.right, attributeValues, trace, depth + 1)
                WhereRuleValue.BooleanValue(evaluateComparison(expression.operator, left, right))
            }
            is WhereRuleExpression.And -> {
                val left = requireBoolean(evaluateValue(expression.left, attributeValues, trace, depth + 1))
                val right = requireBoolean(evaluateValue(expression.right, attributeValues, trace, depth + 1))
                WhereRuleValue.BooleanValue(left && right)
            }
            is WhereRuleExpression.Or -> {
                val left = requireBoolean(evaluateValue(expression.left, attributeValues, trace, depth + 1))
                val right = requireBoolean(evaluateValue(expression.right, attributeValues, trace, depth + 1))
                WhereRuleValue.BooleanValue(left || right)
            }
            is WhereRuleExpression.Not -> {
                val operand = requireBoolean(evaluateValue(expression.operand, attributeValues, trace, depth + 1))
                WhereRuleValue.BooleanValue(!operand)
            }
            is WhereRuleExpression.SelfAttribute -> lookupSelfAttribute(expression.name, attributeValues)
            is WhereRuleExpression.QualifiedSelfAttribute -> lookupSelfAttribute(expression.name, attributeValues)
            is WhereRuleExpression.Exists -> {
                val value =
                    attributeValues[expression.attribute]
                        ?: throw WhereRuleEvaluationException(
                            "attribute '${expression.attribute}' referenced by EXISTS() is not present in the " +
                                "supplied value bag",
                        )
                WhereRuleValue.BooleanValue(value !is WhereRuleValue.Unset)
            }
            is WhereRuleExpression.StringLiteral -> WhereRuleValue.StringValue(expression.value)
            is WhereRuleExpression.IntegerLiteral -> WhereRuleValue.IntegerValue(expression.value)
            is WhereRuleExpression.RealLiteral -> WhereRuleValue.RealValue(expression.value)
            is WhereRuleExpression.SelfInstance ->
                throw WhereRuleEvaluationException("a bare SELF instance is only valid as the first USEDIN() argument")
            is WhereRuleExpression.UsedIn -> evaluateUsedIn(expression, trace)
            is WhereRuleExpression.GetAttributeValue -> evaluateGetAttributeValue(expression, trace)
            is WhereRuleExpression.AcyclicCall -> evaluateAcyclic(expression, trace)
            is WhereRuleExpression.QualifiedChain -> evaluateChain(expression, trace)
            is WhereRuleExpression.SizeOf -> {
                val operand = evaluateValue(expression.operand, attributeValues, trace, depth + 1)
                val aggregate =
                    operand as? WhereRuleValue.InstanceAggregate
                        ?: throw WhereRuleEvaluationException("SIZEOF() requires an aggregate but got $operand")
                WhereRuleValue.IntegerValue(aggregate.size.toLong())
            }
        }
    }

    private fun requireContext(
        trace: EvaluationTrace,
        construct: String,
    ): InstanceEvaluationContext =
        trace.context
            ?: throw WhereRuleEvaluationException("$construct requires an instance-graph context; none was supplied")

    // get_*_value(SELF): the attribute_value of the single *_attribute instance naming SELF, else
    // EXPRESS `?` (Unset). An incomplete population throws IndeterminateDerivedValueException.
    private fun evaluateGetAttributeValue(
        expression: WhereRuleExpression.GetAttributeValue,
        trace: EvaluationTrace,
    ): WhereRuleValue {
        val context = requireContext(trace, "${expression.function}(SELF)")
        val function =
            SchemaFunctionIntrinsics.ATTRIBUTE_VALUE_FUNCTIONS[expression.function]
                ?: throw WhereRuleEvaluationException(
                    "unknown schema function '${clipForMessage(expression.function)}'",
                )
        val value =
            SchemaFunctionIntrinsics.getAttributeValue(
                context.scope,
                context.self,
                function.attributeEntity,
                function.roleAttribute,
            )
        return if (value == null) WhereRuleValue.Unset else WhereRuleValue.StringValue(value)
    }

    private fun evaluateAcyclic(
        expression: WhereRuleExpression.AcyclicCall,
        trace: EvaluationTrace,
    ): WhereRuleValue {
        val context = requireContext(trace, "${SchemaFunctionIntrinsics.ACYCLIC_FUNCTION}(SELF, ...)")
        val specific = context.roleResolver.resolveEntityName(expression.specificRelation)
        return when (val outcome = AcyclicRelationshipEvaluator.evaluate(context, specific)) {
            AcyclicRelationshipEvaluator.Outcome.Acyclic -> WhereRuleValue.BooleanValue(true)
            is AcyclicRelationshipEvaluator.Outcome.Cycle -> {
                trace.cycleFound = true
                trace.details += "cycle: " + AcyclicRelationshipEvaluator.describePath(outcome.path, context.index)
                WhereRuleValue.BooleanValue(false)
            }
            is AcyclicRelationshipEvaluator.Outcome.Indeterminate -> {
                trace.indeterminate = true
                trace.details += "indeterminate: ${clipForMessage(outcome.reason)}"
                WhereRuleValue.BooleanValue(true)
            }
        }
    }

    // SELF\a.x\b.y: navigation over entity references with a type test at every hop after the first.
    // The result is Unset (EXPRESS `?`) when a reference is unset or an instance is not of the narrowed type.
    private fun evaluateChain(
        expression: WhereRuleExpression.QualifiedChain,
        trace: EvaluationTrace,
    ): WhereRuleValue {
        val context = requireContext(trace, "a multi-hop SELF chain")
        var current: Any = context.self
        expression.links.forEachIndexed { position, link ->
            val description =
                context.index.describe(current)
                    ?: throw WhereRuleEvaluationException("chain instance is not part of the population")
            if (position > 0 &&
                link.group != null &&
                !context.roleResolver.isSubtypeOrSelf(description.entityName, link.group)
            ) {
                return WhereRuleValue.Unset
            }
            val attribute = link.attribute.lowercase()
            if (position == expression.links.lastIndex) {
                return description.attributeValues[link.attribute] ?: description.attributeValues[attribute]
                    ?: throw WhereRuleEvaluationException(
                        "chain attribute '${clipForMessage(link.attribute)}' of " +
                            "'${clipForMessage(description.entityName)}' is not a value attribute",
                    )
            }
            val reference = description.references.firstOrNull { it.attribute.lowercase() == attribute }
            if (reference == null) {
                if (description.attributeValues.containsKey(link.attribute)) {
                    throw WhereRuleEvaluationException(
                        "chain attribute '${clipForMessage(link.attribute)}' is not an entity reference",
                    )
                }
                return WhereRuleValue.Unset
            }
            current = reference.targets.singleOrNull() ?: return WhereRuleValue.Unset
        }
        return WhereRuleValue.Unset
    }

    private fun evaluateUsedIn(
        expression: WhereRuleExpression.UsedIn,
        trace: EvaluationTrace,
    ): WhereRuleValue {
        val roleShown = clipForMessage(expression.roleText)
        val context =
            trace.context
                ?: throw WhereRuleEvaluationException(
                    "USEDIN(SELF, '$roleShown') requires an instance-graph context; none was supplied",
                )
        return when (val role = context.roleResolver.resolve(expression.roleText)) {
            is ResolvedUsedInRole.AnyRole ->
                WhereRuleValue.InstanceAggregate(AggregateKind.BAG, context.index.incoming(context.self))
            is ResolvedUsedInRole.Modelled ->
                WhereRuleValue.InstanceAggregate(
                    AggregateKind.BAG,
                    context.index.usedIn(context.self, role.declaringEntity, role.attribute),
                )
            is ResolvedUsedInRole.KnownUnmodelled -> {
                trace.assumptions +=
                    AppliedAssumption(
                        entityName = context.selfEntity,
                        ruleLabel = trace.ruleLabel,
                        roleText = clipForMessage(role.roleText),
                        reason = role.reason,
                    )
                WhereRuleValue.InstanceAggregate(AggregateKind.BAG, emptyList())
            }
        }
    }

    private fun lookupSelfAttribute(
        name: String,
        attributeValues: Map<String, WhereRuleValue>,
    ): WhereRuleValue {
        val value =
            attributeValues[name]
                ?: throw WhereRuleEvaluationException(
                    "attribute '$name' referenced by SELF is not present in the supplied value bag",
                )
        if (value is WhereRuleValue.Unset) {
            throw WhereRuleEvaluationException(
                "attribute '$name' is Unset (an EXPRESS-optional attribute that was never " +
                    "set) and cannot be used directly in a WHERE-rule expression — only " +
                    "EXISTS($name) may reference an Unset attribute",
            )
        }
        return value
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
            left is WhereRuleValue.InstanceAggregate || right is WhereRuleValue.InstanceAggregate ->
                throw WhereRuleEvaluationException("cannot compare aggregate $left with $right — use SIZEOF()")
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
