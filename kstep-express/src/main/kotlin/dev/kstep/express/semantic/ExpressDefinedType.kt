package dev.kstep.express.semantic

/**
 * An EXPRESS `TYPE X = underlyingType;` declaration (ISO 10303-11 `typeDecl`), captured well
 * enough to identify the single case V1 codegen can resolve without further indirection:
 * `TYPE X = <simpleTypes>;` — a direct alias to STRING/INTEGER/REAL/BOOLEAN/NUMBER/BINARY
 * (`typeDecl -> underlyingType -> concreteTypes -> simpleTypes`).
 *
 * [underlyingSimpleType] is non-null only for that case. A bare `typeRef` alias is captured in
 * [aliasedTypeName] and an explicit `ENUMERATION OF (...)` in [enumerationItems]; codegen follows
 * alias chains transitively and emits a Kotlin enum class per enumeration. Aggregation-backed and
 * SELECT TYPEs carry none of the three and stay unsupported; see
 * `dev.kstep.express.codegen.ExpressKotlinCodeGenerator`, which throws `CodeGenException` for them.
 */
data class ExpressDefinedType(
    val name: String,
    val underlyingSimpleType: ExpressType?,
    /**
     * Non-null for `TYPE X = Y;` where `Y` is a bare `typeRef` (a name, not a simple type or
     * aggregation). Codegen follows this chain transitively, with a cycle/depth guard, until
     * it reaches a simple type or an [enumerationItems] declaration.
     */
    val aliasedTypeName: String? = null,
    /**
     * Non-null for `TYPE X = ENUMERATION OF (a, b, ...);` with an explicit item list, in
     * declaration order. `EXTENSIBLE`/`BASED_ON` enumerations carry no list here and stay
     * unsupported by codegen.
     */
    val enumerationItems: List<String>? = null,
)
