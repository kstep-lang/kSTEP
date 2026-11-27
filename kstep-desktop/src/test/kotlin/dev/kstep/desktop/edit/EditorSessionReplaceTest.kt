package dev.kstep.desktop.edit

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/** Replace all is one undo step (ADR-0067): the history keeps full snapshots, so no history change is needed. */
class EditorSessionReplaceTest :
    StringSpec({
        fun text(s: EditorSession) = s.view.value.text

        "replace all is one undo step, redo restores it, typing stays a separate step".config(timeout = 60.seconds) {
            val file = TestSupport.script(TestSupport.newDir(), "r", "a b a c a")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                session.onTextChanged("a b a c a!", 10)
                session.view.value.dirty shouldBe true

                val ok =
                    TextReplace
                        .replaceAllChecked(text(session), "a", true, "zz")
                        .shouldBeInstanceOf<TextReplace.Outcome.Ok>()
                session.replaceText(ok.text, ok.caret)
                text(session) shouldBe "zz b zz c zz!"

                session.undo()
                text(session) shouldBe "a b a c a!"
                session.redo()
                text(session) shouldBe "zz b zz c zz!"
                session.undo()
                session.undo()
                text(session) shouldBe "a b a c a"
            } finally {
                session.close()
            }
        }

        "a single replace is one step and makes the buffer dirty".config(timeout = 60.seconds) {
            val file = TestSupport.script(TestSupport.newDir(), "r2", "one two")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                session.view.value.dirty shouldBe false
                val (t, caret) = TextReplace.replaceAt(text(session), 4, 7, "2")
                session.replaceText(t, caret)
                text(session) shouldBe "one 2"
                session.view.value.dirty shouldBe true
                session.undo()
                text(session) shouldBe "one two"
            } finally {
                session.close()
            }
        }

        "applyProposalIf applies only to the expected text, as one undo step".config(timeout = 60.seconds) {
            val file = TestSupport.script(TestSupport.newDir(), "r3", "one two")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                session.applyProposalIf("one two", "one 2") shouldBe true
                text(session) shouldBe "one 2"
                session.view.value.dirty shouldBe true
                // the editor moved on: a stale expectation changes nothing
                session.applyProposalIf("one two", "zzz") shouldBe false
                text(session) shouldBe "one 2"
                session.undo()
                text(session) shouldBe "one two"
            } finally {
                session.close()
            }
        }
    })
