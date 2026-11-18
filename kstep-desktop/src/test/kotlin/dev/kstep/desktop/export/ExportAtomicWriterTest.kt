package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class ExportAtomicWriterTest :
    StringSpec({
        fun entries(dir: Path): List<String> =
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

        fun withDirs(block: (Path, Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                val target = Files.createDirectory(dir.resolve("target"))
                val work = Files.createDirectory(dir.resolve("work"))
                block(work, target)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a new file is written and no temp file is left" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), ByteArray(100) { it.toByte() })
                val result =
                    ExportAtomicWriter(
                        chunkBytes = 16,
                    ).write(source, target.resolve("a.step"), false) { false }
                result shouldBe WriteResult.Written(100)
                Files.readAllBytes(target.resolve("a.step")).size shouldBe 100
                entries(target) shouldBe listOf("a.step")
            }
        }

        "an existing target is not replaced without confirmation" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                Files.writeString(target.resolve("a.step"), "old!")
                val result = ExportAtomicWriter().write(source, target.resolve("a.step"), false) { false }
                result shouldBe WriteResult.NeedsReplace(4)
                Files.readString(target.resolve("a.step")) shouldBe "old!"
                entries(target) shouldBe listOf("a.step")
            }
        }

        "a confirmed replacement replaces the file" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                Files.writeString(target.resolve("a.step"), "old!")
                ExportAtomicWriter().write(source, target.resolve("a.step"), true) { false } shouldBe
                    WriteResult.Written(3)
                Files.readString(target.resolve("a.step")) shouldBe "new"
            }
        }

        "a file that appears between the check and the move is not overwritten silently" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                val file = target.resolve("a.step")
                val writer = ExportAtomicWriter(beforeMove = { Files.writeString(file, "appeared") })
                writer.write(source, file, false) { false } shouldBe WriteResult.NeedsReplace(8)
                Files.readString(file) shouldBe "appeared"
                entries(target) shouldBe listOf("a.step")
            }
        }

        "a stop in the middle removes the temp file and leaves the target alone" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), ByteArray(1000))
                Files.writeString(target.resolve("a.step"), "old")
                var calls = 0
                val result =
                    ExportAtomicWriter(chunkBytes = 10).write(source, target.resolve("a.step"), true) { ++calls > 3 }
                result shouldBe WriteResult.Cancelled
                Files.readString(target.resolve("a.step")) shouldBe "old"
                entries(target) shouldBe listOf("a.step")
            }
        }

        "a target that became a symbolic link is refused" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                val victim = Files.writeString(work.resolve("victim"), "keep")
                val file = target.resolve("a.step")
                val writer =
                    ExportAtomicWriter(
                        beforeMove = {
                            try {
                                Files.createSymbolicLink(file, victim)
                            } catch (_: Exception) {
                                Files.createDirectory(file) // no links here: a directory is just as invalid
                            }
                        },
                    )
                val result = writer.write(source, file, true) { false }
                result.shouldBeInstanceOf<WriteResult.Failed>().code shouldBe DesktopErrorCodes.EXPORT_TARGET_INVALID
                Files.readString(victim) shouldBe "keep"
            }
        }

        "an I/O failure is KSTEP-D-023, names no path and leaves no temp file" {
            val dir = TestSupport.newDir()
            try {
                val source = Files.write(dir.resolve("s"), "x".toByteArray())
                val gone = dir.resolve("gone")
                val result = ExportAtomicWriter().write(source, gone.resolve("a.step"), false) { false }
                val failed = result.shouldBeInstanceOf<WriteResult.Failed>()
                failed.code shouldBe DesktopErrorCodes.EXPORT_WRITE_FAILED
                failed.message shouldNotContain dir.toString()
                entries(dir) shouldBe listOf("s")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
