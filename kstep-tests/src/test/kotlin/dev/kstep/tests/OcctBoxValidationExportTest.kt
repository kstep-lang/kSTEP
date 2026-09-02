package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Analogous to [FreeCadValidationExportTest], but for the OCCT geometry bridge (Geometrie
 * Welle 1, see docs/adr/ADR-0005-occt-jni-bridge.adoc): writes a real, on-disk AP242 STEP file
 * containing an actual `MANIFOLD_SOLID_BREP`, for manual re-import into a third-party STEP
 * viewer, alongside `FreeCadValidationExportTest`'s existing product-structure-only fixture.
 *
 * kSTEP-ADR-0001's V1 acceptance criterion 2 ("a B-Rep object is exported via STEP and opens in a
 * common STEP viewer") could not be satisfied by V1 itself -- V1 deliberately excludes B-Rep
 * geometry entirely, so `Part21Writer`'s own output carries no shape a viewer could render at
 * all. This is the first file in this repository whose STEP output contains real, viewable
 * geometry.
 */
class OcctBoxValidationExportTest :
    StringSpec({
        "a 20x30x40mm box exports to a real .step file for manual STEP-viewer import".config(
            enabled = OcctKernel.availability() is OcctAvailability.Available,
        ) {
            val outDir = File("build/occt-validation").apply { mkdirs() }
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val written = box.writeStepFile(File(outDir, "box.step").toPath())
                val writtenFile = written.toFile()
                writtenFile.exists() shouldBe true
                (writtenFile.length() > 0) shouldBe true
            }
        }
    })
