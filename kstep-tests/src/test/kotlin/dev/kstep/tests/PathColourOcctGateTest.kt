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
 * The gate of Welle AA (ADR-0048) as a CI test, pinned as a tripwire: kSTEP's importer does NOT deliver a colour that a
 * hand-written `SPECIFIED_HIGHER_USAGE_OCCURRENCE` puts on one path of an assembly (fixtures in the resource folder `path-colour`, made by
 * `scripts/interop/make_path_colour_fixtures.py`; see `scripts/interop/verify-path-colour-gate.sh`). The reader runs with
 * `SetSHUOMode(false)`, so the path colour is not delivered, and the gate failed anyway because OCCT's own presentation
 * logic (`XCAFPrs_DocumentExplorer`) ignores SHUO colours. The per-usage and part colours stay as before (ADR-0046).
 * Scene: ASM holds SUB twice (u1 at x=0, u2 at x=100), SUB holds a 10 x 20 x 30 box twice (u3 at x=0, u4 at x=20); box volume
 * 6000, four occurrences, total 24000. The OCCT nature of the measurement itself (the reader with SHUO mode on and the
 * explorer) is only in the script, not here. The class name carries `Occt` so the CI skip gate covers it.
 */
class PathColourOcctGateTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun read(name: String): StepImportResult {
            val bytes =
                requireNotNull(PathColourOcctGateTest::class.java.getResourceAsStream("/path-colour/$name")) { name }
                    .use { it.readBytes() }
            return StepImporter.read(bytes)
        }

        fun import(name: String): ImportedStepModel {
            val result = read(name)
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model
        }

        fun ImportedStepModel.colourAt(minX: Double): RgbColor? =
            occurrences.single { Math.abs(it.boundingBox!!.minX - minX) < 1e-6 }.color

        fun RgbColor?.shouldBeRgb(
            r: Double,
            g: Double,
            b: Double,
        ) {
            val c = this!!
            c.red shouldBe (r plusOrMinus 1e-6)
            c.green shouldBe (g plusOrMinus 1e-6)
            c.blue shouldBe (b plusOrMinus 1e-6)
        }

        fun ImportedStepModel.shouldHaveFourUncolouredOccurrences() {
            occurrences.size shouldBe 4
            totals.volume!! shouldBe (24000.0 plusOrMinus 1e-6)
            for (x in listOf(0.0, 20.0, 100.0, 120.0)) colourAt(x) shouldBe null
        }

        for (name in listOf("gate-P1", "gate-P5", "gate-P7")) {
            "$name: the green path colour is not delivered, nothing is coloured".config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                import("$name.step").use { m -> m.shouldHaveFourUncolouredOccurrences() }
            }
        }

        "P2: the per-usage colour of u3 stays, the path colour is not delivered".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-P2.step").use { m ->
                m.occurrences.size shouldBe 4
                m.colourAt(0.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(100.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(20.0) shouldBe null
                m.colourAt(120.0) shouldBe null
            }
        }

        "P3: the orange part colour stays on all four occurrences".config(enabled = available, timeout = 60.seconds) {
            import("gate-P3.step").use { m ->
                m.occurrences.size shouldBe 4
                for (x in listOf(0.0, 20.0, 100.0, 120.0)) m.colourAt(x).shouldBeRgb(1.0, 0.5, 0.0)
            }
        }

        "P4b: the ADR-0046 precedence is unchanged, the path colour is not delivered".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-P4b.step").use { m ->
                m.colourAt(0.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(20.0).shouldBeRgb(1.0, 0.5, 0.0)
                m.colourAt(100.0).shouldBeRgb(1.0, 0.0, 0.0)
                m.colourAt(120.0).shouldBeRgb(1.0, 0.5, 0.0)
            }
        }

        "P6 (negative): without the shape definition the colour lands on the shared part".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-P6.step").use { m ->
                m.occurrences.size shouldBe 4
                for (x in listOf(0.0, 20.0, 100.0, 120.0)) m.colourAt(x).shouldBeRgb(0.0, 1.0, 0.0)
            }
        }

        val tamperFixtures =
            listOf(
                "tamper-cycle",
                "tamper-nonchain",
                "tamper-selfref",
                "tamper-deep200",
                "tamper-alpha-negative",
                "tamper-alpha-over",
                "tamper-alpha-huge",
            )
        for (name in tamperFixtures) {
            "$name: no crash, no hang, a typed result, alpha stays in 0..1".config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                when (val result = read("$name.step")) {
                    is StepImportResult.Failure -> result.message.isNotEmpty() shouldBe true
                    is StepImportResult.Success ->
                        result.model.use { m ->
                            m.occurrences.mapNotNull { it.color }.all { it.alpha in 0.0..1.0 } shouldBe true
                        }
                }
            }
        }

        "the gate fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
