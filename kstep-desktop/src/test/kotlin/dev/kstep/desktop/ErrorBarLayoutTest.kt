package dev.kstep.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.ui.ErrorBar
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan

/** Layout regression: the error bar must not take the canvas' height (it sits below the weighted canvas). */
class ErrorBarLayoutTest :
    StringSpec({
        "the canvas keeps its height next to a collapsed error bar" {
            var canvasH = 0
            var barH = 0
            val scene = ImageComposeScene(width = 800, height = 600)
            try {
                scene.setContent {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxSize().onSizeChanged { canvasH = it.height })
                        ErrorBar(
                            listOf(DesktopError("E1", 1, 2, "boom"), DesktopError("E2", 3, 4, "bang")),
                            dark = false,
                            maxHeight = 200.dp,
                            modifier = Modifier.onSizeChanged { barH = it.height },
                        )
                    }
                }
                scene.render()
            } finally {
                scene.close()
            }
            canvasH shouldBeGreaterThan 400
            barH shouldBeGreaterThan 0
            barH shouldBeLessThan 240
        }

        "an expanded error bar does not crash, is bounded and leaves the canvas its height" {
            var canvasH = 0
            var barH = 0
            val errors = (1..30).map { DesktopError("E$it", it, 2, "boom $it") }
            val scene = ImageComposeScene(width = 800, height = 600)
            try {
                scene.setContent {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxSize().onSizeChanged { canvasH = it.height })
                        ErrorBar(
                            errors,
                            dark = false,
                            maxHeight = 200.dp,
                            modifier = Modifier.onSizeChanged { barH = it.height },
                            initiallyExpanded = true,
                        )
                    }
                }
                scene.render()
            } finally {
                scene.close()
            }
            canvasH shouldBeGreaterThan 0
            barH shouldBeGreaterThan 100
            // maxHeight (200) for the list plus the button row (about 48)
            barH shouldBeLessThan 260
        }
    })
