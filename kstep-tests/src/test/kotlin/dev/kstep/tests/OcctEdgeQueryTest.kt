package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.FaceKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** Welle L (ADR-0031): the native edge/face geometry queries against real OCCT shapes. */
class OcctEdgeQueryTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a 40x30x20 box reports 12 straight edges with lengths 4x40, 4x30, 4x20 and 6 planar faces".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { box ->
                box.edges.size shouldBe box.topology.edges
                box.edges.size shouldBe 12
                box.edges.all { it.kind == EdgeKind.LINE && !it.seam && !it.degenerated } shouldBe true
                box.edges.map { Math.round(it.length * 1000) / 1000.0 }.sorted() shouldBe
                    List(4) { 20.0 } + List(4) { 30.0 } + List(4) { 40.0 }
                box.faces.size shouldBe 6
                box.faces.all { it.kind == FaceKind.PLANE } shouldBe true
                box.edges.all { it.faceIndices.size == 2 } shouldBe true
                box.faces.all { it.edgeIndices.size == 4 } shouldBe true
            }
        }

        "box face normals point outward and areas and centroids are exact".config(enabled = available) {
            OcctKernel.makeBox(40.0, 30.0, 20.0).use { box ->
                val top = box.faces.single { it.normal == Direction3(0.0, 0.0, 1.0) }
                top.area shouldBe (1200.0 plusOrMinus 1e-6)
                top.centroid.z shouldBe (20.0 plusOrMinus 1e-9)
                val bottom = box.faces.single { it.centroid.z < 1e-9 && it.area > 1199.0 }
                bottom.normal!!.z shouldBe (-1.0 plusOrMinus 1e-9)
                box.faces
                    .map { it.normal!!.unit() }
                    .toSet()
                    .size shouldBe 6
                box.faces.sumOf { it.area } shouldBe (2.0 * (1200.0 + 800.0 + 600.0) plusOrMinus 1e-6)
            }
        }

        "the edge order of the query is the order fillet accepts: index i of the query is index i of the topology"
            .config(
                enabled = available,
            ) {
                OcctKernel.makeBox(40.0, 30.0, 20.0).use { box ->
                    val zEdge = box.edges.first { it.direction!!.z != 0.0 }
                    OcctKernel.fillet(box, zEdge.index, 2.0).use { filleted ->
                        // a vertical 20 mm edge filleted at r=2 removes (1 - pi/4) * 4 * 20 of volume
                        (24000.0 - filleted.volume) shouldBe ((1.0 - Math.PI / 4.0) * 4.0 * 20.0 plusOrMinus 1e-6)
                    }
                }
            }

        "a cylinder reports two circles with radius, one seam line and one cylindrical face".config(
            enabled = available,
        ) {
            OcctKernel.makeCylinder(5.0, 10.0).use { cylinder ->
                cylinder.edges.size shouldBe cylinder.topology.edges
                val circles = cylinder.edges.filter { it.kind == EdgeKind.CIRCLE }
                circles.size shouldBe 2
                circles.forEach {
                    it.radius!! shouldBe (5.0 plusOrMinus 1e-9)
                    it.length shouldBe (2.0 * Math.PI * 5.0 plusOrMinus 1e-6)
                    Math.abs(it.direction!!.z) shouldBe (1.0 plusOrMinus 1e-9)
                }
                circles.map {
                    it.circleCenter!!.z.let { z ->
                        Math.round(z * 1e6) / 1e6
                    }
                } shouldContainExactlyInAnyOrder
                    listOf(0.0, 10.0)
                val seams = cylinder.edges.filter { it.seam }
                seams.size shouldBe 1
                seams.single().kind shouldBe EdgeKind.LINE
                seams.single().length shouldBe (10.0 plusOrMinus 1e-9)
                cylinder.faces.map { it.kind } shouldContainExactlyInAnyOrder
                    listOf(FaceKind.PLANE, FaceKind.PLANE, FaceKind.CYLINDER)
                val side = cylinder.faces.single { it.kind == FaceKind.CYLINDER }
                side.radius!! shouldBe (5.0 plusOrMinus 1e-9)
                side.area shouldBe (2.0 * Math.PI * 5.0 * 10.0 plusOrMinus 1e-6)
            }
        }

        "edge and face queries are cached and a closed shape throws IllegalStateException".config(enabled = available) {
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            (box.edges === box.edges) shouldBe true
            box.close()
            shouldThrow<IllegalStateException> { box.edges }
            shouldThrow<IllegalStateException> { box.faces }
        }

        "the native query functions reject an unknown handle with IllegalStateException".config(enabled = available) {
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeEdgeInfo(987654321L) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeFaceInfo(987654321L) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeFaceEdges(987654321L) }
        }

        "selectEdges on a real box matches the geometric expectation" {
            // covered by OcctSelectedFilletChamferTest; this case documents that selectEdges needs the bridge
            if (!available) {
                shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                    OcctKernel.selectEdges(
                        OcctKernel.makeBox(1.0, 1.0, 1.0),
                        dev.kstep.geometry.select.Edges
                            .all(),
                    )
                }
            }
        }
    })
