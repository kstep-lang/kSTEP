package dev.kstep.desktop.okf

import dev.kstep.cli.okf.MarkdownBlockExtractor
import dev.kstep.cli.okf.OkfLimits
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

class OkfSmallPartsTest :
    StringSpec({
        "BlockLineMap maps content lines to note lines" {
            val m = BlockLineMap(contentStartLine = 14, contentLineCount = 5)
            m.toMarkdown(1) shouldBe 14
            m.toMarkdown(3) shouldBe 16
            m.toMarkdown(5) shouldBe 18
            m.toMarkdown(6) shouldBe null
            m.toMarkdown(0) shouldBe null
            m.toMarkdown(null) shouldBe null
        }

        "the session file is private, removed after use and after an exception" {
            val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()
            val dir = BlockSessionFile.createSessionDir()
            try {
                var seen: Path? = null
                BlockSessionFile.withBlockFile(dir, 7, "shape(x)") { f ->
                    seen = f
                    Files.readString(f) shouldBe "shape(x)"
                    f.fileName.toString() shouldBe "block-7.kstep.kts"
                    if (posix) {
                        java.nio.file.attribute.PosixFilePermissions
                            .toString(Files.getPosixFilePermissions(f)) shouldBe "rw-------"
                        java.nio.file.attribute.PosixFilePermissions
                            .toString(Files.getPosixFilePermissions(dir)) shouldBe "rwx------"
                    }
                }
                Files.exists(seen!!) shouldBe false
                runCatching {
                    BlockSessionFile.withBlockFile<Unit>(dir, 8, "x") { throw IllegalStateException("boom") }
                }
                Files.exists(dir.resolve("block-8.kstep.kts")) shouldBe false
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "an existing file is never overwritten" {
            val dir = BlockSessionFile.createSessionDir()
            try {
                Files.writeString(dir.resolve("block-1.kstep.kts"), "mine")
                runCatching { BlockSessionFile.withBlockFile(dir, 1, "x") { } }.isFailure shouldBe true
                Files.readString(dir.resolve("block-1.kstep.kts")) shouldBe "mine"
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the highlight plan has fence lines, code and other fences; a large block has no code range" {
            val text = "~~~py\nq\n~~~\n```kstep\nshape(a)\n```\n"
            val plan = KnowledgeHighlightPlan.of(MarkdownBlockExtractor.extract(text))
            plan.count { it.kind == HlKind.OTHER_FENCE } shouldBe 1
            plan.count { it.kind == HlKind.FENCE_LINE } shouldBe 2
            val code = plan.single { it.kind == HlKind.KSTEP_CODE }
            text.substring(code.start, code.end) shouldBe "shape(a)"
            code.offsetBase shouldBe code.start
            val big = "x".repeat(OkfLimits.MAX_BLOCK_BYTES + 1)
            val bigPlan = KnowledgeHighlightPlan.of(MarkdownBlockExtractor.extract("```kstep\n$big\n```\n"))
            bigPlan.count { it.kind == HlKind.KSTEP_CODE } shouldBe 0
            bigPlan.count { it.kind == HlKind.FENCE_LINE } shouldBe 2
        }

        "the file limit of the okf logic equals the editor limit" {
            dev.kstep.desktop.edit.EditorText.MAX_BYTES
                .toLong() shouldBe OkfLimits.MAX_FILE_BYTES
        }
    })
