package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.camera.ViewerCameraState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

private const val SIZE = 128
private const val TOGGLES = 10

/** A picking whose [isOverPart] is a Compose state, like the desktop host's hover state (ADR-0068). */
private class StatefulPicking : CanvasPicking {
    var over by mutableStateOf(false)

    override fun onHover(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) = Unit

    override fun onClick(
        x: Float,
        y: Float,
        frame: ScreenFrame?,
    ) = Unit

    override fun onDragStart() = Unit

    override fun onPointerExit() = Unit

    override fun onEscape(): Boolean = false

    override fun isOverPart(): Boolean = over
}

/** The hover cursor is read outside the canvas body: toggling it must not recompose the canvas (ADR-0068). */
@OptIn(ExperimentalTestApi::class)
class ViewerCanvasRecompositionTest :
    StringSpec({
        val square =
            MeshComposition.mergeColored(
                listOf(
                    PlacedMesh(
                        TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 1.0, 0.0)),
                        color = MeshColor(1.0, 0.0, 0.0),
                    ),
                ),
            )

        "toggling the hover cursor recomposes only the cursor scope" {
            val counts = HashMap<String, Int>()
            recompositionProbe = { counts.merge(it, 1, Int::plus) }
            try {
                val picking = StatefulPicking()
                runDesktopComposeUiTest(width = SIZE, height = SIZE) {
                    setContent {
                        ShapeCanvas(
                            square,
                            Modifier.size(SIZE.dp),
                            mutableStateOf(ViewerCameraState.HOME),
                            null,
                            mutableStateOf<CanvasHighlight?>(null),
                            picking,
                        )
                    }
                    waitForIdle()
                    val canvas = counts["canvas"] ?: 0
                    val body = counts["body"] ?: 0
                    val cursor = counts["cursor"] ?: 0
                    repeat(TOGGLES) {
                        picking.over = !picking.over
                        waitForIdle()
                    }
                    counts["canvas"] shouldBe canvas
                    counts["body"] shouldBe body
                    (counts["cursor"] ?: 0) shouldBeGreaterThanOrEqual cursor + TOGGLES
                }
            } finally {
                recompositionProbe = null
            }
        }

        "the probe counts a scope that does recompose (the test is not trivially green)" {
            val counts = HashMap<String, Int>()
            recompositionProbe = { counts.merge(it, 1, Int::plus) }
            try {
                var flag by mutableStateOf(false)
                runDesktopComposeUiTest(width = SIZE, height = SIZE) {
                    setContent {
                        val read = flag
                        SideEffect { recompositionProbe?.invoke("canvas:$read") }
                    }
                    waitForIdle()
                    repeat(TOGGLES) {
                        flag = !flag
                        waitForIdle()
                    }
                    ((counts["canvas:true"] ?: 0) + (counts["canvas:false"] ?: 0)) shouldBeGreaterThanOrEqual TOGGLES
                }
            } finally {
                recompositionProbe = null
            }
        }
    })
