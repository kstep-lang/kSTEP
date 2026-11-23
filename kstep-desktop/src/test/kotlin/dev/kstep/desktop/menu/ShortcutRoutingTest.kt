package dev.kstep.desktop.menu

import dev.kstep.desktop.menu.ShortcutRouting.Route
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** The key handler never runs a menu-owned shortcut itself (ADR-0072): it passes it on or shows the reason. */
class ShortcutRoutingTest :
    StringSpec({
        val noScript = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)
        val script = MenuContext(scriptOpen = true, knowledgeOpen = false, exportEnabled = true)
        val workspace = MenuContext(scriptOpen = true, knowledgeOpen = true, exportEnabled = false)

        "the menu owns exactly four shortcuts" {
            for (os in MenuOs.entries) {
                ShortcutRouting.menuOwnedShortcuts(os).keys shouldBe
                    setOf(Shortcut('O'), Shortcut('O', true), Shortcut('S'), Shortcut('E'))
            }
        }

        "every menu-owned shortcut is routed, never NotMenuOwned" {
            for (os in MenuOs.entries) {
                for ((sc, action) in ShortcutRouting.menuOwnedShortcuts(os)) {
                    for (ctx in listOf(noScript, script, workspace)) {
                        val route = ShortcutRouting.route(sc.key, true, sc.shift, false, os, ctx)
                        route shouldNotBe Route.NotMenuOwned
                        val state = stateOf(action, ctx)
                        route shouldBe
                            if (state.enabled) Route.PassToMenu else Route.ShowReason(checkNotNull(state.reason))
                    }
                }
            }
        }

        "other keys, a missing primary key and Alt are not menu-owned" {
            for (os in MenuOs.entries) {
                for (k in listOf('R', 'K', 'H')) {
                    ShortcutRouting.route(k, true, false, false, os, script) shouldBe Route.NotMenuOwned
                }
                ShortcutRouting.route('O', false, false, false, os, script) shouldBe Route.NotMenuOwned
                ShortcutRouting.route('O', true, false, true, os, script) shouldBe Route.NotMenuOwned
                ShortcutRouting.route('S', true, true, false, os, script) shouldBe Route.NotMenuOwned
            }
        }

        "Save without a script shows its reason" {
            ShortcutRouting.route('S', true, false, false, MenuOs.MAC, noScript) shouldBe
                Route.ShowReason(MenuReasons.OPEN_SCRIPT_FIRST)
        }

        "Export in a workspace shows the workspace reason" {
            ShortcutRouting.route('E', true, false, false, MenuOs.LINUX, workspace) shouldBe
                Route.ShowReason(MenuReasons.KNOWLEDGE_UNAVAILABLE)
        }
    })
