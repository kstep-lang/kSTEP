package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.edit.LineEnding
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.PreviewOrigin
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.time.Instant

private val file: Path = Path.of("/tmp/a.kstep.kts")

private fun editing(
    text: String,
    base: String = text,
) = EditorView(EditorState.Editing(file, base, null, text, LineEnding.LF, false, false), 0, 0, 0)

private fun model(origin: PreviewOrigin = PreviewOrigin.SavedFile) =
    DesktopModel(
        null,
        12,
        1,
        Instant.EPOCH,
        5,
        emptyList(),
        parts = PartTable(emptyList(), IntArray(0)),
        origin = origin,
    )

class McpSnapshotMapperTest :
    StringSpec({
        "an editing script with a finished run" {
            val s = McpSnapshotMapper.of(editing("box()"), DesktopState.Ok(file, 1, model()), false, true)
            s.editorKind shouldBe EditorKind.SCRIPT
            s.editorText shouldBe "box()"
            s.filePath shouldBe file
            s.run shouldBe RunKind.OK
            s.triangles shouldBe 12
            s.parts shouldBe 0
            s.previewOrigin shouldBe PreviewKind.SAVED_FILE
            s.dirty shouldBe false
        }

        "unsaved text is dirty, and a buffer run says so" {
            val running = DesktopState.Running(file, 2, null, 0, PreviewOrigin.Buffer("x"))
            val s = McpSnapshotMapper.of(editing("changed", base = "orig"), running, false, true)
            s.dirty shouldBe true
            s.run shouldBe RunKind.RUNNING
            s.previewOrigin shouldBe PreviewKind.UNSAVED_BUFFER
            s.triangles shouldBe null
        }

        "a note in a workspace" {
            val s = McpSnapshotMapper.of(editing("# n"), DesktopState.Idle, true, false)
            s.editorKind shouldBe EditorKind.NOTE
            s.knowledgeWorkspace shouldBe true
        }

        "no file, an unavailable editor, waiting and unavailable geometry" {
            val none = McpSnapshotMapper.of(EditorView(EditorState.NoFile, 0, 0, 0), DesktopState.Idle, false, true)
            none.editorKind shouldBe EditorKind.NONE
            none.editorText shouldBe null
            none.filePath shouldBe null
            val unavailable =
                McpSnapshotMapper.of(
                    EditorView(EditorState.Unavailable(file, "KSTEP-D-1", "too large"), 0, 0, 0),
                    DesktopState.NativeUnavailable(file, 1, "no bridge", null),
                    false,
                    true,
                )
            unavailable.editorKind shouldBe EditorKind.NONE
            unavailable.filePath shouldBe file
            unavailable.run shouldBe RunKind.UNAVAILABLE
            val waiting =
                McpSnapshotMapper.of(
                    editing("x"),
                    DesktopState.Waiting(file, 1, "Pick a block", null),
                    true,
                    false,
                )
            waiting.run shouldBe RunKind.IDLE
            waiting.waitingHint shouldBe "Pick a block"
        }

        "toString never prints the text or the path" {
            val s = McpSnapshotMapper.of(editing("SECRET-TEXT"), DesktopState.Idle, false, true)
            s.toString().contains("SECRET-TEXT") shouldBe false
            s.toString().contains("a.kstep.kts") shouldBe false
        }

        "the run report of an error state carries the buffer text, a clean ok state has none" {
            val err =
                listOf(
                    dev.kstep.desktop.run
                        .DesktopError("KSTEP-S-001", 1, 1, "x"),
                )
            val failed =
                DesktopState.Error(file, 1, err, null, Instant.EPOCH, PreviewOrigin.Buffer("src"))
            val s = McpSnapshotMapper.of(editing("src"), failed, false, true)
            s.runReport!!.severity shouldBe dev.kstep.desktop.run.Severity.ERROR
            s.runReport!!.bufferText shouldBe "src"
            McpSnapshotMapper.of(editing("box()"), DesktopState.Ok(file, 1, model()), false, true).runReport shouldBe
                null
        }
    })
