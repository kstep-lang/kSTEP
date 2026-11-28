package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/** A free port with the next ten free as well; tests never use a fixed port (the real app may be running). */
private fun freeBase(): Int {
    repeat(50) {
        val base = ServerSocket(0).use { it.localPort }
        if (base + 10 > 65535) return@repeat
        val free =
            (base..base + 10).all { p ->
                try {
                    ServerSocket(p, 1, loopback).use { true }
                } catch (_: Exception) {
                    false
                }
            }
        if (free) return base
    }
    error("no free port range found")
}

private fun occupy(
    from: Int,
    to: Int,
): List<ServerSocket> = (from..to).map { ServerSocket(it, 1, loopback) }

private fun reachable(port: Int): Boolean =
    try {
        Socket().use { it.connect(InetSocketAddress(loopback, port), 500) }
        true
    } catch (_: Exception) {
        false
    }

private class Rig(
    dir: Path,
    initial: McpSettings? = null,
    val activity: McpActivityTracker = McpActivityTracker(),
) {
    val prefs = McpPreferencesStore(dir)
    val tokens = McpTokenStore(dir)
    val dir = dir
    val manager: DesktopMcpServerManager

    init {
        initial?.let { prefs.save(it) }
        manager = DesktopMcpServerManager(prefs, tokens, FakeHost(snap()), activity)
    }

    fun settle() = manager.awaitIdle()
}

class DesktopMcpServerManagerTest :
    StringSpec({
        "a call is counted by the real server and stopping the server clears the activity" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.manager.setEnabled(true)
                    rig.settle()
                    val token = rig.manager.copyToken()!!
                    Raw.mcp(base, rpc("ping"), token).status shouldBe 200
                    rig.activity.state.value.finished shouldBe 1L
                    rig.activity.state.value.recent
                        .single()
                        .outcome shouldBe McpCallOutcome.Ok
                    rig.manager.setEnabled(false)
                    rig.settle()
                    rig.activity.state.value shouldBe McpActivity(finished = 1L)
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "by default nothing listens and no token file exists" {
            withDir { dir ->
                val rig = Rig(dir)
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.Off
                    Files.exists(dir.resolve(McpTokenStore.FILE_NAME)) shouldBe false
                    Files.exists(dir.resolve(McpPreferencesStore.FILE_NAME)) shouldBe false
                    rig.manager.copyCommand() shouldBe null
                    rig.manager.copyToken() shouldBe null
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "switching on starts the server, makes the token and saves the setting" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.Running(base, base)
                    rig.prefs.load() shouldBe McpSettings(true, base)
                    Files.exists(dir.resolve(McpTokenStore.FILE_NAME)) shouldBe true
                    rig.manager.hasToken.value shouldBe true
                    val token = rig.manager.copyToken()!!
                    Raw.mcp(base, rpc("ping"), token).status shouldBe 200
                    rig.manager.copyCommand()!!.contains("Bearer $token") shouldBe true
                    rig.manager.setEnabled(false)
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.Off
                    reachable(base) shouldBe false
                    rig.prefs.load().enabled shouldBe false
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "a fast allowActions change is not overwritten by the delayed save of setEnabled" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base, allowActions = true))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.setEnabled(false)
                    rig.manager.setAllowActions(false)
                    rig.settle()
                    rig.prefs.load() shouldBe McpSettings(false, base, allowActions = false)
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "setAllowFiles needs a running server and the permission for changes; off always works" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.setAllowFiles(true) shouldBe FilesChange.ACTIONS_OFF
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.setAllowFiles(true) shouldBe FilesChange.ACTIONS_OFF
                    rig.manager.setAllowActions(true) shouldBe ActionsChange.CHANGED
                    rig.manager.setAllowFiles(true) shouldBe FilesChange.CHANGED
                    rig.manager.allowFiles.value shouldBe true
                    rig.manager.setAllowFiles(false) shouldBe FilesChange.CHANGED
                    rig.manager.allowFiles.value shouldBe false
                    rig.manager.setAllowActions(false)
                    rig.manager.setEnabled(false)
                    rig.settle()
                    rig.manager.setAllowActions(true) shouldBe ActionsChange.SERVER_NOT_RUNNING
                    rig.manager.setAllowFiles(true) shouldBe FilesChange.ACTIONS_OFF
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "the file permission goes off with actions off, a new token, switching off, and is never restored" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base, allowActions = true))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    val on = { rig.manager.setAllowFiles(true) shouldBe FilesChange.CHANGED }
                    on()
                    rig.manager.setAllowActions(false)
                    rig.manager.allowFiles.value shouldBe false
                    rig.manager.setAllowActions(true)
                    on()
                    rig.manager.regenerateToken() shouldBe true
                    rig.manager.allowFiles.value shouldBe false
                    on()
                    rig.manager.setEnabled(false)
                    rig.settle()
                    rig.manager.allowFiles.value shouldBe false
                    rig.manager.setEnabled(true)
                    rig.settle()
                    on()
                } finally {
                    rig.manager.shutdown()
                }
                // a new manager on the same file, even one that says true by hand, starts with the permission off
                Files.writeString(
                    dir.resolve(McpPreferencesStore.FILE_NAME),
                    """{"version":1,"enabled":true,"port":$base,"allowActions":true,"allowFiles":true}""",
                )
                val again = Rig(dir)
                try {
                    again.manager.startFromSettings()
                    again.settle()
                    again.manager.allowFiles.value shouldBe false
                } finally {
                    again.manager.shutdown()
                }
            }
        }

        "an open confirmation ends with FILES_DISABLED when the permission is taken back" {
            withDir { dir ->
                val base = freeBase()
                val gate = McpConfirmationGate(FakeConfirmScheduler(), System::currentTimeMillis)
                val host =
                    McpActionHost(McpUiBridge(kotlinx.coroutines.Dispatchers.Default, { false }), gate)
                val manager =
                    DesktopMcpServerManager(
                        McpPreferencesStore(dir).also { it.save(McpSettings(true, base, allowActions = true)) },
                        McpTokenStore(dir),
                        FakeHost(snap()),
                        McpActivityTracker(),
                        host,
                    )
                try {
                    manager.startFromSettings()
                    manager.awaitIdle()
                    manager.setAllowFiles(true) shouldBe FilesChange.CHANGED
                    val answer =
                        java.util.concurrent.CompletableFuture.supplyAsync {
                            gate.request { McpConfirmRequest(it, McpFileAction.SAVE, "a", "b", "c", 0) }
                        }
                    while (gate.pending.value == null) Thread.sleep(2)
                    manager.setAllowFiles(false)
                    val outcome = answer.get(30, java.util.concurrent.TimeUnit.SECONDS)
                    (outcome as McpConfirmOutcome.Cancelled).code shouldBe McpToolCodes.FILES_DISABLED
                } finally {
                    manager.shutdown()
                }
            }
        }

        "two threads switching the permissions leave the last value on the disk" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    val t1 = Thread { repeat(200) { rig.manager.setAllowActions(it % 2 == 0) } }
                    val t2 = Thread { repeat(200) { rig.manager.setAllowFiles(it % 2 == 0) } }
                    t1.start()
                    t2.start()
                    t1.join()
                    t2.join()
                    rig.manager.setAllowActions(true)
                    rig.manager.setAllowActions(false)
                    rig.prefs.load().allowActions shouldBe false
                    rig.manager.allowFiles.value shouldBe false
                    rig.manager.settingsState.value.allowActions shouldBe false
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "the saved setting starts the server at app start" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.Running(base, base)
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "a busy port falls back to the next one without changing the setting; adopting stores it" {
            withDir { dir ->
                val base = freeBase()
                val blockers = occupy(base, base)
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.Running(base + 1, base)
                    rig.prefs.load().port shouldBe base
                    rig.manager.adoptActualPort()
                    rig.prefs.load().port shouldBe base + 1
                    rig.manager.status.value shouldBe McpServerStatus.Running(base + 1, base + 1)
                } finally {
                    rig.manager.shutdown()
                    blockers.forEach { it.close() }
                }
            }
        }

        "when all ten ports are busy the status says so" {
            withDir { dir ->
                val base = freeBase()
                val blockers = occupy(base, base + 9)
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.status.value shouldBe McpServerStatus.NoFreePort(base, base + 9)
                } finally {
                    rig.manager.shutdown()
                    blockers.forEach { it.close() }
                }
            }
        }

        "the port can only be changed while the server is not running, and only within the range" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.setPort(80) shouldBe PortChange.OUT_OF_RANGE
                    rig.manager.setPort(70000) shouldBe PortChange.OUT_OF_RANGE
                    rig.manager.setPort(base + 2) shouldBe PortChange.CHANGED
                    rig.prefs.load().port shouldBe base + 2
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.setPort(base + 5) shouldBe PortChange.SERVER_RUNNING
                    rig.prefs.load().port shouldBe base + 2
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "regenerating the token cuts off the old one without a restart" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    val old = rig.manager.copyToken()!!
                    rig.manager.regenerateToken() shouldBe true
                    val fresh = rig.manager.copyToken()!!
                    (old == fresh) shouldBe false
                    Raw.mcp(base, rpc("ping"), old).status shouldBe 401
                    Raw.mcp(base, rpc("ping"), fresh).status shouldBe 200
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "a token that cannot be stored keeps the server off with TokenUnavailable" {
            withDir { dir ->
                if ("posix" in dir.fileSystem.supportedFileAttributeViews() &&
                    System.getProperty("user.name") != "root"
                ) {
                    val rig = Rig(dir, McpSettings(true, freeBase()))
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"))
                    try {
                        rig.manager.startFromSettings()
                        rig.settle()
                        rig.manager.status.value shouldBe McpServerStatus.TokenUnavailable
                    } finally {
                        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                        rig.manager.shutdown()
                    }
                }
            }
        }

        "shutdown is idempotent, stops the server, and a later switch-on does nothing" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base))
                rig.manager.startFromSettings()
                rig.settle()
                rig.manager.status.value shouldBe McpServerStatus.Running(base, base)
                rig.manager.shutdown()
                rig.manager.shutdown()
                reachable(base) shouldBe false
                rig.manager.status.value shouldBe McpServerStatus.Off
                rig.manager.setEnabled(true)
                Thread.sleep(200)
                reachable(base) shouldBe false
                McpShutdown.register(rig.manager)
                McpShutdown.stopAll()
            }
        }

        "the permission to make changes is off by default and can only be turned on while the server runs" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(false, base))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.allowActions.value shouldBe false
                    rig.manager.setAllowActions(true) shouldBe ActionsChange.SERVER_NOT_RUNNING
                    rig.manager.allowActions.value shouldBe false
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.setAllowActions(true) shouldBe ActionsChange.CHANGED
                    rig.manager.allowActions.value shouldBe true
                    rig.prefs.load().allowActions shouldBe true
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "the permission can always be turned off, also while the server is off" {
            withDir { dir ->
                val rig = Rig(dir, McpSettings(false, freeBase(), true))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.allowActions.value shouldBe true
                    rig.manager.setAllowActions(false) shouldBe ActionsChange.CHANGED
                    rig.prefs.load().allowActions shouldBe false
                } finally {
                    rig.manager.shutdown()
                }
            }
        }

        "the permission survives switching the server, regenerating the token and a restart" {
            withDir { dir ->
                val base = freeBase()
                val rig = Rig(dir, McpSettings(true, base, true))
                try {
                    rig.manager.startFromSettings()
                    rig.settle()
                    rig.manager.setEnabled(false)
                    rig.settle()
                    rig.prefs.load() shouldBe McpSettings(false, base, true)
                    rig.manager.setEnabled(true)
                    rig.settle()
                    rig.manager.regenerateToken() shouldBe true
                    rig.manager.adoptActualPort()
                    rig.prefs.load() shouldBe McpSettings(true, base, true)
                    rig.manager.allowActions.value shouldBe true
                } finally {
                    rig.manager.shutdown()
                }
                val again = Rig(dir)
                try {
                    again.manager.startFromSettings()
                    again.settle()
                    again.manager.allowActions.value shouldBe true
                } finally {
                    again.manager.shutdown()
                }
            }
        }
    })
