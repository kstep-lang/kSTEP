package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.ai.keys.KeyLookup
import dev.kstep.desktop.ai.keys.KeychainBackend
import dev.kstep.desktop.ai.keys.Secret
import dev.kstep.desktop.ai.keys.SessionKeyStore
import dev.kstep.desktop.ai.keys.StoreFailure
import dev.kstep.desktop.ai.provider.AiProvider
import dev.kstep.desktop.ai.provider.ProviderCatalog
import dev.kstep.desktop.ai.repair.AttemptLine
import dev.kstep.desktop.ai.repair.CandidateCompiler
import dev.kstep.desktop.ai.repair.CompileOutcome
import dev.kstep.desktop.ai.repair.RepairState
import dev.kstep.desktop.ai.repair.RunContext
import dev.kstep.desktop.ai.repair.Verdict
import dev.kstep.desktop.ai.settings.AiSettings
import dev.kstep.desktop.ai.settings.SettingsStore
import dev.kstep.desktop.aiwire.AiController
import dev.kstep.desktop.aiwire.AiUiState
import dev.kstep.desktop.aiwire.KeyStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * Headless composition of the AI panel in the same scrollable container DesktopApp puts it in. A vertically scrollable
 * child measured with an infinite height throws, so every state with an attempt list must compose without throwing.
 */
class AiPanelSceneTest :
    StringSpec({
        val noKeychain =
            object : KeychainBackend {
                override val displayName = "none"

                override fun isAvailable() = false

                override fun put(
                    provider: AiProvider,
                    secret: Secret,
                ): StoreFailure? = StoreFailure.UNAVAILABLE

                override fun get(provider: AiProvider): KeyLookup = KeyLookup.Missing

                override fun has(provider: AiProvider): Boolean? = null

                override fun delete(provider: AiProvider): StoreFailure? = null
            }
        val ctx = RunContext(1, "a box", "base", false, 0)
        val lines = (1..6).map { AttemptLine(it, 4, "answer $it was rejected") }
        val longScript = (1..400).joinToString("\n") { "val line$it = $it" }

        fun render(repair: RepairState) {
            val controller =
                AiController(
                    SettingsStore(TestSupport.newDir()),
                    noKeychain,
                    SessionKeyStore(),
                    env = { null },
                    compiler = CandidateCompiler { _, _ -> CompileOutcome.Ok },
                )
            val p = AiProvider.ANTHROPIC
            val ui = AiUiState(AiSettings(), p, ProviderCatalog.modelById(p, null), null, KeyStatus.PRESENT, true)
            val scene = ImageComposeScene(width = 420, height = 700)
            try {
                scene.setContent {
                    AiPanel(
                        controller = controller,
                        ui = ui,
                        repair = repair,
                        editorText = "val a = 1\n",
                        hasFile = true,
                        focusNonce = 0,
                        onApply = {},
                        onEscapeToEditor = {},
                        modifier = Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    )
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
                controller.close()
            }
        }

        "the panel composes in the idle, running, failed, stopped and proposal states".config(timeout = 90.seconds) {
            render(RepairState.Idle)
            render(RepairState.Requesting(ctx, 1, emptyList()))
            render(RepairState.Requesting(ctx, 2, lines))
            render(RepairState.Compiling(ctx, 2, lines.take(1)))
            render(RepairState.Failed("KSTEP-D-014", "gave up", lines))
            render(RepairState.Stopped)
            Verdict.entries.forEach {
                render(RepairState.Proposal("val a = 2\n", "base", it, lines, 3))
                render(RepairState.Proposal(longScript, "base", it, emptyList(), 1))
            }
        }

        "the status line uses the configured attempt limit".config(timeout = 30.seconds) {
            aiStatusText(RepairState.Requesting(ctx, 2, emptyList()), 3_000, 6) shouldBe "AI: attempt 2/6 · 3 s"
            aiStatusText(RepairState.Compiling(ctx, 1, emptyList()), 0) shouldBe "AI: attempt 1/4 · 0 s"
        }
    })
