package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.script.KStepScriptHost
import dev.kstep.script.KStepScriptOutcome
import dev.kstep.shape.Ap242ShapeExporter
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A STEP file with colours and layers written by kSTEP is read by a FOREIGN tool, FreeCAD (ADR-0039). Geometry, volume
 * and placement are checked; colours are not readable in a headless `freecadcmd` (see `freecad_read_styles.py`), which
 * the ADR records as a limit of this check. The class name carries `Occt` so the CI skip gate sees the test.
 */
class OcctFreeCadStyledExportTest :
    StringSpec({
        val workDir = File("build/freecad-styled-export").apply { mkdirs() }
        val available = OcctKernel.availability() is OcctAvailability.Available
        val freecad: File? =
            listOfNotNull(System.getenv("FREECADCMD"), "/Applications/FreeCAD.app/Contents/Resources/bin/freecadcmd")
                .map(::File)
                .firstOrNull { it.isFile && it.canExecute() }
                ?: System
                    .getenv("PATH")
                    .orEmpty()
                    .split(File.pathSeparator)
                    .map { File(it, "freecadcmd") }
                    .firstOrNull { it.isFile && it.canExecute() }

        "FreeCAD reads a coloured two-part assembly with the right volumes and placement (skipped when FreeCAD is not installed)"
            .config(enabled = available && freecad != null) {
                val resource =
                    requireNotNull(
                        OcctFreeCadStyledExportTest::class.java.getResourceAsStream(
                            "/export-colour-assembly-two-parts.kstep.kts",
                        ),
                    )
                val outcome = KStepScriptHost.eval(resource.use { String(it.readBytes(), Charsets.UTF_8) })
                val model = (outcome as KStepScriptOutcome.Success).model
                val out = File(workDir, "styled-assembly.step").apply { delete() }
                out.writeText(Ap242ShapeExporter.export(model.header, model.roots, model.shapes, model.placements))

                val script = File("../scripts/interop/freecad_read_styles.py").absoluteFile
                val log = File(workDir, "freecad-styled.log")
                val process =
                    ProcessBuilder(freecad!!.path, script.path)
                        .directory(workDir)
                        .redirectErrorStream(true)
                        .redirectOutput(log)
                        .also { it.environment()["STEP_FILE"] = out.absolutePath }
                        .start()
                val finished = process.waitFor(180, TimeUnit.SECONDS)
                if (!finished) process.destroyForcibly()
                finished shouldBe true
                val text = log.readText()
                text shouldContain "Red part volume 6000.000000 bbox [0.000,10.000]x[0.000,20.000]x[0.000,30.000]"
                text shouldContain "Green part volume 1000.000000 bbox [30.000,40.000]x[0.000,10.000]x[0.000,10.000]"
            }
    })
