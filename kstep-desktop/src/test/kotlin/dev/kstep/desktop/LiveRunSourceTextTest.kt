package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.RunResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** ADR-0091: the controller records the exact text a run evaluated, never trusting the worker for it. */
class LiveRunSourceTextTest :
    StringSpec({
        val file = Path.of("/tmp/never-read-source-text.kstep.kts")

        fun model(generation: Long) = DesktopModel(null, 0, generation, Instant.EPOCH, 1, emptyList())

        fun controller(
            reader: (Path) -> String?,
            routed: Boolean = false,
        ) = LiveRunController(
            runner = { _, gen, _ -> RunResult.Success(model(gen)) },
            watcherFactory = { _, _ -> AutoCloseable { } },
            bufferRunner = { _, _, gen, _ -> RunResult.Success(model(gen)) },
            isRouted = { routed },
            sourceTextReader = reader,
        )

        fun sourceText(c: LiveRunController): String? =
            TestSupport
                .awaitState(c, 10_000) { it is DesktopState.Ok }!!
                .shouldBeInstanceOf<DesktopState.Ok>()
                .model.sourceText

        "a buffer run carries exactly the buffer text".config(timeout = 60.seconds) {
            val c = controller({ "from file" })
            try {
                c.open(file)
                TestSupport.awaitState(c, 10_000) { it is DesktopState.Ok }
                c.runBuffer(file, "unsaved text")
                val ok = TestSupport.awaitState(c, 10_000) { it is DesktopState.Ok && it.generation == 2L }
                ok.shouldBeInstanceOf<DesktopState.Ok>().model.sourceText shouldBe "unsaved text"
            } finally {
                c.close()
            }
        }

        "a file run with two equal readings carries the text".config(timeout = 60.seconds) {
            val c = controller({ "saved text" })
            try {
                c.open(file)
                sourceText(c) shouldBe "saved text"
            } finally {
                c.close()
            }
        }

        "a file that changes between the two readings gives no text".config(timeout = 60.seconds) {
            val reads = AtomicInteger()
            val c = controller({ "version ${reads.incrementAndGet()}" })
            try {
                c.open(file)
                sourceText(c) shouldBe null
            } finally {
                c.close()
            }
        }

        "a reader that returns null or throws keeps the run a success without text".config(timeout = 60.seconds) {
            val nulls = controller({ null })
            try {
                nulls.open(file)
                sourceText(nulls) shouldBe null
            } finally {
                nulls.close()
            }
            val throwing = controller({ throw IllegalStateException("boom") })
            try {
                throwing.open(file)
                sourceText(throwing) shouldBe null
            } finally {
                throwing.close()
            }
        }

        "a routed file (a note) gets no text".config(timeout = 60.seconds) {
            val c = controller({ "note text" }, routed = true)
            try {
                c.open(file)
                sourceText(c) shouldBe null
            } finally {
                c.close()
            }
        }
    })
