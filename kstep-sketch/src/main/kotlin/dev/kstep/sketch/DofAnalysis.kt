package dev.kstep.sketch

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Outcome of a degrees-of-freedom analysis.
 *
 * @property unknowns number of free coordinates (2 per non-fixed point).
 * @property rank rank of the constraint Jacobian at the analysed configuration.
 * @property degreesOfFreedom `unknowns - rank`.
 * @property redundant labels of constraints that are linearly dependent on EARLIER ones, in input order.
 * @property movablePoints indices of points that can still move (a null-space component above tolerance).
 */
internal class DofResult(
    val unknowns: Int,
    val rank: Int,
    val redundantConstraints: List<Int>,
    val movablePoints: List<Int>,
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

    /** Residuals of [constraint] at [coords] (x,y interleaved per point). */
    fun residual(
        constraint: SketchConstraintData,
        coords: DoubleArray,
    ): DoubleArray {
        fun x(i: Int) = coords[2 * i]

        fun y(i: Int) = coords[2 * i + 1]
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
        }
    }

    /** Sparse Jacobian rows of [constraint]: each row maps a coordinate slot (2*point or 2*point+1) to its partial derivative. */
    fun jacobianRows(
        constraint: SketchConstraintData,
        coords: DoubleArray,
    ): List<Map<Int, Double>> {
        fun x(i: Int) = coords[2 * i]

        fun y(i: Int) = coords[2 * i + 1]

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
    ): DofResult {
        val pointCount = fixed.size
        require(coords.size == pointCount * 2) { "coords must hold two values per point" }
        // Column index of each coordinate slot, -1 for fixed points.
        val column = IntArray(pointCount * 2) { -1 }
        var unknowns = 0
        for (p in 0 until pointCount) {
            if (!fixed[p]) {
                column[2 * p] = unknowns++
                column[2 * p + 1] = unknowns++
            }
        }
        val basis = ArrayList<DoubleArray>()
        val redundant = ArrayList<Int>()
        constraints.forEachIndexed { index, constraint ->
            var dependent = false
            for (sparse in jacobianRows(constraint, coords)) {
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
        return DofResult(unknowns, basis.size, redundant, movablePoints(basis, column, pointCount, unknowns))
    }

    /** Null-space of the constraint rows, projected back to the points that have a component in it. */
    private fun movablePoints(
        rowBasis: List<DoubleArray>,
        column: IntArray,
        pointCount: Int,
        unknowns: Int,
    ): List<Int> {
        if (unknowns == 0 || rowBasis.size >= unknowns) return emptyList()
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
        return moving
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
