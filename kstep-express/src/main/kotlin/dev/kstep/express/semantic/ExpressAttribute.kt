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
     * `SELF\entity.attr (RENAMED newName)?` — captured verbatim, not resolved.
     * Resolving it requires supertype attribute inheritance, out of scope for this wave.
     */
    data class Redeclared(
        val rawText: String,
        override val sourceLine: Int,
    ) : ExpressAttribute
}
