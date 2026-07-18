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
import dev.kstep.express.semantic.ExpressType
import dev.kstep.express.semantic.IntegerType
import dev.kstep.express.semantic.LogicalType
import dev.kstep.express.semantic.NumberType
import dev.kstep.express.semantic.RealType
import dev.kstep.express.semantic.StringType
import dev.kstep.express.semantic.UnsupportedType

/** Maps a resolved [ExpressType] to a KotlinPoet [TypeName]. Throws [CodeGenException] for anything not yet supported. */
internal fun resolveKotlinTypeName(
    type: ExpressType,
    packageName: String,
    errorContext: String,
): TypeName =
    when (type) {
        is StringType -> STRING
        is IntegerType -> INT
        is RealType -> DOUBLE
        is BooleanType -> BOOLEAN
        is EntityTypeRef -> ClassName(packageName, NamingConventions.toClassName(type.entityName))
        is AggregationType -> resolveAggregationTypeName(type, packageName, errorContext)
        is LogicalType -> throw CodeGenException(
            "$errorContext: EXPRESS LOGICAL (tri-state) has no V1 Kotlin mapping yet",
        )
        is NumberType -> throw CodeGenException("$errorContext: EXPRESS NUMBER has no V1 Kotlin mapping yet")
        is BinaryType -> throw CodeGenException("$errorContext: EXPRESS BINARY has no V1 Kotlin mapping yet")
        is DefinedTypeRef ->
            throw CodeGenException(
                "$errorContext: attribute references TYPE '${type.typeName}', not yet supported by codegen",
            )
        is UnsupportedType -> throw CodeGenException("$errorContext: ${type.reasonHint}")
    }

// BAG and ARRAY both collapse to List<T>: the Kotlin stdlib has no multiset, and EXPRESS
// ARRAY index bounds aren't modeled in V1. This is a documented approximation, not silent.
private fun resolveAggregationTypeName(
    type: AggregationType,
    packageName: String,
    errorContext: String,
): ParameterizedTypeName {
    val element = resolveKotlinTypeName(type.elementType, packageName, errorContext)
    val collection =
        when (type.kind) {
            AggregationKind.SET -> SET
            AggregationKind.LIST, AggregationKind.ARRAY, AggregationKind.BAG -> LIST
        }
    return collection.parameterizedBy(element)
}
