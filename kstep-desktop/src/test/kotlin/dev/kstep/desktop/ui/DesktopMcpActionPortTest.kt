package dev.kstep.desktop.ui

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.mcp.AgentEditResult
import dev.kstep.desktop.mcp.EditorKind
import dev.kstep.desktop.mcp.McpRunGate
import dev.kstep.desktop.mcp.McpRunMode
import dev.kstep.desktop.mcp.McpShowTarget
import dev.kstep.desktop.menu.MenuContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The window side of the action tools (ADR-0097): the real session, fake run entries. */
class DesktopMcpActionPortTest :
    StringSpec({
        fun port(
            session: EditorSession,
            ctx: MenuContext,
            knowledge: Boolean = false,
            started: MutableList<McpRunMode> = mutableListOf(),
            shownSource: String? = null,
        ) = DesktopMcpActionPort(
            session = session,
            menuContext = { ctx },
            knowledgeOpen = { knowledge },
            runStarter = { started += it },
            generation = { 4L },
            runAwaiter = { _, _ -> null },
            selectionGet = {
                dev.kstep.desktop.pick
                    .SelectionState()
            },
            selectionSet = {},
            visibilityGet = {
                dev.kstep.desktop.pick
                    .VisibilityState()
            },
            visibilitySet = {},
            sceneNow = { null },
            partsNow = { null },
            displaySourceText = { shownSource },
            jumpTo = {},
            notice = {},
        )

        fun ctx(
            dirty: Boolean = false,
            script: Boolean = true,
            draft: Boolean = false,
        ) = MenuContext(
            scriptOpen = true,
            knowledgeOpen = false,
            exportEnabled = true,
            fileOpen = true,
            editorIsScript = script,
            editorDirty = dirty,
            draftOfferOpen = draft,
        )

        fun opened(content: String): EditorSession {
            val session = EditorSession()
            session.open(TestSupport.script(TestSupport.newDir(), "port", content))
            TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
            return session
        }

        "the editor is read fresh from the session" {
            val session = opened("box(1)")
            try {
                val p = port(session, ctx())
                p.editor().kind shouldBe EditorKind.SCRIPT
                p.editor().text shouldBe "box(1)"
                session.onTextChanged("box(2)", 6)
                p.editor().text shouldBe "box(2)"
                p.editor().dirty shouldBe true
            } finally {
                session.close()
            }
        }

        "no file and a note are told apart" {
            EditorSession().use { port(it, ctx()).editor().kind shouldBe EditorKind.NONE }
            val session = opened("# note")
            try {
                port(session, ctx(script = false)).editor().kind shouldBe EditorKind.NOTE
                port(session, ctx(), knowledge = true).editor().kind shouldBe EditorKind.NOTE
            } finally {
                session.close()
            }
        }

        "an agent edit goes through the session and is translated" {
            val session = opened("one")
            try {
                val p = port(session, ctx())
                p.applyAgentEdit("one", "two") shouldBe AgentEditResult.APPLIED
                p.applyAgentEdit("one", "three") shouldBe AgentEditResult.STALE
                p.applyAgentEdit("two", "two") shouldBe AgentEditResult.UNCHANGED
            } finally {
                session.close()
            }
        }

        "the run gate follows the menu rules" {
            val session = opened("one")
            try {
                port(session, ctx(dirty = true)).runGate(McpRunMode.UNSAVED) shouldBe McpRunGate.ALLOWED
                port(session, ctx(dirty = false)).runGate(McpRunMode.UNSAVED) shouldBe McpRunGate.UNAVAILABLE
                port(session, ctx(dirty = false)).runGate(McpRunMode.SAVED) shouldBe McpRunGate.ALLOWED
                port(session, ctx(script = false)).runGate(McpRunMode.SAVED) shouldBe McpRunGate.NOT_A_SCRIPT
                port(session, ctx(), knowledge = true).runGate(McpRunMode.SAVED) shouldBe McpRunGate.NOT_A_SCRIPT
            } finally {
                session.close()
            }
            EditorSession().use { port(it, ctx()).runGate(McpRunMode.SAVED) shouldBe McpRunGate.NO_FILE }
        }

        "a run goes through the starter the menu uses and reports the generation before" {
            val session = opened("one")
            try {
                val started = mutableListOf<McpRunMode>()
                val p = port(session, ctx(dirty = true), started = started)
                p.startRun(McpRunMode.UNSAVED) shouldBe 4L
                p.startRun(McpRunMode.SAVED)
                started shouldBe listOf(McpRunMode.UNSAVED, McpRunMode.SAVED)
            } finally {
                session.close()
            }
        }

        "show in script needs the shown source to equal the buffer" {
            val session = opened("box(1)")
            try {
                port(session, ctx(), shownSource = "other").showInScriptTarget("a") shouldBe McpShowTarget.NotFound
                port(session, ctx(), shownSource = null).showInScriptTarget("a") shouldBe McpShowTarget.NotFound
            } finally {
                session.close()
            }
            EditorSession().use { port(it, ctx()).showInScriptTarget("a") shouldBe McpShowTarget.NoFile }
            val note = opened("# n")
            try {
                port(note, ctx(script = false)).showInScriptTarget("a").shouldBeInstanceOf<McpShowTarget.NotAScript>()
            } finally {
                note.close()
            }
        }
    })
