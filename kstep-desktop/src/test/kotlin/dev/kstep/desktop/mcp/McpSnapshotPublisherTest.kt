package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.Visibility
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.PreviewOrigin
import dev.kstep.desktop.run.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant

private val file: Path = Path.of("/tmp/a.kstep.kts")

private fun editing(text: String = "box()") =
    EditorView(EditorState.Editing(file, text, null, text, LineEnding.LF, false, false), 0, 0, 0)

private fun model(
    gen: Long = 1,
    warnings: List<DesktopError> = emptyList(),
    origin: PreviewOrigin = PreviewOrigin.SavedFile,
) = DesktopModel(
    null,
    12,
    gen,
    Instant.EPOCH,
    5,
    emptyList(),
    parts = PartTable(listOf(partInfo("a", 0)), IntArray(12)),
    warnings = warnings,
    origin = origin,
)

private fun fixture(): Pair<SnapshotHost, McpSnapshotPublisher> {
    val host = SnapshotHost("t")
    return host to McpSnapshotPublisher(host)
}

class McpSnapshotPublisherTest :
    StringSpec({
        "the first input publishes revision 1" {
            val (host, p) = fixture()
            p.onEditorOrRun(editing(), DesktopState.Idle, false, true)
            host.snapshot.revision shouldBe 1
            p.revision shouldBe 1
        }

        "the same references publish nothing and keep the revision" {
            val (host, p) = fixture()
            val e = editing()
            val st = DesktopState.Ok(file, 1, model())
            p.onEditorOrRun(e, st, false, true)
            val first = host.snapshot
            p.onEditorOrRun(e, st, false, true)
            host.snapshot shouldBe first
            (host.snapshot === first) shouldBe true
            p.revision shouldBe 1
        }

        "a new run state moves the revision by one" {
            val (host, p) = fixture()
            val e = editing()
            p.onEditorOrRun(e, DesktopState.Idle, false, true)
            p.onEditorOrRun(e, DesktopState.Ok(file, 1, model()), false, true)
            host.snapshot.revision shouldBe 2
            host.snapshot.run shouldBe RunKind.OK
        }

        "a hover changes nothing, a selection or a visibility does" {
            val (host, p) = fixture()
            p.onEditorOrRun(editing(), DesktopState.Idle, false, true)
            val vis = VisibilityState()
            p.onView(McpViewInput.of(SelectionState(), vis, false, false))
            p.revision shouldBe 1
            p.onView(McpViewInput.of(SelectionState(hovered = 3, treeHover = 1..2), vis, false, false))
            p.revision shouldBe 1
            p.onView(McpViewInput.of(SelectionState(selected = "a"), vis, false, false))
            p.revision shouldBe 2
            host.snapshot.selection?.selected shouldBe "a"
            p.onView(McpViewInput.of(SelectionState(selected = "a", hovered = 5), vis, false, false))
            p.revision shouldBe 2
            p.onView(
                McpViewInput.of(
                    SelectionState(selected = "a"),
                    VisibilityState(mapOf("k" to Visibility.Hidden)),
                    false,
                    false,
                ),
            )
            p.revision shouldBe 3
            p.onView(
                McpViewInput.of(
                    SelectionState(selected = "a"),
                    VisibilityState(mapOf("k" to Visibility.Hidden)),
                    true,
                    false,
                ),
            )
            p.revision shouldBe 4
            host.snapshot.viewSettings.softWrap shouldBe true
        }

        "a view before any editor input is remembered, not published" {
            val (host, p) = fixture()
            p.onView(McpViewInput.of(SelectionState(selected = "a"), VisibilityState(), false, false))
            p.revision shouldBe 0
            host.snapshot.revision shouldBe 0
            p.onEditorOrRun(editing(), DesktopState.Idle, false, true)
            host.snapshot.selection?.selected shouldBe "a"
        }

        "the scene is the same instance for the same model and new for a new one" {
            val (host, p) = fixture()
            val e = editing()
            val m = model()
            p.onEditorOrRun(e, DesktopState.Ok(file, 1, m), false, true)
            val first = host.snapshot.scene
            (first != null) shouldBe true
            p.onView(McpViewInput.of(SelectionState(selected = "a"), VisibilityState(), false, false))
            (host.snapshot.scene === first) shouldBe true
            p.onEditorOrRun(e, DesktopState.Ok(file, 2, m), false, true)
            (host.snapshot.scene === first) shouldBe true
            p.onEditorOrRun(e, DesktopState.Ok(file, 3, model(gen = 3)), false, true)
            (host.snapshot.scene === first) shouldBe false
            host.snapshot.scene!!.generation shouldBe 3
        }

        "no scene while the native part is unavailable" {
            val (host, p) = fixture()
            p.onEditorOrRun(editing(), DesktopState.NativeUnavailable(file, 1, "no", model()), false, true)
            host.snapshot.scene shouldBe null
            host.snapshot.run shouldBe RunKind.UNAVAILABLE
        }

        "run reports come from errors and from warnings, and a clean run has none" {
            val (host, p) = fixture()
            val e = editing()
            val err = listOf(DesktopError("KSTEP-S-001", 1, 1, "x"))
            p.onEditorOrRun(
                e,
                DesktopState.Error(file, 1, err, null, Instant.EPOCH, PreviewOrigin.Buffer("src")),
                false,
                true,
            )
            host.snapshot.runReport!!.severity shouldBe Severity.ERROR
            host.snapshot.runReport!!.bufferText shouldBe "src"
            host.snapshot.runReport!!.origin shouldBe PreviewKind.UNSAVED_BUFFER
            val warn = listOf(DesktopError("KSTEP-D-012", null, null, "w", Severity.WARNING))
            p.onEditorOrRun(e, DesktopState.Ok(file, 2, model(warnings = warn)), false, true)
            host.snapshot.runReport!!.severity shouldBe Severity.WARNING
            host.snapshot.runReport!!.bufferText shouldBe null
            p.onEditorOrRun(e, DesktopState.Ok(file, 3, model()), false, true)
            host.snapshot.runReport shouldBe null
        }
    })
