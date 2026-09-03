package dev.kstep.geometry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.sqrt

private const val TOLERANCE = 1e-9

private infix fun Double.eq(expected: Double) = this shouldBe (expected plusOrMinus TOLERANCE)

/**
 * Pure-Kotlin, no-OCCT coverage of [Placement] -- see `PlacementPackageBoundaryTest` for the
 * companion guard that [Placement]/`MeshComposition.kt` stay free of any `dev.kstep.render`/
 * Compose import.
 */
class PlacementTest :
    StringSpec({
        "rotationZ(90 degrees) maps (1,0,0) to (0,1,0)" {
            val rotated = Placement.rotationZ(90.0).apply(1.0, 0.0, 0.0)
            rotated[0] eq 0.0
            rotated[1] eq 1.0
            rotated[2] eq 0.0
        }

        "rotationX(90 degrees) maps (0,1,0) to (0,0,1)" {
            val rotated = Placement.rotationX(90.0).apply(0.0, 1.0, 0.0)
            rotated[0] eq 0.0
            rotated[1] eq 0.0
            rotated[2] eq 1.0
        }

        "rotationY(90 degrees) maps (0,0,1) to (1,0,0)" {
            val rotated = Placement.rotationY(90.0).apply(0.0, 0.0, 1.0)
            rotated[0] eq 1.0
            rotated[1] eq 0.0
            rotated[2] eq 0.0
        }

        "translation shifts a point by exactly the given offset" {
            val moved = Placement.translation(3.0, -4.0, 5.0).apply(1.0, 1.0, 1.0)
            moved[0] eq 4.0
            moved[1] eq -3.0
            moved[2] eq 6.0
        }

        "IDENTITY leaves every point unchanged" {
            val p = Placement.IDENTITY.apply(7.5, -2.25, 100.0)
            p[0] eq 7.5
            p[1] eq -2.25
            p[2] eq 100.0
        }

        "IDENTITY.then(x) == x and x.then(IDENTITY) == x, for a point round-trip" {
            val point = Triple(2.0, -3.0, 9.0)
            val x = Placement.translation(1.0, 2.0, 3.0).then(Placement.rotationZ(40.0))

            val leftIdentity = Placement.IDENTITY.then(x).apply(point.first, point.second, point.third)
            val rightIdentity = x.then(Placement.IDENTITY).apply(point.first, point.second, point.third)
            val direct = x.apply(point.first, point.second, point.third)

            leftIdentity[0] eq direct[0]
            leftIdentity[1] eq direct[1]
            leftIdentity[2] eq direct[2]
            rightIdentity[0] eq direct[0]
            rightIdentity[1] eq direct[1]
            rightIdentity[2] eq direct[2]
        }

        "then() applies this FIRST, then the argument -- order matters" {
            // Translate along +X, THEN rotate 90 degrees around Z: (1,0,0) -> translate -> (2,0,0)
            // -> rotate -> (0,2,0).
            val translateThenRotate = Placement.translation(1.0, 0.0, 0.0).then(Placement.rotationZ(90.0))
            val a = translateThenRotate.apply(1.0, 0.0, 0.0)
            a[0] eq 0.0
            a[1] eq 2.0
            a[2] eq 0.0

            // Rotate 90 degrees around Z, THEN translate along +X: (1,0,0) -> rotate -> (0,1,0)
            // -> translate -> (1,1,0). Genuinely different from the composition above --
            // composition is not commutative.
            val rotateThenTranslate = Placement.rotationZ(90.0).then(Placement.translation(1.0, 0.0, 0.0))
            val b = rotateThenTranslate.apply(1.0, 0.0, 0.0)
            b[0] eq 1.0
            b[1] eq 1.0
            b[2] eq 0.0

            (a[0] != b[0] || a[1] != b[1]) shouldBe true
        }

        "every Placement this type can build preserves vector length (is an isometry)" {
            val placements =
                listOf(
                    Placement.IDENTITY,
                    Placement.translation(5.0, -5.0, 5.0),
                    Placement.rotationX(37.0),
                    Placement.rotationY(-64.0),
                    Placement.rotationZ(123.0),
                    Placement
                        .rotationX(
                            20.0,
                        ).then(Placement.rotationY(50.0))
                        .then(Placement.translation(1.0, 2.0, 3.0)),
                )
            // Two points, distance sqrt(3) apart -- translation must not change that distance
            // (an isometry check has to use TWO points; checking distance-from-origin alone would
            // not catch a translation that also happened to preserve length by coincidence).
            val p = doubleArrayOf(0.0, 0.0, 0.0)
            val q = doubleArrayOf(1.0, 1.0, 1.0)
            val originalDistance = sqrt(3.0)

            placements.forEach { placement ->
                val pp = placement.apply(p[0], p[1], p[2])
                val qq = placement.apply(q[0], q[1], q[2])
                val dx = qq[0] - pp[0]
                val dy = qq[1] - pp[1]
                val dz = qq[2] - pp[2]
                val distance = sqrt(dx * dx + dy * dy + dz * dz)
                distance eq originalDistance
            }
        }

        "every Placement this type can build has rotation-block determinant +1" {
            fun determinant(placement: Placement): Double {
                // Reconstructs the 3x3 rotation block by applying `placement` to the origin
                // (isolates the translation) and to each basis vector -- this test's own way of
                // reading the private matrix without exposing it from Placement itself.
                val origin = placement.apply(0.0, 0.0, 0.0)
                val ex =
                    placement.apply(1.0, 0.0, 0.0).let {
                        doubleArrayOf(
                            it[0] - origin[0],
                            it[1] - origin[1],
                            it[2] - origin[2],
                        )
                    }
                val ey =
                    placement.apply(0.0, 1.0, 0.0).let {
                        doubleArrayOf(
                            it[0] - origin[0],
                            it[1] - origin[1],
                            it[2] - origin[2],
                        )
                    }
                val ez =
                    placement.apply(0.0, 0.0, 1.0).let {
                        doubleArrayOf(
                            it[0] - origin[0],
                            it[1] - origin[1],
                            it[2] - origin[2],
                        )
                    }
                // Determinant via the scalar triple product ex . (ey x ez).
                val crossX = ey[1] * ez[2] - ey[2] * ez[1]
                val crossY = ey[2] * ez[0] - ey[0] * ez[2]
                val crossZ = ey[0] * ez[1] - ey[1] * ez[0]
                return ex[0] * crossX + ex[1] * crossY + ex[2] * crossZ
            }

            val placements =
                listOf(
                    Placement.IDENTITY,
                    Placement.translation(9.0, -1.0, 0.0),
                    Placement.rotationX(90.0),
                    Placement.rotationY(45.0),
                    Placement.rotationZ(200.0),
                    Placement.rotationX(30.0).then(Placement.rotationY(30.0)).then(Placement.rotationZ(30.0)),
                )
            placements.forEach { placement -> determinant(placement) eq 1.0 }
        }
    })
