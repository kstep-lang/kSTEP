package dev.kstep.shape.importer

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * The Kotlin-side expansion limit fires AFTER the part shapes were allocated (ADR-0038): the importer must
 * release them. The class name carries `Occt` so the CI skip gate sees it.
 */
class StepImporterLimitOcctTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a LimitExceeded after the part shapes were allocated closes them (LIMIT_EXCEEDED, no leaked handle)".config(
            enabled = available,
        ) {
            val file = File("build/importer-limit").apply { mkdirs() }.resolve("box.step")
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { it.writeStepFile(file.toPath()) }
            val content = file.readBytes()
            val shapesBefore = OcctBridge.nativeLiveShapeCount()
            val sessionsBefore = OcctBridge.nativeImportSessionCount()
            repeat(20) {
                val result = StepImporter.read(content, maxDepth = 0, maxOccurrences = 0)
                (result as StepImportResult.Failure).kind shouldBe StepImportErrorKind.LIMIT_EXCEEDED
            }
            (OcctBridge.nativeLiveShapeCount() - shapesBefore <= 2) shouldBe true
            (OcctBridge.nativeImportSessionCount() - sessionsBefore <= 2) shouldBe true
        }

        "the same content imports normally with the default limits".config(enabled = available) {
            val file = File("build/importer-limit").apply { mkdirs() }.resolve("box2.step")
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { it.writeStepFile(file.toPath()) }
            val result = StepImporter.read(file.readBytes())
            (result as StepImportResult.Success).model.use { it.occurrences.size shouldBe 1 }
        }
    })
