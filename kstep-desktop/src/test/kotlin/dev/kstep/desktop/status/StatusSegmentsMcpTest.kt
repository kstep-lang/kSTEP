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

        "with permission to make changes the segment reads MCP edit and is essential" {
            val segment =
                StatusSegments
                    .build(
                        knowledgeLabel = null,
                        runState = "● Ok",
                        durationMs = null,
                        triangles = null,
                        triangleWarning = null,
                        note = null,
                        transientNotice = null,
                        aiStatus = null,
                        hidden = 0,
                        transparent = 0,
                        dirty = false,
                        mcp = "MCP",
                        mcpEditable = true,
                    ).single { it.id == "mcp" }
            segment.text shouldBe "MCP edit"
            segment.priority shouldBe SegmentPriority.ESSENTIAL
        }

        "with the file permission the segment reads MCP files in the same place, essential" {
            val segment =
                StatusSegments
                    .build(
                        knowledgeLabel = null,
                        runState = "● Ok",
                        durationMs = null,
                        triangles = null,
                        triangleWarning = null,
                        note = null,
                        transientNotice = null,
                        aiStatus = null,
                        hidden = 0,
                        transparent = 0,
                        dirty = false,
                        mcp = "MCP",
                        mcpEditable = true,
                        mcpFiles = true,
                    ).single { it.id == "mcp" }
            segment.text shouldBe "MCP files"
            segment.priority shouldBe SegmentPriority.ESSENTIAL
        }

        "without the file permission the segment stays MCP edit" {
            val files =
                StatusSegments
                    .build(
                        null,
                        "● Ok",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0,
                        0,
                        false,
                        mcp = "MCP",
                        mcpEditable = true,
                        mcpFiles = false,
                    ).single { it.id == "mcp" }
            files.text shouldBe "MCP edit"
        }

        "without the permission it stays the quiet MCP segment of medium priority" {
            val segment = build("MCP").single { it.id == "mcp" }
            segment.text shouldBe "MCP"
            segment.priority shouldBe SegmentPriority.MEDIUM
            segment.dropRank shouldBe 1
        }

        "MCP edit survives a narrow line next to the state, hidden and unsaved changes" {
            val segments =
                StatusSegments.build(
                    knowledgeLabel = null,
                    runState = "● Running…",
                    durationMs = 1234L,
                    triangles = 12345,
                    triangleWarning = "Large model",
                    note = "A note",
                    transientNotice = "Saved",
                    aiStatus = "AI ready",
                    hidden = 3,
                    transparent = 2,
                    dirty = true,
                    mcp = "MCP",
                    mcpEditable = true,
                )
            val fitted = StatusSegments.fit(segments, 40)
            val shown = fitted.shown.split(StatusLineText.SEPARATOR)
            listOf("MCP edit", "● Running…", "3 hidden", "unsaved changes").forEach { (it in shown) shouldBe true }
            val range = fitted.ranges.getValue("mcp")
            fitted.shown.substring(range.first, range.last + 1) shouldBe "MCP edit"
        }
    })
