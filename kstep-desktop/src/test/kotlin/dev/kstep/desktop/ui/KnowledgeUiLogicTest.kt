package dev.kstep.desktop.ui

import androidx.compose.ui.text.AnnotatedString
import dev.kstep.desktop.okf.BlockHeader
import dev.kstep.desktop.okf.BlockHint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class KnowledgeUiLogicTest :
    StringSpec({
        "the block header text names the block, its lines and the cursor state" {
            blockHeaderText(BlockHeader(1, 3, 14, 38, cursorOutside = false, hint = null)) shouldBe
                "Block 2 of 3 · lines 14–38"
            blockHeaderText(BlockHeader(0, 2, 3, 4, cursorOutside = true, hint = BlockHint.SaveToPreview)) shouldBe
                "Block 1 of 2 · lines 3–4 · cursor outside block · Save to preview this block"
            blockHeaderText(BlockHeader(0, 0, 0, 0, cursorOutside = false, hint = BlockHint.NoBlocks)) shouldBe
                "No ```kstep blocks in this note."
        }

        "the knowledge transformation keeps the text and the identity offset mapping, and colours fences and code" {
            val text = "# Note\n\n```kstep\nval x = 1\n```\n"
            val result = KnowledgeSyntaxTransformation(EditorColors.LIGHT).filter(AnnotatedString(text))
            result.text.text shouldBe text
            result.offsetMapping.originalToTransformed(7) shouldBe 7
            result.text.spanStyles.isNotEmpty() shouldBe true
            // the heading line is prose: no style starts in the first line
            result.text.spanStyles.none { it.start < 7 } shouldBe true
        }

        "an untrusted name is escaped in the sidebar text" {
            escapedText("a" + 0x202E.toChar() + "b", androidx.compose.ui.graphics.Color.Gray).text shouldBe "a‹U+202E›b"
        }
    })
