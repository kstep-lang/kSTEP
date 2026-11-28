package dev.kstep.desktop.mcp

import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class McpActivityTrackerTest :
    StringSpec({
        "calls closer than 600 ms do not update, one at 600 ms does" {
            var now = 0L
            val tracker = McpActivityTracker { now }
            tracker.record()
            tracker.lastLitAtMs.value shouldBe 0L
            now = 100
            tracker.record()
            tracker.lastLitAtMs.value shouldBe 0L
            now = 599
            tracker.record()
            tracker.lastLitAtMs.value shouldBe 0L
            now = 600
            tracker.record()
            tracker.lastLitAtMs.value shouldBe 600L
        }

        "isLit is true for 600 ms and not before or after" {
            McpActivityTracker.isLit(1000, 999) shouldBe false
            McpActivityTracker.isLit(1000, 1000) shouldBe true
            McpActivityTracker.isLit(1000, 1599) shouldBe true
            McpActivityTracker.isLit(1000, 1600) shouldBe false
            McpActivityTracker.isLit(Long.MIN_VALUE / 2, 1_700_000_000_000L) shouldBe false
        }
    })

class McpUiBridgeTest :
    StringSpec({
        "the block's result comes back" {
            val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                McpUiBridge(ui, isUiThread = { false }).onUi { 42 } shouldBe 42
            } finally {
                ui.close()
            }
        }

        "a busy UI thread gives UI_BUSY within a second and the block never runs later" {
            val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            val release = CountDownLatch(1)
            val ran = AtomicBoolean(false)
            try {
                CoroutineScope(ui).launch { release.await() }
                Thread.sleep(100)
                val started = System.nanoTime()
                val e =
                    shouldThrow<McpToolException> { McpUiBridge(ui, { false }, timeoutMs = 50).onUi { ran.set(true) } }
                e.code shouldBe McpToolCodes.UI_BUSY
                ((System.nanoTime() - started) / 1_000_000 < 1000) shouldBe true
                release.countDown()
                Thread.sleep(200)
                ran.get() shouldBe false
            } finally {
                release.countDown()
                ui.close()
            }
        }

        "a call from the UI thread itself is refused" {
            val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
            try {
                shouldThrow<IllegalStateException> { McpUiBridge(ui, { true }).onUi { 1 } }
            } finally {
                ui.close()
            }
        }
    })

class McpDialogStateTest :
    StringSpec({
        val settings = McpSettings(true, 7391)

        "the file switch: locked without actions and a running server, off always possible, wording exact" {
            McpDialogText.FILES_SWITCH shouldBe "Allow agents to save and export files"
            McpDialogText.FILES_HINT shouldBe
                "Agents can save the open file, export to your last export folder and open recent files. Every file " +
                "action asks you first. Off after every restart."
            McpDialogText.FILES_LOCKED shouldBe "Allow agents to make changes first"
            val running = McpServerStatus.Running(7391, 7391)
            val acts = McpSettings(true, 7391, allowActions = true)
            mcpDialogState(running, acts, true, false).filesEnabled shouldBe true
            mcpDialogState(running, settings, true, false).filesEnabled shouldBe false
            mcpDialogState(running, settings, true, false).filesReason shouldBe McpDialogText.FILES_LOCKED
            mcpDialogState(McpServerStatus.Off, acts, true, false).filesEnabled shouldBe false
            mcpDialogState(McpServerStatus.Off, acts, true, false, allowFiles = true).filesEnabled shouldBe true
            mcpDialogState(running, acts, true, false, allowFiles = true).filesChecked shouldBe true
        }

        "the hint says it cannot save, export or open files, until the file switch is on" {
            McpDialogText.hint(true).contains("It cannot save, export or open files itself") shouldBe true
            McpDialogText.hint(true, true).contains("It cannot save, export or open files itself") shouldBe false
            McpDialogText.hint(true, true).contains("only after you agreed in a dialog each time") shouldBe true
        }

        "the status texts" {
            mcpDialogState(McpServerStatus.Off, settings, false, false).statusText shouldBe "Off"
            mcpDialogState(McpServerStatus.Running(7391, 7391), settings, true, false).statusText shouldBe
                "Running on 127.0.0.1:7391"
            mcpDialogState(McpServerStatus.Running(7392, 7391), settings, true, false).statusText shouldBe
                "Port 7391 was busy — running on 7392."
            mcpDialogState(McpServerStatus.NoFreePort(7391, 7400), settings, true, false).statusText shouldBe
                "No free port between 7391 and 7400 (KSTEP-D-050)"
            mcpDialogState(McpServerStatus.TokenUnavailable, settings, false, false).statusText shouldBe
                "Could not store the token (KSTEP-D-051)"
            mcpDialogState(McpServerStatus.Off, settings, true, true).warningText shouldBe
                "Settings could not be saved (KSTEP-D-052)"
            mcpDialogState(McpServerStatus.Off, settings, true, false).warningText shouldBe null
        }

        "the hint names what an agent can read and says it cannot change anything while changes are off" {
            val hint = McpDialogText.hint(false)
            hint.endsWith("Changes are turned off.") shouldBe true
            listOf("127.0.0.1", "token", "errors", "parts", "selection", "cannot change anything").forEach {
                hint shouldContain it
            }
            hint shouldNotContain "only to read the status and the editor text"
        }

        "the hint with changes allowed says what may change and never claims it cannot change anything" {
            val hint = McpDialogText.hint(true)
            hint shouldNotContain "cannot change anything"
            listOf("editor text", "runs", "selection", "visibility", "cannot save").forEach { hint shouldContain it }
        }

        "the permission switch is locked while the server is off but a saved permission can be taken back" {
            val off = mcpDialogState(McpServerStatus.Off, McpSettings(), false, false)
            off.actionsEnabled shouldBe false
            off.actionsChecked shouldBe false
            off.actionsReason shouldBe McpDialogText.ACTIONS_LOCKED
            val offButOn = mcpDialogState(McpServerStatus.Off, McpSettings(allowActions = true), false, false)
            offButOn.actionsChecked shouldBe true
            offButOn.actionsEnabled shouldBe true
            offButOn.actionsReason shouldBe null
            val running = mcpDialogState(McpServerStatus.Running(7391, 7391), McpSettings(), true, false)
            running.actionsEnabled shouldBe true
            running.actionsReason shouldBe null
            listOf(McpServerStatus.NoFreePort(1, 2), McpServerStatus.TokenUnavailable).forEach {
                mcpDialogState(it, McpSettings(), true, false).actionsEnabled shouldBe false
            }
        }

        "the port is editable only while the server is not running" {
            mcpDialogState(McpServerStatus.Off, settings, true, false).portEditable shouldBe true
            mcpDialogState(McpServerStatus.NoFreePort(1, 2), settings, true, false).portEditable shouldBe true
            val running = mcpDialogState(McpServerStatus.Running(7391, 7391), settings, true, false)
            running.portEditable shouldBe false
            running.portReason shouldBe "Turn the server off to change the port"
        }

        "use-this-port is offered only after a fallback" {
            mcpDialogState(McpServerStatus.Running(7391, 7391), settings, true, false).useActualPortLabel shouldBe null
            mcpDialogState(McpServerStatus.Running(7393, 7391), settings, true, false).useActualPortLabel shouldBe
                "Use 7393 from now on"
        }

        "copy is disabled with a reason while there is no token" {
            val none = mcpDialogState(McpServerStatus.Off, settings, false, false)
            none.copyEnabled shouldBe false
            none.copyReason shouldBe McpDialogText.NO_TOKEN
            mcpDialogState(McpServerStatus.Off, settings, true, false).copyEnabled shouldBe true
        }

        "the preview carries the literal <token> and the real port; the command carries the real token" {
            val preview = mcpDialogState(McpServerStatus.Running(7392, 7391), settings, true, false).commandPreview
            preview shouldContain "http://127.0.0.1:7392/mcp"
            preview shouldContain "Bearer <token>"
            withDir { dir ->
                val token = McpTokenStore(dir).getOrCreate()
                val text = McpCommand.text(7392, token)
                text shouldContain "Bearer ${token.value}"
                text shouldContain "claude mcp add --transport http kstep-desktop http://127.0.0.1:7392/mcp"
                text shouldNotContain "<token>"
            }
        }

        "parsePortInput accepts only trimmed digits in 1024..65535" {
            parsePortInput("  7391 ") shouldBe 7391
            parsePortInput("1024") shouldBe 1024
            parsePortInput("65535") shouldBe 65535
            listOf("1023", "65536", "7391a", "-1", "", "  ", "0x1F90", "７３９１", "007391999").forEach {
                parsePortInput(it) shouldBe null
            }
        }

        "the error codes exist and are distinct" {
            listOf(
                DesktopErrorCodes.MCP_NO_FREE_PORT,
                DesktopErrorCodes.MCP_TOKEN_WRITE_FAILED,
                DesktopErrorCodes.MCP_SETTINGS_WRITE_FAILED,
            ) shouldBe listOf("KSTEP-D-050", "KSTEP-D-051", "KSTEP-D-052")
        }
    })
