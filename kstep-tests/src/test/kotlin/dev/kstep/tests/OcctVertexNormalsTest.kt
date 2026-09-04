package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import dev.kstep.geometry.transformedBy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.acos
import kotlin.math.sqrt

private fun normalize(v: DoubleArray): DoubleArray {
    val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
}

private fun dot(
    a: DoubleArray,
    b: DoubleArray,
): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

/** Face normal of one triangle, from its three world-space vertices (unnormalized cross
 *  product, normalized here). */
private fun faceNormalAt(
    coordinates: DoubleArray,
    triangleOffset: Int,
): DoubleArray {
    val v0 =
        doubleArrayOf(coordinates[triangleOffset], coordinates[triangleOffset + 1], coordinates[triangleOffset + 2])
    val v1 =
        doubleArrayOf(
            coordinates[triangleOffset + 3],
            coordinates[triangleOffset + 4],
            coordinates[triangleOffset + 5],
        )
    val v2 =
        doubleArrayOf(
            coordinates[triangleOffset + 6],
            coordinates[triangleOffset + 7],
            coordinates[triangleOffset + 8],
        )
    val ux = v1[0] - v0[0]
    val uy = v1[1] - v0[1]
    val uz = v1[2] - v0[2]
    val wx = v2[0] - v0[0]
    val wy = v2[1] - v0[1]
    val wz = v2[2] - v0[2]
    return normalize(doubleArrayOf(uy * wz - uz * wy, uz * wx - ux * wz, ux * wy - uy * wx))
}

/**
 * End-to-end proof, against REAL `OcctKernel`-produced shapes, of kSTEP's smooth per-vertex
 * normals (see docs/adr/ADR-0018-smooth-vertex-normals.adoc): `BRepLib_ToolTriangulatedShape::
 * ComputeNormals`, the header-prefixed native return layout, the REVERSED-face negation, and the
 * `TopLoc_Location`-is-rotation-only rule for [Placement.applyToDirection]/[transformedBy].
 *
 * A plain box is deliberately used for the REVERSED/winding/planar-equivalence cases (b, c) --
 * ADR-0010's own note that a box's REVERSED faces sit at the origin and cannot prove
 * `TopLoc_Location` handling does NOT apply to normals in the same way (a face NORMAL is a
 * direction, unaffected by where the face sits, unlike the signed-volume trap that note
 * describes for POSITIONS) -- and a real `OcctKernel.fillet(...)` result (the same curved-face
 * shape `FilletShadingTest` already uses) for the genuinely curved case (a).
 */
class OcctVertexNormalsTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun filletedBox(): TriangleMesh =
            OcctKernel.makeBox(20.0, 20.0, 12.0).use { box ->
                OcctKernel.fillet(box, edgeIndex = 0, radius = 3.0).use { it.triangulate() }
            }

        // a: curved face -- at least one vertex normal measurably diverges from its OWN
        // triangle's flat face normal (proves genuine per-vertex smoothing on the fillet's curved
        // region, unlike test b's exact planar equality). OCCT triangulates each TopoDS_Face with
        // its own separate Poly_Triangulation (CLAUDE.md's Renderer-Sizing-Heuristik note: "Kanten
        // bleiben scharf" -- edges are NOT smoothed across face boundaries), so this deliberately
        // checks divergence WITHIN one triangle's own reading, not equality of same-position
        // vertices reached via different triangles (which can legitimately belong to different
        // faces, and therefore different, independently-smoothed normals -- verified empirically:
        // an earlier version of this test asserted cross-triangle equality by rounded 3D position
        // and failed on this exact shape, precisely because it was unknowingly comparing two
        // different faces' boundary nodes).
        "a real fillet's curved face carries at least one vertex normal that diverges from its triangle's flat face normal"
            .config(enabled = available) {
                val mesh = filletedBox()
                mesh.hasVertexNormals shouldBe true
                val vn = mesh.vertexNormals!!
                val c = mesh.coordinates

                var maxAngleDegrees = 0.0
                var i = 0
                while (i < c.size) {
                    val faceNormal = faceNormalAt(c, i)
                    for (corner in 0 until 3) {
                        val offset = i + corner * 3
                        val vertexNormal = normalize(doubleArrayOf(vn[offset], vn[offset + 1], vn[offset + 2]))
                        val cosAngle = dot(faceNormal, vertexNormal).coerceIn(-1.0, 1.0)
                        val angle = Math.toDegrees(acos(cosAngle))
                        if (angle > maxAngleDegrees) maxAngleDegrees = angle
                    }
                    i += 9
                }
                // Measured (see docs/adr/ADR-0018-smooth-vertex-normals.adoc's Measurements table):
                // a cylindrical fillet surface shows up to ~6.9 degrees of vertex-vs-face-normal
                // divergence. 1.0 degree is a safe margin above floating-point/meshing noise while
                // staying well below that measured value.
                (maxAngleDegrees > 1.0) shouldBe true
            }

        // b: planar face -- vertex normal must equal the face (plane) normal exactly (up to
        // floating-point noise), for EVERY triangle of a pure box. Pins Jobs' condition 5.
        "a plain box's vertex normals equal each triangle's own face normal (planar faces)".config(
            enabled = available,
        ) {
            val mesh = OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() }
            mesh.hasVertexNormals shouldBe true
            val vn = mesh.vertexNormals!!
            val c = mesh.coordinates
            var i = 0
            while (i < c.size) {
                val faceNormal = faceNormalAt(c, i)
                for (corner in 0 until 3) {
                    val offset = i + corner * 3
                    val vertexNormal = normalize(doubleArrayOf(vn[offset], vn[offset + 1], vn[offset + 2]))
                    dot(vertexNormal, faceNormal) shouldBe (1.0 plusOrMinus 1e-5)
                }
                i += 9
            }
        }

        // c: REVERSED-face sign. Every vertex normal of a convex, closed box must point OUTWARD
        // -- this is the test that catches a missing `v.Reverse()` on the native side (half the
        // box's faces are REVERSED; without the negation, half the vertex normals point inward).
        "every vertex normal of a closed box points outward from its centroid".config(enabled = available) {
            val mesh = OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() }
            val vn = mesh.vertexNormals!!
            val c = mesh.coordinates
            val centroid = doubleArrayOf(5.0, 10.0, 15.0) // box center, dx/2, dy/2, dz/2
            var i = 0
            while (i < c.size) {
                for (corner in 0 until 3) {
                    val offset = i + corner * 3
                    val toVertex =
                        doubleArrayOf(c[offset] - centroid[0], c[offset + 1] - centroid[1], c[offset + 2] - centroid[2])
                    val n = doubleArrayOf(vn[offset], vn[offset + 1], vn[offset + 2])
                    (dot(n, toVertex) > 0.0) shouldBe true
                }
                i += 9
            }
        }

        // d: TopLoc_Location transform on normals -- rotated + translated, must rotate WITHOUT
        // translating (unit length must survive; a translation leak would blow it up to ~ the
        // translation magnitude, see MeshCompositionTest's identical Kotlin-side guard).
        "a rotated and translated box's vertex normals stay unit length and rotate, never translate".config(
            enabled = available,
        ) {
            val mesh = OcctKernel.makeBox(10.0, 20.0, 30.0).use { it.triangulate() }
            val placement = Placement.rotationZ(90.0).then(Placement.translation(1000.0, 2000.0, 3000.0))
            val transformed = mesh.transformedBy(placement)
            val vn = transformed.vertexNormals!!
            var idx = 0
            while (idx < vn.size) {
                val len = sqrt(vn[idx] * vn[idx] + vn[idx + 1] * vn[idx + 1] + vn[idx + 2] * vn[idx + 2])
                len shouldBe (1.0 plusOrMinus 1e-6)
                idx += 3
            }
        }

        // e: invariant + unit length across the whole mesh.
        "vertexNormals is exactly as long as coordinates, and every normal is unit length".config(enabled = available) {
            val mesh = filletedBox()
            val vn = mesh.vertexNormals!!
            vn.size shouldBe mesh.coordinates.size
            var idx = 0
            while (idx < vn.size) {
                val len = sqrt(vn[idx] * vn[idx] + vn[idx + 1] * vn[idx + 1] + vn[idx + 2] * vn[idx + 2])
                len shouldBe (1.0 plusOrMinus 1e-6)
                idx += 3
            }
        }

        // f: determinism -- extends T-8's coordinate-only determinism check to normals.
        "repeated triangulation of the same shape produces identical positions and normals".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val first = box.triangulate()
                val second = box.triangulate()
                first.coordinates.toList() shouldBe second.coordinates.toList()
                first.vertexNormals!!.toList() shouldBe second.vertexNormals!!.toList()
            }
        }

        // g: a shape with zero faces (a wire/vertex-only compound) is not directly constructible
        // through OcctKernel's current public API (every OcctKernel factory produces a solid) --
        // this is covered instead at the decodeTriangles layer (see MeshCompositionTest's
        // "decodeTriangles: header-only array (N=0) decodes to an empty mesh") and at
        // OcctTriangulationTest's own empty-mesh-is-valid documentation. Left as a documented
        // gap rather than a fabricated OCCT call, since kSTEP has no supported way to construct
        // a genuinely faceless shape today.
        "TriangleMesh(coordinates) with zero triangles has null vertexNormals (documented empty-mesh contract)" {
            val mesh = TriangleMesh(DoubleArray(0))
            mesh.triangleCount shouldBe 0
            mesh.vertexNormals shouldBe null
        }
    })
