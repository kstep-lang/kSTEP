package dev.kstep.sketch

import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.OcctKernel
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Builds a [Sketch] with a small constraint DSL:
 *
 * ```
 * val plate = sketch {
 *     val frame = rectangle(0.0, 0.0, 40.0, 20.0)           // corner fixed, H/V and two dimensions
 *     val centre = point(20.0, 10.0)                        // start value: where the solver begins
 *     offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
 *     circle(centre, radius = 3.0)
 * }.solve().extrude(10.0)
 * ```
 *
 * **Start values matter.** The solver converges to the solution nearest to the coordinates given
 * when a point is created, so a dimension's SIGN (which side of a point another lies on) comes from
 * those coordinates, not from the constraint: `distance` and `length` are unsigned magnitudes.
 * Every constraint must reference points created by THIS builder.
 *
 * **Radii** are fixed measures by default (`fixedRadius = true`). With `fixedRadius = false` a
 * radius becomes a solver unknown that [radius], [diameter], [equalRadius] and the tangent
 * constraints can determine; the start value then only seeds the solver. Tangency decided by the
 * solver depends on the start values (which side of a line, inside or outside of a circle), see
 * [tangent].
 */
class SketchBuilder internal constructor() {
    private val token = Any()
    private val points = ArrayList<SketchPointData>()
    private val lines = ArrayList<SketchLineData>()
    private val arcs = ArrayList<SketchArcData>()
    private val circles = ArrayList<SketchCircleData>()
    private val scalars = ArrayList<SketchScalarData>()
    private val constraints = ArrayList<SketchConstraintData>()

    /**
     * Adds a point with start coordinates ([x], [y]). A [fixed] point is held at those
     * coordinates; [name] only appears in diagnostics (up to 64 printable ASCII characters).
     */
    fun point(
        x: Double,
        y: Double,
        fixed: Boolean = false,
        name: String? = null,
    ): SketchPointRef = addPoint(x, y, fixed, name, construction = false)

    /** Adds a straight segment between two points; [construction] segments are ignored by the profile. */
    fun line(
        a: SketchPointRef,
        b: SketchPointRef,
        construction: Boolean = false,
    ): SketchLineRef {
        own(a)
        own(b)
        require(a.index != b.index) { fail(SketchErrorKind.INVALID_INPUT, "a line needs two distinct points") }
        limitSegments()
        lines.add(SketchLineData(a.index, b.index, construction))
        return SketchLineRef(token, lines.size - 1, a, b)
    }

    /**
     * Adds a circular arc around [center] from [start] to [end], going counter-clockwise when [ccw]
     * (else clockwise). Implies the two constraints `|center start| = radius` and
     * `|center end| = radius`. With `fixedRadius = false` the radius is a solver unknown that [radius],
     * [equalRadius] or [tangent] constraints determine; [radius] is then only its start value.
     */
    fun arc(
        center: SketchPointRef,
        start: SketchPointRef,
        end: SketchPointRef,
        radius: Double,
        ccw: Boolean = true,
        fixedRadius: Boolean = true,
    ): SketchArcRef {
        own(center)
        own(start)
        own(end)
        require(setOf(center.index, start.index, end.index).size == 3) {
            fail(SketchErrorKind.INVALID_INPUT, "an arc needs three distinct points (centre, start, end)")
        }
        requireRadius(radius)
        limitSegments()
        val scalar = addScalar(radius, fixedRadius, "radius of arc ${nameOf(start.index)}-${nameOf(end.index)}")
        arcs.add(SketchArcData(center.index, start.index, end.index, scalar, ccw))
        val suffix = if (fixedRadius) "=$radius" else ""
        addConstraint(
            PointOnCircleData(
                start.index,
                center.index,
                scalar,
                "arc radius ${nameOf(center.index)}-${nameOf(start.index)}$suffix",
            ),
        )
        addConstraint(
            PointOnCircleData(
                end.index,
                center.index,
                scalar,
                "arc radius ${nameOf(center.index)}-${nameOf(end.index)}$suffix",
            ),
        )
        return SketchArcRef(token, arcs.size - 1, center, scalar)
    }

    /**
     * Adds a full circle around [center]. The radius is a fixed measure unless `fixedRadius = false`,
     * in which case [radius] only seeds the solver and a [radius]/[diameter]/[equalRadius]/[tangent]
     * constraint must determine it. [name] only appears in diagnostics.
     */
    fun circle(
        center: SketchPointRef,
        radius: Double,
        fixedRadius: Boolean = true,
        name: String? = null,
    ): SketchCircleRef {
        own(center)
        requireRadius(radius)
        limitSegments()
        val label = name ?: "circle@${nameOf(center.index)}"
        require(label.length <= MAX_NAME_LENGTH && label.all { it.code in 0x20..0x7E }) {
            fail(
                SketchErrorKind.INVALID_INPUT,
                "a circle name must be at most $MAX_NAME_LENGTH printable ASCII characters",
            )
        }
        val scalar = addScalar(radius, fixedRadius, "radius of $label")
        circles.add(SketchCircleData(center.index, scalar, label))
        return SketchCircleRef(token, circles.size - 1, center, scalar)
    }

    /** Fixes the radius of a circle or arc to [value]; the shape must have been created with `fixedRadius = false`. */
    fun radius(
        shape: SketchRoundRef,
        value: Double,
    ) {
        ownRound(shape)
        requireRadius(value)
        addConstraint(RadiusData(shape.scalar, value, "radius(${roundName(shape)})=$value"))
    }

    /** Fixes the diameter of a circle or arc to [value] (stored as radius `value / 2`). */
    fun diameter(
        shape: SketchRoundRef,
        value: Double,
    ) {
        ownRound(shape)
        requireRadius(value / 2.0)
        addConstraint(RadiusData(shape.scalar, value / 2.0, "diameter(${roundName(shape)})=$value"))
    }

    /** Makes two circles/arcs equal in radius. */
    fun equalRadius(
        a: SketchRoundRef,
        b: SketchRoundRef,
    ) {
        ownRound(a)
        ownRound(b)
        require(a.scalar != b.scalar) { fail(SketchErrorKind.INVALID_INPUT, "equalRadius needs two different shapes") }
        addConstraint(EqualRadiusData(a.scalar, b.scalar, "equalRadius(${roundName(a)}, ${roundName(b)})"))
    }

    /**
     * Makes the INFINITE line through [line] tangent to a circle or arc (not just the segment). Which
     * side of the line the circle lies on, comes from the start values; the centre must not be an end
     * point of the line.
     */
    fun tangent(
        line: SketchLineRef,
        shape: SketchRoundRef,
    ) {
        own(line)
        ownRound(shape)
        own(line.from)
        own(line.to)
        require(shape.center.index != line.from.index && shape.center.index != line.to.index) {
            fail(SketchErrorKind.INVALID_INPUT, "tangent: the centre must not be an end point of the line")
        }
        addConstraint(
            TangentLineData(
                line.from.index,
                line.to.index,
                shape.center.index,
                shape.scalar,
                "tangent(${lineName(line)}, ${roundName(shape)})",
            ),
        )
    }

    /**
     * Makes two circles/arcs tangent: externally touching, or with [internal] one inside the other.
     * Both centres must start at different coordinates.
     *
     * With [internal] = true and two EQUAL radii (for example via [equalRadius] or two [radius] constraints)
     * the circles would have to be concentric, which makes the tangency gradient degenerate (0/0): the
     * solver then fails with a generic `SOLVER_FAILED`. Do not combine the two.
     */
    fun tangent(
        a: SketchRoundRef,
        b: SketchRoundRef,
        internal: Boolean = false,
    ) {
        ownRound(a)
        ownRound(b)
        require(a.scalar != b.scalar && a.center.index != b.center.index) {
            fail(SketchErrorKind.INVALID_INPUT, "tangent needs two different circles/arcs with different centres")
        }
        addConstraint(
            TangentCirclesData(
                a.center.index,
                a.scalar,
                b.center.index,
                b.scalar,
                internal,
                "tangent(${roundName(a)}, ${roundName(b)}${if (internal) ", internal" else ""})",
            ),
        )
    }

    /**
     * Fixes the angle from the direction of [l1] to the direction of [l2] to [degrees], counter-clockwise
     * positive, within (-180, 180]. The lines may share an end point.
     */
    fun angle(
        l1: SketchLineRef,
        l2: SketchLineRef,
        degrees: Double,
    ) {
        distinctLines(l1, l2)
        require(degrees.isFinite() && degrees > -180.0 && degrees <= 180.0) {
            fail(SketchErrorKind.INVALID_INPUT, "angle must be finite and within (-180, 180] degrees, got $degrees")
        }
        addConstraint(
            AngleData(
                l1.from.index,
                l1.to.index,
                l2.from.index,
                l2.to.index,
                Math.toRadians(degrees),
                "angle(${lineName(l1)}, ${lineName(l2)})=$degrees",
            ),
        )
    }

    /**
     * Adds a rectangle with its origin corner at ([x], [y]) -- four points, four lines, horizontal
     * and vertical constraints and the two dimensions [width] and [height]. With [fixCorner] the
     * origin corner is held, which makes the rectangle fully constrained.
     *
     * The corners are called `p<index>` by default, where the index counts ALL points of the sketch so far -- so
     * inserting a point before the rectangle shifts the names. Pass [names] (four names, counter-clockwise from the
     * origin corner) to give the corners stable names, for example to refer to them with [SketchCorner].
     */
    fun rectangle(
        x: Double,
        y: Double,
        width: Double,
        height: Double,
        fixCorner: Boolean = true,
        names: List<String>? = null,
    ): SketchRectangle {
        requireMeasure("width", width)
        requireMeasure("height", height)
        require(names == null || names.size == 4) {
            fail(SketchErrorKind.INVALID_INPUT, "rectangle names must be four names, got ${names?.size}")
        }
        val p0 = point(x, y, fixed = fixCorner, name = names?.get(0))
        val p1 = point(x + width, y, name = names?.get(1))
        val p2 = point(x + width, y + height, name = names?.get(2))
        val p3 = point(x, y + height, name = names?.get(3))
        val corners = listOf(p0, p1, p2, p3)
        val edges = corners.indices.map { line(corners[it], corners[(it + 1) % 4]) }
        horizontal(edges[0])
        vertical(edges[1])
        horizontal(edges[2])
        vertical(edges[3])
        length(edges[0], width)
        length(edges[1], height)
        return SketchRectangle(corners, edges)
    }

    /**
     * Places [to] at the offset ([dx], [dy]) from [from]: an unsigned horizontal and vertical
     * dimension through an auxiliary construction point. The side of each offset comes from the
     * start coordinates of [to] (and of the auxiliary point, which starts at `(to.x, from.y)`).
     * A zero offset in one direction needs no auxiliary point.
     */
    fun offset(
        from: SketchPointRef,
        to: SketchPointRef,
        dx: Double,
        dy: Double,
    ) {
        own(from)
        own(to)
        require(from.index != to.index) { fail(SketchErrorKind.INVALID_INPUT, "offset needs two distinct points") }
        require(
            dx.isFinite() && dy.isFinite(),
        ) { fail(SketchErrorKind.INVALID_INPUT, "offset must be finite, got ($dx, $dy)") }
        val ax = abs(dx)
        val ay = abs(dy)
        when {
            ax == 0.0 && ay == 0.0 -> coincident(from, to)
            ay == 0.0 -> {
                horizontal(from, to)
                distance(from, to, ax)
            }
            ax == 0.0 -> {
                vertical(from, to)
                distance(from, to, ay)
            }
            else -> {
                val toData = points[to.index]
                val fromData = points[from.index]
                val corner =
                    addPoint(toData.x, fromData.y, fixed = false, name = "${toData.name}.offset", construction = true)
                horizontal(from, corner)
                vertical(corner, to)
                distance(from, corner, ax)
                distance(corner, to, ay)
            }
        }
    }

    /** Holds [p] at its start coordinates. */
    fun fix(p: SketchPointRef) {
        own(p)
        points[p.index] = points[p.index].copy(fixed = true)
    }

    /** Keeps the two points at equal height. */
    fun horizontal(
        a: SketchPointRef,
        b: SketchPointRef,
    ) {
        distinctPair(a, b)
        addConstraint(HorizontalData(a.index, b.index, "horizontal(${nameOf(a.index)}, ${nameOf(b.index)})"))
    }

    /** Keeps the line's two end points at equal height. */
    fun horizontal(line: SketchLineRef) = horizontal(line.from, line.to)

    /** Keeps the two points at equal x. */
    fun vertical(
        a: SketchPointRef,
        b: SketchPointRef,
    ) {
        distinctPair(a, b)
        addConstraint(VerticalData(a.index, b.index, "vertical(${nameOf(a.index)}, ${nameOf(b.index)})"))
    }

    /** Keeps the line's two end points at equal x. */
    fun vertical(line: SketchLineRef) = vertical(line.from, line.to)

    /** Fixes the (unsigned) distance between two points to [value]. */
    fun distance(
        a: SketchPointRef,
        b: SketchPointRef,
        value: Double,
    ) {
        distinctPair(a, b)
        requireMeasure("distance", value)
        addConstraint(DistanceData(a.index, b.index, value, "distance(${nameOf(a.index)}, ${nameOf(b.index)})=$value"))
    }

    /** Fixes the length of a line to [value]. */
    fun length(
        line: SketchLineRef,
        value: Double,
    ) = distance(line.from, line.to, value)

    /** Makes two points coincide. */
    fun coincident(
        a: SketchPointRef,
        b: SketchPointRef,
    ) {
        distinctPair(a, b)
        addConstraint(CoincidentData(a.index, b.index, "coincident(${nameOf(a.index)}, ${nameOf(b.index)})"))
    }

    /** Puts [p] on the infinite line through [l]. */
    fun pointOnLine(
        p: SketchPointRef,
        l: SketchLineRef,
    ) {
        own(p)
        own(l.from)
        own(l.to)
        require(p.index != l.from.index && p.index != l.to.index) {
            fail(SketchErrorKind.INVALID_INPUT, "pointOnLine: the point must not be an end point of the line")
        }
        addConstraint(
            PointOnLineData(
                p.index,
                l.from.index,
                l.to.index,
                "pointOnLine(${nameOf(p.index)}, ${nameOf(l.from.index)}-${nameOf(l.to.index)})",
            ),
        )
    }

    /** Makes two lines parallel. */
    fun parallel(
        l1: SketchLineRef,
        l2: SketchLineRef,
    ) {
        distinctLines(l1, l2)
        addConstraint(
            ParallelData(
                l1.from.index,
                l1.to.index,
                l2.from.index,
                l2.to.index,
                "parallel(${lineName(l1)}, ${lineName(l2)})",
            ),
        )
    }

    /** Makes two lines perpendicular. */
    fun perpendicular(
        l1: SketchLineRef,
        l2: SketchLineRef,
    ) {
        distinctLines(l1, l2)
        addConstraint(
            PerpendicularData(
                l1.from.index,
                l1.to.index,
                l2.from.index,
                l2.to.index,
                "perpendicular(${lineName(l1)}, ${lineName(l2)})",
            ),
        )
    }

    internal fun build(): Sketch =
        Sketch(
            points.toList(),
            lines.toList(),
            arcs.toList(),
            circles.toList(),
            scalars.toList(),
            constraints.toList(),
            token,
        )

    private fun addPoint(
        x: Double,
        y: Double,
        fixed: Boolean,
        name: String?,
        construction: Boolean,
    ): SketchPointRef {
        if (points.size >= PlaneGcsSolver.MAX_POINTS) {
            throw SketchException(
                SketchErrorKind.LIMIT_EXCEEDED,
                "a sketch may have at most ${PlaneGcsSolver.MAX_POINTS} points (including centres and construction points)",
            )
        }
        require(x.isFinite() && y.isFinite()) {
            fail(SketchErrorKind.INVALID_INPUT, "point coordinates must be finite, got ($x, $y)")
        }
        require(abs(x) <= MAX_ABS_COORDINATE && abs(y) <= MAX_ABS_COORDINATE) {
            fail(SketchErrorKind.INVALID_INPUT, "point coordinates must be within +/-$MAX_ABS_COORDINATE, got ($x, $y)")
        }
        val label = name ?: "p${points.size}"
        require(label.length <= MAX_NAME_LENGTH && label.all { it.code in 0x20..0x7E }) {
            fail(
                SketchErrorKind.INVALID_INPUT,
                "a point name must be at most $MAX_NAME_LENGTH printable ASCII characters",
            )
        }
        points.add(SketchPointData(x, y, fixed, label, construction))
        return SketchPointRef(token, points.size - 1)
    }

    private fun addConstraint(c: SketchConstraintData) {
        if (constraints.size >= PlaneGcsSolver.MAX_CONSTRAINTS) {
            throw SketchException(
                SketchErrorKind.LIMIT_EXCEEDED,
                "a sketch may have at most ${PlaneGcsSolver.MAX_CONSTRAINTS} constraints (including those implied by arcs)",
            )
        }
        constraints.add(c)
    }

    private fun addScalar(
        initial: Double,
        fixed: Boolean,
        label: String,
    ): Int {
        if (scalars.size >= PlaneGcsSolver.MAX_SCALARS) {
            throw SketchException(
                SketchErrorKind.LIMIT_EXCEEDED,
                "a sketch may have at most ${PlaneGcsSolver.MAX_SCALARS} circles and arcs",
            )
        }
        scalars.add(SketchScalarData(initial, fixed, label))
        return scalars.size - 1
    }

    private fun ownRound(r: SketchRoundRef) {
        require(r.owner === token && r.scalar in scalars.indices) {
            fail(SketchErrorKind.INVALID_INPUT, "a circle or arc reference belongs to a different sketch")
        }
    }

    private fun roundName(r: SketchRoundRef) = scalars[r.scalar].label.removePrefix("radius of ")

    private fun limitSegments() {
        if (lines.size + arcs.size + circles.size >= MAX_SEGMENTS) {
            throw SketchException(SketchErrorKind.LIMIT_EXCEEDED, "a sketch may have at most $MAX_SEGMENTS segments")
        }
    }

    private fun own(p: SketchPointRef) {
        require(
            p.owner === token,
        ) { fail(SketchErrorKind.INVALID_INPUT, "a point reference belongs to a different sketch") }
    }

    private fun own(l: SketchLineRef) {
        require(
            l.owner === token,
        ) { fail(SketchErrorKind.INVALID_INPUT, "a line reference belongs to a different sketch") }
    }

    private fun distinctPair(
        a: SketchPointRef,
        b: SketchPointRef,
    ) {
        own(a)
        own(b)
        require(a.index != b.index) { fail(SketchErrorKind.INVALID_INPUT, "a constraint needs two distinct points") }
    }

    private fun distinctLines(
        l1: SketchLineRef,
        l2: SketchLineRef,
    ) {
        own(l1)
        own(l2)
        require(l1.index != l2.index) { fail(SketchErrorKind.INVALID_INPUT, "a constraint needs two distinct lines") }
        val a = points[l1.from.index]
        val b = points[l1.to.index]
        val c = points[l2.from.index]
        val d = points[l2.to.index]
        require(hypot(b.x - a.x, b.y - a.y) > 0.0 && hypot(d.x - c.x, d.y - c.y) > 0.0) {
            fail(SketchErrorKind.INVALID_INPUT, "both lines need distinct start coordinates for their end points")
        }
    }

    private fun requireRadius(radius: Double) {
        require(radius.isFinite() && radius >= OcctKernel.MIN_DIMENSION && radius <= OcctKernel.MAX_DIMENSION) {
            fail(
                SketchErrorKind.INVALID_INPUT,
                "radius must be finite and within [${OcctKernel.MIN_DIMENSION}, ${OcctKernel.MAX_DIMENSION}], got $radius",
            )
        }
    }

    private fun requireMeasure(
        what: String,
        value: Double,
    ) {
        require(value.isFinite() && value > 0.0 && value <= PlaneGcsSolver.MAX_DISTANCE) {
            fail(
                SketchErrorKind.INVALID_INPUT,
                "$what must be finite, positive and at most ${PlaneGcsSolver.MAX_DISTANCE}, got $value",
            )
        }
    }

    private fun nameOf(index: Int) = points[index].name

    private fun lineName(l: SketchLineRef) = "${nameOf(l.from.index)}-${nameOf(l.to.index)}"

    /** `require` messages become [SketchException]s: this throws it directly, the message is never used. */
    private fun fail(
        kind: SketchErrorKind,
        message: String,
    ): Nothing = throw SketchException(kind, message)

    internal companion object {
        const val MAX_ABS_COORDINATE: Double = 1e7
        const val MAX_NAME_LENGTH: Int = 64
        const val MAX_SEGMENTS: Int = 128
    }
}

/** Builds a [Sketch]; see [SketchBuilder]. */
fun sketch(block: SketchBuilder.() -> Unit): Sketch = SketchBuilder().apply(block).build()
