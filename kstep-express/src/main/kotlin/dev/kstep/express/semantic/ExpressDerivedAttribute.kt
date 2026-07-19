package dev.kstep.express.semantic

/**
 * A `DERIVE` clause `derivedAttr` -- `attributeDecl : declaredType := expression;`. The
 * initializer `expression` is captured verbatim, exactly like [ExpressWhereRule.expressionText]:
 * real AP242 DERIVE initializers call functions (`get_name_value(SELF)`) this walker does not
 * evaluate -- evaluating DERIVE is out of scope for this wave, capture only.
 *
 * `derivedAttr` shares `attributeDecl` with `explicitAttr` (`attributeId | redeclaredAttribute`),
 * so a subtype can redeclare an inherited DERIVE with a `SELF\entity.attr`-qualified name --
 * mirrors [ExpressAttribute.Explicit]/[ExpressAttribute.Redeclared]'s existing split for the
 * identical ambiguity.
 */
sealed interface ExpressDerivedAttribute {
    val sourceLine: Int

    data class Explicit(
        val name: String,
        val declaredType: ExpressType,
        val expressionText: String,
        override val sourceLine: Int,
    ) : ExpressDerivedAttribute

    /**
     * `SELF\entity.attr : declaredType := expression;` -- the name is captured verbatim, not
     * resolved. Resolving it requires supertype attribute inheritance, out of scope for this
     * wave (mirrors [ExpressAttribute.Redeclared]).
     */
    data class Redeclared(
        val rawText: String,
        val declaredType: ExpressType,
        val expressionText: String,
        override val sourceLine: Int,
    ) : ExpressDerivedAttribute
}
