package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Hostile notes: linear time, no memory blow-up, the bounds hold (ADR-0058). */
class MarkdownBlockExtractorHostileTest :
    StringSpec({
        "one mebibyte of backticks".config(timeout = 30.seconds) {
            val e = MarkdownBlockExtractor.extract("`".repeat(1 shl 20))
            e.blocks.size shouldBe 0
        }

        "100000 opening fences stay within the caps".config(timeout = 30.seconds) {
            val sb = StringBuilder()
            repeat(100_000) { sb.append("```kstep\n") }
            val e = MarkdownBlockExtractor.extract(sb.toString())
            // the first fence swallows the rest (an open fence ignores further openings): one unclosed block
            e.blocks.size shouldBe 1
            (e.blocks[0].state != BlockState.OK) shouldBe true
        }

        "100000 closed blocks: capped at 200, scan completes".config(timeout = 30.seconds) {
            val sb = StringBuilder()
            repeat(100_000) { sb.append("```kstep\nx\n```\n") }
            val e = MarkdownBlockExtractor.extract(sb.toString())
            e.blocks.size shouldBe OkfLimits.MAX_BLOCKS
            e.tooManyBlocks shouldBe true
        }

        "a single line of 20 MiB".config(timeout = 30.seconds) {
            val sb = StringBuilder(20 shl 20)
            sb.append("```kstep ")
            while (sb.length < (20 shl 20)) sb.append("a")
            val e = MarkdownBlockExtractor.extract(sb.toString())
            e.blocks.single().state shouldBe BlockState.UNCLOSED
        }

        "ten thousand tilde runs of ten thousand characters".config(timeout = 30.seconds) {
            val run = "~".repeat(10_000)
            val sb = StringBuilder()
            repeat(10_000) { sb.append(run).append('\n') }
            val e = MarkdownBlockExtractor.extract(sb.toString())
            e.blocks.size shouldBe 0
        }
    })
