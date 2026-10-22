package dev.kstep.geometry

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** One boundary element of a [ProfileLoop], in the XY plane. */
sealed interface ProfileSegment {
    /** A straight segment. */
    data class Line(
        val start: ProfilePoint,
        val end: ProfilePoint,
    ) : ProfileSegment

    /** A circular arc from [start] through [mid] to [end] (three-point arc, never a full circle). */
    data class Arc(
        val start: ProfilePoint,
        val mid: ProfilePoint,
        val end: ProfilePoint,
    ) : ProfileSegment

    /** A full circle; must be the only segment of its loop. */
    data class Circle(
        val center: ProfilePoint,
        val radius: Double,
    ) : ProfileSegment
}

/** A closed chain of [ProfileSegment]s (the end of each is the start of the next, the last closes the first). */
data class ProfileLoop(
    val segments: List<ProfileSegment>,
)

/**
 * A planar region: one outer loop and any number of holes, all in the XY plane at z = 0. Extrude or
 * revolve it with `OcctKernel.extrudeRegion` / `OcctKernel.revolveRegion`.
 */
data class ProfileRegion(
    val outer: ProfileLoop,
    val holes: List<ProfileLoop> = emptyList(),
)

/**
 * Validates this region exactly as `OcctKernel.extrudeRegion` / `revolveRegion` do before they touch
 * native code: closed continuous loops, valid arcs and circles, size limits, holes inside the outer
 * loop and not overlapping each other. Self-intersection of a single loop is NOT checked here; OCCT's
 * own validity check rejects it when the solid is built.
 *
 * @throws IllegalArgumentException naming the offending loop/segment.
 */
fun ProfileRegion.requireValid() = RegionValidation.validate(this)

/**
 * Checks that [this] list of regions forms valid, pairwise DISJOINT separate bodies: every region on its
 * own is valid, at most [RegionValidation.MAX_REGIONS] regions, and no two regions cross, touch or nest
 * (an island inside a HOLE of another region is allowed). See ADR-0032 for the conservative clearance rule.
 *
 * @throws IllegalArgumentException naming the offending regions.
 */
fun List<ProfileRegion>.requireValidDisjoint() = RegionValidation.validateDisjoint(this)

/**
 * Groups closed [loops] into regions by nesting depth: a loop at even depth (not inside any other, or
 * inside a hole) is an outer contour, a loop at odd depth is a hole of its direct parent. Loops must not
 * cross each other. The regions come in the order of their outer loops.
 *
 * @throws IllegalArgumentException if two loops cross.
 */
fun groupLoopsIntoRegions(loops: List<ProfileLoop>): List<ProfileRegion> = RegionValidation.group(loops)

/**
 * Signed area of this loop's polygon approximation (arcs and circles are sampled): positive when the
 * loop runs counter-clockwise. Accurate to a fraction of a percent, intended for ordering loops by
 * size and for orientation, not for measurement.
 */
fun ProfileLoop.approximateSignedArea(): Double = RegionValidation.signedArea(RegionValidation.polygon(this))

internal fun ProfileSegment.startPoint(): ProfilePoint =
    when (this) {
        is ProfileSegment.Line -> start
        is ProfileSegment.Arc -> start
        is ProfileSegment.Circle -> ProfilePoint(center.x + radius, center.y)
    }

internal fun ProfileSegment.endPoint(): ProfilePoint =
    when (this) {
        is ProfileSegment.Line -> end
        is ProfileSegment.Arc -> end
        is ProfileSegment.Circle -> ProfilePoint(center.x + radius, center.y)
    }

/** Pure-Kotlin validation and normalisation of [ProfileRegion]s; the native side re-validates independently. */
internal object RegionValidation {
    const val MAX_REGION_LOOPS: Int = 64
    const val MAX_REGION_SEGMENTS: Int = 512
    const val MAX_REGIONS: Int = 32
    private const val CLEARANCE_SAMPLES = 128
    private const val SAGITTA_FACTOR = 0.0003
    private const val CLEARANCE_RELATIVE = 1e-6
    private const val MAX_CLEARANCE_PAIRS = 40_000_000L
    private const val ARC_SAMPLES = 16
    private const val CIRCLE_SAMPLES = 32
    private const val COLLINEAR_SINE = 1e-9

    /** Validates [region]; throws [IllegalArgumentException] naming the offending loop/segment. */
    fun validate(region: ProfileRegion) {
        val loops = listOf(region.outer) + region.holes
        require(
            loops.size <= MAX_REGION_LOOPS,
        ) { "region must have at most $MAX_REGION_LOOPS loops, got ${loops.size}" }
        val total = loops.sumOf { it.segments.size }
        require(total <= MAX_REGION_SEGMENTS) { "region must have at most $MAX_REGION_SEGMENTS segments, got $total" }
        loops.forEachIndexed { l, loop -> validateLoop(l, loop) }
        val polygons = loops.map { polygon(it) }
        polygons.forEachIndexed { l, p ->
            require(abs(signedArea(p)) > 1e-12) { "loop $l encloses no area" }
        }
        for (h in 1 until polygons.size) {
            require(allInside(polygons[h], polygons[0]) && !crosses(polygons[h], polygons[0])) {
                "hole ${h - 1} is not completely inside the outer loop"
            }
            for (k in 1 until h) {
                require(
                    !crosses(polygons[h], polygons[k]) &&
                        !allInside(polygons[h], polygons[k]) &&
                        !allInside(polygons[k], polygons[h]),
                ) {
                    "holes ${k - 1} and ${h - 1} overlap or are nested"
                }
            }
        }
    }

    /** Validates several regions as pairwise disjoint bodies; see [requireValidDisjoint]. */
    fun validateDisjoint(regions: List<ProfileRegion>) {
        require(regions.isNotEmpty()) { "at least one region is required" }
        require(regions.size <= MAX_REGIONS) { "at most $MAX_REGIONS regions are allowed, got ${regions.size}" }
        val loopCount = regions.sumOf { 1 + it.holes.size }
        require(
            loopCount <= MAX_REGION_LOOPS,
        ) { "regions together may have at most $MAX_REGION_LOOPS loops, got $loopCount" }
        val segCount = regions.sumOf { r -> (listOf(r.outer) + r.holes).sumOf { it.segments.size } }
        require(segCount <= MAX_REGION_SEGMENTS) {
            "regions together may have at most $MAX_REGION_SEGMENTS segments, got $segCount"
        }
        regions.forEachIndexed { i, r ->
            try {
                validate(r)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("region $i: ${e.message}", e)
            }
        }
        if (regions.size == 1) return
        val outers = regions.map { polygon(it.outer) }
        val holes = regions.map { r -> r.holes.map { polygon(it) } }
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                require(!crosses(outers[i], outers[j])) { "regions $i and $j cross each other" }
                val iInJ = allInside(outers[i], outers[j])
                val jInI = allInside(outers[j], outers[i])
                if (iInJ) {
                    require(holes[j].any { allInside(outers[i], it) }) {
                        "region $i lies inside region $j (not in one of its holes)"
                    }
                }
                if (jInI) {
                    require(holes[i].any { allInside(outers[j], it) }) {
                        "region $j lies inside region $i (not in one of its holes)"
                    }
                }
            }
        }
        requireClearance(regions)
    }

    private fun loopsOf(region: ProfileRegion) = listOf(region.outer) + region.holes

    private fun sagitta(loop: ProfileLoop): Double {
        if (loop.segments.all { it is ProfileSegment.Line }) return 0.0
        val pts = polygon(loop)
        val extent = max(pts.maxOf { it.x } - pts.minOf { it.x }, pts.maxOf { it.y } - pts.minOf { it.y })
        return SAGITTA_FACTOR * extent
    }

    /** Conservative minimum distance between the boundaries of two different regions. */
    private fun requireClearance(regions: List<ProfileRegion>) {
        class Dense(
            val poly: List<ProfilePoint>,
            val sag: Double,
        )

        val dense =
            regions.map { r -> loopsOf(r).map { Dense(polygon(it, dense = true), sagitta(it)) } }
        val scale = max(1.0, dense.flatten().maxOf { d -> d.poly.maxOf { max(abs(it.x), abs(it.y)) } })
        var work = 0L
        for (i in regions.indices) {
            for (j in i + 1 until regions.size) {
                for (a in dense[i]) {
                    for (b in dense[j]) {
                        val margin = a.sag + b.sag + CLEARANCE_RELATIVE * scale
                        if (boxGap(a.poly, b.poly) > margin) continue
                        work += a.poly.size.toLong() * b.poly.size
                        require(work <= MAX_CLEARANCE_PAIRS) {
                            "regions are too complex to check their clearance (limit reached); simplify the profile"
                        }
                        val d = polylineDistance(a.poly, b.poly)
                        require(d > margin) {
                            "regions $i and $j touch or are closer than ${"%.3g".format(margin)}"
                        }
                    }
                }
            }
        }
    }

    private fun boxGap(
        a: List<ProfilePoint>,
        b: List<ProfilePoint>,
    ): Double {
        val gx = max(0.0, max(a.minOf { it.x } - b.maxOf { it.x }, b.minOf { it.x } - a.maxOf { it.x }))
        val gy = max(0.0, max(a.minOf { it.y } - b.maxOf { it.y }, b.minOf { it.y } - a.maxOf { it.y }))
        return hypot(gx, gy)
    }

    private fun polylineDistance(
        a: List<ProfilePoint>,
        b: List<ProfilePoint>,
    ): Double {
        var best = Double.MAX_VALUE
        for (i in a.indices) {
            val p1 = a[i]
            val p2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                val q1 = b[j]
                val q2 = b[(j + 1) % b.size]
                if (segmentsIntersect(p1, p2, q1, q2)) return 0.0
                best =
                    minOf(
                        best,
                        pointSegment(p1, q1, q2),
                        pointSegment(p2, q1, q2),
                        pointSegment(q1, p1, p2),
                        pointSegment(q2, p1, p2),
                    )
            }
        }
        return best
    }

    private fun pointSegment(
        p: ProfilePoint,
        a: ProfilePoint,
        b: ProfilePoint,
    ): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
    }

    /** See [groupLoopsIntoRegions]. */
    fun group(loops: List<ProfileLoop>): List<ProfileRegion> {
        val polys = loops.map { polygon(it) }
        val areas = polys.map { abs(signedArea(it)) }
        for (i in loops.indices) {
            for (j in i + 1 until loops.size) {
                require(!crosses(polys[i], polys[j])) { "profile loops $i and $j cross each other" }
            }
        }
        val parent = IntArray(loops.size) { -1 }
        for (i in loops.indices) {
            var best = -1
            for (j in loops.indices) {
                if (i == j || areas[j] <= areas[i]) continue
                if (allInside(polys[i], polys[j]) && (best < 0 || areas[j] < areas[best])) best = j
            }
            parent[i] = best
        }

        fun depth(i: Int): Int {
            var d = 0
            var k = parent[i]
            while (k >= 0) {
                d++
                k = parent[k]
            }
            return d
        }
        val depths = loops.indices.map { depth(it) }
        return loops.indices.filter { depths[it] % 2 == 0 }.map { outer ->
            ProfileRegion(
                loops[outer],
                loops.indices.filter { parent[it] == outer && depths[it] % 2 == 1 }.map { loops[it] },
            )
        }
    }

    private fun validateLoop(
        l: Int,
        loop: ProfileLoop,
    ) {
        require(loop.segments.isNotEmpty()) { "loop $l has no segments" }
        loop.segments.forEachIndexed { s, seg ->
            val where = "loop $l segment $s"
            when (seg) {
                is ProfileSegment.Line -> {
                    point("$where start", seg.start)
                    point("$where end", seg.end)
                    require(dist(seg.start, seg.end) > OcctKernel.PROFILE_POINT_COINCIDENCE_TOLERANCE) {
                        "$where has coincident start and end"
                    }
                }
                is ProfileSegment.Arc -> {
                    point("$where start", seg.start)
                    point("$where mid", seg.mid)
                    point("$where end", seg.end)
                    val a = dist(seg.start, seg.mid)
                    val b = dist(seg.mid, seg.end)
                    val c = dist(seg.start, seg.end)
                    require(
                        a > OcctKernel.PROFILE_POINT_COINCIDENCE_TOLERANCE &&
                            b > OcctKernel.PROFILE_POINT_COINCIDENCE_TOLERANCE,
                    ) {
                        "$where has coincident arc points"
                    }
                    require(c > OcctKernel.PROFILE_POINT_COINCIDENCE_TOLERANCE) {
                        "$where has coincident start and end (use a circle for a full circle)"
                    }
                    val cross =
                        (seg.mid.x - seg.start.x) * (seg.end.y - seg.start.y) -
                            (seg.mid.y - seg.start.y) * (seg.end.x - seg.start.x)
                    require(abs(cross) > COLLINEAR_SINE * a * c) { "$where is collinear, not an arc" }
                }
                is ProfileSegment.Circle -> {
                    point("$where center", seg.center)
                    require(
                        seg.radius.isFinite() &&
                            seg.radius >= OcctKernel.MIN_DIMENSION &&
                            seg.radius <= OcctKernel.MAX_DIMENSION,
                    ) {
                        "$where radius must be within [${OcctKernel.MIN_DIMENSION}, ${OcctKernel.MAX_DIMENSION}], got ${seg.radius}"
                    }
                    require(loop.segments.size == 1) { "$where: a circle must be the only segment of its loop" }
                }
            }
        }
        val n = loop.segments.size
        if (n > 1) {
            for (s in 0 until n) {
                val end = loop.segments[s].endPoint()
                val next = loop.segments[(s + 1) % n].startPoint()
                require(dist(end, next) <= OcctKernel.PROFILE_POINT_COINCIDENCE_TOLERANCE) {
                    "loop $l is not closed/continuous between segment $s and ${(s + 1) % n}"
                }
            }
        }
    }

    private fun point(
        what: String,
        p: ProfilePoint,
    ) {
        require(p.x.isFinite() && p.y.isFinite()) { "$what must be finite, got (${p.x}, ${p.y})" }
        require(abs(p.x) <= OcctKernel.MAX_ABS_COORDINATE && abs(p.y) <= OcctKernel.MAX_ABS_COORDINATE) {
            "$what must be within +/-${OcctKernel.MAX_ABS_COORDINATE}, got (${p.x}, ${p.y})"
        }
    }

    private fun dist(
        a: ProfilePoint,
        b: ProfilePoint,
    ) = hypot(a.x - b.x, a.y - b.y)

    /** Polygon approximation of a loop (arcs and circles sampled). */
    fun polygon(
        loop: ProfileLoop,
        dense: Boolean = false,
    ): List<ProfilePoint> {
        val arcSamples = if (dense) CLEARANCE_SAMPLES else ARC_SAMPLES
        val circleSamples = if (dense) CLEARANCE_SAMPLES else CIRCLE_SAMPLES
        val out = ArrayList<ProfilePoint>()
        for (seg in loop.segments) {
            when (seg) {
                is ProfileSegment.Line -> out.add(seg.start)
                is ProfileSegment.Arc -> out.addAll(sampleArc(seg, arcSamples))
                is ProfileSegment.Circle ->
                    for (i in 0 until circleSamples) {
                        val a = 2.0 * PI * i / circleSamples
                        out.add(ProfilePoint(seg.center.x + seg.radius * cos(a), seg.center.y + seg.radius * sin(a)))
                    }
            }
        }
        return out
    }

    private fun sampleArc(
        arc: ProfileSegment.Arc,
        samples: Int = ARC_SAMPLES,
    ): List<ProfilePoint> {
        val (s, m, e) = Triple(arc.start, arc.mid, arc.end)
        val d = 2.0 * (s.x * (m.y - e.y) + m.x * (e.y - s.y) + e.x * (s.y - m.y))
        val ux =
            (
                (s.x * s.x + s.y * s.y) * (m.y - e.y) + (m.x * m.x + m.y * m.y) * (e.y - s.y) +
                    (e.x * e.x + e.y * e.y) * (s.y - m.y)
            ) /
                d
        val uy =
            (
                (s.x * s.x + s.y * s.y) * (e.x - m.x) + (m.x * m.x + m.y * m.y) * (s.x - e.x) +
                    (e.x * e.x + e.y * e.y) * (m.x - s.x)
            ) /
                d
        val r = hypot(s.x - ux, s.y - uy)
        val a0 = atan2(s.y - uy, s.x - ux)
        val am = atan2(m.y - uy, m.x - ux)
        val a1 = atan2(e.y - uy, e.x - ux)
        // Orientation: counter-clockwise iff (start, mid, end) turn left.
        val ccw = (m.x - s.x) * (e.y - s.y) - (m.y - s.y) * (e.x - s.x) > 0.0
        var sweep = if (ccw) normalizePositive(a1 - a0) else -normalizePositive(a0 - a1)
        // The midpoint must lie on the swept side; guards numerical edge cases.
        val midSweep = if (ccw) normalizePositive(am - a0) else -normalizePositive(a0 - am)
        if (abs(midSweep) > abs(sweep)) sweep = if (ccw) sweep + 2.0 * PI else sweep - 2.0 * PI
        return List(samples) { i ->
            val a = a0 + sweep * i / samples
            ProfilePoint(ux + r * cos(a), uy + r * sin(a))
        }
    }

    private fun normalizePositive(angle: Double): Double {
        var a = angle % (2.0 * PI)
        if (a < 0.0) a += 2.0 * PI
        return a
    }

    fun signedArea(poly: List<ProfilePoint>): Double {
        var sum = 0.0
        for (i in poly.indices) {
            val p = poly[i]
            val q = poly[(i + 1) % poly.size]
            sum += p.x * q.y - q.x * p.y
        }
        return sum / 2.0
    }

    private fun allInside(
        inner: List<ProfilePoint>,
        outer: List<ProfilePoint>,
    ) = inner.all { pointInPolygon(it, outer) }

    private fun pointInPolygon(
        p: ProfilePoint,
        poly: List<ProfilePoint>,
    ): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) inside = !inside
            j = i
        }
        return inside
    }

    private fun crosses(
        a: List<ProfilePoint>,
        b: List<ProfilePoint>,
    ): Boolean {
        if (a.maxOf { it.x } < b.minOf { it.x } || b.maxOf { it.x } < a.minOf { it.x }) return false
        if (a.maxOf { it.y } < b.minOf { it.y } || b.maxOf { it.y } < a.minOf { it.y }) return false
        for (i in a.indices) {
            val p1 = a[i]
            val p2 = a[(i + 1) % a.size]
            for (j in b.indices) {
                if (segmentsIntersect(p1, p2, b[j], b[(j + 1) % b.size])) return true
            }
        }
        return false
    }

    private fun orient(
        a: ProfilePoint,
        b: ProfilePoint,
        c: ProfilePoint,
    ) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun segmentsIntersect(
        a: ProfilePoint,
        b: ProfilePoint,
        c: ProfilePoint,
        d: ProfilePoint,
    ): Boolean {
        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0) && o1 != 0.0 && o2 != 0.0 && o3 != 0.0 && o4 != 0.0
    }

    /** Reverses a loop's direction: segment order reversed, each segment's endpoints swapped. A circle is unchanged. */
    private fun reversed(loop: ProfileLoop): ProfileLoop =
        ProfileLoop(
            loop.segments.reversed().map { seg ->
                when (seg) {
                    is ProfileSegment.Line -> ProfileSegment.Line(seg.end, seg.start)
                    is ProfileSegment.Arc -> ProfileSegment.Arc(seg.end, seg.mid, seg.start)
                    is ProfileSegment.Circle -> seg
                }
            },
        )

    private fun isCircle(loop: ProfileLoop) = loop.segments.size == 1 && loop.segments[0] is ProfileSegment.Circle

    /** Outer loop counter-clockwise, holes clockwise (circle loops carry their direction implicitly). */
    fun normalized(region: ProfileRegion): ProfileRegion {
        val outer =
            if (isCircle(region.outer) ||
                signedArea(polygon(region.outer)) > 0.0
            ) {
                region.outer
            } else {
                reversed(region.outer)
            }
        val holes = region.holes.map { h -> if (isCircle(h) || signedArea(polygon(h)) < 0.0) h else reversed(h) }
        return ProfileRegion(outer, holes)
    }

    /** Native region encoding: loop sizes, segment kinds (0 line, 1 arc, 2 circle) and 6 doubles per segment. */
    class Encoded(
        val loopSizes: IntArray,
        val kinds: IntArray,
        val data: DoubleArray,
    )

    fun encode(region: ProfileRegion): Encoded {
        val loops = listOf(region.outer) + region.holes
        val segs = loops.flatMap { it.segments }
        val kinds = IntArray(segs.size)
        val data = DoubleArray(segs.size * 6)
        segs.forEachIndexed { i, seg ->
            val b = i * 6
            when (seg) {
                is ProfileSegment.Line -> {
                    kinds[i] = 0
                    data[b] = seg.start.x
                    data[b + 1] = seg.start.y
                    data[b + 2] = seg.end.x
                    data[b + 3] = seg.end.y
                }
                is ProfileSegment.Arc -> {
                    kinds[i] = 1
                    data[b] = seg.start.x
                    data[b + 1] = seg.start.y
                    data[b + 2] = seg.mid.x
                    data[b + 3] = seg.mid.y
                    data[b + 4] = seg.end.x
                    data[b + 5] = seg.end.y
                }
                is ProfileSegment.Circle -> {
                    kinds[i] = 2
                    data[b] = seg.center.x
                    data[b + 1] = seg.center.y
                    data[b + 2] = seg.radius
                }
            }
        }
        return Encoded(IntArray(loops.size) { loops[it].segments.size }, kinds, data)
    }
}

/** Test seam: the orientation-normalised form of this region (outer counter-clockwise, holes clockwise). */
internal fun ProfileRegion.normalizedForOcct(): ProfileRegion = RegionValidation.normalized(this)
