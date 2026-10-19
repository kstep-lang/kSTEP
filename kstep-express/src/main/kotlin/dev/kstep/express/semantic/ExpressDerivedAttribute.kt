package dev.kstep.express.semantic

/**
 * A `DERIVE` clause `derivedAttr` -- `attributeDecl : declaredType := expression;`. The
 * initializer `expression` is captured verbatim, exactly like [ExpressWhereRule.expressionText]:
 * real AP242 DERIVE initializers call functions (`get_name_value(SELF)`) outside the supported
 * expression subset. The semantic model stays capture-only; the supported subset is translated
 * to computed Kotlin properties by `dev.kstep.express.codegen.DerivedAttributeCodeGen` (ADR-0021).
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
     * `SELF\supertype.attr (RENAMED newName)? : declaredType := expression;`. [rawText] keeps the
     * verbatim declaration; [supertypeName], [attributeName] and [renamedTo] are the structured
     * parts, read straight from the grammar's `redeclaredAttribute` context (no regex on text).
     * [InheritanceResolver] resolves it against the SUBTYPE OF chain (ADR-0021); `RENAMED` is
     * captured but rejected there as out of scope.
     */
    data class Redeclared(
        val rawText: String,
        val supertypeName: String,
        val attributeName: String,
        val renamedTo: String?,
        val declaredType: ExpressType,
        val expressionText: String,
        override val sourceLine: Int,
    ) : ExpressDerivedAttribute
}
