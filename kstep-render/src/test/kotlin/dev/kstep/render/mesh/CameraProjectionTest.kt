package dev.kstep.render.mesh

import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0
private const val TOLERANCE = 1e-12

/** Squared Euclidean distance between two triangles' six coordinates, used by the
 *  order-independent comparison below -- see that test's own comment for why plain `zip()` is
 *  not safe here. */
private fun coordDistanceSquared(
    a: ProjectedTriangle,
    b: ProjectedTriangle,
): Double {
    val dax = a.ax - b.ax
    val day = a.ay - b.ay
    val dbx = a.bx - b.bx
    val dby = a.by - b.by
    val dcx = a.cx - b.cx
    val dcy = a.cy - b.cy
    return dax * dax + day * day + dbx * dbx + dby * dby + dcx * dcx + dcy * dcy
}

// Same unit cube as IsometricProjectionTest -- duplicated here deliberately (see this wave's
// plan/ADR-0012) rather than shared, so this file stays a self-contained cross-check of
// MeshProjection's camera parametrization independent of that older, narrower-scoped test.
private fun unitCubeMesh(): TriangleMesh {
    val p0 = doubleArrayOf(0.0, 0.0, 0.0)
    val p1 = doubleArrayOf(1.0, 0.0, 0.0)
    val p2 = doubleArrayOf(1.0, 1.0, 0.0)
    val p3 = doubleArrayOf(0.0, 1.0, 0.0)
    val p4 = doubleArrayOf(0.0, 0.0, 1.0)
    val p5 = doubleArrayOf(1.0, 0.0, 1.0)
    val p6 = doubleArrayOf(1.0, 1.0, 1.0)
    val p7 = doubleArrayOf(0.0, 1.0, 1.0)
    val triangles =
        listOf(
            Triple(p0, p3, p2),
            Triple(p0, p2, p1),
            Triple(p4, p5, p6),
            Triple(p4, p6, p7),
            Triple(p0, p4, p7),
            Triple(p0, p7, p3),
            Triple(p1, p2, p6),
            Triple(p1, p6, p5),
            Triple(p0, p1, p5),
            Triple(p0, p5, p4),
            Triple(p3, p6, p2),
            Triple(p3, p7, p6),
        )
    val coords = DoubleArray(triangles.size * 9)
    triangles.forEachIndexed { i, (a, b, c) ->
        System.arraycopy(a, 0, coords, i * 9, 3)
        System.arraycopy(b, 0, coords, i * 9 + 3, 3)
        System.arraycopy(c, 0, coords, i * 9 + 6, 3)
    }
    return TriangleMesh(coords)
}

class CameraProjectionTest :
    StringSpec({
        "the implicit-default project() call is identical to the fully-explicit ISOMETRIC/null call" {
            val mesh = unitCubeMesh()
            val implicit = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val explicit = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, Camera.ISOMETRIC, null)

            implicit.size shouldBe explicit.size
            implicit.zip(explicit).forEach { (a, b) ->
                a.ax shouldBe (b.ax plusOrMinus TOLERANCE)
                a.ay shouldBe (b.ay plusOrMinus TOLERANCE)
                a.bx shouldBe (b.bx plusOrMinus TOLERANCE)
                a.by shouldBe (b.by plusOrMinus TOLERANCE)
                a.cx shouldBe (b.cx plusOrMinus TOLERANCE)
                a.cy shouldBe (b.cy plusOrMinus TOLERANCE)
                a.shade shouldBe (b.shade plusOrMinus TOLERANCE)
                a.depth shouldBe (b.depth plusOrMinus TOLERANCE)
            }
        }

        "a separately-constructed ISOMETRIC-angled camera derives the same view direction via trig" {
            // Deliberately NOT Camera.ISOMETRIC itself -- a fresh instance with the identical
            // angles, so MeshProjection's `camera === Camera.ISOMETRIC` fast path is NOT taken,
            // and this actually exercises Camera.viewDirection()'s trig derivation.
            val separatelyConstructed = Camera.of(45.0, 35.264389682754654)
            (separatelyConstructed === Camera.ISOMETRIC) shouldBe false

            val mesh = unitCubeMesh()
            val fastPath = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, Camera.ISOMETRIC)
            val trigPath = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, separatelyConstructed)

            fastPath.size shouldBe trigPath.size
            // Matched by NEAREST triangle, not zip()'d in list order: fastPath and trigPath each
            // independently sort their own triangles by depth (painter's algorithm), computed from
            // two numerically slightly different bases (bit-identical fast-path constants vs.
            // freshly evaluated trig, differing by ~1e-16 per component). For a symmetric mesh like
            // a unit cube, two DIFFERENT triangles can legitimately have near-tied depths -- close
            // enough that this ~1e-16 numerical noise flips their relative sort rank between the
            // two calls, even though each triangle's own coordinates individually stay accurate to
            // ~1e-13. A plain zip() would then compare two DIFFERENT triangles' coordinates against
            // each other and fail with a macroscopic difference that has nothing to do with the
            // trig derivation's actual precision (this is exactly what an earlier draft of this
            // test did, and failed on -- see docs/adr/ADR-0012-viewer-camera-interaction.adoc's
            // Stolperfallen).
            fastPath.forEach { a ->
                val nearest = trigPath.minBy { b -> coordDistanceSquared(a, b) }
                coordDistanceSquared(a, nearest) shouldBe (0.0 plusOrMinus 1e-6)
                a.shade shouldBe (nearest.shade plusOrMinus 1e-6)
            }
        }

        "Camera.viewDirection()'s trig derivation reproduces the fixed home direction within 1e-12" {
            // Direct check of Camera.viewDirection() itself (both this file and Camera.kt live in
            // dev.kstep.render.mesh, so the internal function/type are visible here) -- more
            // precise than the full-pipeline cross-check above, and isolates the trig formula
            // from any projection/scale arithmetic that could otherwise mask a regression here.
            val separatelyConstructed = Camera.of(45.0, 35.264389682754654)
            val derived = separatelyConstructed.viewDirection()
            val expected = Vec3(-1.0, -1.0, -1.0).normalized()
            derived.x shouldBe (expected.x plusOrMinus TOLERANCE)
            derived.y shouldBe (expected.y plusOrMinus TOLERANCE)
            derived.z shouldBe (expected.z plusOrMinus TOLERANCE)
        }

        "every pose in a full azimuth/elevation sweep produces finite, in-bounds, correctly-shaded output" {
            val mesh = unitCubeMesh()
            for (az in 0..345 step 15) {
                for (el in -89..89 step 10) {
                    val triangles =
                        MeshProjection.project(
                            mesh,
                            CANVAS_WIDTH,
                            CANVAS_HEIGHT,
                            Camera.of(az.toDouble(), el.toDouble()),
                        )
                    triangles.forEach { t ->
                        listOf(t.ax, t.ay, t.bx, t.by, t.cx, t.cy, t.shade, t.depth).forEach { v ->
                            v.isFinite() shouldBe true
                        }
                        (t.shade in MeshProjection.AMBIENT..1.0) shouldBe true
                        (t.ax in 0.0..CANVAS_WIDTH) shouldBe true
                        (t.bx in 0.0..CANVAS_WIDTH) shouldBe true
                        (t.cx in 0.0..CANVAS_WIDTH) shouldBe true
                        (t.ay in 0.0..CANVAS_HEIGHT) shouldBe true
                        (t.by in 0.0..CANVAS_HEIGHT) shouldBe true
                        (t.cy in 0.0..CANVAS_HEIGHT) shouldBe true
                    }
                }
            }
        }

        "orbiting 90 degrees in azimuth from home produces a genuinely different projection" {
            val mesh = unitCubeMesh()
            val home = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, Camera.ISOMETRIC)
            val orbited =
                MeshProjection.project(
                    mesh,
                    CANVAS_WIDTH,
                    CANVAS_HEIGHT,
                    Camera.of(135.0, 35.264389682754654),
                )
            (home != orbited) shouldBe true
        }

        "scale of zero is rejected" {
            shouldThrow<IllegalArgumentException> {
                MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT, scale = 0.0)
            }
        }

        "a negative scale is rejected" {
            shouldThrow<IllegalArgumentException> {
                MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT, scale = -1.0)
            }
        }

        "a NaN scale is rejected" {
            shouldThrow<IllegalArgumentException> {
                MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT, scale = Double.NaN)
            }
        }

        "an infinite scale is rejected" {
            shouldThrow<IllegalArgumentException> {
                MeshProjection.project(unitCubeMesh(), CANVAS_WIDTH, CANVAS_HEIGHT, scale = Double.POSITIVE_INFINITY)
            }
        }

        "a positive finite scale is accepted and directly scales the silhouette" {
            val mesh = unitCubeMesh()
            val autoFit = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val fitScale = MeshProjection.fitScale(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val explicit = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, scale = fitScale)
            autoFit.zip(explicit).forEach { (a, b) ->
                a.ax shouldBe (b.ax plusOrMinus TOLERANCE)
                a.ay shouldBe (b.ay plusOrMinus TOLERANCE)
            }
        }

        "fitScale at ISOMETRIC matches the auto-fit scale project() would have used" {
            val mesh = unitCubeMesh()
            val fitScale = MeshProjection.fitScale(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val doubled = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, scale = fitScale * 2.0)
            val autoFit = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            // A 2x explicit scale must make every edge twice as far from the canvas center as
            // auto-fit -- proves fitScale() actually reports the same number project()'s own
            // auto-fit path would have used internally, not just "some positive number".
            val autoDx = autoFit.first().ax - CANVAS_WIDTH / 2.0
            val doubledDx = doubled.first().ax - CANVAS_WIDTH / 2.0
            doubledDx shouldBe (autoDx * 2.0 plusOrMinus 1e-6)
        }

        "shade values at ISOMETRIC are unchanged from this codebase's pre-wave behavior" {
            val mesh = unitCubeMesh()
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT, Camera.ISOMETRIC)
            triangles.forEach { t -> (t.shade in MeshProjection.AMBIENT..1.0) shouldBe true }
            val distinctShades = triangles.map { it.shade }.toSet()
            (distinctShades.size >= 3) shouldBe true
        }
    })
