package dev.kstep.express.semantic

/**
 * An EXPRESS `entityDecl`, losslessly captured (including constructs V1 codegen refuses to
 * emit -- `DERIVE`, `INVERSE`, `UNIQUE`, and redeclared attributes are all captured here even
 * though `dev.kstep.express.codegen.ExpressKotlinCodeGenerator` never reads them).
 */
data class ExpressEntity(
    val name: String,
    val isAbstract: Boolean,
    val supertypes: List<String>,
    val supertypeConstraintRawText: String?,
    val attributes: List<ExpressAttribute>,
    val derivedAttributes: List<ExpressDerivedAttribute>,
    val inverseAttributes: List<ExpressInverseAttribute>,
    val uniqueRules: List<ExpressUniqueRule>,
    val whereRules: List<ExpressWhereRule>,
    val sourceLine: Int,
)
