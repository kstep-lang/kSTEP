package dev.kstep.desktop.menu

import dev.kstep.desktop.recent.RecentList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The File menu model (ADR-0072): order, labels, shortcut texts per operating system. */
class FileMenuTest :
    StringSpec({
        fun shape(os: MenuOs): List<String> =
            fileMenu(os, RecentList.empty(false)).map {
                when (it) {
                    is MenuEntry.Action -> it.action.label
                    is MenuEntry.Recent -> "<recent>"
                    MenuEntry.Separator -> "-"
                }
            }

        "macOS has no Quit entry" {
            shape(MenuOs.MAC) shouldBe
                listOf("Open…", "Open Folder…", "<recent>", "-", "Close Workspace", "-", "Save", "Export…")
        }

        "Windows and Linux end with a separator and Quit" {
            val expected =
                listOf("Open…", "Open Folder…", "<recent>", "-", "Close Workspace", "-", "Save", "Export…", "-", "Quit")
            shape(MenuOs.WINDOWS) shouldBe expected
            shape(MenuOs.LINUX) shouldBe expected
        }

        "the ellipsis is the real character exactly where the menu opens a dialog" {
            FileAction.entries.filter { '…' in it.label }.map { it.label } shouldBe
                listOf("Open…", "Open Folder…", "Export…")
            FileAction.entries.none { "..." in it.label } shouldBe true
        }

        "shortcut texts per operating system" {
            Shortcut('O').display(MenuOs.MAC) shouldBe "⌘O"
            Shortcut('O', shift = true).display(MenuOs.MAC) shouldBe "⇧⌘O"
            Shortcut('O').display(MenuOs.WINDOWS) shouldBe "Ctrl+O"
            Shortcut('O', shift = true).display(MenuOs.LINUX) shouldBe "Ctrl+Shift+O"
        }

        "Close Workspace and Quit have no shortcut" {
            FileAction.CLOSE_WORKSPACE.shortcut shouldBe null
            FileAction.QUIT.shortcut shouldBe null
        }

        "the operating system is read from os.name" {
            MenuOs.current("Mac OS X") shouldBe MenuOs.MAC
            MenuOs.current("Windows 11") shouldBe MenuOs.WINDOWS
            MenuOs.current("Linux") shouldBe MenuOs.LINUX
            MenuOs.current("") shouldBe MenuOs.LINUX
        }
    })
