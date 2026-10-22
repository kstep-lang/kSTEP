package dev.kstep.sketch

/** The failure families a sketch can report; each maps to a structured `KSTEP-S-010` message in scripts. */
enum class SketchErrorKind {
    /** A documented size limit (points, constraints, segments) was exceeded. */
    LIMIT_EXCEEDED,

    /** An argument is invalid: non-finite, out of range, a foreign reference, a bad name. */
    INVALID_INPUT,

    /** The sketch still has free degrees of freedom and full constraint was required. */
    UNDER_CONSTRAINED,

    /** Constraints are redundant or contradict each other. */
    OVER_CONSTRAINED,

    /** The solver did not converge, or its result violates a constraint. */
    SOLVER_FAILED,

    /** The profile geometry does not form closed loops. */
    NOT_CLOSED,

    /** The closed loops do not form a valid region (e.g. a hole outside the outer contour). */
    INVALID_PROFILE,
}

/**
 * A structured sketch failure. [details] lists the individual findings (constraint labels, point
 * names); [degreesOfFreedom] is set when the analysis got that far.
 */
class SketchException(
    val kind: SketchErrorKind,
    message: String,
    val details: List<String> = emptyList(),
    val degreesOfFreedom: Int? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Sketch solving needs the native PlaneGCS bridge, which is not available in this build (for
 * example the release zip ships without native bridges).
 */
class SketchSolverUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
