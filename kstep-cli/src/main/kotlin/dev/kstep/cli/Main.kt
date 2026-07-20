package dev.kstep.cli

import dev.kstep.mcp.runStdioServer
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

// Deliberately plain println, not kotlin-logging (kSTEP M2 Welle 2 scope decision): this is a
// CLI's own stdout output -- the thing the user invoked the tool to see -- not diagnostic
// logging. Now that this module has real commands (M2 Welle 3: `kstep mcp`), the same reasoning
// still applies to USAGE_TEXT below. See kstep-mcp for this project's actual kotlin-logging usage.
const val USAGE_TEXT: String =
    """kSTEP CLI

Usage:
  kstep mcp     Start the kSTEP MCP server over stdio
  kstep help    Show this message
"""

sealed interface CliCommand {
    data object StartMcpServer : CliCommand

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
        else -> CliCommand.ShowUsage(exitCode = 1)
    }

fun main(args: Array<String>) {
    when (val command = resolveCommand(args)) {
        CliCommand.StartMcpServer -> runBlocking { runStdioServer() }
        is CliCommand.ShowUsage -> {
            println(USAGE_TEXT)
            if (command.exitCode != 0) exitProcess(command.exitCode)
        }
    }
}
