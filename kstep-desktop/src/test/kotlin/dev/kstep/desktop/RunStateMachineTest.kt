package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopEvent
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.Effect
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.RunStateMachine
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

class RunStateMachineTest :
    StringSpec({
        val machine = RunStateMachine()
        val file = Path.of("/tmp/a.kstep.kts")
        val now = Instant.EPOCH

        fun model(generation: Long) = DesktopModel(null, 0, generation, now, 10, emptyList())

        fun step(
            state: DesktopState,
            event: DesktopEvent,
        ) = machine.reduce(state, event, 0, now)

        "Ok, then Error, then Ok again" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Finished(1, RunResult.Success(model(1)))).state
            s.shouldBeInstanceOf<DesktopState.Ok>()
            s = step(s, DesktopEvent.Changed).state
            s.shouldBeInstanceOf<DesktopState.Running>()
            s =
                step(
                    s,
                    DesktopEvent.Finished(2, RunResult.Failure(listOf(DesktopError("KSTEP-S-001", 1, 1, "x")))),
                ).state
            s.shouldBeInstanceOf<DesktopState.Error>()
            s = step(s, DesktopEvent.Changed).state
            s = step(s, DesktopEvent.Finished(3, RunResult.Success(model(3)))).state
            s.shouldBeInstanceOf<DesktopState.Ok>()
        }

        "lastGood survives Running and Error" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Finished(1, RunResult.Success(model(1)))).state
            s = step(s, DesktopEvent.Changed).state
            s.lastGood?.generation shouldBe 1
            s =
                step(
                    s,
                    DesktopEvent.Finished(2, RunResult.Failure(listOf(DesktopError("KSTEP-S-001", null, null, "x")))),
                ).state
            s.lastGood?.generation shouldBe 1
            s = step(s, DesktopEvent.Changed).state
            s = step(s, DesktopEvent.Finished(3, RunResult.NativeUnavailable("n"))).state
            s.shouldBeInstanceOf<DesktopState.NativeUnavailable>()
            s.lastGood?.generation shouldBe 1
        }

        "a Finished event of an old generation is discarded" {
            val running = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            val changed = step(running, DesktopEvent.Changed)
            val transition = step(changed.state, DesktopEvent.Finished(1, RunResult.Success(model(1))))
            transition.state shouldBe changed.state
            transition.effects.shouldBeEmpty()
        }

        "Missing gives D-005 and keeps lastGood, and makes a running result outdated" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Finished(1, RunResult.Success(model(1)))).state
            s = step(s, DesktopEvent.Changed).state // gen 2 running
            val missing = step(s, DesktopEvent.Missing)
            val error = missing.state.shouldBeInstanceOf<DesktopState.Error>()
            error.errors.single().code shouldBe DesktopErrorCodes.FILE_MISSING
            error.lastGood?.generation shouldBe 1
            missing.effects shouldContain Effect.CancelRun
            step(missing.state, DesktopEvent.Finished(2, RunResult.Success(model(2)))).state shouldBe missing.state
        }

        "Open resets lastGood and asks for a camera reset" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Finished(1, RunResult.Success(model(1)))).state
            val opened = step(s, DesktopEvent.Open(Path.of("/tmp/b.kstep.kts")))
            opened.state.lastGood shouldBe null
            opened.effects shouldContain Effect.ResetCamera
            opened.effects shouldContain Effect.CancelRun
            opened.effects shouldContain Effect.StartRun(2, Path.of("/tmp/b.kstep.kts"))
        }

        "ForceRun and Changed without a file do nothing".config(timeout = 30.seconds) {
            val forced = step(DesktopState.Idle, DesktopEvent.ForceRun)
            forced.state shouldBe DesktopState.Idle
            forced.effects.shouldBeEmpty()
            step(DesktopState.Idle, DesktopEvent.Changed).effects.shouldBeEmpty()
            step(DesktopState.Idle, DesktopEvent.Missing).effects.shouldBeEmpty()
        }

        "Skipped becomes Waiting, keeps the last good model, and a change runs again" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Finished(1, RunResult.Success(model(1)))).state
            s = step(s, DesktopEvent.Changed).state
            s = step(s, DesktopEvent.Finished(2, RunResult.Skipped("Empty block"))).state
            val waiting = s.shouldBeInstanceOf<DesktopState.Waiting>()
            waiting.hint shouldBe "Empty block"
            waiting.lastGood?.generation shouldBe 1
            val changed = step(s, DesktopEvent.Changed)
            changed.state.shouldBeInstanceOf<DesktopState.Running>()
            changed.effects.shouldContain(Effect.StartRun(3, file))
            step(s, DesktopEvent.ForceRun).state.shouldBeInstanceOf<DesktopState.Running>()
        }

        "a late Skipped of an old generation is dropped" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Changed).state
            step(s, DesktopEvent.Finished(1, RunResult.Skipped("x"))).state.shouldBeInstanceOf<DesktopState.Running>()
        }

        "Close returns to Idle and cancels; a later Open above the floor never reuses a generation" {
            var s: DesktopState = step(DesktopState.Idle, DesktopEvent.Open(file)).state
            s = step(s, DesktopEvent.Changed).state // gen 2
            val closed = step(s, DesktopEvent.Close)
            closed.state shouldBe DesktopState.Idle
            closed.effects.shouldContain(Effect.CancelRun)
            val reopened = step(closed.state, DesktopEvent.Open(file, floor = 2))
            reopened.state.generation shouldBe 3
        }
    })
