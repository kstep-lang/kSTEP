package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.WatchSignal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The controller with the real runner (real child processes) and a watcher that the test fires by hand. */
class LiveRunControllerProcessTest :
    StringSpec({
        class Harness(
            timeoutMillis: Long,
        ) {
            val dir: Path = TestSupport.newDir()
            val session: Path = LiveRunController.createSessionDir(dir)
            var signal: (WatchSignal) -> Unit = {}
            private val runner = WorkerRunner(session, timeoutMillis = timeoutMillis)
            val controller =
                LiveRunController(
                    runner = { f, g, s -> runner.run(f, g, s) },
                    watcherFactory = { _, onEvent ->
                        signal = onEvent
                        AutoCloseable { }
                    },
                    sessionDir = session,
                )

            fun cleanup() {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a file deleted during a run ends in KSTEP-D-005, keeps the last good result and leaves no worker".config(
            timeout = 240.seconds,
        ) {
            val h = Harness(timeoutMillis = 90_000)
            try {
                val script = TestSupport.copyFixture(h.dir, "hello-assembly.kstep.kts", "a.kstep.kts")
                h.controller.open(script)
                TestSupport.awaitState(h.controller) { it is DesktopState.Ok }
                Files.writeString(script, "while (true) {}\n")
                h.signal(WatchSignal.CHANGED)
                TestSupport.await(60_000) { TestSupport.liveWorkers().isNotEmpty() } shouldBe true
                Files.delete(script)
                h.signal(WatchSignal.MISSING)
                val state = TestSupport.awaitState(h.controller, 30_000) { it is DesktopState.Error }
                state
                    .shouldBeInstanceOf<DesktopState.Error>()
                    .errors
                    .single()
                    .code shouldBe
                    DesktopErrorCodes.FILE_MISSING
                state.lastGood shouldNotBe null
                TestSupport.await(30_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "ten quick changes never leave more than one worker, and the last generation runs".config(
            timeout = 300.seconds,
        ) {
            val h = Harness(timeoutMillis = 20_000)
            try {
                val script = TestSupport.script(h.dir, "loop", "while (true) {}\n")
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
                repeat(10) { h.signal(WatchSignal.CHANGED) }
                val last = h.controller.state.value.generation
                val state =
                    TestSupport.awaitState(h.controller, 120_000) { it is DesktopState.Error && it.generation == last }
                sampler.interrupt()
                state
                    .shouldBeInstanceOf<DesktopState.Error>()
                    .errors
                    .first()
                    .code shouldBe DesktopErrorCodes.TIMEOUT
                (maxAlive.get() <= 1) shouldBe true
                TestSupport.await(30_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "close ends the worker and removes the session directory".config(timeout = 240.seconds) {
            val h = Harness(timeoutMillis = 90_000)
            try {
                val script = TestSupport.script(h.dir, "loop", "while (true) {}\n")
                h.controller.open(script)
                TestSupport.await(60_000) { TestSupport.liveWorkers().isNotEmpty() } shouldBe true
                h.controller.close()
                Files.exists(h.session) shouldBe false
                TestSupport.await(30_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
            } finally {
                h.cleanup()
            }
        }
    })
