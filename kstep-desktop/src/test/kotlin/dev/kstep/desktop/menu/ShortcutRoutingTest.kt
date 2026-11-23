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

        fun l(c: Char) = ShortcutKey.Letter(c)

        "every menu-owned shortcut is routed, never NotMenuOwned (with the editor focused)" {
            for (os in MenuOs.entries) {
                for ((sc, owned) in ShortcutRouting.menuOwnedShortcuts(os)) {
                    for (base in listOf(noScript, script, workspace)) {
                        val ctx = base.copy(editorFocused = true)
                        val route = ShortcutRouting.route(sc.key, true, sc.shift, sc.alt, os, ctx)
                        route shouldNotBe Route.NotMenuOwned
                        val state =
                            when (owned) {
                                is ShortcutRouting.MenuOwned.File -> stateOf(owned.action, ctx)
                                is ShortcutRouting.MenuOwned.Edit -> stateOf(owned.action, ctx)
                                is ShortcutRouting.MenuOwned.View -> stateOf(owned.action, ctx)
                            }
                        route shouldBe
                            if (state.enabled) Route.PassToMenu else Route.ShowReason(checkNotNull(state.reason))
                    }
                }
            }
        }

        "the table of each operating system, shortcut by shortcut" {
            fun owned(os: MenuOs) = ShortcutRouting.menuOwnedShortcuts(os)
            val mac = owned(MenuOs.MAC)
            mac[Shortcut(l('Z'))] shouldBe ShortcutRouting.MenuOwned.Edit(EditAction.UNDO)
            mac[Shortcut(l('Z'), shift = true)] shouldBe ShortcutRouting.MenuOwned.Edit(EditAction.REDO)
            mac[Shortcut(l('F'), alt = true)] shouldBe ShortcutRouting.MenuOwned.Edit(EditAction.REPLACE)
            mac[Shortcut(ShortcutKey.Up, alt = true)] shouldBe ShortcutRouting.MenuOwned.View(ViewAction.PREVIOUS_BLOCK)
            mac[Shortcut(l('Y'))] shouldBe null
            for (os in listOf(MenuOs.WINDOWS, MenuOs.LINUX)) {
                val t = owned(os)
                t[Shortcut(l('Y'))] shouldBe ShortcutRouting.MenuOwned.Edit(EditAction.REDO)
                t[Shortcut(l('H'))] shouldBe ShortcutRouting.MenuOwned.Edit(EditAction.REPLACE)
                t[Shortcut(ShortcutKey.Down, alt = true)] shouldBe ShortcutRouting.MenuOwned.View(ViewAction.NEXT_BLOCK)
                t[Shortcut(l('R'))] shouldBe ShortcutRouting.MenuOwned.View(ViewAction.RUN)
                t[Shortcut(l('K'))] shouldBe ShortcutRouting.MenuOwned.View(ViewAction.AI_PANEL)
                t[Shortcut(l('Z'), shift = true)] shouldBe null
            }
        }

        "no shortcut is registered twice and the editor aliases never overlap the menu" {
            for (os in MenuOs.entries) {
                val actions =
                    FileAction.entries.mapNotNull { it.shortcut } +
                        EditAction.entries.mapNotNull { shortcutOf(it, os) } +
                        ViewAction.entries.mapNotNull { shortcutOf(it, os) }
                actions.toSet().size shouldBe actions.size
                ShortcutRouting.menuOwnedShortcuts(os).size shouldBe actions.size
                ShortcutRouting.menuOwnedShortcuts(os).keys.intersect(EditorOwnedShortcuts.of(os)) shouldBe emptySet()
            }
        }

        "Undo and Redo without the editor focus are not menu-owned, even when enabled" {
            val ctx = script.copy(editorOpen = true, canUndo = true, canRedo = true, editorFocused = false)
            for (os in MenuOs.entries) {
                val undo = checkNotNull(shortcutOf(EditAction.UNDO, os))
                val redo = checkNotNull(shortcutOf(EditAction.REDO, os))
                ShortcutRouting.route(undo.key, true, undo.shift, undo.alt, os, ctx) shouldBe Route.NotMenuOwned
                ShortcutRouting.route(redo.key, true, redo.shift, redo.alt, os, ctx) shouldBe Route.NotMenuOwned
            }
        }

        "Undo with the editor focus and no history shows its reason and takes the key" {
            val ctx = script.copy(editorOpen = true, editorFocused = true)
            ShortcutRouting.route(l('Z'), true, false, false, MenuOs.MAC, ctx) shouldBe
                Route.ShowReason(MenuReasons.NOTHING_TO_UNDO)
            ShortcutRouting.route(l('Z'), true, false, false, MenuOs.MAC, ctx.copy(canUndo = true)) shouldBe
                Route.PassToMenu
        }

        "Run without a file and the AI panel in a workspace show their reasons" {
            ShortcutRouting.route(l('R'), true, false, false, MenuOs.LINUX, noScript) shouldBe
                Route.ShowReason(MenuReasons.OPEN_SCRIPT_FIRST)
            ShortcutRouting.route(l('K'), true, false, false, MenuOs.MAC, workspace) shouldBe
                Route.ShowReason(MenuReasons.KNOWLEDGE_UNAVAILABLE)
        }

        "single keys, a missing primary key and an unknown Alt combination are not menu-owned" {
            for (os in MenuOs.entries) {
                ShortcutRouting.route(l('O'), false, false, false, os, script) shouldBe Route.NotMenuOwned
                ShortcutRouting.route(l('O'), true, false, true, os, script) shouldBe Route.NotMenuOwned
                ShortcutRouting.route(l('S'), true, true, false, os, script) shouldBe Route.NotMenuOwned
                for (k in listOf('T', 'V', 'I', 'G')) {
                    ShortcutRouting.route(l(k), true, false, false, os, script) shouldBe Route.NotMenuOwned
                }
            }
        }

        "Save without a script shows its reason" {
            ShortcutRouting.route(l('S'), true, false, false, MenuOs.MAC, noScript) shouldBe
                Route.ShowReason(MenuReasons.OPEN_SCRIPT_FIRST)
        }

        "Export in a workspace shows the workspace reason" {
            ShortcutRouting.route(l('E'), true, false, false, MenuOs.LINUX, workspace) shouldBe
                Route.ShowReason(MenuReasons.KNOWLEDGE_UNAVAILABLE)
        }

        "the clipboard keys belong to the editor only with the real editor focus" {
            val ctx = script.copy(editorOpen = true, editorFocusTarget = true)
            for (os in MenuOs.entries) {
                for (k in listOf('X', 'C', 'V', 'A')) {
                    ShortcutRouting.route(l(k), true, false, false, os, ctx) shouldBe Route.NotMenuOwned
                    ShortcutRouting.route(l(k), true, false, false, os, ctx.copy(editorFocused = true)) shouldNotBe
                        Route.NotMenuOwned
                }
            }
        }

        "a disabled clipboard entry with the editor focus shows its reason" {
            val ctx = script.copy(editorOpen = true, editorFocused = true, editorFocusTarget = true)
            val mac = MenuOs.MAC
            ShortcutRouting.route(l('X'), true, false, false, mac, ctx) shouldBe
                Route.ShowReason(MenuReasons.SELECT_TEXT_FIRST)
            ShortcutRouting.route(l('C'), true, false, false, mac, ctx) shouldBe
                Route.ShowReason(MenuReasons.SELECT_TEXT_FIRST)
            ShortcutRouting.route(l('V'), true, false, false, mac, ctx) shouldBe
                Route.ShowReason(MenuReasons.CLIPBOARD_EMPTY)
            ShortcutRouting.route(l('A'), true, false, false, mac, ctx) shouldBe Route.PassToMenu
            val full = ctx.copy(textSelected = true, clipboardHasText = true)
            for (k in listOf('X', 'C', 'V', 'A')) {
                ShortcutRouting.route(l(k), true, false, false, MenuOs.WINDOWS, full) shouldBe Route.PassToMenu
            }
            val noTarget = ctx.copy(editorFocusTarget = false)
            ShortcutRouting.route(l('V'), true, false, false, mac, noTarget) shouldBe
                Route.ShowReason(MenuReasons.CLICK_INTO_EDITOR)
        }

        "the clipboard shortcuts do not collide with File, View or each other" {
            for (os in MenuOs.entries) {
                val all =
                    FileAction.entries.mapNotNull { it.shortcut } +
                        EditAction.entries.mapNotNull {
                            shortcutOf(
                                it,
                                os,
                            )
                        } +
                        ViewAction.entries.mapNotNull { shortcutOf(it, os) }
                all.toSet().size shouldBe all.size
                for (k in listOf('X', 'C', 'V', 'A')) {
                    EditorOwnedShortcuts.of(os).contains(Shortcut(l(k))) shouldBe false
                }
            }
        }

        "Ctrl+Shift+V and the Alt variants are not menu-owned" {
            for (os in MenuOs.entries) {
                for (k in listOf('X', 'C', 'V', 'A')) {
                    ShortcutRouting.route(l(k), true, true, false, os, script) shouldBe Route.NotMenuOwned
                    ShortcutRouting.route(l(k), true, false, true, os, script) shouldBe Route.NotMenuOwned
                }
            }
        }

        "ClipboardKeys swallow on macOS and dispatch elsewhere" {
            ClipboardKeys.of(MenuOs.MAC) shouldBe ClipboardKeys.Handling.SWALLOW
            ClipboardKeys.of(MenuOs.WINDOWS) shouldBe ClipboardKeys.Handling.DISPATCH
            ClipboardKeys.of(MenuOs.LINUX) shouldBe ClipboardKeys.Handling.DISPATCH
            ClipboardKeys.actionOf(l('A')) shouldBe EditAction.SELECT_ALL
            ClipboardKeys.actionOf(l('C')) shouldBe EditAction.COPY
            ClipboardKeys.actionOf(l('V')) shouldBe EditAction.PASTE
            ClipboardKeys.actionOf(l('X')) shouldBe EditAction.CUT
            ClipboardKeys.actionOf(l('Z')) shouldBe null
            ClipboardKeys.actionOf(ShortcutKey.Up) shouldBe null
            ClipboardKeys.ACTIONS shouldBe
                setOf(EditAction.CUT, EditAction.COPY, EditAction.PASTE, EditAction.SELECT_ALL)
        }
    })
