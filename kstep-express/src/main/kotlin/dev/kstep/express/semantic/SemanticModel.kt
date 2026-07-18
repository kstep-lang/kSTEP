package dev.kstep.express.semantic

/** Result of walking an EXPRESS `syntax` unit (one or more `schemaDecl`s) into an AST. */
data class SemanticModel(
    val schemas: List<ExpressSchema>,
)
