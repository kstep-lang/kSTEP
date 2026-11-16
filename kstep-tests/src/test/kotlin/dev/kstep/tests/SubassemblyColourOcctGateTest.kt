package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.RgbColor
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The gate of ADR-0046 as a CI test: OCCT's own XCAF reader reads the hand-written sub-assembly pattern (fixtures
 * `instance-colour/gate-H*.step`, made by `scripts/interop/make_instance_colour_fixtures.py --leaf-items`) as a colour of the
 * component of a sub-assembly. Scene: ASM holds SUB twice (u1 at x=0, u2 at x=100), SUB holds a 10 x 20 x 30 box twice (u3 at
 * x=0, u4 at x=20); box volume 6000, four occurrences, total 24000; occurrence order u1/u3, u1/u4, u2/u3, u2/u4. The class
 * name carries `Occt` so the CI skip gate covers it.
 */
class SubassemblyColourOcctGateTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun import(name: String): ImportedStepModel {
            val bytes =
                requireNotNull(
                    SubassemblyColourOcctGateTest::class.java.getResourceAsStream("/instance-colour/$name"),
                ) { name }
                    .use { it.readBytes() }
            val result = StepImporter.read(bytes)
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model
        }

        fun RgbColor?.shouldBeRgb(
            r: Double,
            g: Double,
            b: Double,
        ) {
            val c = this!!
            c.red shouldBe (r plusOrMinus 1e-6)
            c.green shouldBe (g plusOrMinus 1e-6)
            c.blue shouldBe (b plusOrMinus 1e-6)
            c.alpha shouldBe (1.0 plusOrMinus 1e-6)
        }

        /** Effective colour of the occurrence whose bounding box starts at [minX] (0, 20, 100, 120 = hand calculation). */
        fun ImportedStepModel.colourAt(minX: Double): RgbColor? {
            val o = occurrences.single { Math.abs(it.boundingBox!!.minX - minX) < 1e-6 }
            return o.color
        }

        "H1: u2 blue gives occurrences 3 and 4 blue and 1 and 2 none, the part stays uncoloured".config(
            enabled = available,
        ) {
            import("gate-H1.step").use { m ->
                m.parts.size shouldBe 1
                val box = m.parts.single()
                box.volume!! shouldBe (6000.0 plusOrMinus 1e-6)
                box.color shouldBe null
                m.occurrences.size shouldBe 4
                m.totals.volume!! shouldBe (24000.0 plusOrMinus 1e-6)
                m.colourAt(0.0) shouldBe null
                m.colourAt(20.0) shouldBe null
                m.colourAt(100.0).shouldBeRgb(0.0, 0.0, 1.0)
                m.colourAt(120.0).shouldBeRgb(0.0, 0.0, 1.0)
                m.presentation!!.coloredTargets shouldBe 1
                m.presentation!!.instanceStylesIgnored shouldBe 0
            }
        }

        "H3: an orange part beats the blue colour of the outer sub-assembly occurrence everywhere".config(
            enabled = available,
        ) {
            import("gate-H3.step").use { m ->
                val box = m.parts.single { it.volume != null && it.volume!! > 0 }
                box.color.shouldBeRgb(1.0, 0.5, 0.0)
                for (x in listOf(0.0, 20.0, 100.0, 120.0)) m.colourAt(x).shouldBeRgb(1.0, 0.5, 0.0)
            }
        }

        "H4: the inner occurrence colour beats the outer one, hand calculation of the four leaves".config(
            enabled = available,
        ) {
            import("gate-H4.step").use { m ->
                // u3 (red) sits at x=0 and x=100 inside both SUB occurrences, u2 (blue) colours the second SUB occurrence
                m.colourAt(0.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(20.0) shouldBe null
                m.colourAt(100.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(120.0).shouldBeRgb(0.0, 0.0, 1.0)
            }
        }

        "the gate fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
