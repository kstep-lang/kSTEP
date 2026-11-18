package dev.kstep.cli

import dev.kstep.preview.RenderFormat
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlin.system.exitProcess

/**
 * Entry point of the export subprocess of kstep-desktop (ADR-0057). Not a user command: only the desktop's export
 * runner starts this class, as a separate JVM, through [ImportWorkerSupervisor]. A script is only ever evaluated in
 * that subprocess, never in the desktop's JVM.
 *
 * It accepts exactly one argument shape and nothing else (strict whitelist, [parse]):
 * `<script> --format glb|stl|3mf|step --out <path> --output json`. Any other flag, order or count ends with
 * [USAGE_EXIT_CODE]. The mesh formats run `kstep render` (which already fails closed for STL/3MF, ADR-0049), STEP runs
 * `kstep export`.
 *
 * The orphan watchdog is armed with [EXPORT_TIMEOUT_MILLIS] plus the grace period and deliberately ignores
 * [ImportWatchdog.resolveTimeoutMillis]: that value can only be lowered from the 60 s default, so using it would let
 * the worker halt itself at 70 s in the middle of a legitimate 120 s export.
 */
object DesktopExportWorker {
    const val MAIN_CLASS: String = "dev.kstep.cli.DesktopExportWorker"

    /** Exit code for arguments that are not one of the whitelisted forms. */
    const val USAGE_EXIT_CODE: Int = 2

    /** The wall-clock limit of one export: the single source for the desktop's supervisor and this worker's watchdog. */
    const val EXPORT_TIMEOUT_MILLIS: Long = 120_000L

    /** The time after which an orphaned export worker halts itself. */
    const val ORPHAN_ARM_MILLIS: Long = EXPORT_TIMEOUT_MILLIS + ImportWorkerLaunch.ORPHAN_GRACE_MILLIS

    private const val ARG_COUNT = 7

    @JvmStatic
    fun main(args: Array<String>) {
        // Same reason as in MainKt.main: kotlin-logging's start-up banner would go to stdout.
        KotlinLoggingConfiguration.logStartupMessage = false
        val command = parse(args.toList())
        if (command == null) {
            System.err.println("kstep desktop export worker: invalid arguments")
            exitProcess(USAGE_EXIT_CODE)
        }
        ImportWatchdog(ORPHAN_ARM_MILLIS) { Runtime.getRuntime().halt(ImportWorkerLaunch.ORPHAN_EXIT_CODE) }.start()
        when (command) {
            is CliCommand.Export -> runExport(command)
            is CliCommand.Render -> runRender(command, includeParts = false)
            else -> exitProcess(USAGE_EXIT_CODE)
        }
    }

    /**
     * The whitelist: exactly `<script> --format <f> --out <path> --output json` with `<f>` one of `glb`, `stl`, `3mf`,
     * `step`; `null` for everything else. Pure, so it is tested without starting a process.
     */
    internal fun parse(args: List<String>): CliCommand? {
        if (args.size != ARG_COUNT) return null
        val (script, formatFlag, format, outFlag, out, outputFlag, json) = args
        if (formatFlag != "--format" || outFlag != "--out" || outputFlag != "--output" || json != "json") return null
        if (script.isBlank() || script.startsWith("-") || out.isBlank() || out.startsWith("-")) return null
        if (script.any { it.isISOControl() } || out.any { it.isISOControl() }) return null
        if (format == "step") {
            return CliCommand.Export(
                scriptPath = script,
                outPath = out,
                jsonOutput = true,
                validate = false,
                requireGeometry = false,
            )
        }
        val renderFormat =
            when (format) {
                "glb" -> RenderFormat.GLB
                "stl" -> RenderFormat.STL
                "3mf" -> RenderFormat.THREE_MF
                else -> return null
            }
        return CliCommand.Render(
            scriptPath = script,
            format = renderFormat,
            outPath = out,
            width = DEFAULT_WIDTH,
            height = DEFAULT_HEIGHT,
            withStep = false,
            requireGeometry = true,
            jsonOutput = true,
            toleranceMm = null,
            requireWatertight = false,
        )
    }

    private const val DEFAULT_WIDTH = 1024
    private const val DEFAULT_HEIGHT = 768
}

private operator fun <T> List<T>.component6(): T = this[5]

private operator fun <T> List<T>.component7(): T = this[6]
