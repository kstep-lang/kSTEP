package dev.kstep.sketch

/** A point of a sketch under construction; only valid for the builder that created it. */
class SketchPointRef internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

/** A line segment of a sketch under construction. */
class SketchLineRef internal constructor(
    internal val owner: Any,
    internal val index: Int,
    internal val from: SketchPointRef,
    internal val to: SketchPointRef,
)

/** A circular arc of a sketch under construction. */
class SketchArcRef internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

/** A full circle of a sketch under construction. */
class SketchCircleRef internal constructor(
    internal val owner: Any,
    internal val index: Int,
)

/** The four corners and edges of a rectangle made by [SketchBuilder.rectangle], counter-clockwise from the origin corner. */
class SketchRectangle internal constructor(
    val corners: List<SketchPointRef>,
    val edges: List<SketchLineRef>,
) {
    val bottomLeft: SketchPointRef get() = corners[0]
    val bottomRight: SketchPointRef get() = corners[1]
    val topRight: SketchPointRef get() = corners[2]
    val topLeft: SketchPointRef get() = corners[3]
}
