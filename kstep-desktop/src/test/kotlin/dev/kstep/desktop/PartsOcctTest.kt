package dev.kstep.desktop

import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/**
 * End to end with real geometry (ADR-0053): the render worker's part list, validated against the GLB it wrote.
 * Named after OCCT on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it.
 */
class PartsOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a placed two-part assembly yields two named parts covering the whole mesh".config(
            enabled = available,
            timeout = 200.seconds,
        ) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                val result = WorkerRunner(dir).run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                result.model.warnings.shouldBeEmpty()
                val table = result.model.parts!!
                table.parts.map { it.name } shouldBe listOf("Red part", "Green part")
                table.parts.sumOf { it.triangleCount } shouldBe result.model.triangles
                table.parts[0].firstTriangle shouldBe 0
                table.parts[1].firstTriangle shouldBe table.parts[0].triangleCount
                table.parts[0].colorHex shouldBe "#FF0000"
                table.parts[1].colorHex shouldBe "#009900"
                table.parts[1]
                    .placement.origin
                    .toList() shouldBe listOf(30.0, 0.0, 0.0)
                table.parts[0].bboxMax.toList() shouldBe listOf(10.0, 20.0, 30.0)
                table.parts[1].bboxMin[0] shouldBe 30.0
                table.partOfTriangle(0) shouldBe 0
                table.partOfTriangle(result.model.triangles - 1) shouldBe 1
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
