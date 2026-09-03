package dev.kstep.constraints.planegcs

/**
 * The tagged-union "kind" values [dev.kstep.constraints.PlaneGcsSolver] encodes each
 * [dev.kstep.constraints.SketchConstraint] into before crossing the JNI boundary (see
 * [PlaneGcsBridge.nativeSolveConstraints]'s KDoc for the full wire-format table). [nativeValue]
 * MUST match `kstep_planegcs_bridge.cpp`'s own `switch` over this same integer exactly -- this enum
 * and that `switch` are two independent, hand-synchronized copies of the same contract; a mismatch
 * here silently routes a constraint to the wrong PlaneGCS constructor natively instead of failing
 * to compile, which is why every entry below is cross-referenced against `docs/adr/ADR-0007-
 * planegcs-additional-constraint-types.adoc`'s wire-format table in code review.
 *
 * Internal: this is an implementation detail of the Kotlin<->native encoding, never part of the
 * public [dev.kstep.constraints.PlaneGcsSolver] API surface.
 *
 * @property nativeValue the raw `int` sent to native code as one entry of `constraintKinds`.
 * @property arity how many of the four `constraintPoints` slots this kind actually uses; the
 *   remaining `4 - arity` slots must be encoded as exactly `-1` (see [POINT_SLOTS]).
 */
internal enum class NativeConstraintKind(
    val nativeValue: Int,
    val arity: Int,
) {
    P2P_DISTANCE(nativeValue = 0, arity = 2),
    P2P_COINCIDENT(nativeValue = 1, arity = 2),
    HORIZONTAL(nativeValue = 2, arity = 2),
    VERTICAL(nativeValue = 3, arity = 2),
    POINT_ON_LINE(nativeValue = 4, arity = 3),
    PARALLEL(nativeValue = 5, arity = 4),
    PERPENDICULAR(nativeValue = 6, arity = 4),
    ;

    internal companion object {
        /**
         * Fixed number of point-index slots reserved per constraint in `constraintPoints`,
         * regardless of that constraint's actual [arity] -- see
         * [PlaneGcsBridge.nativeSolveConstraints]'s KDoc for why 4 was chosen: it was sized ahead
         * of need, back when the maximum arity in use was 3 (`POINT_ON_LINE`), specifically to
         * cover [PARALLEL]/[PERPENDICULAR] without a second wire-format change -- see
         * `docs/adr/ADR-0014-planegcs-parallel-and-perpendicular.adoc`, which is the first ADR to
         * actually spend that headroom (both new kinds fill all four slots).
         */
        const val POINT_SLOTS: Int = 4

        /** Sentinel written into an unused `constraintPoints` slot; see [POINT_SLOTS]. */
        const val UNUSED_POINT_SLOT: Int = -1

        /** Sentinel written into `constraintParams` for a kind that takes no scalar parameter. */
        const val UNUSED_PARAM: Double = 0.0
    }
}
