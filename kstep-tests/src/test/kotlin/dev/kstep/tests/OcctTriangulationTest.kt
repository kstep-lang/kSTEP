package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.TriangleMesh
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The end-to-end proof for kSTEP's Viewer-Welle 1 (OCCT triangulation, see
 * docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc): a real triangle mesh extracted from OCCT
 * for a box, an extruded prism, and a filleted solid.
 *
 * The two classic OCCT triangulation pitfalls -- forgetting to flip a `REVERSED` face's winding,
 * and forgetting to apply a face's `TopLoc_Location` -- are BOTH invisible on a plain
 * `makeBox` result (see T-2's KDoc for the measured reason), so the signed-volume regression
 * cases here (T-5/T-6) deliberately run on the extruded prism and the filleted solid, not just
 * the box. See this suite's own KDoc on T-2 for the numbers.
 *
 * Every OCCT-dependent case is `.config(enabled = available)`, mirroring `OcctBridgeSmokeTest`;
 * this suite adds no second copy of the `-Pkstep.occt.require=true` guard case.
 */
class OcctTriangulationTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun rectangleProfile() =
            listOf(
                ProfilePoint(0.0, 0.0),
                ProfilePoint(20.0, 0.0),
                ProfilePoint(20.0, 30.0),
                ProfilePoint(0.0, 30.0),
            )

        // Sum over all triangles of v0 . (v1 x v2) / 6 -- the divergence-theorem formula for a
        // closed, consistently-outward-wound mesh's enclosed volume. Sign-sensitive: a REVERSED
        // face whose winding was not flipped flips the sign of exactly that face's contribution,
        // which is what T-5/T-6 below exercise.
        fun signedVolume(mesh: TriangleMesh): Double {
            var sum = 0.0
            val c = mesh.coordinates
            var i = 0
            while (i < c.size) {
                val x0 = c[i]
                val y0 = c[i + 1]
                val z0 = c[i + 2]
                val x1 = c[i + 3]
                val y1 = c[i + 4]
                val z1 = c[i + 5]
                val x2 = c[i + 6]
                val y2 = c[i + 7]
                val z2 = c[i + 8]
                // v0 . (v1 x v2)
                val cx = y1 * z2 - z1 * y2
                val cy = z1 * x2 - x1 * z2
                val cz = x1 * y2 - y1 * x2
                sum += x0 * cx + y0 * cy + z0 * cz
                i += 9
            }
            return sum / 6.0
        }

        // T-1
        "triangulating a box produces the expected triangle count".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val mesh = box.triangulate()
                mesh.triangleCount shouldBe 12
                (mesh.coordinates.size % 9) shouldBe 0
            }
        }

        // T-2: measured (see ADR-0010) -- the box's three REVERSED faces sit at x=0/y=0/z=0 (they
        // pass through the origin), so their tetrahedron contribution to signedVolume is exactly
        // zero regardless of winding, and every one of its six face Locations is the identity
        // transform. A box-only test therefore CANNOT prove either the REVERSED-swap or the
        // TopLoc_Location handling is correct -- see T-5/T-6 below, which use shapes where both
        // bugs are actually visible (measured: -8000/16000 instead of 24000 for the prism).
        "box: signed volume from the triangle mesh matches OCCT's own volume".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val mesh = box.triangulate()
                signedVolume(mesh) shouldBe (box.volume plusOrMinus box.volume * 1e-9)
            }
        }

        // T-3
        "box: every triangle vertex lies within the shape's own bounding box".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val mesh = box.triangulate()
                val c = mesh.coordinates
                var i = 0
                while (i < c.size) {
                    val x = c[i]
                    val y = c[i + 1]
                    val z = c[i + 2]
                    (x >= -1e-6 && x <= 10.0 + 1e-6) shouldBe true
                    (y >= -1e-6 && y <= 20.0 + 1e-6) shouldBe true
                    (z >= -1e-6 && z <= 30.0 + 1e-6) shouldBe true
                    i += 3
                }
            }
        }

        // T-4
        "box: no triangle is degenerate".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val mesh = box.triangulate()
                val c = mesh.coordinates
                var i = 0
                while (i < c.size) {
                    val x0 = c[i]
                    val y0 = c[i + 1]
                    val z0 = c[i + 2]
                    val x1 = c[i + 3]
                    val y1 = c[i + 4]
                    val z1 = c[i + 5]
                    val x2 = c[i + 6]
                    val y2 = c[i + 7]
                    val z2 = c[i + 8]
                    val ax = x1 - x0
                    val ay = y1 - y0
                    val az = z1 - z0
                    val bx = x2 - x0
                    val by = y2 - y0
                    val bz = z2 - z0
                    val nx = ay * bz - az * by
                    val ny = az * bx - ax * bz
                    val nz = ax * by - ay * bx
                    val area = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz) / 2.0
                    (area > 1e-12) shouldBe true
                    i += 9
                }
            }
        }

        // T-5: the beweiskraeftige case -- see this suite's KDoc. A weakened implementation
        // (missing REVERSED-swap) measured -8000 here instead of 24000; missing TopLoc measured
        // 16000.
        "extruded prism: signed volume matches OCCT's own volume".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                val mesh = prism.triangulate()
                signedVolume(mesh) shouldBe (24_000.0 plusOrMinus 240.0) // 1e-2 relative
            }
        }

        // T-6: the second beweiskraeftige case, with a NON-identity Location on top of REVERSED
        // faces (see this suite's KDoc). Measured against OCCT 7.9.2: 23778.823269 (rel. error
        // 2.77e-4 against shape.volume's 23785.398163397, well inside the 1e-2 tolerance below).
        "filleted solid: signed volume matches OCCT's own volume".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    val mesh = rounded.triangulate()
                    signedVolume(mesh) shouldBe (rounded.volume plusOrMinus kotlin.math.abs(rounded.volume) * 1e-2)
                }
            }
        }

        // T-7: exact, measured against OCCT 7.9.2 (see ADR-0010's measurement table).
        "filleted solid triangulates to the expected triangle count".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    rounded.triangulate().triangleCount shouldBe 40
                }
            }
        }

        // T-8: proves BRepTools::Clean resets cleanly rather than accumulating across repeated
        // calls on the same shape -- see TriangulationCleanupGuard's KDoc in
        // kstep_occt_bridge.cpp.
        "repeated triangulation of the same shape is idempotent".config(enabled = available) {
            OcctKernel.makeBox(10.0, 20.0, 30.0).use { box ->
                val first = box.triangulate()
                val second = box.triangulate()
                first.triangleCount shouldBe second.triangleCount
                first.coordinates.toList() shouldBe second.coordinates.toList()
            }
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    val first = rounded.triangulate()
                    val second = rounded.triangulate()
                    first.triangleCount shouldBe second.triangleCount
                    first.coordinates.toList() shouldBe second.coordinates.toList()
                }
            }
        }

        // T-9
        "triangulate() on an already-closed shape throws".config(enabled = available) {
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            box.close()
            shouldThrow<IllegalStateException> { box.triangulate() }
        }

        // T-10
        "the native bridge rejects an unknown handle directly".config(enabled = available) {
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeTriangles(999_999L) }
        }

        // T-11: TriangleMesh's own invariant, independent of OCCT -- always runs, mirroring
        // Stolperfalle 11 ("data class over a DoubleArray") in this suite's KDoc reference.
        "TriangleMesh rejects a coordinate array whose size is not a multiple of 9" {
            shouldThrow<IllegalArgumentException> { TriangleMesh(doubleArrayOf(1.0)) }
            shouldThrow<IllegalArgumentException> { TriangleMesh(DoubleArray(10)) }
            TriangleMesh(DoubleArray(0)).triangleCount shouldBe 0
            TriangleMesh(DoubleArray(9)).triangleCount shouldBe 1
        }
    })
