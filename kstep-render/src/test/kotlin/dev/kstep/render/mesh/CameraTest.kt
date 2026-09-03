package dev.kstep.render.mesh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val TOLERANCE = 1e-12

class CameraTest :
    StringSpec({
        "azimuth wraps into [0, 360) for a value above the range" {
            Camera.of(400.0, 0.0).azimuthDeg shouldBe (40.0 plusOrMinus TOLERANCE)
        }

        "azimuth wraps into [0, 360) for a negative value" {
            Camera.of(-10.0, 0.0).azimuthDeg shouldBe (350.0 plusOrMinus TOLERANCE)
        }

        "azimuth of exactly 360 wraps to 0" {
            Camera.of(360.0, 0.0).azimuthDeg shouldBe (0.0 plusOrMinus TOLERANCE)
        }

        "elevation is clamped to +89 above the range" {
            Camera.of(0.0, 95.0).elevationDeg shouldBe (89.0 plusOrMinus TOLERANCE)
        }

        "elevation is clamped to -89 below the range" {
            Camera.of(0.0, -95.0).elevationDeg shouldBe (-89.0 plusOrMinus TOLERANCE)
        }

        "elevation exactly at the clamp bounds passes through unchanged" {
            Camera.of(0.0, 89.0).elevationDeg shouldBe (89.0 plusOrMinus TOLERANCE)
            Camera.of(0.0, -89.0).elevationDeg shouldBe (-89.0 plusOrMinus TOLERANCE)
        }

        "a NaN azimuth is rejected before normalization" {
            shouldThrow<IllegalArgumentException> { Camera.of(Double.NaN, 0.0) }
        }

        "a NaN elevation is rejected before clamping" {
            shouldThrow<IllegalArgumentException> { Camera.of(0.0, Double.NaN) }
        }

        "a positive-infinite azimuth is rejected" {
            shouldThrow<IllegalArgumentException> { Camera.of(Double.POSITIVE_INFINITY, 0.0) }
        }

        "a positive-infinite elevation is rejected" {
            shouldThrow<IllegalArgumentException> { Camera.of(0.0, Double.POSITIVE_INFINITY) }
        }

        "a negative-infinite azimuth is rejected" {
            shouldThrow<IllegalArgumentException> { Camera.of(Double.NEGATIVE_INFINITY, 0.0) }
        }

        "ISOMETRIC carries the fixed home-pose angles" {
            Camera.ISOMETRIC.azimuthDeg shouldBe (45.0 plusOrMinus TOLERANCE)
            Camera.ISOMETRIC.elevationDeg shouldBe (35.264389682754654 plusOrMinus TOLERANCE)
        }

        "two Camera instances with the same angles are structurally equal" {
            Camera.of(45.0, 35.264389682754654) shouldBe Camera.ISOMETRIC
            (Camera.of(45.0, 35.264389682754654) == Camera.ISOMETRIC) shouldBe true
        }

        "equal Camera instances hash equally" {
            Camera.of(10.0, 20.0).hashCode() shouldBe Camera.of(10.0, 20.0).hashCode()
        }

        "toString includes both angles" {
            val text = Camera.of(45.0, 35.264389682754654).toString()
            (text.contains("45.0") && text.contains("35.264389682754654")) shouldBe true
        }
    })
