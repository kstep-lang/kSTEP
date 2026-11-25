package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportWorkerResult
import dev.kstep.desktop.export.ExportWorkerRunner
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A missing worker (ADR-0082) fails every flow with D-038 before any process, sandbox or warm worker is touched. */
class WorkerRunnerLocationTest :
    StringSpec({
        val broken = WorkerLocationResult.Broken(DesktopErrorCodes.WORKER_MISSING, "the packaged worker is incomplete")

        fun counting(calls: AtomicInteger): (Long, java.nio.file.Path, (Process) -> Unit) -> ImportWorkerSupervisor =
            { _, _, _ ->
                calls.incrementAndGet()
                error("must not be called")
            }

        "a broken worker fails the live run with D-038 and starts nothing".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val calls = AtomicInteger()
                val script = TestSupport.script(dir, "a", "val x = 1\n")
                val r = WorkerRunner(dir, worker = broken, supervisorFactory = counting(calls)).run(script, 1) { }
                r
                    .shouldBeInstanceOf<RunResult.Failure>()
                    .errors
                    .single()
                    .code shouldBe "KSTEP-D-038"
                calls.get() shouldBe 0
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a broken worker leaves the warm client untouched".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val started = AtomicInteger()
                val warm =
                    WarmWorkerClient(
                        dir,
                        WarmWorkerSettings.resolve("reuse", null, null, null),
                        sandbox = null,
                        worker = broken,
                        starter = {
                            started.incrementAndGet()
                            error("must not be called")
                        },
                    )
                try {
                    val script = TestSupport.script(dir, "a", "val x = 1\n")
                    val r = WorkerRunner(dir, worker = broken, warm = warm).run(script, 1) { }
                    r
                        .shouldBeInstanceOf<RunResult.Failure>()
                        .errors
                        .single()
                        .code shouldBe "KSTEP-D-038"
                    started.get() shouldBe 0
                } finally {
                    warm.close()
                }
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a broken worker fails the export with D-038 and starts nothing".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            try {
                val calls = AtomicInteger()
                val script = TestSupport.script(dir, "a", "val x = 1\n")
                val r =
                    ExportWorkerRunner(dir, worker = broken, supervisorFactory = counting(calls))
                        .run(script, ExportFormat.entries.first(), 1) { }
                r.shouldBeInstanceOf<ExportWorkerResult.Failure>().error.code shouldBe "KSTEP-D-038"
                calls.get() shouldBe 0
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
