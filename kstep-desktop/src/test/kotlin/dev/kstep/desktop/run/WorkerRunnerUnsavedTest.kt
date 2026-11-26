package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/** "Run Unsaved" through real worker processes, cold and warm (ADR-0086). No native bridge is needed. */
class WorkerRunnerUnsavedTest :
    StringSpec({
        val okText = Files.readString(TestSupport.fixture("hello-assembly.kstep.kts"))
        val readsData = "check(readInput(\"data.txt\") == \"hello\")\n$okText"

        class Harness(
            warm: Boolean,
            storeFactory: (Path) -> UnsavedBufferStore = { UnsavedBufferStore(it) },
        ) {
            val dir: Path = TestSupport.newDir()
            val session: Path = LiveRunController.createSessionDir(dir)
            val bufferBase: Path = Files.createDirectory(dir.resolve("buffers"))
            val store = storeFactory(bufferBase)
            private val client =
                if (warm) {
                    WarmWorkerClient(session, WarmWorkerSettings(WarmWorkerSettings.Mode.REUSE, 20, 120_000), null)
                } else {
                    null
                }
            val runner = WorkerRunner(session, timeoutMillis = 120_000, warm = client, bufferStore = store)
            private var generation = 0L

            fun runBuffer(
                file: Path,
                text: String,
                onStarted: (Process) -> Unit = {},
            ): RunResult = runner.runBuffer(file, text, ++generation, onStarted)

            /** The files left in the private folder of the store. */
            fun leftover(): List<String> =
                Files.list(bufferBase).use { folders ->
                    folders.toList().flatMap { f ->
                        Files.list(f).use { it.map { p -> p.fileName.toString() }.toList() }
                    }
                }

            fun cleanup() {
                runner.closeWarm()
                dir.toFile().deleteRecursively()
            }
        }

        for (warm in listOf(false, true)) {
            val kind = if (warm) "warm" else "cold"

            "$kind: a buffer reads the input files of the real script folder and leaves the real file alone"
                .config(timeout = 600.seconds) {
                    val h = Harness(warm)
                    try {
                        Files.writeString(h.dir.resolve("data.txt"), "hello")
                        val real = TestSupport.script(h.dir, "part", "val old = 1\n")
                        val before = Files.getLastModifiedTime(real)
                        val result = h.runBuffer(real, readsData)
                        val ok = result.shouldBeInstanceOf<RunResult.Success>()
                        ok.dependencies shouldBe listOf(h.dir.resolve("data.txt").toRealPath())
                        Files.readString(real) shouldBe "val old = 1\n"
                        Files.getLastModifiedTime(real) shouldBe before
                        h.leftover() shouldBe emptyList()
                    } finally {
                        h.cleanup()
                    }
                }

            "$kind: an input file only next to the buffer is not found; the real file need not exist"
                .config(timeout = 600.seconds) {
                    val h = Harness(warm)
                    try {
                        val real = h.dir.resolve("gone.kstep.kts") // never created: the banner "deleted on disk"
                        val failed = h.runBuffer(real, readsData).shouldBeInstanceOf<RunResult.Failure>()
                        failed.errors.any { it.code == "KSTEP-S-017" } shouldBe true
                        Files.writeString(h.dir.resolve("data.txt"), "hello")
                        h.runBuffer(real, readsData).shouldBeInstanceOf<RunResult.Success>()
                        h.leftover() shouldBe emptyList()
                    } finally {
                        h.cleanup()
                    }
                }

            "$kind: a buffer changed between writing and running is refused as an unsafe folder"
                .config(timeout = 600.seconds) {
                    val tamper =
                        { base: Path ->
                            object : UnsavedBufferStore(base) {
                                override fun write(text: String): Written {
                                    val written = super.write(text)
                                    if (written is Written.Ok) Files.writeString(written.path, "val evil = 1\n")
                                    return written
                                }
                            }
                        }
                    val h = Harness(warm, tamper)
                    try {
                        val real = TestSupport.script(h.dir, "part", "val old = 1\n")
                        val failed = h.runBuffer(real, okText).shouldBeInstanceOf<RunResult.Failure>()
                        failed.errors.single().code shouldBe DesktopErrorCodes.SESSION_DIR_UNSAFE
                        failed.errors.single().message shouldContain "not safe"
                        h.leftover() shouldBe emptyList()
                    } finally {
                        h.cleanup()
                    }
                }

            "$kind: the buffer folder is empty after the run was killed".config(timeout = 600.seconds) {
                val h = Harness(warm)
                try {
                    val real = TestSupport.script(h.dir, "part", "val old = 1\n")
                    h.runBuffer(real, "while (true) {}\n") { it.destroyForcibly() }
                    h.leftover() shouldBe emptyList()
                } finally {
                    h.cleanup()
                }
            }
        }

        "a script that prints the refusal marker keeps its real result, saved and unsaved" {
            val h = Harness(warm = false)
            try {
                val noisy = "System.err.println(\"kstep-unsaved-refused:BUFFER_HASH\")\n$okText"
                val real = TestSupport.script(h.dir, "noisy", noisy)
                h.runner.run(real, 100, {}).shouldBeInstanceOf<RunResult.Success>()
                h.runBuffer(real, noisy).shouldBeInstanceOf<RunResult.Success>()
            } finally {
                h.cleanup()
            }
        }

        "a text above one MiB is refused before any process starts" {
            val h = Harness(warm = false)
            try {
                val real = TestSupport.script(h.dir, "part", "val old = 1\n")
                val failed =
                    h
                        .runBuffer(real, "a".repeat(UnsavedRunPolicy.MAX_BYTES + 1))
                        .shouldBeInstanceOf<RunResult.Failure>()
                failed.errors.single().code shouldBe DesktopErrorCodes.UNSAVED_RUN_TOO_LARGE
                failed.errors.single().message shouldBe "the unsaved text is larger than 1 MiB; save it to preview"
            } finally {
                h.cleanup()
            }
        }

        "a script folder that does not exist is refused" {
            val h = Harness(warm = false)
            try {
                val failed =
                    h
                        .runBuffer(h.dir.resolve("missing-dir").resolve("a.kstep.kts"), "val x = 1\n")
                        .shouldBeInstanceOf<RunResult.Failure>()
                failed.errors.single().code shouldBe DesktopErrorCodes.SCRIPT_DIR_MISMATCH
            } finally {
                h.cleanup()
            }
        }
    })
