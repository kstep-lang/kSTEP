package dev.kstep.step21

/**
 * Thrown when a string value — either a [Part21Header]/entity attribute the [Part21Writer] is
 * asked to serialize, or a string literal the [Part21Reader] re-parses — contains a
 * non-printable-ASCII or non-ASCII character. V1 deliberately does not implement ISO
 * 10303-21's `\X\`/`\X2\`/`\X4\` non-ASCII escape mechanism (see README), so such content is
 * rejected structurally rather than silently mis-encoded or truncated.
 */
class Part21EncodingException(
    message: String,
) : RuntimeException(message)
