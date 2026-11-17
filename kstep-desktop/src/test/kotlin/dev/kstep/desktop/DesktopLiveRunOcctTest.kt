package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.FileChangeWatcher
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

/**
 * End to end with real geometry: script file -> polling watcher -> render worker -> GLB -> [dev.kstep.geometry.ColoredMesh].
 * Named after OCCT on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it.
 */
class DesktopLiveRunOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "the OCCT bridge is available when kstep-desktop's build requires it" {
            if (System.getProperty("kstep.occt.require") == "true") {
                OcctKernel.availability().shouldBeInstanceOf<OcctAvailability.Available>()
            }
        }

        fun bounds(mesh: dev.kstep.geometry.ColoredMesh): DoubleArray {
            val c = mesh.mesh.coordinates
            val b =
                doubleArrayOf(
                    Double.MAX_VALUE,
                    Double.MAX_VALUE,
                    Double.MAX_VALUE,
                    -Double.MAX_VALUE,
                    -Double.MAX_VALUE,
                    -Double.MAX_VALUE,
                )
            for (i in c.indices) {
                val axis = i % 3
                b[axis] = minOf(b[axis], c[i])
                b[axis + 3] = maxOf(b[axis + 3], c[i])
            }
            return b
        }

        "a live edit cycle shows geometry, keeps the last good result on an error and recovers".config(
            enabled = available,
            timeout = 400.seconds,
        ) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            val runner = WorkerRunner(session)
            val controller =
                LiveRunController(
                    runner = { f, g, s -> runner.run(f, g, s) },
                    watcherFactory = { f, onEvent -> FileChangeWatcher(f, onEvent = onEvent) },
                    sessionDir = session,
                )
            try {
                val script =
                    TestSupport.copyFixture(
                        dir,
                        "export-colour-assembly-two-parts.kstep.kts",
                        "live.kstep.kts",
                    )
                val original = Files.readString(script)
                controller.open(script)
                val ok =
                    TestSupport.awaitState(
                        controller,
                        120_000,
                    ) { it is DesktopState.Ok || it is DesktopState.Error }
                val first = ok.shouldBeInstanceOf<DesktopState.Ok>().model
                (first.triangles > 0) shouldBe true
                val mesh = first.mesh!!
                val colours = (0 until mesh.mesh.triangleCount).map { mesh.colorAt(it) }.toSet()
                (colours.size >= 2) shouldBe true
                (colours.any { it.r > 0.9 && it.g < 0.1 }) shouldBe true
                val b = bounds(mesh)
                b[0] shouldBe (0.0 plusOrMinus 1e-3)
                b[3] shouldBe (40.0 plusOrMinus 1e-3)
                b[4] shouldBe (20.0 plusOrMinus 1e-3)
                b[5] shouldBe (30.0 plusOrMinus 1e-3)

                // change the box size: gen 2 with other bounds
                replaceAtomically(script, original.replace("makeBox(10.0, 20.0, 30.0)", "makeBox(10.0, 20.0, 50.0)"))
                val second =
                    TestSupport.awaitState(controller, 120_000) {
                        it is DesktopState.Ok &&
                            it.generation > first.generation
                    }
                bounds(second.shouldBeInstanceOf<DesktopState.Ok>().model.mesh!!)[5] shouldBe (50.0 plusOrMinus 1e-3)
                val secondGen = second.generation

                // a syntax error: Error with a line, the last good result stays
                replaceAtomically(script, original + "\nval broken = \n")
                val failed =
                    TestSupport.awaitState(controller, 120_000) {
                        it is DesktopState.Error &&
                            it.generation > secondGen
                    }
                val error = failed.shouldBeInstanceOf<DesktopState.Error>()
                error.errors.first().code shouldBe "KSTEP-S-001"
                (error.errors.first().line != null) shouldBe true
                error.lastGood?.generation shouldBe secondGen

                // fixed again
                replaceAtomically(script, original)
                TestSupport.awaitState(
                    controller,
                    120_000,
                ) { it is DesktopState.Ok && it.generation > failed.generation }
            } finally {
                controller.close()
                dir.toFile().deleteRecursively()
            }
        }

        "geometry without the native bridge reports NativeUnavailable, fails predictably when the native bridge is unavailable"
            .config(
                enabled = !available,
                timeout = 200.seconds,
            ) {
                val dir = TestSupport.newDir()
                try {
                    val runner = WorkerRunner(dir)
                    val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                    runner.run(script, 1) { }.shouldBeInstanceOf<RunResult.NativeUnavailable>()
                } finally {
                    dir.toFile().deleteRecursively()
                }
            }
    })

private fun replaceAtomically(
    target: Path,
    text: String,
) {
    val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.writeString(tmp, text)
    Files.move(
        tmp,
        target,
        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
    )
}
