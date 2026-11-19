package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path

class ExportPathTextTest :
    StringSpec({
        "the file name is whole, the folder is shortened and has no trailing separator" {
            val label =
                ExportPathText.label(
                    Path.of("/private/tmp/claude-501/-Users-irakli-Documents/scratchpad/demo.step"),
                )
            label.fileName shouldBe "demo.step"
            label.folder.length shouldBeLessThanOrEqualTo 48
            label.folder shouldContain "…"
            label.folder shouldEndWith "scratchpad"
            label.folder shouldNotContain "demo.step"
        }

        "a short folder is shown whole" {
            ExportPathText.label(Path.of("/home/me/parts/a.step")).folder shouldBe "/home/me/parts"
        }

        "a file directly in the root and a path without parent" {
            ExportPathText.label(Path.of("/a.step")).folder shouldBe "/"
            ExportPathText.label(Path.of("/")).fileName shouldBe ""
            ExportPathText.label(Path.of("/")).folder shouldBe "/"
        }

        "a name of 300 characters keeps its extension within the limit" {
            val label = ExportPathText.label(Path.of("/tmp/" + "n".repeat(240) + ".step"))
            label.fileName.length shouldBeLessThanOrEqualTo 64
            label.fileName shouldEndWith ".step"
        }

        "forty segments stay within the folder limit and keep the last one" {
            val dir = (1..40).joinToString("/") { "segment$it" }
            val label = ExportPathText.label(Path.of("/$dir/out.glb"))
            label.folder.length shouldBeLessThanOrEqualTo 48
            label.folder shouldEndWith "segment40"
        }

        "bidirectional and control characters are escaped everywhere" {
            val label = ExportPathText.label(Path.of("/tmp/a‮b/c\u0007d/f‮.step"))
            for (text in listOf(label.fileName, label.folder, label.full)) {
                text shouldNotContain "‮"
                text shouldNotContain "\u0007"
            }
            label.full shouldContain "‹U+202E›"
        }

        "unicode segments are not cut in a surrogate pair" {
            val dir = "😀".repeat(60)
            val label = ExportPathText.label(Path.of("/$dir/x/y/z/f.step"), folderMax = 20)
            label.folder.length shouldBeLessThanOrEqualTo 20
            label.folder.none {
                Character.isSurrogate(it) &&
                    !label.folder.codePoints().anyMatch { cp ->
                        cp > 0xFFFF
                    }
            } shouldBe
                true
        }
    })
