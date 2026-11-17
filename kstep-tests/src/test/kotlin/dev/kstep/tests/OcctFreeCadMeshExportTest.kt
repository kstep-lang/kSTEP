package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The STL and 3MF written by kSTEP are read by a FOREIGN tool, FreeCAD's Mesh module (ADR-0049). The volume of the
 * 10 x 20 x 30 mm box must be 6000 mm3. Skipped when FreeCAD is not installed (the report then says "external reader:
 * not checked"). The class name carries `Occt` so the CI skip gate sees the test.
 */
class OcctFreeCadMeshExportTest :
    StringSpec({
        val workDir = File("build/freecad-mesh-export").apply { mkdirs() }
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

        "FreeCAD reads the STL and 3MF box with volume 6000 (skipped when FreeCAD is not installed)"
            .config(enabled = available && freecad != null) {
                val runner = CliProcessRunner(workDir)
                val resource = OcctFreeCadMeshExportTest::class.java.getResourceAsStream("/hello-box.kstep.kts")
                requireNotNull(resource).use { input ->
                    File(workDir, "hello-box.kstep.kts").outputStream().use { output -> input.copyTo(output) }
                }
                val script = File("../scripts/interop/freecad_read_mesh.py").absoluteFile
                for (ext in listOf("stl", "3mf")) {
                    val out = File(workDir, "box.$ext").apply { delete() }
                    runner.run("render", "hello-box.kstep.kts", "-f", ext, "--out", out.name).exitCode shouldBe 0
                    val log = File(workDir, "freecad-$ext.log")
                    val process =
                        ProcessBuilder(freecad!!.path, script.path)
                            .directory(workDir)
                            .redirectErrorStream(true)
                            .redirectOutput(log)
                            .also { it.environment()["MESH_FILE"] = out.absolutePath }
                            .start()
                    val finished = process.waitFor(180, TimeUnit.SECONDS)
                    if (!finished) process.destroyForcibly()
                    finished shouldBe true
                    log.readText() shouldContain "Mesh volume 6000.0"
                }
            }
    })
