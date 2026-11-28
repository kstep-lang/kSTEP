package dev.kstep.desktop.ui

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Density
import dev.kstep.desktop.mcp.McpActivity
import dev.kstep.desktop.mcp.McpPhase
import dev.kstep.desktop.run.SandboxNotice
import dev.kstep.desktop.status.PreviewOriginLabel
import dev.kstep.desktop.status.StatusSegments
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The status bar composed headlessly (ADR-0099): the MCP indicator at the right edge, nothing overlaps. */
class StatusBarSceneTest :
    StringSpec({
        fun measure(
            widthDp: Int,
            phase: McpPhase = McpPhase.Ready,
            withMcp: Boolean = true,
            editable: Boolean = false,
        ): Map<String, Rect> {
            val bounds = HashMap<String, Rect>()
            val scene = ImageComposeScene(width = widthDp, height = 24, density = Density(1f))
            try {
                scene.setContent {
                    StatusBar(
                        fileName = "assembly.kstep.kts",
                        exportText = "Exported bracket.step (12.4 KB)",
                        onShowInFolder = {},
                        sandboxNotice = SandboxNotice("Script runs without sandbox", "no bubblewrap"),
                        statusSegments =
                            StatusSegments.build(null, "● Ok", 12L, 4312, null, null, null, null, 2, 0, true),
                        previewLabel = PreviewOriginLabel.UNSAVED_OLDER,
                        accent = ACCENT_LIGHT,
                        mcp = if (withMcp) McpIndicatorModel(4711, editable, false, McpActivity(phase)) else null,
                        onMcpClick = {},
                        probe = { tag, rect -> bounds[tag] = rect },
                    )
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
            }
            return bounds
        }

        "at the minimum width the indicator is whole at the right edge and overlaps nothing".config(
            timeout = 60.seconds,
        ) {
            val bounds = measure(640)
            val mcp = bounds.getValue(TAG_MCP_INDICATOR)
            (mcp.right <= 640f) shouldBe true
            (mcp.width > 0f) shouldBe true
            bounds.filterKeys { it != TAG_MCP_INDICATOR }.values.forEach { (it.right <= mcp.left + 0.5f) shouldBe true }
        }

        "wide, the indicator keeps its width and sits at the right edge".config(timeout = 60.seconds) {
            val narrow = measure(640).getValue(TAG_MCP_INDICATOR)
            val wide = measure(1600).getValue(TAG_MCP_INDICATOR)
            wide.width shouldBe narrow.width
            (wide.right > 1500f) shouldBe true
        }

        "every phase and the edit permission render, and the edit word makes it wider".config(timeout = 60.seconds) {
            McpPhase.entries.forEach { measure(900, it).containsKey(TAG_MCP_INDICATOR) shouldBe true }
            (
                measure(900, editable = true).getValue(TAG_MCP_INDICATOR).width >
                    measure(900).getValue(TAG_MCP_INDICATOR).width
            ) shouldBe true
        }

        "without a running server there is no indicator".config(timeout = 60.seconds) {
            measure(900, withMcp = false).containsKey(TAG_MCP_INDICATOR) shouldBe false
        }
    })
