package dev.kstep.desktop.recent

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/** The check at click time and the texts of a vanished entry (ADR-0066). */
class RecentOpenCheckTest :
    StringSpec({
        val posix =
            "posix" in
                java.nio.file.FileSystems
                    .getDefault()
                    .supportedFileAttributeViews()

        fun script(path: java.nio.file.Path) = RecentEntry(path, RecentKind.SCRIPT)

        "a regular script and a folder are fine" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "x")
            RecentOpenCheck.check(script(file)) shouldBe RecentCheck.Ok(file)
            RecentOpenCheck.check(RecentEntry(dir, RecentKind.FOLDER)) shouldBe RecentCheck.Ok(dir)
        }

        "a missing file is missing" {
            val dir = TestSupport.newDir()
            RecentOpenCheck.check(script(dir.resolve("gone.kstep.kts"))) shouldBe RecentCheck.Missing
        }

        "a folder where a script was expected and the reverse are the wrong type" {
            val dir = TestSupport.newDir()
            val file = TestSupport.script(dir, "a", "x")
            RecentOpenCheck.check(script(dir)) shouldBe RecentCheck.WrongType
            RecentOpenCheck.check(RecentEntry(file, RecentKind.FOLDER)) shouldBe RecentCheck.WrongType
        }

        "a symbolic link is refused, also when only a folder above it is one" {
            if (posix) {
                val dir = TestSupport.newDir()
                val real = Files.createDirectory(dir.resolve("real"))
                val file = TestSupport.script(real, "a", "x")
                val link = dir.resolve("link.kstep.kts")
                Files.createSymbolicLink(link, file)
                val dirLink = dir.resolve("dirlink")
                Files.createSymbolicLink(dirLink, real)
                RecentOpenCheck.check(script(link)) shouldBe RecentCheck.Link
                RecentOpenCheck.check(script(dirLink.resolve("a.kstep.kts"))) shouldBe RecentCheck.Link
                RecentOpenCheck.check(RecentEntry(dirLink, RecentKind.FOLDER)) shouldBe RecentCheck.Link
            }
        }

        "the message names the file with markers instead of direction characters" {
            val dir = TestSupport.newDir()
            val entry = script(dir.resolve("a‮b.kstep.kts"))
            val error = RecentMessages.forCheck(entry, RecentCheck.Missing)!!
            error.code shouldBe DesktopErrorCodes.RECENT_UNAVAILABLE
            error.message shouldContain "‹U+202E›"
            error.message shouldNotContain "‮"
            error.message shouldContain "removed from Recent"
            RecentMessages.forCheck(entry, RecentCheck.Link)!!.message shouldContain "is a link"
            RecentMessages.forCheck(entry, RecentCheck.Ok(dir)) shouldBe null
        }

        "labels show folders with a slash and cut the parent from the left" {
            val deep =
                java.nio.file.Path
                    .of("/" + "p".repeat(100) + "/sub/work")
            val label = RecentMessages.label(RecentEntry(deep, RecentKind.FOLDER))
            label.name shouldBe "work/"
            label.parent.length shouldBe 60
            label.parent.first() shouldBe '…'
            RecentMessages
                .label(
                    script(
                        java.nio.file.Path
                            .of("/a/b.kstep.kts"),
                    ),
                ).also {
                    it.name shouldBe "b.kstep.kts"
                    it.parent shouldBe "/a"
                }
        }

        "a marker is never cut in half by the left cut" {
            val text = "x".repeat(30) + "‹U+202E›" + "y".repeat(20)
            val cut = RecentMessages.shortenLeft(text, 30)
            cut.shouldBeInstanceOf<String>()
            (cut.contains('‹') == cut.contains('›')) shouldBe true
        }
    })
