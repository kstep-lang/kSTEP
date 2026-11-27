package dev.kstep.desktop.mcp

/**
 * A second safety net for stopping the server (ADR-0095): the JDK's dispatcher thread is not a daemon, so a server that
 * is not stopped keeps the JVM alive. The normal quit paths call `shutdown()` on the manager; `main` calls [stopAll] in
 * a `finally` in case the application is left by an exception.
 */
object McpShutdown {
    private val managers = mutableListOf<DesktopMcpServerManager>()

    @Synchronized
    fun register(manager: DesktopMcpServerManager) {
        managers += manager
    }

    @Synchronized
    fun stopAll() {
        managers.forEach { it.shutdown() }
        managers.clear()
    }
}
