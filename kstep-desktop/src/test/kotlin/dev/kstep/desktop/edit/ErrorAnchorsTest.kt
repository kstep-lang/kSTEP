package dev.kstep.desktop.edit

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ErrorAnchorsTest :
    StringSpec({
        fun err(
            line: Int?,
            column: Int?,
            severity: Severity = Severity.ERROR,
            code: String = "KSTEP-S-001",
        ) = DesktopError(code, line, column, "m", severity)

        fun build(
            text: String,
            vararg errors: DesktopError,
        ) = ErrorAnchors.build(errors.toList(), text, KotlinTokenizer.tokenize(text))

        fun spans(a: ErrorAnchors) = a.anchors.map { Triple(it.start, it.end, it.touched) }

        // line 1 "val alpha = 1", line 2 "  foo(bar)   ", line 3 "" (blank), line 4 "x"
        val text = "val alpha = 1\n  foo(bar)   \n\nx"

        "an error with a column covers the identifier there" {
            val a = build(text, err(1, 5))
            spans(a) shouldBe listOf(Triple(4, 9, false))
        }

        "an error with a column inside a keyword or string covers the token" {
            val a = build("val s = \"hello\"", err(1, 1), err(1, 10))
            spans(a) shouldBe listOf(Triple(0, 3, false), Triple(9, 15, false))
        }

        "an error without a column covers the line from the first non-blank to the end without trailing blanks" {
            spans(build(text, err(2, null))) shouldBe listOf(Triple(16, 24, false))
        }

        "an error on a blank line gets an empty span" {
            spans(build(text, err(3, null))) shouldBe listOf(Triple(28, 28, false))
        }

        "a column behind the line end is clamped, an error without a line or beyond the last line has no anchor" {
            spans(build(text, err(4, 50))) shouldBe listOf(Triple(30, 30, false))
            build(text, err(null, null), err(9, 1)).anchors shouldBe emptyList()
        }

        "a text above the limit gives no anchors" {
            val big = "a".repeat(ErrorAnchors.MAX_TEXT_CHARS + 1)
            ErrorAnchors.build(listOf(err(1, 1)), big, emptyList()) shouldBe ErrorAnchors.EMPTY
        }

        "an edit before the mark moves it, an insertion exactly at its start moves it too" {
            val a = build("abc def", err(1, 5)) // def = 4..7
            spans(a.applyDiff("abc def", "abcXX def")) shouldBe listOf(Triple(6, 9, false))
            spans(a.applyDiff("abc def", "abc Xdef")) shouldBe listOf(Triple(5, 8, false))
            spans(a.applyDiff("abc def", "bc def")) shouldBe listOf(Triple(3, 6, false))
        }

        "an edit behind the mark or exactly at its end leaves it alone" {
            val a = build("abc def", err(1, 1)) // abc = 0..3
            spans(a.applyDiff("abc def", "abc deXf")) shouldBe listOf(Triple(0, 3, false))
            spans(a.applyDiff("abc def", "abcX def")) shouldBe listOf(Triple(0, 3, false))
        }

        "an edit inside the mark makes it touched and keeps its place" {
            val a = build("abcdef", err(1, 1)) // abcdef = 0..6
            spans(a.applyDiff("abcdef", "abcXXdef")) shouldBe listOf(Triple(0, 8, true))
            spans(a.applyDiff("abcdef", "abef")) shouldBe listOf(Triple(0, 4, true))
        }

        "an edit overlapping the start or the end of the mark" {
            val a = build("xx abcdef yy", err(1, 4)) // abcdef = 3..9
            // replaces "xx abc" (0..6) with "Q": mark keeps the tail "def"
            spans(a.applyDiff("xx abcdef yy", "Qdef yy")) shouldBe listOf(Triple(0, 4, true))
            // replaces "def yy" (6..12) with "Z"
            spans(a.applyDiff("xx abcdef yy", "xx abcZ")) shouldBe listOf(Triple(3, 7, true))
        }

        "an edit that swallows the mark removes it" {
            val a = build("xx abc yy", err(1, 4))
            a.applyDiff("xx abc yy", "xx  yy").anchors shouldBe emptyList()
            a.applyDiff("xx abc yy", "xx Q yy").anchors shouldBe emptyList() // replaced text
            a.applyDiff("xx abc yy", "").anchors shouldBe emptyList()
        }

        "a vanished mark does not come back with undo" {
            val a = build("xx abc yy", err(1, 4))
            val gone = a.applyDiff("xx abc yy", "xx  yy")
            gone.applyDiff("xx  yy", "xx abc yy").anchors shouldBe emptyList()
        }

        "a touched mark stays touched after the text is restored" {
            val a = build("abcdef", err(1, 1))
            val typed = a.applyDiff("abcdef", "abcXdef")
            val undone = typed.applyDiff("abcXdef", "abcdef")
            spans(undone).single().third shouldBe true
        }

        "an empty mark moves with an insertion at its place and dies only when strictly covered" {
            val a = build("a\n\nb", err(2, null)) // empty at 2
            spans(a) shouldBe listOf(Triple(2, 2, false))
            spans(a.applyDiff("a\n\nb", "a\nQ\nb")) shouldBe listOf(Triple(3, 3, false))
            // a newline inserted behind the mark (the diff puts it after the common prefix) leaves it where it is
            spans(a.applyDiff("a\n\nb", "a\n\n\nb")) shouldBe listOf(Triple(2, 2, false))
            // text deleted from exactly the mark's place on does not swallow it
            spans(a.applyDiff("a\n\nb", "a\n")) shouldBe listOf(Triple(2, 2, false))
            // deleted text around it does
            a.applyDiff("a\n\nb", "ab").anchors shouldBe emptyList()
            // text deleted in front of it moves it
            spans(a.applyDiff("a\n\nb", "\nb")) shouldBe listOf(Triple(0, 0, false))
        }

        "a replacement over several lines" {
            val src = "one\ntwo\nthree\nfour"
            val a = build(src, err(1, 1), err(3, 1), err(4, 1))
            val edited = a.applyDiff(src, "one\nQ\nfour")
            // "two\nthree" (4..13) replaced by "Q": the mark of line 3 is swallowed
            edited.anchors.map { it.error.line } shouldBe listOf(1, 4)
            edited.anchors.map { it.start } shouldBe listOf(0, 6)
        }

        "a mark arrives for a buffer that already differs: replay the difference" {
            val saved = "abc def"
            val a = build(saved, err(1, 5)).applyDiff(saved, "ZZ abc def")
            spans(a) shouldBe listOf(Triple(7, 10, false))
        }

        "a new build replaces all anchors" {
            val a = build("abc", err(1, 1))
            val b = build("abc", err(1, 2), err(1, 3))
            (b.anchors.size) shouldBe 2
            (a.anchors.size) shouldBe 1
        }

        "a text above the limit empties the anchors" {
            val a = build("abc", err(1, 1))
            val huge = ErrorAnchors.MAX_TEXT_CHARS + 1
            a.applyEdit(TextEdit(0, 0, 1), huge) shouldBe ErrorAnchors.EMPTY
        }

        "line marks: worst severity, hollow only when all marks of the line are touched" {
            val src = "abc def\nxyz"
            val a = build(src, err(1, 1, Severity.WARNING), err(1, 5, Severity.ERROR), err(2, 1, Severity.WARNING))
            val marks = a.lineMarks(src)
            marks.severities shouldBe mapOf(1 to Severity.ERROR, 2 to Severity.WARNING)
            marks.hollow shouldBe emptySet()
            val edited = a.applyDiff(src, "abXc def\nxyz") // touches the first mark only
            edited.lineMarks("abXc def\nxyz").hollow shouldBe emptySet()
            val both = edited.applyDiff("abXc def\nxyz", "abXc dXef\nxyz")
            both.lineMarks("abXc dXef\nxyz").hollow shouldBe setOf(1)
        }

        "line marks follow an inserted line" {
            val src = "a\nb"
            val a = build(src, err(2, 1))
            val edited = a.applyDiff(src, "new\na\nb")
            edited.lineMarks("new\na\nb").severities shouldBe mapOf(3 to Severity.ERROR)
            edited.lineMarks("new\na\nb").tooltips.keys shouldBe setOf(3)
        }
    })
