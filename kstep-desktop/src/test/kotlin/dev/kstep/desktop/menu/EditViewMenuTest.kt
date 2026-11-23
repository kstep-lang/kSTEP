package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The Edit and View menus (ADR-0074): the order, the states, the texts, the registered shortcuts. */
class EditViewMenuTest :
    StringSpec({
        val base = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)
        val open = base.copy(scriptOpen = true, editorOpen = true, fileOpen = true, visibilityAvailable = true)

        "the Edit menu lists its entries in order with the separators" {
            editMenu() shouldBe
                listOf(
                    MenuEntry.EditItem(EditAction.UNDO),
                    MenuEntry.EditItem(EditAction.REDO),
                    MenuEntry.Separator,
                    MenuEntry.EditItem(EditAction.FIND),
                    MenuEntry.EditItem(EditAction.REPLACE),
                    MenuEntry.Separator,
                    MenuEntry.EditItem(EditAction.GO_TO_LINE),
                )
            MenuTitles.EDIT shouldBe "Edit"
            MenuTitles.VIEW shouldBe "View"
        }

        "the View menu lists its entries in order with the separators" {
            viewMenu() shouldBe
                listOf(
                    MenuEntry.ViewItem(ViewAction.RUN),
                    MenuEntry.Separator,
                    MenuEntry.ViewItem(ViewAction.HOME),
                    MenuEntry.ViewItem(ViewAction.PREVIOUS_BLOCK),
                    MenuEntry.ViewItem(ViewAction.NEXT_BLOCK),
                    MenuEntry.Separator,
                    MenuEntry.ViewItem(ViewAction.TRANSPARENT),
                    MenuEntry.ViewItem(ViewAction.HIDE),
                    MenuEntry.ViewItem(ViewAction.ISOLATE),
                    MenuEntry.ViewItem(ViewAction.SHOW_ALL),
                    MenuEntry.Separator,
                    MenuEntry.ViewItem(ViewAction.AI_PANEL),
                )
        }

        "every Edit entry needs an open editor" {
            for (a in EditAction.entries) stateOf(a, base) shouldBe ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
        }

        "Undo and Redo need history, Find, Replace and Go to Line only an editor" {
            stateOf(EditAction.UNDO, open) shouldBe ActionState(false, MenuReasons.NOTHING_TO_UNDO)
            stateOf(EditAction.REDO, open) shouldBe ActionState(false, MenuReasons.NOTHING_TO_REDO)
            stateOf(EditAction.UNDO, open.copy(canUndo = true)) shouldBe ActionState(true, null)
            stateOf(EditAction.REDO, open.copy(canRedo = true)) shouldBe ActionState(true, null)
            for (a in listOf(EditAction.FIND, EditAction.REPLACE, EditAction.GO_TO_LINE)) {
                stateOf(a, open) shouldBe ActionState(true, null)
            }
        }

        "Run needs a file, Home is always on" {
            stateOf(ViewAction.RUN, base) shouldBe ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
            stateOf(ViewAction.RUN, open) shouldBe ActionState(true, null)
            stateOf(ViewAction.HOME, base) shouldBe ActionState(true, null)
        }

        "Previous and Next Block need a folder" {
            for (a in listOf(ViewAction.PREVIOUS_BLOCK, ViewAction.NEXT_BLOCK)) {
                stateOf(a, open) shouldBe ActionState(false, MenuReasons.NO_FOLDER_OPEN)
                stateOf(a, open.copy(knowledgeOpen = true)) shouldBe ActionState(true, null)
            }
        }

        "the visibility entries check the model first, then the selection" {
            for (a in listOf(ViewAction.TRANSPARENT, ViewAction.HIDE, ViewAction.ISOLATE)) {
                // no model and no selection: the first reason wins
                stateOf(a, base) shouldBe ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
                stateOf(a, open) shouldBe ActionState(false, MenuReasons.SELECT_PART_FIRST)
                stateOf(a, open.copy(hasSelection = true)) shouldBe ActionState(true, null)
            }
        }

        "Show All needs a model and something that changed" {
            stateOf(ViewAction.SHOW_ALL, base) shouldBe ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
            stateOf(ViewAction.SHOW_ALL, open.copy(nothingChanged = true)) shouldBe
                ActionState(false, MenuReasons.NOTHING_CHANGED)
            stateOf(ViewAction.SHOW_ALL, open) shouldBe ActionState(true, null)
        }

        "the AI panel is off in a workspace" {
            stateOf(ViewAction.AI_PANEL, open) shouldBe ActionState(true, null)
            stateOf(ViewAction.AI_PANEL, open.copy(knowledgeOpen = true)) shouldBe
                ActionState(false, MenuReasons.KNOWLEDGE_UNAVAILABLE)
        }

        "a disabled entry registers no shortcut, Undo and Redo also need the editor focus" {
            for (os in MenuOs.entries) {
                for (a in EditAction.entries) registeredShortcut(a, base, os) shouldBe null
                for (a in ViewAction.entries) {
                    if (!stateOf(a, base).enabled) registeredShortcut(a, base, os) shouldBe null
                }
                val ready = open.copy(canUndo = true, canRedo = true)
                registeredShortcut(EditAction.UNDO, ready, os) shouldBe null
                registeredShortcut(EditAction.REDO, ready, os) shouldBe null
                registeredShortcut(EditAction.UNDO, ready.copy(editorFocused = true), os) shouldBe
                    shortcutOf(EditAction.UNDO, os)
                registeredShortcut(EditAction.FIND, ready, os) shouldBe shortcutOf(EditAction.FIND, os)
            }
        }

        "an entry with a single-key hint has no shortcut on any system" {
            for (os in MenuOs.entries) {
                for (a in ViewAction.entries) {
                    if (a.keyHint != null) shortcutOf(a, os) shouldBe null
                }
            }
        }

        "the menu text carries the key hint in brackets, two blanks before it" {
            ViewAction.HOME.menuText(MenuOs.MAC) shouldBe "Home  (H)"
            ViewAction.TRANSPARENT.menuText(MenuOs.LINUX) shouldBe "Toggle Transparent  (T)"
            ViewAction.HIDE.menuText(MenuOs.WINDOWS) shouldBe "Toggle Hidden  (V)"
            ViewAction.ISOLATE.menuText(MenuOs.MAC) shouldBe "Isolate  (I)"
            ViewAction.SHOW_ALL.menuText(MenuOs.MAC) shouldBe "Show All  (⇧V)"
            ViewAction.SHOW_ALL.menuText(MenuOs.LINUX) shouldBe "Show All  (Shift+V)"
            ViewAction.RUN.menuText(MenuOs.MAC) shouldBe "Run Script"
        }

        "the shortcuts are shown per system, with Shift, Option and the arrows" {
            val l = ShortcutKey.Letter('F')
            Shortcut(l, shift = true, alt = true).display(MenuOs.MAC) shouldBe "⇧⌥⌘F"
            Shortcut(l, alt = true).display(MenuOs.MAC) shouldBe "⌥⌘F"
            Shortcut(ShortcutKey.Up, alt = true).display(MenuOs.MAC) shouldBe "⌥⌘↑"
            Shortcut(ShortcutKey.Down, alt = true).display(MenuOs.MAC) shouldBe "⌥⌘↓"
            Shortcut(ShortcutKey.Up, alt = true).display(MenuOs.WINDOWS) shouldBe "Ctrl+Alt+Up"
            Shortcut(ShortcutKey.Down, alt = true).display(MenuOs.LINUX) shouldBe "Ctrl+Alt+Down"
            Shortcut(l, shift = true, alt = true).display(MenuOs.LINUX) shouldBe "Ctrl+Shift+Alt+F"
            KeyHint('V', true).hintText(MenuOs.MAC) shouldBe "⇧V"
            KeyHint('T', false).hintText(MenuOs.WINDOWS) shouldBe "T"
        }

        "the toolbar texts are the shared reason constants" {
            MenuReasons.SELECT_PART_FIRST shouldBe "Select a part first"
            MenuReasons.NOTHING_CHANGED shouldBe "Nothing is hidden or transparent"
            MenuReasons.NOTHING_TO_UNDO shouldBe "Nothing to undo"
            MenuReasons.NOTHING_TO_REDO shouldBe "Nothing to redo"
        }

        "a menu text beginning with a bracket never reaches Swing as HTML" {
            MenuText.safe("<html>x") shouldBe "‹html>x"
        }
    })
