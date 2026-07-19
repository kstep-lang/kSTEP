package dev.kstep.express.semantic

/**
 * An EXPRESS `TYPE X = underlyingType;` declaration (ISO 10303-11 `typeDecl`), captured well
 * enough to identify the single case V1 codegen can resolve without further indirection:
 * `TYPE X = <simpleTypes>;` — a direct alias to STRING/INTEGER/REAL/BOOLEAN/NUMBER/BINARY
 * (`typeDecl -> underlyingType -> concreteTypes -> simpleTypes`).
 *
 * [underlyingSimpleType] is non-null only for that case. It is null for everything else a
 * `typeDecl` can legally contain: an aggregation (`concreteTypes -> aggregationTypes`, e.g.
 * `TYPE ids = LIST OF identifier;`), a reference to another TYPE or an entity one or more
 * levels removed from a simple type (`concreteTypes -> typeRef`, e.g. `TYPE outer = inner;`),
 * or a constructed type (`constructedTypes`: SELECT/ENUMERATION). Resolving those — recursive
 * alias-chain resolution, SELECT/ENUMERATION-to-Kotlin mapping — is out of scope for V1; see
 * `dev.kstep.express.codegen.ExpressKotlinCodeGenerator`, which throws `CodeGenException` for
 * a `DefinedTypeRef` whose [ExpressDefinedType.underlyingSimpleType] is null.
 */
data class ExpressDefinedType(
    val name: String,
    val underlyingSimpleType: ExpressType?,
)
