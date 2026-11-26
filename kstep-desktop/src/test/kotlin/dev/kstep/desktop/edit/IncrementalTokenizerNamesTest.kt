package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.random.Random

/** ADR-0089: a changed DSL name set resets the incremental tokenizer by itself. */
class IncrementalTokenizerNamesTest :
    StringSpec({
        "a changed name set re-scans the unchanged text" {
            var names = setOf("other")
            val inc = IncrementalTokenizer(names = { names })
            val text = "val a = 1\nfoo(a)\n"
            inc.update(text).none { it.kind == TokenKind.DSL } shouldBe true
            names = setOf("foo")
            val got = inc.update(text)
            got.any { it.kind == TokenKind.DSL } shouldBe true
            got shouldBe KotlinTokenizer.tokenize(text, names)
            inc.lastStats.full shouldBe true
            inc.verifyAgainstFullScan() shouldBe true
        }

        "an equal but new set instance does not reset" {
            var names = setOf("foo")
            val inc = IncrementalTokenizer(names = { names })
            val text = "val a = 1\nfoo(a)\nval b = 2\n"
            inc.update(text)
            names = setOf("foo")
            val edited = text.replace("val b = 2", "val b = 3")
            inc.update(edited) shouldBe KotlinTokenizer.tokenize(edited, names)
            inc.lastStats.full shouldBe false
        }

        "an edit after a name change equals a full scan" {
            var names = setOf("foo")
            val inc = IncrementalTokenizer(names = { names })
            var text = "foo()\nbar()\nval x = \"\"\"\nraw\n\"\"\"\n"
            inc.update(text)
            names = setOf("bar", "x")
            text = text.replace("raw", "raw2")
            inc.update(text) shouldBe KotlinTokenizer.tokenize(text, names)
            text = "// c\n$text"
            inc.update(text) shouldBe KotlinTokenizer.tokenize(text, names)
            inc.verifyAgainstFullScan() shouldBe true
        }

        "hostile names never produce a token or an exception" {
            val names = setOf("", "a b", "x\ny", "1", "/*")
            val text = "val a = 1\nab\n/* c */ \"s\"\n"
            IncrementalTokenizer(names = { names }).update(text) shouldBe KotlinTokenizer.tokenize(text, names)
            KotlinTokenizer.tokenize(text, names).none { it.kind == TokenKind.DSL } shouldBe true
        }

        "random edits with random name changes equal a full scan" {
            val pool = listOf("foo", "bar", "shape", "x", "val", "fun", "root", "a")
            val words = pool + listOf("\n", " ", "(", ")", "\"", "/*", "*/", "{", "}")
            val random = Random(20261127)
            var comparisons = 0
            for (case in 0 until 60) {
                var names = emptySet<String>()
                val inc = IncrementalTokenizer(names = { names })
                var text =
                    buildString {
                        repeat(
                            random.nextInt(0, 80),
                        ) { append(words[random.nextInt(words.size)]).append(' ') }
                    }
                for (step in 0 until 12) {
                    if (random.nextInt(3) == 0) names = pool.filter { random.nextBoolean() }.toSet()
                    val got = inc.update(text)
                    if (got != KotlinTokenizer.tokenize(text, names) || !inc.verifyAgainstFullScan()) {
                        throw AssertionError("seed=20261127 case=$case step=$step")
                    }
                    comparisons++
                    val at = random.nextInt(text.length + 1)
                    text = text.substring(0, at) + words[random.nextInt(words.size)] + text.substring(at)
                }
            }
            (comparisons >= 500) shouldBe true
        }
    })
