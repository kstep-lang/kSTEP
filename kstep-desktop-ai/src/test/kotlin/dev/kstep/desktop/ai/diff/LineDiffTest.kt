package dev.kstep.desktop.ai.diff

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random

class LineDiffTest :
    FunSpec({
        fun d(
            a: String,
            b: String,
        ) = LineDiff.compute(a, b)

        test("identical texts give no hunks") {
            val r = d("a\nb\n", "a\nb\n") as DiffResult.Hunks
            r.hunks shouldBe emptyList()
            r.added shouldBe 0
        }
        test("only added, only removed, mixed") {
            (d("a\nb", "a\nb\nc") as DiffResult.Hunks).let {
                it.added shouldBe 1
                it.removed shouldBe 0
            }
            (d("a\nb\nc", "a\nc") as DiffResult.Hunks).let {
                it.added shouldBe 0
                it.removed shouldBe 1
            }
            (d("a\nb\nc", "a\nx\nc") as DiffResult.Hunks).let {
                it.added shouldBe 1
                it.removed shouldBe 1
            }
        }
        test("empty old or new text") {
            (d("", "a\nb") as DiffResult.Hunks).added shouldBe 2
            (d("a\nb", "") as DiffResult.Hunks).removed shouldBe 2
            (d("", "") as DiffResult.Hunks).hunks shouldBe emptyList()
        }
        test("three lines of context and a collapsed gap, correct line numbers") {
            val old = (1..30).joinToString("\n") { "l$it" }
            val new = old.replace("l15", "X15")
            val h = (d(old, new) as DiffResult.Hunks).hunks.single().lines
            h.first().shouldBeInstanceOf<DiffLine.Context>().oldNo shouldBe 12
            h.filterIsInstance<DiffLine.Removed>().single().oldNo shouldBe 15
            h.filterIsInstance<DiffLine.Added>().single().newNo shouldBe 15
            h.filterIsInstance<DiffLine.Context>().size shouldBe 6
            h.last().shouldBeInstanceOf<DiffLine.Collapsed>().count shouldBe 12
        }
        test("too many edits or lines give TooLarge") {
            LineDiff.compute("a", "b", maxEdits = 0).shouldBeInstanceOf<DiffResult.TooLarge>()
            LineDiff.compute("a\nb\nc", "a", maxLines = 2).shouldBeInstanceOf<DiffResult.TooLarge>()
        }
        test("applying the hunks to the old text gives the new text (100 random pairs)") {
            val rnd = Random(42)
            repeat(100) {
                val a = List(rnd.nextInt(0, 40)) { "l${rnd.nextInt(0, 8)}" }
                val b = List(rnd.nextInt(0, 40)) { "l${rnd.nextInt(0, 8)}" }
                val r = LineDiff.compute(a.joinToString("\n"), b.joinToString("\n")) as DiffResult.Hunks
                LineDiff.apply(a.joinToString("\n"), r) shouldBe b
            }
        }

        test("a huge old text against a tiny new one is bounded in memory (TooLarge, not an OutOfMemoryError)") {
            val old = (1..20_000).joinToString("\n") { "old line $it" }
            val new = (1..200).joinToString("\n") { "new line $it" }
            val bean =
                java.lang.management.ManagementFactory
                    .getThreadMXBean() as com.sun.management.ThreadMXBean
            val before = bean.getThreadAllocatedBytes(Thread.currentThread().id)
            LineDiff.compute(old, new).shouldBeInstanceOf<DiffResult.TooLarge>()
            val allocated = bean.getThreadAllocatedBytes(Thread.currentThread().id) - before
            // the trace is at most D*D/2 ints (D = maxEdits = 5000, about 50 MB); the full-array copies took ~800 MB
            (allocated < 150L * 1024 * 1024) shouldBe true
        }

        test("a many-edits diff over the trace window still reconstructs the new text") {
            val rnd = Random(7)
            val a = List(400) { "a${rnd.nextInt(0, 30)}" }
            val b = List(380) { "a${rnd.nextInt(0, 30)}" }
            val r = LineDiff.compute(a.joinToString("\n"), b.joinToString("\n")) as DiffResult.Hunks
            LineDiff.apply(a.joinToString("\n"), r) shouldBe b
        }

        test("a cancelled diff stops instead of running to the end") {
            var calls = 0
            val old = (1..2_000).joinToString("\n") { "x$it" }
            val new = (1..2_000).joinToString("\n") { "y$it" }
            val thrown =
                runCatching { LineDiff.compute(old, new, cancelled = { ++calls > 3 }) }.exceptionOrNull()
            thrown.shouldBeInstanceOf<java.util.concurrent.CancellationException>()
            (calls < 10) shouldBe true
        }
    })
