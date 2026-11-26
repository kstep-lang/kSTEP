package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** The core claim of ADR-0088: after any edit the incremental result equals a full scan, and so do the line states. */
class IncrementalTokenizerPropertyTest :
    StringSpec({
        val fragments =
            listOf(
                "\"",
                "\"\"\"",
                "\"\"\"\"",
                "\\\\",
                "\\\"",
                "'",
                "\${",
                "\$x",
                "{",
                "}",
                "/*",
                "*/",
                "/",
                "*",
                "//",
                "\n",
                "\r\n",
                " ",
                "val",
                "fun",
                "shape",
                "x",
                "1",
                "0x1F",
                "1.5e3",
                "@Ann",
                "`",
                "😀",
                "\uD83D",
                "\uDE00",
                "\n",
                "\n",
            )

        fun fragment(r: Random) = fragments[r.nextInt(fragments.size)]

        fun randomText(r: Random) = buildString { repeat(r.nextInt(0, 301)) { append(fragment(r)) } }

        fun edit(
            r: Random,
            t: String,
        ): String =
            when (r.nextInt(8)) {
                0 -> {
                    val at = r.nextInt(t.length + 1)
                    t.substring(0, at) + fragment(r) + t.substring(at)
                }
                1 -> {
                    if (t.isEmpty()) {
                        t
                    } else {
                        val at = r.nextInt(t.length)
                        val to = minOf(t.length, at + r.nextInt(1, 21))
                        t.removeRange(at, to)
                    }
                }
                2 -> {
                    if (t.isEmpty()) {
                        fragment(r)
                    } else {
                        val at = r.nextInt(t.length)
                        val to = minOf(t.length, at + r.nextInt(1, 21))
                        t.substring(0, at) + fragment(r) + t.substring(to)
                    }
                }
                3 -> fragment(r) + t
                4 -> t + fragment(r)
                5 -> randomText(r)
                6 -> ""
                else -> {
                    // a single character in the middle of a surrogate pair or anywhere
                    val at = r.nextInt(t.length + 1)
                    t.substring(0, at) + "\uD83D" + t.substring(at)
                }
            }

        "incremental result equals a full scan after random edits" {
            val random = Random(20261126)
            var comparisons = 0
            for (case in 0 until 400) {
                val inc = IncrementalTokenizer()
                var text = randomText(random)
                for (step in 0 until 15) {
                    val got = inc.update(text)
                    val want = KotlinTokenizer.tokenize(text)
                    if (got != want || !inc.verifyAgainstFullScan()) {
                        throw AssertionError(
                            "seed=20261126 case=$case step=$step text=${text.take(300).replace("\n", "\\n")}",
                        )
                    }
                    comparisons++
                    text = edit(random, text)
                }
            }
            (comparisons >= 6_000) shouldBe true
        }
    })
