package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path

class ExportStateMachineTest :
    StringSpec({
        val target: Path = Path.of("/tmp/out/a.step")
        val error = DesktopError("KSTEP-D-022", null, null, "boom")

        fun reduce(
            state: ExportState,
            event: ExportEvent,
        ) = ExportStateMachine.reduce(state, event)

        fun configured() = ExportStateMachine.initial(ExportFormat.STEP, target)

        fun running(): ExportState.Running = reduce(configured(), ExportEvent.Start(1_000)).state as ExportState.Running

        "Start needs a target" {
            reduce(ExportStateMachine.initial(ExportFormat.STEP, null), ExportEvent.Start(0)).effects shouldBe
                emptyList()
        }

        "Start from Configuring runs the pipeline for the next generation" {
            val t = reduce(configured(), ExportEvent.Start(1_000))
            val state = t.state.shouldBeInstanceOf<ExportState.Running>()
            state.generation shouldBe 1
            state.phase shouldBe ExportPhase.STARTING
            t.effects shouldBe listOf(ExportEffect.StartPipeline(1, ExportFormat.STEP, target, false))
        }

        "an existing target must be confirmed before Start" {
            val needs = ExportStateMachine.initial(ExportFormat.STEP, target, existing = 10)
            reduce(needs, ExportEvent.Start(0)).effects shouldBe emptyList()
            val confirmed = reduce(needs, ExportEvent.ConfirmReplace).state
            reduce(confirmed, ExportEvent.Start(0))
                .effects
                .single()
                .shouldBeInstanceOf<ExportEffect.StartPipeline>()
                .replaceConfirmed shouldBe true
        }

        "a second Start while running is ignored" {
            val r = running()
            reduce(r, ExportEvent.Start(2_000)).effects shouldBe emptyList()
            reduce(r, ExportEvent.Start(2_000)).state shouldBe r
        }

        "choosing a format keeps the stem and changes the extension" {
            val state =
                reduce(
                    configured(),
                    ExportEvent.ChooseFormat(ExportFormat.GLB),
                ).state as ExportState.Configuring
            state.format shouldBe ExportFormat.GLB
            state.target shouldBe Path.of("/tmp/out/a.glb")
            state.overwrite shouldBe Overwrite.None
        }

        "choosing a target takes the existing-file hint and clears an old error" {
            val withError = (configured().copy(error = error))
            val state =
                reduce(withError, ExportEvent.ChooseTarget(Path.of("/x/b.step"), 99)).state as ExportState.Configuring
            state.overwrite shouldBe Overwrite.NeedsConfirm(99)
            state.error shouldBe null
        }

        "a rejected target is shown and kept" {
            val state = reduce(configured(), ExportEvent.TargetRejected(target, error)).state as ExportState.Configuring
            state.error shouldBe error
            state.target shouldBe target
        }

        "phases advance and a late phase of an older generation is ignored" {
            val r = running()
            (
                reduce(
                    r,
                    ExportEvent.PhaseChanged(1, ExportPhase.RUNNING_SCRIPT),
                ).state as ExportState.Running
            ).phase shouldBe
                ExportPhase.RUNNING_SCRIPT
            reduce(r, ExportEvent.PhaseChanged(0, ExportPhase.WRITING)).state shouldBe r
        }

        "Stop kills the run, keeps the settings and says nothing was written" {
            val r = running()
            val t = reduce(r, ExportEvent.Stop)
            val state = t.state.shouldBeInstanceOf<ExportState.Configuring>()
            state.notice shouldBe ExportText.STOPPED_NOTICE
            state.target shouldBe target
            state.format shouldBe ExportFormat.STEP
            t.effects shouldBe listOf(ExportEffect.CancelRun(1))
        }

        "Stop is a no-op while the file is being written" {
            val writing = reduce(running(), ExportEvent.PhaseChanged(1, ExportPhase.WRITING)).state
            reduce(writing, ExportEvent.Stop).state shouldBe writing
            reduce(writing, ExportEvent.Stop).effects shouldBe emptyList()
        }

        "a result of a stopped run is ignored and the next run has a new generation" {
            val stopped = reduce(running(), ExportEvent.Stop).state
            reduce(stopped, ExportEvent.Finished(1, PipelineOutcome.Failed(error))).state shouldBe stopped
            val again = reduce(stopped, ExportEvent.Start(5)).state as ExportState.Running
            again.generation shouldBe 2
            reduce(again, ExportEvent.Finished(1, PipelineOutcome.Failed(error))).state shouldBe again
        }

        "a failure leads back to Configuring with the settings kept and the error shown" {
            val state = reduce(running(), ExportEvent.Finished(1, PipelineOutcome.Failed(error))).state
            val c = state.shouldBeInstanceOf<ExportState.Configuring>()
            c.error shouldBe error
            c.target shouldBe target
        }

        "a replacement that is needed after the check goes back to the inline question" {
            val c = reduce(running(), ExportEvent.Finished(1, PipelineOutcome.NeedsReplace(7))).state
            c.shouldBeInstanceOf<ExportState.Configuring>().overwrite shouldBe Overwrite.NeedsConfirm(7)
        }

        "success ends in Succeeded and Close then closes the dialog" {
            val done =
                reduce(
                    running(),
                    ExportEvent.Finished(1, PipelineOutcome.Success("a.step", 5, Path.of("/tmp/out"))),
                ).state
            done.shouldBeInstanceOf<ExportState.Succeeded>()
            reduce(done, ExportEvent.Close).effects shouldBe listOf(ExportEffect.CloseDialog)
        }

        "Close closes while configuring and does nothing while running" {
            reduce(configured(), ExportEvent.Close).effects shouldBe listOf(ExportEffect.CloseDialog)
            reduce(running(), ExportEvent.Close).effects shouldBe emptyList()
        }
    })
