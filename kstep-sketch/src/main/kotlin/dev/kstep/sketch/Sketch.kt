package dev.kstep.sketch

import dev.kstep.constraints.AngleConstraint
import dev.kstep.constraints.CoincidenceConstraint
import dev.kstep.constraints.ConstraintSolverException
import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.EqualScalarConstraint
import dev.kstep.constraints.HorizontalConstraint
import dev.kstep.constraints.ParallelConstraint
import dev.kstep.constraints.PerpendicularConstraint
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PlaneGcsUnavailableException
import dev.kstep.constraints.PointOnCircleConstraint
import dev.kstep.constraints.PointOnLineConstraint
import dev.kstep.constraints.RadiusConstraint
import dev.kstep.constraints.SketchConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SketchScalar
import dev.kstep.constraints.TangentCircleCircleConstraint
import dev.kstep.constraints.TangentLineCircleConstraint
import dev.kstep.constraints.VerticalConstraint
import dev.kstep.geometry.ExternalGenerationSource
import dev.kstep.geometry.GenerationSource
import dev.kstep.geometry.NamedShape
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.PointAt
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.SegmentAt
import dev.kstep.geometry.feature.ExtrudeRegionFeature
import dev.kstep.geometry.feature.ExtrudeRegionsFeature
import dev.kstep.geometry.feature.Feature
import dev.kstep.geometry.feature.RevolveRegionFeature
import dev.kstep.geometry.feature.RevolveRegionsFeature
import dev.kstep.geometry.groupLoopsIntoRegions
import dev.kstep.geometry.requireValid
import dev.kstep.geometry.requireValidDisjoint
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
    private val scalars: List<SketchScalarData>,
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
        val pointCount = points.size
        val start = DoubleArray(pointCount * 2 + scalars.size)
        val fixed = BooleanArray(pointCount)
        val fixedScalars = BooleanArray(scalars.size)
        points.forEachIndexed { i, p ->
            start[2 * i] = p.x
            start[2 * i + 1] = p.y
            fixed[i] = p.fixed
        }
        scalars.forEachIndexed { k, sc ->
            start[2 * pointCount + k] = sc.initial
            fixedScalars[k] = sc.fixed
        }

        val pre = DofAnalysis.analyze(start, fixed, constraints, fixedScalars)
        if (pre.redundantConstraints.isNotEmpty()) {
            throw SketchException(
                SketchErrorKind.OVER_CONSTRAINED,
                "the sketch is over-constrained: ${pre.redundantConstraints.size} constraint(s) are redundant or contradictory",
                pre.redundantConstraints.map { redundancyLabel(constraints[it]) },
                pre.degreesOfFreedom,
            )
        }
        if (pre.degreesOfFreedom > 0 && requireFullyConstrained) throw underConstrained(pre)

        var coords = start
        val anyFree = fixed.any { !it } || fixedScalars.any { !it }
        if (anyFree && constraints.isNotEmpty()) coords = runSolver(start, fixed, maxIterations)
        verifyRadii(coords)
        verifyResiduals(coords)

        val post = DofAnalysis.analyze(coords, fixed, constraints, fixedScalars)
        if (post.degreesOfFreedom > 0 && requireFullyConstrained) throw underConstrained(post)
        logger.debug {
            "sketch solved: ${points.size} points, ${scalars.size} radii, ${constraints.size} constraints, " +
                "dof=${post.degreesOfFreedom}"
        }
        return SolvedSketch(this, coords, post.degreesOfFreedom)
    }

    private fun redundancyLabel(c: SketchConstraintData): String {
        val fixedRadius =
            when (c) {
                is RadiusData -> scalars[c.scalar].fixed
                is EqualRadiusData -> scalars[c.a].fixed && scalars[c.b].fixed
                else -> false
            }
        return if (fixedRadius) "${c.label} (radius is fixed; use fixedRadius = false)" else c.label
    }

    private fun verifyRadii(coords: DoubleArray) {
        scalars.forEachIndexed { k, sc ->
            val r = coords[2 * points.size + k]
            if (!r.isFinite() || r < OcctKernel.MIN_DIMENSION || r > OcctKernel.MAX_DIMENSION) {
                throw SketchException(
                    SketchErrorKind.SOLVER_FAILED,
                    "the solved ${sc.label} is $r, outside [${OcctKernel.MIN_DIMENSION}, ${OcctKernel.MAX_DIMENSION}]",
                    listOf(sc.label),
                )
            }
        }
    }

    private fun checkDistancesSeparated() {
        fun check(
            i: Int,
            j: Int,
            label: String,
        ) {
            val a = points[i]
            val b = points[j]
            if (a.x == b.x && a.y == b.y) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "the start coordinates of ${a.name} and ${b.name} coincide, but they carry a distance, circle " +
                        "or tangent constraint; give them distinct start positions",
                    listOf(label),
                )
            }
        }
        constraints.forEach { c ->
            when (c) {
                is DistanceData -> check(c.a, c.b, c.label)
                is PointOnCircleData -> check(c.p, c.center, c.label)
                is TangentCirclesData -> check(c.c1, c.c2, c.label)
                is TangentLineData -> check(c.from, c.to, c.label)
                else -> Unit
            }
        }
    }

    private fun underConstrained(result: DofResult): SketchException =
        SketchException(
            SketchErrorKind.UNDER_CONSTRAINED,
            "the sketch is under-constrained: ${result.degreesOfFreedom} degree(s) of freedom remain",
            result.movablePoints.map { points[it].name } + result.movableScalars.map { scalars[it].label },
            result.degreesOfFreedom,
        )

    private fun runSolver(
        start: DoubleArray,
        fixed: BooleanArray,
        maxIterations: Int,
    ): DoubleArray {
        val gcsPoints = points.indices.map { SketchPoint(start[2 * it], start[2 * it + 1], fixed[it]) }
        val gcsConstraints = constraints.map { it.toSolverConstraint() }
        val gcsScalars = scalars.indices.map { SketchScalar(start[2 * points.size + it], scalars[it].fixed) }
        val result =
            try {
                PlaneGcsSolver.solve(gcsPoints, gcsConstraints, gcsScalars, maxIterations)
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
        val coords = DoubleArray(points.size * 2 + scalars.size)
        result.points.forEachIndexed { i, p ->
            coords[2 * i] = p.x
            coords[2 * i + 1] = p.y
        }
        scalars.indices.forEach { coords[2 * points.size + it] = result.scalar(it) }
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
                val worst = DofAnalysis.residual(c, coords, points.size).maxOf { abs(it) }
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
            is RadiusData -> RadiusConstraint(scalar, value)
            is EqualRadiusData -> EqualScalarConstraint(a, b)
            is PointOnCircleData -> PointOnCircleConstraint(p, center, scalar)
            is TangentLineData -> TangentLineCircleConstraint(from, to, center, scalar)
            is TangentCirclesData -> TangentCircleCircleConstraint(c1, s1, c2, s2, internal)
            is AngleData -> AngleConstraint(a1, b1, a2, b2, radians)
        }

    internal fun pointName(index: Int) = points[index].name

    /** Degrees-of-freedom analysis at the START coordinates (no solver involved); used by tests and diagnostics. */
    internal fun analyzeStart(): DofResult {
        val coords = DoubleArray(points.size * 2 + scalars.size)
        val fixed = BooleanArray(points.size)
        points.forEachIndexed { i, p ->
            coords[2 * i] = p.x
            coords[2 * i + 1] = p.y
            fixed[i] = p.fixed
        }
        scalars.forEachIndexed { k, sc -> coords[2 * points.size + k] = sc.initial }
        return DofAnalysis.analyze(coords, fixed, constraints, BooleanArray(scalars.size) { scalars[it].fixed })
    }

    internal fun constraintLabels(): List<String> = constraints.map { it.label }

    /** Names of the non-construction points, in creation order (for diagnostics of named profile edges). */
    internal fun profilePointNames(): List<String> = points.filter { !it.construction }.map { it.name }

    /** Solved position of the unique non-construction point called [name], or null if there is none. */
    internal fun solvedPointNamed(
        name: String,
        coords: DoubleArray,
    ): ProfilePoint? {
        val matches = points.indices.filter { !points[it].construction && points[it].name == name }
        if (matches.size > 1) {
            throw SketchException(
                SketchErrorKind.INVALID_INPUT,
                "point name '$name' is not unique (${matches.size} points); give the points distinct names",
                profilePointNames(),
            )
        }
        val i = matches.singleOrNull() ?: return null
        return ProfilePoint(coords[2 * i], coords[2 * i + 1])
    }

    internal fun ownsPoint(ref: SketchPointRef) = ref.owner === token && ref.index in points.indices

    internal fun ownsRound(ref: SketchRoundRef) = ref.owner === token && ref.scalar in scalars.indices

    /** The single profile region; fails when the sketch has several separate outer contours. */
    internal fun toRegion(coords: DoubleArray): ProfileRegion {
        val regions = toRegions(coords)
        if (regions.size > 1) {
            throw SketchException(
                SketchErrorKind.INVALID_PROFILE,
                "the profile has ${regions.size} separate outer contours; use toRegions()/extrude() for multiple bodies",
            )
        }
        return regions.single()
    }

    /** Builds the profile regions (one per separate outer contour) from solved coordinates. */
    internal fun toRegions(coords: DoubleArray): List<ProfileRegion> {
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
            val radius = coords[2 * points.size + arc.scalar]
            val m = ProfilePoint(c.x + radius * cos(mid), c.y + radius * sin(mid))
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
        circles.forEach {
            loops.add(ProfileLoop(listOf(ProfileSegment.Circle(pt(it.center), coords[2 * points.size + it.scalar]))))
        }

        if (loops.isEmpty()) {
            throw SketchException(
                SketchErrorKind.INVALID_PROFILE,
                "the sketch has no closed profile geometry (lines, arcs or circles)",
            )
        }
        val regions =
            try {
                groupLoopsIntoRegions(loops)
            } catch (e: IllegalArgumentException) {
                throw SketchException(
                    SketchErrorKind.INVALID_PROFILE,
                    "the profile is not a valid region: ${e.message}",
                    cause = e,
                )
            }
        try {
            regions.forEach { it.requireValid() }
            if (regions.size > 1) regions.requireValidDisjoint()
        } catch (e: IllegalArgumentException) {
            throw SketchException(
                SketchErrorKind.INVALID_PROFILE,
                "the profile is not a valid region: ${e.message}",
                cause = e,
            )
        }
        return regions
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

    /** Solved radius of a circle or arc. */
    fun radius(ref: SketchRoundRef): Double {
        if (!sketch.ownsRound(ref)) {
            throw SketchException(
                SketchErrorKind.INVALID_INPUT,
                "a circle or arc reference belongs to a different sketch",
            )
        }
        return coords[2 * sketch.pointCount + ref.scalar]
    }

    /**
     * The closed profile as a [ProfileRegion]: the loop of largest area is the outer contour, every
     * other loop a hole. Construction geometry is ignored.
     *
     * @throws SketchException with [SketchErrorKind.NOT_CLOSED] or [SketchErrorKind.INVALID_PROFILE].
     */
    fun toRegion(): ProfileRegion = sketch.toRegion(coords)

    /**
     * All separate outer contours as regions (an island inside a hole counts as its own region).
     *
     * @throws SketchException with [SketchErrorKind.NOT_CLOSED] or [SketchErrorKind.INVALID_PROFILE]; contours
     *   that touch or are closer than the documented clearance are rejected (ADR-0032).
     */
    fun toRegions(): List<ProfileRegion> = sketch.toRegions(coords)

    /**
     * Extrudes the profile by [height] along +Z; see [OcctKernel.extrudeRegion]. Several separate outer
     * contours become several bodies in ONE shape ([OcctKernel.extrudeRegions]).
     */
    fun extrude(height: Double): OcctShape {
        val regions = toRegions()
        return if (regions.size == 1) {
            OcctKernel.extrudeRegion(regions[0], height)
        } else {
            OcctKernel.extrudeRegions(regions, height)
        }
    }

    /** Revolves the profile about [axis]; see [OcctKernel.revolveRegion]; several contours as in [extrude]. */
    fun revolve(
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): OcctShape {
        val regions = toRegions()
        return if (regions.size == 1) {
            OcctKernel.revolveRegion(regions[0], angleDegrees, axis)
        } else {
            OcctKernel.revolveRegions(regions, angleDegrees, axis)
        }
    }

    /**
     * [extrude] for a single-contour profile that also reports the edges generated from [sources] (ADR-0033):
     * [SketchCorner] and [SketchEdgeOf] by point name, or any region-level source ([dev.kstep.geometry.RegionVertex],
     * [dev.kstep.geometry.PointAt], ...). The returned map is keyed by the sources AS GIVEN.
     *
     * @throws SketchException with [SketchErrorKind.INVALID_INPUT] (and the available point names in `details`)
     *   for an unknown or duplicate point name, a point that is not a corner of the profile, a missing edge, or
     *   a sketch with several separate contours (not supported yet).
     */
    fun extrudeNamed(
        height: Double,
        sources: List<GenerationSource>,
    ): NamedShape {
        val (region, translated, back) = prepareNamed(sources)
        return OcctKernel.extrudeRegionNamed(region, height, translated).rekeyed(back)
    }

    /** [revolve] with a generation map; see [extrudeNamed]. */
    fun revolveNamed(
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
        sources: List<GenerationSource>,
    ): NamedShape {
        val (region, translated, back) = prepareNamed(sources)
        return OcctKernel.revolveRegionNamed(region, angleDegrees, axis, translated).rekeyed(back)
    }

    private data class NamedPlan(
        val region: ProfileRegion,
        val translated: List<GenerationSource>,
        val back: Map<GenerationSource, GenerationSource>,
    )

    private fun prepareNamed(sources: List<GenerationSource>): NamedPlan {
        val regions = toRegions()
        if (regions.size != 1) {
            throw SketchException(
                SketchErrorKind.INVALID_INPUT,
                "naming generated edges needs a single contour; this sketch has ${regions.size} separate outer " +
                    "contours (multi-body naming is not supported yet, see ADR-0033)",
            )
        }
        val region = regions[0]
        val segments = (listOf(region.outer) + region.holes).flatMap { it.segments }
        val corners = segments.filterNot { it is ProfileSegment.Circle }.map { cornerOf(it) }

        fun near(
            a: ProfilePoint,
            b: ProfilePoint,
        ) = abs(a.x - b.x) <= NAME_MATCH_TOLERANCE && abs(a.y - b.y) <= NAME_MATCH_TOLERANCE

        fun lookup(name: String): ProfilePoint =
            sketch.solvedPointNamed(name, coords)
                ?: throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "unknown point name '$name'; available names: ${sketch.profilePointNames()}",
                    sketch.profilePointNames(),
                )

        fun requireCorner(
            name: String,
            p: ProfilePoint,
        ) {
            if (corners.none { near(it, p) }) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "point '$name' is not a corner of the profile (a centre or construction point?); " +
                        "available names: ${sketch.profilePointNames()}",
                    sketch.profilePointNames(),
                )
            }
        }
        val translated = ArrayList<GenerationSource>(sources.size)
        val back = LinkedHashMap<GenerationSource, GenerationSource>()
        sources.forEach { source ->
            val t: GenerationSource =
                when (source) {
                    is SketchCorner -> {
                        val p = lookup(source.pointName)
                        requireCorner(source.pointName, p)
                        PointAt(p.x, p.y)
                    }
                    is SketchEdgeOf -> {
                        val a = lookup(source.fromName)
                        val b = lookup(source.toName)
                        requireCorner(source.fromName, a)
                        requireCorner(source.toName, b)
                        val exists =
                            segments.any { seg ->
                                val s = segmentStart(seg)
                                val e = segmentEnd(seg)
                                (near(s, a) && near(e, b)) || (near(s, b) && near(e, a))
                            }
                        if (!exists) {
                            throw SketchException(
                                SketchErrorKind.INVALID_INPUT,
                                "the profile has no edge between '${source.fromName}' and '${source.toName}'",
                                sketch.profilePointNames(),
                            )
                        }
                        SegmentAt(a.x, a.y, b.x, b.y)
                    }
                    is ExternalGenerationSource ->
                        throw SketchException(
                            SketchErrorKind.INVALID_INPUT,
                            "unsupported generation source '${source.description}' for a sketch",
                        )
                    else -> source
                }
            translated.add(t)
            // Known limitation: the feature rebuilder passes the UNION of the sources of all consumers of
            // this sketch. Two consumers that name the same element differently (e.g. SketchCorner("a") and
            // PointAt(<solved coordinates of a>)) are each valid alone but collide here; the rebuild then
            // fails loudly with this error rather than producing wrong geometry.
            if (back.put(t, source) != null) {
                throw SketchException(
                    SketchErrorKind.INVALID_INPUT,
                    "two generation sources name the same profile element: $source",
                )
            }
        }
        return NamedPlan(region, translated, back)
    }

    private fun cornerOf(seg: ProfileSegment): ProfilePoint = segmentStart(seg)

    private fun segmentStart(seg: ProfileSegment): ProfilePoint =
        when (seg) {
            is ProfileSegment.Line -> seg.start
            is ProfileSegment.Arc -> seg.start
            is ProfileSegment.Circle -> ProfilePoint(seg.center.x + seg.radius, seg.center.y)
        }

    private fun segmentEnd(seg: ProfileSegment): ProfilePoint =
        when (seg) {
            is ProfileSegment.Line -> seg.end
            is ProfileSegment.Arc -> seg.end
            is ProfileSegment.Circle -> ProfilePoint(seg.center.x + seg.radius, seg.center.y)
        }

    private companion object {
        const val NAME_MATCH_TOLERANCE = 1e-6
    }

    /** The extrusion as a feature-history step (a plain value; the region is solved already). */
    fun extrudeFeature(height: Double): Feature {
        val regions = toRegions()
        return if (regions.size == 1) {
            ExtrudeRegionFeature(regions[0], height)
        } else {
            ExtrudeRegionsFeature(regions, height)
        }
    }

    /** The revolution as a feature-history step. */
    fun revolveFeature(
        angleDegrees: Double = 360.0,
        axis: RevolveAxis = RevolveAxis.Y,
    ): Feature {
        val regions = toRegions()
        return if (regions.size == 1) {
            RevolveRegionFeature(regions[0], angleDegrees, axis)
        } else {
            RevolveRegionsFeature(regions, angleDegrees, axis)
        }
    }
}
