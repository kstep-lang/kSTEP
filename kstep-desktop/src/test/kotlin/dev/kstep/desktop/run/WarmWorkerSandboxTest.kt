package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The warm worker inside the user-flow sandbox (ADR-0081, ADR-0062): the same wrapper as the cold path, and the second
 * run of one process is as confined as the first. Runs where the system has a sandbox (macOS, Linux with bubblewrap).
 */
class WarmWorkerSandboxTest :
    StringSpec({
        val box by lazy { UserScriptSandbox.production() }
        val on by lazy { box.resolve() is UserSandboxState.Active }
        val okText = Files.readString(TestSupport.fixture("hello-assembly.kstep.kts"))

        fun withWarm(block: (Path, Path, WarmWorkerClient, WorkerRunner) -> Unit) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            try {
                val sandbox = box.forSession(session)!!
                val client =
                    WarmWorkerClient(session, WarmWorkerSettings(WarmWorkerSettings.Mode.REUSE, 20, 120_000), sandbox)
                val runner =
                    WorkerRunner(
                        session,
                        timeoutMillis = 120_000,
                        sandbox = sandbox,
                        sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                        sandboxFailureMessage = UserScriptSandbox.SANDBOX_FAILED_MESSAGE,
                        warm = client,
                    )
                try {
                    block(dir, session, client, runner)
                } finally {
                    client.close()
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a warm process in the sandbox serves a script, then refuses writes outside the session in the second run"
            .config(
                timeout = 600.seconds,
            ) {
                if (!on) return@config
                withWarm { dir, session, client, runner ->
                    val outside = SandboxProbes.outsideDir()
                    try {
                        val file = TestSupport.script(dir, "candidate", okText)
                        runner.run(file, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                        val outFile = outside.resolve("probe.txt")
                        Files.writeString(file, SandboxProbes.writeScript(listOf(outFile)) + "\n" + okText)
                        runner.run(file, 2) { }.shouldBeInstanceOf<RunResult.Failure>()
                        Files.exists(outFile) shouldBe false
                        // a write inside the session folder still works in the same warm process
                        Files.writeString(
                            file,
                            SandboxProbes.writeScript(listOf(session.resolve("note.txt"))) + "\n" + okText,
                        )
                        runner.run(file, 3) { }.shouldBeInstanceOf<RunResult.Success>()
                    } finally {
                        outside.toFile().deleteRecursively()
                        client.reset()
                    }
                }
            }

        "a warm process in the sandbox has no network in its second run".config(timeout = 600.seconds) {
            if (!on) return@config
            withWarm { dir, _, client, runner ->
                SandboxProbes.Listeners().use { l ->
                    val file = TestSupport.script(dir, "candidate", okText)
                    runner.run(file, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                    Files.writeString(file, SandboxProbes.networkScript(l, withUrl = true) + "\n" + okText)
                    runner.run(file, 2) { }.shouldBeInstanceOf<RunResult.Failure>()
                    l.finish()
                    l.gotTcp.get() shouldBe false
                    l.gotUdp.get() shouldBe false
                }
                client.reset()
            }
        }
    })
