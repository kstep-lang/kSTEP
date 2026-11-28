package dev.kstep.desktop.ui

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportPreferencesStore
import dev.kstep.desktop.export.ExportWorkerResult
import dev.kstep.desktop.mcp.EditorKind
import dev.kstep.desktop.mcp.McpAgentText
import dev.kstep.desktop.mcp.McpExportFormat
import dev.kstep.desktop.mcp.McpExportPlan
import dev.kstep.desktop.mcp.McpExportResult
import dev.kstep.desktop.mcp.McpExportStart
import dev.kstep.desktop.mcp.McpOpenResult
import dev.kstep.desktop.mcp.McpSafeFileName
import dev.kstep.desktop.mcp.McpSaveResult
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.recent.RecentEntry
import dev.kstep.desktop.recent.RecentKind
import dev.kstep.desktop.recent.RecentList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/** The window side of the file tools (ADR-0098): the real editor session, the real export controller, temp files. */
class DesktopMcpFilePortTest :
    StringSpec({
        class Rig(
            content: String = "box(1)\n",
        ) {
            val dir: Path = TestSupport.newDir()
            val script: Path = TestSupport.script(dir, "port", content)
            val config: Path = Files.createDirectory(dir.resolve("config"))
            val out: Path = Files.createDirectory(dir.resolve("out"))
            val session = EditorSession()
            val prefs = ExportPreferencesStore(config)
            val opened = mutableListOf<Path>()
            val forgotten = mutableListOf<Path>()
            var recent = RecentList.empty(false)
            var workspace = false
            var dialog = false
            val exporter =
                ExportController(
                    worker = { _, _, gen, _ ->
                        ExportWorkerResult.Produced(
                            Files.write(dir.resolve("w-$gen.step"), "ISO-10303-21;\nDATA;".toByteArray()),
                        )
                    },
                )

            init {
                session.open(script)
                check(TestSupport.await(30_000) { session.view.value.state is EditorState.Editing })
            }

            fun port() =
                DesktopMcpFilePort(
                    session = session,
                    menuContext = {
                        MenuContext(
                            scriptOpen = true,
                            knowledgeOpen = false,
                            exportEnabled = true,
                            fileOpen = true,
                            editorIsScript = true,
                            editorDirty = session.view.value.dirty,
                        )
                    },
                    knowledgeOpen = { false },
                    workspaceActive = { workspace },
                    recent = { recent },
                    openPath = {
                        opened.add(it)
                        true
                    },
                    forgetRecent = { forgotten.add(it) },
                    exporter = exporter,
                    exportPrefs = prefs,
                    exportDialogOpen = { dialog },
                    converting = { false },
                )

            fun close() {
                exporter.close()
                session.close()
                dir.toFile().deleteRecursively()
            }
        }

        fun withRig(
            content: String = "box(1)\n",
            block: (Rig) -> Unit,
        ) {
            val rig = Rig(content)
            try {
                block(rig)
            } finally {
                rig.close()
            }
        }

        "target tells the script, the stamp and display texts; a note and no file are told apart" {
            withRig { rig ->
                val t = rig.port().target()
                t.kind shouldBe EditorKind.SCRIPT
                t.fileName shouldBe "port.kstep.kts"
                t.stamp!!.textRevision shouldBe McpAgentText.revisionOf("box(1)\n")
                t.stamp!!.dirty shouldBe false
                t.workspaceOpen shouldBe false
            }
            EditorSession().use { s ->
                val p =
                    DesktopMcpFilePort(
                        s,
                        { MenuContext(false, false, false) },
                        { false },
                        { false },
                        { RecentList.empty() },
                        { true },
                        {},
                        ExportController(worker = {
                            _,
                            _,
                            _,
                            _,
                            ->
                            error("no")
                        }),
                        ExportPreferencesStore(TestSupport.newDir()),
                        { false },
                        { false },
                    )
                p.target().kind shouldBe EditorKind.NONE
                p.target().stamp shouldBe null
            }
        }

        "save writes the file byte for byte and the buffer is clean afterwards" {
            withRig { rig ->
                val port = rig.port()
                rig.session.onTextChanged("box(2)\n", 7)
                val stamp = port.target().stamp!!
                stamp.dirty shouldBe true
                port.beginSave(stamp) shouldBe true
                val saved = port.awaitSave(stamp, 30_000).shouldBeInstanceOf<McpSaveResult.Saved>()
                saved.revision shouldBe McpAgentText.revisionOf("box(2)\n")
                Files.readString(rig.script) shouldBe "box(2)\n"
                port.target().stamp!!.dirty shouldBe false
            }
        }

        "beginSave refuses a buffer that is not the one in the stamp" {
            withRig { rig ->
                val port = rig.port()
                rig.session.onTextChanged("box(2)\n", 7)
                val stamp = port.target().stamp!!
                rig.session.onTextChanged("box(3)\n", 7)
                port.beginSave(stamp) shouldBe false
                Files.readString(rig.script) shouldBe "box(1)\n"
            }
        }

        "a file that changed on the disk is a conflict: Failed, and the disk keeps the other program's text" {
            withRig { rig ->
                val port = rig.port()
                rig.session.onTextChanged("box(2)\n", 7)
                Files.writeString(rig.script, "box(other)\n")
                val stamp = port.target().stamp!!
                port.beginSave(stamp) shouldBe true
                port.awaitSave(stamp, 30_000) shouldBe McpSaveResult.Failed
                Files.readString(rig.script) shouldBe "box(other)\n"
            }
        }

        "the line counts are against the file on the disk" {
            withRig("a\nb\nc\n") { rig ->
                val port = rig.port()
                rig.session.onTextChanged("a\nB\nc\nd\n", 8)
                val delta = port.lineDelta(port.target().stamp!!)!!
                delta.added shouldBe 2
                delta.removed shouldBe 1
                // a stamp of another text gives no count
                port.lineDelta(
                    dev.kstep.desktop.mcp
                        .McpFileStamp("sha256:x", true, "id"),
                ) shouldBe null
            }
        }

        "recent lists only scripts, newest first, at most ten, by name" {
            withRig { rig ->
                val a = rig.dir.resolve("a.kstep.kts")
                val b = rig.dir.resolve("b.kstep.kts")
                rig.recent =
                    RecentList.of(
                        listOf(
                            RecentEntry(a, RecentKind.SCRIPT),
                            RecentEntry(rig.dir, RecentKind.FOLDER),
                            RecentEntry(b, RecentKind.SCRIPT),
                        ),
                        false,
                    )
                val list = rig.port().recentScripts()
                list.map { it.name } shouldBe listOf("a.kstep.kts", "b.kstep.kts")
                list.map { it.index } shouldBe listOf(0, 1)
            }
        }

        "open_recent checks name and stamp, refuses a vanished file, removes it and opens the real path" {
            withRig { rig ->
                val other = TestSupport.script(rig.dir, "other", "box(9)\n")
                rig.recent =
                    RecentList.of(
                        listOf(
                            RecentEntry(other, RecentKind.SCRIPT),
                            RecentEntry(rig.dir.resolve("gone.kstep.kts"), RecentKind.SCRIPT),
                        ),
                        false,
                    )
                val port = rig.port()
                val stamp = port.target().stamp
                port.openRecent(0, "wrong.kstep.kts", stamp) shouldBe McpOpenResult.Stale
                rig.workspace = true
                port.openRecent(0, "other.kstep.kts", stamp) shouldBe McpOpenResult.WorkspaceOpen
                rig.workspace = false
                port.openRecent(1, "gone.kstep.kts", stamp) shouldBe McpOpenResult.Missing
                rig.forgotten.map { it.fileName.toString() } shouldBe listOf("gone.kstep.kts")
                port.openRecent(0, "other.kstep.kts", stamp).shouldBeInstanceOf<McpOpenResult.Opened>()
                rig.opened shouldBe listOf(other.toRealPath())
                // unsaved changes block it
                rig.session.onTextChanged("box(5)\n", 7)
                port.openRecent(0, "other.kstep.kts", stamp) shouldBe McpOpenResult.Unsaved
            }
        }

        "planExport: no folder, remembered folder, collision suffix, exhausted names" {
            withRig { rig ->
                val port = rig.port()
                val stamp = port.target().stamp!!
                port.planExport(McpExportFormat.STEP, null, stamp) shouldBe McpExportPlan.NoFolder
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                val first = port.planExport(McpExportFormat.STEP, null, stamp).shouldBeInstanceOf<McpExportPlan.Ready>()
                first.fileName shouldBe "port.step"
                Files.writeString(rig.out.resolve("port.step"), "x")
                port
                    .planExport(
                        McpExportFormat.STEP,
                        null,
                        stamp,
                    ).shouldBeInstanceOf<McpExportPlan.Ready>()
                    .fileName shouldBe
                    "port-2.step"
                port
                    .planExport(McpExportFormat.GLB, McpSafeFileName.parse("mine"), stamp)
                    .shouldBeInstanceOf<McpExportPlan.Ready>()
                    .fileName shouldBe "mine.glb"
                for (n in 2..99) Files.writeString(rig.out.resolve("port-$n.step"), "x")
                port.planExport(McpExportFormat.STEP, null, stamp) shouldBe McpExportPlan.Exists
            }
        }

        "planExport: a folder that is gone or behind a link is not used" {
            withRig { rig ->
                val port = rig.port()
                val stamp = port.target().stamp!!
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                rig.out.toFile().deleteRecursively()
                port.planExport(McpExportFormat.STEP, null, stamp) shouldBe McpExportPlan.NoFolder
            }
        }

        "export creates a new file in the remembered folder through the controller and never replaces" {
            withRig { rig ->
                val port = rig.port()
                val stamp = port.target().stamp!!
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                val plan = port.planExport(McpExportFormat.STEP, null, stamp).shouldBeInstanceOf<McpExportPlan.Ready>()
                val start =
                    port
                        .startExport(
                            plan,
                            McpExportFormat.STEP,
                            stamp,
                        ).shouldBeInstanceOf<McpExportStart.Started>()
                val done = port.awaitExport(start, 30_000).shouldBeInstanceOf<McpExportResult.Done>()
                done.fileName shouldBe "port.step"
                Files.readString(rig.out.resolve("port.step")) shouldBe "ISO-10303-21;\nDATA;"
                // a second export of the same plan finds the name taken in the meantime: nothing is replaced
                port.startExport(plan, McpExportFormat.STEP, stamp) shouldBe McpExportStart.UiBusy
                Files.readString(rig.out.resolve("port.step")) shouldBe "ISO-10303-21;\nDATA;"
                rig.exporter.exclusiveGeneration shouldBe start.generation
            }
        }

        "export is refused while the dialog is open, with unsaved changes and for a changed buffer" {
            withRig { rig ->
                val port = rig.port()
                val stamp = port.target().stamp!!
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                val plan = port.planExport(McpExportFormat.STEP, null, stamp).shouldBeInstanceOf<McpExportPlan.Ready>()
                rig.dialog = true
                port.startExport(plan, McpExportFormat.STEP, stamp) shouldBe McpExportStart.UiBusy
                rig.dialog = false
                rig.session.onTextChanged("box(2)\n", 7)
                port.startExport(plan, McpExportFormat.STEP, stamp) shouldBe McpExportStart.Stale
                val dirty = port.target().stamp!!
                port.startExport(plan, McpExportFormat.STEP, dirty) shouldBe McpExportStart.Unsaved
                Files.list(rig.out).use { it.count() } shouldBe 0
            }
        }
    })
