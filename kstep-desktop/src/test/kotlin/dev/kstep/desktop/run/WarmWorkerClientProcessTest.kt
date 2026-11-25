package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.WarmWorkerSettings.Mode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/**
 * The warm worker with real child processes (ADR-0081): warm results equal cold ones, every abnormal end is one clear
 * error and kills the process, and the reuse rules (script binding, run count, taint, idle) hold. Deliberately not named
 * after OCCT: no native bridge is needed here, so the CI skip guard (test classes with "Occt" in the name) is not involved.
 */
class WarmWorkerClientProcessTest :
    StringSpec({
        class Harness(
            mode: Mode,
            maxRuns: Int = 20,
            idleMillis: Long = 120_000,
            val maxHeap: String = "2g",
            val timeoutMillis: Long = 120_000,
        ) {
            val dir: Path = TestSupport.newDir()
            val session: Path = LiveRunController.createSessionDir(dir)
            val client =
                WarmWorkerClient(
                    session,
                    WarmWorkerSettings(mode, if (mode == Mode.REUSE) maxRuns else 1, idleMillis),
                    null,
                    maxHeap = maxHeap,
                    launchTimeoutMillis = timeoutMillis,
                )
            val runner = WorkerRunner(session, timeoutMillis = timeoutMillis, maxHeap = maxHeap, warm = client)
            val cold = WorkerRunner(session, timeoutMillis = timeoutMillis, maxHeap = maxHeap)
            val lastPid = AtomicLong(-1)
            val lastHandle = AtomicReference<Process?>()
            private var generation = 0L

            fun run(
                file: Path,
                onStarted: (Process) -> Unit = {},
            ): RunResult =
                runner.run(file, ++generation) {
                    lastPid.set(it.pid())
                    lastHandle.set(it)
                    onStarted(it)
                }

            fun run(
                name: String,
                text: String,
            ): RunResult = run(TestSupport.script(dir, name, text))

            fun alive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

            fun cleanup() {
                client.close()
                runner.closeWarm()
                dir.toFile().deleteRecursively()
            }
        }

        val okText = Files.readString(TestSupport.fixture("hello-assembly.kstep.kts"))

        fun failure(result: RunResult) = result.shouldBeInstanceOf<RunResult.Failure>().errors.first()

        // what a run says, without the clock and the duration
        fun shape(result: RunResult): String =
            when (result) {
                is RunResult.Success ->
                    "ok notes=${result.model.notes} tri=${result.model.triangles} deps=${result.dependencies}"
                is RunResult.Failure -> "fail ${result.errors} deps=${result.dependencies}"
                else -> result.toString()
            }

        "warm results equal cold results: success, compile error, runtime error, inputs".config(timeout = 600.seconds) {
            val h = Harness(Mode.SPARE)
            try {
                Files.writeString(h.dir.resolve("data.txt"), "hello")
                val withInput =
                    TestSupport.copyFixture(h.dir, "hello-assembly.kstep.kts", "inputs.kstep.kts").also {
                        Files.writeString(it, "check(readInput(\"data.txt\") == \"hello\")\n" + Files.readString(it))
                    }
                val ok = TestSupport.copyFixture(h.dir, "hello-assembly.kstep.kts", "ok.kstep.kts")
                val compile = TestSupport.script(h.dir, "compile", "val a = 1\nval b = \n")
                val runtime = TestSupport.script(h.dir, "runtime", "val a = 1\nerror(\"boom\")\n")
                for (file in listOf(ok, compile, runtime, withInput)) {
                    val warm = h.run(file)
                    val cold = h.cold.run(file, 1000) { }
                    shape(warm) shouldBe shape(cold)
                }
                (h.run(withInput) as RunResult.Success).dependencies shouldBe listOf(h.dir.resolve("data.txt"))
            } finally {
                h.cleanup()
            }
        }

        "reuse: a leaked property or Top-Level value never reaches the next run, and the process is replaced".config(
            timeout = 600.seconds,
        ) {
            val h = Harness(Mode.REUSE)
            try {
                val file =
                    TestSupport.script(
                        h.dir,
                        "a",
                        "System.setProperty(\"kstep.leak\", \"1\")\nval topLevel = 5\n",
                    )
                h.run(file)
                val first = h.lastPid.get()
                Files.writeString(file, "error(\"leak=\" + System.getProperty(\"kstep.leak\"))\n")
                val error = failure(h.run(file))
                error.message shouldContain "leak=null"
                h.lastPid.get() shouldNotBe first
                h.alive(first) shouldBe false
            } finally {
                h.cleanup()
            }
        }

        "reuse: a clean script keeps the process and a Top-Level value of an earlier run is not visible".config(
            timeout = 600.seconds,
        ) {
            val h = Harness(Mode.REUSE)
            try {
                val file = TestSupport.script(h.dir, "a", "val topLevel = 5\n")
                h.run(file)
                val first = h.lastPid.get()
                h.run(file)
                h.lastPid.get() shouldBe first
                Files.writeString(file, "println(topLevel)\n")
                failure(h.run(file)).code shouldBe "KSTEP-S-001"
            } finally {
                h.cleanup()
            }
        }

        "reuse: a thread that outlives a run replaces the process".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                val file =
                    TestSupport.script(
                        h.dir,
                        "a",
                        "Thread { Thread.sleep(600_000) }.apply { isDaemon = true; start() }\n",
                    )
                h.run(file)
                val first = h.lastPid.get()
                h.run(file)
                h.lastPid.get() shouldNotBe first
            } finally {
                h.cleanup()
            }
        }

        "reuse: another script gets another process, the same script the same one".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                val a = TestSupport.script(h.dir, "a", "val x = 1\n")
                val b = TestSupport.script(h.dir, "b", "val x = 2\n")
                h.run(a)
                val pa = h.lastPid.get()
                h.run(a)
                h.lastPid.get() shouldBe pa
                h.run(b)
                h.lastPid.get() shouldNotBe pa
            } finally {
                h.cleanup()
            }
        }

        "reuse: the process ends after maxRuns runs".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE, maxRuns = 3)
            try {
                val a = TestSupport.script(h.dir, "a", "val x = 1\n")
                val pids =
                    (1..4).map {
                        h.run(a)
                        h.lastPid.get()
                    }
                pids[0] shouldBe pids[1]
                pids[1] shouldBe pids[2]
                pids[3] shouldNotBe pids[2]
            } finally {
                h.cleanup()
            }
        }

        "spare: every run has its own process and the next one is warming up already".config(timeout = 600.seconds) {
            val h = Harness(Mode.SPARE)
            try {
                val a = TestSupport.script(h.dir, "a", "val x = 1\n")
                h.run(a)
                val first = h.lastPid.get()
                TestSupport.await(60_000) { h.client.currentPid() != null } shouldBe true
                val spare = h.client.currentPid()
                spare shouldNotBe first
                h.run(a)
                h.lastPid.get() shouldBe spare
                TestSupport.await(60_000) { !h.alive(first) } shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "an idle worker is ended and not replaced".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE, idleMillis = 1_000)
            try {
                h.run("a", "val x = 1\n")
                val pid = h.lastPid.get()
                TestSupport.await(60_000) { !h.alive(pid) } shouldBe true
                TestSupport.await(30_000) { h.client.currentPid() == null } shouldBe true
            } finally {
                h.cleanup()
            }
        }

        "a timeout is KSTEP-D-001, the worker is gone and the next run works".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE, timeoutMillis = 4_000)
            try {
                failure(h.run("loop", "while (true) {}\n")).code shouldBe DesktopErrorCodes.TIMEOUT
                val pid = h.lastPid.get()
                TestSupport.await(30_000) { !h.alive(pid) } shouldBe true
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
                h.lastPid.get() shouldNotBe pid
            } finally {
                h.cleanup()
            }
        }

        "a crash gives one clear error and the next run works".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                val error = failure(h.run("halt", "Runtime.getRuntime().halt(7)\n"))
                error.code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
                error.message shouldContain "exited with code 7"
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "running out of heap is KSTEP-D-002 and the next run works".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE, maxHeap = "256m")
            try {
                failure(h.run("oom", "val a = ByteArray(1_000_000_000)\nprintln(a.size)\n")).code shouldBe
                    DesktopErrorCodes.HEAP_EXHAUSTED
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "a script that floods stdout is KSTEP-D-003, the worker is replaced".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                failure(h.run("flood", "repeat(40_000) { println(\"x\".repeat(1000)) }\n")).code shouldBe
                    DesktopErrorCodes.OUTPUT_TOO_LARGE
                val pid = h.lastPid.get()
                TestSupport.await(30_000) { !h.alive(pid) } shouldBe true
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "a script that exits the JVM leaves no result and the next run works".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                // System.exit inside the worker must not run shutdown hooks or end the protocol cleanly
                failure(h.run("exit", "System.exit(0)\n")).code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "a forged reply for another request is refused and kills the worker".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                val forged =
                    "java.io.FileOutputStream(java.io.FileDescriptor.out).apply { " +
                        "write(\"{\\\"v\\\":1,\\\"id\\\":999,\\\"exit\\\":0}\\n\".toByteArray()); flush() }\n"
                failure(h.run("forge", forged)).code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
                val pid = h.lastPid.get()
                TestSupport.await(30_000) { !h.alive(pid) } shouldBe true
                h.run("ok", okText).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "a forged reply with the right id only fools its own run, the real reply then replaces the process".config(
            timeout = 600.seconds,
        ) {
            val h = Harness(Mode.REUSE)
            try {
                val forged =
                    "java.io.FileOutputStream(java.io.FileDescriptor.out).apply { write((\"{\\\"v\\\":1,\\\"id\\\":1," +
                        "\\\"exit\\\":0,\\\"out64\\\":\\\"\\\",\\\"err64\\\":\\\"\\\",\\\"overflow\\\":false," +
                        "\\\"recycle\\\":false}\\n\").toByteArray()); flush() }\nThread.sleep(500)\n"
                val file = TestSupport.script(h.dir, "forge", forged)
                failure(h.run(file)).code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
                val pid = h.lastPid.get()
                Files.writeString(file, okText)
                h.run(file).shouldBeInstanceOf<RunResult.Success>()
                h.lastPid.get() shouldNotBe pid
            } finally {
                h.cleanup()
            }
        }

        "the handle of a finished request cannot kill the worker of the next one".config(timeout = 600.seconds) {
            val h = Harness(Mode.REUSE)
            try {
                val a = TestSupport.script(h.dir, "a", okText)
                h.run(a)
                val oldHandle = h.lastHandle.get()!!
                h.run(a)
                val pid = h.lastPid.get()
                oldHandle.isAlive shouldBe false
                oldHandle.destroyForcibly()
                h.alive(pid) shouldBe true
                h.run(a).shouldBeInstanceOf<RunResult.Success>()
                h.lastPid.get() shouldBe pid
            } finally {
                h.cleanup()
            }
        }

        "the handle of an open request ends the worker, the run returns an error at once".config(
            timeout = 600.seconds,
        ) {
            val h = Harness(Mode.REUSE)
            try {
                val file = TestSupport.script(h.dir, "loop", "while (true) {}\n")
                val result = AtomicReference<RunResult>()
                val worker =
                    Thread {
                        result.set(
                            h.run(file) { handle ->
                                Thread {
                                    Thread.sleep(2_000)
                                    handle.destroyForcibly()
                                }.start()
                            },
                        )
                    }
                worker.start()
                worker.join(120_000)
                result.get().shouldBeInstanceOf<RunResult.Failure>()
                failure(result.get()).code shouldBe DesktopErrorCodes.RESULT_UNREADABLE
                h.alive(h.lastPid.get()) shouldBe false
            } finally {
                h.cleanup()
            }
        }
    })
