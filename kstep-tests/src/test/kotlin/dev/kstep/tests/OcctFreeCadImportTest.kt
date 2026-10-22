package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A STEP file written by a FOREIGN tool (FreeCAD 1.1.4) is read back by kSTEP (ADR-0030). Two tests: the
 * committed fixture runs wherever OCCT does (so Linux CI proves a foreign writer too); the live test
 * additionally regenerates the file with `freecadcmd` when FreeCAD is installed. The class name carries
 * `Occt` so the CI skip gate sees these tests.
 */
class OcctFreeCadImportTest :
    StringSpec({
        val workDir = File("build/freecad-import").apply { mkdirs() }
        val available = OcctKernel.availability() is OcctAvailability.Available

        val freecad: File? =
            listOfNotNull(
                System.getenv("FREECADCMD"),
                "/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd",
            ).map(::File).firstOrNull { it.isFile && it.canExecute() }
                ?: System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .map { File(it, "freecadcmd") }
                    .firstOrNull { it.isFile && it.canExecute() }

        fun assertFreeCadBox(step: File) {
            val result = StepImporter.read(step.path)
            val model = (result as StepImportResult.Success).model
            model.use {
                it.parts.size shouldBe 1
                val part = it.parts.single()
                part.name shouldContain "FreeCADBox"
                part.volume!! shouldBe (6000.0 plusOrMinus 1e-6)
                part.topology!!.faces shouldBe 6
                val box = part.boundingBox.shouldNotBeNull()
                box.minX shouldBe (0.0 plusOrMinus 1e-4)
                box.maxX shouldBe (10.0 plusOrMinus 1e-4)
                box.maxY shouldBe (20.0 plusOrMinus 1e-4)
                box.maxZ shouldBe (30.0 plusOrMinus 1e-4)
            }
        }

        "the committed FreeCAD 1.1.4 box fixture imports with exact volume, faces and bounding box".config(
            enabled = available,
        ) {
            val target = File(workDir, "freecad-box-1.1.4.step")
            val input =
                requireNotNull(OcctFreeCadImportTest::class.java.getResourceAsStream("/import/freecad-box-1.1.4.step"))
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            assertFreeCadBox(target)
        }

        "a live freecadcmd box imports exactly (skipped when FreeCAD is not installed)".config(
            enabled = available && freecad != null,
        ) {
            val out = File(workDir, "live-freecad-box.step").apply { delete() }
            val script = File("../scripts/interop/freecad_make_box.py").absoluteFile
            val process =
                ProcessBuilder(freecad!!.path, script.path)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(File(workDir, "freecad-live.log"))
                    .also { it.environment()["STEP_FILE"] = out.absolutePath }
                    .start()
            val finished = process.waitFor(180, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished shouldBe true
            out.isFile shouldBe true
            assertFreeCadBox(out)
        }
    })
