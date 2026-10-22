package dev.kstep.geometry.select

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class EdgeSelectorTest :
    StringSpec({
        val box = BoxFixture(40.0, 40.0, 10.0)

        fun resolve(
            selector: EdgeSelector,
            expected: Int? = null,
            edges: List<EdgeInfo> = box.edges,
        ) = EdgeSelection.resolve(edges, box.faces, selector, expected)

        "the fixture is a 12-edge, 6-face box" {
            box.edges.size shouldBe 12
            box.faces.size shouldBe 6
            box.faces.forEach { it.edgeIndices.size shouldBe 4 }
            box.edges.forEach { it.faceIndices.size shouldBe 2 }
        }

        "parallelTo(Z) selects the four vertical edges, in either direction" {
            resolve(Edges.parallelTo(Direction3.Z), expected = 4) shouldContainExactly box.zEdges()
            resolve(Edges.parallelTo(Direction3.NEG_Z), expected = 4) shouldContainExactly box.zEdges()
        }

        "parallelTo does not match edges that are merely close to parallel beyond the tolerance" {
            val tilted = Direction3(0.001, 0.0, 1.0)
            shouldThrow<EdgeSelectionException> { resolve(Edges.parallelTo(tilted)) }
        }

        "atMax(Z) selects the four top edges and atMin(Z) the four bottom edges" {
            val top = resolve(Edges.atMax(Direction3.Z), expected = 4)
            top.forEach { box.edges[it].mid.z shouldBe 10.0 }
            val bottom = resolve(Edges.atMin(Direction3.Z), expected = 4)
            bottom.forEach { box.edges[it].mid.z shouldBe 0.0 }
        }

        "ofFace(Faces.atMax(Z)) selects the four edges of the top face" {
            resolve(Edges.ofFace(Faces.atMax(Direction3.Z)), expected = 4) shouldContainExactly
                resolve(Edges.atMax(Direction3.Z))
        }

        "ofFace(Faces.normal(Z)) matches the top face only" {
            resolve(Edges.ofFace(Faces.normal(Direction3.Z)), expected = 4) shouldContainExactly
                resolve(Edges.atMax(Direction3.Z))
            resolve(Edges.ofFace(Faces.normal(Direction3.NEG_Z)), expected = 4) shouldContainExactly
                resolve(Edges.atMin(Direction3.Z))
        }

        "longest() returns every edge tied for longest, and expected = 1 turns the tie into an error" {
            resolve(Edges.longest()).size shouldBe 8
            val e = shouldThrow<EdgeSelectionException> { resolve(Edges.longest(), expected = 1) }
            e.matched.size shouldBe 8
            e.candidates.size shouldBe 8
            e.expected shouldBe 1
            e.message!! shouldContain "ambiguous"
        }

        "shortest() returns the four vertical edges of a 40x40x10 box" {
            resolve(Edges.shortest(), expected = 4) shouldContainExactly box.zEdges()
        }

        "ofType(LINE) matches all 12 edges and ofType(CIRCLE) none" {
            resolve(Edges.ofType(EdgeKind.LINE)).size shouldBe 12
            shouldThrow<EdgeSelectionException> { resolve(Edges.ofType(EdgeKind.CIRCLE)) }
        }

        "ofType with a radius is only allowed for CIRCLE" {
            shouldThrow<IllegalArgumentException> { Edges.ofType(EdgeKind.LINE, radius = 2.0) }
            shouldThrow<IllegalArgumentException> { Edges.ofType(EdgeKind.CIRCLE, radius = -1.0) }
            shouldThrow<IllegalArgumentException> { Edges.ofType(EdgeKind.CIRCLE, radius = Double.NaN) }
            shouldThrow<IllegalArgumentException> { Faces.ofType(FaceKind.PLANE, radius = 1.0) }
        }

        "a circle radius filter matches within the linear tolerance only" {
            val circle =
                box.edges[0].copy(
                    kind = EdgeKind.CIRCLE,
                    radius = 4.0,
                    direction = Direction3.Z,
                    circleCenter = Point3(0.0, 0.0, 0.0),
                )
            val edges = listOf(circle, circle.copy(index = 1, radius = 5.0))
            val faces = emptyList<FaceInfo>()
            EdgeSelection.resolve(edges, faces, Edges.ofType(EdgeKind.CIRCLE, radius = 4.0)) shouldContainExactly
                listOf(0)
            EdgeSelection.resolve(edges, faces, Edges.ofType(EdgeKind.CIRCLE, radius = 5.0)) shouldContainExactly
                listOf(1)
            shouldThrow<EdgeSelectionException> {
                EdgeSelection.resolve(
                    edges,
                    faces,
                    Edges.ofType(EdgeKind.CIRCLE, radius = 4.5),
                )
            }
        }

        "seam edges are excluded from every selector except seams()" {
            val zIndices = box.zEdges()
            val seamed = box.edges.withSeam(zIndices[0])
            resolve(Edges.parallelTo(Direction3.Z), edges = seamed) shouldContainExactly zIndices.drop(1)
            resolve(Edges.all(), edges = seamed).size shouldBe 11
            resolve(Edges.seams(), edges = seamed) shouldContainExactly listOf(zIndices[0])
            resolve(Edges.parallelTo(Direction3.Z) or Edges.seams(), edges = seamed) shouldContainExactly zIndices
        }

        "degenerated edges are never selected" {
            val degenerated = box.edges.map { if (it.index == 0) it.copy(degenerated = true) else it }
            resolve(Edges.all(), edges = degenerated).size shouldBe 11
        }

        "and / or / minus combine selections" {
            val top = Edges.atMax(Direction3.Z)
            val alongX = Edges.parallelTo(Direction3.X)
            resolve(top and alongX, expected = 2).size shouldBe 2
            resolve(top or alongX).size shouldBe 4 + 4 - 2
            resolve(top minus alongX, expected = 2).forEach { box.edges[it].mid.z shouldBe 10.0 }
            resolve(Edges.all() minus top, expected = 8).size shouldBe 8
        }

        "an empty selection is an error listing all regular edges as candidates" {
            val e = shouldThrow<EdgeSelectionException> { resolve(Edges.parallelTo(Direction3(1.0, 1.0, 1.0))) }
            e.matched shouldBe emptyList()
            e.candidates.size shouldBe 12
            e.message!! shouldContain "matched no edge"
        }

        "the candidate list in the message is cut at 32 entries while the property stays complete" {
            val many =
                List(40) { i -> box.edges[0].copy(index = i, faceIndices = emptyList()) }
            val e =
                shouldThrow<EdgeSelectionException> {
                    EdgeSelection.resolve(
                        many,
                        emptyList(),
                        Edges.all(),
                        expected = 1,
                    )
                }
            e.candidates.size shouldBe 40
            e.message!! shouldContain "and 8 more"
            e.message!!.lines().count { it.trimStart().startsWith("#") } shouldBe EdgeSelection.MAX_LISTED_CANDIDATES
        }

        "expected must be positive" {
            shouldThrow<IllegalArgumentException> { resolve(Edges.all(), expected = 0) }
        }

        "a selector tree deeper than the limit is rejected" {
            var s: EdgeSelector = Edges.all()
            repeat(EdgeSelection.MAX_SELECTOR_DEPTH) { s = s and Edges.all() }
            shouldThrow<IllegalArgumentException> { resolve(s) }.message!! shouldContain "nesting"
        }

        "a selector with too many nodes is rejected" {
            fun wide(n: Int): EdgeSelector = if (n <= 1) Edges.all() else wide(n / 2) or wide(n - n / 2)
            shouldThrow<IllegalArgumentException> {
                resolve(
                    wide(EdgeSelection.MAX_SELECTOR_NODES),
                )
            }.message!! shouldContain
                "nodes"
        }

        "a wide selector below the node limit still resolves" {
            fun wide(n: Int): EdgeSelector = if (n <= 1) Edges.all() else wide(n / 2) or wide(n - n / 2)
            resolve(wide(100)).size shouldBe 12
        }

        "Direction3 rejects non-finite and zero vectors" {
            shouldThrow<IllegalArgumentException> { Direction3(Double.NaN, 0.0, 1.0) }
            shouldThrow<IllegalArgumentException> { Direction3(0.0, Double.POSITIVE_INFINITY, 1.0) }
            shouldThrow<IllegalArgumentException> { Direction3(0.0, 0.0, 0.0) }
            shouldThrow<IllegalArgumentException> { Direction3(1e-12, 0.0, 0.0) }
        }

        "selection tolerances outside the permitted range are rejected" {
            shouldThrow<IllegalArgumentException> { SelectionTolerance(linear = 0.0) }
            shouldThrow<IllegalArgumentException> { SelectionTolerance(linear = 2.0) }
            shouldThrow<IllegalArgumentException> { SelectionTolerance(angularRadians = 0.5) }
            shouldThrow<IllegalArgumentException> { SelectionTolerance(angularRadians = Double.NaN) }
            SelectionTolerance(linear = 1e-9, angularRadians = 0.1) shouldBe SelectionTolerance(1e-9, 0.1)
        }

        "selectors are values with structural equality" {
            (Edges.parallelTo(Direction3.Z) and Edges.atMax(Direction3.Z)) shouldBe
                (Edges.parallelTo(Direction3.Z) and Edges.atMax(Direction3.Z))
            (Edges.parallelTo(Direction3.Z) == Edges.parallelTo(Direction3.X)) shouldBe false
        }

        "a wider box keeps the same geometric selection (stability under parameter change)" {
            val wide = BoxFixture(60.0, 40.0, 10.0)
            EdgeSelection.resolve(wide.edges, wide.faces, Edges.parallelTo(Direction3.Z), 4).size shouldBe 4
            EdgeSelection.resolve(wide.edges, wide.faces, Edges.ofFace(Faces.atMax(Direction3.Z)), 4).size shouldBe 4
        }
    })
