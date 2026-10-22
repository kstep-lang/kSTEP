package dev.kstep.geometry

/** Kind of a node in the XCAF product structure read from a STEP file. */
enum class XcafNodeKind { PART, ASSEMBLY, COMPONENT }

/**
 * One node of the XCAF structure. A [XcafNodeKind.COMPONENT] is a usage of [referredIndex] (a PART
 * or ASSEMBLY node) inside the assembly node [parentIndex], placed by [localMatrix].
 *
 * @property localMatrix row-major 3x4 `[r00 r01 r02 tx, r10 r11 r12 ty, r20 r21 r22 tz]`; identity
 *   for non-components. Compare with `contentEquals`.
 * @property hasShape true for a PART whose B-Rep can be fetched with [OcctStepDocument.partShape].
 * @property rigid false for a component whose transform carries scaling or a reflection.
 */
class XcafNode(
    val index: Int,
    val kind: XcafNodeKind,
    val name: String,
    val parentIndex: Int?,
    val referredIndex: Int?,
    val freeRoot: Boolean,
    val rigid: Boolean,
    val localMatrix: DoubleArray,
    val hasShape: Boolean,
)

/** Counters of one STEP read. */
data class StepReadStats(
    val entityCount: Int,
    val unknownEntityCount: Int,
    val unknownEntityTypes: List<String>,
    val freeRootCount: Int,
)
