package dev.kstep.constraints

/**
 * Thrown by [PlaneGcsSolver] when the native PlaneGCS bridge itself reports an error condition that
 * is not simple caller-input validation (which instead throws [IllegalArgumentException] before any
 * native call is made) and not bridge unavailability (which throws [PlaneGcsUnavailableException]).
 * In practice this covers an unexpected native-side exception surfaced through JNI, or a native
 * solve status this module's [SolveStatus] enum does not recognize (see
 * [SolveStatus.Companion.fromNative]).
 */
class ConstraintSolverException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
