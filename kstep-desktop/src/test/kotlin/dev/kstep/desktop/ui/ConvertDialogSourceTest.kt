package dev.kstep.desktop.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source checks of the convert dialog (ADR-0078). The dialog must never reach the overwrite switch of the facade; the
 * checks read the file as text and are heuristics (string based), accepted for a cheap guard.
 */
class ConvertDialogSourceTest :
    StringSpec({
        val root =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin/dev/kstep/desktop/ui") }
                .first { Files.isDirectory(it) }
        val text = Files.readString(root.resolve("ConvertDialog.kt"))

        "the dialog never mentions the overwrite switch or the facade request" {
            for (word in listOf("force", "toFacade", "DesktopConvertRequest", "dev.kstep.cli.DesktopConvert")) {
                text.contains(word, ignoreCase = true) shouldBe false
            }
        }

        "replace is called in exactly one place, inside the Conflict branch of the dismiss slot" {
            Regex("replaceConfirmed\\(\\)").findAll(text).count() shouldBe 2 // the host's own method and the call
            Regex("host\\.replaceConfirmed\\(\\)").findAll(text).count() shouldBe 1
            val dismiss = text.indexOf("dismissButton =")
            val confirm = text.indexOf("confirmButton =")
            val call = text.indexOf("host.replaceConfirmed()")
            (call in dismiss..confirm) shouldBe true
            val branch = text.lastIndexOf("is ConvertState.", call)
            text.substring(branch, call).startsWith("is ConvertState.Conflict") shouldBe true
        }

        "Cancel is the filled default button of the conflict, replace is a text button" {
            val at = text.indexOf("host.replaceConfirmed()")
            text.substring(text.lastIndexOf("TextButton(", at), at).contains("TextButton(") shouldBe true
            val confirm = text.indexOf("is ConvertState.Conflict ->", text.indexOf("confirmButton"))
            val slot = text.substring(confirm, confirm + 200)
            slot.contains("Button(") shouldBe true
            slot.contains("CANCEL") shouldBe true
            slot.contains("focusRequester") shouldBe true
        }
    })
