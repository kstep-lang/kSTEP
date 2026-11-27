package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random

/** ADR-0091: the worker's script line becomes a selection, always cross-checked against the buffer. */
class PartSourceResolverTest :
    StringSpec({
        fun resolve(
            text: String,
            name: String,
            line: Int?,
            usable: Boolean = true,
            same: Int = 1,
            budget: Long = PartSourceLocator.DEFAULT_BUDGET_NANOS,
            clock: () -> Long = System::nanoTime,
        ) = PartSourceResolver.resolve(text, name, line, usable, same, budget, clock)

        fun PartSourceResolution.selected(text: String): String =
            when (this) {
                is PartSourceResolution.Name -> text.substring(start, end)
                is PartSourceResolution.Line -> text.substring(start, end)
                is PartSourceResolution.Heuristic -> error("no selection")
            }

        "stage 1: the name on the reported line is selected without its quotes" {
            val text = "val a = 1\nplace(u, \"Bolt\")\nval b = 2\n"
            val r = resolve(text, "Bolt", 2).shouldBeInstanceOf<PartSourceResolution.Name>()
            r.selected(text) shouldBe "Bolt"
        }

        "stage 2: no name on the line, but one unique literal and one part of that name" {
            val text = "val bolt = product(\"Bolt\")\nval x = 1\nplace(u)\n"
            val r = resolve(text, "Bolt", 3, same = 1).shouldBeInstanceOf<PartSourceResolution.Name>()
            r.selected(text) shouldBe "Bolt"
        }

        "stage 3: two parts of one name each mark their own line, trimmed" {
            val text = "val n = \"Bolt\"\n    place(u1)   \n\tplace(u2)\n"
            val first = resolve(text, "Bolt", 2, same = 2).shouldBeInstanceOf<PartSourceResolution.Line>()
            first.selected(text) shouldBe "place(u1)"
            first.line shouldBe 2
            first.findQuery shouldBe "\"Bolt\""
            val second = resolve(text, "Bolt", 3, same = 2).shouldBeInstanceOf<PartSourceResolution.Line>()
            second.selected(text) shouldBe "place(u2)"
        }

        "stage 3: an empty line puts the caret at the line start" {
            val text = "a\n\n   \nb"
            val empty = resolve(text, "Zed", 2, same = 2).shouldBeInstanceOf<PartSourceResolution.Line>()
            empty.start shouldBe 2
            empty.end shouldBe 2
            val blank = resolve(text, "Zed", 3, same = 2).shouldBeInstanceOf<PartSourceResolution.Line>()
            blank.start shouldBe blank.end
            blank.start shouldBe 3
        }

        "the literal twice on the reported line is not decided by the line" {
            val text = "f(\"Bolt\", \"Bolt\")\n"
            val r = resolve(text, "Bolt", 1, same = 2)
            r.shouldBeInstanceOf<PartSourceResolution.Line>()
        }

        "a name that cannot be searched as a literal marks the line without a find query" {
            val text = "val x = 1\nplace(u)\n"
            val r = resolve(text, "a\"b", 2).shouldBeInstanceOf<PartSourceResolution.Line>()
            r.findQuery shouldBe null
            r.selected(text) shouldBe "place(u)"
            resolve(text, "", 2).shouldBeInstanceOf<PartSourceResolution.Line>().findQuery shouldBe null
        }

        "an unusable position falls back to the name heuristic" {
            val text = "x\nplace(u, \"Bolt\")\n"
            val found = resolve(text, "Bolt", 2, usable = false).shouldBeInstanceOf<PartSourceResolution.Name>()
            found.selected(text) shouldBe "Bolt"
            resolve("x \"Bolt\" \"Bolt\"", "Bolt", 1, usable = false) shouldBe
                PartSourceResolution.Heuristic(SourceLocation.Ambiguous("\"Bolt\""))
            resolve("nothing", "Bolt", 1, usable = false) shouldBe
                PartSourceResolution.Heuristic(SourceLocation.NotFound)
            resolve("nothing", "x".repeat(300), 1, usable = false) shouldBe
                PartSourceResolution.Heuristic(SourceLocation.TooLong)
            resolve("nothing", "a\"b", 1, usable = false) shouldBe
                PartSourceResolution.Heuristic(SourceLocation.NotLiteral)
            var now = 0L
            resolve("abc \"Bolt\"", "Bolt", null, usable = false, budget = 10, clock = {
                now += 100
                now
            }) shouldBe
                PartSourceResolution.Heuristic(SourceLocation.TimedOut)
        }

        "a line outside the text falls back, a line without the literal never marks outside the buffer" {
            val text = "one\ntwo\n"
            resolve(text, "Bolt", 99) shouldBe PartSourceResolution.Heuristic(SourceLocation.NotFound)
            resolve(text, "Bolt", Int.MAX_VALUE) shouldBe PartSourceResolution.Heuristic(SourceLocation.NotFound)
            resolve(text, "Bolt", 0) shouldBe PartSourceResolution.Heuristic(SourceLocation.NotFound)
            val line = resolve(text, "Bolt", 2, same = 3).shouldBeInstanceOf<PartSourceResolution.Line>()
            line.selected(text) shouldBe "two"
        }

        "isNameAt checks bounds, quotes and the name itself" {
            val text = "\"Bolt\""
            PartSourceResolver.isNameAt(text, 1, 5, "Bolt") shouldBe true
            PartSourceResolver.isNameAt(text, 0, 4, "Bolt") shouldBe false // no quote before
            PartSourceResolver.isNameAt(text, 1, 6, "Bolt") shouldBe false // length mismatch / end at len
            PartSourceResolver.isNameAt(text, 2, 6, "olt\"") shouldBe false // end == length: no closing quote
            PartSourceResolver.isNameAt(text, 1, 5, "Bolx") shouldBe false
            PartSourceResolver.isNameAt(text, -1, 3, "Bolt") shouldBe false
            PartSourceResolver.isNameAt(text, 5, 1, "") shouldBe false
            PartSourceResolver.isNameAt(text, 100, 104, "Bolt") shouldBe false
            PartSourceResolver.isNameAt("\"\"", 1, 1, "") shouldBe true
        }

        "whatever the line and the name, the offsets stay inside the text" {
            val random = Random(91)
            val alphabet = "ab\" \n\t\\\$ü"
            repeat(500) {
                val text = String(CharArray(random.nextInt(0, 60)) { alphabet[random.nextInt(alphabet.length)] })
                val name = String(CharArray(random.nextInt(0, 5)) { alphabet[random.nextInt(alphabet.length)] })
                val line = if (random.nextBoolean()) null else random.nextInt(-2, 20)
                val r =
                    resolve(text, name, line, usable = random.nextBoolean(), same = random.nextInt(0, 3))
                when (r) {
                    is PartSourceResolution.Name -> {
                        (r.start in 0..r.end && r.end <= text.length) shouldBe true
                        text.substring(r.start, r.end) shouldBe name
                    }
                    is PartSourceResolution.Line -> (r.start in 0..r.end && r.end <= text.length) shouldBe true
                    is PartSourceResolution.Heuristic -> Unit
                }
            }
        }
    })
