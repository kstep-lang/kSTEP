package dev.kstep.desktop.mcp

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.BindException

private val logger = KotlinLogging.logger {}

/** What the server is doing (ADR-0095). */
sealed interface McpServerStatus {
    data object Off : McpServerStatus

    /** [port] is where it listens, [preferred] what the settings ask for; they differ when the port was busy. */
    data class Running(
        val port: Int,
        val preferred: Int,
    ) : McpServerStatus

    /** `KSTEP-D-050`: none of the ports [from]..[to] could be bound. */
    data class NoFreePort(
        val from: Int,
        val to: Int,
    ) : McpServerStatus

    /** `KSTEP-D-051`: the token could not be stored, so the server is not started. */
    data object TokenUnavailable : McpServerStatus
}

/** The outcome of [DesktopMcpServerManager.setPort]. */
enum class PortChange { CHANGED, OUT_OF_RANGE, SERVER_RUNNING, SAVE_FAILED }

/**
 * Owns the server of one window (ADR-0095): off unless the settings say on, started and stopped off the UI thread (all
 * work runs one after the other on one IO thread), never changes the stored port by itself. When the preferred port is
 * busy it tries the next nine; the dialog then offers to adopt the port that worked.
 */
class DesktopMcpServerManager(
    private val preferences: McpPreferencesStore,
    private val tokens: McpTokenStore,
    private val host: McpHost,
    private val activity: McpActivityTracker,
    private val serverFactory: (token: () -> McpToken?) -> DesktopMcpServerHandle =
        { token ->
            DesktopMcpServerHandle.of(
                DesktopMcpHttpServer(McpProtocol(McpToolRegistry(host), host.appVersion), token, activity),
            )
        },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val lock = Any()
    private var running: DesktopMcpServerHandle? = null

    @Volatile private var tokenRef: McpToken? = null

    @Volatile private var closed = false

    private val statusFlow = MutableStateFlow<McpServerStatus>(McpServerStatus.Off)
    private val settingsFlow = MutableStateFlow(McpSettings())
    private val saveFailedFlow = MutableStateFlow(false)
    private val hasTokenFlow = MutableStateFlow(false)

    val status: StateFlow<McpServerStatus> get() = statusFlow
    val settingsState: StateFlow<McpSettings> get() = settingsFlow

    /** `KSTEP-D-052`: the last attempt to save the settings failed. */
    val lastSaveFailed: StateFlow<Boolean> get() = saveFailedFlow
    val hasToken: StateFlow<Boolean> get() = hasTokenFlow

    /**
     * At app start: loads the settings (a 1 KiB file, read at once so that no click can see stale defaults) and
     * starts the server, off the UI thread, only when they say on.
     */
    fun startFromSettings() {
        val loaded = preferences.load()
        settingsFlow.value = loaded
        scope.launch {
            hasTokenFlow.value = tokens.current() != null
            if (loaded.enabled) doStart(loaded.port)
        }
    }

    fun setEnabled(on: Boolean) {
        val next = settingsFlow.value.copy(enabled = on)
        settingsFlow.value = next
        scope.launch {
            saveFailedFlow.value = !preferences.save(next)
            if (on) doStart(next.port) else doStop()
        }
    }

    /** Only while the server does not run. Saves the new port. */
    fun setPort(port: Int): PortChange {
        if (statusFlow.value is McpServerStatus.Running) return PortChange.SERVER_RUNNING
        if (port !in McpPreferencesStore.MIN_PORT..McpPreferencesStore.MAX_PORT) return PortChange.OUT_OF_RANGE
        val next = settingsFlow.value.copy(port = port)
        settingsFlow.value = next
        val saved = preferences.save(next)
        saveFailedFlow.value = !saved
        return if (saved) PortChange.CHANGED else PortChange.SAVE_FAILED
    }

    /** "Use q from now on": stores the port the server actually runs on. */
    fun adoptActualPort() {
        val current = statusFlow.value as? McpServerStatus.Running ?: return
        val next = settingsFlow.value.copy(port = current.port)
        settingsFlow.value = next
        saveFailedFlow.value = !preferences.save(next)
        statusFlow.value = current.copy(preferred = current.port)
    }

    /** A new token; a running server checks it at once. `false` when it could not be stored (the old one stays). */
    fun regenerateToken(): Boolean =
        try {
            val fresh = tokens.regenerate()
            tokenRef = fresh
            hasTokenFlow.value = true
            true
        } catch (_: McpTokenWriteException) {
            false
        }

    /** The whole `claude mcp add` command with the real token, for the clipboard; `null` while there is no token. */
    fun copyCommand(): String? = tokens.current()?.let { McpCommand.text(currentPort(), it) }

    /** The real token, for the clipboard; `null` while there is none. */
    fun copyToken(): String? = tokens.current()?.value

    private fun currentPort(): Int = (statusFlow.value as? McpServerStatus.Running)?.port ?: settingsFlow.value.port

    /** Stops the server now and for good. Idempotent; called from every quit path. */
    fun shutdown() {
        closed = true
        doStop()
        scope.cancel()
    }

    /** Waits until all queued start and stop work is done (tests). */
    internal fun awaitIdle() {
        runBlocking { scope.launch { }.join() }
    }

    private fun doStop() {
        synchronized(lock) {
            running?.stop()
            running = null
            statusFlow.value = McpServerStatus.Off
        }
    }

    private fun doStart(preferred: Int) {
        synchronized(lock) {
            if (closed || running != null) return
            val token =
                try {
                    tokens.getOrCreate()
                } catch (_: McpTokenWriteException) {
                    statusFlow.value = McpServerStatus.TokenUnavailable
                    return
                }
            tokenRef = token
            hasTokenFlow.value = true
            val last = minOf(preferred + FALLBACK_PORTS, McpPreferencesStore.MAX_PORT)
            for (candidate in preferred..last) {
                val server = serverFactory { tokenRef }
                try {
                    val actual = server.start(candidate)
                    running = server
                    statusFlow.value = McpServerStatus.Running(actual, preferred)
                    return
                } catch (_: BindException) {
                    continue
                } catch (e: IOException) {
                    logger.debug { "mcp server start failed (${e::class.simpleName})" }
                    statusFlow.value = McpServerStatus.Off
                    return
                }
            }
            statusFlow.value = McpServerStatus.NoFreePort(preferred, last)
        }
    }

    companion object {
        /** After the preferred port, this many more are tried. */
        const val FALLBACK_PORTS = 9
    }
}

/** What the manager needs of a server (a seam for tests). */
interface DesktopMcpServerHandle {
    fun start(port: Int): Int

    fun stop()

    companion object {
        fun of(server: DesktopMcpHttpServer): DesktopMcpServerHandle =
            object : DesktopMcpServerHandle {
                override fun start(port: Int): Int = server.start(port)

                override fun stop() = server.stop()
            }
    }
}
