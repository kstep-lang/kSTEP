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

        "requestSelection selects a range, bumps the revision and adds no history (ADR-0091)".config(
            timeout = 60.seconds,
        ) {
            val file = TestSupport.script(TestSupport.newDir(), "sel1", "one two three")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                val before = session.view.value
                session.requestSelection(4, 7, before.revision) shouldBe true
                val v = session.view.value
                v.selectionStart shouldBe 4
                v.caret shouldBe 7
                v.revision shouldBe before.revision + 1
                v.canUndo shouldBe before.canUndo
                v.canRedo shouldBe before.canRedo
                text(session) shouldBe "one two three"
                // the next revision drops the selection
                session.requestCaret(2)
                session.view.value.selectionStart shouldBe 2
                session.view.value.caret shouldBe 2
            } finally {
                session.close()
            }
        }

        "requestSelection refuses bad ranges and stale revisions and leaves the view alone".config(
            timeout = 60.seconds,
        ) {
            val file = TestSupport.script(TestSupport.newDir(), "sel2", "abc")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                val v = session.view.value
                session.requestSelection(2, 1, v.revision) shouldBe false
                session.requestSelection(0, 4, v.revision) shouldBe false
                session.requestSelection(-1, 1, v.revision) shouldBe false
                session.requestSelection(0, 1, v.revision + 5) shouldBe false
                session.view.value.revision shouldBe v.revision
                session.view.value.caret shouldBe v.caret
                session.requestSelection(0, 3, v.revision) shouldBe true
                // the revision moved on: the same expectation is stale now
                session.requestSelection(0, 3, v.revision) shouldBe false
                // undo works as without a selection (nothing to undo here)
                session.undo()
                text(session) shouldBe "abc"
            } finally {
                session.close()
            }
        }

        "requestSelection is refused without an open file" {
            val session = EditorSession()
            try {
                session.requestSelection(0, 0, session.view.value.revision) shouldBe false
            } finally {
                session.close()
            }
        }
    })
