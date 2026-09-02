package dev.kstep.constraints

/**
 * 1:1 mapping of PlaneGCS's `GCS::SolveStatus` enum (`third_party/planegcs/GCS.h:51-58`, verified
 * against the vendored source -- see `docs/adr/ADR-0006-planegcs-constraint-bridge.adoc`).
 *
 * The ordinal values match the native `GCS::SolveStatus` values exactly (`SUCCESS` = 0, `CONVERGED`
 * = 1, `FAILED` = 2, `SUCCESSFUL_SOLUTION_INVALID` = 3) --
 * [dev.kstep.constraints.planegcs.PlaneGcsBridge]'s native side returns the raw native integer, and
 * [PlaneGcsSolver.solveDistances] converts it back via [entries] `ordinal` lookup. Do not reorder
 * these entries.
 */
enum class SolveStatus {
    /**
     * The solver found a solution that zeroes the error function exactly (within
     * [PlaneGcsSolver.DEFAULT_CONVERGENCE]).
     */
    SUCCESS,

    /** The solver found a solution that minimizes the error function, but did not reach zero. */
    CONVERGED,

    /** The solver failed to find any solution -- e.g. conflicting or unsatisfiable constraints. */
    FAILED,

    /**
     * The solver reported success, but PlaneGCS itself flags the resulting geometry as invalid.
     * Not exercised by this wave's own constraint vocabulary (plain point-to-point distances have
     * no such invalid-geometry failure mode PlaneGCS is known to report) -- kept only because it is
     * part of the native enum this type mirrors 1:1.
     */
    SUCCESSFUL_SOLUTION_INVALID,
    ;

    internal companion object {
        /**
         * Converts a raw native `GCS::SolveStatus` ordinal (as returned by
         * [dev.kstep.constraints.planegcs.PlaneGcsBridge.nativeSolveP2PDistances]) into a
         * [SolveStatus].
         *
         * @throws ConstraintSolverException if `nativeStatus` is not one of the four known values --
         *   this can only happen if the vendored PlaneGCS source and this enum have drifted apart
         *   (e.g. a PlaneGCS upgrade added a new status), never from ordinary caller input.
         */
        fun fromNative(nativeStatus: Int): SolveStatus =
            entries.getOrNull(nativeStatus)
                ?: throw ConstraintSolverException(
                    "Unknown native PlaneGCS solve status: $nativeStatus (expected 0..${entries.size - 1})",
                )
    }
}
