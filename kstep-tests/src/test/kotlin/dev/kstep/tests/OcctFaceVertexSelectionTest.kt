package dev.kstep.tests

import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeSelection
import dev.kstep.geometry.select.EdgeSelectionException
import dev.kstep.geometry.select.EdgeSelector
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.FaceSelectionException
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.Point3
import dev.kstep.geometry.select.VertexSelectionException
import dev.kstep.geometry.select.Vertices
import dev.kstep.geometry.select.and
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

private const val FV_LEAK_SLACK = 10

/**
 * Welle Q (ADR-0037): `nearest`, face and vertex selection against the real OCCT bridge. The box is
 * [0,10] x [0,20] x [0,30]; every expected distance and volume is hand-computed (see ADR-0037, "Verification").
 */
class OcctFaceVertexSelectionTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun box() = OcctKernel.makeBox(10.0, 20.0, 30.0)

        "the box has 8 vertices with three edges each, consistent with the edge end points".config(
            enabled = available,
        ) {
            box().use { b ->
                b.vertices shouldHaveSize 8
                b.vertices.forEach { v ->
                    v.edgeIndices shouldHaveSize 3
                    v.edgeIndices.forEach { e ->
                        val edge = b.edges[e]
                        (edge.start == v.point || edge.end == v.point) shouldBe true
                    }
                }
                // every edge ends in exactly two vertices
                val counts = IntArray(b.edges.size)
                b.vertices.forEach { v -> v.edgeIndices.forEach { counts[it]++ } }
                counts.toList().all { it == 2 } shouldBe true
            }
        }

        "Edges.nearest at (100, 0, 0) returns the three edges meeting in the corner (10, 0, 0), each 90 away".config(
            enabled = available,
        ) {
            box().use { b ->
                val result = OcctKernel.selectEdges(b, Edges.nearest(100.0, 0.0, 0.0), expected = 3)
                result.forEach { e ->
                    val edge = b.edges[e]
                    val corner = Point3(10.0, 0.0, 0.0)
                    (edge.start == corner || edge.end == corner) shouldBe true
                }
            }
        }

        "a point beside the box ties exactly the two vertical edges at 90.554, not four edges".config(
            enabled = available,
        ) {
            box().use { b ->
                val result = OcctKernel.selectEdges(b, Edges.nearest(100.0, 10.0, 15.0), expected = 2)
                result.forEach { e ->
                    b.edges[e].direction!!.z shouldBe (1.0 plusOrMinus 1e-9)
                    b.edges[e].mid.x shouldBe (10.0 plusOrMinus 1e-9)
                }
                result.map { b.edges[it].mid.y }.sorted() shouldBe listOf(0.0, 20.0)
            }
        }

        "the box centre ties all four vertical edges, and expected = 1 reports four candidates".config(
            enabled = available,
        ) {
            box().use { b ->
                val four = OcctKernel.selectEdges(b, Edges.nearest(5.0, 10.0, 15.0), expected = 4)
                four.forEach { b.edges[it].direction!!.z shouldBe (1.0 plusOrMinus 1e-9) }
                val e =
                    shouldThrow<EdgeSelectionException> {
                        OcctKernel.selectEdges(b, Edges.nearest(5.0, 10.0, 15.0), expected = 1)
                    }
                e.matched shouldHaveSize 4
                e.candidates shouldHaveSize 4
            }
        }

        "Vertices.nearest and Faces.nearest give the hand-computed answers on the real box".config(
            enabled = available,
        ) {
            box().use { b ->
                val corner = OcctKernel.selectVertices(b, Vertices.nearest(100.0, 0.0, 0.0), expected = 1).single()
                b.vertices[corner].point shouldBe Point3(10.0, 0.0, 0.0)
                val four = OcctKernel.selectVertices(b, Vertices.nearest(100.0, 10.0, 15.0), expected = 4)
                four.forEach { b.vertices[it].point.x shouldBe 10.0 }
                val face = OcctKernel.selectFaces(b, Faces.nearest(100.0, 10.0, 15.0), expected = 1).single()
                b.faces[face].centroid.x shouldBe (10.0 plusOrMinus 1e-9)
                OcctKernel.selectFaces(b, Faces.nearest(100.0, 0.0, 0.0), expected = 3) shouldHaveSize 3
                // a point ON a face is at distance 0 from it only
                val inside = OcctKernel.selectFaces(b, Faces.nearest(5.0, 10.0, 30.0), expected = 1).single()
                b.faces[inside].centroid.z shouldBe (30.0 plusOrMinus 1e-9)
            }
        }

        "atVertex steers an edge selection: the edges at the corner nearest (100, 0, 0)".config(enabled = available) {
            box().use { b ->
                OcctKernel.selectEdges(
                    b,
                    Edges.atVertex(Vertices.nearest(100.0, 0.0, 0.0)),
                    expected = 3,
                ) shouldContainExactly
                    OcctKernel.selectEdges(b, Edges.nearest(100.0, 0.0, 0.0), expected = 3)
                OcctKernel.selectEdges(b, Edges.atVertex(Vertices.atMax(Direction3.Z)), expected = 8) shouldHaveSize 8
                OcctKernel.selectVertices(
                    b,
                    Vertices.ofEdge(Edges.ofFace(Faces.atMax(Direction3.Z))),
                    expected = 4,
                ) shouldHaveSize
                    4
            }
        }

        "within restricts the candidates, and is not the same as and".config(enabled = available) {
            box().use { b ->
                val p = Point3(100.0, 10.0, 15.0)
                val within =
                    OcctKernel.selectEdges(
                        b,
                        Edges.nearest(p, within = Edges.parallelTo(Direction3.X)),
                        expected = 4,
                    )
                within.forEach { b.edges[it].direction!!.x shouldBe (1.0 plusOrMinus 1e-9) }
                shouldThrow<EdgeSelectionException> {
                    OcctKernel.selectEdges(b, Edges.nearest(p) and Edges.parallelTo(Direction3.X))
                }
            }
        }

        "nearest on a cylinder never picks the seam and finds the cap".config(enabled = available) {
            OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                val cap = OcctKernel.selectFaces(c, Faces.nearest(0.0, 0.0, 100.0), expected = 1).single()
                c.faces[cap].kind shouldBe FaceKind.PLANE
                c.faces[cap].centroid.z shouldBe (20.0 plusOrMinus 1e-9)
                // At (100, 0, 10) the seam (x = 10, y = 0) would be 90 away, the two circles 90.55: the seam is excluded.
                val edges = OcctKernel.selectEdges(c, Edges.nearest(100.0, 0.0, 10.0), expected = 2)
                edges.forEach {
                    c.edges[it].seam shouldBe false
                    c.edges[it].kind shouldBe EdgeKind.CIRCLE
                }
            }
        }

        "filleting the edges nearest a point removes the hand-computed volume: 5948.4956 and 8 faces".config(
            enabled = available,
        ) {
            box().use { b ->
                OcctKernel.fillet(b, Edges.nearest(100.0, 10.0, 15.0), 2.0, expected = 2).use { f ->
                    f.volume shouldBe (6000.0 - 2.0 * (4.0 - PI) * 30.0 plusOrMinus 1e-6)
                    f.topology.faces shouldBe 8
                }
                val e =
                    shouldThrow<EdgeSelectionException> {
                        OcctKernel.fillet(b, Edges.nearest(100.0, 10.0, 15.0), 2.0, expected = 1)
                    }
                e.candidates shouldHaveSize 2
                e.message!! shouldContain "ambiguous"
            }
        }

        "an empty selection lists all faces and vertices".config(enabled = available) {
            box().use { b ->
                val none = Faces.normal(Direction3(0.3, 1.0, 0.7))
                shouldThrow<FaceSelectionException> { OcctKernel.selectFaces(b, none) }.candidates shouldHaveSize 6
                shouldThrow<VertexSelectionException> {
                    OcctKernel.selectVertices(b, Vertices.nearest(0.0, 0.0, 0.0) and Vertices.atMax(Direction3.X))
                }.candidates shouldHaveSize 8
            }
        }

        "the existing edge selectors give the same indices through the new context as through bare lists".config(
            enabled = available,
        ) {
            fun same(
                shape: OcctShape,
                selector: EdgeSelector,
            ) = OcctKernel.selectEdges(shape, selector) shouldContainExactly
                EdgeSelection.resolve(shape.edges, shape.faces, selector)
            box().use { b ->
                for (s in listOf(
                    Edges.all(),
                    Edges.parallelTo(Direction3.Z),
                    Edges.atMax(Direction3.Z),
                    Edges.atMin(Direction3.X),
                    Edges.longest(),
                    Edges.shortest(),
                    Edges.ofFace(Faces.atMax(Direction3.Z)),
                    Edges.ofFace(Faces.normal(Direction3.NEG_X)),
                    Edges.ofType(EdgeKind.LINE),
                )) {
                    same(b, s)
                }
            }
            OcctKernel.makeCylinder(10.0, 20.0).use { c ->
                same(c, Edges.ofType(EdgeKind.CIRCLE, radius = 10.0))
                same(c, Edges.seams())
            }
        }

        "a hostile distance query is refused and neither leaks a shape nor crashes".config(enabled = available) {
            val handle = OcctBridge.nativeMakeBox(10.0, 20.0, 30.0)
            try {
                System.gc()
                val before = OcctBridge.nativeLiveShapeCount()
                repeat(50) {
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShapeDistances(handle, 0, 0.0, 0.0, 0.0) }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShapeDistances(handle, 3, 0.0, 0.0, 0.0) }
                    shouldThrow<IllegalArgumentException> {
                        OcctBridge.nativeShapeDistances(handle, 1, Double.NaN, 0.0, 0.0)
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctBridge.nativeShapeDistances(handle, 2, 0.0, Double.POSITIVE_INFINITY, 0.0)
                    }
                    shouldThrow<IllegalArgumentException> { OcctBridge.nativeShapeDistances(handle, 1, 2e7, 0.0, 0.0) }
                    shouldThrow<IllegalStateException> { OcctBridge.nativeShapeDistances(-1L, 1, 0.0, 0.0, 0.0) }
                    shouldThrow<IllegalStateException> { OcctBridge.nativeShapeVertexInfo(-1L) }
                    shouldThrow<IllegalStateException> { OcctBridge.nativeShapeVertexEdges(-1L) }
                }
                OcctBridge.nativeShapeDistances(handle, 1, 100.0, 0.0, 0.0).size shouldBe 12
                OcctBridge.nativeShapeDistances(handle, 2, 100.0, 0.0, 0.0).size shouldBe 6
                OcctBridge.nativeShapeVertexInfo(handle).size shouldBe 24
                System.gc()
                OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + FV_LEAK_SLACK
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }

        "a boolean result with a hole still resolves vertices and distances consistently".config(enabled = available) {
            box().use { b ->
                OcctKernel.makeCylinder(3.0, 40.0, 5.0, 10.0, -5.0).use { drill ->
                    OcctKernel.boolean(BooleanOperation.CUT, b, drill).use { holed ->
                        holed.vertices.forEach { v ->
                            v.edgeIndices.forEach {
                                it shouldBeLessThanOrEqual
                                    holed.edges.size - 1
                            }
                        }
                        val nearest = OcctKernel.selectFaces(holed, Faces.nearest(5.0, 10.0, 15.0 + 2.0))
                        nearest.forEach { holed.faces[it].kind shouldBe FaceKind.CYLINDER }
                    }
                }
            }
        }
    })
