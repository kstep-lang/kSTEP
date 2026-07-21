package dev.kstep.express.semantic

/** An EXPRESS entity attribute declared inside `entityBody.explicitAttr`. */
sealed interface ExpressAttribute {
    val sourceLine: Int

    data class Explicit(
        val name: String,
        val declaredType: ExpressType,
        val isOptional: Boolean,
        override val sourceLine: Int,
    ) : ExpressAttribute

    /**
     * `SELF\entity.attr (RENAMED newName)?` — captured verbatim, not resolved. Still out of
     * scope even after [InheritanceResolver] added SUBTYPE OF attribute flattening: a
     * `Redeclared` attribute encountered while flattening makes [InheritanceResolver] throw a
     * [SemanticModelException] rather than attempt to resolve it, a deliberate V1 scope
     * decision, not a missing capability.
     */
    data class Redeclared(
        val rawText: String,
        override val sourceLine: Int,
    ) : ExpressAttribute
}
