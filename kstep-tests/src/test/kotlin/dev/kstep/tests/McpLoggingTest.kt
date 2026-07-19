package dev.kstep.tests

import dev.kstep.mcp.buildServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.ByteArrayOutputStream
import java.io.PrintStream

@OptIn(ExperimentalMcpApi::class)
private suspend fun connectedClient(server: Server): Client {
    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
    server.createSession(serverTransport)
    val client = Client(clientInfo = Implementation(name = "kstep-mcp-logging-test-client", version = "test"))
    client.connect(clientTransport)
    return client
}

// slf4j-simple's zero-config output choice re-reads System.err on every call instead of caching
// it at class-init time, so this swap genuinely captures one call's output -- see kSTEP M2 Welle 2
// plan pitfall on OutputChoice caching before adding a simplelogger.properties file, which would
// silently break this technique.
@OptIn(ExperimentalMcpApi::class)
class McpLoggingTest :
    StringSpec({
        "a structured tool error is actually emitted through the configured SLF4J backend" {
            val originalErr = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured, true, Charsets.UTF_8))
            try {
                val client = connectedClient(buildServer())
                client.callTool("get_entity", mapOf("id" to "does-not-exist"))
            } finally {
                System.setErr(originalErr)
            }

            val output = captured.toString(Charsets.UTF_8)
            output shouldContain "get_entity"
            output shouldContain "unknown_reference"
        }
    })
