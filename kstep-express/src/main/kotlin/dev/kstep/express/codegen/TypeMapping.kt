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

// Only the single-level simple-alias case resolves (TYPE X = STRING/INTEGER/REAL/BOOLEAN/
// NUMBER/BINARY;, ExpressDefinedType.underlyingSimpleType != null); recursing into
// resolveKotlinTypeName on that underlying type (rather than duplicating the STRING/INTEGER/
// etc. mapping here) means a defined type aliasing LOGICAL/NUMBER/BINARY still throws the same
// CodeGenException a direct attribute of that type would. Anything requiring further
// indirection (another TYPE, an aggregation, SELECT, ENUMERATION) throws — transitive
// alias-chain and SELECT/ENUMERATION resolution are out of scope for V1, see ExpressDefinedType.
private fun resolveDefinedTypeRef(
    type: DefinedTypeRef,
    packageName: String,
    errorContext: String,
    definedTypes: Map<String, ExpressDefinedType>,
): TypeName {
    val definedType =
        definedTypes[type.typeName.lowercase()]
            ?: throw CodeGenException(
                "$errorContext: attribute references TYPE '${type.typeName}', which codegen has no " +
                    "resolution info for (no matching ExpressDefinedType was supplied for this schema)",
            )
    val underlying =
        definedType.underlyingSimpleType
            ?: throw CodeGenException(
                "$errorContext: TYPE '${type.typeName}' is not a simple STRING/INTEGER/REAL/BOOLEAN/NUMBER/" +
                    "BINARY alias (it aggregates, selects, enumerates, or itself references another TYPE); " +
                    "only single-level simple aliases are resolved by codegen",
            )
    return resolveKotlinTypeName(underlying, packageName, errorContext, definedTypes)
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
