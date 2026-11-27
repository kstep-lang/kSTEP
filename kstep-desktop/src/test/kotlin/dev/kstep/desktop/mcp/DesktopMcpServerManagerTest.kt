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
) {
    val prefs = McpPreferencesStore(dir)
    val tokens = McpTokenStore(dir)
    val dir = dir
    val manager: DesktopMcpServerManager

    init {
        initial?.let { prefs.save(it) }
        manager = DesktopMcpServerManager(prefs, tokens, FakeHost(snap()), McpActivityTracker())
    }

    fun settle() = manager.awaitIdle()
}

class DesktopMcpServerManagerTest :
    StringSpec({
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
    })
