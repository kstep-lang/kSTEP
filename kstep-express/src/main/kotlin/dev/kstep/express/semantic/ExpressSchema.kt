package dev.kstep.express.semantic

/**
 * An EXPRESS `FUNCTION` declaration, captured only by name and verbatim text. kSTEP has no general
 * EXPRESS function interpreter (ADR-0023): the text exists so codegen can refuse a schema that
 * declares its own variant of a function kSTEP implements as a fixed intrinsic.
 */
data class ExpressFunction(
    val name: String,
    val rawText: String,
    val sourceLine: Int,
)

/** An EXPRESS `schemaDecl` — one independent symbol-table namespace (no cross-schema resolution in V1). */
data class ExpressSchema(
    val name: String,
    val entities: List<ExpressEntity>,
    val definedTypes: List<ExpressDefinedType>,
    val sourceLine: Int,
    val functions: List<ExpressFunction> = emptyList(),
)
