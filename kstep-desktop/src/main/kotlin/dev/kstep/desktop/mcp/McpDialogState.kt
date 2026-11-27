package dev.kstep.desktop.mcp

import dev.kstep.desktop.run.DesktopErrorCodes

/** The texts of the MCP server dialog (ADR-0095). */
object McpDialogText {
    const val TITLE = "MCP Server"
    const val SWITCH = "Allow agents to connect"
    const val PORT_LOCKED = "Turn the server off to change the port"
    const val PORT_RANGE = "Use a port between 1024 and 65535"
    const val NO_TOKEN = "Turn the server on once to create a token"
    const val HINT =
        "Only reachable from this computer (127.0.0.1). Any program on this computer that has the token can use " +
            "kSTEP Desktop through it — in this version only to read: the status, the editor text, the errors of the " +
            "last run, the parts, the assembly tree and the selection. It cannot change anything yet."
    const val REGEN_TITLE = "Regenerate token?"
    const val REGEN_TEXT = "Agents using the current token lose access until you add the new command again."
    const val TOKEN_MASK = "••••••••••••"
    const val TOKEN_FAILED = "Could not store the token (${DesktopErrorCodes.MCP_TOKEN_WRITE_FAILED})"
    const val SETTINGS_FAILED = "Settings could not be saved (${DesktopErrorCodes.MCP_SETTINGS_WRITE_FAILED})"
}

/** The wording of the shell command that adds the server to an agent. Pure. */
object McpCommand {
    private fun line(
        port: Int,
        token: String,
    ) = "claude mcp add --transport http kstep-desktop http://127.0.0.1:$port/mcp " +
        "--header \"Authorization: Bearer $token\""

    /** The command with the real token: only for the clipboard. */
    fun text(
        port: Int,
        token: McpToken,
    ): String = line(port, token.value)

    /** The command with the literal placeholder `<token>`: what the dialog shows. */
    fun preview(port: Int): String = line(port, "<token>")
}

/** What the dialog shows for the current state. */
data class McpDialogState(
    val statusText: String,
    val warningText: String?,
    val useActualPortLabel: String?,
    val portEditable: Boolean,
    val portReason: String?,
    val copyEnabled: Boolean,
    val copyReason: String?,
    val commandPreview: String,
)

fun mcpDialogState(
    status: McpServerStatus,
    settings: McpSettings,
    hasToken: Boolean,
    saveFailed: Boolean,
): McpDialogState {
    val running = status as? McpServerStatus.Running
    val statusText =
        when (status) {
            McpServerStatus.Off -> "Off"
            is McpServerStatus.Running ->
                if (status.port == status.preferred) {
                    "Running on 127.0.0.1:${status.port}"
                } else {
                    "Port ${status.preferred} was busy — running on ${status.port}."
                }
            is McpServerStatus.NoFreePort ->
                "No free port between ${status.from} and ${status.to} (${DesktopErrorCodes.MCP_NO_FREE_PORT})"
            McpServerStatus.TokenUnavailable -> McpDialogText.TOKEN_FAILED
        }
    return McpDialogState(
        statusText = statusText,
        warningText = if (saveFailed) McpDialogText.SETTINGS_FAILED else null,
        useActualPortLabel = running?.takeIf { it.port != it.preferred }?.let { "Use ${it.port} from now on" },
        portEditable = running == null,
        portReason = if (running != null) McpDialogText.PORT_LOCKED else null,
        copyEnabled = hasToken,
        copyReason = if (hasToken) null else McpDialogText.NO_TOKEN,
        commandPreview = McpCommand.preview(running?.port ?: settings.port),
    )
}

/** The port the user typed: trimmed ASCII digits only, in 1024..65535; otherwise `null`. */
fun parsePortInput(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_PORT_DIGITS || !t.all { it in '0'..'9' }) return null
    return t.toInt().takeIf { it in McpPreferencesStore.MIN_PORT..McpPreferencesStore.MAX_PORT }
}

private const val MAX_PORT_DIGITS = 5
