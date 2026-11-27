package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** An agent edit (ADR-0097): one undo step of its own, never saved, the caret and the error marks move with it. */
class EditorSessionAgentEditTest :
    StringSpec({
        fun text(s: EditorSession) = s.view.value.text

        fun opened(
            content: String,
            saves: MutableList<String>? = null,
        ): Pair<EditorSession, java.nio.file.Path> {
            val file = TestSupport.script(TestSupport.newDir(), "agent", content)
            val session =
                if (saves == null) {
                    EditorSession()
                } else {
                    EditorSession(saver = { _, _, _, _ ->
                        saves += "save"
                        error("must not save")
                    })
                }
            session.open(file)
            TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
            return session to file
        }

        "an agent edit makes the buffer dirty, never saves and leaves the file as it was".config(timeout = 60.seconds) {
            val saves = mutableListOf<String>()
            val (session, file) = opened("one two", saves)
            try {
                val before = Files.readAllBytes(file)
                session.applyAgentEdit("one two", "one 2") shouldBe AgentEditOutcome.APPLIED
                text(session) shouldBe "one 2"
                session.view.value.dirty shouldBe true
                session.writeDraftNow()
                saves shouldBe emptyList()
                Files.readAllBytes(file).contentEquals(before) shouldBe true
            } finally {
                session.close()
            }
        }

        "undo restores the text before, redo and undo again stay consistent".config(timeout = 60.seconds) {
            val (session, _) = opened("one two")
            try {
                session.applyAgentEdit("one two", "one 2") shouldBe AgentEditOutcome.APPLIED
                session.undo()
                text(session) shouldBe "one two"
                session.redo()
                text(session) shouldBe "one 2"
                session.undo()
                text(session) shouldBe "one two"
            } finally {
                session.close()
            }
        }

        "typing, then an agent edit, then undo removes only the agent edit; a second undo the typing".config(
            timeout = 60.seconds,
        ) {
            val (session, _) = opened("one two")
            try {
                session.onTextChanged("one two!", 8)
                session.applyAgentEdit("one two!", "ONE two!") shouldBe AgentEditOutcome.APPLIED
                session.undo()
                text(session) shouldBe "one two!"
                session.undo()
                text(session) shouldBe "one two"
            } finally {
                session.close()
            }
        }

        "typing after an agent edit does not merge with it".config(timeout = 60.seconds) {
            val (session, _) = opened("one two")
            try {
                session.applyAgentEdit("one two", "one two!") shouldBe AgentEditOutcome.APPLIED
                session.onTextChanged("one two!x", 9)
                session.undo()
                text(session) shouldBe "one two!"
                session.undo()
                text(session) shouldBe "one two"
            } finally {
                session.close()
            }
        }

        "a stale expectation, an unchanged text and no file change nothing".config(timeout = 60.seconds) {
            val (session, _) = opened("one two")
            try {
                session.applyAgentEdit("other", "x") shouldBe AgentEditOutcome.STALE
                session.applyAgentEdit("one two", "one two") shouldBe AgentEditOutcome.UNCHANGED
                text(session) shouldBe "one two"
                session.view.value.dirty shouldBe false
            } finally {
                session.close()
            }
            EditorSession().use {
                it.applyAgentEdit("a", "b") shouldBe AgentEditOutcome.NOT_EDITING
            }
        }

        "the caret follows the change: before stays, after shifts, inside goes to the end".config(
            timeout = 60.seconds,
        ) {
            val (session, _) = opened("abc def ghi")
            try {
                session.onCaretMoved(2)
                session.applyAgentEdit("abc def ghi", "abc DEFGH ghi")
                session.view.value.caret shouldBe 2
                session.undo()
                session.onCaretMoved(11)
                session.applyAgentEdit("abc def ghi", "abc DEFGH ghi")
                session.view.value.caret shouldBe 13
                session.undo()
                session.onCaretMoved(5)
                session.applyAgentEdit("abc def ghi", "abc DEFGH ghi")
                session.view.value.caret shouldBe 9
            } finally {
                session.close()
            }
        }

        "error marks move with an agent edit above the error line" {
            val errors = listOf(DesktopError("KSTEP-S-001", 2, 1, "m"))
            val saved = "a\nbcd\ne"
            val tracker = ErrorAnchorTracker()
            tracker
                .update(errors, saved, saved)
                .anchors
                .single()
                .start shouldBe 2
            val edited = "NEW LINE\n$saved"
            tracker
                .update(errors, saved, edited)
                .anchors
                .single()
                .start shouldBe 2 + "NEW LINE\n".length
        }
    })
