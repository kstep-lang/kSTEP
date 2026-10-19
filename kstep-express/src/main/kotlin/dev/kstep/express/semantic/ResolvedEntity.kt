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
    /**
     * Non-null when a subtype redeclares this inherited *explicit* attribute as a `DERIVE`
     * (`SELF\super.attr : T := expr;`). The attribute keeps its position in the flattened list
     * (Part 21 attribute order is unchanged; the instance encodes `*` there) but is no longer a
     * constructor parameter of the generated class (ADR-0021).
     */
    val redeclaredAsDerived: ResolvedDerivedAttribute? = null,
)

/**
 * One `DERIVE` attribute of a [ResolvedEntity] after inheritance resolution: [declaringEntity] is
 * the entity whose initializer wins (the most specific one when a subtype redeclares it).
 */
data class ResolvedDerivedAttribute(
    val declaringEntity: String,
    /** EXPRESS name of the attribute. */
    val name: String,
    val declaredType: ExpressType,
    val expressionText: String,
    /** Non-null when this entry replaces an inherited attribute via `SELF\super.attr`. */
    val overrides: Override? = null,
    val sourceLine: Int,
) {
    sealed interface Override {
        val originalDeclaringEntity: String

        /** Replaces an inherited explicit attribute; its Part 21 position encodes `*`. */
        data class InheritedExplicit(
            override val originalDeclaringEntity: String,
        ) : Override

        /** Replaces an inherited DERIVE (same list position). */
        data class InheritedDerived(
            override val originalDeclaringEntity: String,
        ) : Override
    }

    /** The entity that first declared this attribute, whatever subtype overrides it later. */
    val originalDeclaringEntity: String
        get() = overrides?.originalDeclaringEntity ?: declaringEntity
}

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
    /** Flattened `DERIVE` attributes, ancestors first; a redeclared one replaces its ancestor's entry in place. */
    val flattenedDerivedAttributes: List<ResolvedDerivedAttribute> = emptyList(),
)
