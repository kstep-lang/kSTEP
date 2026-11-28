package dev.kstep.desktop.mcp

import dev.kstep.desktop.run.DesktopErrorCodes

/** The texts of the MCP server dialog (ADR-0095). */
object McpDialogText {
    const val TITLE = "MCP Server"
    const val SWITCH = "Allow agents to connect"
    const val PORT_LOCKED = "Turn the server off to change the port"
    const val PORT_RANGE = "Use a port between 1024 and 65535"
    const val NO_TOKEN = "Turn the server on once to create a token"
    const val ACTIONS_SWITCH = "Allow agents to make changes"
    const val ACTIONS_SUBTITLE =
        "Agents may then change the editor text (never saved to disk), start runs, and change the selection and " +
            "the visibility of parts. A run executes the script an agent wrote, as you: inside the script sandbox it " +
            "can still read your files; without a sandbox (Windows, or Linux without bubblewrap) runs are refused. " +
            "Undo reverts an edit."
    const val ACTIONS_LOCKED = "Turn the server on to change this"
    const val FILES_SWITCH = "Allow agents to save and export files"
    const val FILES_HINT =
        "Agents can save the open file, export to your last export folder and open recent files. Every file " +
            "action asks you first. Off after every restart."
    const val FILES_SUBTITLE = FILES_HINT
    const val FILES_LOCKED = "Allow agents to make changes first"

    private const val HINT_LOCAL =
        "Only reachable from this computer (127.0.0.1). Any program on this computer that has the token can use " +
            "kSTEP Desktop through it"
    private const val HINT_READ =
        "to read: the status, the editor text, the errors of the last run, the parts, the assembly tree and the " +
            "selection."

    /** The hint under the port, for the state of the permission to make changes. */
    fun hint(
        allowActions: Boolean,
        allowFiles: Boolean = false,
    ): String =
        if (allowActions) {
            val files =
                if (allowFiles) {
                    "It may also save the open file, export to your last export folder and open recent files, " +
                        "but only after you agreed in a dialog each time"
                } else {
                    "It cannot save, export or open files itself"
                }
            "$HINT_LOCAL, $HINT_READ It may also change things: the editor text (not saved to disk), runs, the " +
                "selection and the visibility of parts. $files, but it can have " +
                "the app run a script it wrote: that script runs as you, can read any file and is only refused " +
                "when no sandbox is available."
        } else {
            "$HINT_LOCAL, but only $HINT_READ Agents cannot change anything. Changes are turned off."
        }

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
    val actionsChecked: Boolean,
    val actionsEnabled: Boolean,
    val actionsReason: String?,
    val filesChecked: Boolean,
    val filesEnabled: Boolean,
    val filesReason: String?,
    val hintText: String,
)

fun mcpDialogState(
    status: McpServerStatus,
    settings: McpSettings,
    hasToken: Boolean,
    saveFailed: Boolean,
    allowFiles: Boolean = false,
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
        actionsChecked = settings.allowActions,
        // turning it off always works, so a saved permission can be taken back while the server is off
        actionsEnabled = running != null || settings.allowActions,
        actionsReason = if (running != null || settings.allowActions) null else McpDialogText.ACTIONS_LOCKED,
        filesChecked = allowFiles,
        // turning it off always works; turning it on needs a running server and the permission for changes
        filesEnabled = (running != null && settings.allowActions) || allowFiles,
        filesReason =
            if ((running != null && settings.allowActions) ||
                allowFiles
            ) {
                null
            } else {
                McpDialogText.FILES_LOCKED
            },
        hintText = McpDialogText.hint(settings.allowActions, allowFiles),
    )
}

/** The port the user typed: trimmed ASCII digits only, in 1024..65535; otherwise `null`. */
fun parsePortInput(text: String): Int? {
    val t = text.trim()
    if (t.isEmpty() || t.length > MAX_PORT_DIGITS || !t.all { it in '0'..'9' }) return null
    return t.toInt().takeIf { it in McpPreferencesStore.MIN_PORT..McpPreferencesStore.MAX_PORT }
}

private const val MAX_PORT_DIGITS = 5
