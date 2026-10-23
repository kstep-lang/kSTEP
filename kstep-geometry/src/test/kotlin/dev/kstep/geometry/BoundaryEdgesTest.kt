package dev.kstep.geometry

import dev.kstep.geometry.select.EdgeInfo
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.FaceInfo
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.Point3
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** BOUNDARY = edges of the blend faces that border a face the operation did not create. Synthetic topology. */
class BoundaryEdgesTest :
    StringSpec({
        val o = Point3(0.0, 0.0, 0.0)

        fun edge(
            index: Int,
            faces: List<Int>,
            seam: Boolean = false,
            degenerated: Boolean = false,
        ) = EdgeInfo(index, EdgeKind.LINE, 1.0, o, o, o, null, null, null, seam, degenerated, faces)

        fun face(
            index: Int,
            edges: List<Int>,
        ) = FaceInfo(index, FaceKind.OTHER, 1.0, o, null, null, null, edges)

        // blend faces 0 and 1 (adjacent: edge 1 is shared), plain faces 2 and 3
        val faces =
            listOf(face(0, listOf(0, 1, 4, 5)), face(1, listOf(1, 2, 3)), face(2, listOf(0)), face(3, listOf(2, 3)))
        val edges =
            listOf(
                edge(0, listOf(0, 2)),
                edge(1, listOf(0, 1)),
                edge(2, listOf(1, 3)),
                edge(3, listOf(1, 3)),
                edge(4, listOf(0), seam = true),
                edge(5, listOf(0, 2), degenerated = true),
            )

        "edges to faces outside the operation's blend faces are the boundary" {
            boundaryEdges(setOf(0), setOf(0, 1), faces, edges) shouldBe listOf(0)
            boundaryEdges(setOf(1), setOf(0, 1), faces, edges) shouldBe listOf(2, 3)
        }

        "an edge between two blend faces of the same operation is not boundary" {
            boundaryEdges(setOf(0, 1), setOf(0, 1), faces, edges) shouldBe listOf(0, 2, 3)
        }

        "seam and degenerated edges are never boundary" {
            boundaryEdges(setOf(0), setOf(0), faces, edges).containsAll(listOf(4, 5)) shouldBe false
        }

        "judged against only its own faces, a shared edge is boundary of both" {
            boundaryEdges(setOf(0, 1), setOf(0), faces, edges) shouldBe listOf(0, 1, 2, 3)
        }

        "no blend face gives no boundary, and a face outside the result is refused" {
            boundaryEdges(emptySet(), emptySet(), faces, edges) shouldBe emptyList()
            shouldThrow<IllegalArgumentException> { boundaryEdges(setOf(9), setOf(9), faces, edges) }
        }
    })
