package dev.kstep.express.validation

/**
 * Thrown for a genuine evaluation-time problem with an already-built [WhereRuleExpression] —
 * a `SELF.attribute` reference missing from the supplied value bag, a top-level result that
 * isn't boolean, or a comparison between incompatible value kinds. Distinct from
 * [UnsupportedWhereExpressionException]: that one is thrown while *building* the AST for a
 * construct the evaluator was never taught to model; this one is thrown while *evaluating*
 * an AST it understood perfectly well, against data that doesn't satisfy its preconditions.
 */
class WhereRuleEvaluationException(
    message: String,
) : RuntimeException(message)
