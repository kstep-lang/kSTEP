package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MeshColorTest :
    StringSpec({
        "NEUTRAL is exactly (1.0, 1.0, 1.0)" {
            MeshColor.NEUTRAL.r shouldBe 1.0
            MeshColor.NEUTRAL.g shouldBe 1.0
            MeshColor.NEUTRAL.b shouldBe 1.0
        }

        "a color with every channel in 0.0..1.0 is accepted" {
            val color = MeshColor(0.0, 0.5, 1.0)
            color.r shouldBe 0.0
            color.g shouldBe 0.5
            color.b shouldBe 1.0
        }

        "a channel above 1.0 is rejected" {
            shouldThrow<IllegalArgumentException> { MeshColor(1.1, 0.5, 0.5) }
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, 1.1, 0.5) }
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, 0.5, 1.1) }
        }

        "a channel below 0.0 is rejected" {
            shouldThrow<IllegalArgumentException> { MeshColor(-0.1, 0.5, 0.5) }
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, -0.1, 0.5) }
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, 0.5, -0.1) }
        }

        "a NaN channel is rejected" {
            shouldThrow<IllegalArgumentException> { MeshColor(Double.NaN, 0.5, 0.5) }
        }

        "an infinite channel is rejected" {
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, Double.POSITIVE_INFINITY, 0.5) }
            shouldThrow<IllegalArgumentException> { MeshColor(0.5, 0.5, Double.NEGATIVE_INFINITY) }
        }

        "two MeshColors with equal channels are equal (value equality, needed for palette dedup)" {
            MeshColor(0.4, 0.5, 0.6) shouldBe MeshColor(0.4, 0.5, 0.6)
        }
    })
