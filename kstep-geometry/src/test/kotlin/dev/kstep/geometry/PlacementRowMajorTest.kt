package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** OCCT-free coverage of [Placement.fromRowMajor3x4]/[Placement.rowMajor3x4] and [BoundingBox]. */
class PlacementRowMajorTest :
    StringSpec({
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        "identity round-trips" {
            Placement.fromRowMajor3x4(identity).rowMajor3x4().contentEquals(identity) shouldBe true
        }

        "Rz(90) with translation round-trips through the matrix" {
            val original = Placement.rotationZ(90.0).then(Placement.translation(30.0, 5.0, 10.0))
            val back = Placement.fromRowMajor3x4(original.rowMajor3x4(), 1e-9)
            val p = back.apply(1.0, 0.0, 0.0)
            p[0] shouldBe (30.0 plusOrMinus 1e-9)
            p[1] shouldBe (6.0 plusOrMinus 1e-9)
            p[2] shouldBe (10.0 plusOrMinus 1e-9)
        }

        "scaling is rejected" {
            val scaled = identity.copyOf().also { it[0] = 2.0 }
            shouldThrow<IllegalArgumentException> { Placement.fromRowMajor3x4(scaled) }
        }

        "reflection is rejected" {
            val mirrored = identity.copyOf().also { it[10] = -1.0 }
            shouldThrow<IllegalArgumentException> { Placement.fromRowMajor3x4(mirrored) }
        }

        "NaN, infinity and wrong length are rejected" {
            shouldThrow<IllegalArgumentException> {
                Placement.fromRowMajor3x4(
                    identity.copyOf().also {
                        it[3] =
                            Double.NaN
                    },
                )
            }
            shouldThrow<IllegalArgumentException> {
                Placement.fromRowMajor3x4(
                    identity.copyOf().also {
                        it[7] =
                            Double.POSITIVE_INFINITY
                    },
                )
            }
            shouldThrow<IllegalArgumentException> { Placement.fromRowMajor3x4(DoubleArray(11)) }
        }

        "BoundingBox.union takes the extremes" {
            val a = BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
            val b = BoundingBox(-1.0, 0.5, 0.2, 0.5, 3.0, 0.4)
            a.union(b) shouldBe BoundingBox(-1.0, 0.0, 0.0, 1.0, 3.0, 1.0)
        }
    })
