package dev.kstep.express.semantic

/**
 * One [ExpressAttribute.Explicit] contributed to a [ResolvedEntity]'s flattened attribute
 * list, tagged with the entity that actually declares it. [declaringEntity] is carried
 * through purely for diagnostics (error messages, KDoc) — [InheritanceResolver] already
 * resolved the attribute's type and optionality against the correct schema context before
 * this was built, so codegen never needs to re-walk the SUBTYPE OF chain itself.
 */
data class ResolvedAttribute(
    val declaringEntity: String,
    val attribute: ExpressAttribute.Explicit,
)

/**
 * An [ExpressEntity] with its SUBTYPE OF chain resolved: every ancestor's explicit attributes
 * flattened into a single ordered list, supertype-most-general first, then this entity's own
 * (matching STEP Part 21 instance-encoding attribute order — see [InheritanceResolver]).
 *
 * Produced by [InheritanceResolver.resolve] (whole-schema) or [InheritanceResolver.resolveStandalone]
 * (a single entity known to have no SUBTYPE OF of its own). Consumed by
 * `dev.kstep.express.codegen.ExpressKotlinCodeGenerator`, which emits a Kotlin `data class` per
 * [ResolvedEntity] with [isInstantiable] `true` and skips the ones that are `false` (an
 * `ABSTRACT SUPERTYPE` contributes attributes to its subtypes but is never itself instantiated).
 */
data class ResolvedEntity(
    val entity: ExpressEntity,
    val flattenedAttributes: List<ResolvedAttribute>,
    val isInstantiable: Boolean,
    val ancestorChain: List<String>,
)
