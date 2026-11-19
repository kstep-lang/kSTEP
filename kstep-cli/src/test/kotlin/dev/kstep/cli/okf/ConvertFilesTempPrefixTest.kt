package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/** The temp-file prefix of [ConvertFiles.writeAtomic] (shared by `kstep export --target`, ADR-0065). */
class ConvertFilesTempPrefixTest :
    StringSpec({
        fun tmp(): Path = Files.createTempDirectory("kstep-export-test").toRealPath()

        fun leftovers(d: Path): List<String> =
            Files.list(d).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList() }

        "the default prefix stays .kstep-convert- (seen from beforeMove), the export prefix is .kstep-export-" {
            for ((prefix, expected) in listOf(null to ".kstep-convert-", ".kstep-export-" to ".kstep-export-")) {
                val d = tmp()
                var seen = emptyList<String>()
                val body: () -> Unit = { seen = leftovers(d) }
                val r =
                    if (prefix == null) {
                        ConvertFiles.writeAtomic(d, "t.step", "x".toByteArray(), replace = false, beforeMove = body)
                    } else {
                        ConvertFiles.writeAtomic(
                            d,
                            "t.step",
                            "x".toByteArray(),
                            replace = false,
                            tempPrefix = prefix,
                            beforeMove = body,
                        )
                    }
                r shouldBe ConvertFiles.WriteResult.Written
                seen.size shouldBe 1
                seen[0].startsWith(expected) shouldBe true
                leftovers(d) shouldBe emptyList()
                Files.readString(d.resolve("t.step")) shouldBe "x"
            }
        }

        "no temp file remains after a refused write, and replace overwrites a regular file only" {
            val d = tmp()
            Files.writeString(d.resolve("t.step"), "old")
            ConvertFiles.writeAtomic(
                d,
                "t.step",
                "new".toByteArray(),
                replace = false,
                tempPrefix = ".kstep-export-",
            ) shouldBe
                ConvertFiles.WriteResult.AlreadyExists
            leftovers(d) shouldBe emptyList()
            Files.readString(d.resolve("t.step")) shouldBe "old"
            ConvertFiles.writeAtomic(
                d,
                "t.step",
                "new".toByteArray(),
                replace = true,
                tempPrefix = ".kstep-export-",
            ) shouldBe
                ConvertFiles.WriteResult.Written
            Files.readString(d.resolve("t.step")) shouldBe "new"
        }

        "a symlink target is refused even with replace = true and its destination stays untouched" {
            val d = tmp()
            val victim = d.resolve("victim.txt")
            Files.writeString(victim, "keep")
            val ok =
                try {
                    Files.createSymbolicLink(d.resolve("t.step"), victim)
                    true
                } catch (_: Exception) {
                    false
                }
            if (ok) {
                ConvertFiles
                    .writeAtomic(d, "t.step", "new".toByteArray(), replace = true, tempPrefix = ".kstep-export-")
                    .shouldBeInstanceOf<ConvertFiles.WriteResult.Failed>()
                Files.readString(victim) shouldBe "keep"
                leftovers(d) shouldBe emptyList()
            }
        }
    })
