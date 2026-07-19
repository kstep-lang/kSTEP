package dev.kstep.step21

/** Thrown when an object reachable from [Part21Writer.write]'s `roots` is not one of the six supported `kstep-core` types. */
class Part21WriteException(
    message: String,
) : RuntimeException(message)
