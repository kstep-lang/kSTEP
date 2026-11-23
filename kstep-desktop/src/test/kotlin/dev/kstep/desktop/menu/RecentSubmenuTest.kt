package dev.kstep.desktop.menu

import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentKind
import dev.kstep.desktop.recent.RecentList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path

/** The Open Recent submenu model (ADR-0072). */
class RecentSubmenuTest :
    StringSpec({
        fun script(name: String) = RecentEntry(Path.of("/w/$name.kstep.kts"), RecentKind.SCRIPT)

        fun textOf(entry: RecentEntry): String =
            RecentSubmenuModel
                .of(RecentList.empty(false).touch(entry))
                .items
                .single()
                .text

        "an empty list shows the grey placeholder and Clear Recent is off" {
            val sub = RecentSubmenuModel.of(RecentList.empty(false))
            sub.items shouldBe emptyList()
            sub.emptyPlaceholder shouldBe "No Recent Items"
            sub.clearEnabled shouldBe false
        }

        "a full list keeps its order, at most ten, without a placeholder" {
            var list = RecentList.empty(false)
            (1..10).forEach { list = list.touch(script("s$it")) }
            val sub = RecentSubmenuModel.of(list)
            sub.items.map { it.entry } shouldBe list.entries
            sub.items.size shouldBe 10
            sub.emptyPlaceholder shouldBe null
            sub.clearEnabled shouldBe true
        }

        "the eleventh touch pushes out the oldest" {
            var list = RecentList.empty(false)
            (1..11).forEach { list = list.touch(script("s$it")) }
            val sub = RecentSubmenuModel.of(list)
            sub.items.size shouldBe 10
            sub.items.first().entry shouldBe script("s11")
            sub.items.last().entry shouldBe script("s2")
        }

        "Clear gives the empty submenu again" {
            val sub = RecentSubmenuModel.of(RecentList.empty(false).touch(script("a")).clear())
            sub.items shouldBe emptyList()
            sub.emptyPlaceholder shouldBe "No Recent Items"
        }

        "a folder carries the slash and the shortened parent" {
            textOf(RecentEntry(Path.of("/w/notes"), RecentKind.FOLDER)) shouldBe "notes/  —  /w"
        }

        "a leading angle bracket never reaches Swing as HTML" {
            val html = RecentEntry(Path.of("/w/<html><b>x"), RecentKind.SCRIPT)
            val text = textOf(html)
            text shouldStartWith "‹html>"
            MenuText.safe("<html>hi") shouldBe "‹html>hi"
            MenuText.safe("  \t<html>hi") shouldBe "  \t‹html>hi"
            MenuText.safe("<<html>") shouldBe "‹‹html>"
            MenuText.safe("a<b") shouldBe "a<b"
            MenuText.safe("") shouldBe ""
        }

        "control and direction characters in names stay escaped" {
            val evil = RecentEntry(Path.of("/w/a‮b\u0007.kstep.kts"), RecentKind.SCRIPT)
            val text = textOf(evil)
            text shouldNotContain "‮"
            text shouldNotContain "\u0007"
        }
    })
