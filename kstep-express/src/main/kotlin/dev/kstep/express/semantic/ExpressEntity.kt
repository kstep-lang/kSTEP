package dev.kstep.express.semantic

/** An EXPRESS `entityDecl`, losslessly captured (including constructs V1 codegen refuses to emit). */
data class ExpressEntity(
    val name: String,
    val isAbstract: Boolean,
    val supertypes: List<String>,
    val supertypeConstraintRawText: String?,
    val attributes: List<ExpressAttribute>,
    val whereRules: List<ExpressWhereRule>,
    val sourceLine: Int,
)
