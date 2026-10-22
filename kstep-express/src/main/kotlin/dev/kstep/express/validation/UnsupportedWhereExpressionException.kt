package dev.kstep.express.validation

/**
 * Thrown when a WHERE-rule `domainRule` expression uses a construct outside the supported
 * subset (comparisons, `SELF.attribute`, `AND`/`OR`/`NOT`, string/integer/real literals,
 * `EXISTS(<attribute>)`, and — behind an opt-in — `SIZEOF(USEDIN(SELF, '<role>'))`) —
 * e.g. other function calls, `SIZEOF` of anything but `USEDIN`, aggregate/set operations, `QUERY`
 * expressions, arithmetic operators, or the tri-state `LOGICAL` type. Mirrors
 * `dev.kstep.express.codegen.CodeGenException`'s "throw instead of silently doing the wrong
 * thing" philosophy: this evaluator is a small interpreter for the actually-occurring
 * subset, not a general EXPRESS expression evaluator.
 */
class UnsupportedWhereExpressionException(
    message: String,
) : RuntimeException(message)
