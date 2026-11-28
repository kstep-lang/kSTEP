package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors

private const val V = "MCP-Protocol-Version: 2025-06-18"

class McpActionToolsHttpTest :
    StringSpec({
        fun call(
            port: Int,
            token: String?,
            name: String,
            args: String,
        ) = Raw.mcp(
            port,
            rpc("tools/call", params = """{"name":"$name","arguments":$args}"""),
            token,
            extra = listOf(V),
        )

        "tools/list shows the six action tools with a prefix text, and the read tools without it" {
            withDir { dir ->
                val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "http-ui").apply { isDaemon = true } }
                val host = FakeHost(snap())
                val actions = McpActionHost(McpUiBridge(executor.asCoroutineDispatcher(), { false }))
                val manager =
                    DesktopMcpServerManager(
                        McpPreferencesStore(dir),
                        McpTokenStore(dir),
                        host,
                        McpActivityTracker(),
                        actions,
                    )
                try {
                    manager.startFromSettings()
                    manager.setEnabled(true)
                    manager.awaitIdle()
                    val running = manager.status.value as McpServerStatus.Running
                    val token = manager.copyToken()!!
                    val list = Raw.mcp(running.port, rpc("tools/list"), token, extra = listOf(V)).json()
                    val tools =
                        list
                            .getValue("result")
                            .jsonObject
                            .getValue("tools")
                            .jsonArray
                            .map { it.jsonObject }
                    tools.size shouldBe 18
                    for (t in tools) {
                        val name = t.getValue("name").jsonPrimitive.content
                        val schema = t.getValue("inputSchema").jsonObject
                        schema.getValue("type").jsonPrimitive.content shouldBe "object"
                        schema.getValue("additionalProperties").jsonPrimitive.boolean shouldBe false
                        val description = t.getValue("description").jsonPrimitive.content
                        val acting =
                            name in
                                setOf(
                                    "kstep_desktop_set_script",
                                    "kstep_desktop_replace_in_script",
                                    "kstep_desktop_run",
                                    "kstep_desktop_select",
                                    "kstep_desktop_set_visibility",
                                    "kstep_desktop_show_in_script",
                                    "kstep_desktop_save",
                                    "kstep_desktop_list_recent",
                                    "kstep_desktop_open_recent",
                                    "kstep_desktop_export",
                                )
                        if (acting) {
                            val files =
                                name in
                                    setOf(
                                        "kstep_desktop_save",
                                        "kstep_desktop_list_recent",
                                        "kstep_desktop_open_recent",
                                        "kstep_desktop_export",
                                    )
                            description shouldStartWith
                                if (files) {
                                    "[Changes files;"
                                } else {
                                    "[Changes the app; requires 'Allow agents to make changes'.]"
                                }
                        } else {
                            description.contains("Changes the app") shouldBe false
                        }
                    }
                } finally {
                    manager.shutdown()
                    executor.shutdownNow()
                }
            }
        }

        "an action is refused until the user allows it, then it works, and without a token it is 401" {
            withDir { dir ->
                val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "http-ui").apply { isDaemon = true } }
                val fake = FakePort(text = "box(1)\n")
                val actions =
                    McpActionHost(
                        McpUiBridge(executor.asCoroutineDispatcher(), {
                            Thread.currentThread().name ==
                                "http-ui"
                        }),
                    )
                actions.port = fake
                val host = FakeHost(snap())
                val manager =
                    DesktopMcpServerManager(
                        McpPreferencesStore(dir),
                        McpTokenStore(dir),
                        host,
                        McpActivityTracker(),
                        actions,
                    )
                try {
                    manager.startFromSettings()
                    manager.setEnabled(true)
                    manager.awaitIdle()
                    val running = manager.status.value as McpServerStatus.Running
                    val token = manager.copyToken()!!
                    val args = """{"text":"box(2)\n","expectedRevision":"${McpAgentText.revisionOf("box(1)\n")}"}"""
                    val off =
                        call(
                            running.port,
                            token,
                            "kstep_desktop_set_script",
                            args,
                        ).json().getValue("result").jsonObject
                    off.getValue("isError").jsonPrimitive.boolean shouldBe true
                    off
                        .getValue("structuredContent")
                        .jsonObject
                        .getValue("code")
                        .jsonPrimitive.content shouldBe
                        "ACTIONS_DISABLED"
                    fake.text shouldBe "box(1)\n"
                    manager.setAllowActions(true) shouldBe ActionsChange.CHANGED
                    val on =
                        call(
                            running.port,
                            token,
                            "kstep_desktop_set_script",
                            args,
                        ).json().getValue("result").jsonObject
                    on.getValue("isError").jsonPrimitive.boolean shouldBe false
                    fake.text shouldBe "box(2)\n"
                    // switched off again: the very next call is refused, no restart needed
                    manager.setAllowActions(false) shouldBe ActionsChange.CHANGED
                    val again =
                        call(
                            running.port,
                            token,
                            "kstep_desktop_set_script",
                            """{"text":"box(3)\n","expectedRevision":"${McpAgentText.revisionOf("box(2)\n")}"}""",
                        ).json().getValue("result").jsonObject
                    again.getValue("isError").jsonPrimitive.boolean shouldBe true
                    fake.text shouldBe "box(2)\n"
                    call(running.port, null, "kstep_desktop_set_script", args).status shouldBe 401
                    // a request over 1 MiB is refused by the server before any tool sees it
                    manager.setAllowActions(true)
                    val big =
                        Raw.request(
                            running.port,
                            headers =
                                listOf(
                                    "Host: 127.0.0.1:${running.port}",
                                    "Authorization: Bearer $token",
                                    "Content-Type: application/json",
                                ),
                            body = "{}".toByteArray(),
                            declaredLength = (1 shl 20) + 1L,
                        )
                    big.status shouldBe 413
                    fake.text shouldBe "box(2)\n"
                } finally {
                    manager.shutdown()
                    executor.shutdownNow()
                }
            }
        }
    })
