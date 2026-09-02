package dev.kstep.constraints.planegcs

/**
 * Raw JNI declarations for the `kstep_planegcs_bridge` native library (see
 * `src/main/cpp/kstep_planegcs_bridge.cpp`).
 *
 * A plain Kotlin `object`, not `@JvmStatic` and not a `companion object` -- the Kotlin compiler
 * emits this as JVM *instance* methods on the object's singleton instance, so every native symbol
 * below carries a `jobject` second parameter
 * (`Java_dev_kstep_constraints_planegcs_PlaneGcsBridge_<name>(JNIEnv*, jobject, ...)`), NOT
 * `..._PlaneGcsBridge_00024Companion_...`, which a `companion object` would produce instead. Verify
 * with `nm -D --defined-only <library>.so` after any signature change here.
 *
 * Public (not `internal`) so that [dev.kstep.tests.PlaneGcsBridgeSmokeTest] can exercise the raw
 * bridge directly with hostile input (null arrays, mismatched lengths, out-of-range indices) that
 * [dev.kstep.constraints.PlaneGcsSolver]'s own public API -- which validates everything before
 * calling here -- can never construct on its own. This is still an implementation-detail package by
 * convention, not a stable public API: nothing outside `dev.kstep.constraints` itself (and its own
 * test suite) should depend on it.
 */
object PlaneGcsBridge {
    external fun nativePlaneGcsSourceCommit(): String

    /**
     * Solves a 2D point system for a set of point-to-point distance constraints. See
     * `kstep_planegcs_bridge.cpp`'s own doc comment for the exact validation this performs
     * natively, independent of any Kotlin-side validation.
     *
     * @param coords `[x0, y0, x1, y1, ...]`, length `2 * pointCount`.
     * @param fixedFlags one entry per point (length `pointCount`): `1` if fixed, `0` if free.
     * @param constraintA point index per constraint (length `constraintCount`).
     * @param constraintB point index per constraint (length `constraintCount`).
     * @param constraintDist target distance per constraint (length `constraintCount`).
     * @param outCoords output buffer, length `2 * pointCount`, overwritten with the solved (or
     *   last-attempted, on failure) coordinates.
     * @return the native `GCS::SolveStatus` ordinal (0 Success, 1 Converged, 2 Failed, 3
     *   SuccessfulSolutionInvalid).
     */
    external fun nativeSolveP2PDistances(
        coords: DoubleArray,
        fixedFlags: IntArray,
        constraintA: IntArray,
        constraintB: IntArray,
        constraintDist: DoubleArray,
        maxIterations: Int,
        convergence: Double,
        outCoords: DoubleArray,
    ): Int
}
