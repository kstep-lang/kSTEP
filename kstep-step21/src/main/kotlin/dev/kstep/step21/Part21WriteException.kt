package dev.kstep.step21

/**
 * Thrown when a write-time operation cannot produce a well-formed [Part21Document]: an object
 * reachable from [Part21Writer.write]'s `roots` is not one of the six supported `kstep-core`
 * types, [Part21Document.concat] finds a colliding instance id, or [Part21Document.renumbered]'s
 * id-shift arithmetic would overflow a 32-bit Int.
 */
class Part21WriteException(
    message: String,
) : RuntimeException(message)
