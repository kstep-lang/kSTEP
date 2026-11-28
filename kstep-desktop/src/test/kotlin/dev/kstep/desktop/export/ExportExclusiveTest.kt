package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/** The exclusive write of an agent's export (ADR-0098): the target is created, never replaced. */
class ExportExclusiveTest :
    StringSpec({
        fun entries(dir: Path): List<String> =
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

        fun withDirs(block: (Path, Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                block(Files.createDirectory(dir.resolve("work")), Files.createDirectory(dir.resolve("target")))
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a new file is created and no temp file stays" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), ByteArray(100) { it.toByte() })
                ExportAtomicWriter(chunkBytes = 16).writeExclusive(source, target.resolve("a.step")) { false } shouldBe
                    WriteResult.Written(100)
                Files.readAllBytes(target.resolve("a.step")).size shouldBe 100
                entries(target) shouldBe listOf("a.step")
            }
        }

        "an existing file is never replaced" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                Files.writeString(target.resolve("a.step"), "old!")
                ExportAtomicWriter().writeExclusive(source, target.resolve("a.step")) { false } shouldBe
                    WriteResult.NeedsReplace(4)
                Files.readString(target.resolve("a.step")) shouldBe "old!"
                entries(target) shouldBe listOf("a.step")
            }
        }

        "a file that appears at the last moment stays byte for byte as it was, and no temp file stays" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                val file = target.resolve("a.step")
                val writer = ExportAtomicWriter(beforeMove = { Files.writeString(file, "appeared") })
                writer.writeExclusive(source, file) { false }.shouldBeInstanceOf<WriteResult.NeedsReplace>()
                Files.readString(file) shouldBe "appeared"
                entries(target) shouldBe listOf("a.step")
            }
        }

        "even a confirmed replace flag of the normal path is not used by the exclusive path" {
            withDirs { work, target ->
                val source = Files.write(work.resolve("s"), "new".toByteArray())
                val file = target.resolve("a.step")
                Files.writeString(file, "old")
                ExportAtomicWriter()
                    .writeExclusive(
                        source,
                        file,
                    ) { false }
                    .shouldBeInstanceOf<WriteResult.NeedsReplace>()
                Files.readString(file) shouldBe "old"
            }
        }

        "the controller creates a new file for startExclusive, marks the generation and refuses an existing target" {
            val dir = TestSupport.newDir()
            val out = Files.createDirectory(dir.resolve("out"))
            val session = Files.createDirectory(dir.resolve("session"))
            val script = TestSupport.script(dir, "bracket", "// x\n")
            val controller =
                ExportController(
                    worker = { _, _, gen, _ ->
                        ExportWorkerResult.Produced(
                            Files.write(session.resolve("export-$gen.step"), "ISO-10303-21;\nDATA;".toByteArray()),
                        )
                    },
                )
            try {
                val generation = controller.startExclusive(script, ExportFormat.STEP, out.resolve("bracket.step"))!!
                controller.exclusiveGeneration shouldBe generation
                TestSupport.await(30_000) { controller.state.value is ExportState.Succeeded } shouldBe true
                controller.state.value.generation shouldBe generation
                Files.readString(out.resolve("bracket.step")) shouldBe "ISO-10303-21;\nDATA;"
                // the same name now exists: nothing starts and the file is untouched
                controller.startExclusive(script, ExportFormat.STEP, out.resolve("bracket.step")) shouldBe null
                Files.readString(out.resolve("bracket.step")) shouldBe "ISO-10303-21;\nDATA;"
                entries(out) shouldBe listOf("bracket.step")
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
