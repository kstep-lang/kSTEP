package dev.kstep.step21

/**
 * Thrown for structurally malformed Part-21 text: a missing semicolon, a malformed `#N=`, a
 * mismatched paren/quote, a missing/misordered section keyword, an unknown entity name, wrong
 * argument arity, wrong argument kind (`STRING` vs. `#N`-reference shape), or a reference
 * whose target instance is the wrong entity type for the position it fills.
 */
class Part21SyntaxException(
    message: String,
) : RuntimeException(message)
