package dev.kstep.sketch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Outcome of a degrees-of-freedom analysis.
 *
 * @property unknowns number of free coordinates (2 per non-fixed point) plus free scalars (radii).
 * @property rank rank of the constraint Jacobian at the analysed configuration.
 * @property degreesOfFreedom `unknowns - rank`.
 * @property redundant labels of constraints that are linearly dependent on EARLIER ones, in input order.
 * @property movablePoints indices of points that can still move (a null-space component above tolerance).
 * @property movableScalars indices of scalars (radii) that can still change.
 */
internal class DofResult(
    val unknowns: Int,
    val rank: Int,
    val redundantConstraints: List<Int>,
    val movablePoints: List<Int>,
    val movableScalars: List<Int> = emptyList(),
) {
    val degreesOfFreedom: Int get() = unknowns - rank
}

/**
 * Degrees-of-freedom analysis of a 2D constraint system in plain Kotlin, independent of PlaneGCS.
 *
 * The unknowns are the coordinates of the non-fixed points; every constraint contributes analytic
 * residual rows (coincidence: 2, all others: 1). The rank of their Jacobian is computed by
 * incremental Gram-Schmidt in INPUT ORDER, so the constraint that first makes the system
 * dependent is the one reported -- deterministic and easy to explain ("constraint 7 is redundant
 * given constraints 0-6"). Matrix size is bounded by the sketch limits (at most 256 rows by 128
 * columns).
 */
internal object DofAnalysis {
    private const val DEPENDENCE_TOLERANCE = 1e-7
    private const val ZERO_ROW = 1e-12
    private const val MOVABLE_TOLERANCE = 1e-6

    /**
     * Residuals of [constraint] at [coords]: x,y interleaved per point (the first `2 * pointCount` entries),
     * followed by the scalar unknowns (radii).
     */
    fun residual(
        constraint: SketchConstraintData,
        coords: DoubleArray,
        pointCount: Int = coords.size / 2,
    ): DoubleArray {
        fun x(i: Int) = coords[2 * i]

        fun y(i: Int) = coords[2 * i + 1]

        fun sc(i: Int) = coords[2 * pointCount + i]
        return when (constraint) {
            is CoincidentData -> doubleArrayOf(x(constraint.a) - x(constraint.b), y(constraint.a) - y(constraint.b))
            is DistanceData ->
                doubleArrayOf(
                    hypot(x(constraint.b) - x(constraint.a), y(constraint.b) - y(constraint.a)) - constraint.distance,
                )
            is HorizontalData -> doubleArrayOf(y(constraint.a) - y(constraint.b))
            is VerticalData -> doubleArrayOf(x(constraint.a) - x(constraint.b))
            is PointOnLineData ->
                doubleArrayOf(
                    (x(constraint.b) - x(constraint.a)) * (y(constraint.p) - y(constraint.a)) -
                        (y(constraint.b) - y(constraint.a)) * (x(constraint.p) - x(constraint.a)),
                )
            is ParallelData ->
                doubleArrayOf(
                    (x(constraint.b1) - x(constraint.a1)) * (y(constraint.b2) - y(constraint.a2)) -
                        (y(constraint.b1) - y(constraint.a1)) * (x(constraint.b2) - x(constraint.a2)),
                )
            is PerpendicularData ->
                doubleArrayOf(
                    (x(constraint.b1) - x(constraint.a1)) * (x(constraint.b2) - x(constraint.a2)) +
                        (y(constraint.b1) - y(constraint.a1)) * (y(constraint.b2) - y(constraint.a2)),
                )
            is RadiusData -> doubleArrayOf(sc(constraint.scalar) - constraint.value)
            is EqualRadiusData -> doubleArrayOf(sc(constraint.a) - sc(constraint.b))
            is PointOnCircleData ->
                doubleArrayOf(
                    hypot(x(constraint.p) - x(constraint.center), y(constraint.p) - y(constraint.center)) -
                        sc(constraint.scalar),
                )
            is TangentLineData -> {
                val len = hypot(x(constraint.to) - x(constraint.from), y(constraint.to) - y(constraint.from))
                val cross = crossOf(constraint.from, constraint.to, constraint.center, ::x, ::y)
                doubleArrayOf((if (len > ZERO_ROW) abs(cross) / len else 0.0) - sc(constraint.scalar))
            }
            is TangentCirclesData -> {
                val d = hypot(x(constraint.c1) - x(constraint.c2), y(constraint.c1) - y(constraint.c2))
                val s1 = sc(constraint.s1)
                val s2 = sc(constraint.s2)
                doubleArrayOf(d - (if (constraint.internal) abs(s1 - s2) else s1 + s2))
            }
            is AngleData -> {
                val ux = x(constraint.b1) - x(constraint.a1)
                val uy = y(constraint.b1) - y(constraint.a1)
                val vx = x(constraint.b2) - x(constraint.a2)
                val vy = y(constraint.b2) - y(constraint.a2)
                doubleArrayOf(wrapAngle(atan2(vy, vx) - atan2(uy, ux) - constraint.radians))
            }
        }
    }

    private fun crossOf(
        a: Int,
        b: Int,
        p: Int,
        x: (Int) -> Double,
        y: (Int) -> Double,
    ): Double = (x(b) - x(a)) * (y(p) - y(a)) - (y(b) - y(a)) * (x(p) - x(a))

    private fun wrapAngle(angle: Double): Double {
        var a = angle
        while (a > PI) a -= 2.0 * PI
        while (a < -PI) a += 2.0 * PI
        return a
    }

    /**
     * Sparse Jacobian rows of [constraint]: each row maps a state slot (2*point, 2*point+1, or
     * 2*pointCount+scalar) to its partial derivative.
     */
    fun jacobianRows(
        constraint: SketchConstraintData,
        coords: DoubleArray,
        pointCount: Int = coords.size / 2,
    ): List<Map<Int, Double>> {
        fun x(i: Int) = coords[2 * i]

        fun y(i: Int) = coords[2 * i + 1]

        fun isc(i: Int) = 2 * pointCount + i

        fun sc(i: Int) = coords[2 * pointCount + i]

        fun row(vararg entries: Pair<Int, Double>): Map<Int, Double> {
            val m = HashMap<Int, Double>()
            for ((slot, v) in entries) m[slot] = (m[slot] ?: 0.0) + v
            return m
        }

        fun ix(i: Int) = 2 * i

        fun iy(i: Int) = 2 * i + 1
        return when (constraint) {
            is CoincidentData ->
                listOf(
                    row(ix(constraint.a) to 1.0, ix(constraint.b) to -1.0),
                    row(iy(constraint.a) to 1.0, iy(constraint.b) to -1.0),
                )
            is DistanceData -> {
                val dx = x(constraint.b) - x(constraint.a)
                val dy = y(constraint.b) - y(constraint.a)
                val l = hypot(dx, dy)
                val ux = if (l > ZERO_ROW) dx / l else 0.0
                val uy = if (l > ZERO_ROW) dy / l else 0.0
                listOf(
                    row(
                        ix(constraint.a) to -ux,
                        iy(constraint.a) to -uy,
                        ix(constraint.b) to ux,
                        iy(constraint.b) to uy,
                    ),
                )
            }
            is HorizontalData -> listOf(row(iy(constraint.a) to 1.0, iy(constraint.b) to -1.0))
            is VerticalData -> listOf(row(ix(constraint.a) to 1.0, ix(constraint.b) to -1.0))
            is PointOnLineData -> {
                val a = constraint.a
                val b = constraint.b
                val p = constraint.p
                listOf(
                    row(
                        ix(p) to -(y(b) - y(a)),
                        iy(p) to (x(b) - x(a)),
                        ix(a) to -(y(p) - y(a)) + (y(b) - y(a)),
                        iy(a) to -(x(b) - x(a)) + (x(p) - x(a)),
                        ix(b) to (y(p) - y(a)),
                        iy(b) to -(x(p) - x(a)),
                    ),
                )
            }
            is ParallelData -> {
                val ux = x(constraint.b1) - x(constraint.a1)
                val uy = y(constraint.b1) - y(constraint.a1)
                val vx = x(constraint.b2) - x(constraint.a2)
                val vy = y(constraint.b2) - y(constraint.a2)
                listOf(
                    row(
                        ix(constraint.b1) to vy,
                        ix(constraint.a1) to -vy,
                        iy(constraint.b1) to -vx,
                        iy(constraint.a1) to vx,
                        iy(constraint.b2) to ux,
                        iy(constraint.a2) to -ux,
                        ix(constraint.b2) to -uy,
                        ix(constraint.a2) to uy,
                    ),
                )
            }
            is PerpendicularData -> {
                val ux = x(constraint.b1) - x(constraint.a1)
                val uy = y(constraint.b1) - y(constraint.a1)
                val vx = x(constraint.b2) - x(constraint.a2)
                val vy = y(constraint.b2) - y(constraint.a2)
                listOf(
                    row(
                        ix(constraint.b1) to vx,
                        ix(constraint.a1) to -vx,
                        iy(constraint.b1) to vy,
                        iy(constraint.a1) to -vy,
                        ix(constraint.b2) to ux,
                        ix(constraint.a2) to -ux,
                        iy(constraint.b2) to uy,
                        iy(constraint.a2) to -uy,
                    ),
                )
            }
            is RadiusData -> listOf(row(isc(constraint.scalar) to 1.0))
            is EqualRadiusData -> listOf(row(isc(constraint.a) to 1.0, isc(constraint.b) to -1.0))
            is PointOnCircleData -> {
                val dx = x(constraint.p) - x(constraint.center)
                val dy = y(constraint.p) - y(constraint.center)
                val l = hypot(dx, dy)
                val ux = if (l > ZERO_ROW) dx / l else 0.0
                val uy = if (l > ZERO_ROW) dy / l else 0.0
                listOf(
                    row(
                        ix(constraint.p) to ux,
                        iy(constraint.p) to uy,
                        ix(constraint.center) to -ux,
                        iy(constraint.center) to -uy,
                        isc(constraint.scalar) to -1.0,
                    ),
                )
            }
            is TangentLineData -> {
                val a = constraint.from
                val b = constraint.to
                val p = constraint.center
                val dx = x(b) - x(a)
                val dy = y(b) - y(a)
                val len = hypot(dx, dy)
                val cross = crossOf(a, b, p, ::x, ::y)
                if (len <= ZERO_ROW) {
                    listOf(row(isc(constraint.scalar) to -1.0))
                } else {
                    val sigma = if (cross < 0.0) -1.0 else 1.0
                    // d|C|/L = sigma * (dC / L - C * dL / L^2)
                    val k = sigma / len
                    val m = sigma * cross / (len * len * len)
                    listOf(
                        row(
                            ix(p) to -k * dy,
                            iy(p) to k * dx,
                            ix(a) to k * (-(y(p) - y(a)) + dy) + m * dx,
                            iy(a) to k * (-dx + (x(p) - x(a))) + m * dy,
                            ix(b) to k * (y(p) - y(a)) - m * dx,
                            iy(b) to -k * (x(p) - x(a)) - m * dy,
                            isc(constraint.scalar) to -1.0,
                        ),
                    )
                }
            }
            is TangentCirclesData -> {
                val dx = x(constraint.c1) - x(constraint.c2)
                val dy = y(constraint.c1) - y(constraint.c2)
                val l = hypot(dx, dy)
                val ux = if (l > ZERO_ROW) dx / l else 0.0
                val uy = if (l > ZERO_ROW) dy / l else 0.0
                val sigma = if (sc(constraint.s1) - sc(constraint.s2) < 0.0) -1.0 else 1.0
                val g1 = if (constraint.internal) -sigma else -1.0
                val g2 = if (constraint.internal) sigma else -1.0
                listOf(
                    row(
                        ix(constraint.c1) to ux,
                        iy(constraint.c1) to uy,
                        ix(constraint.c2) to -ux,
                        iy(constraint.c2) to -uy,
                        isc(constraint.s1) to g1,
                        isc(constraint.s2) to g2,
                    ),
                )
            }
            is AngleData -> {
                val ux = x(constraint.b1) - x(constraint.a1)
                val uy = y(constraint.b1) - y(constraint.a1)
                val vx = x(constraint.b2) - x(constraint.a2)
                val vy = y(constraint.b2) - y(constraint.a2)
                val lu = ux * ux + uy * uy
                val lv = vx * vx + vy * vy
                if (lu <= ZERO_ROW || lv <= ZERO_ROW) {
                    listOf(row())
                } else {
                    // d atan2(y, x) = (-y dx + x dy) / (x^2 + y^2); the residual is atan2(v) - atan2(u).
                    listOf(
                        row(
                            ix(constraint.b2) to -vy / lv,
                            iy(constraint.b2) to vx / lv,
                            ix(constraint.a2) to vy / lv,
                            iy(constraint.a2) to -vx / lv,
                            ix(constraint.b1) to uy / lu,
                            iy(constraint.b1) to -ux / lu,
                            ix(constraint.a1) to -uy / lu,
                            iy(constraint.a1) to ux / lu,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Analyses [constraints] over the points described by [coords] (x,y interleaved) and [fixed].
     * The Jacobian is evaluated at [coords].
     */
    fun analyze(
        coords: DoubleArray,
        fixed: BooleanArray,
        constraints: List<SketchConstraintData>,
        fixedScalars: BooleanArray = BooleanArray(0),
    ): DofResult {
        val pointCount = fixed.size
        val scalarCount = fixedScalars.size
        require(coords.size == pointCount * 2 + scalarCount) {
            "coords must hold two values per point followed by one per scalar"
        }
        // Column index of each state slot (points, then scalars), -1 for fixed ones.
        val column = IntArray(pointCount * 2 + scalarCount) { -1 }
        var unknowns = 0
        for (p in 0 until pointCount) {
            if (!fixed[p]) {
                column[2 * p] = unknowns++
                column[2 * p + 1] = unknowns++
            }
        }
        for (k in 0 until scalarCount) {
            if (!fixedScalars[k]) column[2 * pointCount + k] = unknowns++
        }
        val basis = ArrayList<DoubleArray>()
        val redundant = ArrayList<Int>()
        constraints.forEachIndexed { index, constraint ->
            var dependent = false
            for (sparse in jacobianRows(constraint, coords, pointCount)) {
                val row = DoubleArray(unknowns)
                for ((slot, value) in sparse) if (column[slot] >= 0) row[column[slot]] += value
                val norm = norm(row)
                if (norm < ZERO_ROW) {
                    dependent = true
                    continue
                }
                for (i in row.indices) row[i] /= norm
                // Two passes of modified Gram-Schmidt for numerical stability.
                repeat(2) {
                    for (b in basis) {
                        val dot = dot(row, b)
                        for (i in row.indices) row[i] -= dot * b[i]
                    }
                }
                val rest = norm(row)
                if (rest < DEPENDENCE_TOLERANCE) {
                    dependent = true
                } else {
                    for (i in row.indices) row[i] /= rest
                    basis.add(row)
                }
            }
            if (dependent) redundant.add(index)
        }
        val moving = movable(basis, column, pointCount, scalarCount, unknowns)
        return DofResult(unknowns, basis.size, redundant, moving.first, moving.second)
    }

    /** Null-space of the constraint rows, projected back to the points that have a component in it. */
    private fun movable(
        rowBasis: List<DoubleArray>,
        column: IntArray,
        pointCount: Int,
        scalarCount: Int,
        unknowns: Int,
    ): Pair<List<Int>, List<Int>> {
        if (unknowns == 0 || rowBasis.size >= unknowns) return Pair(emptyList(), emptyList())
        val nullBasis = ArrayList<DoubleArray>()
        for (j in 0 until unknowns) {
            val v = DoubleArray(unknowns)
            v[j] = 1.0
            repeat(2) {
                for (b in rowBasis) {
                    val d = dot(v, b)
                    for (i in v.indices) v[i] -= d * b[i]
                }
                for (b in nullBasis) {
                    val d = dot(v, b)
                    for (i in v.indices) v[i] -= d * b[i]
                }
            }
            val n = norm(v)
            if (n > 1e-6) {
                for (i in v.indices) v[i] /= n
                nullBasis.add(v)
            }
        }
        val moving = ArrayList<Int>()
        for (p in 0 until pointCount) {
            val cx = column[2 * p]
            if (cx < 0) continue
            if (nullBasis.any { abs(it[cx]) > MOVABLE_TOLERANCE || abs(it[cx + 1]) > MOVABLE_TOLERANCE }) moving.add(p)
        }
        val movingScalars = ArrayList<Int>()
        for (k in 0 until scalarCount) {
            val c = column[2 * pointCount + k]
            if (c >= 0 && nullBasis.any { abs(it[c]) > MOVABLE_TOLERANCE }) movingScalars.add(k)
        }
        return Pair(moving, movingScalars)
    }

    private fun dot(
        a: DoubleArray,
        b: DoubleArray,
    ): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun norm(a: DoubleArray) = sqrt(dot(a, a))
}
