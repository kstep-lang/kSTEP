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

    /**
     * Marks an entity-typed (reference) attribute that currently has a value. Its only use is
     * `EXISTS(attribute)` on an OPTIONAL reference attribute (ADR-0023): the reference target itself is
     * carried by the instance description's references, never by the value bag, so any other use
     * (comparison, boolean operand) is a [WhereRuleEvaluationException].
     */
    data object EntityReference : WhereRuleValue

    /**
     * An EXPRESS BAG/SET of entity usages (the `USEDIN` result, or an `INVERSE` attribute value).
     * Identity semantics, hence not a data class. Never comparable: only `SIZEOF` consumes it.
     */
    class InstanceAggregate internal constructor(
        val kind: AggregateKind,
        val elements: List<InstanceReference>,
    ) : WhereRuleValue {
        val size: Int get() = elements.size

        override fun toString(): String = "InstanceAggregate($kind, size=$size)"
    }
}

enum class AggregateKind { BAG, SET }
