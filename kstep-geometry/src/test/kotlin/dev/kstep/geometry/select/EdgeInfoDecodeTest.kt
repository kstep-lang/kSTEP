package dev.kstep.geometry.select

import dev.kstep.geometry.OcctKernel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EdgeInfoDecodeTest :
    StringSpec({
        fun lineRecord(
            seam: Double = 0.0,
            degenerated: Double = 0.0,
            kind: Double = 0.0,
        ) = doubleArrayOf(
            kind,
            10.0,
            0.0,
            0.0,
            0.0,
            10.0,
            0.0,
            0.0,
            5.0,
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            seam,
            degenerated,
        )

        // two edges, one face containing both: [faceCount, offsets(2)..., indices...]
        val csr = intArrayOf(1, 0, 2, 0, 1)

        "a hand-built line record decodes with its adjacency" {
            val edges = decodeEdgeInfo(lineRecord() + lineRecord(), csr)
            edges.size shouldBe 2
            edges[0].kind shouldBe EdgeKind.LINE
            edges[0].length shouldBe 10.0
            edges[0].direction shouldBe Direction3(1.0, 0.0, 0.0)
            edges[0].faceIndices shouldBe listOf(0)
            edges[1].index shouldBe 1
        }

        "a circle record carries centre and radius, a flagged edge its flags" {
            val circle = lineRecord(seam = 1.0, kind = 1.0)
            circle[14] = 1.0
            circle[15] = 2.0
            circle[16] = 3.0
            circle[17] = 4.5
            val edges = decodeEdgeInfo(circle + lineRecord(degenerated = 1.0), csr)
            edges[0].kind shouldBe EdgeKind.CIRCLE
            edges[0].circleCenter shouldBe Point3(1.0, 2.0, 3.0)
            edges[0].radius shouldBe 4.5
            edges[0].seam shouldBe true
            edges[1].degenerated shouldBe true
        }

        "a payload whose size is not a multiple of the record size is rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeInfo(DoubleArray(21), intArrayOf(0, 0)) }
        }

        "an unknown kind, a bad flag and a non-finite number are rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(kind = 3.0), intArrayOf(0, 0)) }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(seam = 0.5), intArrayOf(0, 0)) }
            val nan = lineRecord()
            nan[3] = Double.NaN
            shouldThrow<IllegalStateException> { decodeEdgeInfo(nan, intArrayOf(0, 0)) }
        }

        "a negative edge length is rejected" {
            val r = lineRecord()
            r[1] = -1.0
            shouldThrow<IllegalStateException> { decodeEdgeInfo(r, intArrayOf(0, 0)) }
        }

        "an adjacency index outside the edge range is rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(1, 0, 1, 7)) }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(1, 0, 1, -1)) }
        }

        "malformed adjacency headers are rejected" {
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), IntArray(0)) }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(-1, 0)) }
            shouldThrow<IllegalStateException> {
                decodeEdgeInfo(
                    lineRecord(),
                    intArrayOf(OcctKernel.MAX_QUERY_FACES + 1, 0),
                )
            }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(2, 0, 0)) }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(1, 1, 1, 0)) }
            shouldThrow<IllegalStateException> { decodeEdgeInfo(lineRecord(), intArrayOf(1, 0, 5, 0)) }
        }

        "more edges than the query limit are rejected" {
            shouldThrow<IllegalStateException> {
                decodeEdgeInfo(
                    DoubleArray(
                        (OcctKernel.MAX_QUERY_EDGES + 1) * EDGE_RECORD,
                    ),
                    intArrayOf(0, 0),
                )
            }
        }

        "a face record decodes plane normals and cylinder axes" {
            val plane = doubleArrayOf(0.0, 16.0, 1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
            val cylinder = doubleArrayOf(1.0, 31.4, 0.0, 0.0, 5.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 3.0)
            val faces = decodeFaceInfo(plane + cylinder, intArrayOf(2, 0, 1, 2, 0, 1), edgeCount = 2)
            faces[0].kind shouldBe FaceKind.PLANE
            faces[0].normal shouldBe Direction3.Z
            faces[0].edgeIndices shouldBe listOf(0)
            faces[1].kind shouldBe FaceKind.CYLINDER
            faces[1].axis shouldBe Direction3.Z
            faces[1].radius shouldBe 3.0
            faces[1].normal shouldBe null
        }

        "a face payload inconsistent with the adjacency is rejected" {
            val plane = doubleArrayOf(0.0, 16.0, 1.0, 2.0, 3.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0)
            shouldThrow<IllegalStateException> { decodeFaceInfo(plane, intArrayOf(2, 0, 0, 0), edgeCount = 1) }
            shouldThrow<IllegalStateException> { decodeFaceInfo(DoubleArray(13), intArrayOf(0, 0), edgeCount = 0) }
            val badKind = plane.copyOf().also { it[0] = 9.0 }
            shouldThrow<IllegalStateException> { decodeFaceInfo(badKind, intArrayOf(1, 0, 0), edgeCount = 0) }
        }
    })
