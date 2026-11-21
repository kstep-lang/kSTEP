package dev.kstep.desktop.ai.diff

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.random.Random

class HunkSelectionTest :
    FunSpec({
        fun ready(
            a: String,
            b: String,
        ) = LineDiff.plan(a, b).shouldBeInstanceOf<HunkPlan.Ready>()

        fun build(
            a: String,
            b: String,
            sel: Set<Int>,
            max: Int = 1 shl 20,
        ): SelectionBuild = HunkSelection.build(a, b, ready(a, b), sel, max)

        fun built(
            a: String,
            b: String,
            sel: Set<Int>,
        ) = (build(a, b, sel) as SelectionBuild.Built).text

        val base = (1..40).joinToString("\n") { "l$it" } + "\n"

        fun edit(vararg changes: Pair<Int, String>): String {
            val lines = base.trimEnd('\n').split('\n').toMutableList()
            for ((i, t) in changes) lines[i - 1] = t
            return lines.joinToString("\n") + "\n"
        }

        test("groups split exactly where the display collapses") {
            val far = ready(base, edit(5 to "X5", 20 to "X20", 35 to "X35"))
            far.hunks.size shouldBe 3
            far.hunks.map { it.oldStart } shouldBe listOf(4, 19, 34)
            far.hunks.map { it.oldEnd } shouldBe listOf(5, 20, 35)
            far.added shouldBe 3
            far.removed shouldBe 3
            // 6 unchanged lines between two changes stay in one group, 7 split
            ready(base, edit(5 to "X5", 12 to "X12")).hunks.size shouldBe 1
            ready(base, edit(5 to "X5", 13 to "X13")).hunks.size shouldBe 2
        }

        test("the display lines of all groups equal compute() without the collapsed markers") {
            val new = edit(2 to "X2", 20 to "X20", 21 to "Y21", 38 to "X38")
            val plan = ready(base, new)
            val flat = (LineDiff.compute(base, new) as DiffResult.Hunks).hunks.flatMap { it.lines }
            plan.hunks.flatMap { it.display.lines } shouldBe flat.filter { it !is DiffLine.Collapsed }
            plan.hunks.none { h -> h.display.lines.any { it is DiffLine.Collapsed } } shouldBe true
        }

        test("too large and identical texts") {
            LineDiff.plan("a", "b", maxEdits = 0).shouldBeInstanceOf<HunkPlan.TooLarge>()
            LineDiff.plan("a\nb\nc", "a", maxLines = 2).shouldBeInstanceOf<HunkPlan.TooLarge>()
            ready("a\nb\n", "a\nb\n").hunks.size shouldBe 0
            ready("a\nb", "a\nb\n").hunks.size shouldBe 0 // only the final newline differs
        }

        test("a cancelled plan throws") {
            shouldThrow<java.util.concurrent.CancellationException> {
                LineDiff.plan("a\nb", "x\ny", cancelled = { true })
            }
        }

        test("all chosen is the proposal byte for byte, none is the editor text") {
            val new = edit(5 to "X5", 20 to "X20")
            built(base, new, setOf(0, 1)) shouldBe new
            built(base, new, emptySet()) shouldBe base
            val crlf = "a\r\nb\r\nc"
            val crlfNew = "a\r\nB\r\nc"
            built(crlf, crlfNew, setOf(0)) shouldBe crlfNew
            built("a\nb", "a\nc", setOf(0)) shouldBe "a\nc"
        }

        test("one group at the start, middle and end; neighbours inside a group go together") {
            val new = edit(2 to "X2", 20 to "X20", 39 to "X39")
            built(base, new, setOf(0)) shouldBe edit(2 to "X2")
            built(base, new, setOf(1)) shouldBe edit(20 to "X20")
            built(base, new, setOf(2)) shouldBe edit(39 to "X39")
            built(base, new, setOf(0, 2)) shouldBe edit(2 to "X2", 39 to "X39")
            val near = edit(10 to "X10", 12 to "X12", 30 to "X30")
            ready(base, near).hunks.size shouldBe 2
            built(base, near, setOf(0)) shouldBe edit(10 to "X10", 12 to "X12")
        }

        test("insertions and deletions") {
            val a = (1..30).joinToString("\n") { "l$it" }
            val withInsert = a.replace("l3\n", "l3\nnew\n").replace("l28\n", "l28\nnew2\n")
            val plan = ready(a, withInsert)
            plan.hunks.size shouldBe 2
            built(a, withInsert, setOf(1)) shouldBe a.replace("l28\n", "l28\nnew2\n")
            val del = a.replace("l3\n", "").replace("l28\n", "")
            built(a, del, setOf(0)) shouldBe a.replace("l3\n", "")
        }

        test("unknown id and foreign texts are rejected") {
            val new = edit(5 to "X5")
            val plan = ready(base, new)
            shouldThrow<IllegalArgumentException> { HunkSelection.build(base, new, plan, setOf(7)) }
            shouldThrow<IllegalArgumentException> { HunkSelection.build(base + "x\n", new, plan, setOf(0)) }
            shouldThrow<IllegalArgumentException> { HunkSelection.build(base, new + "x\n", plan, setOf(0)) }
        }

        test("final newline: taken from the proposal only when a chosen group touches the end") {
            val a = (1..30).joinToString("\n") { "l$it" } // no final newline
            val b = a.replace("l2\n", "X2\n") + "\nend\n" // changed start, appended end, final newline
            val plan = ready(a, b)
            plan.hunks.size shouldBe 2
            built(a, b, setOf(0)) shouldBe a.replace("l2\n", "X2\n")
            built(a, b, setOf(1)) shouldBe a + "\nend\n"
            // editor with a final newline, proposal without
            val c = a + "\n"
            val d = a.replace("l2\n", "X2\n") + "\nend"
            built(c, d, setOf(0)) shouldBe c.replace("l2\n", "X2\n")
            built(c, d, setOf(1)) shouldBe c + "end"
        }

        test("an empty editor and a proposal that removes everything") {
            built("", "a\nb\n", setOf(0)) shouldBe "a\nb\n"
            built("a\nb\n", "", setOf(0)) shouldBe ""
            val a = (1..30).joinToString("\n") { "l$it" } + "\n"
            val b = a.replace("l1\n", "X1\n")
            built(a, b, emptySet()) shouldBe a
        }

        test("a result over the byte limit is TooLarge, a full selection is never checked against it") {
            val new = edit(5 to "X5", 30 to "X30")
            build(base, new, setOf(0), max = 10) shouldBe SelectionBuild.TooLarge
            build(base, new, setOf(0, 1), max = 10).shouldBeInstanceOf<SelectionBuild.Built>()
        }

        test("every subset of up to four groups: all is the proposal, none the editor, k groups give k changes") {
            val rnd = Random(7)
            repeat(25) {
                val n = rnd.nextInt(1, 5)
                val starts = (0 until n).map { 2 + it * 10 }
                val changes = starts.map { (it + 1) to "mod$it" }.toTypedArray()
                val new = edit(*changes)
                val plan = ready(base, new)
                plan.hunks.size shouldBe n
                for (mask in 0 until (1 shl n)) {
                    val sel = (0 until n).filter { mask and (1 shl it) != 0 }.toSet()
                    val text = built(base, new, sel)
                    if (sel.size == n) text shouldBe new
                    if (sel.isEmpty()) text shouldBe base
                    val again = LineDiff.plan(base, text) as HunkPlan.Ready
                    again.hunks.size shouldBe sel.size
                }
            }
        }
    })
