package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Analogous to [OcctBoxValidationExportTest], but for Geometrie Welle 5a's feature operations (see
 * docs/adr/ADR-0008-occt-feature-operations.adoc): writes a real, on-disk AP242 STEP file
 * containing an extruded-then-filleted solid (an L-shaped profile, extruded, with one edge
 * rounded), for manual re-import into a third-party STEP viewer -- the first file in this
 * repository whose viewable geometry came from more than a single primitive box.
 */
class OcctFeatureValidationExportTest :
    StringSpec({
        "an extruded, filleted L-profile exports to a real .step file for manual STEP-viewer import".config(
            enabled = OcctKernel.availability() is OcctAvailability.Available,
        ) {
            val outDir = File("build/occt-validation").apply { mkdirs() }
            val lProfile =
                listOf(
                    ProfilePoint(0.0, 0.0),
                    ProfilePoint(20.0, 0.0),
                    ProfilePoint(20.0, 10.0),
                    ProfilePoint(10.0, 10.0),
                    ProfilePoint(10.0, 20.0),
                    ProfilePoint(0.0, 20.0),
                )
            OcctKernel.extrudeProfile(lProfile, 5.0).use { extruded ->
                OcctKernel.fillet(extruded, edgeIndex = 0, radius = 2.0).use { rounded ->
                    val written = rounded.writeStepFile(File(outDir, "extruded-filleted.step").toPath())
                    val writtenFile = written.toFile()
                    writtenFile.exists() shouldBe true
                    (writtenFile.length() > 0) shouldBe true
                }
            }
        }
    })
