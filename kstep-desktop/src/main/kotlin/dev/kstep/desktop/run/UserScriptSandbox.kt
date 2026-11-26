package dev.kstep.desktop.run

import dev.kstep.cli.WorkerTempDir
import dev.kstep.desktop.run.WorkerSandbox.UnavailableKind
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/** What the system offers for the user's own scripts (ADR-0062). */
sealed interface UserSandboxState {
    /** No flow has run yet; nothing was probed. */
    data object Unknown : UserSandboxState

    /** The probe succeeded; [sandbox] is the probe instance (bound to a throw-away folder, see [UserScriptSandbox.forSession]). */
    data class Active(
        val sandbox: WorkerSandbox,
    ) : UserSandboxState

    /** The probe failed; the flows run unsandboxed and the status line says so, with the reason as a tooltip. */
    data class Unavailable(
        val kind: UnavailableKind,
        val detail: String,
    ) : UserSandboxState
}

/**
 * The sandbox of the user's own flows (live preview, export, knowledge blocks; ADR-0062). The system is probed once per
 * program run, at the first need and never on the UI thread (the first flow runs in a runner thread). A failed probe is
 * a visible state, not an error: the flows run as before and the status line shows "Script runs without sandbox".
 * Once the probe succeeded, a failing start is an error (D-031), never a fall-back. The AI check keeps its own,
 * fail-closed detection (ADR-0059).
 */
class UserScriptSandbox(
    private val detector: () -> WorkerSandbox = { defaultDetect() },
) {
    private val mutableState = MutableStateFlow<UserSandboxState>(UserSandboxState.Unknown)

    val state: StateFlow<UserSandboxState> = mutableState.asStateFlow()

    /** Probes once (synchronized); later calls return the stored state. */
    @Synchronized
    fun resolve(): UserSandboxState {
        val known = mutableState.value
        if (known !is UserSandboxState.Unknown) return known
        val found =
            try {
                detector()
            } catch (e: Exception) {
                WorkerSandbox.Unavailable(
                    "worker sandbox check failed (${e.javaClass.simpleName})",
                    UnavailableKind.CHECK_FAILED,
                )
            }
        val next =
            when (found) {
                is WorkerSandbox.Unavailable -> UserSandboxState.Unavailable(found.kind, found.reason)
                else -> UserSandboxState.Active(found)
            }
        logger.info {
            "user flow sandbox: ${if (next is UserSandboxState.Active) "active" else "unavailable (${(next as UserSandboxState.Unavailable).kind})"}"
        }
        mutableState.value = next
        return next
    }

    /**
     * The sandbox for a flow working in [sessionDir] (bound to its real path: `/var` is `/private/var` on macOS), or
     * `null` when the system has none and the flow runs unsandboxed with a visible notice.
     */
    fun forSession(sessionDir: Path): WorkerSandbox? =
        when (val s = resolve()) {
            is UserSandboxState.Unknown -> null
            is UserSandboxState.Unavailable -> null
            is UserSandboxState.Active -> {
                val real = sessionDir.toRealPath()
                when (val probe = s.sandbox) {
                    is WorkerSandbox.MacSandboxExec -> WorkerSandbox.MacSandboxExec(probe.profile, real, real)
                    is WorkerSandbox.Bubblewrap -> WorkerSandbox.Bubblewrap(probe.path, real, real, hideTmp = false)
                    is WorkerSandbox.Unavailable -> null
                }
            }
        }

    /** A runner that resolves the sandbox on first use (in the runner thread), shared by the production wiring. */
    fun workerRunner(
        sessionDir: Path,
        warm: WarmWorkerSettings? = null,
        bufferStore: UnsavedBufferStore = UnsavedBufferStore(),
    ): Lazy<WorkerRunner> =
        lazy {
            val box = forSession(sessionDir)
            WorkerRunner(
                sessionDir,
                sandbox = box,
                sandboxFailureCode = DesktopErrorCodes.SANDBOX_START_FAILED,
                sandboxFailureMessage = SANDBOX_FAILED_MESSAGE,
                // The warm worker (ADR-0081) runs in the same sandbox as a cold one; null or OFF is the cold path.
                warm = warm?.takeIf { it.enabled }?.let { WarmWorkerClient(sessionDir, it, box) },
                bufferStore = bufferStore,
            )
        }

    companion object {
        const val SANDBOX_FAILED_MESSAGE: String = "Sandbox failed to start; the script was not run."

        fun production(): UserScriptSandbox = UserScriptSandbox()

        private fun defaultDetect(): WorkerSandbox {
            val probeDir = LiveRunController.createSessionDir()
            try {
                return WorkerSandbox.detect(probeDir, probeDir, hideTmp = false)
            } finally {
                WorkerTempDir.deleteRecursively(probeDir)
            }
        }
    }
}
