package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.importer.ImportedStepModel
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/**
 * The gate of Welle Z (ADR-0047) as a CI test, pinned as a tripwire: OCCT's STEP reader does NOT deliver a
 * PRESENTATION_LAYER_ASSIGNMENT on an assembly usage to the component label for any schema-valid pattern kSTEP can write
 * (fixtures `instance-layer/gate-*.step`, made by `scripts/interop/make_instance_layer_fixtures.py`; see
 * `scripts/interop/verify-instance-layer-gate.sh`). Scene V is a 10 x 20 x 30 box used twice (x=0, x=50), scene X is
 * ASM > SUB x2 > BOX x2. The only thing a layer assigned to the solids does is land on the SHARED part (p3 control).
 *
 * If a future OCCT starts delivering the layer, these tests turn red and ADR-0047 must be reassessed. The class name
 * carries `Occt` so the CI skip gate covers it.
 */
class InstanceLayerOcctGateTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun import(name: String): ImportedStepModel {
            val resource = "/instance-layer/$name"
            val bytes =
                requireNotNull(InstanceLayerOcctGateTest::class.java.getResourceAsStream(resource)) { name }
                    .use { it.readBytes() }
            val result = StepImporter.read(bytes)
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model
        }

        fun ImportedStepModel.shouldHaveNoInstanceLayer() {
            presentation!!.instanceStylesIgnored shouldBe 0
            presentation!!.layerAssignments shouldBe 0
            parts.all { it.layers.isEmpty() && it.bodies.orEmpty().all { b -> b.layers.isEmpty() } } shouldBe true
            parts.all { p -> p.faceStyles.all { it.layers.isEmpty() } } shouldBe true
        }

        for (pattern in listOf("p1", "p2", "p4")) {
            "L1-$pattern: the layer assigned to usage 2 is lost, nothing lands on the part".config(
                enabled = available,
                timeout = 60.seconds,
            ) {
                import("gate-L1-$pattern.step").use { m ->
                    m.occurrences.size shouldBe 2
                    m.totals.volume!! shouldBe (12000.0 plusOrMinus 1e-6)
                    m.shouldHaveNoInstanceLayer()
                }
            }
        }

        "L1-p3 (control): a layer assigned to the solids lands on the shared part, never on one usage".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-L1-p3.step").use { m ->
                m.occurrences.size shouldBe 2
                m.parts.single().layers shouldBe listOf("Layer-I")
                m.presentation!!.instanceStylesIgnored shouldBe 0
            }
        }

        "L3: the part layer stays, the usage layer is lost".config(enabled = available, timeout = 60.seconds) {
            import("gate-L3.step").use { m ->
                m.parts.single().layers shouldBe listOf("Layer-P")
                m.presentation!!.instanceStylesIgnored shouldBe 0
            }
        }

        "L5: a layer on a sub-assembly occurrence is lost".config(enabled = available, timeout = 60.seconds) {
            import("gate-L5.step").use { m ->
                m.totals.volume!! shouldBe (24000.0 plusOrMinus 1e-6)
                m.shouldHaveNoInstanceLayer()
            }
        }

        "L6: layer plus colour on one usage keeps the colour and loses the layer".config(
            enabled = available,
            timeout = 60.seconds,
        ) {
            import("gate-L6.step").use { m ->
                val blue = m.occurrences.single { Math.abs(it.boundingBox!!.minX - 50.0) < 1e-6 }.color!!
                blue.red shouldBe (0.0 plusOrMinus 1e-6)
                blue.blue shouldBe (1.0 plusOrMinus 1e-6)
                m.presentation!!.instanceStylesIgnored shouldBe 0
                m.presentation!!.layerAssignments shouldBe 0
            }
        }

        "the gate fails predictably when the native bridge is unavailable".config(enabled = !available) {
            (OcctKernel.availability() is OcctAvailability.Available) shouldBe false
        }
    })
