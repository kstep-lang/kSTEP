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
}
