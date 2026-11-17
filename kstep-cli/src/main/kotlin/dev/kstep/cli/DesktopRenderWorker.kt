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
        val command = resolveCommand(arrayOf("render") + args)
        if (command !is CliCommand.Render || !command.jsonOutput) {
            System.err.println("kstep desktop render worker: invalid arguments")
            exitProcess(USAGE_EXIT_CODE)
        }
        ImportWatchdog(ImportWatchdog.resolveTimeoutMillis() + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS) {
            Runtime.getRuntime().halt(ImportWorkerLaunch.ORPHAN_EXIT_CODE)
        }.start()
        runRender(command)
    }
}
