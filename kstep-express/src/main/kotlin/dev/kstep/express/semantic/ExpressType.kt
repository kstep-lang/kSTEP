package dev.kstep.express.semantic

/** An EXPRESS attribute type, per ISO 10303-11 `parameterType`. */
sealed interface ExpressType

/**
 * `widthText`/`precisionText` are raw source substrings, not `Int?` — EXPRESS
 * `widthSpec`/`precisionSpec` bottom out at `numericExpression`, which can be an
 * arbitrary expression (e.g. `STRING(some_constant)`), not necessarily an integer
 * literal. Storing the raw text avoids a crash on legal-but-non-literal input.
 */
data class StringType(
    val widthText: String?,
    val fixed: Boolean,
) : ExpressType

data object IntegerType : ExpressType

data class RealType(
    val precisionText: String?,
) : ExpressType

data object BooleanType : ExpressType

data object LogicalType : ExpressType

data object NumberType : ExpressType

data class BinaryType(
    val widthText: String?,
    val fixed: Boolean,
) : ExpressType

data class EntityTypeRef(
    val entityName: String,
) : ExpressType

data class DefinedTypeRef(
    val typeName: String,
) : ExpressType

enum class AggregationKind { SET, LIST, BAG, ARRAY }

data class AggregationType(
    val kind: AggregationKind,
    val elementType: ExpressType,
    val boundsRawText: String?,
    val unique: Boolean,
) : ExpressType

/** `AGGREGATE OF ...`, `GENERIC`, `GENERIC_ENTITY` — syntactically valid, out of scope for V1 codegen. */
data class UnsupportedType(
    val rawText: String,
    val reasonHint: String,
) : ExpressType
