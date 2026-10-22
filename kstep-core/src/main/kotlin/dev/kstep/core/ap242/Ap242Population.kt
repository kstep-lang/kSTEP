package dev.kstep.core.ap242

import dev.kstep.express.validation.DerivedValueScope
import dev.kstep.express.validation.ReverseReferenceIndex

/**
 * A population of AP242 V1 instances for reading **population-dependent derived values** (ADR-0023):
 * `product_definition.name`, `application_context.id`, `person_and_organization.description` and so on
 * are `get_*_value(SELF)` in the schema, a function of which `*_attribute` instances name the item.
 * The generated types therefore expose them as `fun name(scope: DerivedValueScope): String?`:
 *
 * ```
 * val population = Ap242Population.of(listOf(nauo, nameAttribute))
 * val name: String? = productDefinition.name(population.derivedScope)
 * ```
 *
 * `null` is EXPRESS `?` (zero or several `name_attribute` instances). An instance that is referenced by
 * something that could not be reconstructed (see [of]'s `incomplete`) makes the call throw
 * [dev.kstep.express.validation.IndeterminateDerivedValueException] instead of answering `null`.
 * The population is the transitive closure of the roots over entity references, as in [Ap242ModelValidator].
 */
class Ap242Population private constructor(
    val instanceCount: Int,
    /** The scope to pass to the generated population-dependent derived functions. */
    val derivedScope: DerivedValueScope,
) {
    companion object {
        /**
         * @param incomplete instances (by identity) whose referrers are partly invisible, e.g. because an
         *   opaque or rejected instance of a Part 21 file references them.
         */
        fun of(
            roots: Collection<Any>,
            limits: ReverseReferenceIndex.Limits = ReverseReferenceIndex.Limits(),
            incomplete: Collection<Any> = emptyList(),
        ): Ap242Population {
            val index = ReverseReferenceIndex.build(roots, Ap242InstanceDescriber, limits)
            return Ap242Population(index.instanceCount, index.asDerivedValueScope(incomplete))
        }
    }
}
