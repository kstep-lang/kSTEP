package dev.kstep.express.validation

/** Thrown when an [InstanceDescriber] does not recognize an instance reachable from the roots. */
class UnknownInstanceTypeException(
    message: String,
) : InstanceGraphException(message)
