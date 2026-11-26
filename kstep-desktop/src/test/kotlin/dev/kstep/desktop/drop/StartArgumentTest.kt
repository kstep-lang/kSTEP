package dev.kstep.desktop.drop

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files

/** The start argument is judged like a dropped path (ADR-0083). */
class StartArgumentTest :
    StringSpec({
        "no arguments is None" {
            StartArgument.parse(emptyArray()) shouldBe StartRequest.None
        }

        "NUL, control characters and 4097 characters are rejected with D-033 and no crash" {
            for (raw in listOf("", "a\u0000b.kstep.kts", "a\nb.kstep.kts", "x".repeat(4097))) {
                val r = StartArgument.parse(arrayOf(raw)).shouldBeInstanceOf<StartRequest.Rejected>()
                r.error.code shouldBe DesktopErrorCodes.DROP_REJECTED
            }
        }

        "a relative path becomes absolute and normalized" {
            val r = StartArgument.parse(arrayOf("a/../b.kstep.kts")).shouldBeInstanceOf<StartRequest.Open>()
            r.path.isAbsolute shouldBe true
            r.path.toString().contains("..") shouldBe false
            r.path.fileName.toString() shouldBe "b.kstep.kts"
        }

        "further arguments are counted" {
            StartArgument
                .parse(
                    arrayOf("/x/a.md", "b", "c"),
                ).shouldBeInstanceOf<StartRequest.Open>()
                .ignoredArgs shouldBe
                2
        }

        "the drop evaluator then decides on type and links" {
            val dir = TestSupport.newDir()
            val script = TestSupport.script(dir, "s", "x")
            val note = dir.resolve("n.md").also { Files.writeString(it, "# n") }

            fun evaluate(p: java.nio.file.Path) =
                DropEvaluator
                    .evaluate(listOf((StartArgument.parse(arrayOf(p.toString())) as StartRequest.Open).path))
                    .action
            evaluate(script).shouldBeInstanceOf<DropAction.OpenScript>()
            evaluate(note).shouldBeInstanceOf<DropAction.OpenNote>()
            evaluate(dir).shouldBeInstanceOf<DropAction.OpenFolder>()
            val link = dir.resolve("l.kstep.kts")
            try {
                Files.createSymbolicLink(link, script)
                evaluate(link).shouldBeInstanceOf<DropAction.Rejected>()
            } catch (_: Exception) {
                // no symbolic links on this system
            }
        }
    })
