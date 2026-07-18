package dev.kstep.express.semantic

/**
 * A `WHERE` clause `domainRule`, captured as raw, unparsed expression text. Evaluating
 * WHERE-rule expressions is explicitly deferred to a later wave — this just records that
 * a rule exists, its optional label, and its verbatim source text.
 */
data class ExpressWhereRule(
    val label: String?,
    val expressionText: String,
    val sourceLine: Int,
)
