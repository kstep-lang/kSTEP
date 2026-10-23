package dev.kstep.cli

import java.io.File
import kotlin.system.exitProcess

/**
 * Stand-in worker for [ImportWorkerSupervisorTest] (test sources only, never on a production class path): it misbehaves
 * on purpose so the supervisor's handling of each abnormal end can be tested with a real child process.
 */
object FakeImportWorker {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "print-exit" -> {
                print(args[2])
                System.out.flush()
                exitProcess(args[1].toInt())
            }
            "halt" -> {
                print(args[2])
                System.out.flush()
                Runtime.getRuntime().halt(args[1].toInt())
            }
            "sleep" -> sleepForever()
            "spawn-and-sleep" -> {
                val java = ImportWorkerLaunch.javaExecutable()
                val child =
                    ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        FakeImportWorker::class.java.name,
                        "sleep",
                    ).start()
                File(args[1]).writeText(child.pid().toString())
                sleepForever()
            }
            "oom" -> {
                val hoard = ArrayList<LongArray>()
                while (true) hoard.add(LongArray(1 shl 20))
            }
            "flood" -> {
                val line = "x".repeat(1023) + "\n"
                while (true) print(line)
            }
            else -> exitProcess(99)
        }
    }

    private fun sleepForever(): Nothing {
        while (true) Thread.sleep(60_000)
    }
}
