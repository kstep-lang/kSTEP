package dev.kstep.desktop.menu

import dev.kstep.cli.kstepVersion
import dev.kstep.desktop.recent.RecentList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch
import java.nio.file.Files
import java.nio.file.Path

/** The Help menu, the shortcut overview and the slimmer toolbar (ADR-0076). */
class HelpMenuTest :
    StringSpec({
        val uiRoot =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin/dev/kstep/desktop/ui") }
                .first { Files.isDirectory(it) }
        val desktopApp = Files.readString(uiRoot.resolve("DesktopApp.kt"))

        fun rows(os: MenuOs) = shortcutOverview(os).flatMap { it.rows }

        "the overview names every menu key, every hint and every editor key exactly once" {
            for (os in MenuOs.entries) {
                val keys = rows(os).flatMap { it.keys }
                val expected =
                    ShortcutRouting.menuOwnedShortcuts(os).keys.map { it.display(os) } +
                        ViewAction.entries.mapNotNull { it.keyHint?.hintText(os) } +
                        EditorOwnedShortcuts.of(os).map { it.display(os) }
                keys.sorted() shouldContainExactly expected.sorted()
            }
        }

        "no key text appears twice in the overview" {
            for (os in MenuOs.entries) {
                val keys = rows(os).flatMap { it.keys }
                keys.toSet().size shouldBe keys.size
            }
        }

        "groups come in the order File, Edit, View, Editor with the labels of the menu" {
            for (os in MenuOs.entries) {
                val groups = shortcutOverview(os)
                groups.map { it.title } shouldContainExactly listOf("File", "Edit", "View", "Editor")
                groups[0].rows.map { it.label } shouldContainExactly
                    FileAction.entries.filter { it.shortcut != null }.map { it.label }
                groups[1].rows.map { it.label } shouldContainExactly EditAction.entries.map { it.label }
                // Soft Wrap has no key, so it is not in the shortcut overview
                groups[2].rows.map { it.label } shouldContainExactly
                    ViewAction.entries.filter { it != ViewAction.SOFT_WRAP }.map { it.label }
                groups[3].rows.map { it.label } shouldContainExactly
                    listOf(EditorShortcut.FIND_NEXT.label, EditorShortcut.FIND_PREVIOUS.label)
                groups[1]
                    .rows
                    .single { it.label == EditAction.REDO.label }
                    .keys.size shouldBe 2
                groups.map { it.footnote != null } shouldContainExactly listOf(false, false, true, false)
            }
        }

        "Help entries: macOS with the system About has only Keyboard Shortcuts, all else has both" {
            helpMenuEntries(MenuOs.MAC, true) shouldContainExactly listOf(HelpAction.SHORTCUTS)
            helpMenuEntries(MenuOs.MAC, false) shouldContainExactly HelpAction.entries
            helpMenuEntries(MenuOs.WINDOWS, false) shouldContainExactly HelpAction.entries
            helpMenuEntries(MenuOs.LINUX, false) shouldContainExactly HelpAction.entries
        }

        "Help entries are always enabled" {
            val ctx = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)
            for (a in HelpAction.entries) stateOf(a, ctx) shouldBe ActionState(true, null)
        }

        "AboutInfo carries the fixed texts and link" {
            val info = AboutInfo.of("1.2.3")
            info.name shouldBe "kSTEP Desktop"
            info.version shouldBe "1.2.3"
            info.note shouldBe "Unpublished development tool"
            info.linkLabel shouldBe "kstep.dev"
            info.url shouldBe "https://kstep.dev"
        }

        "the menu mnemonics are F, E, V, H and all different" {
            MenuTitles.MNEMONICS shouldBe
                mapOf(MenuTitles.FILE to 'F', MenuTitles.EDIT to 'E', MenuTitles.VIEW to 'V', MenuTitles.HELP to 'H')
            MenuTitles.MNEMONICS.values
                .toSet()
                .size shouldBe 4
            MenuTitles.HELP shouldBe "Help"
        }

        "a plain key has neither the primary key nor Alt" {
            isPlainKey(command = false, alt = false) shouldBe true
            isPlainKey(command = false, alt = true) shouldBe false
            isPlainKey(command = true, alt = false) shouldBe false
        }

        "the editor key table is unchanged and every editor key is its own" {
            EditorOwnedShortcuts.of(MenuOs.MAC) shouldBe
                setOf(
                    Shortcut(ShortcutKey.Letter('G')),
                    Shortcut(ShortcutKey.Letter('G'), shift = true),
                    Shortcut(ShortcutKey.Letter('Y')),
                )
            val other =
                setOf(
                    Shortcut(ShortcutKey.Letter('G')),
                    Shortcut(ShortcutKey.Letter('G'), shift = true),
                    Shortcut(ShortcutKey.Letter('Z'), shift = true),
                )
            EditorOwnedShortcuts.of(MenuOs.WINDOWS) shouldBe other
            EditorOwnedShortcuts.of(MenuOs.LINUX) shouldBe other
            for (os in MenuOs.entries) {
                EditorShortcut.entries
                    .map { shortcutOf(it, os) }
                    .toSet()
                    .size shouldBe 3
            }
        }

        "the Alt+H fix: the Home key of the window handler ignores Alt" {
            desktopApp.contains(
                "isPlainKey(command, event.isAltPressed) && !editorFocused && event.key == Key.H",
            ) shouldBe
                true
        }

        "the toolbar no longer holds Open Folder, Recent or Close Workspace" {
            val start = desktopApp.indexOf("private fun Toolbar(")
            start shouldNotBe -1
            val end = desktopApp.indexOf("\n@Composable", start).let { if (it < 0) desktopApp.length else it }
            val block = desktopApp.substring(start, end)
            block.contains("Open Folder…") shouldBe false
            block.contains("RecentMenu") shouldBe false
            block.contains("Close Workspace") shouldBe false
            desktopApp.contains("RecentMenu") shouldBe false
            desktopApp.contains("openFolderTip") shouldBe false
            desktopApp.contains("Open a folder of notes") shouldBe false
        }

        "File menu keeps Open Folder, Recent and Close Workspace" {
            val actions =
                fileMenu(
                    MenuOs.MAC,
                    RecentList.empty(),
                ).filterIsInstance<MenuEntry.Action>().map { it.action }
            actions.contains(FileAction.OPEN_FOLDER) shouldBe true
            actions.contains(FileAction.CLOSE_WORKSPACE) shouldBe true
            fileMenu(MenuOs.MAC, RecentList.empty()).any { it is MenuEntry.Recent } shouldBe true
            stateOf(FileAction.CLOSE_WORKSPACE, MenuContext(false, true, false)) shouldBe ActionState(true, null)
            stateOf(FileAction.CLOSE_WORKSPACE, MenuContext(false, false, false)).reason shouldBe
                MenuReasons.NO_FOLDER_OPEN
        }

        "the version of the jar is a real version" {
            kstepVersion() shouldNotBe "unknown"
            kstepVersion() shouldMatch Regex("""\d+\.\d+\.\d+.*""")
        }
    })
