package dev.kstep.tests

import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.Placement
import dev.kstep.render.gltf.GlbWriter
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Writes real, on-disk `.glb` fixtures for manual (and, gated, automated) validation against the
 * official Khronos `gltf-validator` -- the GLB analogue of [OcctBoxValidationExportTest]'s STEP
 * fixture, for docs/adr/ADR-0016-gltf-glb-export.adoc. `GlbWriterTest` (in `kstep-render`)
 * already covers the writer's byte-level contract with no OCCT at all; this class is the
 * OCCT-gated, "does a REAL triangulated shape actually validate as real glTF" half of that
 * coverage, plus the gated Node-subprocess cross-check itself.
 */
class GltfValidationExportTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val outDir = File("build/gltf-validation").apply { mkdirs() }

        "a 20x30x40mm box exports to a real, non-empty .glb fixture".config(enabled = available) {
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val mesh = box.triangulate()
                val result = GlbWriter.write(mesh)
                val target = File(outDir, "box.glb")
                target.writeBytes(result.bytes)

                target.exists() shouldBe true
                (target.length() > 0) shouldBe true
                result.triangleCount shouldBe mesh.triangleCount
                result.droppedTriangleCount shouldBe 0
            }
        }

        "a filleted shape (more, non-axis-aligned triangles) exports to a real .glb fixture".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                OcctKernel.fillet(box, edgeIndex = 0, radius = 5.0).use { rounded ->
                    val mesh = rounded.triangulate()
                    val result = GlbWriter.write(mesh)
                    val target = File(outDir, "fillet.glb")
                    target.writeBytes(result.bytes)

                    target.exists() shouldBe true
                    (target.length() > 0) shouldBe true
                    // A fillet adds curved faces beyond the box's flat 12 triangles.
                    (result.triangleCount > 12) shouldBe true
                }
            }
        }

        "a geometry-free model exports to a real, valid, empty .glb fixture" {
            val result = GlbWriter.writeEmptyScene()
            val target = File(outDir, "empty.glb")
            target.writeBytes(result.bytes)

            target.exists() shouldBe true
            (target.length() > 0) shouldBe true
            result.triangleCount shouldBe 0
        }

        fun validateWithKhronos(
            glbFile: File,
            expectTriangles: Int,
            expectVertices: Int,
        ) {
            val scriptsDir = File("../scripts")
            val validatorScript = File(scriptsDir, "validate-gltf.mjs")
            val nodeModules = File(scriptsDir, "node_modules")
            require(validatorScript.exists()) {
                "scripts/validate-gltf.mjs not found at ${validatorScript.absolutePath}"
            }
            require(nodeModules.isDirectory) {
                "scripts/node_modules not found at ${nodeModules.absolutePath} -- run 'npm ci' in " +
                    "scripts/ first (see README's glTF/GLB validation section). This is a hard " +
                    "failure, not a skip: -Pkstep.gltf.validate=true means the validator MUST " +
                    "actually run."
            }

            // Output is redirected to a file rather than read from process.inputStream: reading a
            // pipe via readText() blocks until EOF and must happen BEFORE waitFor(timeout) can be
            // trusted to bound anything -- a hung validator (or a grandchild process that inherits
            // and holds the stdout fd open) would otherwise block this thread forever, past the
            // documented 60s/destroyForcibly() guard below. Redirecting to a file lets waitFor
            // alone enforce the timeout, and the process is always cleaned up in finally so a
            // hung/timed-out run never leaks the child process or its file descriptors.
            val outputFile = File.createTempFile("gltf-validator-", ".log", outDir)
            val process =
                try {
                    ProcessBuilder(
                        "node",
                        validatorScript.absolutePath,
                        glbFile.absolutePath,
                        "--expect-triangles",
                        expectTriangles.toString(),
                        "--expect-vertices",
                        expectVertices.toString(),
                    ).directory(scriptsDir)
                        .redirectErrorStream(true)
                        .redirectOutput(outputFile)
                        .start()
                } catch (e: Exception) {
                    outputFile.delete()
                    throw e
                }
            try {
                val finished = process.waitFor(60, TimeUnit.SECONDS)
                if (!finished) {
                    error("validate-gltf.mjs did not finish within 60s")
                }
                val output = outputFile.readText()
                withClue(output) { process.exitValue() shouldBe 0 }
            } finally {
                process.destroyForcibly()
                outputFile.delete()
            }
        }

        "a real OCCT box's .glb passes the Khronos gltf-validator with zero errors/warnings".config(
            enabled = available && System.getProperty("kstep.gltf.validate") == "true",
        ) {
            val glbFile = File(outDir, "gate-box.glb")
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val mesh = box.triangulate()
                glbFile.writeBytes(GlbWriter.write(mesh).bytes)
            }
            validateWithKhronos(glbFile, expectTriangles = 12, expectVertices = 36)
        }

        // ADR-0035: a placed (translated and turned) two-part assembly merged into one mesh is
        // still one valid glTF mesh; same gate, same hard-fail behaviour as the box case above.
        "a placed two-box assembly's .glb passes the Khronos gltf-validator with zero errors/warnings".config(
            enabled = available && System.getProperty("kstep.gltf.validate") == "true",
        ) {
            val glbFile = File(outDir, "gate-placed.glb")
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { box ->
                val mesh = box.triangulate()
                val placed =
                    MeshComposition.merge(
                        listOf(
                            PlacedMesh(mesh),
                            PlacedMesh(mesh, Placement.rotationZ(90.0).then(Placement.translation(30.0, 0.0, 0.0))),
                        ),
                    )
                glbFile.writeBytes(GlbWriter.write(placed).bytes)
            }
            validateWithKhronos(glbFile, expectTriangles = 24, expectVertices = 72)
        }
    })
