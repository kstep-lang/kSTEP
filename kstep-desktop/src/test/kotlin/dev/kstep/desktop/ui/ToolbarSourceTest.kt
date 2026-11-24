package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source checks of the icon-only toolbar (ADR-0079): no text button and no label may come back. String based
 * heuristics, accepted like in [ConvertDialogSourceTest].
 */
class ToolbarSourceTest :
    StringSpec({
        val root =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin/dev/kstep/desktop/ui") }
                .first { Files.isDirectory(it) }
        val app = Files.readString(root.resolve("DesktopApp.kt"))
        val visibility = Files.readString(root.resolve("VisibilityToolbar.kt"))
        val button = Files.readString(root.resolve("IconTooltipButton.kt"))
        val toolbar =
            app.substring(
                app.indexOf("private fun Toolbar("),
                app.indexOf("StatusLine(", app.indexOf("private fun Toolbar(")),
            )

        "the toolbar row holds no text button, text or button label" {
            toolbar.contains("TextButton") shouldBe false
            Regex("(?<![A-Za-z])(Text|Button)\\(").containsMatchIn(toolbar) shouldBe false
        }

        "the visibility controls use icon buttons, no text button and no percentage label" {
            visibility.substringAfter("internal fun VisibilityControls").let {
                it.contains("TextButton") shouldBe false
                Regex("(?<![A-Za-z])Text\\(").containsMatchIn(it) shouldBe false
                it.contains("Opacity \${") shouldBe false
            }
        }

        "the icon button takes a state and no label parameter" {
            val signature = button.substringAfter("internal fun IconTooltipButton(").substringBefore(") {")
            signature.contains("state: ToolbarButtonState") shouldBe true
            signature.contains("label") shouldBe false
            signature.contains(": String") shouldBe false
        }

        "the toolbar row is tagged and the icon button sizes are 32 and 18 dp" {
            toolbar.contains("testTag(\"toolbar-buttons\")") shouldBe true
            button.contains("TOOLBAR_BUTTON_SIZE: Dp = 32.dp") shouldBe true
            button.contains("TOOLBAR_ICON_SIZE: Dp = 18.dp") shouldBe true
        }

        "the Export state comes from the shared model, not from a hand-copied reason chain" {
            toolbar.contains("exportToolbarState(menuContext)") shouldBe true
            toolbar.contains("OPEN_SCRIPT_FIRST") shouldBe false
        }
    })
