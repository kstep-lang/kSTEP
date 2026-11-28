package dev.kstep.desktop.ui

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportPreferencesStore
import dev.kstep.desktop.export.ExportWorkerResult
import dev.kstep.desktop.mcp.ActionsChange
import dev.kstep.desktop.mcp.DesktopMcpServerManager
import dev.kstep.desktop.mcp.FakeHost
import dev.kstep.desktop.mcp.FilesChange
import dev.kstep.desktop.mcp.McpActionHost
import dev.kstep.desktop.mcp.McpActivityTracker
import dev.kstep.desktop.mcp.McpAgentText
import dev.kstep.desktop.mcp.McpConfirmDecision
import dev.kstep.desktop.mcp.McpPreferencesStore
import dev.kstep.desktop.mcp.McpServerStatus
import dev.kstep.desktop.mcp.McpSettings
import dev.kstep.desktop.mcp.McpTokenStore
import dev.kstep.desktop.mcp.McpUiBridge
import dev.kstep.desktop.mcp.Raw
import dev.kstep.desktop.mcp.body
import dev.kstep.desktop.mcp.code
import dev.kstep.desktop.mcp.isError
import dev.kstep.desktop.mcp.rpc
import dev.kstep.desktop.mcp.snap
import dev.kstep.desktop.menu.MenuContext
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors

private const val V = "MCP-Protocol-Version: 2025-06-18"
private const val UI = "e2e-ui"

/**
 * The file tools over real HTTP (ADR-0098): the real server, the real bridge to a one-thread "UI", the real file port,
 * the real editor session, the real gate and the real export controller (with a worker that makes a valid file). A
 * thread plays the user: it answers each dialog.
 */
class McpFileActionsEndToEndHttpTest :
    StringSpec({
        class Rig {
            val dir: Path = TestSupport.newDir()
            val script: Path = TestSupport.script(dir, "e2e", "box(1)\n")
            val out: Path = Files.createDirectory(dir.resolve("out"))
            val config: Path = Files.createDirectory(dir.resolve("config"))
            val session = EditorSession()
            val executor = Executors.newSingleThreadExecutor { r -> Thread(r, UI).apply { isDaemon = true } }
            val actions =
                McpActionHost(
                    McpUiBridge(executor.asCoroutineDispatcher(), {
                        Thread.currentThread().name ==
                            UI
                    }, 5000),
                )
            val exporter =
                ExportController(
                    worker = { _, _, gen, _ ->
                        ExportWorkerResult.Produced(
                            Files.write(dir.resolve("w-$gen.step"), "ISO-10303-21;\nDATA;".toByteArray()),
                        )
                    },
                )
            val prefs = ExportPreferencesStore(config)
            val port = ServerSocket(0).use { it.localPort }
            val manager =
                DesktopMcpServerManager(
                    McpPreferencesStore(config).also { it.save(McpSettings(true, port, allowActions = true)) },
                    McpTokenStore(config),
                    FakeHost(snap()),
                    McpActivityTracker(),
                    actions,
                )

            @Volatile var answer: McpConfirmDecision? = McpConfirmDecision.ALLOW

            @Volatile private var alive = true
            val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
            var token: String = ""

            init {
                session.open(script)
                check(TestSupport.await(30_000) { session.view.value.state is EditorState.Editing })
                actions.filePort =
                    DesktopMcpFilePort(
                        session = session,
                        menuContext = {
                            MenuContext(
                                scriptOpen = true,
                                knowledgeOpen = false,
                                exportEnabled = true,
                                fileOpen = true,
                                editorIsScript = true,
                                editorDirty = session.view.value.dirty,
                            )
                        },
                        knowledgeOpen = { false },
                        workspaceActive = { false },
                        recent = {
                            dev.kstep.desktop.recent.RecentList
                                .empty()
                        },
                        openPath = { true },
                        forgetRecent = {},
                        exporter = exporter,
                        exportPrefs = prefs,
                        exportDialogOpen = { false },
                        converting = { false },
                    )
                Thread {
                    var last = -1L
                    while (alive) {
                        val request = actions.confirmations.pending.value
                        if (request != null && request.id != last) {
                            last = request.id
                            asked += request.action.name
                            answer?.let { actions.confirmations.resolve(request.id, it) }
                        }
                        Thread.sleep(2)
                    }
                }.apply {
                    isDaemon = true
                    start()
                }
                manager.startFromSettings()
                manager.awaitIdle()
                check(manager.status.value is McpServerStatus.Running)
                token = manager.copyToken()!!
            }

            fun tool(
                name: String,
                args: String,
            ): JsonObject {
                Thread.sleep(1100) // stay under the call rate limit and the run spacing
                return Raw
                    .mcp(
                        (manager.status.value as McpServerStatus.Running).port,
                        rpc("tools/call", params = """{"name":"$name","arguments":$args}"""),
                        token,
                        extra = listOf(V),
                    ).json()
                    .getValue("result")
                    .jsonObject
            }

            fun close() {
                alive = false
                manager.shutdown()
                exporter.close()
                session.close()
                executor.shutdownNow()
                dir.toFile().deleteRecursively()
            }
        }

        fun withRig(block: (Rig) -> Unit) {
            val rig = Rig()
            try {
                block(rig)
            } finally {
                rig.close()
            }
        }

        "no token is 401, and the file tools are refused until the user turns the third switch on" {
            withRig { rig ->
                val p = (rig.manager.status.value as McpServerStatus.Running).port
                Raw.mcp(p, rpc("tools/list"), null, extra = listOf(V)).status shouldBe 401
                rig.tool("kstep_desktop_list_recent", "{}").code shouldBe "FILES_DISABLED"
                rig.manager.setAllowFiles(true) shouldBe FilesChange.CHANGED
                rig.tool("kstep_desktop_list_recent", "{}").isError shouldBe false
            }
        }

        "save: allow changes the file on the disk and cleans the buffer; deny leaves everything as it was" {
            withRig { rig ->
                rig.manager.setAllowFiles(true)
                rig.session.onTextChanged("box(2)\n", 7)
                val rev = McpAgentText.revisionOf("box(2)\n")
                rig.answer = McpConfirmDecision.DENY
                val denied = rig.tool("kstep_desktop_save", """{"expectedRevision":"$rev"}""")
                denied.code shouldBe "DENIED"
                Files.readString(rig.script) shouldBe "box(1)\n"
                rig.session.view.value.dirty shouldBe true
                // the cool-down of a denial: wait it out by asking after it (the real gate uses real time)
                Thread.sleep(10_500)
                rig.answer = McpConfirmDecision.ALLOW
                val saved = rig.tool("kstep_desktop_save", """{"expectedRevision":"$rev"}""")
                saved.isError shouldBe false
                saved.body
                    .getValue("saved")
                    .jsonPrimitive.boolean shouldBe true
                Files.readString(rig.script) shouldBe "box(2)\n"
                rig.session.view.value.dirty shouldBe false
                saved.toString() shouldNotContain rig.dir.toString()
            }
        }

        "export: a new file in the remembered folder, a second call makes a -2 file, no absolute path in the answer" {
            withRig { rig ->
                rig.manager.setAllowFiles(true)
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                val rev = McpAgentText.revisionOf("box(1)\n")
                val before = Files.list(rig.out).use { it.count() }
                before shouldBe 0
                val first = rig.tool("kstep_desktop_export", """{"format":"step","expectedRevision":"$rev"}""")
                first.isError shouldBe false
                first.body
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "e2e.step"
                first.toString() shouldNotContain rig.dir.toString()
                Files.readString(rig.out.resolve("e2e.step")) shouldBe "ISO-10303-21;\nDATA;"
                val second = rig.tool("kstep_desktop_export", """{"format":"step","expectedRevision":"$rev"}""")
                second.body
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "e2e-2.step"
                Files.readString(rig.out.resolve("e2e.step")) shouldBe "ISO-10303-21;\nDATA;"
                rig.asked shouldBe listOf("EXPORT", "EXPORT")
                // the user's remembered format is the one they chose, not the agent's
                rig.prefs.load().lastFormat shouldBe ExportFormat.STEP
            }
        }

        "export: a deny creates nothing in the folder" {
            withRig { rig ->
                rig.manager.setAllowFiles(true)
                rig.prefs.remember(ExportFormat.STEP, rig.out)
                rig.answer = McpConfirmDecision.DENY
                val r =
                    rig.tool(
                        "kstep_desktop_export",
                        """{"format":"stl","expectedRevision":"${McpAgentText.revisionOf("box(1)\n")}"}""",
                    )
                r.code shouldBe "DENIED"
                Files.list(rig.out).use { it.count() } shouldBe 0
            }
        }

        "actions off takes the file tools away at once, even while the server runs" {
            withRig { rig ->
                rig.manager.setAllowFiles(true)
                rig.manager.setAllowActions(false) shouldBe ActionsChange.CHANGED
                rig.tool("kstep_desktop_list_recent", "{}").code shouldBe "ACTIONS_DISABLED"
            }
        }
    })
