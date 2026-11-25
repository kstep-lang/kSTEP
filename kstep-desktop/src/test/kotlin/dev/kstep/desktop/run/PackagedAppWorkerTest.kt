package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * The worker of a built kSTEP.app (ADR-0082): the bundled jlink runtime, its worker folder and the sandbox around both.
 * Runs only when `-Pkstep.desktop.packagedApp=<abs kSTEP.app>` hands a built app over (the packaging tasks are not part
 * of `check`); every case skips itself otherwise. The bridge case also needs `-Pkstep.occt.bridge.library=<abs dylib>`.
 */
class PackagedAppWorkerTest :
    StringSpec({
        val app: Path? =
            System
                .getProperty("kstep.desktop.packagedApp")
                ?.takeIf { it.isNotBlank() }
                ?.let { Path.of(it) }
                ?.takeIf { Files.isDirectory(it) }
        val mac = System.getProperty("os.name").orEmpty().startsWith("Mac")
        val bridge: String? = System.getProperty("kstep.occt.bridge.library")?.takeIf { it.isNotBlank() }

        fun bundledJava(): Path = app!!.resolve("Contents/runtime/Contents/Home")

        fun found(): WorkerLocationResult.Found {
            val libDir = app!!.resolve("Contents/kstep-worker").toString()
            return WorkerLocator
                .resolve(
                    property = { if (it == WorkerLocator.LIB_DIR_PROPERTY) libDir else null },
                    javaHome = bundledJava(),
                ).shouldBeInstanceOf<WorkerLocationResult.Found>()
        }

        fun boxScript(dir: Path): Path {
            val text = Files.readString(TestSupport.fixture("sized-box.kstep.kts"))
            return TestSupport.script(dir, "box", text.replace("readInput(\"size.txt\").trim().toDouble()", "20.0"))
        }

        fun withDir(body: (Path) -> Unit) {
            val dir = TestSupport.newDir()
            try {
                body(dir)
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "the bundled runtime compiles and runs a box script in the worker (skipped when the packaged app is not built)"
            .config(enabled = app != null && bridge == null, timeout = 180.seconds) {
                withDir { dir ->
                    val location = found().location
                    location.source shouldBe WorkerLocation.Source.PACKAGED
                    // JVM-only bundle: the script compiles and runs, but no geometry can be built.
                    WorkerRunner(
                        dir,
                        worker = found(),
                    ).run(boxScript(dir), 1) { }.shouldBeInstanceOf<RunResult.NativeUnavailable>()
                }
            }

        "a script error from the bundled worker keeps its KSTEP-S code and line (skipped when the packaged app is not built)"
            .config(enabled = app != null, timeout = 180.seconds) {
                withDir { dir ->
                    val script = TestSupport.script(dir, "bad", "val a = 1\nval b: Int = \"text\"\n")
                    val failure =
                        WorkerRunner(dir, worker = found()).run(script, 1) { }.shouldBeInstanceOf<RunResult.Failure>()
                    val error = failure.errors.first()
                    error.code shouldStartWith "KSTEP-S-"
                    error.line shouldBe 2
                }
            }

        "the bundled runtime runs inside the user-flow sandbox (skipped when the packaged app is not built)"
            .config(enabled = app != null && mac, timeout = 180.seconds) {
                withDir { dir ->
                    val box =
                        WorkerSandbox.detect(
                            dir,
                            dir,
                            hideTmp = false,
                            java = bundledJava().resolve("bin/java").toString(),
                        )
                    box.shouldBeInstanceOf<WorkerSandbox.MacSandboxExec>()
                    val runner = WorkerRunner(dir, sandbox = box, worker = found())
                    val script = TestSupport.copyFixture(dir, "hello-assembly.kstep.kts", "ok.kstep.kts")
                    runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                    // A write outside the session folder is refused by the profile, so the script fails.
                    val outside = Files.createTempFile("kstep-outside", ".txt")
                    try {
                        Files.delete(outside)
                        val evil =
                            TestSupport.script(
                                dir,
                                "evil",
                                "java.io.File(\"${outside.toString().replace("\\", "\\\\")}\").writeText(\"x\")\n",
                            )
                        runner.run(evil, 2) { }.shouldBeInstanceOf<RunResult.Failure>()
                        Files.exists(outside) shouldBe false
                    } finally {
                        Files.deleteIfExists(outside)
                    }
                }
            }

        "the warm worker serves the second run from the bundled runtime (skipped when the packaged app is not built)"
            .config(enabled = app != null, timeout = 240.seconds) {
                withDir { dir ->
                    val session = LiveRunController.createSessionDir(dir)
                    val settings = WarmWorkerSettings(WarmWorkerSettings.Mode.REUSE, 20, 120_000)
                    val client = WarmWorkerClient(session, settings, null, worker = found())
                    try {
                        val runner = WorkerRunner(session, worker = found(), warm = client)
                        val script = TestSupport.copyFixture(dir, "hello-assembly.kstep.kts", "warm.kstep.kts")
                        var first = 0L
                        var second = 0L
                        val a = measure { runner.run(script, 1) { first = it.pid() } }
                        val b = measure { runner.run(script, 2) { second = it.pid() } }
                        a.second.shouldBeInstanceOf<RunResult.Success>()
                        b.second.shouldBeInstanceOf<RunResult.Success>()
                        println("PACKAGED-WARM first=${a.first} ms second=${b.first} ms pids=$first/$second")
                    } finally {
                        client.close()
                    }
                }
            }

        "with an external OCCT bridge the bundled runtime renders the box to a GLB (skipped when no bridge library is given)"
            .config(enabled = app != null && bridge != null, timeout = 240.seconds) {
                withDir { dir ->
                    val result = WorkerRunner(dir, worker = found()).run(boxScript(dir), 1) { }
                    val success = result.shouldBeInstanceOf<RunResult.Success>()
                    (success.model.triangles > 0) shouldBe true
                }
            }
    })

private fun <T> measure(block: () -> T): Pair<Long, T> {
    val start = System.nanoTime()
    val value = block()
    return (System.nanoTime() - start) / 1_000_000 to value
}
