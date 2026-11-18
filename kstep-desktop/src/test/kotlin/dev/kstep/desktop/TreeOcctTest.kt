package dev.kstep.desktop

import dev.kstep.desktop.pick.AssemblyTree
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
 * End to end with real geometry (ADR-0054): the render worker's assembly tree, validated against the part list and the
 * GLB it wrote. Named after OCCT on purpose: CI's skip guard (`TEST-*Occt*.xml`) then covers it.
 */
class TreeOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun AssemblyTree.dump() =
            (0 until size).map {
                "${"  ".repeat(
                    depth(it),
                )}${name(it)}${duplicateSuffix(it)}${if (isGroup(it)) "" else " [${partOf(it)}]"}"
            }

        "a nested assembly with a repeated sub-assembly gives two separate groups".config(
            enabled = available,
            timeout = 200.seconds,
        ) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.copyFixture(dir, "nested-assembly-tree.kstep.kts")
                val result = WorkerRunner(dir).run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                result.model.warnings.shouldBeEmpty()
                val table = result.model.parts!!
                val tree = result.model.tree!!
                table.parts.map { it.name } shouldBe listOf("X", "X", "Y")
                tree.dump() shouldBe
                    listOf(
                        "Top",
                        "  Sub #1",
                        "    X [0]",
                        "  Sub #2",
                        "    X [1]",
                        "  Y [2]",
                    )
                tree.firstPart(0) shouldBe 0
                tree.lastPart(0) shouldBe 2
                // the second Sub sits at x = 100, its box is part 1
                table.parts[1].bboxMin[0] shouldBe 100.0
            } finally {
                dir.toFile().deleteRecursively()
            }
        }

        "a flat two-part assembly gives the root and two leaves".config(enabled = available, timeout = 200.seconds) {
            val dir = TestSupport.newDir()
            try {
                val script = TestSupport.copyFixture(dir, "export-colour-assembly-two-parts.kstep.kts")
                val result = WorkerRunner(dir).run(script, 1) { }.shouldBeInstanceOf<RunResult.Success>()
                result.model.tree!!.dump() shouldBe listOf("Coloured assembly", "  Red part [0]", "  Green part [1]")
            } finally {
                dir.toFile().deleteRecursively()
            }
        }
    })
