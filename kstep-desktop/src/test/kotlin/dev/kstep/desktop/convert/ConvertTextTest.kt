package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertIssue
import dev.kstep.cli.ConvertSkipReason
import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.ConvertedFile
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ConvertTextTest :
    StringSpec({
        val req = ConvertRequest(Path.of("/a/b.md"), ConvertTarget.OKF, null, recursive = false)

        fun file(n: String) = ConvertedFile(n, "s", null, null, null)

        "all eight skip reasons have a plain text, with and without a line" {
            ConvertSkipReason.entries.size shouldBe 8
            for (reason in ConvertSkipReason.entries) {
                val plain = ConvertText.skipReason(reason, null)
                (plain.isNotBlank() && !plain.contains("(line")) shouldBe true
                ConvertText.skipReason(reason, 7) shouldBe "$plain (line 7)"
            }
            ConvertText.skipReason(ConvertSkipReason.HIDDEN_DIR, null) shouldBe "Inside a hidden folder"
        }

        "titles are singular and plural" {
            val done1 = ConvertState.Done(req, listOf(file("a")), emptyList(), emptyList())
            ConvertText.title(done1) shouldBe "Converted 1 file"
            ConvertText.title(done1.copy(written = listOf(file("a"), file("b")))) shouldBe "Converted 2 files"
            ConvertText.title(done1.copy(written = emptyList())) shouldBe "Nothing was converted"
            ConvertText.title(ConvertState.Conflict(req, listOf("a"), 0, 1)) shouldBe "1 file already exists"
            ConvertText.title(ConvertState.Conflict(req, listOf("a"), 59, 60)) shouldBe "60 files already exist"
            ConvertText.title(ConvertState.Failed(req, emptyList<ConvertIssue>(), 0, emptyList(), emptyList())) shouldBe
                "Conversion failed"
            ConvertText.title(ConvertState.Editing(req)) shouldBe "Convert"
            ConvertText.replaceLabel(1) shouldBe "Replace 1 file"
            ConvertText.replaceLabel(3) shouldBe "Replace 3 files"
        }

        "more is null for nothing" {
            ConvertText.more(0) shouldBe null
            ConvertText.more(-1) shouldBe null
            ConvertText.more(3) shouldBe "and 3 more"
        }

        "the direction is derived from the name, case-insensitively, and a folder is always a note-to-script run" {
            ConvertText.deriveTarget("n.md", false) shouldBe ConvertTarget.KTS
            ConvertText.deriveTarget("x.KSTEP.KTS", false) shouldBe ConvertTarget.OKF
            ConvertText.deriveTarget("x.kstep.kts", true) shouldBe ConvertTarget.KTS
            ConvertText.deriveTarget("noextension", false) shouldBe ConvertTarget.KTS
            ConvertText.directionLabel(ConvertTarget.KTS) shouldBe "Note → scripts"
            ConvertText.directionSubtitle(ConvertTarget.OKF) shouldBe ".kstep.kts to .md"
        }

        "tags are split, trimmed and cleaned" {
            ConvertText.parseTags("a, b,, c ") shouldBe listOf("a", "b", "c")
            ConvertText.parseTags("") shouldBe emptyList()
        }

        "title and tags are sent only for a single script that becomes a note" {
            val src = Path.of("/a/s.kstep.kts")
            val sent = ConvertText.effectiveRequest(req, src, false, " T ", "x, y")
            sent.title shouldBe "T"
            sent.tags shouldBe listOf("x", "y")
            val folder = ConvertText.effectiveRequest(req, Path.of("/a"), true, "T", "x")
            folder.title shouldBe null
            folder.tags shouldBe emptyList()
            val kts = ConvertText.effectiveRequest(req.copy(target = ConvertTarget.KTS), src, false, "T", "x")
            kts.title shouldBe null
            kts.tags shouldBe emptyList()
            ConvertText.effectiveRequest(req, src, false, "  ", "").title shouldBe null
        }

        "a file never recurses, a folder follows the checkbox" {
            ConvertText.recursiveFor(false, true) shouldBe false
            ConvertText.recursiveFor(true, true) shouldBe true
            ConvertText.recursiveFor(true, false) shouldBe false
            ConvertText
                .effectiveRequest(
                    req.copy(recursive = true),
                    Path.of("/a/b.md"),
                    false,
                    "",
                    "",
                ).recursive shouldBe
                false
        }

        "the chooser filter accepts notes and scripts only" {
            for (ok in listOf("a.md", "A.MD", "a.kstep.kts")) ConvertText.isConvertSource(ok) shouldBe true
            for (no in listOf("a.kts", "a.txt", "a.kstep.kts.bak")) ConvertText.isConvertSource(no) shouldBe false
        }
    })
