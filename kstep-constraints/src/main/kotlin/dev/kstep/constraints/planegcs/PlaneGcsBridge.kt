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
     * Solves a 2D point system for a mixed set of PlaneGCS constraints. See
     * `kstep_planegcs_bridge.cpp`'s own doc comment for the exact validation this performs
     * natively, independent of any Kotlin-side validation.
     *
     * Wire format for the tagged-union constraint arrays (all four `constraintXxx` arrays share
     * one index `i` per constraint, `0 until constraintKinds.size`):
     *
     * | kind | meaning                    | arity | `constraintPoints[4*i .. 4*i+3]`        | `constraintParams[i]` |
     * |------|-----------------------------|-------|------------------------------------------|------------------------|
     * | 0    | point-to-point distance     | 2     | `[a, b, -1, -1]`                          | target distance (`> 0`, finite) |
     * | 1    | point-to-point coincidence  | 2     | `[a, b, -1, -1]`                          | `0.0` (unused)         |
     * | 2    | horizontal (equal Y)        | 2     | `[a, b, -1, -1]`                          | `0.0` (unused)         |
     * | 3    | vertical (equal X)          | 2     | `[a, b, -1, -1]`                          | `0.0` (unused)         |
     * | 4    | point-on-(infinite)line     | 3     | `[point, lineFrom, lineTo, -1]`           | `0.0` (unused)         |
     * | 5    | parallel (leg A par. leg B) | 4     | `[aFrom, aTo, bFrom, bTo]`                | `0.0` (unused)         |
     * | 6    | perpendicular (leg A perp. leg B) | 4 | `[aFrom, aTo, bFrom, bTo]`             | `0.0` (unused)         |
     *
     * Kinds 5 and 6 are the first to use all four `constraintPoints` slots -- see
     * [dev.kstep.constraints.planegcs.NativeConstraintKind.POINT_SLOTS] for why 4 was chosen ahead
     * of that need. Unlike kinds 0-4, the distinctness check kinds 5/6 apply over their four slots
     * is NOT full pairwise distinctness: each leg (`[aFrom, aTo]` and `[bFrom, bTo]`) must
     * individually be non-degenerate, but the two legs are explicitly allowed to share an endpoint
     * (the common rectangle-corner/chamfer shape) -- see
     * `docs/adr/ADR-0014-planegcs-parallel-and-perpendicular.adoc` for the full rule and why a
     * naive full-pairwise rule would reject that shape.
     *
     * Every slot beyond a kind's arity MUST be exactly `-1`, and every unused parameter MUST be
     * exactly `0.0` -- both checked natively and rejected with `IllegalArgumentException` rather
     * than silently ignored, so a slot the encoder forgot to fill in fails loudly instead of
     * quietly carrying stale or attacker-controlled data across the JNI boundary. See
     * [dev.kstep.constraints.planegcs.NativeConstraintKind] for the Kotlin-side mirror of this
     * table (kept hand-synchronized with `kstep_planegcs_bridge.cpp`'s own `switch`, not derived
     * from it -- there is exactly one authoritative copy of "kind N means X", and it is this KDoc
     * table plus `docs/adr/ADR-0007-planegcs-additional-constraint-types.adoc`'s copy of it).
     *
     * @param coords `[x0, y0, x1, y1, ...]`, length `2 * pointCount`.
     * @param fixedFlags one entry per point (length `pointCount`): `1` if fixed, `0` if free.
     * @param constraintKinds one of the kind values in the table above, length `constraintCount`.
     * @param constraintPoints point indices per constraint, length `4 * constraintCount` -- see the
     *   table above for the per-kind layout and the unused-slot sentinel.
     * @param constraintParams scalar parameter per constraint (only meaningful for kind 0), length
     *   `constraintCount` -- see the table above for the unused-parameter sentinel.
     * @param outCoords output buffer, length `2 * pointCount`, overwritten with the solved (or
     *   last-attempted, on failure) coordinates.
     * @return the native `GCS::SolveStatus` ordinal (0 Success, 1 Converged, 2 Failed, 3
     *   SuccessfulSolutionInvalid), or a negative value if native-side validation rejected the call
     *   before any solve was attempted (see `kstep_planegcs_bridge.cpp`).
     */
    external fun nativeSolveConstraints(
        coords: DoubleArray,
        fixedFlags: IntArray,
        constraintKinds: IntArray,
        constraintPoints: IntArray,
        constraintParams: DoubleArray,
        maxIterations: Int,
        convergence: Double,
        outCoords: DoubleArray,
    ): Int
}
