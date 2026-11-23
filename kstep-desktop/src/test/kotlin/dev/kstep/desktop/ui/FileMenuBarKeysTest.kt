package dev.kstep.desktop.ui

import androidx.compose.ui.input.key.KeyShortcut
import dev.kstep.desktop.menu.FileAction
import dev.kstep.desktop.menu.MenuOs
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** Menu keys and the one-source rule of ADR-0072: the key handler of DesktopApp never runs a menu action itself. */
class FileMenuBarKeysTest :
    StringSpec({
        val root =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin/dev/kstep/desktop/ui") }
                .first { Files.isDirectory(it) }

        "every FileAction shortcut has a Compose key, with Cmd on macOS and Ctrl elsewhere" {
            for (action in FileAction.entries) {
                val shortcut = action.shortcut ?: continue
                for (os in MenuOs.entries) {
                    keyShortcutOf(shortcut, os) shouldBe
                        KeyShortcut(
                            keyFor(shortcut.key),
                            ctrl = os != MenuOs.MAC,
                            meta = os == MenuOs.MAC,
                            shift = shortcut.shift,
                        )
                }
            }
        }

        "an unmapped letter fails loudly" {
            val failed = runCatching { keyFor('Z') }.isFailure
            failed shouldBe true
        }

        "DesktopApp's key handler runs no menu action" {
            val text = Files.readString(root.resolve("DesktopApp.kt"))
            val start = text.indexOf("\n                .onPreviewKeyEvent")
            val block = text.substring(start, text.indexOf("if (exportVisible)", start))
            for (call in listOf("openFile()", "chooseFolder()", "session.save()", "openExport()")) {
                block.contains(call) shouldBe false
            }
            for (key in listOf("Key.O", "Key.S", "Key.E")) {
                // only the letter-to-char mapping for the routing may name them, and that is outside this block
                block.contains(key) shouldBe false
            }
        }

        "FileMenuBar registers shortcuts only from FileAction.shortcut" {
            val text = Files.readString(root.resolve("FileMenuBar.kt"))
            text.contains("action.shortcut?.let { keyShortcutOf(it, os) }") shouldBe true
            Regex("""KeyShortcut\(""").findAll(text).count() shouldBe 1
        }
    })
