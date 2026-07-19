package dev.kstep.express.validation

/**
 * Thrown when a WHERE-rule `domainRule` expression uses a construct outside the supported
 * subset (comparisons, `SELF.attribute`, `AND`/`OR`/`NOT`, string/integer/real literals) —
 * e.g. `EXISTS()`, `SIZEOF()`, other function calls, aggregate/set operations, `QUERY`
 * expressions, arithmetic operators, or the tri-state `LOGICAL` type. Mirrors
 * `dev.kstep.express.codegen.CodeGenException`'s "throw instead of silently doing the wrong
 * thing" philosophy: this evaluator is a small interpreter for the actually-occurring
 * subset, not a general EXPRESS expression evaluator.
 */
class UnsupportedWhereExpressionException(
    message: String,
) : RuntimeException(message)
