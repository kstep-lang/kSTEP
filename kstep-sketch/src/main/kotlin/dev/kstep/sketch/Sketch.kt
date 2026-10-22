package dev.kstep.sketch

import dev.kstep.constraints.CoincidenceConstraint
import dev.kstep.constraints.ConstraintSolverException
import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.HorizontalConstraint
import dev.kstep.constraints.ParallelConstraint
import dev.kstep.constraints.PerpendicularConstraint
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PlaneGcsUnavailableException
import dev.kstep.constraints.PointOnLineConstraint
import dev.kstep.constraints.SketchConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.VerticalConstraint
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.approximateSignedArea
import dev.kstep.geometry.feature.ExtrudeRegionFeature
import dev.kstep.geometry.feature.RevolveRegionFeature
import dev.kstep.geometry.requireValid
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val logger = KotlinLogging.logger {}

/**
 * A constraint sketch ready to be solved: points, line/arc/circle geometry and constraints, built
 * by [sketch]. Immutable; [solve] can be called repeatedly (e.g. after the builder block was
 * parameterised differently, build a new sketch).
 */
class Sketch internal constructor(
    private val points: List<SketchPointData>,
    private val lines: List<SketchLineData>,
    private val arcs: List<SketchArcData>,
    private val circles: List<SketchCircleData>,
    private val constraints: List<SketchConstraintData>,
    private val token: Any,
) {
    /** Number of points (including arc centres and construction points). */
    val pointCount: Int get() = points.size

    /** Number of constraints, including those implied by arcs. */
    val constraintCount: Int get() = constraints.size

    /**
     * Solves the constraints and returns the resulting geometry.
     *
     * The analysis runs in plain Kotlin, independent of the solver: constraints that are
     * redundant (or contradict each other) are reported BEFORE the solver is called; after the
     * solve, the result is checked against every constraint and the remaining degrees of freedom
     * are counted.
     *
     * @param requireFullyConstrained when `true` (the default) a sketch with free degrees of
     *   freedom fails with [SketchErrorKind.UNDER_CONSTRAINED], naming the points that can still
     *   move; when `false` such a sketch solves to the solver's nearest configuration and
     *   [SolvedSketch.degreesOfFreedom] says how many freedoms remain.
     * @throws SketchException for every structural or numerical failure (see [SketchErrorKind]).
     * @throws SketchSolverUnavailableException if the native PlaneGCS bridge is unavailable.
     */
    fun solve(
        requireFullyConstrained: Boolean = true,
        maxIterations: Int = PlaneGcsSolver.DEFAULT_MAX_ITERATIONS,
    ): SolvedSketch {
        if (maxIterations !in 1..PlaneGcsSolver.MAX_ITERATIONS) {
            throw SketchException(
                SketchErrorKind.INVALID_INPUT,
                "maxIterations must be within 1..${PlaneGcsSolver.MAX_ITERATIONS}, got $maxIterations",
            )
        }
        if (points.isEmpty()) throw SketchException(SketchErrorKind.INVALID_INPUT, "the sketch has no points")
        checkDistancesSeparated()
        val start = DoubleArray(points.size * 2)
        val fixed = BooleanArray(points.size)
        points.forEachIndexed { i, p ->
            start[2 * i] = p.x
            start[2 * i + 1] = p.y
            fixed[i] = p.fixed
        }

        val pre = DofAnalysis.analyze(start, fixed, constraints)
        if (pre.redundantConstraints.isNotEmpty()) {
            throw SketchException(
                SketchErrorKind.OVER_CONSTRAINED,
                "the sketch is over-constrained: ${pre.redundantConstraints.size} constraint(s) are redundant or contradictory",
                pre.redundantConstraints.map { constraints[it].label },
                pre.degreesOfFreedom,
            )
        }
        if (pre.degreesOfFreedom > 0 && requireFullyConstrained) throw underConstrained(pre)

        var coords = start
        val anyFree = fixed.any { !it }
        if (anyFree && constraints.isNotEmpty()) coords = runSolver(start, fixed, maxIterations)
        verifyResiduals(coords)

        val post = DofAnalysis.analyze(coords, fixed, constraints)
        if (post.degreesOfFreedom > 0 && requireFullyConstrained) throw underConstrained(post)
        logger.debug {
            "sketch solved: ${points.size} points, ${constraints.size} constraints, dof=${post.degreesOfFreedom}"
        }
        return SolvedSketch(this, coords, post.degreesOfFreedom)
    }

    private fun checkDistancesSeparated() {
        constraints.filterIsInstance<DistanceData>().forEach { d ->
            val a = points[d.a]
            val b = points[d.b]
            if (a.x == b.x && a.y == b.y) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "the start coordinates of ${a.name} and ${b.name} coincide, but they carry a distance constraint; give them distinct start positions",
                    listOf(d.label),
                )
            }
        }
    }

    private fun underConstrained(result: DofResult): SketchException =
        SketchException(
            SketchErrorKind.UNDER_CONSTRAINED,
            "the sketch is under-constrained: ${result.degreesOfFreedom} degree(s) of freedom remain",
            result.movablePoints.map { points[it].name },
            result.degreesOfFreedom,
        )

    private fun runSolver(
        start: DoubleArray,
        fixed: BooleanArray,
        maxIterations: Int,
    ): DoubleArray {
        val gcsPoints = points.indices.map { SketchPoint(start[2 * it], start[2 * it + 1], fixed[it]) }
        val gcsConstraints = constraints.map { it.toSolverConstraint() }
        val result =
            try {
                PlaneGcsSolver.solve(gcsPoints, gcsConstraints, maxIterations)
            } catch (e: PlaneGcsUnavailableException) {
                throw SketchSolverUnavailableException(
                    "Sketch solving needs the native PlaneGCS bridge; this build (e.g. the release zip) " +
                        "ships without native bridges. ${e.message}",
                    e,
                )
            } catch (e: ConstraintSolverException) {
                throw SketchException(
                    SketchErrorKind.SOLVER_FAILED,
                    "the constraint solver failed: ${e.message}",
                    cause = e,
                )
            } catch (e: IllegalArgumentException) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "the solver rejected the sketch: ${e.message}",
                    cause = e,
                )
            }
        if (!result.solved) {
            throw SketchException(
                SketchErrorKind.SOLVER_FAILED,
                "the constraint solver found no solution (status ${result.status}); the constraints may contradict each other",
            )
        }
        val coords = DoubleArray(points.size * 2)
        result.points.forEachIndexed { i, p ->
            coords[2 * i] = p.x
            coords[2 * i + 1] = p.y
        }
        return coords
    }

    private fun verifyResiduals(coords: DoubleArray) {
        val scale =
            max(
                1.0,
                max(
                    coords.maxOfOrNull { abs(it) } ?: 0.0,
                    constraints.filterIsInstance<DistanceData>().maxOfOrNull { it.distance } ?: 0.0,
                ),
            )
        val tolerance = 1e-6 * scale
        val violations =
            constraints.mapNotNull { c ->
                val worst = DofAnalysis.residual(c, coords).maxOf { abs(it) }
                if (worst > tolerance) "${c.label} (off by $worst)" else null
            }
        if (violations.isNotEmpty()) {
            throw SketchException(
                SketchErrorKind.SOLVER_FAILED,
                "the solution violates ${violations.size} constraint(s); the constraints may contradict each other",
                violations,
            )
        }
    }

    private fun SketchConstraintData.toSolverConstraint(): SketchConstraint =
        when (this) {
            is CoincidentData -> CoincidenceConstraint(a, b)
            is DistanceData -> DistanceConstraint(a, b, distance)
            is HorizontalData -> HorizontalConstraint(a, b)
            is VerticalData -> VerticalConstraint(a, b)
            is PointOnLineData -> PointOnLineConstraint(p, a, b)
            is ParallelData -> ParallelConstraint(a1, b1, a2, b2)
            is PerpendicularData -> PerpendicularConstraint(a1, b1, a2, b2)
        }

    internal fun pointName(index: Int) = points[index].name

    /** Degrees-of-freedom analysis at the START coordinates (no solver involved); used by tests and diagnostics. */
    internal fun analyzeStart(): DofResult {
        val coords = DoubleArray(points.size * 2)
        val fixed = BooleanArray(points.size)
        points.forEachIndexed { i, p ->
            coords[2 * i] = p.x
            coords[2 * i + 1] = p.y
            fixed[i] = p.fixed
        }
        return DofAnalysis.analyze(coords, fixed, constraints)
    }

    internal fun constraintLabels(): List<String> = constraints.map { it.label }

    internal fun ownsPoint(ref: SketchPointRef) = ref.owner === token && ref.index in points.indices

    /** Builds the profile region from solved coordinates; see [SolvedSketch.toRegion]. */
    internal fun toRegion(coords: DoubleArray): ProfileRegion {
        fun pt(i: Int) = ProfilePoint(coords[2 * i], coords[2 * i + 1])

        class Edge(
            val from: Int,
            val to: Int,
            val arc: SketchArcData?,
        )
        val edges = ArrayList<Edge>()
        lines.filter { !it.construction }.forEach { edges.add(Edge(it.from, it.to, null)) }
        arcs.forEach { edges.add(Edge(it.start, it.end, it)) }

        val incident = HashMap<Int, MutableList<Int>>()
        edges.forEachIndexed { i, e ->
            incident.getOrPut(e.from) { ArrayList() }.add(i)
            incident.getOrPut(e.to) { ArrayList() }.add(i)
        }
        val open = incident.filter { it.value.size != 2 }.keys.sorted()
        if (open.isNotEmpty()) {
            throw SketchException(
                SketchErrorKind.NOT_CLOSED,
                "the profile is not closed: ${open.size} point(s) do not join exactly two profile segments",
                open.map { "${points[it].name} (${incident.getValue(it).size} segment(s))" },
            )
        }

        fun segmentOf(
            e: Edge,
            forward: Boolean,
        ): ProfileSegment {
            val a = if (forward) e.from else e.to
            val b = if (forward) e.to else e.from
            val arc = e.arc ?: return ProfileSegment.Line(pt(a), pt(b))
            val c = pt(arc.center)
            val s = pt(arc.start)
            val t = pt(arc.end)
            val a0 = atan2(s.y - c.y, s.x - c.x)
            val a1 = atan2(t.y - c.y, t.x - c.x)
            val ccwSweep = positiveAngle(a1 - a0)
            val sweep = if (arc.ccw) ccwSweep else -positiveAngle(a0 - a1)
            if (abs(sweep) < 1e-9 || abs(abs(sweep) - 2.0 * PI) < 1e-9) {
                throw SketchException(
                    SketchErrorKind.INVALID_PROFILE,
                    "arc ${points[arc.start].name}-${points[arc.end].name} has coincident start and end angles; use a circle",
                )
            }
            val mid = a0 + sweep / 2.0
            val m = ProfilePoint(c.x + arc.radius * cos(mid), c.y + arc.radius * sin(mid))
            return if (forward) {
                ProfileSegment.Arc(
                    pt(arc.start),
                    m,
                    pt(arc.end),
                )
            } else {
                ProfileSegment.Arc(pt(arc.end), m, pt(arc.start))
            }
        }

        val visited = BooleanArray(edges.size)
        val loops = ArrayList<ProfileLoop>()
        for (first in edges.indices) {
            if (visited[first]) continue
            val segments = ArrayList<ProfileSegment>()
            var edge = first
            var current = edges[first].from
            while (!visited[edge]) {
                visited[edge] = true
                val e = edges[edge]
                val forward = e.from == current
                segments.add(segmentOf(e, forward))
                current = if (forward) e.to else e.from
                edge = incident.getValue(current).first { it != edge }
            }
            loops.add(ProfileLoop(segments))
        }
        circles.forEach { loops.add(ProfileLoop(listOf(ProfileSegment.Circle(pt(it.center), it.radius)))) }

        if (loops.isEmpty()) {
            throw SketchException(
                SketchErrorKind.INVALID_PROFILE,
                "the sketch has no closed profile geometry (lines, arcs or circles)",
            )
        }
        val outerIndex = loops.indices.maxBy { abs(loops[it].approximateSignedArea()) }
        val region = ProfileRegion(loops[outerIndex], loops.filterIndexed { i, _ -> i != outerIndex })
        try {
            region.requireValid()
        } catch (e: IllegalArgumentException) {
            throw SketchException(
                SketchErrorKind.INVALID_PROFILE,
                "the profile is not a valid region: ${e.message}",
                cause = e,
            )
        }
        return region
    }

    private fun positiveAngle(angle: Double): Double {
        var a = angle % (2.0 * PI)
        if (a < 0.0) a += 2.0 * PI
        return a
    }
}

/**
 * The result of [Sketch.solve]: solved coordinates plus the profile region they form.
 *
 * Shapes returned by [extrude] and [revolve] are owned by the caller (close them, or hand them to
 * `shape(...)` in a script, which closes registered shapes itself).
 */
class SolvedSketch internal constructor(
    private val sketch: Sketch,
    private val coords: DoubleArray,
    /** Free degrees of freedom left after solving (0 for a fully constrained sketch). */
    val degreesOfFreedom: Int,
) {
    /** Solved position of [ref]. */
    fun point(ref: SketchPointRef): ProfilePoint {
        if (!sketch.ownsPoint(ref)) {
            throw SketchException(SketchErrorKind.INVALID_INPUT, "a point reference belongs to a different sketch")
        }
        return ProfilePoint(coords[2 * ref.index], coords[2 * ref.index + 1])
    }

    /**
     * The closed profile as a [ProfileRegion]: the loop of largest area is the outer contour, every
     * other loop a hole. Construction geometry is ignored.
     *
     * @throws SketchException with [SketchErrorKind.NOT_CLOSED] or [SketchErrorKind.INVALID_PROFILE].
     */
    fun toRegion(): ProfileRegion = sketch.toRegion(coords)

    /** Extrudes the profile by [height] along +Z; see [OcctKernel.extrudeRegion]. */
    fun extrude(height: Double): OcctShape = OcctKernel.extrudeRegion(toRegion(), height)

    /** Revolves the profile about [axis]; see [OcctKernel.revolveRegion]. */
    fun revolve(
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): OcctShape = OcctKernel.revolveRegion(toRegion(), angleDegrees, axis)

    /** The extrusion as a feature-history step (a plain value; the region is solved already). */
    fun extrudeFeature(height: Double): ExtrudeRegionFeature = ExtrudeRegionFeature(toRegion(), height)

    /** The revolution as a feature-history step. */
    fun revolveFeature(
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): RevolveRegionFeature = RevolveRegionFeature(toRegion(), angleDegrees, axis)
}
