package dev.kstep.cli

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess

/**
 * Entry point of the render subprocess of kstep-desktop (ADR-0051). Not a user command: only the desktop's
 * `WorkerRunner` starts this class, as a separate JVM, through [ImportWorkerSupervisor].
 *
 * It accepts the arguments of `kstep render` without the command word and only `--output json` renders; anything else
 * ends with [USAGE_EXIT_CODE]. Unlike `MainKt render` it arms the [ImportWatchdog] with the timeout plus
 * [ImportWorkerLaunch.ORPHAN_GRACE_MILLIS], so a worker whose desktop was killed (SIGKILL) halts itself instead of
 * running a script's endless loop forever. The timeout is only ever lowered by the `kstep.import.timeoutMillis`
 * property (see [ImportWatchdog.resolveTimeoutMillis]); the parent enforces its own timeout by killing the process.
 */
object DesktopRenderWorker {
    const val MAIN_CLASS: String = "dev.kstep.cli.DesktopRenderWorker"

    /** Exit code for arguments that are not a valid JSON-output render command. */
    const val USAGE_EXIT_CODE: Int = 2

    @JvmStatic
    fun main(args: Array<String>) {
        // Same reason as in MainKt.main: kotlin-logging's start-up banner would go to stdout.
        KotlinLoggingConfiguration.logStartupMessage = false
        val unsaved = parseUnsaved(args)
        if (unsaved == null) {
            System.err.println("kstep desktop render worker: invalid arguments")
            exitProcess(USAGE_EXIT_CODE)
        }
        val command = resolveCommand(arrayOf("render") + unsaved.rest)
        if (command !is CliCommand.Render || !command.jsonOutput) {
            System.err.println("kstep desktop render worker: invalid arguments")
            exitProcess(USAGE_EXIT_CODE)
        }
        val source =
            unsaved.options?.let { o ->
                when (val loaded = UnsavedBufferInput.load(command.scriptPath, o.scriptDir, o.displayName, o.sha256)) {
                    is UnsavedBufferInput.Result.Ok -> loaded.source
                    is UnsavedBufferInput.Result.Refused -> {
                        System.err.println("${UnsavedBufferInput.REFUSED_MARKER}${loaded.reason.name}")
                        exitProcess(USAGE_EXIT_CODE)
                    }
                }
            }
        ImportWatchdog(ImportWatchdog.resolveTimeoutMillis() + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS) {
            Runtime.getRuntime().halt(ImportWorkerLaunch.ORPHAN_EXIT_CODE)
        }.start()
        runRender(command, includeParts = true, source = source)
    }

    /** The three worker-private options of an unsaved run (ADR-0086), cut off before `kstep render` parses the rest. */
    internal data class UnsavedOptions(
        val scriptDir: String,
        val displayName: String,
        val sha256: String,
    )

    internal class Parsed(
        val rest: Array<String>,
        val options: UnsavedOptions?,
    )

    /** `null` for a partial or repeated set of the unsaved options. */
    internal fun parseUnsaved(args: Array<String>): Parsed? {
        val names = listOf("--unsaved-script-dir", "--unsaved-display-name", "--unsaved-sha256")
        val found = HashMap<String, String>()
        val rest = ArrayList<String>()
        var i = 0
        while (i < args.size) {
            val arg = args[i]
            if (arg in names) {
                if (arg in found || i + 1 >= args.size) return null
                found[arg] = args[i + 1]
                i += 2
            } else {
                rest += arg
                i++
            }
        }
        if (found.isEmpty()) return Parsed(rest.toTypedArray(), null)
        if (found.size != names.size) return null
        return Parsed(
            rest.toTypedArray(),
            UnsavedOptions(found.getValue(names[0]), found.getValue(names[1]), found.getValue(names[2])),
        )
    }
}
