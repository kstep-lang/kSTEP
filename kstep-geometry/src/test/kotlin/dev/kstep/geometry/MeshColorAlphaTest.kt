package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MeshColorAlphaTest :
    StringSpec({
        "alpha defaults to 1.0 and NEUTRAL is opaque" {
            MeshColor(0.1, 0.2, 0.3).a shouldBe 1.0
            MeshColor.NEUTRAL.a shouldBe 1.0
            MeshColor.NEUTRAL.isOpaque shouldBe true
        }

        "an alpha in 0..1 is accepted and below 1 is not opaque" {
            for (a in listOf(0.0, 0.25, 0.5, 0.999999, 1.0)) MeshColor(0.5, 0.5, 0.5, a).a shouldBe a
            MeshColor(0.5, 0.5, 0.5, 0.5).isOpaque shouldBe false
        }

        "a NaN, infinite or out-of-range alpha is rejected" {
            for (a in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01)) {
                shouldThrow<IllegalArgumentException> { MeshColor(0.5, 0.5, 0.5, a) }
            }
        }

        "colours that differ only in alpha are different palette keys" {
            (MeshColor(0.0, 0.0, 1.0, 0.5) == MeshColor(0.0, 0.0, 1.0)) shouldBe false
        }
    })
