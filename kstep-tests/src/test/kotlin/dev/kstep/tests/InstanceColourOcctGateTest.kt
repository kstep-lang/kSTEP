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
 * The gate of ADR-0044 as a CI test: OCCT's own XCAF reader reads the hand-written `PRESENTATION_STYLE_BY_CONTEXT`
 * pattern (fixtures `instance-colour/gate-G*.step`, made by `scripts/interop/make_instance_colour_fixtures.py`) as a colour of the
 * assembly component. The class name carries `Occt` so the CI skip gate covers it.
 */
class InstanceColourOcctGateTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun import(name: String): ImportedStepModel {
            val bytes =
                requireNotNull(
                    InstanceColourOcctGateTest::class.java.getResourceAsStream("/instance-colour/$name"),
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

        "G1: only instance 2 blue gives component 2 blue, component 1 none and no part colour".config(
            enabled = available,
        ) {
            import("gate-G1.step").use { m ->
                m.parts.size shouldBe 1
                m.parts[0].volume!! shouldBe (6000.0 plusOrMinus 1e-6)
                m.parts[0].color shouldBe null
                val components = m.assemblies.single().components
                components.size shouldBe 2
                components[0].color shouldBe null
                components[1].color.shouldBeRgb(0.0, 0.0, 1.0)
                m.occurrences.size shouldBe 2
                m.totals.volume!! shouldBe (12000.0 plusOrMinus 1e-6)
                m.presentation!!.coloredTargets shouldBe 1
                m.presentation!!.instanceStylesIgnored shouldBe 0
            }
        }

        "G2: both instances coloured are read as red and blue".config(enabled = available) {
            import("gate-G2.step").use { m ->
                val components = m.assemblies.single().components
                components[0].color.shouldBeRgb(1.0, 0.0, 0.0)
                components[1].color.shouldBeRgb(0.0, 0.0, 1.0)
                m.parts[0].color shouldBe null
                m.presentation!!.coloredTargets shouldBe 2
            }
        }

        "G3: an orange part and a blue instance stay apart".config(enabled = available) {
            import("gate-G3.step").use { m ->
                m.parts[0].color.shouldBeRgb(1.0, 0.5, 0.0)
                val components = m.assemblies.single().components
                components[0].color shouldBe null
                components[1].color.shouldBeRgb(0.0, 0.0, 1.0)
                m.presentation!!.coloredTargets shouldBe 2
            }
        }

        "G6: a colour that is not exactly representable survives the sRGB round trip within 1e-6".config(
            enabled = available,
        ) {
            import("gate-G6.step").use { m ->
                val components = m.assemblies.single().components
                components[0].color.shouldBeRgb(0xC0 / 255.0, 0.3, 0.25)
                components[1].color.shouldBeRgb(0.25, 0.3, 0xC0 / 255.0)
            }
        }

        "the gate fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
