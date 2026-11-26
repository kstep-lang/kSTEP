package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class MenuIconsTest :
    StringSpec({
        "every FileAction has an icon" {
            for (action in FileAction.entries) iconOf(action) // exhaustive when; must not throw
            FileAction.entries.map { iconOf(it) }.size shouldBe FileAction.entries.size
        }

        "Convert has its own icon, different from Export" {
            iconOf(FileAction.CONVERT) shouldBe MenuIconId.CONVERT
            (iconOf(FileAction.CONVERT) == iconOf(FileAction.EXPORT)) shouldBe false
        }

        "icons are pairwise different" {
            val ids =
                FileAction.entries.map { iconOf(it) } + EditAction.entries.map { iconOf(it) } +
                    ViewAction.entries.map { iconOf(it) } + HelpAction.entries.map { iconOf(it) } +
                    MenuIcons.OPEN_RECENT_SUBMENU + MenuIcons.CLEAR_RECENT_ITEM
            ids.toSet().size shouldBe ids.size
        }

        "every Edit and View action has its own icon" {
            val ids = EditAction.entries.map { iconOf(it) } + ViewAction.entries.map { iconOf(it) }
            ids.size shouldBe 19
            ids.toSet().size shouldBe 19
        }

        "no icon is orphaned: actions, the two extra entries and the toolbar cover all icons" {
            val used =
                FileAction.entries.map { iconOf(it) } + EditAction.entries.map { iconOf(it) } +
                    ViewAction.entries.map { iconOf(it) } + HelpAction.entries.map { iconOf(it) } +
                    MenuIcons.OPEN_RECENT_SUBMENU + MenuIcons.CLEAR_RECENT_ITEM +
                    ToolbarButtonId.entries.map { it.icon }
            used.toSet() shouldBe MenuIconId.entries.toSet()
        }

        "the clipboard entries map to their own icons" {
            iconOf(EditAction.CUT) shouldBe MenuIconId.CUT
            iconOf(EditAction.COPY) shouldBe MenuIconId.COPY
            iconOf(EditAction.PASTE) shouldBe MenuIconId.PASTE
            iconOf(EditAction.SELECT_ALL) shouldBe MenuIconId.SELECT_ALL
        }

        "dynamic recent entries have deliberately no icon" {
            MenuIcons.RECENT_ITEM shouldBe null
            MenuIcons.EMPTY_PLACEHOLDER shouldBe null
        }

        "FileMenuBar takes its menu titles from MenuTitles, not from literals" {
            val root =
                generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                    .map { it.resolve("src/main/kotlin/dev/kstep/desktop/ui") }
                    .first { Files.isDirectory(it) }
            val text = Files.readString(root.resolve("FileMenuBar.kt"))
            text.contains("Menu(MenuTitles.OPEN_RECENT)") shouldBe true
            text.contains("Menu(MenuTitles.FILE") shouldBe true
            text.contains("Menu(MenuTitles.EDIT") shouldBe true
            text.contains("Menu(MenuTitles.VIEW") shouldBe true
            text.contains("Menu(MenuTitles.HELP") shouldBe true
            text.contains("\"Open Recent\"") shouldBe false
            MenuTitles.OPEN_RECENT shouldBe "Open Recent"
            MenuTitles.FILE shouldBe "File"
        }
    })
