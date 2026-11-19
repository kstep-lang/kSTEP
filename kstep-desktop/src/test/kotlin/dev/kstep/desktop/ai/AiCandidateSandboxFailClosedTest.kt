package dev.kstep.desktop.ai

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.ai.llm.LlmReply
import dev.kstep.desktop.ai.prompt.PromptBuilder
import dev.kstep.desktop.ai.repair.AiLimits
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.ai.repair.NotVerifiedReason
import dev.kstep.desktop.ai.repair.RepairEffect
import dev.kstep.desktop.ai.repair.RepairEvent
import dev.kstep.desktop.ai.repair.RepairMachine
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.run.AiCandidateCompiler
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.run.WorkerSandbox
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.listDirectoryEntries
import kotlin.time.Duration.Companion.seconds

/** Without a worker sandbox nothing of a candidate is written or run, and the model is asked exactly once (ADR-0059). */
class AiCandidateSandboxFailClosedTest :
    StringSpec({
        val unavailable = WorkerSandbox.Unavailable("test: none")
        val available = WorkerSandbox.MacSandboxExec("p", Path.of("/s"), Path.of("/t"))

        fun runnerWith(
            dir: Path,
            sandbox: WorkerSandbox,
            starts: AtomicInteger,
            start: (List<String>) -> Process,
        ) = WorkerRunner(
            dir,
            sandbox = sandbox,
            supervisorFactory = { timeout, base, started ->
                ImportWorkerSupervisor(
                    timeout,
                    tempBase = base,
                    onStarted = started,
                    start = {
                        starts.incrementAndGet()
                        start(it)
                    },
                )
            },
        )

        "(a) the compiler with no sandbox writes no file and starts no run" {
            val dir = TestSupport.newDir()
            try {
                val runs = AtomicInteger()
                val c =
                    AiCandidateCompiler(dir, { unavailable }) { _, _, _ ->
                        runs.incrementAndGet()
                        RunResult.Skipped("never")
                    }
                val o = c.compile("val a = 1") {}.shouldBeInstanceOf<CompileOutcome.Unverifiable>()
                o.why shouldBe NotVerifiedReason.SANDBOX_UNAVAILABLE
                runs.get() shouldBe 0
                dir.resolve("ai").toFile().exists() shouldBe false
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "(b) the runner with no sandbox starts no worker and answers KSTEP-D-029".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val starts = AtomicInteger()
                val runner = runnerWith(dir, unavailable, starts) { error("must not start") }
                val file = TestSupport.script(dir, "c", "val a = 1")
                val r = runner.run(file, 1) { }.shouldBeInstanceOf<RunResult.Failure>()
                r.errors.first().code shouldBe AiErrorCodes.AI_SANDBOX_UNAVAILABLE
                starts.get() shouldBe 0
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "(c) the repair loop asks the model once and ends with a not-checked proposal" {
            val dir = TestSupport.newDir()
            try {
                val limits = AiLimits()
                val m = RepairMachine(limits, PromptBuilder(limits))
                val c = AiCandidateCompiler(dir, { unavailable }) { _, _, _ -> error("must not run") }
                var s: RepairState = RepairState.Idle
                var calls = 0

                fun feed(e: RepairEvent) {
                    val (n, eff) = m.reduce(s, e)
                    s = n
                    calls += eff.count { it is RepairEffect.CallLlm }
                    eff.filterIsInstance<RepairEffect.Compile>().forEach {
                        feed(RepairEvent.Compiled(it.runId, c.compile(it.script) {}, 1))
                    }
                }
                feed(RepairEvent.Start(1, "a box", "", false, 0))
                feed(RepairEvent.LlmAnswered(1, LlmReply.Text("```kotlin\nval a = 1\n```", 5), 1))
                calls shouldBe 1
                val p = s.shouldBeInstanceOf<RepairState.Proposal>()
                p.verdict shouldBe Verdict.NOT_VERIFIED
                p.notVerifiedReason shouldBe NotVerifiedReason.SANDBOX_UNAVAILABLE
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "(d) a sandbox that cannot start ends the run with KSTEP-D-030, no retry, no second model call".config(
            timeout = 60.seconds,
        ) {
            val dir = TestSupport.newDir()
            try {
                val starts = AtomicInteger()
                val runner = runnerWith(dir, available, starts) { throw IOException("cannot run program") }
                val limits = AiLimits()
                val m = RepairMachine(limits, PromptBuilder(limits))
                val c = AiCandidateCompiler(dir, { available }) { f, g, st -> runner.run(f, g, st) }
                val outcome = c.compile("val a = 1") {}
                outcome.shouldBeInstanceOf<CompileOutcome.Refused>().code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
                starts.get() shouldBe 1
                var s: RepairState = RepairState.Idle
                var calls = 0
                var (n, eff) = m.reduce(s, RepairEvent.Start(1, "a box", "", false, 0))
                s = n
                calls += eff.count { it is RepairEffect.CallLlm }
                m.reduce(s, RepairEvent.LlmAnswered(1, LlmReply.Text("```kotlin\nval a = 1\n```", 5), 1)).also {
                    s = it.first
                    calls += it.second.count { e -> e is RepairEffect.CallLlm }
                }
                val after = m.reduce(s, RepairEvent.Compiled(1, outcome, 2))
                calls += after.second.count { it is RepairEffect.CallLlm }
                after.first.shouldBeInstanceOf<RepairState.Failed>().code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
                calls shouldBe 2 - 1 // the request and nothing after the refusal
                dir
                    .resolve("ai")
                    .takeIf { it.toFile().exists() }
                    ?.listDirectoryEntries()
                    ?.shouldBeEmpty()
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "(e) an error line of the sandbox wrapper itself is KSTEP-D-030, not a compiler error".config(
            timeout = 90.seconds,
        ) {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return@config
            val dir = TestSupport.newDir()
            try {
                val runner =
                    runnerWith(dir, available, AtomicInteger()) {
                        ProcessBuilder(
                            "sh",
                            "-c",
                            "echo 'sandbox-exec: sandbox_apply: Operation not permitted' >&2; exit 71",
                        ).start()
                    }
                val file = TestSupport.script(dir, "c", "val a = 1")
                val r = runner.run(file, 1) { }.shouldBeInstanceOf<RunResult.Failure>()
                r.errors.first().code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "without a sandbox argument the worker command is not wrapped (regression)".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                var first: String? = null
                val runner =
                    WorkerRunner(
                        dir,
                        supervisorFactory = { timeout, base, started ->
                            ImportWorkerSupervisor(timeout, tempBase = base, onStarted = started, start = {
                                first = it.first()
                                throw IOException("stop here")
                            })
                        },
                    )
                runner.run(TestSupport.script(dir, "c", "val a = 1"), 1) { }
                (first!!.endsWith("java") || first!!.endsWith("java.exe")) shouldBe true
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "(e) a candidate that swaps ai/ for a link: the next attempt writes nothing outside and is refused" {
            val dir = TestSupport.newDir()
            val outside = TestSupport.newDir()
            try {
                val runs = AtomicInteger()
                lateinit var c: AiCandidateCompiler
                c =
                    AiCandidateCompiler(dir, { available }) { file, _, _ ->
                        if (runs.incrementAndGet() == 1) {
                            // what the sandboxed candidate does: remove ai/ and put a link in its place
                            java.nio.file.Files
                                .delete(file)
                            java.nio.file.Files
                                .delete(dir.resolve("ai"))
                            java.nio.file.Files
                                .createSymbolicLink(dir.resolve("ai"), outside)
                        }
                        RunResult.Skipped("test")
                    }
                c.compile("val a = 1") {}
                val second = c.compile("val b = 2") {}
                second.shouldBeInstanceOf<CompileOutcome.Refused>().code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
                runs.get() shouldBe 1
                outside.listDirectoryEntries().shouldBeEmpty()
            } finally {
                dir.toFile().deleteRecursively()
                outside.toFile().deleteRecursively()
            }
        }

        "(f) a file planted in place of ai/: refused with KSTEP-D-030, no exception, no run" {
            val dir = TestSupport.newDir()
            try {
                val runs = AtomicInteger()
                val c =
                    AiCandidateCompiler(dir, { available }) { _, _, _ ->
                        runs.incrementAndGet()
                        RunResult.Skipped("t")
                    }
                java.nio.file.Files
                    .writeString(dir.resolve("ai"), "planted")
                c
                    .compile("val a = 1") {}
                    .shouldBeInstanceOf<CompileOutcome.Refused>()
                    .code shouldBe AiErrorCodes.AI_SANDBOX_START_FAILED
                runs.get() shouldBe 0
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
