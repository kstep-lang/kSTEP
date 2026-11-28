package dev.kstep.desktop.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The run status carries no MCP segment any more: the indicator lives on its own in the status bar (ADR-0099). */
class StatusSegmentsMcpTest :
    StringSpec({
        val segments =
            StatusSegments.build(
                knowledgeLabel = null,
                runState = "● Ok",
                durationMs = 1234L,
                triangles = 12345,
                triangleWarning = "Large model",
                note = "A note",
                transientNotice = "Saved",
                aiStatus = "AI ready",
                hidden = 2,
                transparent = 3,
                dirty = true,
            )

        "the run status has no MCP segment, whatever the width" {
            segments.none { it.text.startsWith("MCP") } shouldBe true
            StatusSegments.fit(segments, 1000).full.contains("MCP") shouldBe false
        }

        "the drop ranks of the low segments stay unique" {
            val low = segments.filter { it.priority == SegmentPriority.LOW }
            low.map { it.dropRank }.distinct().size shouldBe low.size
        }

        "a narrow line still keeps the essential segments" {
            val tight = StatusSegments.fit(segments, 1)
            tight.shown.split(StatusLineText.SEPARATOR).toSet() shouldBe setOf("● Ok", "2 hidden", "unsaved changes")
        }
    })
