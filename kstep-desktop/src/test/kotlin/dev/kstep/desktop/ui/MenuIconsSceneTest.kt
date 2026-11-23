package dev.kstep.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.menu.MenuIconId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Every menu icon rendered headlessly at 2x: tinted, not empty, with a transparent outer pixel row (ADR-0073). */
class MenuIconsSceneTest :
    StringSpec({
        val tint = Color(0xFF2B2B2B)

        fun renderPixels(id: MenuIconId): IntArray {
            val scene = ImageComposeScene(width = 32, height = 32, density = Density(2f))
            try {
                scene.setContent {
                    Image(
                        painter = TintedMenuIconPainter(rememberVectorPainter(KstepMenuIcons.vectorFor(id)), tint),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                }
                scene.render()
                val image = scene.render(400_000_000)
                val bitmap =
                    org.jetbrains.skia.Bitmap
                        .makeFromImage(image)
                val out = IntArray(32 * 32)
                for (y in 0 until 32) for (x in 0 until 32) out[y * 32 + x] = bitmap.getColor(x, y)
                return out
            } finally {
                scene.close()
            }
        }

        for (id in MenuIconId.entries) {
            "$id renders as tinted strokes inside the 16 dp box" {
                val px = renderPixels(id)
                val alpha = { c: Int -> (c ushr 24) and 0xFF }
                (px.count { alpha(it) > 0 } >= 20) shouldBe true
                for (i in 0 until 32) {
                    for (edge in listOf(i, 31 * 32 + i, i * 32, i * 32 + 31)) alpha(px[edge]) shouldBe 0
                }
                val solid = px.filter { alpha(it) == 255 }
                (solid.size >= 4) shouldBe true
                for (c in solid) {
                    ((c shr 16) and 0xFF) shouldBe 0x2B
                    ((c shr 8) and 0xFF) shouldBe 0x2B
                    (c and 0xFF) shouldBe 0x2B
                }
            }
        }

        "the Swing bitmap is 16 x density pixels and has drawn content" {
            var image: java.awt.image.BufferedImage? = null
            val scene = ImageComposeScene(width = 32, height = 32, density = Density(2f))
            try {
                scene.setContent {
                    val painter =
                        TintedMenuIconPainter(rememberVectorPainter(KstepMenuIcons.vectorFor(MenuIconId.SAVE)), tint)
                    if (image == null) image = renderMenuIcon(painter, Density(2f))
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
            }
            val img = image!!
            img.width shouldBe 32
            img.height shouldBe 32
            var drawn = 0
            for (y in 0 until 32) for (x in 0 until 32) if ((img.getRGB(x, y) ushr 24) > 0) drawn++
            (drawn >= 20) shouldBe true
        }
    })
