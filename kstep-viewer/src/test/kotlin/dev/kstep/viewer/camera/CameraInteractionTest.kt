package dev.kstep.viewer.camera

import dev.kstep.render.mesh.Camera
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val TOLERANCE = 1e-9

/**
 * No `androidx.compose.*` import in this file -- [CameraInteraction] is deliberately
 * Compose-free, so these are plain unit tests, no window/scene needed (contrast
 * `ViewerCanvasCameraRenderTest`, which drives the actual Compose draw phase).
 */
class CameraInteractionTest :
    StringSpec({
        "a rightward drag from HOME decreases azimuth" {
            val next = CameraInteraction.onDrag(ViewerCameraState.HOME, dx = 100f, dy = 0f)
            // 45.0 - 50.0 = -5.0, wrapped by Camera.of into [0, 360) -> 355.0.
            next.camera.azimuthDeg shouldBe (355.0 plusOrMinus TOLERANCE)
            next.camera.elevationDeg shouldBe (Camera.ISOMETRIC.elevationDeg plusOrMinus TOLERANCE)
        }

        "a large downward drag clamps elevation at the +89 bound" {
            val next = CameraInteraction.onDrag(ViewerCameraState.HOME, dx = 0f, dy = 10_000f)
            next.camera.elevationDeg shouldBe (89.0 plusOrMinus TOLERANCE)
        }

        "a large upward drag clamps elevation at the -89 bound" {
            val next = CameraInteraction.onDrag(ViewerCameraState.HOME, dx = 0f, dy = -10_000f)
            next.camera.elevationDeg shouldBe (-89.0 plusOrMinus TOLERANCE)
        }

        "ten thousand successive small downward drags clamp exactly at +89 with no overshoot or NaN" {
            var state = ViewerCameraState.HOME
            repeat(10_000) { state = CameraInteraction.onDrag(state, dx = 0f, dy = 1f) }
            state.camera.elevationDeg shouldBe (89.0 plusOrMinus TOLERANCE)
            state.camera.elevationDeg.isNaN() shouldBe false
        }

        "azimuth stays within [0, 360) across many drags in one direction" {
            var state = ViewerCameraState.HOME
            repeat(2_000) {
                state = CameraInteraction.onDrag(state, dx = 137f, dy = 0f)
                (state.camera.azimuthDeg >= 0.0 && state.camera.azimuthDeg < 360.0) shouldBe true
            }
        }

        "azimuth stays within [0, 360) across many drags in the opposite direction" {
            var state = ViewerCameraState.HOME
            repeat(2_000) {
                state = CameraInteraction.onDrag(state, dx = -137f, dy = 0f)
                (state.camera.azimuthDeg >= 0.0 && state.camera.azimuthDeg < 360.0) shouldBe true
            }
        }

        "a NaN drag delta leaves the state exactly unchanged" {
            val start = ViewerCameraState.HOME
            CameraInteraction.onDrag(start, dx = Float.NaN, dy = 0f) shouldBe start
            CameraInteraction.onDrag(start, dx = 0f, dy = Float.NaN) shouldBe start
        }

        "an infinite drag delta leaves the state exactly unchanged" {
            val start = ViewerCameraState.HOME
            CameraInteraction.onDrag(start, dx = Float.POSITIVE_INFINITY, dy = 0f) shouldBe start
            CameraInteraction.onDrag(start, dx = 0f, dy = Float.NEGATIVE_INFINITY) shouldBe start
        }

        "ten scroll-in notches multiply zoom by 1.1^10" {
            var state = ViewerCameraState.HOME
            repeat(10) { state = CameraInteraction.onScroll(state, scrollDeltaY = -1f) }
            state.zoom shouldBe (Math.pow(1.1, 10.0) plusOrMinus 1e-6)
        }

        "many zoom-in events clamp at MAX_ZOOM, never reach Infinity" {
            var state = ViewerCameraState.HOME
            repeat(500) { state = CameraInteraction.onScroll(state, scrollDeltaY = -1f) }
            state.zoom shouldBe (50.0 plusOrMinus 1e-6)
            state.zoom.isInfinite() shouldBe false
        }

        "many zoom-out events clamp at MIN_ZOOM, never reach zero or negative" {
            var state = ViewerCameraState.HOME
            repeat(500) { state = CameraInteraction.onScroll(state, scrollDeltaY = 1f) }
            state.zoom shouldBe (0.05 plusOrMinus 1e-6)
            (state.zoom > 0.0) shouldBe true
        }

        "a single scroll event with an extreme delta is capped by MAX_NOTCHES_PER_EVENT, not left to overflow" {
            val next = CameraInteraction.onScroll(ViewerCameraState.HOME, scrollDeltaY = -100_000f)
            next.zoom shouldBe (Math.pow(1.1, 10.0) plusOrMinus 1e-6)
            next.zoom.isInfinite() shouldBe false
        }

        "a NaN scroll delta leaves the state exactly unchanged" {
            val start = ViewerCameraState.HOME
            CameraInteraction.onScroll(start, scrollDeltaY = Float.NaN) shouldBe start
        }

        "an infinite scroll delta leaves the state exactly unchanged" {
            val start = ViewerCameraState.HOME
            CameraInteraction.onScroll(start, scrollDeltaY = Float.POSITIVE_INFINITY) shouldBe start
            CameraInteraction.onScroll(start, scrollDeltaY = Float.NEGATIVE_INFINITY) shouldBe start
        }

        "reset returns exactly HOME" {
            CameraInteraction.reset() shouldBe ViewerCameraState.HOME
        }

        "reset after arbitrary drag/scroll returns exactly to HOME" {
            var state = ViewerCameraState.HOME
            state = CameraInteraction.onDrag(state, dx = 250f, dy = -80f)
            state = CameraInteraction.onScroll(state, scrollDeltaY = -3f)
            CameraInteraction.reset() shouldBe ViewerCameraState.HOME
        }
    })
