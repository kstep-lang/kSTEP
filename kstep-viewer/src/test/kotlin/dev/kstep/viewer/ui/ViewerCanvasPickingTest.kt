package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val SIZE = 256

/** Records what the canvas reports to its host (ADR-0053). */
private class RecordingPicking : CanvasPicking {
    var hovers = 0
    var clicks = 0
    var dragStarts = 0
    var exits = 0
    var escapes = 0
    var lastFrame: ScreenFrame? = null

    override fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {
        hovers++
        lastFrame = frame
    }

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {
        clicks++
        lastFrame = frame
    }

    override fun onDragStart() {
        dragStarts++
    }

    override fun onPointerExit() {
        exits++
    }

    override fun onEscape(): Boolean {
        escapes++
        return true
    }

    override fun isOverPart(): Boolean = false
}

/** Drives the picking wiring of [ShapeCanvas] through Compose's real gesture pipeline, like the camera wiring test. */
@OptIn(ExperimentalTestApi::class)
class ViewerCanvasPickingTest :
    StringSpec({
        // a unit square in the plane z = 0 (two triangles, normals +Z), red
        val square =
            MeshComposition.mergeColored(
                listOf(
                    PlacedMesh(
                        TriangleMesh(
                            doubleArrayOf(
                                0.0,
                                0.0,
                                0.0,
                                1.0,
                                0.0,
                                0.0,
                                1.0,
                                1.0,
                                0.0,
                                0.0,
                                0.0,
                                0.0,
                                1.0,
                                1.0,
                                0.0,
                                0.0,
                                1.0,
                                0.0,
                            ),
                        ),
                        color = MeshColor(1.0, 0.0, 0.0),
                    ),
                ),
            )
        val noHighlight = mutableStateOf<CanvasHighlight?>(null)

        fun withCanvas(
            picking: RecordingPicking,
            state: androidx.compose.runtime.MutableState<ViewerCameraState> = mutableStateOf(ViewerCameraState.HOME),
            highlight: androidx.compose.runtime.State<CanvasHighlight?> = noHighlight,
            block: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit,
        ) {
            runDesktopComposeUiTest(width = SIZE, height = SIZE) {
                setContent {
                    ShapeCanvas(square, Modifier.size(SIZE.dp), state, null, highlight, picking)
                }
                waitForIdle()
                block()
            }
        }

        "a click below the drag threshold reports a click with the frame and does not move the camera" {
            val picking = RecordingPicking()
            val state = mutableStateOf(ViewerCameraState.HOME)
            withCanvas(picking, state) {
                onRoot().performMouseInput { click(Offset(120f, 120f)) }
                waitForIdle()
                picking.clicks shouldBe 1
                picking.dragStarts shouldBe 0
                picking.lastFrame.shouldNotBeNull()
                state.value shouldBe ViewerCameraState.HOME
            }
        }

        "a drag past the threshold orbits, reports a drag start and no click" {
            val picking = RecordingPicking()
            val state = mutableStateOf(ViewerCameraState.HOME)
            withCanvas(picking, state) {
                onRoot().performMouseInput {
                    press()
                    repeat(5) { moveBy(Offset(40f, 0f)) }
                    release()
                }
                waitForIdle()
                picking.dragStarts shouldBe 1
                picking.clicks shouldBe 0
                state.value shouldNotBe ViewerCameraState.HOME
            }
        }

        "a shift press and release never reports a click, a shift drag pans" {
            val picking = RecordingPicking()
            val state = mutableStateOf(ViewerCameraState.HOME)
            withCanvas(picking, state) {
                onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
                onRoot().performMouseInput {
                    click(Offset(100f, 100f))
                }
                onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
                waitForIdle()
                picking.clicks shouldBe 0
                onRoot().performKeyInput { keyDown(Key.ShiftLeft) }
                onRoot().performMouseInput {
                    press()
                    repeat(5) { moveBy(Offset(30f, 10f)) }
                    release()
                }
                onRoot().performKeyInput { keyUp(Key.ShiftLeft) }
                waitForIdle()
                picking.clicks shouldBe 0
                (state.value.panX != 0.0 || state.value.panY != 0.0) shouldBe true
            }
        }

        "Escape reaches the host while the canvas has the focus" {
            val picking = RecordingPicking()
            withCanvas(picking) {
                onRoot().performKeyInput { pressKey(Key.Escape) }
                waitForIdle()
                picking.escapes shouldBe 1
            }
        }

        "moving over the canvas reports hovers and leaving it reports the exit" {
            val picking = RecordingPicking()
            withCanvas(picking) {
                onRoot().performMouseInput {
                    enter(Offset(100f, 100f))
                    advanceEventTime(30)
                    moveTo(Offset(100f, 100f))
                    advanceEventTime(30)
                    moveBy(Offset(5f, 5f))
                    advanceEventTime(30)
                    moveBy(Offset(5f, 5f))
                    advanceEventTime(30)
                    exit(Offset(-10f, -10f))
                }
                waitForIdle()
                (picking.hovers >= 1) shouldBe true
                picking.exits shouldBe 1
            }
        }

        "a selected part is tinted towards the accent colour" {
            val highlight = mutableStateOf<CanvasHighlight?>(null)
            val picking = RecordingPicking()
            withCanvas(picking, highlight = highlight) {
                fun centerBlue(): Float {
                    val pixels = onRoot().captureToImage().toPixelMap()
                    return pixels[SIZE / 2, SIZE / 2].blue
                }
                val before = centerBlue()
                highlight.value =
                    CanvasHighlight(
                        intArrayOf(0, 0),
                        selected = 0,
                        hovered = -1,
                        accentRgb = doubleArrayOf(0.0, 0.0, 1.0),
                    )
                waitForIdle()
                val after = centerBlue()
                (after > before) shouldBe true
                // hover alone tints less than selection
                highlight.value =
                    CanvasHighlight(
                        intArrayOf(0, 0),
                        selected = -1,
                        hovered = 0,
                        accentRgb = doubleArrayOf(0.0, 0.0, 1.0),
                    )
                waitForIdle()
                val hoverBlue = centerBlue()
                (hoverBlue > before && hoverBlue < after) shouldBe true
                // a highlight of another part leaves the pixel alone
                highlight.value =
                    CanvasHighlight(
                        intArrayOf(0, 0),
                        selected = 3,
                        hovered = -1,
                        accentRgb = doubleArrayOf(0.0, 0.0, 1.0),
                    )
                waitForIdle()
                centerBlue() shouldBe before
            }
        }
    })
