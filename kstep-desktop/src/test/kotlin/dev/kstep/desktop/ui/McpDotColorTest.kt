package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.Color
import dev.kstep.desktop.mcp.McpPhase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class McpDotColorTest :
    StringSpec({
        val accent = Color(0xFF1E66F5)
        val error = Color(0xFFB3261E)

        "ready is green in both themes, never the grey of 'off'" {
            for (dark in listOf(false, true)) {
                val c = mcpDotColor(McpPhase.Ready, false, dark, accent, error)
                (c.green > c.red && c.green > c.blue) shouldBe true
                c shouldNotBe DIM
            }
        }

        "a failed call is red whatever else is going on, a busy or just ended call is the accent" {
            mcpDotColor(McpPhase.Failed, true, false, accent, error) shouldBe error
            mcpDotColor(McpPhase.Busy, false, true, accent, error) shouldBe accent
            mcpDotColor(McpPhase.Ready, true, false, accent, error) shouldBe accent
        }
    })
