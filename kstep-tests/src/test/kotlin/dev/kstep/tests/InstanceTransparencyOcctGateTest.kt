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
import kotlin.time.Duration.Companion.seconds

/**
 * The gate of the Welle Y amendment to ADR-0044 as a CI test: OCCT's own XCAF reader reads the hand-written translucent
 * pattern (fixtures `instance-colour/gate-Y*.step`, made by `scripts/interop/make_instance_colour_fixtures.py` with a fourth
 * colour component) as an alpha of the instance colour. Scene V is a 10 x 20 x 30 box used twice (x=0, x=50), scene X is
 * ASM > SUB x2 (x=0, x=100) > BOX x2 (x=0, x=20). The class name carries `Occt` so the CI skip gate covers it.
 */
class InstanceTransparencyOcctGateTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun import(name: String): ImportedStepModel {
            val bytes =
                requireNotNull(
                    InstanceTransparencyOcctGateTest::class.java.getResourceAsStream("/instance-colour/$name"),
                ) { name }
                    .use { it.readBytes() }
            val result = StepImporter.read(bytes)
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model
        }

        fun RgbColor?.shouldBeRgba(
            r: Double,
            g: Double,
            b: Double,
            a: Double,
        ) {
            val c = this!!
            c.red shouldBe (r plusOrMinus 1e-6)
            c.green shouldBe (g plusOrMinus 1e-6)
            c.blue shouldBe (b plusOrMinus 1e-6)
            c.alpha shouldBe (a plusOrMinus 1e-6)
        }

        fun ImportedStepModel.colourAt(minX: Double): RgbColor? =
            occurrences.single { Math.abs(it.boundingBox!!.minX - minX) < 1e-6 }.color

        "Y1: instance 2 blue with alpha 0.5, instance 1 and the part stay uncoloured".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-Y1.step").use { m ->
                m.parts.single().color shouldBe null
                m.occurrences.size shouldBe 2
                m.totals.volume!! shouldBe (12000.0 plusOrMinus 1e-6)
                m.colourAt(0.0) shouldBe null
                m.colourAt(50.0).shouldBeRgba(0.0, 0.0, 1.0, 0.5)
                m.presentation!!.coloredTargets shouldBe 1
                m.presentation!!.instanceStylesIgnored shouldBe 0
            }
        }

        "Y3: an opaque orange part is not repainted and leaks no alpha, the translucent instance wins on its occurrence"
            .config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                import("gate-Y3.step").use { m ->
                    m.parts
                        .single()
                        .color
                        .shouldBeRgba(1.0, 0.5, 0.0, 1.0)
                    m.colourAt(0.0).shouldBeRgba(1.0, 0.5, 0.0, 1.0)
                    m.colourAt(50.0).shouldBeRgba(0.0, 0.0, 1.0, 0.5)
                    m.presentation!!.instanceStylesIgnored shouldBe 0
                }
            }

        "Y3b: an opaque instance colour over a translucent part colour supplies its whole colour, alpha included"
            .config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                import("gate-Y3b.step").use { m ->
                    m.parts
                        .single()
                        .color
                        .shouldBeRgba(1.0, 0.5, 0.0, 0.5)
                    m.colourAt(0.0).shouldBeRgba(1.0, 0.5, 0.0, 0.5)
                    m.colourAt(50.0).shouldBeRgba(0.0, 0.0, 1.0, 1.0)
                }
            }

        "Y4: a translucent sub-assembly occurrence colours its two leaves, the other sub-assembly and the part stay none"
            .config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                import("gate-Y4.step").use { m ->
                    m.parts.single { (it.volume ?: 0.0) > 0 }.color shouldBe null
                    m.totals.volume!! shouldBe (24000.0 plusOrMinus 1e-6)
                    m.colourAt(0.0) shouldBe null
                    m.colourAt(20.0) shouldBe null
                    m.colourAt(100.0).shouldBeRgba(0.0, 0.0, 1.0, 0.5)
                    m.colourAt(120.0).shouldBeRgba(0.0, 0.0, 1.0, 0.5)
                    m.presentation!!.instanceStylesIgnored shouldBe 0
                }
            }

        "Y5: the inner opaque red beats the outer translucent blue, hand calculation of the four leaves".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-Y5.step").use { m ->
                m.colourAt(0.0).shouldBeRgba(1.0, 0.0, 0.0, 1.0)
                m.colourAt(20.0) shouldBe null
                m.colourAt(100.0).shouldBeRgba(1.0, 0.0, 0.0, 1.0)
                m.colourAt(120.0).shouldBeRgba(0.0, 0.0, 1.0, 0.25)
            }
        }

        "Y6: an alpha that needs float rounding survives within 1e-6".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-Y6.step").use { m ->
                m.colourAt(0.0) shouldBe null
                m.colourAt(50.0).shouldBeRgba(0.0, 0.0, 1.0, 0.7529411764705882)
            }
        }

        // Hostile alpha (SURFACE_STYLE_TRANSPARENT of -0.5, 1.5, 1.E400): never a crash, never an alpha outside 0..1 or NaN
        for (name in listOf("tamper-alpha-neg.step", "tamper-alpha-gt1.step", "tamper-alpha-huge.step")) {
            "hostile transparency in $name never leaves 0..1 or crashes the import".config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                val bytes =
                    InstanceTransparencyOcctGateTest::class.java
                        .getResourceAsStream("/instance-colour/$name")!!
                        .use { it.readBytes() }
                val result = StepImporter.read(bytes)
                if (result is StepImportResult.Success) {
                    result.model.use { m ->
                        val colours = m.parts.mapNotNull { it.color } + m.occurrences.mapNotNull { it.color }
                        for (c in colours) {
                            (c.alpha.isFinite() && c.alpha in 0.0..1.0) shouldBe true
                        }
                        m.occurrences.size shouldBe 2
                    }
                }
            }
        }

        "the gate fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
