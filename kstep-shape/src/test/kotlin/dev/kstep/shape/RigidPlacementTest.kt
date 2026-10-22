package dev.kstep.shape

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.random.Random

private const val EPS = 1e-9

private fun near(
    actual: Vec3,
    expected: Vec3,
    eps: Double = EPS,
) {
    actual.x shouldBe (expected.x plusOrMinus eps)
    actual.y shouldBe (expected.y plusOrMinus eps)
    actual.z shouldBe (expected.z plusOrMinus eps)
}

class RigidPlacementTest :
    StringSpec({
        "identity leaves points unchanged and reports isIdentity" {
            val p = Vec3(1.0, -2.0, 3.5)
            RigidPlacement.IDENTITY.apply(p) shouldBe p
            RigidPlacement.IDENTITY.isIdentity shouldBe true
            RigidPlacement(Vec3(1.0, 0.0, 0.0)).isIdentity shouldBe false
        }

        "a 90 degree turn about Z is exact (no rounding residue)" {
            val r = Rotation.about(Vec3.Z, 90.0)
            r.axis shouldBe Vec3(0.0, 0.0, 1.0)
            r.refDirection shouldBe Vec3(0.0, 1.0, 0.0)
            r.apply(Vec3(10.0, 0.0, 0.0)) shouldBe Vec3(0.0, 10.0, 0.0)
        }

        "multiples of 90 degrees (including negative and over 360) are exact" {
            Rotation.about(Vec3.Z, 180.0).refDirection shouldBe Vec3(-1.0, 0.0, 0.0)
            Rotation.about(Vec3.Z, -90.0).refDirection shouldBe Vec3(0.0, -1.0, 0.0)
            Rotation.about(Vec3.Z, 450.0).refDirection shouldBe Vec3(0.0, 1.0, 0.0)
            Rotation.about(Vec3.X, 90.0).axis shouldBe Vec3(0.0, -1.0, 0.0)
            Rotation.about(Vec3.Z, 0.0) shouldBe Rotation.IDENTITY
        }

        "the third axis follows the right-hand rule and the point mapping is p = R p + t" {
            val placement = RigidPlacement(Vec3(30.0, 5.0, 10.0), Rotation.about(Vec3.Z, 90.0))
            // local box corner (10, 20, 0) -> (-20, 10, 0) + t
            near(placement.apply(Vec3(10.0, 20.0, 0.0)), Vec3(10.0, 15.0, 10.0))
            near(placement.apply(Vec3.ZERO), Vec3(30.0, 5.0, 10.0))
        }

        "composition equals applying both placements in sequence" {
            val outer = RigidPlacement(Vec3(1.0, 2.0, 3.0), Rotation.about(Vec3(1.0, 1.0, 0.0), 33.0))
            val inner = RigidPlacement(Vec3(-4.0, 0.5, 7.0), Rotation.about(Vec3(0.0, 1.0, 1.0), -71.0))
            val p = Vec3(0.3, -9.0, 2.0)
            near(outer.then(inner).apply(p), outer.apply(inner.apply(p)))
        }

        "an arbitrary axis and angle gives an orthonormal right-handed frame" {
            val r = Rotation.about(Vec3(1.0, 2.0, 3.0), 47.0)
            (r.axis.norm()) shouldBe (1.0 plusOrMinus EPS)
            (r.refDirection.norm()) shouldBe (1.0 plusOrMinus EPS)
            (r.axis dot r.refDirection) shouldBe (0.0 plusOrMinus EPS)
            (r.yDirection.norm()) shouldBe (1.0 plusOrMinus EPS)
            // right-handed: ex x ey == ez
            near(r.refDirection cross r.yDirection, r.axis)
        }

        "random placements preserve lengths and distances (1000 cases)" {
            val rnd = Random(20261022)
            repeat(1000) {
                fun v() =
                    Vec3(rnd.nextDouble(-100.0, 100.0), rnd.nextDouble(-100.0, 100.0), rnd.nextDouble(-100.0, 100.0))
                val axis = v()
                val placement = RigidPlacement(v(), Rotation.about(axis, rnd.nextDouble(-720.0, 720.0)))
                val a = v()
                val b = v()
                val dist = (a - b).norm()
                val mapped = (placement.apply(a) - placement.apply(b)).norm()
                abs(dist - mapped) shouldBe (0.0 plusOrMinus 1e-7)
                // rotation about the origin keeps the norm
                abs(placement.rotation.apply(a).norm() - a.norm()) shouldBe (0.0 plusOrMinus 1e-7)
            }
        }

        "fromAxes accepts an orthonormal pair and rejects everything else" {
            Rotation.fromAxes(Vec3.Z, Vec3.X) shouldBe Rotation.IDENTITY
            shouldThrow<IllegalArgumentException> { Rotation.fromAxes(Vec3(0.0, 0.0, 2.0), Vec3.X) }
            shouldThrow<IllegalArgumentException> { Rotation.fromAxes(Vec3.Z, Vec3(2.0, 0.0, 0.0)) }
            shouldThrow<IllegalArgumentException> { Rotation.fromAxes(Vec3.Z, Vec3(0.0, 0.0, 1.0)) }
            shouldThrow<IllegalArgumentException> { Rotation.fromAxes(Vec3.Z, Vec3(Double.NaN, 0.0, 0.0)) }
            shouldThrow<IllegalArgumentException> {
                Rotation.fromAxes(
                    Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0),
                    Vec3.X,
                )
            }
        }

        "about rejects a zero axis and non-finite input" {
            shouldThrow<IllegalArgumentException> { Rotation.about(Vec3.ZERO, 10.0) }
            shouldThrow<IllegalArgumentException> { Rotation.about(Vec3.Z, Double.NaN) }
            shouldThrow<IllegalArgumentException> { Rotation.about(Vec3(Double.NaN, 0.0, 1.0), 10.0) }
        }

        "a placement rejects non-finite and implausibly large translations" {
            shouldThrow<IllegalArgumentException> { RigidPlacement(Vec3(Double.NaN, 0.0, 0.0)) }
            shouldThrow<IllegalArgumentException> { RigidPlacement(Vec3(0.0, Double.POSITIVE_INFINITY, 0.0)) }
            shouldThrow<IllegalArgumentException> { RigidPlacement(Vec3(0.0, 0.0, 1.0e9 + 1.0)) }
            RigidPlacement(Vec3(1.0e9, -1.0e9, 0.0)).translation shouldBe Vec3(1.0e9, -1.0e9, 0.0)
        }

        "stepReal always writes a REAL lexeme and never negative zero" {
            stepReal(0.0) shouldBe "0."
            stepReal(-0.0) shouldBe "0."
            stepReal(40.0) shouldBe "40.0"
            stepReal(-3.5) shouldBe "-3.5"
            stepReal(1e-5) shouldBe "1.0E-5"
            stepReal(1e20) shouldBe "1.0E20"
            shouldThrow<IllegalArgumentException> { stepReal(Double.NaN) }
            shouldThrow<IllegalArgumentException> { stepReal(Double.NEGATIVE_INFINITY) }
        }
    })
