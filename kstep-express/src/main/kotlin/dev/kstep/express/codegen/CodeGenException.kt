package dev.kstep.express.codegen

/**
 * Thrown when the semantic model contains a construct the codegen doesn't yet turn into
 * Kotlin (e.g. `SUBTYPE OF`, `LOGICAL`-typed attributes). The semantic model layer is
 * permissive and captures everything losslessly; codegen is strict and refuses loudly
 * instead of emitting silently-wrong or partial classes.
 */
class CodeGenException(
    message: String,
) : RuntimeException(message)
