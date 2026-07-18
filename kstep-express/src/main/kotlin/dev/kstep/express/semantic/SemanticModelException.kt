package dev.kstep.express.semantic

/** Thrown for a genuinely unresolvable EXPRESS construct (e.g. a named type that resolves to nothing). */
class SemanticModelException(
    message: String,
) : RuntimeException(message)
