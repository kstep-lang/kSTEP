package dev.kstep.cli

import dev.kstep.mcp.runStdioServer
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21WriteException
import dev.kstep.step21.Part21Writer
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import kotlin.system.exitProcess

// Deliberately plain println, not kotlin-logging (kSTEP M2 Welle 2 scope decision): this is a
// CLI's own stdout output -- the thing the user invoked the tool to see -- not diagnostic
// logging. That reasoning now also covers `kstep export`'s own result rendering (M2 Welle 6):
// both the human-readable text and the `--output json` document are the command's actual
// output, not a log line, and stay plain println for the same reason USAGE_TEXT does. See
// kstep-mcp for this project's actual kotlin-logging usage, and kstep-script's KStepScriptHost
// for the one place *this* wave does log diagnostically (an unexpected host-level failure).
const val USAGE_TEXT: String =
    """kSTEP CLI

Usage:
  kstep mcp                                Start the kSTEP MCP server over stdio
  kstep export <script.kstep.kts> [opts]   Export a *.kstep.kts script to a STEP Part 21 file
      --out <file.step>                    Output path (default: derived from the script name)
      --output json                        Emit the result as a JSON document instead of text
  kstep render <script.kstep.kts> [opts]   Render a headless SVG/PNG/text preview of a script
      -f, --format <auto|svg|png|text>     Preview container (default: auto)
      -o, --out <file>                     Output path (default: derived from the script name)
      -w, --width <px>                     Image width, 16..4096 (default: 1024)
          --height <px>                    Image height, 16..4096 (default: 768)
          --with-step                      Include the Part-21 text in the preview
          --require-geometry               Fail (exit 1) instead of silently falling back to text
          --output json                    Emit the result as a JSON document instead of text
  kstep help                                Show this message
"""

sealed interface CliCommand {
    data object StartMcpServer : CliCommand

    data class Export(
        val scriptPath: String,
        val outPath: String?,
        val jsonOutput: Boolean,
    ) : CliCommand

    data class Render(
        val scriptPath: String,
        val format: RenderFormat,
        val outPath: String?,
        val width: Int,
        val height: Int,
        val withStep: Boolean,
        val requireGeometry: Boolean,
        val jsonOutput: Boolean,
    ) : CliCommand

    data class ShowUsage(
        val exitCode: Int,
    ) : CliCommand
}

// Pure Array<String> -> CliCommand mapping, deliberately free of I/O/coroutines/exitProcess side
// effects, so it's directly testable from kstep-tests without forking a subprocess (see
// CliMainTest -- calling main() itself in-process is unsafe because its error path calls
// exitProcess, which would kill the whole test JVM).
fun resolveCommand(args: Array<String>): CliCommand =
    when {
        args.isEmpty() -> CliCommand.ShowUsage(exitCode = 0)
        args.size == 1 && args[0] == "mcp" -> CliCommand.StartMcpServer
        args.size == 1 && (args[0] == "help" || args[0] == "--help") -> CliCommand.ShowUsage(exitCode = 0)
        args[0] == "export" -> resolveExportCommand(args.drop(1))
        args[0] == "render" -> resolveRenderCommand(args.drop(1))
        else -> CliCommand.ShowUsage(exitCode = 1)
    }

// Small hand-rolled flag loop -- deliberately no argument-parsing library, matching this
// module's existing "a plain `when` is enough at this scope" stance (see resolveCommand
// above). Any malformed flag combination (no script path, `--out`/`--output` with no value, an
// unknown flag, an `--output` value other than "json", or more than one positional argument)
// resolves to ShowUsage(1), never a partially-filled CliCommand.Export.
private fun resolveExportCommand(rest: List<String>): CliCommand {
    var scriptPath: String? = null
    var outPath: String? = null
    var jsonOutput = false
    var i = 0
    while (i < rest.size) {
        when (rest[i]) {
            "--out" -> {
                outPath = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "--output" -> {
                val value = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                if (value != "json") return CliCommand.ShowUsage(1)
                jsonOutput = true
                i += 2
            }
            else -> {
                val arg = rest[i]
                if (arg.startsWith("--") || scriptPath != null) return CliCommand.ShowUsage(1)
                scriptPath = arg
                i += 1
            }
        }
    }
    val resolvedScriptPath = scriptPath ?: return CliCommand.ShowUsage(1)
    return CliCommand.Export(scriptPath = resolvedScriptPath, outPath = outPath, jsonOutput = jsonOutput)
}

private const val DEFAULT_RENDER_WIDTH = 1024
private const val DEFAULT_RENDER_HEIGHT = 768

// Same "any malformed combination -> ShowUsage(1), never a partially-filled command" discipline
// as resolveExportCommand above -- deliberately no argument-parsing library (see that function's
// KDoc). Numeric --width/--height values that fail to parse as Int also resolve to ShowUsage(1)
// here (range validation against RenderLimits happens later, in RenderCommand.kt, once a
// concrete Int is in hand) rather than throwing a NumberFormatException out of command
// resolution.
private fun resolveRenderCommand(rest: List<String>): CliCommand {
    var scriptPath: String? = null
    var format: RenderFormat = RenderFormat.AUTO
    var outPath: String? = null
    var width = DEFAULT_RENDER_WIDTH
    var height = DEFAULT_RENDER_HEIGHT
    var withStep = false
    var requireGeometry = false
    var jsonOutput = false
    var i = 0
    while (i < rest.size) {
        when (rest[i]) {
            "-f", "--format" -> {
                val value = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                format = RenderFormat.parse(value) ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "-o", "--out" -> {
                outPath = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "-w", "--width" -> {
                width = rest.getOrNull(i + 1)?.toIntOrNull() ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "--height" -> {
                height = rest.getOrNull(i + 1)?.toIntOrNull() ?: return CliCommand.ShowUsage(1)
                i += 2
            }
            "--with-step" -> {
                withStep = true
                i += 1
            }
            "--require-geometry" -> {
                requireGeometry = true
                i += 1
            }
            "--output" -> {
                val value = rest.getOrNull(i + 1) ?: return CliCommand.ShowUsage(1)
                if (value != "json") return CliCommand.ShowUsage(1)
                jsonOutput = true
                i += 2
            }
            else -> {
                val arg = rest[i]
                if (arg.startsWith("--") || scriptPath != null) return CliCommand.ShowUsage(1)
                scriptPath = arg
                i += 1
            }
        }
    }
    val resolvedScriptPath = scriptPath ?: return CliCommand.ShowUsage(1)
    return CliCommand.Render(
        scriptPath = resolvedScriptPath,
        format = format,
        outPath = outPath,
        width = width,
        height = height,
        withStep = withStep,
        requireGeometry = requireGeometry,
        jsonOutput = jsonOutput,
    )
}

fun main(args: Array<String>) {
    // kotlin-logging prints a one-line "kotlin-logging: initializing... active logger factory:
    // ..." banner to STDOUT (not stderr) the very first time ANY KotlinLogging.logger{} call
    // anywhere in the process actually resolves a logger (a static-initializer side effect --
    // see KotlinLoggingConfiguration.logStartupMessage in the kotlin-logging-jvm library, default
    // true). `kstep export`'s happy path never triggered this (KStepScriptHost only logs on an
    // unexpected host failure), but `kstep render` does on every successful geometry render
    // (OcctNativeLibrary.load logs at INFO on every native-bridge load) -- and this CLI's stdout
    // contract (`Wrote <path>` / a single `--output json` document, nothing else) must stay
    // exactly that, not a library banner plus the real output. MUST run before any other code in
    // this process touches KotlinLogging.logger{} (i.e. first thing in main()), or the banner has
    // already printed by the time this assignment runs.
    KotlinLoggingConfiguration.logStartupMessage = false
    when (val command = resolveCommand(args)) {
        CliCommand.StartMcpServer -> runBlocking { runStdioServer() }
        is CliCommand.Export -> runExport(command)
        is CliCommand.Render -> runRender(command)
        is CliCommand.ShowUsage -> {
            println(USAGE_TEXT)
            if (command.exitCode != 0) exitProcess(command.exitCode)
        }
    }
}

private const val SCRIPT_EXTENSION = ".kstep.kts"

// "bracket.kstep.kts" -> "bracket.step". A script path not ending in the conventional
// extension (unusual, but not rejected -- resolveCommand doesn't enforce it either) just gets
// ".step" appended to its full path instead, rather than guessing at a different split point.
private fun deriveOutputPath(scriptPath: String): String =
    if (scriptPath.endsWith(SCRIPT_EXTENSION)) {
        scriptPath.removeSuffix(SCRIPT_EXTENSION) + ".step"
    } else {
        "$scriptPath.step"
    }

private fun runExport(command: CliCommand.Export) {
    when (val outcome = KStepScriptHost.eval(File(command.scriptPath))) {
        is KStepScriptOutcome.Success -> writeExport(command, outcome)
        is KStepScriptOutcome.CompilationError -> printError(command.jsonOutput, outcome)
        is KStepScriptOutcome.NoModelProduced -> printError(command.jsonOutput, outcome)
        is KStepScriptOutcome.ValidationErrors -> printError(command.jsonOutput, outcome)
        is KStepScriptOutcome.RuntimeError -> printError(command.jsonOutput, outcome)
    }
}

// Part21Writer.write can still throw even for a script-level "valid" KStepModel -- e.g. a root
// that isn't one of the six supported entity types (Part21WriteException), a string field with
// a non-ASCII/control character (Part21EncodingException), or a pathologically deep reference
// graph (Part21LimitExceededException). All three are re-routed through the same RuntimeError
// rendering path used for a script-runtime exception (KSTEP-S-003) -- from the CLI caller's
// perspective, "the export step failed" is the same kind of outcome either way.
private fun writeExport(
    command: CliCommand.Export,
    outcome: KStepScriptOutcome.Success,
) {
    val text =
        try {
            Part21Writer.write(outcome.model.header, outcome.model.roots)
        } catch (e: Part21WriteException) {
            printError(command.jsonOutput, e.toRuntimeError())
        } catch (e: Part21EncodingException) {
            printError(command.jsonOutput, e.toRuntimeError())
        } catch (e: Part21LimitExceededException) {
            printError(command.jsonOutput, e.toRuntimeError())
        }
    val outPath = command.outPath ?: deriveOutputPath(command.scriptPath)
    File(outPath).writeText(text)
    val shapeCount = outcome.model.shapes.size
    if (shapeCount > 0) {
        // Geometry registered via shape(...) (kSTEP's headless-preview-rendering wave, see
        // docs/adr/ADR-0011-headless-preview-rendering.adoc) is NOT merged into the exported
        // Part-21 file this wave -- Part21Writer has no entity type for it yet (Ap242ShapeExporter
        // merging is Folge-Welle R-1). Warn rather than silently dropping it.
        System.err.println(
            "kstep export: model carries $shapeCount geometric shape(s); geometry merging is not " +
                "wired into 'export' yet -- see 'kstep render' for a preview.",
        )
    }
    if (command.jsonOutput) {
        println(
            buildJsonObject {
                put("status", "success")
                put("outPath", outPath)
                put("rootCount", outcome.model.roots.size)
                put("shapeCount", shapeCount)
            }.toString(),
        )
    } else {
        println("Exported ${outcome.model.roots.size} root(s) to $outPath")
    }
}

private fun Exception.toRuntimeError(): KStepScriptOutcome.RuntimeError =
    KStepScriptOutcome.RuntimeError(
        message = message ?: "export failed",
        exceptionClass = this::class.qualifiedName ?: "unknown",
    )

private fun printError(
    jsonOutput: Boolean,
    outcome: KStepScriptOutcome,
): Nothing = printExportError(jsonOutput, outcome, command = "export")

/**
 * Shared script-outcome error reporting for both `export` and `render` -- see
 * `RenderCommand.kt`'s own `printError` delegate. [command] is added to the `--output json`
 * document (`"command":"export"`/`"command":"render"`) so a consumer parsing the JSON can tell
 * which subcommand produced a given error document without also having to remember which CLI
 * invocation it came from.
 */
internal fun printExportError(
    jsonOutput: Boolean,
    outcome: KStepScriptOutcome,
    command: String,
): Nothing {
    if (jsonOutput) {
        val base = errorJson(outcome)
        println(
            buildJsonObject {
                base.forEach { (key, value) -> put(key, value) }
                put("command", command)
            }.toString(),
        )
    } else {
        println(errorText(outcome))
    }
    exitProcess(1)
}

private fun errorText(outcome: KStepScriptOutcome): String =
    when (outcome) {
        is KStepScriptOutcome.CompilationError ->
            "Script failed to compile:\n" +
                outcome.diagnostics.joinToString("\n") { d ->
                    val location = if (d.line != null) " (line ${d.line}, column ${d.column ?: "?"})" else ""
                    "  [${d.severity}]$location ${d.message}"
                }
        is KStepScriptOutcome.NoModelProduced -> "Script produced no exportable model: ${outcome.message}"
        is KStepScriptOutcome.ValidationErrors ->
            "Validation failed:\n" +
                outcome.violations.joinToString("\n") { "  [${it.code}] ${it.entityName}: ${it.message}" }
        is KStepScriptOutcome.RuntimeError -> "Script export failed (${outcome.exceptionClass}): ${outcome.message}"
        is KStepScriptOutcome.Success ->
            error("errorText must never be called with a Success outcome")
    }

private fun errorJson(outcome: KStepScriptOutcome): JsonObject =
    when (outcome) {
        is KStepScriptOutcome.CompilationError ->
            buildJsonObject {
                put("status", "error")
                put("errorKind", "compilation_error")
                put("code", KStepScriptOutcomeCodes.COMPILATION_ERROR)
                putJsonArray("diagnostics") {
                    outcome.diagnostics.forEach { d ->
                        add(
                            buildJsonObject {
                                put("severity", d.severity)
                                put("message", d.message)
                                put("line", d.line)
                                put("column", d.column)
                            },
                        )
                    }
                }
            }
        is KStepScriptOutcome.NoModelProduced ->
            buildJsonObject {
                put("status", "error")
                put("errorKind", "no_model_produced")
                put("code", KStepScriptOutcomeCodes.NO_MODEL_PRODUCED)
                put("message", outcome.message)
            }
        is KStepScriptOutcome.ValidationErrors ->
            buildJsonObject {
                put("status", "error")
                put("errorKind", "validation_failed")
                putJsonArray("violations") {
                    outcome.violations.forEach { v ->
                        add(
                            buildJsonObject {
                                put("code", v.code)
                                put("entityName", v.entityName)
                                put("ruleLabel", v.ruleLabel)
                                put("expressionText", v.expressionText)
                                put("message", v.message)
                            },
                        )
                    }
                }
            }
        is KStepScriptOutcome.RuntimeError ->
            buildJsonObject {
                put("status", "error")
                put("errorKind", "runtime_error")
                put("code", KStepScriptOutcomeCodes.RUNTIME_ERROR)
                put("message", outcome.message)
                put("exceptionClass", outcome.exceptionClass)
            }
        is KStepScriptOutcome.Success ->
            error("errorJson must never be called with a Success outcome")
    }
