package dev.kstep.express.semantic

/** An EXPRESS `schemaDecl` — one independent symbol-table namespace (no cross-schema resolution in V1). */
data class ExpressSchema(
    val name: String,
    val entities: List<ExpressEntity>,
    val definedTypeNames: Set<String>,
    val sourceLine: Int,
)
