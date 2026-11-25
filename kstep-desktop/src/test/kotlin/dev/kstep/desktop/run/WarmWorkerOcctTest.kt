package dev.kstep.desktop.run

import dev.kstep.desktop.TestSupport
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * Real geometry on the warm worker (ADR-0081): mesh, part list and export targets equal the cold run, twice in a row on
 * one process. Named after OCCT on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it.
 */
class WarmWorkerOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "geometry, parts and export targets of a warm run equal the cold run, twice on one process".config(
            enabled = available,
            timeout = 600.seconds,
        ) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            val client = WarmWorkerClient(session, WarmWorkerSettings(WarmWorkerSettings.Mode.REUSE, 20, 120_000), null)
            try {
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts", "two.kstep.kts")
                val cold = WorkerRunner(session).run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                val warm = WorkerRunner(session, warm = client)
                var pid = -1L
                repeat(2) { n ->
                    val result = warm.run(script, 10L + n) { pid = it.pid() }.shouldBeInstanceOf<RunResult.Success>()
                    result.model.triangles shouldBe cold.model.triangles
                    result.model.mesh!!
                        .mesh.coordinates
                        .toList() shouldBe
                        cold.model.mesh!!
                            .mesh.coordinates
                            .toList()
                    result.model.parts!!
                        .parts
                        .map { it.name to it.colorHex } shouldBe
                        cold.model.parts!!
                            .parts
                            .map { it.name to it.colorHex }
                    result.model.parts!!.triangleCount shouldBe cold.model.parts!!.triangleCount
                    result.model.scriptExportTargets shouldBe cold.model.scriptExportTargets
                    result.model.tree!!.size shouldBe cold.model.tree!!.size
                    result.dependencies shouldBe cold.dependencies
                }
                (pid > 0) shouldBe true
            } finally {
                client.close()
                dir.toFile().deleteRecursively()
            }
        }

        "a warm run without the native bridge reports NativeUnavailable like the cold run".config(
            enabled = available,
            timeout = 600.seconds,
        ) {
            val dir = TestSupport.newDir()
            val session = LiveRunController.createSessionDir(dir)
            val client =
                WarmWorkerClient(
                    session,
                    WarmWorkerSettings(WarmWorkerSettings.Mode.SPARE, 1, 120_000),
                    null,
                    extraJvmOptions = listOf("-Dkstep.occt.bridge.library=/nonexistent/libx.dylib"),
                )
            try {
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts", "two.kstep.kts")
                WorkerRunner(
                    session,
                    warm = client,
                ).run(script, 1) { }.shouldBeInstanceOf<RunResult.NativeUnavailable>()
            } finally {
                client.close()
                dir.toFile().deleteRecursively()
            }
        }
    })
