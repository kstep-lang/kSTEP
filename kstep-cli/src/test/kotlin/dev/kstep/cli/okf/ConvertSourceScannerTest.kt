package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

class ConvertSourceScannerTest :
    StringSpec({
        fun tmp(): Path = Files.createTempDirectory("kstep-scan-test").toRealPath()

        fun link(
            link: Path,
            to: Path,
        ): Boolean =
            try {
                Files.createSymbolicLink(link, to)
                true
            } catch (_: Exception) {
                false
            }

        fun list(
            d: Path,
            entries: Int = 100,
            files: Int = 100,
            bytes: Long = 1_000_000,
        ) = ConvertSourceScanner.list(d, ".md", entries, files, bytes)

        "sorted ordinally and deterministically, case-insensitive suffix, not recursive" {
            val d = tmp()
            for (n in listOf("b.md", "Z.md", "a.MD", "ä.md", "c.txt")) Files.writeString(d.resolve(n), "x")
            Files.createDirectory(d.resolve("sub"))
            Files.writeString(d.resolve("sub").resolve("deep.md"), "x")
            val r = list(d).shouldBeInstanceOf<ConvertSourceScanner.Outcome.Ok>()
            r.candidates.map { it.name } shouldBe listOf("Z.md", "a.MD", "b.md", "ä.md")
        }

        "links, directories named like sources and hidden files are ignored with a reason" {
            val d = tmp()
            val real = Files.writeString(d.resolve("real.md"), "x")
            Files.writeString(d.resolve(".hidden.md"), "x")
            Files.createDirectory(d.resolve("dir.md"))
            val linked = link(d.resolve("l.md"), real)
            val r = list(d).shouldBeInstanceOf<ConvertSourceScanner.Outcome.Ok>()
            r.candidates.map { it.name } shouldBe listOf("real.md")
            val reasons = r.ignored.associate { it.name to it.reason }
            reasons[".hidden.md"] shouldBe ConvertSourceScanner.IgnoredReason.HIDDEN
            reasons["dir.md"] shouldBe ConvertSourceScanner.IgnoredReason.NOT_REGULAR
            if (linked) reasons["l.md"] shouldBe ConvertSourceScanner.IgnoredReason.SYMBOLIC_LINK
        }

        "limits give no partial result".config(timeout = 30.seconds) {
            val d = tmp()
            for (i in 1..4) Files.writeString(d.resolve("f$i.md"), "xxxx")
            (list(d, entries = 3) as ConvertSourceScanner.Outcome.Failed).limit shouldBe true
            (list(d, files = 3) as ConvertSourceScanner.Outcome.Failed).limit shouldBe true
            (list(d, bytes = 15) as ConvertSourceScanner.Outcome.Failed).limit shouldBe true
            list(d, entries = 4, files = 4, bytes = 16).shouldBeInstanceOf<ConvertSourceScanner.Outcome.Ok>()
        }

        "an empty folder lists nothing" {
            val r = list(tmp()).shouldBeInstanceOf<ConvertSourceScanner.Outcome.Ok>()
            r.candidates.size shouldBe 0
        }
    })
