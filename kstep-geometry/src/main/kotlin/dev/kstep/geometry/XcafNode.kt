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

/** What a colour or layer assignment refers to inside a PART. */
enum class XcafStyleTarget { PART, SOLID, FACE }

/**
 * A colour on [target] of the PART node [nodeIndex]. [subIndex] is the 0-based position in OCCT's face / solid
 * enumeration of the part (the same order as `OcctShape.faces[i].index - 1` and the solid summaries); 0 for
 * the part itself.
 */
class XcafColorAssignment(
    val nodeIndex: Int,
    val target: XcafStyleTarget,
    val subIndex: Int,
    val color: RgbColor,
)

/** A layer name on [target] of the PART node [nodeIndex]; see [XcafColorAssignment] for [subIndex]. */
class XcafLayerAssignment(
    val nodeIndex: Int,
    val target: XcafStyleTarget,
    val subIndex: Int,
    val layer: String,
)

/**
 * Style counters of one read.
 *
 * @property unattached styles on edges, vertices or sub-shapes that could not be matched to a face or solid
 * @property instanceStylesIgnored colours / layers on assembly component instances (not reported yet)
 * @property truncated styles dropped because a native cap was reached
 */
data class XcafStyleStats(
    val unattached: Int,
    val instanceStylesIgnored: Int,
    val truncated: Int,
)

/** How the file declares its length unit. */
enum class LengthUnitState { DECLARED, MISSING, MIXED }

/**
 * The length unit of the file as declared (the geometry itself is always delivered in millimetres).
 *
 * @property names unit names found in the file (`INCH`, `millimetre`, ...)
 * @property factorToMm millimetres per file unit, `null` if the file declares no length unit
 */
data class StepLengthUnitInfo(
    val state: LengthUnitState,
    val names: List<String>,
    val factorToMm: Double?,
)
