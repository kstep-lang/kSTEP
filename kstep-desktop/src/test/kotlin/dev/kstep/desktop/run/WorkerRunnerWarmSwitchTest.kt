package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The kill switch and the scope of the warm worker (ADR-0081): off and default are the cold path. */
class WorkerRunnerWarmSwitchTest :
    StringSpec({
        "without a warm client the cold supervisor is used for every run".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val calls = AtomicInteger()
                val runner =
                    WorkerRunner(
                        dir,
                        supervisorFactory = { timeout, base, started ->
                            calls.incrementAndGet()
                            ImportWorkerSupervisor(
                                timeout,
                                tempBase = base,
                                onStarted = started,
                                start = { throw IOException("no process in this test") },
                            )
                        },
                    )
                runner.usesWarmWorker shouldBe false
                val script = TestSupport.script(dir, "a", "val x = 1\n")
                runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.Failure>()
                calls.get() shouldBe 1
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the user sandbox wiring builds a warm runner only for spare and reuse".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val box = UserScriptSandbox { WorkerSandbox.Unavailable("test") }
                box.workerRunner(dir).value.usesWarmWorker shouldBe false
                box.workerRunner(dir, WarmWorkerSettings.resolve("off", null, null, null)).value.usesWarmWorker shouldBe
                    false
                box
                    .workerRunner(
                        dir,
                        WarmWorkerSettings.resolve("spare", null, null, null),
                    ).value.usesWarmWorker shouldBe
                    true
                box
                    .workerRunner(
                        dir,
                        WarmWorkerSettings.resolve("reuse", null, null, null),
                    ).value.usesWarmWorker shouldBe
                    true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a failing sandbox start of the warm worker is D-031 and never falls back to an unsandboxed process".config(
            timeout = 60.seconds,
        ) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            try {
                val starts = AtomicInteger()
                val sandbox =
                    WorkerSandbox.Bubblewrap(
                        java.nio.file.Path
                            .of("/nonexistent/bwrap"),
                        session,
                        session,
                        hideTmp = false,
                    )
                val client =
                    WarmWorkerClient(
                        session,
                        WarmWorkerSettings(WarmWorkerSettings.Mode.SPARE, 1, 120_000),
                        sandbox,
                        starter = {
                            starts.incrementAndGet()
                            throw IOException("sandbox refused")
                        },
                    )
                val runner =
                    WorkerRunner(
                        session,
                        sandbox = sandbox,
                        sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                        sandboxFailureMessage = UserScriptSandbox.SANDBOX_FAILED_MESSAGE,
                        warm = client,
                    )
                val script = TestSupport.script(dir, "a", "val x = 1\n")
                val result = runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.Failure>()
                result.errors.first().code shouldBe DesktopErrorCodes.SANDBOX_START_FAILED
                (starts.get() >= 1) shouldBe true
                client.close()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
