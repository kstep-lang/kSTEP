package dev.kstep.viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.unit.dp
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode

private const val PROBE_SIZE = 128

/**
 * The probe that decides the transparency draw path of ADR-0055: does `drawVertices` with `BlendMode.DST` take the
 * per-vertex ALPHA, so one batch can mix opaque and translucent triangles? If it does not, the canvas must split the
 * triangles into runs of equal mode (see ViewerCanvas).
 */
class SkiaVertexAlphaProbeTest :
    StringSpec({
        fun channel(
            pixel: Int,
            shift: Int,
        ) = (pixel ushr shift) and 0xFF

        "drawVertices with BlendMode.DST blends a per-vertex alpha over what is already drawn" {
            val scene = ImageComposeScene(width = PROBE_SIZE, height = PROBE_SIZE)
            try {
                scene.setContent {
                    Canvas(Modifier.size(PROBE_SIZE.dp)) {
                        drawIntoCanvas { canvas ->
                            canvas.skiaCanvas.clear(Color.makeRGB(255, 255, 255))
                            val square =
                                floatArrayOf(
                                    5f,
                                    5f,
                                    PROBE_SIZE - 5f,
                                    5f,
                                    5f,
                                    PROBE_SIZE - 5f,
                                    5f,
                                    PROBE_SIZE - 5f,
                                    PROBE_SIZE - 5f,
                                    5f,
                                    PROBE_SIZE - 5f,
                                    PROBE_SIZE - 5f,
                                )
                            val translucentBlue = Color.makeARGB(128, 0, 0, 255)
                            canvas.skiaCanvas.drawVertices(
                                VertexMode.TRIANGLES,
                                square,
                                IntArray(6) { translucentBlue },
                                null,
                                null,
                                BlendMode.DST,
                                Paint(),
                            )
                        }
                    }
                }
                scene.render()
                val image = scene.render()
                val bitmap = Bitmap()
                bitmap.allocN32Pixels(PROBE_SIZE, PROBE_SIZE)
                image.readPixels(bitmap)
                val p = bitmap.getColor(PROBE_SIZE / 2, PROBE_SIZE / 2)
                println("PROBE pixel=${channel(p, 24)},${channel(p, 16)},${channel(p, 8)},${channel(p, 0)}")
                // white under 50 % blue: red and green drop to about half, blue stays high
                (channel(p, 16) in 100..160) shouldBe true
                (channel(p, 8) in 100..160) shouldBe true
                (channel(p, 0) > 230) shouldBe true
            } finally {
                scene.close()
            }
        }
    })
