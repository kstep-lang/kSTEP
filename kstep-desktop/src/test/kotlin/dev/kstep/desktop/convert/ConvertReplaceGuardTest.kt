package dev.kstep.desktop.convert

import dev.kstep.cli.ConvertOutcome
import dev.kstep.cli.ConvertTarget
import dev.kstep.cli.ConvertedFile
import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/** The dialog calls replace blindly; the controller must run a forced conversion only from a conflict (ADR-0078). */
class ConvertReplaceGuardTest :
    StringSpec({
        val request = ConvertRequest(Path.of("/tmp/kstep-convert-guard"), ConvertTarget.KTS, null, recursive = false)

        "replace from Idle, Editing and Done never runs a forced conversion" {
            val forced = CopyOnWriteArrayList<Boolean>()
            val controller =
                ConvertController(
                    runner = { r ->
                        forced += r.force
                        ConvertOutcome.Success(
                            listOf(ConvertedFile("a", "s", null, null, null)),
                            emptyList(),
                            emptyList(),
                        )
                    },
                )
            try {
                controller.replace()
                controller.begin(request)
                controller.replace()
                controller.start()
                TestSupport.await(20_000) { controller.state.value is ConvertState.Done } shouldBe true
                controller.replace()
                Thread.sleep(200)
                forced.toList() shouldBe listOf(false)
                (controller.state.value is ConvertState.Done) shouldBe true
            } finally {
                controller.close()
            }
        }
    })
