package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Cut, Copy, Paste and Select All (ADR-0075): states, reasons, registered shortcuts, focus target, paste text. */
class ClipboardMenuTest :
    StringSpec({
        val base = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)
        val open = base.copy(scriptOpen = true, editorOpen = true, editorFocusTarget = true)
        val clip = listOf(EditAction.CUT, EditAction.COPY, EditAction.PASTE, EditAction.SELECT_ALL)

        "the shortcuts are X, C, V, A with the primary key on every OS" {
            for (os in MenuOs.entries) {
                shortcutOf(EditAction.CUT, os) shouldBe Shortcut(ShortcutKey.Letter('X'))
                shortcutOf(EditAction.COPY, os) shouldBe Shortcut(ShortcutKey.Letter('C'))
                shortcutOf(EditAction.PASTE, os) shouldBe Shortcut(ShortcutKey.Letter('V'))
                shortcutOf(EditAction.SELECT_ALL, os) shouldBe Shortcut(ShortcutKey.Letter('A'))
            }
            shortcutOf(EditAction.CUT, MenuOs.MAC)!!.display(MenuOs.MAC) shouldBe "⌘X"
            shortcutOf(EditAction.COPY, MenuOs.MAC)!!.display(MenuOs.MAC) shouldBe "⌘C"
            shortcutOf(EditAction.PASTE, MenuOs.LINUX)!!.display(MenuOs.LINUX) shouldBe "Ctrl+V"
            shortcutOf(EditAction.SELECT_ALL, MenuOs.WINDOWS)!!.display(MenuOs.WINDOWS) shouldBe "Ctrl+A"
        }

        "every clipboard entry needs an open editor first" {
            for (a in clip) {
                stateOf(a, base.copy(editorFocusTarget = true)) shouldBe
                    ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
            }
        }

        "without the focus target the reason is Click into the editor, before the other reasons" {
            val ctx = open.copy(editorFocusTarget = false)
            for (a in clip) stateOf(a, ctx) shouldBe ActionState(false, MenuReasons.CLICK_INTO_EDITOR)
        }

        "Cut and Copy need a selection, Paste needs text on the clipboard, Select All needs neither" {
            stateOf(EditAction.CUT, open) shouldBe ActionState(false, MenuReasons.SELECT_TEXT_FIRST)
            stateOf(EditAction.COPY, open) shouldBe ActionState(false, MenuReasons.SELECT_TEXT_FIRST)
            stateOf(EditAction.PASTE, open) shouldBe ActionState(false, MenuReasons.CLIPBOARD_EMPTY)
            stateOf(EditAction.SELECT_ALL, open) shouldBe ActionState(true, null)
            stateOf(EditAction.CUT, open.copy(textSelected = true)) shouldBe ActionState(true, null)
            stateOf(EditAction.COPY, open.copy(textSelected = true)) shouldBe ActionState(true, null)
            stateOf(EditAction.PASTE, open.copy(clipboardHasText = true)) shouldBe ActionState(true, null)
            // a selection does not make Paste possible, a full clipboard does not make Cut possible
            stateOf(EditAction.PASTE, open.copy(textSelected = true)) shouldBe
                ActionState(false, MenuReasons.CLIPBOARD_EMPTY)
            stateOf(EditAction.CUT, open.copy(clipboardHasText = true)) shouldBe
                ActionState(false, MenuReasons.SELECT_TEXT_FIRST)
        }

        "Undo and Redo ignore the focus target (they stay active without the focus)" {
            val ctx = open.copy(editorFocusTarget = false, canUndo = true, canRedo = true)
            stateOf(EditAction.UNDO, ctx) shouldBe ActionState(true, null)
            stateOf(EditAction.REDO, ctx) shouldBe ActionState(true, null)
        }

        "the part selection is not the text selection" {
            stateOf(EditAction.COPY, open.copy(hasSelection = true)) shouldBe
                ActionState(false, MenuReasons.SELECT_TEXT_FIRST)
        }

        "enabled is exactly the absence of a reason, for every combination" {
            val bools = listOf(false, true)
            for (editorOpen in bools) {
                for (target in bools) {
                    for (sel in bools) {
                        for (cb in bools) {
                            val ctx =
                                base.copy(
                                    editorOpen = editorOpen,
                                    editorFocusTarget = target,
                                    textSelected = sel,
                                    clipboardHasText = cb,
                                )
                            for (a in EditAction.entries) {
                                val s = stateOf(a, ctx)
                                s.enabled shouldBe (s.reason == null)
                            }
                        }
                    }
                }
            }
        }

        "the shortcut is registered only while the entry is enabled and the editor really has the focus" {
            val full = open.copy(textSelected = true, clipboardHasText = true)
            for (os in MenuOs.entries) {
                for (a in clip) {
                    registeredShortcut(a, full, os) shouldBe null
                    registeredShortcut(a, full.copy(editorFocused = true), os) shouldBe shortcutOf(a, os)
                    registeredShortcut(a, open.copy(editorFocused = true), os) shouldBe
                        if (a == EditAction.SELECT_ALL) shortcutOf(a, os) else null
                }
            }
        }

        "FOCUS_OWNED is Undo, Redo and the four clipboard entries" {
            FOCUS_OWNED shouldBe setOf(EditAction.UNDO, EditAction.REDO) + clip.toSet()
        }

        "FocusTarget has eight cells" {
            for (current in listOf(false, true)) {
                FocusTarget.next(current, editorFocused = true, windowFocused = true) shouldBe true
                FocusTarget.next(current, editorFocused = true, windowFocused = false) shouldBe true
                FocusTarget.next(current, editorFocused = false, windowFocused = true) shouldBe false
                FocusTarget.next(current, editorFocused = false, windowFocused = false) shouldBe current
            }
        }

        "PasteText turns CRLF and lone CR into LF" {
            PasteText.normalize("a\r\nb") shouldBe "a\nb"
            PasteText.normalize("a\rb") shouldBe "a\nb"
            PasteText.normalize("a\r\n\r\nb\rc\nd") shouldBe "a\n\nb\nc\nd"
            PasteText.normalize("\r\r") shouldBe "\n\n"
            PasteText.normalize("") shouldBe ""
            PasteText.normalize("plain") shouldBe "plain"
        }

        "PasteText accepts exactly MAX_CHARS and refuses one more" {
            PasteText.normalize("a".repeat(PasteText.MAX_CHARS))!!.length shouldBe PasteText.MAX_CHARS
            PasteText.normalize("a".repeat(PasteText.MAX_CHARS + 1)) shouldBe null
            // CRLF shrinks: this is over the limit raw, but within it once normalised
            PasteText.normalize("\r\n".repeat(PasteText.MAX_CHARS / 2))!!.length shouldBe PasteText.MAX_CHARS / 2
            PasteText.normalize("a".repeat(PasteText.MAX_CHARS * 2 + 1)) shouldBe null
        }
    })
