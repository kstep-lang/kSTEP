package dev.kstep.cli

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Entry point of the `kstep import` worker subprocess (ADR-0038, worker model). Not a user command: the `kstep-cli`
 * launcher always starts [main] in `MainKt`, which never dispatches here; only [ImportWorkerSupervisor] starts this
 * class, as a separate JVM with its own main class. Nothing in an input file can select it.
 *
 * The arguments are the ones of `kstep import` without the command word, rebuilt by [importWorkerArgs] from an already
 * validated command. The worker runs exactly the in-process import ([runImportInProcess]) and writes its report and
 * exit code as `kstep import` always did. Running this class directly (`java -cp ... dev.kstep.cli.ImportWorker
 * <file> ...`) is the in-process import without the supervisor's bounds; the tests use that to compare the two paths.
 */
object ImportWorker {
    /** Exit code of a worker started with arguments that are not a valid import command (never happens via the CLI). */
    const val USAGE_EXIT_CODE: Int = 2

    @JvmStatic
    fun main(args: Array<String>) {
        // Same reason as in MainKt.main: kotlin-logging's start-up banner would go to stdout.
        KotlinLoggingConfiguration.logStartupMessage = false
        when (val command = resolveImportCommand(args.toList())) {
            is CliCommand.Import -> runImportInProcess(command)
            else -> {
                System.err.println("kstep import worker: invalid arguments")
                exitProcess(USAGE_EXIT_CODE)
            }
        }
    }
}

/** How the supervisor starts the worker JVM (ADR-0038). */
internal object ImportWorkerLaunch {
    /** Main class of the worker JVM. */
    const val WORKER_MAIN_CLASS: String = "dev.kstep.cli.ImportWorker"

    /** Heap limit of the worker (`-Xmx`); an OutOfMemoryError ends the worker and is reported as `limit_exceeded`. */
    const val WORKER_MAX_HEAP: String = "2g"

    /** Grace period after the timeout before a worker without a supervisor halts itself. */
    const val ORPHAN_GRACE_MILLIS: Long = 10_000L

    /** Exit code of a worker that halted itself because its supervisor did not end it (no output). */
    const val ORPHAN_EXIT_CODE: Int = 124

    /**
     * System properties forwarded to the worker when set in the supervisor: the two native-bridge overrides (trusted,
     * set only by whoever starts the JVM, see OcctNativeLibrary/PlaneGcsNativeLibrary) and the AWT headless flag.
     */
    val FORWARDED_PROPERTIES: List<String> =
        listOf("kstep.occt.bridge.library", "kstep.planegcs.bridge.library", "java.awt.headless")

    /**
     * The worker's JVM options. `-XX:+SuppressFatalErrorMessage` keeps a native crash from printing the JVM's fatal-error
     * banner (or writing an hs_err file into the working directory); `-XX:+ExitOnOutOfMemoryError` ends the worker on the
     * first OutOfMemoryError in any thread; `-XX:-CreateCoredumpOnCrash` avoids core files.
     */
    fun jvmOptions(
        timeoutMillis: Long,
        maxHeap: String = WORKER_MAX_HEAP,
        tmpDir: Path? = null,
    ): List<String> =
        buildList {
            add("-Xmx$maxHeap")
            add("-XX:+ExitOnOutOfMemoryError")
            add("-XX:+SuppressFatalErrorMessage")
            add("-XX:-CreateCoredumpOnCrash")
            add("-XX:TieredStopAtLevel=1")
            // The worker writes the bytes the supervisor passes through unchanged, so it must encode exactly as this
            // process would (a pipe would otherwise get native.encoding instead of the console's encoding).
            add("-Dstdout.encoding=${stdoutCharset().name()}")
            add("-Dstderr.encoding=${stderrCharset().name()}")
            System.getProperty("file.encoding")?.let { add("-Dfile.encoding=$it") }
            for (name in FORWARDED_PROPERTIES) System.getProperty(name)?.let { add("-D$name=$it") }
            add("-D${ImportWatchdog.TIMEOUT_PROPERTY}=$timeoutMillis")
            // The worker's own temp directory (native-library extracts, temp files): the supervisor removes it afterwards.
            // Deliberately not forwarded from the supervisor's own java.io.tmpdir.
            tmpDir?.let { add("-Djava.io.tmpdir=$it") }
        }

    /** The java executable of the running JVM (`java.home`), the same runtime the CLI was started with. */
    fun javaExecutable(): String {
        val windows =
            System
                .getProperty("os.name")
                .orEmpty()
                .lowercase()
                .contains("windows")
        val java = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        check(Files.isExecutable(java)) { "no java executable at $java" }
        return java.toString()
    }

    /** The full worker command line: same JVM, same class path ([System.getProperty] `java.class.path`). */
    fun command(
        workerArgs: List<String>,
        timeoutMillis: Long,
        mainClass: String = WORKER_MAIN_CLASS,
        maxHeap: String = WORKER_MAX_HEAP,
        tmpDir: Path? = null,
    ): List<String> =
        buildList {
            add(javaExecutable())
            addAll(jvmOptions(timeoutMillis, maxHeap, tmpDir))
            add("-cp")
            add(System.getProperty("java.class.path"))
            add(mainClass)
            addAll(workerArgs)
        }

    fun stdoutCharset(): Charset = charsetOf("stdout.encoding")

    fun stderrCharset(): Charset = charsetOf("stderr.encoding")

    private fun charsetOf(property: String): Charset =
        System.getProperty(property)?.let { runCatching { Charset.forName(it) }.getOrNull() }
            ?: Charset.defaultCharset()
}
