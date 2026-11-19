package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** The names a script suggests (ADR-0063): the pure state rules and the controller that never leaves the chosen folder. */
class ExportScriptTargetsTest :
    StringSpec({
        val a = ScriptExportTarget(ExportFormat.STEP, "bracket.step")
        val b = ScriptExportTarget(ExportFormat.GLB, "bracket-view.glb")
        val c = ScriptExportTarget(ExportFormat.STL, "bracket.stl")
        val folder: Path = Path.of("/tmp/chosen")

        fun reduce(
            state: ExportState,
            event: ExportEvent,
        ) = ExportStateMachine.reduce(state, event).state as ExportState.Configuring

        fun configuring(
            targets: List<ScriptExportTarget>,
            selected: Int? = if (targets.isEmpty()) null else 0,
        ) = ExportStateMachine.initial(
            targets.firstOrNull()?.format ?: ExportFormat.STEP,
            folder.resolve(targets.firstOrNull()?.fileName ?: "x.step"),
            scriptTargets = targets,
            selectedScriptTarget = selected,
        )

        "without a block the last format wins and the name comes from the script file" {
            val s = ExportStateMachine.suggestion("my part.kstep.kts", emptyList(), ExportFormat.GLB)
            s shouldBe ExportStateMachine.Suggestion(ExportFormat.GLB, null, "my part.glb")
            ExportStateMachine.suggestion("p.kstep.kts", emptyList(), null).format shouldBe ExportFormat.DEFAULT
        }

        "with a block the first declared target wins over the last format" {
            ExportStateMachine.suggestion("p.kstep.kts", listOf(b, a), ExportFormat.STL) shouldBe
                ExportStateMachine.Suggestion(ExportFormat.GLB, 0, "bracket-view.glb")
        }

        "a click selects the target, sets format and name, drops an old overwrite check, keeps the folder" {
            val start = configuring(listOf(a, b, c))
            val after = reduce(start.copy(overwrite = Overwrite.Confirmed), ExportEvent.ChooseScriptTarget(1))
            after.format shouldBe ExportFormat.GLB
            after.selectedScriptTarget shouldBe 1
            after.target shouldBe folder.resolve("bracket-view.glb")
            after.overwrite shouldBe Overwrite.None
        }

        "a click outside the list is ignored" {
            val start = configuring(listOf(a))
            reduce(start, ExportEvent.ChooseScriptTarget(7)) shouldBe start
            reduce(start, ExportEvent.ChooseScriptTarget(-1)) shouldBe start
        }

        "a format chosen by hand drops the mark" {
            val after = reduce(configuring(listOf(a, b)), ExportEvent.ChooseFormat(ExportFormat.STL))
            after.selectedScriptTarget shouldBe null
            after.format shouldBe ExportFormat.STL
        }

        "another file name drops the mark, the declared one keeps it" {
            val start = configuring(listOf(a, b))
            reduce(start, ExportEvent.ChooseTarget(folder.resolve("bracket.step"), null)).selectedScriptTarget shouldBe
                0
            reduce(start, ExportEvent.ChooseTarget(folder.resolve("other.step"), null)).selectedScriptTarget shouldBe
                null
        }

        "a run that is stopped returns to the dialog with the targets and the mark" {
            val start = configuring(listOf(a, b))
            val running =
                ExportStateMachine
                    .reduce(
                        start,
                        ExportEvent.Start(1),
                    ).state
                    .shouldBeInstanceOf<ExportState.Running>()
            val back =
                ExportStateMachine
                    .reduce(
                        running,
                        ExportEvent.Stop,
                    ).state
                    .shouldBeInstanceOf<ExportState.Configuring>()
            back.scriptTargets shouldBe listOf(a, b)
            back.selectedScriptTarget shouldBe 0
        }

        fun controller(): ExportController = ExportController(worker = { _, _, _, _ -> error("not used") })

        "begin marks the first declared target and puts the name into the chosen folder, not the script's" {
            val dir = TestSupport.newDir()
            val out = Files.createDirectory(dir.resolve("out"))
            val script = TestSupport.script(dir, "bracket", "// x\n")
            val controller = controller()
            try {
                controller.begin(script, ExportFormat.STL, out, listOf(b, a))
                val state = controller.state.value.shouldBeInstanceOf<ExportState.Configuring>()
                state.format shouldBe ExportFormat.GLB
                state.selectedScriptTarget shouldBe 0
                state.target shouldBe out.resolve("bracket-view.glb")
                controller.chooseScriptTarget(1)
                val second = controller.state.value.shouldBeInstanceOf<ExportState.Configuring>()
                second.format shouldBe ExportFormat.STEP
                second.target shouldBe out.resolve("bracket.step")
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a declared .stp name stays .stp" {
            val dir = TestSupport.newDir()
            val script = TestSupport.script(dir, "bracket", "// x\n")
            val controller = controller()
            try {
                val stp = ScriptExportTarget(ExportFormat.STEP, "bracket.stp")
                controller.begin(script, ExportFormat.STEP, dir, listOf(stp))
                val state = controller.state.value.shouldBeInstanceOf<ExportState.Configuring>()
                state.target shouldBe dir.resolve("bracket.stp")
                // the user confirms the very same name in the system dialog: still marked, still .stp
                controller.chooseTarget(dir.resolve("bracket.stp"))
                controller.state.value
                    .shouldBeInstanceOf<ExportState.Configuring>()
                    .selectedScriptTarget shouldBe 0
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        "an export with a declared name ends in the chosen folder and nowhere else".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            val out = Files.createDirectory(dir.resolve("out"))
            val session = Files.createDirectory(dir.resolve("session"))
            val script = TestSupport.script(dir, "bracket", "// x\n")
            val controller =
                ExportController(
                    worker = { _, _, gen, _ ->
                        ExportWorkerResult.Produced(
                            Files.write(session.resolve("export-$gen.step"), "ISO-10303-21;\nDATA;".toByteArray()),
                        )
                    },
                )
            try {
                controller.begin(script, ExportFormat.STEP, out, listOf(a))
                controller.start()
                check(TestSupport.await(20_000) { controller.state.value is ExportState.Succeeded })
                Files.list(out).use { s -> s.map { it.fileName.toString() }.toList() } shouldBe listOf("bracket.step")
                Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() } shouldBe
                    listOf("bracket.kstep.kts", "out", "session")
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
