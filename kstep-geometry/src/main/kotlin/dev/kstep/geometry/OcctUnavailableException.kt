package dev.kstep.geometry

/**
 * Thrown by [OcctKernel] when the native OCCT bridge is not available on this JVM -- see
 * [OcctAvailability.Unavailable] for why (missing OCCT dev headers at build time, unsupported
 * platform, or a runtime link failure); the exception's [message] carries that same reason.
 */
class OcctUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
