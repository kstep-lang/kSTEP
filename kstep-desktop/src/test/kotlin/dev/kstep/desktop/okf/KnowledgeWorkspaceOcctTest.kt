package dev.kstep.desktop.okf

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.FileChangeWatcher
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/**
 * A note with two shape blocks end to end with real geometry: note -> controller route -> block file -> worker -> GLB.
 * Named after OCCT on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it.
 */
class KnowledgeWorkspaceOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "the OCCT bridge is available when kstep-desktop's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        "a note with two shape blocks shows block 1, then block 2 after a caret move".config(
            enabled = available,
            timeout = 400.seconds,
        ) {
            val root = TestSupport.newDir()
            val session = BlockSessionFile.createSessionDir()
            val runner = WorkerRunner(session)
            lateinit var knowledge: KnowledgeWorkspaceController
            knowledge =
                KnowledgeWorkspaceController(
                    workerRun = { f, g, s -> runner.run(f, g, s) },
                    sessionDir = session,
                    scheduler = { _, task ->
                        task()
                        AutoCloseable { }
                    },
                )
            val live = BlockSessionFile.createSessionDir()
            val controller =
                LiveRunController(
                    runner = { f, g, s -> knowledge.runBlock(f, g, s) },
                    watcherFactory = { f, onEvent -> FileChangeWatcher(f, onEvent = onEvent) },
                    sessionDir = live,
                )
            knowledge.onRunRequested = controller::forceRun
            controller.runFilter = knowledge::runFilter
            try {
                val base =
                    Files
                        .readAllLines(TestSupport.fixture("export-colour-assembly-two-parts.kstep.kts"))
                        .filterNot { it.startsWith("//") }
                        .joinToString("\n")
                val block1 = base
                val block2 = base.replace("makeBox(10.0, 20.0, 30.0)", "makeBox(50.0, 40.0, 5.0)")
                val text = "# Two parts\n\n```kstep\n$block1\n```\n\ntext\n\n```kstep\n$block2\n```\n"
                val md = root.resolve("note.md").also { Files.writeString(it, text) }
                knowledge.openFolder(root)
                TestSupport.await(30_000) { knowledge.workspace.value is WorkspaceState.Open } shouldBe true
                knowledge.trustFolder()
                knowledge.onFileOpened(md)
                controller.open(md)
                val first = TestSupport.awaitState(controller) { it is DesktopState.Ok }
                val m1 = first.lastGood!!.mesh
                m1 shouldNotBe null
                knowledge.onBuffer(text, text.indexOf("makeBox(50.0"))
                val second =
                    TestSupport.awaitState(controller) {
                        it is DesktopState.Ok && it.generation > first.generation
                    }
                val triangles1 = first.lastGood!!.triangles
                (second.lastGood!!.mesh != null) shouldBe true
                // a different block gives a different model (other bounding box)
                (
                    second.lastGood!!
                        .mesh!!
                        .mesh.coordinates
                        .max() != m1!!.mesh.coordinates.max() ||
                        second.lastGood!!.triangles != triangles1
                ) shouldBe
                    true
            } finally {
                controller.close()
                knowledge.close()
                root.toFile().deleteRecursively()
            }
        }
    })
