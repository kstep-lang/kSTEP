package dev.kstep.express.semantic

/**
 * A `WHERE` clause `domainRule`, captured as raw, unparsed expression text. Evaluating
 * this text is not this class's job — see `dev.kstep.express.validation.WhereRuleValidator`,
 * which re-parses `expressionText` via `ExpressParserFactory.parseExpression` and evaluates
 * it against actual instance attribute values.
 */
data class ExpressWhereRule(
    val label: String?,
    val expressionText: String,
    val sourceLine: Int,
)
