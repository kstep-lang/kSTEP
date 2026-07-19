package dev.kstep.express.semantic

/** `SET`/`BAG` -- the only two aggregation wrappers `inverseAttrType` grammatically permits. */
enum class InverseAggregationKind { SET, BAG }

/**
 * An `INVERSE` clause `inverseAttr` -- `attributeDecl : (SET|BAG boundSpec? OF)? targetEntity
 * FOR (forEntity '.')? forAttribute;`. Captured structurally, not verbatim: `inverseAttrType`'s
 * shape is simple enough (an optional SET/BAG wrapper around a bare `entityRef`) that raw-text
 * capture would just make a caller re-parse it. `targetEntity`/`forEntity` are raw `entityRef`
 * text, deliberately unresolved against the schema symbol table -- unlike `parameterType`'s
 * `namedTypes` (`entityRef | typeRef`, a genuine grammar-level ambiguity `mapParameterType`
 * exists to resolve), `inverseAttrType`'s target is unambiguously an `entityRef`, so this
 * mirrors [ExpressEntity.supertypes]'s existing unresolved-raw-text convention instead.
 *
 * `inverseAttr` shares `attributeDecl` with `explicitAttr` (`attributeId | redeclaredAttribute`),
 * so a subtype can redeclare an inherited INVERSE with a `SELF\entity.attr`-qualified name --
 * mirrors [ExpressAttribute.Explicit]/[ExpressAttribute.Redeclared]'s existing split for the
 * identical ambiguity.
 */
sealed interface ExpressInverseAttribute {
    val kind: InverseAggregationKind?
    val boundsRawText: String?
    val targetEntity: String
    val forEntity: String?
    val forAttribute: String
    val sourceLine: Int

    data class Explicit(
        val name: String,
        override val kind: InverseAggregationKind?,
        override val boundsRawText: String?,
        override val targetEntity: String,
        override val forEntity: String?,
        override val forAttribute: String,
        override val sourceLine: Int,
    ) : ExpressInverseAttribute

    /**
     * `SELF\entity.attr : ... FOR ...;` -- the name is captured verbatim, not resolved.
     * Resolving it requires supertype attribute inheritance, out of scope for this wave
     * (mirrors [ExpressAttribute.Redeclared]).
     */
    data class Redeclared(
        val rawText: String,
        override val kind: InverseAggregationKind?,
        override val boundsRawText: String?,
        override val targetEntity: String,
        override val forEntity: String?,
        override val forAttribute: String,
        override val sourceLine: Int,
    ) : ExpressInverseAttribute
}
