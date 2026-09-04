package dev.kstep.geometry

import kotlin.math.cos
import kotlin.math.sin

/**
 * A rigid-body transform (rotation + translation, no scaling, no reflection) applied to a
 * [TriangleMesh]'s coordinates -- see [TriangleMesh.transformedBy] and [MeshComposition]. Stored
 * as 12 [Double]s: a row-major 3x3 rotation matrix (indices 0..8, `m[3*row + col]`) followed by
 * a 3-component translation (indices 9..11).
 *
 * Deliberately closed: the only ways to build one are [IDENTITY], [translation], [rotationX],
 * [rotationY], [rotationZ] and [then] (composing two of those) -- there is no public matrix
 * constructor and no scaling primitive. This is a scope decision, not an oversight (see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`): every [Placement] this type
 * can produce has determinant exactly `+1` by construction (translation and each axis rotation do,
 * and matrix multiplication of two determinant-`+1` matrices stays determinant-`+1`), so there is
 * no `require(det > 0)` guard here -- there is nothing for it to catch. A scaling primitive would
 * reopen the reflection question (a negative scale factor flips a mesh's winding, silently
 * breaking [MeshProjection]'s backface culling) that this closed set avoids by construction; see
 * this type's Folge-Wellen entry in that ADR for what reopening it would require.
 *
 * `internal fun apply` (not `private`): [TriangleMesh.transformedBy], in [MeshComposition.kt],
 * needs to transform each of a mesh's vertices individually -- exposing the full [m] array itself
 * would leak this type's row-major storage choice; exposing just point application does not.
 *
 * Every [Placement] this type can produce has determinant exactly `+1` and applies no scaling (see
 * above) -- so for a *direction* vector (a vertex normal, not a point), the correct transform is
 * the SAME rotation block [apply] already uses, with the translation dropped: no separate
 * inverse-transpose normal matrix is needed (that machinery exists only to correct for
 * non-uniform scaling and reflection, neither of which this closed [Placement] set can ever
 * produce), and a unit-length input stays exactly unit length. See [applyToDirection].
 */
class Placement private constructor(
    private val m: DoubleArray,
) {
    init {
        require(m.size == 12) { "Placement's internal matrix must have exactly 12 entries, got ${m.size}" }
    }

    /**
     * Composes this transform with [next]: a point is transformed by `this` FIRST, then by
     * [next] -- i.e. `next.then` is applied to the world, not the other way around. Matches the
     * reading order `partPlacement.then(assemblyPlacement)` for "place the part, then place the
     * assembly it belongs to".
     */
    fun then(next: Placement): Placement {
        val combinedRotation = multiply3x3(next.m, m)
        val rotatedTranslation = applyRotation(next.m, m[9], m[10], m[11])
        val combined =
            combinedRotation +
                doubleArrayOf(
                    rotatedTranslation[0] + next.m[9],
                    rotatedTranslation[1] + next.m[10],
                    rotatedTranslation[2] + next.m[11],
                )
        return Placement(combined)
    }

    /** Applies this transform to one point, returning `[x', y', z']`. See this class's KDoc for
     *  why this is `internal`, not `private`. */
    internal fun apply(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray {
        val rotated = applyRotation(m, x, y, z)
        return doubleArrayOf(rotated[0] + m[9], rotated[1] + m[10], rotated[2] + m[11])
    }

    /**
     * Applies ONLY this transform's rotation block to one direction vector `(x, y, z)`, returning
     * `[x', y', z']` -- for a vertex normal, which must rotate along with a mesh but must NEVER be
     * translated by it. See this class's KDoc above for why no inverse-transpose normal matrix is
     * needed here. `internal`, same visibility rationale as [apply].
     */
    internal fun applyToDirection(
        x: Double,
        y: Double,
        z: Double,
    ): DoubleArray = applyRotation(m, x, y, z)

    companion object {
        /** The no-op transform: rotation = identity, translation = zero. */
        val IDENTITY: Placement = Placement(identityMatrix() + doubleArrayOf(0.0, 0.0, 0.0))

        fun translation(
            dx: Double,
            dy: Double,
            dz: Double,
        ): Placement = Placement(identityMatrix() + doubleArrayOf(dx, dy, dz))

        /** Rotation of [degrees] around the X axis (right-hand rule), no translation. */
        fun rotationX(degrees: Double): Placement {
            val rad = Math.toRadians(degrees)
            val c = cos(rad)
            val s = sin(rad)
            val rotation =
                doubleArrayOf(
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    c,
                    -s,
                    0.0,
                    s,
                    c,
                )
            return Placement(rotation + doubleArrayOf(0.0, 0.0, 0.0))
        }

        /** Rotation of [degrees] around the Y axis (right-hand rule), no translation. */
        fun rotationY(degrees: Double): Placement {
            val rad = Math.toRadians(degrees)
            val c = cos(rad)
            val s = sin(rad)
            val rotation =
                doubleArrayOf(
                    c,
                    0.0,
                    s,
                    0.0,
                    1.0,
                    0.0,
                    -s,
                    0.0,
                    c,
                )
            return Placement(rotation + doubleArrayOf(0.0, 0.0, 0.0))
        }

        /** Rotation of [degrees] around the Z axis (right-hand rule), no translation. */
        fun rotationZ(degrees: Double): Placement {
            val rad = Math.toRadians(degrees)
            val c = cos(rad)
            val s = sin(rad)
            val rotation =
                doubleArrayOf(
                    c,
                    -s,
                    0.0,
                    s,
                    c,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                )
            return Placement(rotation + doubleArrayOf(0.0, 0.0, 0.0))
        }

        private fun identityMatrix(): DoubleArray =
            doubleArrayOf(
                1.0,
                0.0,
                0.0,
                0.0,
                1.0,
                0.0,
                0.0,
                0.0,
                1.0,
            )

        /** Row-major 3x3 matrix product `a * b` (both length-12 [Placement.m] arrays -- only the
         *  first 9 entries, the rotation block, are read or written). */
        private fun multiply3x3(
            a: DoubleArray,
            b: DoubleArray,
        ): DoubleArray {
            val result = DoubleArray(9)
            for (row in 0 until 3) {
                for (col in 0 until 3) {
                    var sum = 0.0
                    for (k in 0 until 3) {
                        sum += a[row * 3 + k] * b[k * 3 + col]
                    }
                    result[row * 3 + col] = sum
                }
            }
            return result
        }

        /** Applies only the rotation block of a length-12 [Placement.m] array to `(x, y, z)`,
         *  ignoring its translation entries (indices 9..11). */
        private fun applyRotation(
            m: DoubleArray,
            x: Double,
            y: Double,
            z: Double,
        ): DoubleArray =
            doubleArrayOf(
                m[0] * x + m[1] * y + m[2] * z,
                m[3] * x + m[4] * y + m[5] * z,
                m[6] * x + m[7] * y + m[8] * z,
            )
    }
}
