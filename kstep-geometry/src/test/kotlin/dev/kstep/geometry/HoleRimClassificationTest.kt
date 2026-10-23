package dev.kstep.geometry

import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeInfo
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.FaceInfo
import dev.kstep.geometry.select.FaceKind
import dev.kstep.geometry.select.Point3
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Rim classification works on plain edge data: no OCCT needed. */
class HoleRimClassificationTest :
    StringSpec({
        fun circle(
            index: Int,
            z: Double,
            cx: Double = 20.0,
        ) = EdgeInfo(
            index,
            EdgeKind.CIRCLE,
            25.13,
            Point3(cx + 4, 20.0, z),
            Point3(cx + 4, 20.0, z),
            Point3(cx - 4, 20.0, z),
            Direction3.Z,
            Point3(cx, 20.0, z),
            4.0,
            seam = false,
            degenerated = false,
            faceIndices = listOf(0, 1),
        )

        // The two halves of a ring split by the seam: they share both end points.
        fun halves(
            first: Int,
            z: Double,
        ) = listOf(
            EdgeInfo(
                first,
                EdgeKind.OTHER,
                12.5,
                Point3(24.0, 20.0, z),
                Point3(16.0, 20.0, z),
                Point3(20.0, 24.0, z),
                null,
                null,
                null,
                seam = false,
                degenerated = false,
                faceIndices = listOf(0, 1),
            ),
            EdgeInfo(
                first + 1,
                EdgeKind.OTHER,
                12.5,
                Point3(16.0, 20.0, z),
                Point3(24.0, 20.0, z),
                Point3(20.0, 16.0, z),
                null,
                null,
                null,
                seam = false,
                degenerated = false,
                faceIndices = listOf(0, 1),
            ),
        )

        val down = Direction3(0.0, 0.0, -1.0)
        val start = Point3(20.0, 20.0, 11.0)

        "a straight section edge joining the rims (side breakout) is a naming error" {
            val line =
                EdgeInfo(
                    20,
                    EdgeKind.LINE,
                    10.0,
                    Point3(24.0, 20.0, 10.0),
                    Point3(24.0, 20.0, 0.0),
                    Point3(24.0, 20.0, 5.0),
                    Direction3.Z,
                    null,
                    null,
                    seam = false,
                    degenerated = false,
                    faceIndices = listOf(0, 1),
                )
            val e =
                shouldThrow<EdgeNamingException> {
                    classifyRims(listOf(circle(9, 10.0), line, circle(13, 0.0)), start, down, 1e-6)
                }
            e.message!! shouldContain "breaks out through a side face"
        }

        "a single outline spanning more than maxRingSpan along the drill is a naming error" {
            val arc =
                EdgeInfo(
                    3,
                    EdgeKind.OTHER,
                    30.0,
                    Point3(24.0, 20.0, 10.0),
                    Point3(24.0, 20.0, 0.0),
                    Point3(24.0, 22.0, 5.0),
                    null,
                    null,
                    null,
                    seam = false,
                    degenerated = false,
                    faceIndices = listOf(0, 1),
                )
            val e = shouldThrow<EdgeNamingException> { classifyRims(listOf(arc), start, down, 1e-6, 8.0) }
            e.message!! shouldContain "breaks out through a side face"
            classifyRims(listOf(arc), start, down, 1e-6) shouldBe listOf(listOf(3))
        }

        "faces decide: one planar entry face is a ring, several faces or an inclined plane are not" {
            fun face(
                index: Int,
                kind: FaceKind,
                normal: Direction3?,
            ) = FaceInfo(
                index,
                kind,
                1.0,
                Point3(0.0, 0.0, 0.0),
                normal,
                if (index == 0) Direction3.Z else null,
                if (index == 0) 4.0 else null,
                emptyList(),
            )
            val flat = listOf(face(0, FaceKind.CYLINDER, null), face(1, FaceKind.PLANE, Direction3.Z))
            classifyRims(listOf(circle(9, 10.0)), start, down, 1e-6, faces = flat, wallRadius = 4.0) shouldBe
                listOf(listOf(9))
            val inclined = listOf(face(0, FaceKind.CYLINDER, null), face(1, FaceKind.PLANE, Direction3(1.0, 0.0, 0.2)))
            shouldThrow<EdgeNamingException> {
                classifyRims(listOf(circle(9, 10.0)), start, down, 1e-6, faces = inclined, wallRadius = 4.0)
            }
            val curved = listOf(face(0, FaceKind.CYLINDER, null), face(1, FaceKind.CYLINDER, null))
            shouldThrow<EdgeNamingException> {
                classifyRims(listOf(circle(9, 10.0)), start, down, 1e-6, faces = curved, wallRadius = 4.0)
            }
            classifyRims(
                listOf(circle(9, 10.0), circle(13, 0.0)),
                start,
                down,
                1e-6,
                faces = curved,
                wallRadius = 4.0,
            ) shouldBe listOf(listOf(9), listOf(13))
        }

        "no edge gives no ring" {
            classifyRims(emptyList(), start, down, 1e-6) shouldBe emptyList()
        }

        "one ring is the entry ring and there is no exit ring" {
            classifyRims(listOf(circle(9, 10.0)), start, down, 1e-6) shouldBe listOf(listOf(9))
        }

        "two rings are ordered by distance from the start along the drill direction" {
            classifyRims(listOf(circle(13, 0.0), circle(9, 10.0)), start, down, 1e-6) shouldBe
                listOf(listOf(9), listOf(13))
        }

        "the order follows the drill direction, not the world axis" {
            classifyRims(
                listOf(circle(13, 0.0), circle(9, 10.0)),
                Point3(20.0, 20.0, -1.0),
                Direction3.Z,
                1e-6,
            ) shouldBe
                listOf(listOf(13), listOf(9))
        }

        "a ring split in two edges by the seam is one ring" {
            val rings = classifyRims(halves(4, 10.0) + circle(9, 0.0), start, down, 1e-6)
            rings shouldBe listOf(listOf(4, 5), listOf(9))
        }

        "a non-unit direction gives the same order" {
            classifyRims(listOf(circle(13, 0.0), circle(9, 10.0)), start, Direction3(0.0, 0.0, -7.0), 1e-6)
                .map { it.single() } shouldBe listOf(9, 13)
        }

        "two rings on the same plane are ambiguous and reported with the candidates" {
            val e =
                shouldThrow<EdgeNamingException> {
                    classifyRims(listOf(circle(1, 10.0, cx = 20.0), circle(2, 10.0, cx = 50.0)), start, down, 1e-6)
                }
            e.message!! shouldContain "ambiguous"
            e.candidates.size shouldBe 2
        }

        "more edges than the limit are refused" {
            val many = List(4097) { circle(it, 10.0 - it * 0.01) }
            shouldThrow<IllegalArgumentException> { classifyRims(many, start, down, 1e-6) }
        }
    })
