package dev.kstep.desktop.run

import dev.kstep.desktop.run.WorkerSandbox.UnavailableKind
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SandboxNoticeTest :
    StringSpec({
        "no notice while unknown or active" {
            sandboxNotice(UserSandboxState.Unknown) shouldBe null
            sandboxNotice(
                UserSandboxState.Active(
                    WorkerSandbox.Bubblewrap(
                        java.nio.file.Path
                            .of("/b"),
                        java.nio.file.Path
                            .of("/s"),
                        java.nio.file.Path
                            .of("/s"),
                    ),
                ),
            ) shouldBe null
        }

        "every kind has the exact text and its reason" {
            val expected =
                mapOf(
                    UnavailableKind.WINDOWS to "No sandbox is available for kSTEP scripts on Windows.",
                    UnavailableKind.BWRAP_MISSING to "bubblewrap (bwrap) not found. Install it to sandbox scripts.",
                    UnavailableKind.BWRAP_PROBE_FAILED to
                        "bubblewrap is installed but cannot start (user namespaces may be disabled).",
                    UnavailableKind.MAC_EXEC_MISSING to "sandbox-exec cannot start.",
                    UnavailableKind.MAC_PROBE_FAILED to "sandbox-exec cannot start.",
                    UnavailableKind.OTHER_OS to "No sandbox is available for kSTEP scripts on this system.",
                    UnavailableKind.CHECK_FAILED to "The sandbox check failed.",
                )
            UnavailableKind.entries.toSet() shouldBe expected.keys
            for ((kind, reason) in expected) {
                val n = sandboxNotice(UserSandboxState.Unavailable(kind, "detail"))!!
                n.text shouldBe "Script runs without sandbox"
                n.reason shouldBe reason
                n.contentDescription shouldContain "Script runs without sandbox"
                n.contentDescription shouldContain reason
            }
        }
    })
