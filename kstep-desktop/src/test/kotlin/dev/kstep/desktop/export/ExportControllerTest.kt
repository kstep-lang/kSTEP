package dev.kstep.desktop.export

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** The controller with a fake worker: the pipeline, the target handling and the cancel rules, without a process. */
class ExportControllerTest :
    StringSpec({
        class Harness(
            val worker: (Path, ExportFormat, Long, (Process) -> Unit) -> ExportWorkerResult,
        ) {
            val dir: Path = TestSupport.newDir()
            val out: Path = Files.createDirectory(dir.resolve("out"))
            val session: Path = Files.createDirectory(dir.resolve("session"))
            val script: Path = TestSupport.script(dir, "bracket", "// x\n")
            val controller = ExportController(worker = worker)

            fun cleanup() {
                controller.close()
                dir.toFile().deleteRecursively()
            }

            fun awaitState(predicate: (ExportState) -> Boolean): ExportState {
                check(TestSupport.await(20_000) { predicate(controller.state.value) }) {
                    "unexpected state ${controller.state.value}"
                }
                return controller.state.value
            }

            fun produce(
                gen: Long,
                content: ByteArray = "ISO-10303-21;\nDATA;".toByteArray(),
            ): ExportWorkerResult =
                ExportWorkerResult.Produced(Files.write(session.resolve("export-$gen.step"), content))
        }

        fun harness(worker: (Path, ExportFormat, Long, (Process) -> Unit) -> ExportWorkerResult) = Harness(worker)

        fun names(dir: Path): List<String> =
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

        "begin suggests a name from the script and the last format" {
            lateinit var h: Harness
            h = harness { _, _, g, _ -> h.produce(g) }
            try {
                h.controller.begin(h.script, ExportFormat.GLB, h.out)
                val state =
                    h.controller.state.value
                        .shouldBeInstanceOf<ExportState.Configuring>()
                state.format shouldBe ExportFormat.GLB
                state.target shouldBe h.out.resolve("bracket.glb")
            } finally {
                h.cleanup()
            }
        }

        "a successful export writes the target atomically and removes the worker output".config(timeout = 60.seconds) {
            lateinit var h: Harness
            h = harness { _, _, g, _ -> h.produce(g) }
            try {
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                h.controller.start()
                val done = h.awaitState { it is ExportState.Succeeded }.shouldBeInstanceOf<ExportState.Succeeded>()
                done.fileName shouldBe "bracket.step"
                Files.readString(h.out.resolve("bracket.step")) shouldBe "ISO-10303-21;\nDATA;"
                names(h.out) shouldBe listOf("bracket.step")
                names(h.session) shouldBe emptyList()
            } finally {
                h.cleanup()
            }
        }

        "an existing target needs the inline confirmation, then it is replaced".config(timeout = 60.seconds) {
            lateinit var h: Harness
            h = harness { _, _, g, _ -> h.produce(g) }
            try {
                Files.writeString(h.out.resolve("bracket.step"), "old")
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                (h.controller.state.value as ExportState.Configuring).overwrite shouldBe Overwrite.NeedsConfirm(3)
                h.controller.start() // ignored without the confirmation
                h.controller.state.value
                    .shouldBeInstanceOf<ExportState.Configuring>()
                Files.readString(h.out.resolve("bracket.step")) shouldBe "old"
                h.controller.confirmReplace()
                h.controller.start()
                h.awaitState { it is ExportState.Succeeded }
                Files.readString(h.out.resolve("bracket.step")) shouldBe "ISO-10303-21;\nDATA;"
            } finally {
                h.cleanup()
            }
        }

        "a failing worker leads back to Configuring with the error and writes nothing".config(timeout = 60.seconds) {
            val error = DesktopError(DesktopErrorCodes.EXPORT_FAILED, null, null, "the script has no geometry")
            val h = harness { _, _, _, _ -> ExportWorkerResult.Failure(error) }
            try {
                h.controller.begin(h.script, ExportFormat.STL, h.out)
                h.controller.start()
                val state = h.awaitState { it is ExportState.Configuring && it.error != null }
                state.shouldBeInstanceOf<ExportState.Configuring>().error shouldBe error
                names(h.out) shouldBe emptyList()
            } finally {
                h.cleanup()
            }
        }

        "an invalid worker output is KSTEP-D-024, nothing is written and the output is removed".config(
            timeout = 60.seconds,
        ) {
            lateinit var h: Harness
            h = harness { _, _, g, _ -> h.produce(g, "not a step file".toByteArray()) }
            try {
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                h.controller.start()
                val state = h.awaitState { it is ExportState.Configuring && it.error != null }
                state.shouldBeInstanceOf<ExportState.Configuring>().error!!.code shouldBe
                    DesktopErrorCodes.EXPORT_OUTPUT_INVALID
                names(h.out) shouldBe emptyList()
                names(h.session) shouldBe emptyList()
            } finally {
                h.cleanup()
            }
        }

        "a stop while the worker runs writes nothing even if the worker still returns a file".config(
            timeout = 60.seconds,
        ) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            lateinit var h: Harness
            h =
                harness { _, _, g, _ ->
                    entered.countDown()
                    release.await()
                    h.produce(g)
                }
            try {
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                h.controller.start()
                entered.await()
                h.controller.stop()
                val state =
                    h.controller.state.value
                        .shouldBeInstanceOf<ExportState.Configuring>()
                state.notice shouldBe ExportText.STOPPED_NOTICE
                release.countDown()
                TestSupport.await(10_000) { names(h.session).isEmpty() } shouldBe true
                names(h.out) shouldBe emptyList()
                h.controller.state.value
                    .shouldBeInstanceOf<ExportState.Configuring>()
            } finally {
                release.countDown()
                h.cleanup()
            }
        }

        "a second start while running does not start a second worker".config(timeout = 60.seconds) {
            val calls = AtomicInteger()
            val release = CountDownLatch(1)
            lateinit var h: Harness
            h =
                harness { _, _, g, _ ->
                    calls.incrementAndGet()
                    release.await()
                    h.produce(g)
                }
            try {
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                h.controller.start()
                h.controller.start()
                h.controller.start()
                release.countDown()
                h.awaitState { it is ExportState.Succeeded }
                calls.get() shouldBe 1
            } finally {
                release.countDown()
                h.cleanup()
            }
        }

        "an unwritable or invalid target is shown before any run" {
            val h = harness { _, _, _, _ -> error("must not run") }
            try {
                h.controller.begin(h.script, ExportFormat.STEP, h.dir.resolve("missing-folder"))
                val state =
                    h.controller.state.value
                        .shouldBeInstanceOf<ExportState.Configuring>()
                state.error!!.code shouldBe DesktopErrorCodes.EXPORT_TARGET_INVALID
            } finally {
                h.cleanup()
            }
        }

        "changing the format keeps the stem and re-checks the new name".config(timeout = 60.seconds) {
            val h = harness { _, _, _, _ -> error("must not run") }
            try {
                Files.writeString(h.out.resolve("bracket.glb"), "12345")
                h.controller.begin(h.script, ExportFormat.STEP, h.out)
                h.controller.chooseFormat(ExportFormat.GLB)
                val state =
                    h.controller.state.value
                        .shouldBeInstanceOf<ExportState.Configuring>()
                state.target shouldBe h.out.resolve("bracket.glb")
                state.overwrite shouldBe Overwrite.NeedsConfirm(5)
            } finally {
                h.cleanup()
            }
        }
    })
