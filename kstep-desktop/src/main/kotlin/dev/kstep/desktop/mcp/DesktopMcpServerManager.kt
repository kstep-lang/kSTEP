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

/** The outcome of [DesktopMcpServerManager.setAllowActions]. */
enum class ActionsChange { CHANGED, SERVER_NOT_RUNNING, SAVE_FAILED }

/** The outcome of [DesktopMcpServerManager.setAllowFiles] (ADR-0098). */
enum class FilesChange { CHANGED, ACTIONS_OFF, SERVER_NOT_RUNNING }

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
    /** The UI link of the action tools (ADR-0097); without a port in it every action answers `UI_BUSY`. */
    private val actions: McpActionHost = McpActionHost(McpUiBridge(Dispatchers.Default, { false })),
    private val serverFactory:
        (token: () -> McpToken?, allowActions: () -> Boolean, allowFiles: () -> Boolean) -> DesktopMcpServerHandle =
        { token, allowActions, allowFiles ->
            // the permissions are read at every call, never copied at start (ADR-0097 D1, ADR-0098 D1)
            val context = McpActionContext(actions, allowActions = allowActions, allowFiles = allowFiles)
            DesktopMcpServerHandle.of(
                DesktopMcpHttpServer(
                    McpProtocol(McpToolRegistry(host, actions = context), host.appVersion),
                    token,
                    activity,
                ),
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

    private val allowActionsFlow = MutableStateFlow(false)

    // Never saved and never restored: off after every start (ADR-0098 D1).
    private val allowFilesFlow = MutableStateFlow(false)

    val status: StateFlow<McpServerStatus> get() = statusFlow

    /** Agents may change things (ADR-0097); off by default and saved with the settings. */
    val allowActions: StateFlow<Boolean> get() = allowActionsFlow
    val settingsState: StateFlow<McpSettings> get() = settingsFlow

    /** Agents may save, export and open files, each after the user agreed (ADR-0098); this session only. */
    val allowFiles: StateFlow<Boolean> get() = allowFilesFlow

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
        allowActionsFlow.value = loaded.allowActions
        filesOff()
        scope.launch {
            hasTokenFlow.value = tokens.current() != null
            if (loaded.enabled) doStart(loaded.port)
        }
    }

    // Reading the latest settings and writing them is one step: two saves must not cross (an older value written last).
    private val saveLock = Any()

    private fun saveLatest(): Boolean = synchronized(saveLock) { preferences.save(settingsFlow.value) }

    /** Takes the file permission back and ends an open confirmation with `FILES_DISABLED`. */
    private fun filesOff() {
        allowFilesFlow.value = false
        actions.confirmations.cancelAll(McpToolCodes.FILES_DISABLED)
    }

    fun setEnabled(on: Boolean) {
        val next = settingsFlow.value.copy(enabled = on)
        settingsFlow.value = next
        scope.launch {
            // Save the latest snapshot, not the captured one: a setting changed meanwhile must not be overwritten.
            saveFailedFlow.value = !saveLatest()
            if (on) doStart(next.port) else doStop()
        }
    }

    /**
     * Switches the permission for agents to make changes (ADR-0097). Turning it on needs a running server; turning it
     * off always works, also while the server is off, so a saved permission can always be taken back.
     */
    fun setAllowActions(on: Boolean): ActionsChange {
        if (on && statusFlow.value !is McpServerStatus.Running) return ActionsChange.SERVER_NOT_RUNNING
        val saved =
            synchronized(saveLock) {
                settingsFlow.value = settingsFlow.value.copy(allowActions = on)
                allowActionsFlow.value = on
                // without the permission for changes there is no permission for files (ADR-0098 D1)
                if (!on) filesOff()
                saveLatest()
            }
        saveFailedFlow.value = !saved
        return if (saved) ActionsChange.CHANGED else ActionsChange.SAVE_FAILED
    }

    /**
     * Switches the permission for file actions (ADR-0098). Turning it on needs a running server and the permission to
     * make changes; turning it off always works and ends an open confirmation. Never saved.
     */
    fun setAllowFiles(on: Boolean): FilesChange =
        synchronized(saveLock) {
            if (!on) {
                filesOff()
                FilesChange.CHANGED
            } else if (!settingsFlow.value.allowActions) {
                FilesChange.ACTIONS_OFF
            } else if (statusFlow.value !is McpServerStatus.Running) {
                FilesChange.SERVER_NOT_RUNNING
            } else {
                allowFilesFlow.value = true
                FilesChange.CHANGED
            }
        }

    /** Only while the server does not run. Saves the new port. */
    fun setPort(port: Int): PortChange {
        if (statusFlow.value is McpServerStatus.Running) return PortChange.SERVER_RUNNING
        if (port !in McpPreferencesStore.MIN_PORT..McpPreferencesStore.MAX_PORT) return PortChange.OUT_OF_RANGE
        val saved =
            synchronized(saveLock) {
                settingsFlow.value = settingsFlow.value.copy(port = port)
                saveLatest()
            }
        saveFailedFlow.value = !saved
        return if (saved) PortChange.CHANGED else PortChange.SAVE_FAILED
    }

    /** "Use q from now on": stores the port the server actually runs on. */
    fun adoptActualPort() {
        val current = statusFlow.value as? McpServerStatus.Running ?: return
        val saved =
            synchronized(saveLock) {
                settingsFlow.value = settingsFlow.value.copy(port = current.port)
                saveLatest()
            }
        saveFailedFlow.value = !saved
        statusFlow.value = current.copy(preferred = current.port)
    }

    /** A new token; a running server checks it at once. `false` when it could not be stored (the old one stays). */
    fun regenerateToken(): Boolean =
        try {
            val fresh = tokens.regenerate()
            tokenRef = fresh
            // a new token means a new trust decision: the file permission does not survive it (ADR-0098 D1)
            filesOff()
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
        actions.confirmations.shutdown()
    }

    /** Waits until all queued start and stop work is done (tests). */
    internal fun awaitIdle() {
        runBlocking { scope.launch { }.join() }
    }

    private fun doStop() {
        synchronized(lock) {
            running?.stop()
            running = null
            activity.reset()
            statusFlow.value = McpServerStatus.Off
            filesOff()
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
                val server =
                    serverFactory(
                        { tokenRef },
                        { settingsFlow.value.allowActions },
                        {
                            settingsFlow.value.allowActions &&
                                allowFilesFlow.value &&
                                statusFlow.value is McpServerStatus.Running
                        },
                    )
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
