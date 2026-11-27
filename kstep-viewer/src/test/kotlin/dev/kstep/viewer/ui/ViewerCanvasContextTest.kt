package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

private const val SIZE = 256

/** Records the modifier-aware callbacks (ADR-0092). */
private class ContextPicking : CanvasPicking {
    var plainClicks = 0
    var modClicks = mutableListOf<PickModifiers>()
    var secondary = mutableListOf<PickModifiers>()
    var menuKeys = 0
    var dragStarts = 0

    override fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {}

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {
        plainClicks++
    }

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) {
        modClicks += modifiers
    }

    override fun onSecondaryClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
        modifiers: PickModifiers,
    ) {
        secondary += modifiers
    }

    override fun onMenuKey(): Boolean {
        menuKeys++
        return true
    }

    override fun onDragStart() {
        dragStarts++
    }

    override fun onPointerExit() {}

    override fun onEscape(): Boolean = false

    override fun isOverPart(): Boolean = false
}

/** A host that only knows the original callbacks must keep working: the new ones have defaults. */
private class LegacyPicking : CanvasPicking {
    var clicks = 0

    override fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {}

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) {
        clicks++
    }

    override fun onDragStart() {}

    override fun onPointerExit() {}

    override fun onEscape(): Boolean = false

    override fun isOverPart(): Boolean = false
}

@OptIn(ExperimentalTestApi::class)
class ViewerCanvasContextTest :
    StringSpec({
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
            picking: CanvasPicking,
            state: androidx.compose.runtime.MutableState<ViewerCameraState> = mutableStateOf(ViewerCameraState.HOME),
            block: androidx.compose.ui.test.DesktopComposeUiTest.() -> Unit,
        ) {
            runDesktopComposeUiTest(width = SIZE, height = SIZE) {
                setContent { ShapeCanvas(square, Modifier.size(SIZE.dp), state, null, noHighlight, picking) }
                waitForIdle()
                block()
            }
        }

        "a secondary click reports once, with no primary click and no camera move" {
            val picking = ContextPicking()
            val state = mutableStateOf(ViewerCameraState.HOME)
            withCanvas(picking, state) {
                onRoot().performMouseInput { rightClick(Offset(120f, 120f)) }
                waitForIdle()
                picking.secondary.size shouldBe 1
                picking.plainClicks + picking.modClicks.size shouldBe 0
                state.value shouldBe ViewerCameraState.HOME
            }
        }

        "a secondary drag still orbits and reports no menu" {
            val picking = ContextPicking()
            val state = mutableStateOf(ViewerCameraState.HOME)
            withCanvas(picking, state) {
                onRoot().performMouseInput {
                    press(androidx.compose.ui.test.MouseButton.Secondary)
                    repeat(5) { moveBy(Offset(40f, 0f)) }
                    release(androidx.compose.ui.test.MouseButton.Secondary)
                }
                waitForIdle()
                picking.dragStarts shouldBe 1
                picking.secondary.size shouldBe 0
                state.value shouldNotBe ViewerCameraState.HOME
            }
        }

        "a click carries the modifier keys held at the press" {
            val picking = ContextPicking()
            withCanvas(picking) {
                onRoot().performKeyInput { keyDown(Key.CtrlLeft) }
                onRoot().performMouseInput { click(Offset(100f, 100f)) }
                onRoot().performKeyInput { keyUp(Key.CtrlLeft) }
                waitForIdle()
                picking.modClicks.size shouldBe 1
                picking.modClicks[0].ctrl shouldBe true
                picking.modClicks[0].meta shouldBe false
                onRoot().performMouseInput { click(Offset(100f, 100f)) }
                waitForIdle()
                picking.modClicks[1] shouldBe PickModifiers()
            }
        }

        "the Menu key and Shift+F10 reach the host" {
            val picking = ContextPicking()
            withCanvas(picking) {
                onRoot().performKeyInput { pressKey(Key.Menu) }
                onRoot().performKeyInput {
                    keyDown(Key.ShiftLeft)
                    pressKey(Key.F10)
                    keyUp(Key.ShiftLeft)
                }
                // F10 without Shift is not a menu key
                onRoot().performKeyInput { pressKey(Key.F10) }
                waitForIdle()
                picking.menuKeys shouldBe 2
            }
        }

        "a host with only the original callbacks still gets its click and ignores the new ones" {
            val legacy = LegacyPicking()
            withCanvas(legacy) {
                onRoot().performMouseInput { click(Offset(100f, 100f)) }
                onRoot().performMouseInput { rightClick(Offset(100f, 100f)) }
                onRoot().performKeyInput { pressKey(Key.Menu) }
                waitForIdle()
                legacy.clicks shouldBe 1
            }
            legacy.onClick(0f, 0f, null, PickModifiers(ctrl = true))
            legacy.clicks shouldBe 2
            legacy.onMenuKey() shouldBe false
        }
    })
