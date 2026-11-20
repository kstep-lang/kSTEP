package dev.kstep.desktop.drop

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.okf.ScanResult
import dev.kstep.desktop.okf.WorkspaceScanner
import dev.kstep.desktop.okf.WorkspaceState
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/** What a drop opens, and what it never does (ADR-0066). */
class DropDecisionTest :
    StringSpec({
        val posix =
            "posix" in
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()

        fun file(
            dir: Path,
            name: String,
        ): Path = Files.writeString(dir.resolve(name), "x")

        "a script, a note and a folder are each recognised" {
            val dir = TestSupport.newDir()
            val script = file(dir, "a.kstep.kts")
            val note = file(dir, "n.md")
            val folder = Files.createDirectory(dir.resolve("f"))
            DropEvaluator.evaluate(listOf(script)).action shouldBe DropAction.OpenScript(script)
            DropEvaluator.evaluate(listOf(note)).action shouldBe DropAction.OpenNote(note)
            DropEvaluator.evaluate(listOf(folder)).action shouldBe DropAction.OpenFolder(folder)
        }

        "the extension is compared without regard to case" {
            val dir = TestSupport.newDir()
            val script = file(dir, "A.KSTEP.KTS")
            val note = file(dir, "N.MD")
            DropEvaluator.evaluate(listOf(script)).action shouldBe DropAction.OpenScript(script)
            DropEvaluator.evaluate(listOf(note)).action shouldBe DropAction.OpenNote(note)
        }

        "a folder with a dot in its name is a folder" {
            val dir = TestSupport.newDir()
            val folder = Files.createDirectory(dir.resolve("v1.2"))
            DropEvaluator.evaluate(listOf(folder)).action shouldBe DropAction.OpenFolder(folder)
        }

        "a file of another kind is rejected with the fixed text" {
            val dir = TestSupport.newDir()
            val d = DropEvaluator.evaluate(listOf(file(dir, "a.txt")))
            val rejected = d.action.shouldBeInstanceOf<DropAction.Rejected>()
            rejected.error.code shouldBe DesktopErrorCodes.DROP_REJECTED
            rejected.error.message shouldBe DropEvaluator.ONLY_SUPPORTED
            d.ignoredCount shouldBe 1
        }

        "the first usable path wins and every other one is counted, invalid ones before it too" {
            val dir = TestSupport.newDir()
            val bad = file(dir, "a.txt")
            val first = file(dir, "b.kstep.kts")
            val second = file(dir, "c.kstep.kts")
            val d = DropEvaluator.evaluate(listOf(bad, first, second))
            d.action shouldBe DropAction.OpenScript(first)
            d.ignoredCount shouldBe 2
        }

        "only unusable paths are rejected, and an empty drop too" {
            val dir = TestSupport.newDir()
            DropEvaluator
                .evaluate(listOf(file(dir, "a.txt"), dir.resolve("gone.md")))
                .action
                .shouldBeInstanceOf<DropAction.Rejected>()
            val empty = DropEvaluator.evaluate(emptyList())
            empty.action.shouldBeInstanceOf<DropAction.Rejected>()
            empty.ignoredCount shouldBe 0
        }

        "more than 1000 paths do not crash and only the first 1000 are looked at" {
            val dir = TestSupport.newDir()
            val many = (1..1500).map { dir.resolve("n$it.txt") }
            val d = DropEvaluator.evaluate(many)
            d.action.shouldBeInstanceOf<DropAction.Rejected>()
            d.ignoredCount shouldBe 1500
            val good = file(dir, "late.kstep.kts")
            DropEvaluator.evaluate(many + good).action.shouldBeInstanceOf<DropAction.Rejected>()
        }

        "a link is rejected by name and never followed" {
            if (posix) {
                val dir = TestSupport.newDir()
                val real = file(dir, "real.kstep.kts")
                val link = dir.resolve("link.kstep.kts")
                Files.createSymbolicLink(link, real)
                val d = DropEvaluator.evaluate(listOf(link))
                d.action
                    .shouldBeInstanceOf<DropAction.Rejected>()
                    .error.message shouldContain
                    "is a link and was not opened."
            }
        }

        "a direction character in a name shows as a marker in the rejection" {
            if (posix) {
                val dir = TestSupport.newDir()
                val real = file(dir, "real.kstep.kts")
                val link = dir.resolve("a‮b.kstep.kts")
                Files.createSymbolicLink(link, real)
                val message =
                    DropEvaluator
                        .evaluate(listOf(link))
                        .action
                        .shouldBeInstanceOf<DropAction.Rejected>()
                        .error.message
                message shouldContain "‹U+202E›"
                message shouldNotContain "‮"
            }
        }

        "the hint looks at names only" {
            DropEvaluator.hint(listOf("a.kstep.kts")) shouldBe DropHint.ACCEPT
            DropEvaluator.hint(listOf("A.MD")) shouldBe DropHint.ACCEPT
            DropEvaluator.hint(listOf("folder")) shouldBe DropHint.ACCEPT
            DropEvaluator.hint(listOf("a.txt", "b.png")) shouldBe DropHint.UNSUPPORTED
            DropEvaluator.hint(listOf("a.txt", "b.md")) shouldBe DropHint.ACCEPT
            DropEvaluator.hint(emptyList()) shouldBe DropHint.ACCEPT
        }

        "the notice names what opened and how many items were ignored" {
            DropEvaluator.openedNotice("a.kstep.kts", 2) shouldBe "Opened a.kstep.kts; 2 other item(s) ignored."
            DropEvaluator.openedNotice("a.kstep.kts", 0) shouldBe null
        }

        "a uri list gives file paths only" {
            val paths =
                DropPaths.fromUriList(
                    "# comment\nfile:///tmp/a.kstep.kts\nhttps://example.com/x\n\nnot a uri \u0000\n",
                )
            paths.size shouldBe 1
            paths.first().fileName.toString() shouldBe "a.kstep.kts"
        }

        // ---- the note that waits for its folder ----
        fun open(root: Path): WorkspaceState.Open =
            WorkspaceState.Open(WorkspaceScanner().scan(root) { false }.shouldBeInstanceOf<ScanResult.Ok>())

        "a pending note waits while the scan runs and without a pending note" {
            val dir = TestSupport.newDir()
            val note = dir.resolve("n.md")
            PendingNote.resolve(null, dir, WorkspaceState.None, false, false) shouldBe PendingResolution.Wait
            PendingNote.resolve(note, dir, WorkspaceState.Scanning(dir), false, true) shouldBe PendingResolution.Wait
            PendingNote.resolve(note, dir, WorkspaceState.None, false, false) shouldBe PendingResolution.Wait
        }

        "a pending note is dropped when the scan failed or ended without a workspace" {
            val dir = TestSupport.newDir()
            val note = dir.resolve("n.md")
            PendingNote.resolve(note, dir, WorkspaceState.None, true, false) shouldBe PendingResolution.Drop
            PendingNote.resolve(note, dir, WorkspaceState.None, false, true) shouldBe PendingResolution.Drop
        }

        "a pending note is opened when the scan has it, dropped when not, and an old workspace is ignored" {
            val dir = TestSupport.newDir()
            val note = file(dir, "n.md")
            val ws = open(dir)
            PendingNote.resolve(note, dir, ws, false, true).shouldBeInstanceOf<PendingResolution.Open>()
            PendingNote.resolve(dir.resolve("other.md"), dir, ws, false, true) shouldBe PendingResolution.Drop
            val elsewhere = TestSupport.newDir()
            PendingNote.resolve(note, elsewhere, ws, false, true) shouldBe PendingResolution.Wait
        }
    })
