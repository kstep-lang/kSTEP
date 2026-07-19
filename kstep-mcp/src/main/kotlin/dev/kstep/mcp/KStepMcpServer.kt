package dev.kstep.mcp

import dev.kstep.mcp.tools.registerApprovalTool
import dev.kstep.mcp.tools.registerBuildNextAssemblyUsageOccurrenceTool
import dev.kstep.mcp.tools.registerBuildPersonAndOrganizationTool
import dev.kstep.mcp.tools.registerBuildProductDefinitionFormationTool
import dev.kstep.mcp.tools.registerBuildProductDefinitionTool
import dev.kstep.mcp.tools.registerBuildProductTool
import dev.kstep.mcp.tools.registerExportPart21Tool
import dev.kstep.mcp.tools.registerGetEntityTool
import dev.kstep.mcp.tools.registerListEntitiesTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

/**
 * Assembles the kSTEP MCP server: the six V1 entity builders as `build_*` tools, `export_part21`
 * (wrapping `kstep-step21`'s `Part21Writer` unmodified), and the `list_entities`/`get_entity`
 * introspection tools — all sharing one [EntityStore], scoped to this [Server] instance for its
 * process lifetime (one store per server, reset only on restart; see [EntityStore]'s own KDoc for
 * why that's the right session boundary given what the `kotlin-sdk` actually exposes here).
 */
fun buildServer(store: EntityStore = EntityStore()): Server {
    val server =
        Server(
            serverInfo = Implementation(name = "kstep-mcp", version = "0.1.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())),
        )
    registerBuildProductTool(server, store)
    registerBuildPersonAndOrganizationTool(server, store)
    registerBuildProductDefinitionFormationTool(server, store)
    registerBuildProductDefinitionTool(server, store)
    registerBuildNextAssemblyUsageOccurrenceTool(server, store)
    registerApprovalTool(server, store)
    registerExportPart21Tool(server, store)
    registerListEntitiesTool(server, store)
    registerGetEntityTool(server, store)
    return server
}

/**
 * Runs [server] over stdio until the session closes. `Server.createSession` returns as soon as
 * the transport is connected — its reader/processor/writer coroutines already run in the
 * background — so without the `Job()`/`onClose`/`join()` idiom below, this function (and the
 * process, if called from `main`) would return before a single message is ever read.
 */
suspend fun runStdioServer(server: Server = buildServer()) {
    val transport =
        StdioServerTransport(
            input = System.`in`.asSource().buffered(),
            output = System.out.asSink().buffered(),
        )
    val session = server.createSession(transport)
    val done = Job()
    session.onClose { done.complete() }
    done.join()
}

// Manual dev-time entry point only — no kstep-cli wiring this wave (M2 Welle 1 scope). A future
// `kstep mcp` subcommand only needs to call runStdioServer() from kstep-cli's Main.kt.
fun main() =
    runBlocking {
        runStdioServer()
    }
