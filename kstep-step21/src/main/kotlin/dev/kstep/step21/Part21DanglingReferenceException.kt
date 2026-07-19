package dev.kstep.step21

/** Thrown when a `#N` used in a reference argument position is never defined by any `#N=...;` statement in `DATA`. */
class Part21DanglingReferenceException(
    message: String,
) : RuntimeException(message)
