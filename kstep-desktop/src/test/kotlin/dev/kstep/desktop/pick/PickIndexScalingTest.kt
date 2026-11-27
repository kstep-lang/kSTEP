package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import dev.kstep.desktop.model.PartTable
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.TriangleMesh
import dev.kstep.render.RenderLimits
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import java.util.Random

/**
 * Measures how many triangle tests one pick costs at the load limit and beyond (ADR-0093). The numbers decide whether a
 * BVH per part is needed; the assertions fix the claims the ADR makes: a pick never tests more triangles than exist
 * (twice that with a visibility mode, which runs the search up to twice), and it agrees with a brute-force picker.
 * No timing assertions: the macOS CI runner is slow.
 */
class PickIndexScalingTest :
    StringSpec({
        class Stats(
            val median: Long,
            val p95: Long,
            val max: Long,
        )

        fun stats(values: List<Long>): Stats {
            val s = values.sorted()
            return Stats(s[s.size / 2], s[(s.size * 95) / 100 - 1], s.last())
        }

        /** Nearest part by testing every triangle; the reference [PickIndex] must agree with. */
        fun brute(
            ray: Ray,
            mesh: ColoredMesh,
            table: PartTable,
        ): Int? {
            var extent = 0.0
            for (p in table.parts) for (a in 0 until 3) extent = maxOf(extent, p.bboxMax[a] - p.bboxMin[a])
            val eps = 1e-9 * maxOf(1.0, extent)
            val toPart = table.triangleToPartArray()
            var best = Double.POSITIVE_INFINITY
            var bestPart: Int? = null
            for (t in 0 until mesh.mesh.coordinates.size / 9) {
                val hit = TrianglePicker.intersect(ray, mesh.mesh.coordinates, t, eps) ?: continue
                if (hit < best) {
                    best = hit
                    bestPart = toPart[t]
                }
            }
            return bestPart
        }

        /**
         * A scene of any size, built directly: `MeshComposition.mergeColored` refuses more than the load limit, and the
         * limit is exactly what scenario (d) goes beyond. `ColoredMesh`'s constructor is internal, hence reflection.
         */
        fun bigScene(coordinates: Array<DoubleArray>): Pair<ColoredMesh, PartTable> {
            val all = DoubleArray(coordinates.sumOf { it.size })
            var offset = 0
            for (c in coordinates) {
                c.copyInto(all, offset)
                offset += c.size
            }
            val triangles = all.size / 9
            val ctor =
                ColoredMesh::class.java.getDeclaredConstructor(
                    TriangleMesh::class.java,
                    List::class.java,
                    IntArray::class.java,
                )
            ctor.isAccessible = true
            val mesh = ctor.newInstance(TriangleMesh(all), listOf(MeshColor(25 / 255.0, 0.0, 0.0)), IntArray(triangles))
            var first = 0
            val toPart = IntArray(triangles)
            val parts =
                coordinates.mapIndexed { i, c ->
                    val count = c.size / 9
                    val lo = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
                    val hi = doubleArrayOf(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
                    for (k in c.indices) {
                        lo[k % 3] = minOf(lo[k % 3], c[k])
                        hi[k % 3] = maxOf(hi[k % 3], c[k])
                    }
                    for (t in first until first + count) toPart[t] = i
                    PartInfo(
                        "P$i",
                        first,
                        count,
                        PartPlacement(
                            doubleArrayOf(0.0, 0.0, 0.0),
                            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                        ),
                        "#190000",
                        lo,
                        hi,
                        doubleArrayOf((lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2),
                    ).also { first += count }
                }
            return mesh to PartTable(parts, toPart)
        }

        fun measure(
            label: String,
            coordinates: Array<DoubleArray>,
            rays: List<Ray>,
            triangleCount: Int,
        ): Stats {
            val (mesh, table) = bigScene(coordinates)
            val index = PickIndex(mesh, table)
            val tests = ArrayList<Long>()
            val modes = ByteArray(table.parts.size) { if (it % 2 == 0) PartMode.VISIBLE else PartMode.TRANSPARENT }
            for (ray in rays) {
                val before = index.trianglesTested
                val got = index.pick(ray)
                val used = index.trianglesTested - before
                used shouldBeLessThanOrEqual triangleCount.toLong()
                got shouldBe brute(ray, mesh, table)
                tests += used
            }
            for (ray in rays.take(10)) {
                val before = index.trianglesTested
                index.pick(ray, modes)
                (index.trianglesTested - before) shouldBeLessThanOrEqual 2L * triangleCount
            }
            val s = stats(tests)
            println(
                "PICK-SCALING $label: triangles=$triangleCount parts=${table.parts.size} rays=${rays.size} " +
                    "tests median=${s.median} p95=${s.p95} max=${s.max}",
            )
            return s
        }

        fun rays(
            seed: Long,
            n: Int,
            lo: Double,
            hi: Double,
        ): List<Ray> {
            val r = Random(seed)
            return List(n) {
                Ray(
                    doubleArrayOf(lo + r.nextDouble() * (hi - lo), lo + r.nextDouble() * (hi - lo), -1000.0),
                    doubleArrayOf(0.0, 0.0, 1.0),
                )
            }
        }

        fun setTriangle(
            out: DoubleArray,
            o: Int,
            x: Double,
            y: Double,
            z: Double,
            size: Double,
        ) {
            out[o] = x
            out[o + 1] = y
            out[o + 2] = z
            out[o + 3] = x + size
            out[o + 4] = y
            out[o + 5] = z
            out[o + 6] = x
            out[o + 7] = y + size
            out[o + 8] = z
        }

        /** [count] small triangles in the plane z, laid out on a [side] x [side] raster starting at (x0, y0). */
        fun plate(
            count: Int,
            x0: Double,
            y0: Double,
            z: Double,
            side: Int,
        ): DoubleArray {
            val out = DoubleArray(count * 9)
            for (i in 0 until count) {
                val x = x0 + (i % side) * 0.5
                val y = y0 + (i / side) * 0.5
                val o = i * 9
                setTriangle(out, o, x, y, z, 0.4)
            }
            return out
        }

        "the documented load limit is the one the ADR measures" {
            check(RenderLimits.EXPORT_MAX_TRIANGLES == 130_000) {
                "Load limit changed: re-run this test and update the measurement in ADR-0093"
            }
        }

        "a 64 x 64 grid of parts at the load limit: each pick stays below the triangle count" {
            val perPart = 31
            val parts = Array(64 * 64) { plate(perPart, (it % 64) * 4.0, (it / 64) * 4.0, 0.0, 6) }
            val total = parts.size * perPart
            check(total <= RenderLimits.EXPORT_MAX_TRIANGLES)
            val s = measure("grid-4096-parts", parts, rays(1, 1000, 0.0, 64 * 4.0), total)
            // The slab pre-filter keeps a pick at the parts the ray crosses, far below the whole scene.
            s.max shouldBeLessThanOrEqual perPart.toLong() * 4L
        }

        "one part with 130000 triangles: a pick tests at most that part" {
            val n = RenderLimits.EXPORT_MAX_TRIANGLES
            measure("one-part-130k", arrayOf(plate(n, 0.0, 0.0, 0.0, 400)), rays(2, 200, 0.0, 200.0), n)
        }

        "64 nested shells whose boxes all contain the ray, the ray passing through their holes" {
            // Shell k: two rings of 50 small triangles at z = +k and z = -k; the ray along the axis misses all of them,
            // so every box is entered and every triangle of every box is tested.
            val shells =
                Array(64) { k ->
                    val ring = DoubleArray(100 * 9)
                    for (i in 0 until 100) {
                        val a = 2 * Math.PI * (i % 50) / 50
                        val z = if (i < 50) (k + 1).toDouble() else -(k + 1).toDouble()
                        val x = 20 * Math.cos(a)
                        val y = 20 * Math.sin(a)
                        val o = i * 9
                        setTriangle(ring, o, x, y, z, 1.0)
                    }
                    ring
                }
            val axis = List(20) { Ray(doubleArrayOf(0.1 * it, 0.0, -1000.0), doubleArrayOf(0.0, 0.0, 1.0)) }
            val s = measure("nested-64-shells", shells, axis + rays(3, 200, -25.0, 25.0), 64 * 100)
            s.max shouldBeLessThanOrEqual 64L * 100
        }

        "about a million triangles, in 4096 parts and in one part (beyond the load limit, only measured)" {
            val total = 1 shl 20
            val many = Array(4096) { plate(total / 4096, (it % 64) * 8.0, (it / 64) * 8.0, 0.0, 16) }
            measure("1M-in-4096-parts", many, rays(4, 50, 0.0, 64 * 8.0), total)
            val one = arrayOf(plate(total, 0.0, 0.0, 0.0, 1024))
            measure("1M-in-1-part", one, rays(5, 50, 0.0, 512.0), total)
        }
    })
