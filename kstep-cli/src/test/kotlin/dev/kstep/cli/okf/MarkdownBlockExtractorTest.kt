package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MarkdownBlockExtractorTest :
    StringSpec({
        fun ex(
            text: String,
            body: Int = 1,
        ) = MarkdownBlockExtractor.extract(text, body)

        "a backtick block gets its lines and offsets" {
            val text = "intro\n```kstep\nshape(a)\nshape(b)\n```\noutro\n"
            val e = ex(text)
            e.blocks.size shouldBe 1
            val b = e.blocks[0]
            b.openLine shouldBe 2
            b.contentStartLine shouldBe 3
            b.contentEndLine shouldBe 4
            b.closeLine shouldBe 5
            b.state shouldBe BlockState.OK
            MarkdownBlockExtractor.content(text, b) shouldBe "shape(a)\nshape(b)"
        }

        "tilde fences and longer closing fences work" {
            val e = ex("~~~kstep\nx\n~~~~~\n")
            e.blocks.single().state shouldBe BlockState.OK
            e.blocks.single().closeLine shouldBe 3
        }

        "an outer tilde fence swallows an inner kstep fence" {
            val e = ex("~~~~\n```kstep\nx\n```\n~~~~\n")
            e.blocks.size shouldBe 0
            e.others.size shouldBe 1
        }

        "an unclosed block is reported with its line" {
            val e = ex("a\n```kstep\nx\ny")
            val b = e.blocks.single()
            b.state shouldBe BlockState.UNCLOSED
            b.openLine shouldBe 2
            b.closeLine shouldBe null
            b.contentEndLine shouldBe 4
        }

        "four spaces of indent or a tab is no fence, three spaces is" {
            ex("    ```kstep\nx\n```\n").blocks.size shouldBe 0
            ex("\t```kstep\nx\n```\n").blocks.size shouldBe 0
            ex("   ```kstep\nx\n```\n").blocks.size shouldBe 1
        }

        "the info string must start with the word kstep" {
            ex("```kstep {title=\"x\"}\nx\n```\n").blocks.size shouldBe 1
            ex("```kstepx\nx\n```\n").blocks.size shouldBe 0
            ex("```kotlin\nx\n```\n").blocks.size shouldBe 0
            ex("```kstep `x`\nx\n```\n").blocks.size shouldBe 0 // a backtick in the info string: not a fence
        }

        "CRLF, CR and a BOM are tolerated" {
            val bom = 0xFEFF.toChar()
            val crlf = ex("${bom}a\r\n```kstep\r\nx\r\n```\r\n")
            crlf.blocks.single().openLine shouldBe 2
            crlf.blocks.single().state shouldBe BlockState.OK
            ex("```kstep\rx\r```\r").blocks.single().closeLine shouldBe 3
            ex("$bom```kstep\nx\n```\n").blocks.size shouldBe 1
        }

        "an empty block is EMPTY and has an empty line range" {
            val e = ex("```kstep\n```\n```kstep\n  \n```\n")
            e.blocks[0].state shouldBe BlockState.EMPTY
            (e.blocks[0].contentEndLine < e.blocks[0].contentStartLine) shouldBe true
            e.blocks[1].state shouldBe BlockState.EMPTY
        }

        "blockAtLine counts the fence lines" {
            val e = ex("x\n```kstep\na\n```\ny\n")
            MarkdownBlockExtractor.blockAtLine(e, 1) shouldBe null
            MarkdownBlockExtractor.blockAtLine(e, 2) shouldBe 0
            MarkdownBlockExtractor.blockAtLine(e, 3) shouldBe 0
            MarkdownBlockExtractor.blockAtLine(e, 4) shouldBe 0
            MarkdownBlockExtractor.blockAtLine(e, 5) shouldBe null
        }

        "a fence in a block quote or list item is not recognised" {
            ex("> ```kstep\n> x\n> ```\n").blocks.size shouldBe 0
            ex("- ```kstep\n  x\n  ```\n").blocks.size shouldBe 0
        }

        "the 201st block sets tooManyBlocks and the scan goes on" {
            val sb = StringBuilder()
            repeat(201) { sb.append("```kstep\nx\n```\n") }
            sb.append("~~~py\nq\n~~~\n")
            val e = ex(sb.toString())
            e.blocks.size shouldBe OkfLimits.MAX_BLOCKS
            e.tooManyBlocks shouldBe true
            e.others.size shouldBe 1
        }

        "a block over 256 KiB is TOO_LARGE" {
            val big = "x".repeat(OkfLimits.MAX_BLOCK_BYTES + 1)
            ex("```kstep\n$big\n```\n").blocks.single().state shouldBe BlockState.TOO_LARGE
            val ok = "x".repeat(OkfLimits.MAX_BLOCK_BYTES - 10)
            ex("```kstep\n$ok\n```\n").blocks.single().state shouldBe BlockState.OK
        }

        "the body can start after a front matter" {
            val e = ex("```kstep\nx\n```\n```kstep\ny\n```\n", body = 4)
            e.blocks.size shouldBe 1
            e.blocks[0].openLine shouldBe 4
        }
    })
