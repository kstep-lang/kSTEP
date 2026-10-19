package dev.kstep.express.validation

/**
 * The population view that a population-dependent `DERIVE` value needs (ADR-0023): the attribute
 * value of a derived attribute such as `product_definition.name` is `get_name_value(SELF)`, a
 * function of *who references SELF*, not of SELF's own attributes. Generated code therefore takes a
 * scope as an explicit argument (`fun name(scope: DerivedValueScope): String?`) instead of being a
 * property getter.
 *
 * Implementations are immutable snapshots; obtain one from [ReverseReferenceIndex.asDerivedValueScope].
 */
interface DerivedValueScope {
    /** `USEDIN(target, 'schema.declaringEntity.attribute')`: one entry per usage, in discovery order. */
    fun usedIn(
        target: Any,
        declaringEntity: String,
        attribute: String,
    ): List<InstanceReference>

    /** What the population knows about [instance]; `null` when it is not part of the population. */
    fun describe(instance: Any): InstanceDescription?

    /**
     * `false` when [instance] is referenced by something that could not be reconstructed (an
     * opaque, rejected or skipped instance on a Part 21 read): its referrers are then partly
     * invisible, so any value derived from `USEDIN` would be unreliable.
     */
    fun isComplete(instance: Any): Boolean
}

/**
 * Thrown when a population-dependent derived value cannot be determined because the population
 * is incomplete for the instance in question (see [DerivedValueScope.isComplete]). Returning `null`
 * (EXPRESS `?`) there would be wrong: `?` means "0 or 2+ matches", not "unknown".
 */
class IndeterminateDerivedValueException(
    val entity: String,
    val attribute: String,
    val reason: String,
) : RuntimeException(
        "derived value '${clipForMessage(entity)}.${clipForMessage(attribute)}' is indeterminate: " +
            clipForMessage(reason),
    )
