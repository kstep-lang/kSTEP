package dev.kstep.express.semantic

/**
 * An EXPRESS `entityDecl`, losslessly captured (including constructs V1 codegen refuses to
 * emit -- `DERIVE`, `INVERSE`, `UNIQUE`, and redeclared attributes are all captured here;
 * `dev.kstep.express.codegen.ExpressKotlinCodeGenerator` translates the supported subset of
 * `DERIVE` to computed properties (ADR-0021) and does not read the other clauses).
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
