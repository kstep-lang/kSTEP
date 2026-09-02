package dev.kstep.constraints

/**
 * Thrown by [PlaneGcsSolver] when the native PlaneGCS bridge is not available on this JVM -- see
 * [PlaneGcsAvailability.Unavailable] for why (missing Eigen/Boost headers at build time, unsupported
 * platform, or a runtime link failure); the exception's [message] carries that same reason.
 */
class PlaneGcsUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
