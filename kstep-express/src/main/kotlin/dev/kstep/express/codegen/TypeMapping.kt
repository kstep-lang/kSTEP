package dev.kstep.express.codegen

import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.DOUBLE
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.LIST
import com.squareup.kotlinpoet.ParameterizedTypeName
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.SET
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import dev.kstep.express.semantic.AggregationKind
import dev.kstep.express.semantic.AggregationType
import dev.kstep.express.semantic.BinaryType
import dev.kstep.express.semantic.BooleanType
import dev.kstep.express.semantic.DefinedTypeRef
import dev.kstep.express.semantic.EntityTypeRef
import dev.kstep.express.semantic.ExpressDefinedType
import dev.kstep.express.semantic.ExpressType
import dev.kstep.express.semantic.IntegerType
import dev.kstep.express.semantic.LogicalType
import dev.kstep.express.semantic.NumberType
import dev.kstep.express.semantic.RealType
import dev.kstep.express.semantic.StringType
import dev.kstep.express.semantic.UnsupportedType

/**
 * Maps a resolved [ExpressType] to a KotlinPoet [TypeName]. Throws [CodeGenException] for
 * anything not yet supported.
 *
 * [definedTypes] resolves a [DefinedTypeRef] against the owning schema's `TYPE` declarations
 * (keyed lowercase, mirroring the semantic model's own case-insensitive symbol table) — see
 * [resolveDefinedTypeRef]. It defaults to empty so call sites that never see a `DefinedTypeRef`
 * (most of `ExpressCodeGenTest`'s single-entity, schema-less cases) don't need to construct one.
 */
internal fun resolveKotlinTypeName(
    type: ExpressType,
    packageName: String,
    errorContext: String,
    definedTypes: Map<String, ExpressDefinedType> = emptyMap(),
): TypeName =
    when (type) {
        is StringType -> STRING
        is IntegerType -> INT
        is RealType -> DOUBLE
        is BooleanType -> BOOLEAN
        is EntityTypeRef -> ClassName(packageName, NamingConventions.toClassName(type.entityName))
        is AggregationType -> resolveAggregationTypeName(type, packageName, errorContext, definedTypes)
        is LogicalType -> throw CodeGenException(
            "$errorContext: EXPRESS LOGICAL (tri-state) has no V1 Kotlin mapping yet",
        )
        is NumberType -> throw CodeGenException("$errorContext: EXPRESS NUMBER has no V1 Kotlin mapping yet")
        is BinaryType -> throw CodeGenException("$errorContext: EXPRESS BINARY has no V1 Kotlin mapping yet")
        is DefinedTypeRef -> resolveDefinedTypeRef(type, packageName, errorContext, definedTypes)
        is UnsupportedType -> throw CodeGenException("$errorContext: ${type.reasonHint}")
    }

// Follows a chain of `TYPE a = b; TYPE b = c; ...` (ExpressDefinedType.aliasedTypeName) until it
// reaches a simple-type alias (resolved by recursing into resolveKotlinTypeName on that
// underlying type, so an alias ending in LOGICAL/NUMBER/BINARY still throws the same
// CodeGenException a direct attribute of that type would), an ENUMERATION (mapped to the
// Kotlin enum class ExpressKotlinCodeGenerator emits under the TYPE's own name) or a closed
// SELECT (mapped to the sealed interface emitted under the TYPE's own name, ADR-0020).
// Cyclic chains and chains longer than MAX_ALIAS_CHAIN_DEPTH throw rather than loop, naming the
// link that actually failed. EXTENSIBLE/BASED_ON SELECTs and ENUMERATIONs, aggregation-backed
// TYPEs and aliases of entities still throw, each with its own specific reason.
internal const val MAX_ALIAS_CHAIN_DEPTH = 32

/** Nesting limit for SELECT-in-SELECT membership; deliberately the same bound as alias chains. */
internal const val MAX_SELECT_NESTING_DEPTH = MAX_ALIAS_CHAIN_DEPTH

private fun resolveDefinedTypeRef(
    type: DefinedTypeRef,
    packageName: String,
    errorContext: String,
    definedTypes: Map<String, ExpressDefinedType>,
): TypeName {
    val visited = linkedSetOf<String>()
    var currentName = type.typeName
    while (true) {
        val key = currentName.lowercase()
        if (key in visited) {
            throw CodeGenException(
                "$errorContext: TYPE '${type.typeName}' has a cyclic alias chain " +
                    "(${(visited.toList() + key).joinToString(" -> ")}); codegen cannot resolve it",
            )
        }
        visited.add(key)
        if (visited.size > MAX_ALIAS_CHAIN_DEPTH) {
            throw CodeGenException(
                "$errorContext: TYPE '${type.typeName}' has an over-long alias chain that exceeds " +
                    "MAX_ALIAS_CHAIN_DEPTH ($MAX_ALIAS_CHAIN_DEPTH) at '$currentName' " +
                    "(${visited.joinToString(" -> ")}); codegen cannot resolve it",
            )
        }
        val path = visited.joinToString(" -> ")
        val definedType =
            definedTypes[key]
                ?: throw CodeGenException(
                    "$errorContext: attribute references TYPE '$currentName' (alias path $path), which " +
                        "codegen has no resolution info for (no matching ExpressDefinedType was supplied " +
                        "for this schema, or the name is an entity rather than a TYPE)",
                )
        definedType.unsupportedReason?.let { reason ->
            throw CodeGenException(
                "$errorContext: TYPE '$currentName' (reached from '${type.typeName}' via $path) is " +
                    "unsupported: $reason",
            )
        }
        definedType.underlyingSimpleType?.let {
            return resolveKotlinTypeName(it, packageName, errorContext, definedTypes)
        }
        if (definedType.enumerationItems != null || definedType.selectItems != null) {
            return ClassName(packageName, NamingConventions.toClassName(definedType.name))
        }
        currentName =
            definedType.aliasedTypeName
                ?: throw CodeGenException(
                    "$errorContext: TYPE '$currentName' (reached from '${type.typeName}' via $path) is an " +
                        "aggregation-backed TYPE (or another form with no Kotlin mapping); only simple-alias " +
                        "chains, explicit ENUMERATION OF (...) and closed SELECT (...) are resolved",
                )
    }
}

// BAG and ARRAY both collapse to List<T>: the Kotlin stdlib has no multiset, and EXPRESS
// ARRAY index bounds aren't modeled in V1. This is a documented approximation, not silent.
private fun resolveAggregationTypeName(
    type: AggregationType,
    packageName: String,
    errorContext: String,
    definedTypes: Map<String, ExpressDefinedType>,
): ParameterizedTypeName {
    val element = resolveKotlinTypeName(type.elementType, packageName, errorContext, definedTypes)
    val collection =
        when (type.kind) {
            AggregationKind.SET -> SET
            AggregationKind.LIST, AggregationKind.ARRAY, AggregationKind.BAG -> LIST
        }
    return collection.parameterizedBy(element)
}
