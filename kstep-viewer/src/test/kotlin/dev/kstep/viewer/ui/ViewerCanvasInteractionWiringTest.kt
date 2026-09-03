package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.Camera
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val SCENE_SIZE = 256

/**
 * Drives [ShapeCanvas]'s ACTUAL pointer/keyboard wiring -- `detectDragGestures`, the
 * `onPointerEvent(PointerEventType.Scroll)` handler, `detectTapGestures`'s `onDoubleTap`, and
 * `onKeyEvent`'s `R` case -- through Compose's own [runDesktopComposeUiTest] gesture-injection
 * harness (`performMouseInput { drag()/click()/doubleClick()/scroll(...) }`,
 * `performKeyInput { pressKey(...) }`), rather than either:
 *  - calling [dev.kstep.viewer.camera.CameraInteraction]'s pure functions directly
 *    ([dev.kstep.viewer.camera.CameraInteractionTest]), or
 *  - injecting a [ViewerCameraState] straight into `ShapeCanvas`'s `state` parameter
 *    ([ViewerCanvasCameraRenderTest]).
 *
 * Neither of those two existing suites would catch a bug in the WIRING itself -- e.g. `dx`/`dy`
 * swapped at the `CameraInteraction.onDrag` call site in [ShapeCanvas], a wrong [Key] comparison
 * in its `onKeyEvent`, or an `onDoubleTap` callback that never actually fires -- because both
 * bypass Compose's real gesture-detection pipeline entirely. This suite drives that pipeline
 * instead, and asserts on the externally-hoisted `state` [ShapeCanvas] was given -- the same
 * hoisting mechanism docs/adr/ADR-0012-viewer-camera-interaction.adoc's Folge-Welle V-2d names
 * for an external toolbar, reused here purely as a test seam.
 *
 * Uses `runDesktopComposeUiTest`/`performMouseInput`/`performKeyInput` rather than hand-rolled
 * [androidx.compose.ui.ImageComposeScene.sendPointerEvent] calls (the technique
 * [ViewerCanvasCameraRenderTest]/[ViewerCanvasZeroSizeTest] use for non-gesture draw-phase
 * rendering): an early draft of this suite drove raw Press/Release pairs through
 * `ImageComposeScene` directly for the double-click case and it silently never fired
 * `onDoubleTap` -- `MouseInjectionScope`'s own `click()`/`doubleClick()` get the exact
 * button-state/event-timing details of a real click sequence right in a way a hand-rolled
 * sequence did not reproduce. `runDesktopComposeUiTest` is the `@Deprecated`
 * (in favor of `androidx.compose.ui.test.v2`) but still fully functional v1 API -- deliberately
 * NOT the v2 replacement, which defaults to a `StandardTestDispatcher` that queues rather than
 * immediately runs composition/effects (`ShapeCanvas`'s own auto-focus `LaunchedEffect` included),
 * requiring explicit clock/dispatcher advancement this suite has no other reason to take on.
 */
@OptIn(ExperimentalTestApi::class)
class ViewerCanvasInteractionWiringTest :
    StringSpec({
        fun singleTriangleMesh(): TriangleMesh {
            val coords =
                doubleArrayOf(
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                    0.0,
                    0.0,
                    1.0,
                    0.0,
                )
            return TriangleMesh(coords)
        }

        "a real drag gesture through ShapeCanvas's pointerInput orbits the camera and leaves zoom untouched" {
            val state = mutableStateOf(ViewerCameraState.HOME)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                waitForIdle()

                onRoot().performMouseInput {
                    press()
                    // Several intermediate moves, not a single big jump straight from press to
                    // release -- detectDragGestures needs to observe an actually-moving pointer
                    // past touch slop, not just a press immediately followed by a release
                    // elsewhere.
                    repeat(5) { moveBy(Offset(40f, 0f)) }
                    release()
                }
                waitForIdle()

                // A rightward drag decreases azimuth -- CameraInteractionTest already pins the
                // exact math for CameraInteraction.onDrag itself; this only cares that SOME orbit
                // reached `state` through ShapeCanvas's real gesture wiring, not the precise value
                // (which would make this test redundant with CameraInteractionTest instead of a
                // wiring check).
                state.value shouldNotBe ViewerCameraState.HOME
                state.value.camera.azimuthDeg shouldNotBe ViewerCameraState.HOME.camera.azimuthDeg
                state.value.zoom shouldBe ViewerCameraState.HOME.zoom
            }
        }

        "a real scroll event through ShapeCanvas's onPointerEvent handler zooms the camera and leaves orbit untouched" {
            val state = mutableStateOf(ViewerCameraState.HOME)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                waitForIdle()

                onRoot().performMouseInput { scroll(-3f) }
                waitForIdle()

                state.value.zoom shouldNotBe ViewerCameraState.HOME.zoom
                state.value.camera shouldBe ViewerCameraState.HOME.camera
            }
        }

        "a real double-click through ShapeCanvas's detectTapGestures resets an orbited/zoomed camera" {
            val orbited = ViewerCameraState(Camera.of(120.0, 10.0), zoom = 3.0)
            val state = mutableStateOf(orbited)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                waitForIdle()
                state.value shouldBe orbited // sanity: still holding the pre-reset pose

                onRoot().performMouseInput { doubleClick() }
                waitForIdle()

                state.value shouldBe ViewerCameraState.HOME
            }
        }

        "a single click (no second tap) through ShapeCanvas's detectTapGestures does NOT reset the camera" {
            val orbited = ViewerCameraState(Camera.of(80.0, -15.0), zoom = 2.0)
            val state = mutableStateOf(orbited)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                waitForIdle()

                onRoot().performMouseInput { click() }
                waitForIdle()

                state.value shouldBe orbited
            }
        }

        "pressing R through ShapeCanvas's onKeyEvent resets an orbited/zoomed camera" {
            val orbited = ViewerCameraState(Camera.of(200.0, -40.0), zoom = 0.4)
            val state = mutableStateOf(orbited)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                // ShapeCanvas auto-focuses itself via LaunchedEffect(Unit) -- waitForIdle() lets
                // that effect (and the layout it depends on) actually run before a key event has
                // anything focused to land on.
                waitForIdle()

                onRoot().performKeyInput { pressKey(Key.R) }
                waitForIdle()

                state.value shouldBe ViewerCameraState.HOME
            }
        }

        "pressing a key other than R through ShapeCanvas's onKeyEvent leaves the camera untouched" {
            val orbited = ViewerCameraState(Camera.of(15.0, 5.0), zoom = 1.5)
            val state = mutableStateOf(orbited)
            runDesktopComposeUiTest(width = SCENE_SIZE, height = SCENE_SIZE) {
                setContent {
                    ShapeCanvas(singleTriangleMesh(), Modifier.size(SCENE_SIZE.dp), state = state)
                }
                waitForIdle()

                onRoot().performKeyInput { pressKey(Key.T) }
                waitForIdle()

                state.value shouldBe orbited
            }
        }
    })
