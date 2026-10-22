package dev.kstep.express.validation

/** Thrown when a [ReverseReferenceIndex.Limits] guard trips; the message names the failing chain or limit. */
class ReverseReferenceIndexLimitExceededException(
    message: String,
) : InstanceGraphException(message)
