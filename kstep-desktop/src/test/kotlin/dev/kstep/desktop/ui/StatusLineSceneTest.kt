package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.run.SandboxNotice
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The status line composed headlessly at a window width of 900 dp: nothing overlaps, the sandbox notice is whole. */
class StatusLineSceneTest :
    StringSpec({
        val sandbox = SandboxNotice("Script runs without sandbox", "no bubblewrap")

        fun measure(
            widthDp: Int,
            fileName: String?,
            export: String?,
            sandboxNotice: SandboxNotice?,
            status: String,
        ): Map<String, Rect> {
            val bounds = HashMap<String, Rect>()
            val scene = ImageComposeScene(width = widthDp, height = 40, density = Density(1f))
            try {
                scene.setContent {
                    Row(Modifier.width(widthDp.dp).height(40.dp)) {
                        StatusLine(
                            fileName = fileName,
                            exportText = export,
                            onShowInFolder = {},
                            sandboxNotice = sandboxNotice,
                            statusText = status,
                            probe = { tag, rect -> bounds[tag] = rect },
                        )
                    }
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
            }
            return bounds
        }

        fun noOverlap(bounds: Map<String, Rect>) {
            val rects = bounds.entries.toList()
            for (i in rects.indices) {
                for (j in i + 1 until rects.size) {
                    val a = rects[i].value
                    val b = rects[j].value
                    val overlap = a.left < b.right - 0.5f && b.left < a.right - 0.5f
                    check(!overlap) { "${rects[i].key} $a overlaps ${rects[j].key} $b" }
                }
            }
        }

        val longName = "a-very-long-script-name-for-a-bracket-assembly-of-many-parts.kstep.kts"
        val statusText = "● Running…  ·  12 ms  ·  4,312 triangles"
        val exportText = "Exported bracket.step (12.4 KB)"

        "export line, sandbox notice, long name and status do not overlap and stay inside the row".config(
            timeout = 60.seconds,
        ) {
            // 900 dp window minus the sidebar (240 dp) and the toolbar buttons is not simulated: 660 dp is a hard case.
            val bounds = measure(660, longName, exportText, sandbox, statusText)
            bounds.keys shouldBe setOf("status.file", "status.export", "status.sandbox", "status.run")
            noOverlap(bounds)
            bounds.values.forEach { (it.left >= 0f && it.right <= 660f) shouldBe true }
        }

        "without a workspace at 900 dp everything fits and the sandbox notice has its full width".config(
            timeout = 60.seconds,
        ) {
            val wide = measure(900, longName, exportText, sandbox, statusText)
            val narrow = measure(520, longName, exportText, sandbox, statusText)
            noOverlap(wide)
            noOverlap(narrow)
            val natural = measure(2000, longName, exportText, sandbox, statusText)
            // the sandbox notice is never cut: same width at every row width
            wide.getValue("status.sandbox").width shouldBe natural.getValue("status.sandbox").width
            narrow.getValue("status.sandbox").width shouldBe natural.getValue("status.sandbox").width
            narrow.getValue("status.file").width shouldBeGreaterThanOrEqual 63f
        }

        "without an export line there is no export node" {
            val bounds = measure(900, "a.kstep.kts", null, sandbox, statusText)
            bounds.containsKey("status.export") shouldBe false
        }

        "nothing to show right of the name leaves no separator or run node".config(timeout = 60.seconds) {
            val bounds = measure(900, "a.kstep.kts", null, null, "")
            bounds.keys shouldBe setOf("status.file")
        }
    })
