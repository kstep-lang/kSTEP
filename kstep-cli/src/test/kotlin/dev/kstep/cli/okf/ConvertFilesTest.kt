package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class ConvertFilesTest :
    StringSpec({
        fun tmp(): Path = Files.createTempDirectory("kstep-convert-test").toRealPath()

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

        "input: symlink, directory, oversize, invalid UTF-8" {
            val d = tmp()
            val f = d.resolve("a.kstep.kts")
            Files.writeString(f, "x")
            if (link(d.resolve("l.kstep.kts"), f)) {
                ConvertFiles
                    .readBounded(
                        d.resolve("l.kstep.kts"),
                        100,
                    ).shouldBeInstanceOf<ConvertFiles.ReadResult.Failed>()
            }
            ConvertFiles.readBounded(d, 100).shouldBeInstanceOf<ConvertFiles.ReadResult.Failed>()
            val big = d.resolve("big")
            Files.write(big, ByteArray(1_048_577))
            ConvertFiles.readBounded(big, 1_048_576).shouldBeInstanceOf<ConvertFiles.ReadResult.Failed>()
            val exact = d.resolve("exact")
            Files.write(exact, ByteArray(1_048_576) { 'a'.code.toByte() })
            ConvertFiles.readBounded(exact, 1_048_576).shouldBeInstanceOf<ConvertFiles.ReadResult.Ok>()
            val bad = d.resolve("bad")
            Files.write(bad, byteArrayOf(0xC3.toByte(), 0x28))
            ConvertFiles.readBounded(bad, 100).shouldBeInstanceOf<ConvertFiles.ReadResult.Failed>()
        }

        "out dir must exist and is never created; a link is allowed" {
            val d = tmp()
            val missing = d.resolve("nope")
            ConvertFiles.resolveOutDir(missing).shouldBeInstanceOf<ConvertFiles.DirResult.Failed>()
            Files.exists(missing) shouldBe false
            val sub = Files.createDirectory(d.resolve("sub"))
            if (link(d.resolve("lnk"), sub)) {
                ConvertFiles
                    .resolveOutDir(
                        d.resolve("lnk"),
                    ).shouldBeInstanceOf<ConvertFiles.DirResult.Ok>()
                    .real shouldBe
                    sub
            }
        }

        "targets: link and directory are invalid even with force, input itself too" {
            val d = tmp()
            val input = Files.writeString(d.resolve("in.kstep.kts"), "x")
            val victim = Files.writeString(d.resolve("victim.txt"), "keep")
            if (link(d.resolve("t.md"), victim)) {
                ConvertFiles.checkTarget(d, "t.md", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
                ConvertFiles
                    .writeAtomic(
                        d,
                        "t.md",
                        "new".toByteArray(),
                        replace = true,
                    ).shouldBeInstanceOf<ConvertFiles.WriteResult.Failed>()
                Files.readString(victim) shouldBe "keep"
            }
            Files.createDirectory(d.resolve("dir.md"))
            ConvertFiles.checkTarget(d, "dir.md", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
            ConvertFiles.checkTarget(d, "in.kstep.kts", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
            ConvertFiles.checkTarget(d, "../x", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
            ConvertFiles.checkTarget(d, "free.md", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Free>()
            ConvertFiles.checkTarget(d, "victim.txt", input).shouldBeInstanceOf<ConvertFiles.TargetCheck.Exists>()
        }

        "writeAtomic: race via beforeMove keeps the foreign file and leaves no temp" {
            val d = tmp()
            val r =
                ConvertFiles.writeAtomic(d, "t.md", "mine".toByteArray(), replace = false) {
                    Files.writeString(d.resolve("t.md"), "foreign")
                }
            r shouldBe ConvertFiles.WriteResult.AlreadyExists
            Files.readString(d.resolve("t.md")) shouldBe "foreign"
            Files.list(d).use { s ->
                s.filter { it.fileName.toString().startsWith(".kstep-convert-") }.count()
            } shouldBe
                0L
        }

        "writeAtomic: failing move leaves no temp; replace overwrites a regular file" {
            val d = tmp()
            val r =
                ConvertFiles.writeAtomic(d, "t.md", "x".toByteArray(), replace = false) {
                    throw java.io.IOException("simulated")
                }
            r.shouldBeInstanceOf<ConvertFiles.WriteResult.Failed>()
            Files.list(d).use { s -> s.count() } shouldBe 0L
            Files.writeString(d.resolve("t.md"), "old")
            ConvertFiles.writeAtomic(d, "t.md", "new".toByteArray(), replace = true) shouldBe
                ConvertFiles.WriteResult.Written
            Files.readString(d.resolve("t.md")) shouldBe "new"
        }

        "checkTarget with several inputs: a hard link to an input is invalid, the single-input variant is unchanged" {
            val d = tmp()
            val a = Files.writeString(d.resolve("a.kstep.kts"), "x")
            val b = Files.writeString(d.resolve("b.kstep.kts"), "y")
            val hard = d.resolve("hard.md")
            val linked =
                try {
                    Files.createLink(hard, b)
                    true
                } catch (_: Exception) {
                    false
                }
            val keys =
                listOf(a, b)
                    .mapNotNull {
                        Files.readAttributes(it, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
                    }.toSet()
            if (linked) {
                ConvertFiles
                    .checkTarget(d, "hard.md", keys, setOf(a, b))
                    .shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
            }
            ConvertFiles
                .checkTarget(
                    d,
                    "free.md",
                    keys,
                    setOf(a, b),
                ).shouldBeInstanceOf<ConvertFiles.TargetCheck.Free>()
            ConvertFiles
                .checkTarget(
                    d,
                    "a.kstep.kts",
                    keys,
                    setOf(a, b),
                ).shouldBeInstanceOf<ConvertFiles.TargetCheck.Invalid>()
            ConvertFiles.checkTarget(d, "b.kstep.kts", a).shouldBeInstanceOf<ConvertFiles.TargetCheck.Exists>()
        }
    })
