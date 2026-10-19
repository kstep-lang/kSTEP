package dev.kstep.express.validation

/**
 * Base of the failures raised while building a [ReverseReferenceIndex] over an instance graph
 * (ADR-0022). Distinct from [WhereRuleEvaluationException] (a data problem while evaluating a
 * rule) and from [UnsupportedWhereExpressionException] (a construct outside the supported
 * subset): these are about the *population* itself being unusable — too large, too deep, or
 * containing an instance of a type the supplying adapter does not know.
 */
open class InstanceGraphException(
    message: String,
) : RuntimeException(message)

/** Shortens text that originates from instance data or user input before it enters an exception message. */
internal fun clipForMessage(
    text: String,
    max: Int = MAX_MESSAGE_FRAGMENT_LENGTH,
): String = if (text.length <= max) text else text.substring(0, max) + "..."

internal const val MAX_MESSAGE_FRAGMENT_LENGTH = 200
