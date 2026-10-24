package dev.kstep.cli

import java.nio.file.Path

/**
 * Test-only program: supervises a [FakeImportWorker] (`pid-then-sleep`) with a long timeout, so a test can end this
 * process with SIGTERM and check that the shutdown hook kills the worker and removes its temp directory.
 *
 * Arguments: `<tempBase> <workerPidFile>`.
 */
object SupervisorHookHarness {
    @JvmStatic
    fun main(args: Array<String>) {
        val tempBase = Path.of(args[0])
        val supervisor = ImportWorkerSupervisor(120_000, tempBase = tempBase)
        supervisor.runWith { dir ->
            ImportWorkerLaunch.command(
                listOf("pid-then-sleep", args[1]),
                120_000,
                mainClass = FakeImportWorker::class.java.name,
                maxHeap = "64m",
                tmpDir = dir,
            )
        }
    }
}
