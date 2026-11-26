package dev.kstep.desktop.draft

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

class DraftKeyTest :
    StringSpec({
        "the name is 64 hex digits and .draft, without any part of the path" {
            val name =
                DraftKey.fileName(
                    DraftKey.normalize(
                        java.nio.file.Path
                            .of("/tmp/secret-name/a.kstep.kts"),
                    ),
                )
            name shouldMatch DraftPolicy.NAME
            name shouldNotContain "secret"
            name shouldNotContain "kstep.kts"
        }

        "a dot segment does not change the key" {
            val dir = TestSupport.newDir()
            val a = DraftKey.fileName(DraftKey.normalize(dir.resolve("x/./a.kstep.kts")))
            val b = DraftKey.fileName(DraftKey.normalize(dir.resolve("x/a.kstep.kts")))
            a shouldBe b
        }

        "a parent folder that is a link gives the key of the real folder" {
            val dir = TestSupport.newDir()
            val real = Files.createDirectory(dir.resolve("real"))
            val link = runCatching { Files.createSymbolicLink(dir.resolve("link"), real) }.getOrNull()
            if (link != null) DraftKey.normalize(link.resolve("a.kstep.kts")) shouldBe real.resolve("a.kstep.kts")
        }

        "a deleted file keeps its key as long as the parent exists" {
            val dir = TestSupport.newDir()
            val file = dir.resolve("a.kstep.kts")
            Files.writeString(file, "x")
            val before = DraftKey.normalize(file)
            Files.delete(file)
            DraftKey.normalize(file) shouldBe before
        }

        "a file that is a link is its own key" {
            val dir = TestSupport.newDir()
            val target = dir.resolve("t.kstep.kts").also { Files.writeString(it, "x") }
            val link = runCatching { Files.createSymbolicLink(dir.resolve("l.kstep.kts"), target) }.getOrNull()
            if (link != null) DraftKey.normalize(link) shouldBe link
        }
    })
