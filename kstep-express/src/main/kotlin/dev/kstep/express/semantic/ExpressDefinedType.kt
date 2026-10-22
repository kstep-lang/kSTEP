package dev.kstep.express.semantic

/**
 * One member of a closed `TYPE x = SELECT (m1, m2, ...)` list. The grammar cannot tell an
 * `entityRef` from a `typeRef` there (`namedTypes`), so the semantic model resolves each member
 * against the schema's symbol table and records which kind it is. [name] keeps the declaration's
 * original casing.
 */
sealed interface ExpressSelectItem {
    val name: String

    data class Entity(
        override val name: String,
    ) : ExpressSelectItem

    data class DefinedType(
        override val name: String,
    ) : ExpressSelectItem
}

/**
 * An EXPRESS `TYPE X = underlyingType;` declaration (ISO 10303-11 `typeDecl`), captured well
 * enough for V1 codegen to resolve the forms it supports without further indirection:
 * - a direct alias to STRING/INTEGER/REAL/BOOLEAN/NUMBER/BINARY ([underlyingSimpleType]),
 * - a bare `typeRef` alias ([aliasedTypeName]), followed transitively by codegen,
 * - an explicit `ENUMERATION OF (...)` ([enumerationItems]),
 * - a closed `SELECT (a, b, ...)` ([selectItems], ADR-0020).
 *
 * Aggregation-backed TYPEs carry none of these fields and stay unsupported. EXTENSIBLE/BASED_ON/
 * GENERIC_ENTITY SELECTs and EXTENSIBLE/BASED_ON ENUMERATIONs are open-ended by definition and
 * are never modelled as closed Kotlin types: they carry no item list and set [unsupportedReason]
 * so codegen can name the actual cause. See `dev.kstep.express.codegen.ExpressKotlinCodeGenerator`.
 */
data class ExpressDefinedType(
    val name: String,
    val underlyingSimpleType: ExpressType?,
    /**
     * Non-null for `TYPE X = Y;` where `Y` is a bare `typeRef` (a name, not a simple type or
     * aggregation). Codegen follows this chain transitively, with a cycle/depth guard, until
     * it reaches a simple type, an [enumerationItems] declaration or a [selectItems] declaration.
     */
    val aliasedTypeName: String? = null,
    /**
     * Non-null for `TYPE X = ENUMERATION OF (a, b, ...);` with an explicit item list, in
     * declaration order. `EXTENSIBLE`/`BASED_ON` enumerations carry no list here and stay
     * unsupported by codegen.
     */
    val enumerationItems: List<String>? = null,
    /** Non-null only for a closed `SELECT (a, b, ...)` (no EXTENSIBLE / GENERIC_ENTITY / BASED_ON), in declaration order. */
    val selectItems: List<ExpressSelectItem>? = null,
    /** Non-null when the TYPE is a SELECT/ENUMERATION form codegen deliberately refuses; human-readable. */
    val unsupportedReason: String? = null,
)
