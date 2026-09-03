package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.render.mesh.MeshProjection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.math.max
import kotlin.math.sqrt

private const val CANVAS_WIDTH = 800.0
private const val CANVAS_HEIGHT = 600.0

// This codebase's PRE-fill-light shading formula, reconstructed here only for comparison -- see
// keyOnlyShades' own KDoc for why this test cannot simply import MeshProjection's private
// KEY_WEIGHT/LIGHT_DIRECTION_HOME constants.
private const val KEY_ONLY_WEIGHT = 1.0 - MeshProjection.AMBIENT

private fun normalize(v: DoubleArray): DoubleArray {
    val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    return doubleArrayOf(v[0] / len, v[1] / len, v[2] / len)
}

private fun dot(
    a: DoubleArray,
    b: DoubleArray,
): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

private fun cross(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray =
    doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

private fun sub(
    a: DoubleArray,
    b: DoubleArray,
): DoubleArray = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

// MeshProjection.VIEW_DIRECTION_HOME/LIGHT_DIRECTION_HOME are `private` to
// dev.kstep.render.mesh -- re-derived here from the same literals that object's own KDoc
// documents (`normalize(-1,-1,-1)` / `normalize(-0.2,-0.4,-1.0)`), exactly like
// `IsometricProjectionTest`/`CameraProjectionTest` already do for the view direction. This is a
// deliberately independent re-derivation, not a shortcut around `Vec3`'s `internal` visibility
// (see `Placement`'s own KDoc for why `kstep-geometry` does not reach for `dev.kstep.render`'s
// internal vector type either).
private val VIEW_HOME = normalize(doubleArrayOf(-1.0, -1.0, -1.0))
private val LIGHT_HOME = normalize(doubleArrayOf(-0.2, -0.4, -1.0))

/**
 * Independently recomputes what [MeshProjection.project] would have produced under the
 * PRE-multi-shape-composition-and-fill-light-wave, key-light-ONLY formula (see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`), for the exact same ISOMETRIC
 * view/light directions and backface-culling rule [MeshProjection.project] itself uses. NOT a
 * copy of [MeshProjection]'s full cull/sort/scale/screen-basis pipeline -- just enough of its
 * shading formula and culling rule to produce one shade value per SURVIVING triangle, in mesh
 * order, so this test can compare "how many distinct shades would the OLD formula have produced"
 * against the REAL (key+fill) shades [MeshProjection.project] actually returns for the identical
 * input mesh.
 */
private fun keyOnlyShades(coordinates: DoubleArray): List<Double> {
    val shades = mutableListOf<Double>()
    val negLight = doubleArrayOf(-LIGHT_HOME[0], -LIGHT_HOME[1], -LIGHT_HOME[2])
    var i = 0
    while (i < coordinates.size) {
        val v0 = doubleArrayOf(coordinates[i], coordinates[i + 1], coordinates[i + 2])
        val v1 = doubleArrayOf(coordinates[i + 3], coordinates[i + 4], coordinates[i + 5])
        val v2 = doubleArrayOf(coordinates[i + 6], coordinates[i + 7], coordinates[i + 8])
        i += 9
        val normal = cross(sub(v1, v0), sub(v2, v0))
        val length = sqrt(dot(normal, normal))
        if (length <= 1e-12) continue // degenerate, same threshold MeshProjection uses
        if (dot(normal, VIEW_HOME) >= 0.0) continue // backface, same rule MeshProjection uses
        val unitNormal = doubleArrayOf(normal[0] / length, normal[1] / length, normal[2] / length)
        val shade = MeshProjection.AMBIENT + KEY_ONLY_WEIGHT * max(0.0, dot(unitNormal, negLight))
        shades.add(shade)
    }
    return shades
}

/**
 * End-to-end proof, against a REAL `OcctKernel.fillet(...)` result (not a hand-built mesh), that
 * kSTEP's fill light (see `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`)
 * measurably changes the shading of an actual filleted OCCT shape -- `FillLightShadingTest` (in
 * `kstep-render`) already pins the formula itself against a hand-built triangle; this suite is the
 * real-geometry companion, mirroring how `RenderOcctPipelineTest` complements
 * `IsometricProjectionTest`.
 */
class FilletShadingTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        "a real filleted box's minimum visible shade sits comfortably above AMBIENT, unlike the pre-wave key-only formula"
            .config(
                enabled = available,
            ) {
                val mesh =
                    OcctKernel.makeBox(20.0, 20.0, 12.0).use { box ->
                        OcctKernel.fillet(box, edgeIndex = 0, radius = 3.0).use { it.triangulate() }
                    }

                val realTriangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
                realTriangles.isEmpty() shouldBe false

                val minReal = realTriangles.minOf { it.shade }
                // The pre-wave key-only formula's floor is exactly AMBIENT (0.25) for any face whose
                // normal has zero (or negative) key-light alignment; the fill light's whole point is
                // that no VISIBLE face should be left sitting at that floor any more. 0.05 is a safe
                // margin above AMBIENT, comfortably clear of floating-point noise.
                (minReal > MeshProjection.AMBIENT + 0.05) shouldBe true
            }

        "a real filleted box shows at least as many distinct shades under key+fill as the pre-wave key-only formula would"
            .config(
                enabled = available,
            ) {
                val mesh =
                    OcctKernel.makeBox(20.0, 20.0, 12.0).use { box ->
                        OcctKernel.fillet(box, edgeIndex = 0, radius = 3.0).use { it.triangulate() }
                    }

                val realTriangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
                val keyOnly = keyOnlyShades(mesh.coordinates)
                // Both derivations apply the identical cull rule to the identical mesh, in the same
                // triangle order -- their surviving-triangle counts must match exactly.
                keyOnly.size shouldBe realTriangles.size

                val realDistinctCount = realTriangles.map { it.shade }.toSet().size
                val keyOnlyDistinctCount = keyOnly.toSet().size
                (realDistinctCount >= keyOnlyDistinctCount) shouldBe true
            }
    })
