package dev.kstep.shape

import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

// Pure placement algebra for assembly components (ADR-0028). No OCCT call anywhere in this
// file, so everything here is testable without the native bridge.

/** A 3-vector. All components must be finite wherever a [Vec3] is validated (see [Rotation], [RigidPlacement]). */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun times(s: Double): Vec3 = Vec3(x * s, y * s, z * s)

    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun norm(): Double = sqrt(this dot this)

    internal fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val X = Vec3(1.0, 0.0, 0.0)
        val Y = Vec3(0.0, 1.0, 0.0)
        val Z = Vec3(0.0, 0.0, 1.0)
    }
}

/** DSL convenience: `vec(30.0, 5.0, 10.0)`. */
fun vec(
    x: Double,
    y: Double,
    z: Double,
): Vec3 = Vec3(x, y, z)

/**
 * A rigid-body rotation (orthonormal, right-handed, determinant +1) stored the STEP-native way as
 * the images of the local Z axis ([axis]) and the local X axis ([refDirection]) in the parent
 * frame -- exactly `AXIS2_PLACEMENT_3D.axis` and `.ref_direction`. Scaling and mirroring are
 * unrepresentable: the third axis is always `axis x refDirection`.
 */
class Rotation private constructor(
    /** Local Z axis expressed in the parent frame (unit length). */
    val axis: Vec3,
    /** Local X axis expressed in the parent frame (unit length, orthogonal to [axis]). */
    val refDirection: Vec3,
) {
    /** Local Y axis in the parent frame: `axis x refDirection`. */
    val yDirection: Vec3 get() = axis cross refDirection

    /** Rotates [v] (a vector or point relative to the rotation centre). */
    fun apply(v: Vec3): Vec3 = refDirection * v.x + yDirection * v.y + axis * v.z

    /** `this` after [inner]: the rotation that first applies [inner], then `this`. */
    fun then(inner: Rotation): Rotation = of(apply(inner.axis), apply(inner.refDirection))

    override fun equals(other: Any?): Boolean =
        other is Rotation && other.axis == axis && other.refDirection == refDirection

    override fun hashCode(): Int = 31 * axis.hashCode() + refDirection.hashCode()

    override fun toString(): String = "Rotation(axis=$axis, refDirection=$refDirection)"

    companion object {
        private const val TOLERANCE = 1e-9

        val IDENTITY: Rotation = Rotation(Vec3.Z, Vec3.X)

        /**
         * Rotation by [degrees] about [axis] (right-hand rule). [axis] need not be unit length but
         * must be non-zero and finite. Multiples of 90 degrees are computed exactly (no
         * `6.1e-17` residue in the written file).
         */
        fun about(
            axis: Vec3,
            degrees: Double,
        ): Rotation {
            require(axis.isFinite()) { "rotation axis must be finite: $axis" }
            require(degrees.isFinite()) { "rotation angle must be finite: $degrees" }
            val length = axis.norm()
            require(length > 0.0 && length.isFinite()) { "rotation axis must not be the zero vector" }
            val k = axis * (1.0 / length)
            val quarterTurns = Math.floorMod(Math.round(degrees / 90.0), 4L).toInt()
            val exactQuarter = abs(degrees / 90.0 - Math.rint(degrees / 90.0)) < 1e-12
            val (c, s) =
                if (exactQuarter) {
                    when (quarterTurns) {
                        0 -> 1.0 to 0.0
                        1 -> 0.0 to 1.0
                        2 -> -1.0 to 0.0
                        else -> 0.0 to -1.0
                    }
                } else {
                    val radians = degrees * PI / 180.0
                    cos(radians) to sin(radians)
                }

            // Rodrigues: R v = v c + (k x v) s + k (k . v)(1 - c)
            fun rot(v: Vec3): Vec3 = v * c + (k cross v) * s + k * ((k dot v) * (1.0 - c))
            return of(rot(Vec3.Z), rot(Vec3.X))
        }

        /**
         * STEP-native construction from `AXIS2_PLACEMENT_3D`'s [axis] and [refDirection]. Rejects
         * (rather than silently normalising) vectors that are not unit length or not orthogonal.
         */
        fun fromAxes(
            axis: Vec3,
            refDirection: Vec3,
        ): Rotation {
            require(axis.isFinite() && refDirection.isFinite()) { "axis and refDirection must be finite" }
            require(abs(axis.norm() - 1.0) <= TOLERANCE) {
                "axis must have unit length (|axis| = ${axis.norm()}); normalise it explicitly"
            }
            require(abs(refDirection.norm() - 1.0) <= TOLERANCE) {
                "refDirection must have unit length (|refDirection| = ${refDirection.norm()}); normalise it explicitly"
            }
            require(abs(axis dot refDirection) <= TOLERANCE) {
                "axis and refDirection must be orthogonal (dot = ${axis dot refDirection})"
            }
            return Rotation(axis, refDirection)
        }

        // Cleans -0.0 (x + 0.0 == 0.0 for x == -0.0) and re-validates the result of an algebraic construction.
        private fun of(
            axis: Vec3,
            refDirection: Vec3,
        ): Rotation = fromAxes(clean(axis), clean(refDirection))

        private fun clean(v: Vec3): Vec3 = Vec3(v.x + 0.0, v.y + 0.0, v.z + 0.0)
    }
}

/**
 * A rigid transformation from a component's own coordinate system into its parent assembly's:
 * `pParent = R * pChild + translation`. Translation is in millimetres (the unit of every
 * OCCT-produced representation context).
 */
data class RigidPlacement(
    val translation: Vec3 = Vec3.ZERO,
    val rotation: Rotation = Rotation.IDENTITY,
) {
    init {
        require(translation.isFinite()) { "translation must be finite: $translation" }
        require(
            abs(translation.x) <= MAX_TRANSLATION_MM &&
                abs(translation.y) <= MAX_TRANSLATION_MM &&
                abs(translation.z) <= MAX_TRANSLATION_MM,
        ) { "translation component exceeds the plausibility limit of $MAX_TRANSLATION_MM mm: $translation" }
    }

    /** Maps a point given in the child's frame to the parent's frame. */
    fun apply(p: Vec3): Vec3 = rotation.apply(p) + translation

    /** `this` after [inner] (`this.apply(inner.apply(p))`): the placement of a nested component in the outermost frame. */
    fun then(inner: RigidPlacement): RigidPlacement =
        RigidPlacement(apply(inner.translation), rotation.then(inner.rotation))

    val isIdentity: Boolean get() = this == IDENTITY

    companion object {
        /** DoS/plausibility bound for a translation component, in millimetres. */
        const val MAX_TRANSLATION_MM: Double = 1e9

        val IDENTITY = RigidPlacement()
    }
}

/** A placement bound to one assembly usage; produced by the script DSL's `place(...)`. */
data class UsagePlacement(
    val usage: NextAssemblyUsageOccurrence,
    val placement: RigidPlacement,
)

/**
 * Formats a [Double] as a Part-21 REAL lexeme: always contains a decimal point (never the INTEGER
 * lexeme `40`), never `-0.0`. Matches the renderer's `NUMBER_LEXEME` grammar.
 */
internal fun stepReal(d: Double): String {
    require(d.isFinite()) { "cannot write a non-finite number to a STEP file: $d" }
    if (d == 0.0) return "0."
    return d.toString()
}
