package dev.kstep.desktop.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The "MCP" segment of the run status (ADR-0095). */
class StatusSegmentsMcpTest :
    StringSpec({
        fun build(mcp: String?) =
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
                mcp = mcp,
            )

        "without the server there is no MCP segment" {
            build(null).none { it.id == "mcp" } shouldBe true
            build(null).none { it.text == "MCP" } shouldBe true
        }

        "with the server it stands right after the AI status" {
            val texts = build("MCP").map { it.text }
            texts[texts.indexOf("AI ready") + 1] shouldBe "MCP"
        }

        "it is the last medium segment to go, after all low ones, and never before an essential one" {
            val segments = build("MCP")
            val wide = StatusSegments.fit(segments, 1000)
            wide.shown shouldBe wide.full
            // drop one segment at a time by narrowing the budget; MCP must outlast every other MEDIUM segment
            var width = wide.full.length
            var mcpGoneAt = -1
            var lastMediumGoneAt = -1
            while (width > 0) {
                val fitted = StatusSegments.fit(segments, width)
                val shown = fitted.shown.split(StatusLineText.SEPARATOR)
                if (mcpGoneAt < 0 && "MCP" !in shown) mcpGoneAt = width
                if ("Large model" !in shown || "A note" !in shown || "3 transparent" !in shown) {
                    if (lastMediumGoneAt < 0) lastMediumGoneAt = width
                }
                width--
            }
            (mcpGoneAt < lastMediumGoneAt) shouldBe true
            val tight = StatusSegments.fit(segments, 1)
            tight.shown.split(StatusLineText.SEPARATOR).toSet() shouldBe setOf("● Ok", "2 hidden", "unsaved changes")
        }

        "the range of the MCP segment points at it inside the shown text" {
            val fitted = StatusSegments.fit(build("MCP"), 1000)
            val range = fitted.ranges.getValue("mcp")
            fitted.shown.substring(range.first, range.last + 1) shouldBe "MCP"
            StatusSegments.fit(build(null), 1000).ranges shouldBe emptyMap()
        }

        "after a segment is dropped the range follows the shortened text" {
            val segments = build("MCP")
            val fitted = StatusSegments.fit(segments, StatusSegments.fit(segments, 1000).full.length - 3)
            val range = fitted.ranges.getValue("mcp")
            fitted.shown.substring(range.first, range.last + 1) shouldBe "MCP"
        }
    })
