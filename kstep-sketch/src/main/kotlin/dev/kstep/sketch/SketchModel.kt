package dev.kstep.sketch

/** Internal, immutable description of a sketch point. */
internal data class SketchPointData(
    val x: Double,
    val y: Double,
    val fixed: Boolean,
    val name: String,
    val construction: Boolean,
)

internal data class SketchLineData(
    val from: Int,
    val to: Int,
    val construction: Boolean,
)

/** A scalar unknown (a circle/arc radius): start value, whether it is held, and a diagnostic name. */
internal data class SketchScalarData(
    val initial: Double,
    val fixed: Boolean,
    val label: String,
)

internal data class SketchArcData(
    val center: Int,
    val start: Int,
    val end: Int,
    val scalar: Int,
    val ccw: Boolean,
)

internal data class SketchCircleData(
    val center: Int,
    val scalar: Int,
    val name: String,
)

/** One constraint on point indices; [label] names it in diagnostics. */
internal sealed interface SketchConstraintData {
    val label: String
}

internal data class CoincidentData(
    val a: Int,
    val b: Int,
    override val label: String,
) : SketchConstraintData

internal data class DistanceData(
    val a: Int,
    val b: Int,
    val distance: Double,
    override val label: String,
) : SketchConstraintData

internal data class HorizontalData(
    val a: Int,
    val b: Int,
    override val label: String,
) : SketchConstraintData

internal data class VerticalData(
    val a: Int,
    val b: Int,
    override val label: String,
) : SketchConstraintData

internal data class PointOnLineData(
    val p: Int,
    val a: Int,
    val b: Int,
    override val label: String,
) : SketchConstraintData

internal data class ParallelData(
    val a1: Int,
    val b1: Int,
    val a2: Int,
    val b2: Int,
    override val label: String,
) : SketchConstraintData

internal data class PerpendicularData(
    val a1: Int,
    val b1: Int,
    val a2: Int,
    val b2: Int,
    override val label: String,
) : SketchConstraintData

internal data class RadiusData(
    val scalar: Int,
    val value: Double,
    override val label: String,
) : SketchConstraintData

internal data class EqualRadiusData(
    val a: Int,
    val b: Int,
    override val label: String,
) : SketchConstraintData

internal data class PointOnCircleData(
    val p: Int,
    val center: Int,
    val scalar: Int,
    override val label: String,
) : SketchConstraintData

internal data class TangentLineData(
    val from: Int,
    val to: Int,
    val center: Int,
    val scalar: Int,
    override val label: String,
) : SketchConstraintData

internal data class TangentCirclesData(
    val c1: Int,
    val s1: Int,
    val c2: Int,
    val s2: Int,
    val internal: Boolean,
    override val label: String,
) : SketchConstraintData

internal data class AngleData(
    val a1: Int,
    val b1: Int,
    val a2: Int,
    val b2: Int,
    val radians: Double,
    override val label: String,
) : SketchConstraintData
