package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The "MCP Server…" entry of the View menu (ADR-0095). */
class McpMenuTest :
    StringSpec({
        val base = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)

        "it comes directly after Show AI Panel, in the same group" {
            val entries = viewMenu()
            val ai = entries.indexOf(MenuEntry.ViewItem(ViewAction.AI_PANEL))
            entries[ai + 1] shouldBe MenuEntry.ViewItem(ViewAction.MCP_SERVER)
            entries[ai + 2] shouldBe MenuEntry.Separator
            entries[ai - 1] shouldBe MenuEntry.Separator
        }

        "it is available in every state, also without a file and in a knowledge workspace" {
            stateOf(ViewAction.MCP_SERVER, base) shouldBe ActionState(true, null)
            stateOf(ViewAction.MCP_SERVER, base.copy(knowledgeOpen = true)) shouldBe ActionState(true, null)
            stateOf(ViewAction.MCP_SERVER, base.copy(editorOpen = true, scriptOpen = true, fileOpen = true)) shouldBe
                ActionState(true, null)
        }

        "it has no shortcut on any system, so no registered shortcut collides" {
            for (os in MenuOs.entries) {
                shortcutOf(ViewAction.MCP_SERVER, os) shouldBe null
                val registered = ViewAction.entries.mapNotNull { shortcutOf(it, os) }
                registered.toSet().size shouldBe registered.size
            }
        }

        "its label ends with the ellipsis and it carries no single-key hint" {
            ViewAction.MCP_SERVER.label shouldBe "MCP Server…"
            ViewAction.MCP_SERVER.keyHint shouldBe null
            iconOf(ViewAction.MCP_SERVER) shouldBe MenuIconId.MCP_SERVER
        }
    })
