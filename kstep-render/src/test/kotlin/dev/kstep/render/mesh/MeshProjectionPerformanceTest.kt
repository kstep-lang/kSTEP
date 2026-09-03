package dev.kstep.render.mesh

import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.TriangleMesh
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

private val logger = KotlinLogging.logger {}

private const val CANVAS_WIDTH = 1200.0
private const val CANVAS_HEIGHT = 900.0

/**
 * A synthetic mesh at [OcctKernel.MAX_TRIANGLES]'s order of magnitude -- generated arithmetically,
 * no OCCT/native bridge involved, so this test always runs (unlike the `kstep.occt.require`-gated
 * tests elsewhere in this module). Guards against a future change to [MeshProjection.fitScale]/
 * [MeshProjection.project] (e.g. the bounding-box computation, or [MeshProjection]'s per-call
 * basis/light recombination added in this wave) silently going quadratic -- a single `project()`
 * call at this scale must stay comfortably sub-second.
 */
private fun manyTinyTrianglesMesh(count: Int): TriangleMesh {
    // A grid of tiny, non-degenerate, non-coplanar triangles -- each one individually axis-tilted
    // by its index so backface culling does not simply discard all of them (which would make this
    // test measure an empty loop instead of the real cull/shade/bbox pipeline).
    val coords = DoubleArray(count * 9)
    for (i in 0 until count) {
        val row = i / 1000
        val col = i % 1000
        val baseX = col * 0.01
        val baseY = row * 0.01
        val tiltZ = (i % 7) * 0.001 // varies the normal across triangles
        val o = i * 9
        coords[o + 0] = baseX
        coords[o + 1] = baseY
        coords[o + 2] = tiltZ
        coords[o + 3] = baseX + 0.005
        coords[o + 4] = baseY
        coords[o + 5] = tiltZ
        coords[o + 6] = baseX
        coords[o + 7] = baseY + 0.005
        coords[o + 8] = tiltZ + 0.002
    }
    return TriangleMesh(coords)
}

class MeshProjectionPerformanceTest :
    StringSpec({
        "a single project() call over MAX_TRIANGLES-order-of-magnitude triangles stays well under one second" {
            val mesh = manyTinyTrianglesMesh(OcctKernel.MAX_TRIANGLES)

            val startNanos = System.nanoTime()
            val triangles = MeshProjection.project(mesh, CANVAS_WIDTH, CANVAS_HEIGHT)
            val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000.0

            logger.info { "MeshProjection.project() over ${mesh.triangleCount} triangles took ${elapsedMillis}ms" }

            triangles.isEmpty() shouldBe false
            (elapsedMillis < 1000.0) shouldBe true
        }
    })
