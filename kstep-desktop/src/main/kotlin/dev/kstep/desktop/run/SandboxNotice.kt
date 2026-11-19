package dev.kstep.desktop.run

import dev.kstep.desktop.run.WorkerSandbox.UnavailableKind

/** The visible notice of a flow that runs without sandbox (ADR-0062). */
const val NO_SANDBOX_TEXT: String = "Script runs without sandbox"

/** [text] goes into the status line, [reason] into the tooltip. */
data class SandboxNotice(
    val text: String,
    val reason: String,
) {
    val contentDescription: String get() = "$text. $reason"
}

/** `null` while nothing is wrong (not probed yet, or a sandbox is active): the status line shows no segment then. */
fun sandboxNotice(state: UserSandboxState): SandboxNotice? =
    when (state) {
        UserSandboxState.Unknown, is UserSandboxState.Active -> null
        is UserSandboxState.Unavailable -> SandboxNotice(NO_SANDBOX_TEXT, reasonOf(state.kind))
    }

private fun reasonOf(kind: UnavailableKind): String =
    when (kind) {
        UnavailableKind.WINDOWS -> "No sandbox is available for kSTEP scripts on Windows."
        UnavailableKind.BWRAP_MISSING -> "bubblewrap (bwrap) not found. Install it to sandbox scripts."
        UnavailableKind.BWRAP_PROBE_FAILED ->
            "bubblewrap is installed but cannot start (user namespaces may be disabled)."
        UnavailableKind.MAC_EXEC_MISSING, UnavailableKind.MAC_PROBE_FAILED -> "sandbox-exec cannot start."
        UnavailableKind.OTHER_OS -> "No sandbox is available for kSTEP scripts on this system."
        UnavailableKind.CHECK_FAILED -> "The sandbox check failed."
    }
