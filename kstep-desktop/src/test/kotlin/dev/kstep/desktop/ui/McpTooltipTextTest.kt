package dev.kstep.desktop.ui

import dev.kstep.desktop.mcp.McpActivity
import dev.kstep.desktop.mcp.McpCall
import dev.kstep.desktop.mcp.McpCallOutcome
import dev.kstep.desktop.mcp.McpPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.ZoneId

class McpTooltipTextTest :
    StringSpec({
        val utc = ZoneId.of("UTC")

        "the tooltip names the port, the phase, the permissions and the last calls with a fixed zone" {
            val activity =
                McpActivity(
                    McpPhase.Failed,
                    0,
                    listOf(McpCall(2, "kstep_desktop_get_script", 3_661_000L, McpCallOutcome.Failed)),
                    1,
                )
            val text = McpTooltipText.of(4711, editable = true, files = false, activity = activity, zone = utc)
            text shouldContain "127.0.0.1:4711"
            text shouldContain "last call failed"
            text shouldContain "Agents may make changes"
            text shouldContain "01:01:01  kstep_desktop_get_script  failed"
        }

        "read only agents get no permission line, a running call is marked" {
            val activity = McpActivity(McpPhase.Busy, 1, listOf(McpCall(1, "ping", 0, null)), 0)
            val text = McpTooltipText.of(1, editable = false, files = false, activity = activity, zone = utc)
            text shouldNotContain "Agents may"
            text shouldContain "00:00:00  ping  running"
            McpTooltipText.permissions(false, false) shouldBe null
        }

        "the description says state and permission for screen readers" {
            McpTooltipText.description(editable = true, files = true, phase = McpPhase.Busy) shouldContain "working"
            McpTooltipText.description(editable = true, files = true, phase = McpPhase.Busy) shouldContain
                "save, export"
        }
    })
