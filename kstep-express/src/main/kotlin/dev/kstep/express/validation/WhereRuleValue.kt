package dev.kstep.express.validation

/**
 * The runtime value bag [WhereRuleEvaluator] operates on. WHERE rules constrain *instances*,
 * not the schema, so evaluation needs actual attribute values, not [dev.kstep.express.semantic.ExpressType]s.
 */
sealed interface WhereRuleValue {
    data class StringValue(
        val value: String,
    ) : WhereRuleValue

    data class IntegerValue(
        val value: Long,
    ) : WhereRuleValue

    data class RealValue(
        val value: Double,
    ) : WhereRuleValue

    data class BooleanValue(
        val value: Boolean,
    ) : WhereRuleValue

    /**
     * The value of an EXPRESS `OPTIONAL` attribute that was never set — distinct from an empty
     * [StringValue], which is a legitimate, present value. Meaningful only as an
     * [WhereRuleExpression.Exists] operand: [WhereRuleEvaluator] throws
     * [WhereRuleEvaluationException] if `Unset` reaches any other position (a bare
     * [WhereRuleExpression.SelfAttribute] reference, a comparison, an AND/OR/NOT operand) —
     * kSTEP does not model EXPRESS's three-valued (`UNKNOWN`) logic, so an `Unset` value simply
     * cannot participate in anything but presence-checking.
     */
    data object Unset : WhereRuleValue
}
