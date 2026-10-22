package dev.kstep.geometry

/**
 * Kind of boolean operation for [OcctKernel.boolean]. [nativeCode] mirrors `nativeBooleanOp`'s
 * `op` argument in `kstep_occt_bridge.cpp` (hand-synchronized pair; pinned by a contract test).
 */
enum class BooleanOperation(
    internal val nativeCode: Int,
) {
    /** `left + right` (`BRepAlgoAPI_Fuse`). */
    UNION(0),

    /** `left - right` (`BRepAlgoAPI_Cut`). */
    CUT(1),

    /** `left & right` (`BRepAlgoAPI_Common`). */
    COMMON(2),
}
