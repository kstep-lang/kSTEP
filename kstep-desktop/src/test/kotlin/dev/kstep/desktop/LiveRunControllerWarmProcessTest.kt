package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.WarmWorkerClient
import dev.kstep.desktop.run.WarmWorkerSettings
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The controller with the warm runner (ADR-0081): cancel and a quick next run on a shared process never clash. */
class LiveRunControllerWarmProcessTest :
    StringSpec({
        class Harness(
            mode: WarmWorkerSettings.Mode,
        ) {
            val dir: Path = TestSupport.newDir()
            val session: Path = LiveRunController.createSessionDir(dir)
            var signal: (WatchSignal) -> Unit = {}
            private val client =
                WarmWorkerClient(
                    session,
                    WarmWorkerSettings(mode, if (mode == WarmWorkerSettings.Mode.REUSE) 20 else 1, 120_000),
                    null,
                )
            private val runner = WorkerRunner(session, timeoutMillis = 120_000, warm = client)
            val controller =
                LiveRunController(
                    runner = { f, g, s -> runner.run(f, g, s) },
                    watcherFactory = { _, onEvent ->
                        signal = onEvent
                        AutoCloseable { }
                    },
                    sessionDir = session,
                    onClose = { client.close() },
                )

            fun cleanup() {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        val okText = Files.readString(TestSupport.fixture("hello-assembly.kstep.kts"))

        "an edit cancels the running script; the next run on the shared process is correct, not a spurious error"
            .config(
                timeout = 600.seconds,
            ) {
                val h = Harness(WarmWorkerSettings.Mode.REUSE)
                try {
                    val script = TestSupport.script(h.dir, "a", okText)
                    h.controller.open(script)
                    TestSupport.awaitState(h.controller, 180_000) { it is DesktopState.Ok }
                    Files.writeString(script, "while (true) {}\n")
                    h.signal(WatchSignal.CHANGED)
                    TestSupport.await(120_000) { TestSupport.liveWorkers().isNotEmpty() } shouldBe true
                    Thread.sleep(1_500) // let the loop really run on the worker
                    Files.writeString(script, okText)
                    h.signal(WatchSignal.CHANGED)
                    val last = h.controller.state.value.generation
                    val state =
                        TestSupport.awaitState(h.controller, 180_000) {
                            it is DesktopState.Ok &&
                                it.generation >= last
                        }
                    state.shouldBeInstanceOf<DesktopState.Ok>()
                } finally {
                    h.cleanup()
                }
            }

        "ten quick edits end with the result of the last one and no stray worker".config(timeout = 900.seconds) {
            val h = Harness(WarmWorkerSettings.Mode.REUSE)
            try {
                val script = TestSupport.script(h.dir, "a", "while (true) {}\n")
                h.controller.open(script)
                val maxAlive = AtomicInteger(0)
                val sampler =
                    Thread {
                        while (!Thread.currentThread().isInterrupted) {
                            maxAlive.accumulateAndGet(TestSupport.liveWorkers().size, ::maxOf)
                            try {
                                Thread.sleep(50)
                            } catch (_: InterruptedException) {
                                return@Thread
                            }
                        }
                    }.apply { isDaemon = true }
                sampler.start()
                repeat(9) { h.signal(WatchSignal.CHANGED) }
                Files.writeString(script, okText)
                h.signal(WatchSignal.CHANGED)
                val last = h.controller.state.value.generation
                val state =
                    TestSupport.awaitState(h.controller, 300_000) {
                        it is DesktopState.Ok &&
                            it.generation == last
                    }
                sampler.interrupt()
                state.shouldBeInstanceOf<DesktopState.Ok>()
                // the process being killed and the next one starting may overlap for a moment, never more than that
                (maxAlive.get() <= 2) shouldBe true
                TestSupport.await(60_000) { TestSupport.liveWorkers().size <= 1 } shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "close ends the warm worker and removes the session directory".config(timeout = 600.seconds) {
            val h = Harness(WarmWorkerSettings.Mode.SPARE)
            try {
                val script = TestSupport.script(h.dir, "a", okText)
                h.controller.open(script)
                TestSupport.awaitState(h.controller, 180_000) { it is DesktopState.Ok }
                TestSupport.await(120_000) { TestSupport.liveWorkers().isNotEmpty() } shouldBe true // the spare
                h.controller.close()
                Files.exists(h.session) shouldBe false
                TestSupport.await(60_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
            } finally {
                h.cleanup()
            }
        }
    })
