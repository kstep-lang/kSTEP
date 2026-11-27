package dev.kstep.desktop.mcp

import dev.kstep.desktop.pick.SelectionState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val CALLS =
    listOf(
        "kstep_desktop_get_errors" to "{}",
        "kstep_desktop_get_parts" to """{"limit":2}""",
        "kstep_desktop_get_assembly_tree" to "{}",
        "kstep_desktop_get_selection" to "{}",
        "kstep_desktop_get_view" to "{}",
        "kstep_desktop_find_in_script" to """{"query":"box"}""",
    )

private fun <T> serve(
    host: FakeHost,
    block: (port: Int, token: String) -> T,
): T =
    withDir { dir ->
        val token = McpTokenStore(dir).getOrCreate()
        val server =
            DesktopMcpHttpServer(McpProtocol(McpToolRegistry(host), host.appVersion), { token }, McpActivityTracker())
        try {
            block(server.start(0), token.value)
        } finally {
            server.stop()
        }
    }

class McpReadToolsHttpTest :
    StringSpec({
        val host =
            FakeHost(
                snap(
                    text = "box(1)\nbox(2)\n",
                    scene = sceneOf("a", "b", "c"),
                    selection = SelectionState(selected = "b"),
                ),
            )

        "every read tool answers over the real server, with structuredContent on 2025-06-18" {
            serve(host) { port, token ->
                for ((name, args) in CALLS) {
                    val r =
                        Raw.mcp(
                            port,
                            rpc("tools/call", params = """{"name":"$name","arguments":$args}"""),
                            token,
                            extra = listOf("MCP-Protocol-Version: 2025-06-18"),
                        )
                    r.status shouldBe 200
                    val result = r.json().getValue("result").jsonObject
                    result.getValue("isError").jsonPrimitive.boolean shouldBe false
                    val body = result.getValue("structuredContent").jsonObject
                    body.containsKey("snapshotRevision") shouldBe true
                    (r.body.toByteArray().size <= (1 shl 20)) shouldBe true
                }
            }
        }

        "every read tool answers on the old protocol with text only" {
            serve(host) { port, token ->
                for ((name, args) in CALLS) {
                    val r =
                        Raw.mcp(
                            port,
                            rpc("tools/call", params = """{"name":"$name","arguments":$args}"""),
                            token,
                            extra = listOf("MCP-Protocol-Version: 2025-03-26"),
                        )
                    val result: JsonObject = r.json().getValue("result").jsonObject
                    result.containsKey("structuredContent") shouldBe false
                    result.getValue("content").jsonArray.size shouldBe 1
                    result.getValue("isError").jsonPrimitive.boolean shouldBe false
                }
            }
        }

        "bad arguments over the server are a tool error, not a JSON-RPC error" {
            serve(host) { port, token ->
                val r =
                    Raw.mcp(
                        port,
                        rpc(
                            "tools/call",
                            params = """{"name":"kstep_desktop_find_in_script","arguments":{"query":1}}""",
                        ),
                        token,
                        extra = listOf("MCP-Protocol-Version: 2025-06-18"),
                    )
                val result = r.json().getValue("result").jsonObject
                result.getValue("isError").jsonPrimitive.boolean shouldBe true
                result
                    .getValue("structuredContent")
                    .jsonObject
                    .getValue("code")
                    .jsonPrimitive.content shouldBe "INVALID_ARGUMENTS"
            }
        }
    })
