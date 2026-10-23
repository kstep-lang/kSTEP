package dev.kstep.preview

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Placement-aware preview (ADR-0035): scripts are inline so the hand calculations sit next to them.
private const val PRELUDE =
    """
    val appCtx = applicationContext { application = "config control" }.getOrThrow()
    val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
    val defCtx = productDefinitionContext { name = "engineering"; frameOfReference = appCtx; lifeCycleStage = "design" }.getOrThrow()
    fun pd(id: String): ProductDefinition {
        val p = product(id) { name = id; frameOfReference = setOf(prodCtx) }.getOrThrow()
        val f = productDefinitionFormation("${'$'}id-F") { ofProduct = p }.getOrThrow()
        return productDefinition("${'$'}id-D") { formation = f; frameOfReference = defCtx }.getOrThrow()
    }
    fun usage(id: String, parent: ProductDefinition, child: ProductDefinition) =
        nextAssemblyUsageOccurrence(id) { name = id; relatingProductDefinition = parent; relatedProductDefinition = child }.getOrThrow()
    """

// Two 10 mm cubes; P2 is moved by (30,0,0) and optionally turned 90 degrees about Z.
// Turned:   (x,y,z) -> (-y,x,z) maps [0,10]^3 to x[-10,0] y[0,10]; +30 in x gives x[20,30]; whole bbox x[0,30].
// Unturned: P2 spans x[30,40]; whole bbox x[0,40].
private fun twoBoxes(rotation: String) =
    PRELUDE +
        """
        val asm = pd("ASM"); val p1 = pd("P1"); val p2 = pd("P2")
        val u1 = usage("U1", asm, p1); val u2 = usage("U2", asm, p2)
        stepFile(fileName = "two.step") {
            roots(u1, u2)
            shape(p1, OcctKernel.makeBox(10.0, 10.0, 10.0))
            shape(p2, OcctKernel.makeBox(10.0, 10.0, 10.0))
            place(u2, translation = vec(30.0, 0.0, 0.0)$rotation)
        }
        """

// TOP -> SUB (+100 in x, 90 deg about Z) -> LEAF (box 10x20x5, +5 in x). Hand calculation:
// x [5,15] -> rotation (-y,x,z): x[-20,0] y[5,15] -> +100: x[80,100] y[5,15] z[0,5].
private val NESTED =
    PRELUDE +
        """
        val top = pd("TOP"); val sub = pd("SUB"); val leaf = pd("LEAF")
        val uSub = usage("U-SUB", top, sub); val uLeaf = usage("U-LEAF", sub, leaf)
        stepFile(fileName = "nested.step") {
            roots(uSub, uLeaf)
            shape(leaf, OcctKernel.makeBox(10.0, 20.0, 5.0))
            place(uSub, translation = vec(100.0, 0.0, 0.0), rotation = Rotation.about(Vec3.Z, 90.0))
            place(uLeaf, translation = vec(5.0, 0.0, 0.0))
        }
        """

// One screw part, used twice: one triangulation, two instances.
private val DOUBLE_USE =
    PRELUDE +
        """
        val asm = pd("ASM"); val screw = pd("SCR")
        val u1 = usage("U1", asm, screw); val u2 = usage("U2", asm, screw)
        stepFile(fileName = "screws.step") {
            roots(u1, u2)
            shape(screw, OcctKernel.makeBox(4.0, 4.0, 12.0))
            place(u2, translation = vec(20.0, 0.0, 0.0))
        }
        """

private data class GlbInfo(
    val json: String,
    val min: List<Double>,
    val max: List<Double>,
    val vertexCount: Int,
)

private fun parseGlb(bytes: ByteArray): GlbInfo {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.getInt(0) shouldBe 0x46546C67
    val jsonLength = buf.getInt(12)
    val json = String(bytes, 20, jsonLength, Charsets.UTF_8)
    val position = json.substring(json.indexOf("\"accessors\""))

    fun array(key: String): List<Double> =
        Regex("\"$key\":\\[([^\\]]*)]")
            .find(position)!!
            .groupValues[1]
            .split(",")
            .map { it.trim().toDouble() }
    val count = Regex("\"count\":(\\d+)").find(position)!!.groupValues[1].toInt()
    return GlbInfo(json, array("min"), array("max"), count)
}

private fun renderGlb(script: String): Pair<PreviewOutcome.Rendered, GlbInfo> {
    val request = PreviewRequest(PreviewSource.InlineScript(script, "placed.kstep.kts"), format = RenderFormat.GLB)
    val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
    return outcome to parseGlb(outcome.bytes)
}

private fun List<Double>.shouldBeNear(vararg expected: Double) {
    size shouldBe expected.size
    forEachIndexed { i, v -> v shouldBe (expected[i] plusOrMinus 1e-4) }
}

class PreviewPlacedAssemblyTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a part turned 90 degrees about Z previews at its hand-calculated place (GLB bounds x 0..30)".config(
            enabled = available,
        ) {
            val (outcome, glb) =
                renderGlb(twoBoxes(", rotation = Rotation.about(Vec3.Z, 90.0)"))
            outcome.contentKind shouldBe PreviewContentKind.GEOMETRY
            outcome.geometry?.instanceCount shouldBe 2
            outcome.geometry?.distinctShapeCount shouldBe 2
            outcome.geometry?.meshTriangleCount shouldBe 24
            glb.vertexCount shouldBe 72
            glb.min.shouldBeNear(0.0, 0.0, 0.0)
            glb.max.shouldBeNear(30.0, 10.0, 10.0)
            outcome.warnings shouldBe emptyList()
        }

        "the same part without the rotation reaches x 40 (control case)".config(enabled = available) {
            val (_, glb) = renderGlb(twoBoxes(""))
            glb.min.shouldBeNear(0.0, 0.0, 0.0)
            glb.max.shouldBeNear(40.0, 10.0, 10.0)
        }

        "nested placements compose from the root down (GLB bounds x 80..100, y 5..15, z 0..5)".config(
            enabled = available,
        ) {
            val (outcome, glb) = renderGlb(NESTED)
            outcome.geometry?.instanceCount shouldBe 1
            glb.min.shouldBeNear(80.0, 5.0, 0.0)
            glb.max.shouldBeNear(100.0, 15.0, 5.0)
        }

        "a part used twice is triangulated once and drawn twice".config(enabled = available) {
            val (outcome, glb) = renderGlb(DOUBLE_USE)
            outcome.geometry?.instanceCount shouldBe 2
            outcome.geometry?.distinctShapeCount shouldBe 1
            outcome.geometry?.meshTriangleCount shouldBe 24
            glb.max.shouldBeNear(24.0, 4.0, 12.0)
        }

        "the GLB is one node and one mesh whatever the instance count".config(enabled = available) {
            val (_, glb) = renderGlb(DOUBLE_USE)
            Regex("\"meshes\":\\[\\{").findAll(glb.json).count() shouldBe 1
            glb.json
                .substringAfter("\"nodes\":[{")
                .substringBefore("]")
                .contains("},{") shouldBe false
        }

        "the merged normals of a part turned 45 degrees about Z are rotated, not translated".config(
            enabled = available,
        ) {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(
                        twoBoxes(", rotation = Rotation.about(Vec3.Z, 45.0)"),
                        "placed.kstep.kts",
                    ),
                    format = RenderFormat.GLB,
                )
            val bytes = (PreviewRenderer.render(request) as PreviewOutcome.Rendered).bytes
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val binStart = 20 + buf.getInt(12) + 8
            val vertexCount = 72
            // Normals follow the positions in the BIN chunk; P2's vertices come after P1's 36.
            val normalsStart = binStart + vertexCount * 12
            var diagonal = 0
            for (v in 36 until vertexCount) {
                val nx = Math.abs(buf.getFloat(normalsStart + v * 12))
                val ny = Math.abs(buf.getFloat(normalsStart + v * 12 + 4))
                if (Math.abs(nx - 0.70710678) < 1e-4 && Math.abs(ny - 0.70710678) < 1e-4) diagonal++
            }
            // 4 side faces x 6 vertices; the two caps keep their +Z/-Z normal.
            diagonal shouldBe 24
        }

        "SVG and PNG of a placed assembly render geometry".config(enabled = available) {
            for (format in listOf(RenderFormat.SVG, RenderFormat.PNG)) {
                val request =
                    PreviewRequest(
                        PreviewSource.InlineScript(DOUBLE_USE, "placed.kstep.kts"),
                        format = format,
                    )
                val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
                outcome.contentKind shouldBe PreviewContentKind.GEOMETRY
                outcome.geometry?.instanceCount shouldBe 2
            }
        }

        "the summary names the instance and part count".config(enabled = available) {
            val request =
                PreviewRequest(
                    PreviewSource.InlineScript(DOUBLE_USE, "placed.kstep.kts"),
                    format = RenderFormat.GLB,
                    withStep = false,
                )
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            outcome.geometry?.shouldBeInstanceOf<PreviewGeometryStats>()
            val text =
                PreviewRenderer
                    .render(request.copy(format = RenderFormat.TEXT))
                    .shouldBeInstanceOf<PreviewOutcome.Rendered>()
                    .bytes
                    .toString(Charsets.UTF_8)
            text shouldContain "assembly: 2 instance(s) of 1 part(s)"
        }

        "a placement on a usage without geometry degrades into a placement_invalid notice".config(
            enabled = available,
        ) {
            val script =
                PRELUDE +
                    """
                    val asm = pd("ASM"); val a = pd("A"); val b = pd("B")
                    val ua = usage("UA", asm, a); val ub = usage("UB", asm, b)
                    stepFile(fileName = "bad.step") {
                        roots(ua, ub)
                        shape(a, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        place(ub, translation = vec(1.0, 0.0, 0.0))
                    }
                    """
            val request = PreviewRequest(PreviewSource.InlineScript(script, "bad.kstep.kts"), format = RenderFormat.SVG)
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            outcome.contentKind shouldBe PreviewContentKind.NOTICE
            outcome.fallbackReason shouldBe RenderFallbackReasons.PLACEMENT_INVALID
        }

        "several shapes without any assembly usage keep the old first-shape preview and warn".config(
            enabled = available,
        ) {
            val script =
                PRELUDE +
                    """
                    val a = pd("A"); val b = pd("B")
                    stepFile(fileName = "flat.step") {
                        roots(a, b)
                        shape(a, OcctKernel.makeBox(1.0, 1.0, 1.0))
                        shape(b, OcctKernel.makeBox(2.0, 2.0, 2.0))
                    }
                    """
            val request =
                PreviewRequest(PreviewSource.InlineScript(script, "flat.kstep.kts"), format = RenderFormat.GLB)
            val outcome = PreviewRenderer.render(request).shouldBeInstanceOf<PreviewOutcome.Rendered>()
            outcome.geometry?.instanceCount shouldBe 1
            outcome.geometry?.previewedShapeIndex shouldBe 0
            outcome.warnings.single() shouldContain "previewing shape 1 of 2"
            parseGlb(outcome.bytes).max.shouldBeNear(1.0, 1.0, 1.0)
        }
    })
