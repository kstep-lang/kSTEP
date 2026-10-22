package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

/** A native "limit:" failure surfaces as [OcctImportLimitException] (needs the OCCT bridge). */
class OcctImportLimitMappingTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a tiny entity limit becomes OcctImportLimitException".config(enabled = available) {
            val dir = File("build/limit-mapping").apply { mkdirs() }
            val file = File(dir, "box.step")
            OcctKernel.makeBox(1.0, 2.0, 3.0).use { it.writeStepFile(file.toPath()) }
            val limits = intArrayOf(10, 10_000, 10_000, 2_000, 250_000, 256)
            shouldThrow<OcctImportLimitException> { OcctKernel.readStep(file.readBytes(), limits) }
        }

        "empty content is an IllegalArgumentException before any native call".config(enabled = available) {
            shouldThrow<IllegalArgumentException> { OcctKernel.readStep(ByteArray(0)) }
        }

        // The placed-assembly fixture (`kstep export export-placed-assembly.kstep.kts`, see README.adoc in
        // kstep-tests' import fixtures) has: 8 XCAF nodes (1 assembly + 3 parts + 4 components), 4 components,
        // 3 parts, 18 faces in total (three boxes) and the root assembly name "Baugruppe".
        fun placedAssembly(): ByteArray =
            requireNotNull(OcctImportLimitMappingTest::class.java.getResourceAsStream("/import/placed-assembly.step")) {
                "placed-assembly.step"
            }.use { it.readBytes() }

        // Index order of the native limits array: entities, nodes, components, parts, faces, name chars.
        fun limits(
            nodes: Int = 10_000,
            components: Int = 10_000,
            parts: Int = 2_000,
            faces: Int = 250_000,
            nameChars: Int = 256,
        ) = intArrayOf(1_000_000, nodes, components, parts, faces, nameChars)

        fun limitMessage(limits: IntArray): String =
            shouldThrow<OcctImportLimitException> { OcctKernel.readStep(placedAssembly(), limits) }.message.orEmpty()

        "StepImportLimits.nativeLimits keeps the index order the native code reads" {
            StepImportLimits.nativeLimits().toList() shouldBe
                listOf(
                    StepImportLimits.MAX_ENTITIES,
                    StepImportLimits.MAX_NODES,
                    StepImportLimits.MAX_COMPONENTS,
                    StepImportLimits.MAX_PARTS,
                    StepImportLimits.MAX_TOTAL_FACES,
                    StepImportLimits.MAX_NAME_CHARS,
                )
        }

        "limits exactly at the fixture's size are accepted (no off-by-one)".config(enabled = available) {
            OcctKernel
                .readStep(placedAssembly(), limits(nodes = 8, components = 4, parts = 3, faces = 18))
                .use { doc ->
                    doc.nodes.size shouldBe 8
                }
        }

        "a part limit below the part count is a part-count limit failure".config(enabled = available) {
            limitMessage(limits(parts = 1)) shouldContain "limit: part count"
            limitMessage(limits(parts = 2)) shouldContain "limit: part count"
        }

        "a component limit below the component count is a component-count limit failure".config(enabled = available) {
            limitMessage(limits(components = 1)) shouldContain "limit: component count"
            limitMessage(limits(components = 3)) shouldContain "limit: component count"
        }

        "a face limit below the summed face count is a face-count limit failure".config(enabled = available) {
            limitMessage(limits(faces = 5)) shouldContain "limit: face count"
            limitMessage(limits(faces = 17)) shouldContain "limit: face count"
        }

        "a node limit below the node count is a node-count limit failure".config(enabled = available) {
            limitMessage(limits(nodes = 2)) shouldContain "limit: node count"
            limitMessage(limits(nodes = 7)) shouldContain "limit: node count"
        }

        "names are truncated to the name limit, not rejected".config(enabled = available) {
            OcctKernel.readStep(placedAssembly(), limits(nameChars = 3)).use { doc ->
                doc.nodes.all { it.name.length <= 3 } shouldBe true
                doc.nodes.any { it.name == "Bau" } shouldBe true
            }
        }
    })
