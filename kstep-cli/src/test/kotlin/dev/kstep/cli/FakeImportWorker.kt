package dev.kstep.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess

/**
 * Stand-in worker for [ImportWorkerSupervisorTest] (test sources only, never on a production class path): it misbehaves
 * on purpose so the supervisor's handling of each abnormal end can be tested with a real child process.
 *
 * Every mode that would otherwise run forever ends itself after [SELF_STOP_MILLIS] (exit 98), so a failed test can
 * never leave a process behind on a CI runner.
 */
object FakeImportWorker {
    private const val SELF_STOP_MILLIS = 120_000L
    private const val SELF_STOP_EXIT = 98
    private const val CHECK_EVERY_LINES = 1_000

    private val okJson = """{"status":"success","command":"import","file":"a.step"}""" + "\n"

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
            "halt-stderr" -> {
                System.err.print(args[2])
                System.err.flush()
                Runtime.getRuntime().halt(args[1].toInt())
            }
            "sleep" -> sleepBounded()
            "pid-then-sleep" -> {
                writeAtomic(args[1], ProcessHandle.current().pid().toString())
                sleepBounded()
            }
            "spawn-and-sleep" -> {
                val child = spawnSleeper(inherit = false)
                writeAtomic(args[1], child.pid().toString())
                sleepBounded()
            }
            "leak-pipe-and-exit" -> {
                val child = spawnSleeper(inherit = true)
                writeAtomic(args[1], child.pid().toString())
                print(okJson)
                System.out.flush()
                exitProcess(0)
            }
            "extract-then" -> extractThen(args)
            "orphan" -> {
                startOrphanBound()
                sleepBounded()
            }
            "oom" -> {
                val hoard = ArrayList<LongArray>()
                while (true) hoard.add(LongArray(1 shl 20))
            }
            "flood" -> flood { print(it) }
            "flood-stderr" -> flood { System.err.print(it) }
            else -> exitProcess(99)
        }
    }

    /** `extract-then <pathfile> <linkTarget|-> <halt <code>|sleep>`: leaves files in java.io.tmpdir like a native extract. */
    private fun extractThen(args: Array<String>) {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        val extract = Files.createDirectories(tmp.resolve("kstep-occt-fake"))
        Files.write(extract.resolve("libkstep_occt_bridge.dylib"), ByteArray(64) { 7 })
        if (args[2] != "-") Files.createSymbolicLink(tmp.resolve("link"), Path.of(args[2]))
        writeAtomic(args[1], tmp.toString())
        when (args[3]) {
            "halt" -> Runtime.getRuntime().halt(args[4].toInt())
            else -> sleepBounded()
        }
    }

    private fun spawnSleeper(inherit: Boolean): Process {
        val builder =
            ProcessBuilder(
                ImportWorkerLaunch.javaExecutable(),
                "-Xmx32m",
                "-cp",
                System.getProperty("java.class.path"),
                FakeImportWorker::class.java.name,
                "sleep",
            )
        if (inherit) {
            builder.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            builder.redirectError(ProcessBuilder.Redirect.INHERIT)
        }
        return builder.start()
    }

    /** Writes [text] so that a reader never sees an empty or half-written file. */
    private fun writeAtomic(
        file: String,
        text: String,
    ) {
        val target = Path.of(file)
        val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.writeString(temp, text)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun flood(emit: (String) -> Unit): Nothing {
        val line = "x".repeat(1023) + "\n"
        val deadline = System.nanoTime() + SELF_STOP_MILLIS * 1_000_000
        var lines = 0
        while (true) {
            emit(line)
            if (++lines % CHECK_EVERY_LINES == 0 && System.nanoTime() > deadline) exitProcess(SELF_STOP_EXIT)
        }
    }

    private fun sleepBounded(): Nothing {
        val deadline = System.nanoTime() + SELF_STOP_MILLIS * 1_000_000
        while (System.nanoTime() < deadline) Thread.sleep(250)
        exitProcess(SELF_STOP_EXIT)
    }
}
