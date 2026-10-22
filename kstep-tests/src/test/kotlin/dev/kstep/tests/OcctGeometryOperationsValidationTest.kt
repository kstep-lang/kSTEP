package dev.kstep.tests

import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.Placement
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.RevolveAxis
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec

/**
 * Welle J (ADR-0029): the Kotlin-side validation of the new geometry operations. Deliberately NOT
 * gated on OCCT: every input below is rejected BEFORE the availability check (and before any
 * native call), so these cases hold on a machine without OCCT too.
 */
class OcctGeometryOperationsValidationTest :
    StringSpec({
        val square =
            listOf(ProfilePoint(1.0, 0.0), ProfilePoint(2.0, 0.0), ProfilePoint(2.0, 1.0), ProfilePoint(1.0, 1.0))

        "makeCylinder rejects non-finite or out-of-range radius and height" {
            listOf(
                0.0,
                -1.0,
                OcctKernel.MIN_DIMENSION,
                Double.NaN,
                Double.POSITIVE_INFINITY,
                OcctKernel.MAX_DIMENSION * 2,
            ).forEach { bad ->
                shouldThrow<IllegalArgumentException> { OcctKernel.makeCylinder(bad, 1.0) }
                shouldThrow<IllegalArgumentException> { OcctKernel.makeCylinder(1.0, bad) }
            }
        }

        "makeCylinder rejects a bad origin and a null or non-finite direction" {
            shouldThrow<IllegalArgumentException> { OcctKernel.makeCylinder(1.0, 1.0, x = Double.NaN) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.makeCylinder(
                    1.0,
                    1.0,
                    y =
                        OcctKernel.MAX_ABS_COORDINATE * 2,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.makeCylinder(
                    1.0,
                    1.0,
                    dirX = 0.0,
                    dirY = 0.0,
                    dirZ = 0.0,
                )
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeCylinder(1.0, 1.0, dirZ = 1e-12) }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeCylinder(1.0, 1.0, dirX = Double.POSITIVE_INFINITY) }
        }

        "revolveProfile rejects bad angles" {
            listOf(0.0, -10.0, Double.NaN, 360.0001, Double.POSITIVE_INFINITY).forEach { angle ->
                shouldThrow<IllegalArgumentException> { OcctKernel.revolveProfile(square, angle) }
            }
        }

        "revolveProfile rejects profiles with too few or too many points and coincident points" {
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveProfile(square.take(2)) }
            val big =
                (0..OcctKernel.MAX_REVOLVE_PROFILE_POINTS).map {
                    val a = 2.0 * Math.PI * it / (OcctKernel.MAX_REVOLVE_PROFILE_POINTS + 1)
                    ProfilePoint(10.0 + kotlin.math.cos(a), kotlin.math.sin(a))
                }
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveProfile(big) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.revolveProfile(
                    listOf(ProfilePoint(1.0, 0.0), ProfilePoint(1.0, 0.0), ProfilePoint(2.0, 1.0)),
                )
            }
        }

        "revolveProfile rejects a profile crossing the rotation axis, for both axes" {
            val crossingY = listOf(ProfilePoint(-1.0, 0.0), ProfilePoint(1.0, 0.0), ProfilePoint(1.0, 1.0))
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveProfile(crossingY, 360.0, RevolveAxis.Y) }
            val crossingX = listOf(ProfilePoint(0.0, -1.0), ProfilePoint(0.0, 1.0), ProfilePoint(1.0, 1.0))
            shouldThrow<IllegalArgumentException> { OcctKernel.revolveProfile(crossingX, 360.0, RevolveAxis.X) }
        }

        "chamfer rejects empty, oversized, negative-index and bad-distance requests" {
            // The shape argument is never reached: validation happens first. A closed dummy is not
            // needed because every failure below precedes withHandle.
            val shape = fakeShapeNeverUsed()
            shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(shape, emptyList(), 1.0) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.chamfer(shape, (0..OcctKernel.MAX_CHAMFER_EDGES).toList(), 1.0)
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(shape, listOf(-1), 1.0) }
            listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, OcctKernel.MAX_CHAMFER_DISTANCE * 2).forEach { d ->
                shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(shape, 0, d) }
            }
        }

        "hole rejects invalid diameter, depth and direction" {
            val shape = fakeShapeNeverUsed()
            shouldThrow<IllegalArgumentException> { OcctKernel.hole(shape, 0.0, 1.0, 0.0, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.hole(shape, 1.0, Double.NaN, 0.0, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.hole(shape, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
        }

        "translate rejects non-finite or out-of-range components" {
            val shape = fakeShapeNeverUsed()
            shouldThrow<IllegalArgumentException> { OcctKernel.translate(shape, Double.NaN, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.translate(
                    shape,
                    0.0,
                    OcctKernel.MAX_ABS_COORDINATE * 2,
                    0.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.transformed(shape, Placement.translation(0.0, 0.0, Double.POSITIVE_INFINITY))
            }
        }
    })

/** A shape that is never dereferenced: all callers fail argument validation before withHandle. */
private fun fakeShapeNeverUsed(): dev.kstep.geometry.OcctShape {
    val ctor = dev.kstep.geometry.OcctShape::class.java.declaredConstructors.first { it.parameterCount == 1 }
    ctor.isAccessible = true
    return ctor.newInstance(0L) as dev.kstep.geometry.OcctShape
}
