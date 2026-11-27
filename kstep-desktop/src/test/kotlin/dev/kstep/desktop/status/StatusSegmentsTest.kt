package dev.kstep.desktop.status

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.Locale

class StatusSegmentsTest :
    StringSpec({
        val sep = StatusLineText.SEPARATOR

        fun busy() =
            StatusSegments.build(
                knowledgeLabel = "Knowledge",
                runState = "● Ok",
                durationMs = 1234L,
                triangles = 12345,
                triangleWarning = "Large model",
                note = "A note",
                transientNotice = "Saved",
                aiStatus = "AI ready",
                hidden = 2,
                transparent = 3,
                dirty = true,
            )

        fun parts(text: String) = if (text.isEmpty()) emptyList() else text.split(sep)

        "a large budget shows everything in order" {
            val fitted = StatusSegments.fit(busy(), 1000)
            fitted.droppedCount shouldBe 0
            fitted.shown shouldBe fitted.full
            fitted.shown shouldBe
                listOf(
                    "Knowledge",
                    "● Ok",
                    "1234 ms",
                    "12,345 triangles",
                    "Large model",
                    "A note",
                    "Saved",
                    "AI ready",
                    "2 hidden",
                    "3 transparent",
                    "unsaved changes",
                ).joinToString(sep)
        }

        "a shrinking budget drops duration, triangles, then medium, then high segments" {
            val segments = busy()
            val seen = mutableListOf<Set<String>>()
            var previous = parts(StatusSegments.fit(segments, 1000).shown).toSet()
            for (budget in 200 downTo 0) {
                val now = parts(StatusSegments.fit(segments, budget).shown).toSet()
                if (now != previous) seen += previous - now
                previous = now
            }
            val order = seen.flatten()
            order.indexOf("1234 ms") shouldBe 0
            order.indexOf("12,345 triangles") shouldBe 1
            val medium = listOf("Large model", "A note", "3 transparent").map { order.indexOf(it) }
            val high = listOf("Knowledge", "Saved", "AI ready").map { order.indexOf(it) }
            (medium.all { it in 2..4 }) shouldBe true
            (high.all { it in 5..7 }) shouldBe true
            order.contains("● Ok") shouldBe false
        }

        "essential segments stay whole even with a tiny budget" {
            val error = StatusSegments.build(null, "● Error", null, null, null, null, null, null, 2, 0, true)
            val expected = listOf("● Error", "2 hidden", "unsaved changes").joinToString(sep)
            for (budget in listOf(10, 0, -5)) StatusSegments.fit(error, budget).shown shouldBe expected
        }

        "every shown segment is one of the full segments and full is always complete" {
            val segments = busy()
            val all = segments.map { it.text }
            for (budget in 0..120 step 7) {
                val fitted = StatusSegments.fit(segments, budget)
                fitted.full shouldBe all.joinToString(sep)
                parts(fitted.shown).forEach { (it in all) shouldBe true }
                parts(fitted.shown).size + fitted.droppedCount shouldBe all.size
            }
        }

        "the width in dp becomes a character budget" {
            StatusSegments.maxChars(700f) shouldBe 100
        }

        "the result is deterministic" {
            StatusSegments.fit(busy(), 60) shouldBe StatusSegments.fit(busy(), 60)
        }

        "empty, null and blank segments are left out" {
            val segments = StatusSegments.build("", "● Ok", null, null, " ", null, null, "", 0, 0, false)
            segments.map { it.text } shouldBe listOf("● Ok")
            StatusSegments.fit(emptyList(), 10) shouldBe FittedStatus("", "", 0)
        }

        "triangles use a grouping comma whatever the default locale is" {
            val before = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                val text =
                    StatusSegments.build(null, null, null, 12345, null, null, null, null, 0, 0, false).single().text
                text shouldBe "12,345 triangles"
                text shouldNotContain "."
                text shouldContain ","
            } finally {
                Locale.setDefault(before)
            }
        }
    })
