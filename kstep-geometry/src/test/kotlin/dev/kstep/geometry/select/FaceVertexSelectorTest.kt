package dev.kstep.geometry.select

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Welle Q (ADR-0037): face/vertex selectors, `nearest` and the shared tree validator, OCCT-free. The box is
 * [0,10] x [0,20] x [0,30]; the analytic distances stand in for BRepExtrema (see BoxFixture.oracle).
 */
class FaceVertexSelectorTest :
    StringSpec({
        val box = BoxFixture(10.0, 20.0, 30.0)
        val tol = SelectionTolerance()

        fun edges(
            s: EdgeSelector,
            expected: Int? = null,
            ctx: SelectionContext = box.context(),
        ) = EdgeSelection.resolve(ctx, s, expected, tol)

        fun faces(
            s: FaceSelector,
            expected: Int? = null,
        ) = FaceSelection.resolve(box.context(), s, expected, tol)

        fun verts(
            s: VertexSelector,
            expected: Int? = null,
        ) = VertexSelection.resolve(box.context(), s, expected, tol)

        fun at(
            x: Double,
            y: Double,
            z: Double,
        ) = Point3(x, y, z)

        "the fixture has 8 vertices with 3 edges each" {
            box.vertices shouldHaveSize 8
            box.vertices.forEach { it.edgeIndices shouldHaveSize 3 }
        }

        "new selectors are plain data with structural equality" {
            Edges.nearest(at(1.0, 2.0, 3.0)) shouldBe Edges.nearest(1.0, 2.0, 3.0)
            Edges.nearest(at(1.0, 2.0, 3.0)).hashCode() shouldBe Edges.nearest(1.0, 2.0, 3.0).hashCode()
            Edges.nearest(at(1.0, 2.0, 3.0)) shouldNotBe Edges.nearest(at(1.0, 2.0, 4.0))
            Faces.nearest(1.0, 2.0, 3.0) shouldBe Faces.nearest(at(1.0, 2.0, 3.0))
            Vertices.nearest(1.0, 2.0, 3.0) shouldBe Vertices.nearest(at(1.0, 2.0, 3.0))
            (Faces.all() and Faces.atMax(Direction3.Z)) shouldBe (Faces.all() and Faces.atMax(Direction3.Z))
            (Vertices.all() or Vertices.atMin(Direction3.X)) shouldNotBe
                (Vertices.all() and Vertices.atMin(Direction3.X))
            Edges.nearest(at(1.0, 2.0, 3.0), within = Edges.all()) shouldNotBe Edges.nearest(at(1.0, 2.0, 3.0))
        }

        "Edges.nearest at a corner point returns the three edges meeting in the closest vertex" {
            val result = edges(Edges.nearest(100.0, 0.0, 0.0), expected = 3)
            result.forEach { e ->
                val edge = box.edges[e]
                (edge.start == at(10.0, 0.0, 0.0) || edge.end == at(10.0, 0.0, 0.0)) shouldBe true
            }
        }

        "a point beside an edge pair returns only the two vertical edges at 90.554" {
            val result = edges(Edges.nearest(100.0, 10.0, 15.0), expected = 2)
            result.forEach { e ->
                box.edges[e].direction!!.z shouldBe 1.0
                box.edges[e].start.x shouldBe 10.0
            }
            // The four edges at x = 10 are NOT tied: horizontal ones are 91.24 away, the ends 91.79.
            result.toSet() shouldBe
                box.edges
                    .filter { it.direction!!.z == 1.0 && it.start.x == 10.0 }
                    .map { it.index }
                    .toSet()
        }

        "the box centre ties all four vertical edges, and expected = 1 turns the tie into an error" {
            edges(Edges.nearest(5.0, 10.0, 15.0), expected = 4) shouldContainExactly box.zEdges()
            val e = shouldThrow<EdgeSelectionException> { edges(Edges.nearest(5.0, 10.0, 15.0), expected = 1) }
            e.matched shouldHaveSize 4
            e.candidates shouldHaveSize 4
            e.message!! shouldContain "ambiguous"
            e.message!! shouldContain "nearest("
        }

        "a Gleichstand is judged with the linear tolerance" {
            // Move the point 5e-7 towards one vertical edge: still inside the 1e-6 tolerance of the tie.
            edges(Edges.nearest(5.0 - 3e-7, 10.0, 15.0), expected = 4)
            // 0.6 apart is clearly not a tie.
            edges(Edges.nearest(4.4, 10.0, 15.0), expected = 2)
        }

        "Vertices.nearest finds one vertex at a corner and four at a face centre line" {
            val one = verts(Vertices.nearest(100.0, 0.0, 0.0), expected = 1)
            box.vertices[one.single()].point shouldBe at(10.0, 0.0, 0.0)
            verts(Vertices.nearest(100.0, 10.0, 15.0), expected = 4).forEach { box.vertices[it].point.x shouldBe 10.0 }
        }

        "Faces.nearest picks the face on the near side and returns all tied faces at a corner" {
            val one = faces(Faces.nearest(100.0, 10.0, 15.0), expected = 1)
            box.faces[one.single()].centroid.x shouldBe 10.0
            faces(Faces.nearest(100.0, 0.0, 0.0), expected = 3).forEach { box.faces[it].index shouldBe it }
        }

        "within limits the candidates BEFORE the minimum, while and intersects AFTER it" {
            val p = at(100.0, 10.0, 15.0)
            val within = edges(Edges.nearest(p, within = Edges.parallelTo(Direction3.X)), expected = 4)
            within.forEach { box.edges[it].direction!!.x shouldBe 1.0 }
            // nearest(p) is the two vertical edges; none of them is parallel to X.
            shouldThrow<EdgeSelectionException> { edges(Edges.nearest(p) and Edges.parallelTo(Direction3.X)) }
        }

        "within works for faces and vertices as well" {
            val top = faces(Faces.nearest(at(100.0, 10.0, 15.0), within = Faces.atMax(Direction3.Z)), expected = 1)
            box.faces[top.single()].centroid.z shouldBe 30.0
            val v = verts(Vertices.nearest(at(100.0, 0.0, 0.0), within = Vertices.atMax(Direction3.Z)), expected = 1)
            box.vertices[v.single()].point shouldBe at(10.0, 0.0, 30.0)
        }

        "an empty within yields an empty result and therefore the strict error" {
            val none = Faces.normal(Direction3(0.3, 1.0, 0.7))
            shouldThrow<FaceSelectionException> { faces(Faces.nearest(at(0.0, 0.0, 0.0), within = none)) }
                .candidates shouldHaveSize 6
        }

        "and, or and minus work on faces and vertices" {
            faces(Faces.all(), expected = 6)
            faces(Faces.atMax(Direction3.Z) or Faces.atMin(Direction3.Z), expected = 2)
            faces(Faces.all() minus Faces.atMax(Direction3.Z), expected = 5)
            faces(Faces.all() and Faces.normal(Direction3.X), expected = 1)
            verts(Vertices.all(), expected = 8)
            verts(Vertices.atMax(Direction3.Z) or Vertices.atMin(Direction3.Z), expected = 8)
            verts(Vertices.all() minus Vertices.atMax(Direction3.Z), expected = 4)
            verts(Vertices.atMax(Direction3.Z) and Vertices.atMin(Direction3.X), expected = 2)
        }

        "Vertices.atMax and atMin return the four corners of a side" {
            verts(Vertices.atMax(Direction3.Z), expected = 4).forEach { box.vertices[it].point.z shouldBe 30.0 }
            verts(Vertices.atMin(Direction3.X), expected = 4).forEach { box.vertices[it].point.x shouldBe 0.0 }
        }

        "Vertices.ofEdge and Edges.atVertex round trip" {
            verts(Vertices.ofEdge(Edges.ofFace(Faces.atMax(Direction3.Z))), expected = 4)
                .forEach { box.vertices[it].point.z shouldBe 30.0 }
            // Edges at the corner (10,0,0): 3, the same set Edges.nearest finds there.
            edges(Edges.atVertex(Vertices.nearest(100.0, 0.0, 0.0)), expected = 3) shouldContainExactly
                edges(Edges.nearest(100.0, 0.0, 0.0), expected = 3)
            // All edges meeting the top vertices: the top ring plus the four verticals.
            edges(Edges.atVertex(Vertices.atMax(Direction3.Z)), expected = 8)
        }

        "a seam or degenerated edge is never matched through a vertex" {
            val ctx =
                SelectionContext(box.edges.withSeam(8), box.faces, { box.vertices }, box.oracle)
            val withSeamNeighbours = edges(Edges.atVertex(Vertices.all()), ctx = ctx)
            (8 in withSeamNeighbours) shouldBe false
        }

        "a bad nearest point is rejected when the selector is built" {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 1.0000001e7)) {
                shouldThrow<IllegalArgumentException> { Edges.nearest(bad, 0.0, 0.0) }
                shouldThrow<IllegalArgumentException> { Faces.nearest(0.0, bad, 0.0) }
                shouldThrow<IllegalArgumentException> { Vertices.nearest(0.0, 0.0, bad) }
            }
            shouldThrow<IllegalArgumentException> { Edges.nearest(Double.NaN, 0.0, 0.0) }.message!! shouldContain "NaN"
        }

        "an empty selection lists every candidate, capped at 32 lines" {
            val many = List(40) { VertexInfo(it, at(it.toDouble(), 0.0, 0.0), emptyList()) }
            val ctx = SelectionContext(emptyList(), emptyList(), { many }, null)
            val s = Vertices.atMax(Direction3.X) minus Vertices.atMax(Direction3.X)
            val e = shouldThrow<VertexSelectionException> { VertexSelection.resolve(ctx, s, null, tol) }
            e.candidates shouldHaveSize 40
            e.message!!.lines().count { it.trimStart().startsWith("#") } shouldBe 32
            e.message!! shouldContain "... and 8 more"
            val f = shouldThrow<FaceSelectionException> { faces(Faces.all() minus Faces.all()) }
            f.candidates shouldHaveSize 6
            f.message!! shouldContain "matched no face"
        }

        "expected must be at least 1 for faces and vertices" {
            shouldThrow<IllegalArgumentException> { faces(Faces.all(), expected = 0) }
            shouldThrow<IllegalArgumentException> { verts(Vertices.all(), expected = 0) }
        }

        "nearest on edges or faces without a distance query fails with a hint, vertices do not need one" {
            val bare = box.context(withOracle = false)
            shouldThrow<IllegalArgumentException> { edges(Edges.nearest(1.0, 1.0, 1.0), ctx = bare) }
                .message!! shouldContain "OcctKernel.selectEdges"
            shouldThrow<IllegalArgumentException> {
                FaceSelection.resolve(bare, Faces.nearest(1.0, 1.0, 1.0), null, tol)
            }
            VertexSelection.resolve(bare, Vertices.nearest(100.0, 0.0, 0.0), 1, tol) shouldHaveSize 1
        }

        "the public EdgeSelection.resolve rejects nearest and atVertex on bare lists" {
            shouldThrow<IllegalArgumentException> {
                EdgeSelection.resolve(box.edges, box.faces, Edges.nearest(1.0, 1.0, 1.0))
            }
            shouldThrow<IllegalArgumentException> {
                EdgeSelection.resolve(box.edges, box.faces, Edges.atVertex(Vertices.all()))
            }
        }

        "a malformed distance payload is an internal error" {
            fun oracle(values: (Int) -> DoubleArray) =
                SelectionContext(box.edges, box.faces, { box.vertices }) { _, _ -> values(box.edges.size) }
            shouldThrow<IllegalStateException> {
                edges(
                    Edges.nearest(1.0, 1.0, 1.0),
                    ctx = oracle { DoubleArray(it - 1) },
                )
            }.message!! shouldContain "malformed"
            shouldThrow<IllegalStateException> {
                edges(Edges.nearest(1.0, 1.0, 1.0), ctx = oracle { DoubleArray(it) { Double.NaN } })
            }.message!! shouldContain "malformed"
            shouldThrow<IllegalStateException> {
                edges(Edges.nearest(1.0, 1.0, 1.0), ctx = oracle { DoubleArray(it) { -1.0 } })
            }.message!! shouldContain "malformed"
        }

        "the distance query is cached per kind and point" {
            var calls = 0
            val ctx =
                SelectionContext(box.edges, box.faces, { box.vertices }) { kind, p ->
                    calls++
                    box.oracle.distances(kind, p)
                }
            edges(Edges.nearest(1.0, 1.0, 1.0) or Edges.nearest(1.0, 1.0, 1.0), ctx = ctx)
            calls shouldBe 1
            edges(Edges.nearest(1.0, 1.0, 2.0) or Edges.nearest(1.0, 1.0, 1.0), ctx = ctx)
            calls shouldBe 2
        }

        "the selector tree limit counts the cross-nested hierarchies" {
            var deep: EdgeSelector = Edges.all()
            repeat(16) { deep = Edges.atVertex(Vertices.ofEdge(deep)) }
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(deep) }.message!! shouldContain "nesting"

            fun wide(n: Int): EdgeSelector =
                if (n <= 1) Edges.atVertex(Vertices.all()) else wide(n / 2) or wide(n - n / 2)
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(wide(100)) }.message!! shouldContain "nodes"
            SelectorTree.validate(wide(60))

            var faceDeep: FaceSelector = Faces.all()
            repeat(40) { faceDeep = Faces.nearest(at(0.0, 0.0, 0.0), within = faceDeep) }
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(faceDeep) }.message!! shouldContain "nesting"
        }

        "a ten-thousand-level selector is rejected iteratively, before anything recurses over it" {
            var s: EdgeSelector = Edges.all()
            repeat(10_000) { s = s and Edges.all() }
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(s) }
            shouldThrow<IllegalArgumentException> { EdgeSelection.resolve(box.edges, box.faces, s) }
            var f: FaceSelector = Faces.all()
            repeat(10_000) { f = f or Faces.all() }
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(f) }
            shouldThrow<IllegalArgumentException> { EdgeSelection.resolveFaces(box.edges, box.faces, f) }
            var v: VertexSelector = Vertices.all()
            repeat(10_000) { v = v minus Vertices.all() }
            shouldThrow<IllegalArgumentException> { SelectorTree.validate(v) }
        }

        "decodeVertexInfo accepts a well-formed payload and rejects broken ones" {
            val raw = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 2.0, 3.0)
            val csr = intArrayOf(2, 0, 2, 3, 0, 1, 1)
            val ok = decodeVertexInfo(raw, csr, 2)
            ok[1].point shouldBe at(1.0, 2.0, 3.0)
            ok[0].edgeIndices shouldContainExactly listOf(0, 1)
            ok[1].edgeIndices shouldContainExactly listOf(1)

            shouldThrow<IllegalStateException> { decodeVertexInfo(doubleArrayOf(0.0, 0.0), intArrayOf(0, 0), 0) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(), 2) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(3, 0, 0, 0, 0), 2) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(2, 0, 2, 3, 0, 1), 2) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(2, 1, 2, 3, 0, 1, 1), 2) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(2, 0, 2, 3, 0, 1, 5), 2) }
            shouldThrow<IllegalStateException> { decodeVertexInfo(raw, intArrayOf(2, 0, 3, 2, 0, 1, 1), 2) }
            shouldThrow<IllegalStateException> {
                decodeVertexInfo(doubleArrayOf(0.0, Double.NaN, 0.0), intArrayOf(1, 0, 0), 0)
            }
            shouldThrow<IllegalStateException> {
                decodeVertexInfo(DoubleArray(3 * 20_001), IntArray(20_001 + 2).also { it[0] = 20_001 }, 0)
            }
        }
    })
