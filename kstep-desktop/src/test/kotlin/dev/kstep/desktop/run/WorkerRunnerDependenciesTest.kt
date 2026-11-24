package dev.kstep.desktop.run

import dev.kstep.cli.ImportWorkerSupervisor
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a worker's `dependencies` do to a run (ADR-0080): the worker is faked by a shell that prints a prepared last
 * line, so no OCCT and no script compiler are needed. Unix shells only.
 */
class WorkerRunnerDependenciesTest :
    StringSpec({
        val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

        fun runWith(
            lastLine: String,
            block: (RunResult, Path) -> Unit,
        ) {
            val dir = TestSupport.newDir()
            try {
                val reply = dir.resolve("reply.json.txt").also { Files.writeString(it, lastLine + "\n") }
                val script = TestSupport.script(dir, "a", "x")
                val runner =
                    WorkerRunner(
                        dir,
                        supervisorFactory = { timeout, base, started ->
                            ImportWorkerSupervisor(
                                timeout,
                                tempBase = base,
                                onStarted = started,
                                start = { ProcessBuilder("/bin/sh", "-c", "cat '$reply'").start() },
                            )
                        },
                    )
                block(runner.run(script, 1) { }, dir)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        val summary = """{"status":"success","content":"summary""""

        "a valid list on a success is reported as absolute paths" {
            if (!windows) {
                runWith("$summary,\"dependencies\":[\"b.txt\",\"sub/a.txt\"]}") { result, dir ->
                    val success = result.shouldBeInstanceOf<RunResult.Success>()
                    success.dependencies shouldBe listOf(dir.resolve("b.txt"), dir.resolve("sub/a.txt"))
                    success.model.notes.shouldBeEmpty()
                }
            }
        }

        "a result without the field (an older worker) gives no report, no notice" {
            if (!windows) {
                runWith("$summary}") { result, _ ->
                    val success = result.shouldBeInstanceOf<RunResult.Success>()
                    success.dependencies shouldBe null
                    success.model.notes.shouldBeEmpty()
                }
            }
        }

        "a hostile list is ignored with a notice and no path is taken over" {
            if (!windows) {
                runWith("$summary,\"dependencies\":[\"../../etc/passwd\"]}") { result, _ ->
                    val success = result.shouldBeInstanceOf<RunResult.Success>()
                    success.dependencies shouldBe emptyList()
                    success.model.notes shouldBe listOf(DependencyListParser.IGNORED_NOTICE)
                }
            }
        }

        "a failed script still reports its inputs, a hostile list adds KSTEP-D-037 as a warning" {
            if (!windows) {
                val failure = """{"status":"error","errorKind":"runtime_error","code":"KSTEP-S-017","message":"m""""
                runWith("$failure,\"dependencies\":[\"missing.txt\"]}") { result, dir ->
                    val failed = result.shouldBeInstanceOf<RunResult.Failure>()
                    failed.dependencies shouldBe listOf(dir.resolve("missing.txt"))
                    failed.errors.map { it.code } shouldBe listOf("KSTEP-S-017")
                }
                runWith("$failure,\"dependencies\":[\"/abs\"]}") { result, _ ->
                    val failed = result.shouldBeInstanceOf<RunResult.Failure>()
                    failed.dependencies shouldBe emptyList()
                    failed.errors.map { it.code } shouldBe listOf("KSTEP-S-017", DesktopErrorCodes.DEPENDENCIES_IGNORED)
                    failed.errors.last().severity shouldBe Severity.WARNING
                }
            }
        }

        "a field-free failure line keeps the previous set (no report)" {
            if (!windows) {
                val io = """{"status":"error","errorKind":"io_error","message":"write failed"}"""
                runWith(io) { result, _ ->
                    result.shouldBeInstanceOf<RunResult.Failure>().dependencies shouldBe null
                }
            }
        }

        "an unreadable result gives no report at all" {
            if (!windows) {
                runWith("not json") { result, _ ->
                    result.shouldBeInstanceOf<RunResult.Failure>().dependencies shouldBe null
                }
            }
        }
    })
