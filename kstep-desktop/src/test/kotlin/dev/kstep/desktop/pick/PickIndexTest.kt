package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

class PickIndexTest :
    StringSpec({
        fun alongZ(
            x: Double,
            y: Double,
        ) = Ray(doubleArrayOf(x, y, -100.0), doubleArrayOf(0.0, 0.0, 1.0))

        "an empty scene picks nothing" {
            val index = PickIndex(TestScenes.mesh(doubleArrayOf()), PartTable(emptyList(), IntArray(0)))
            index.pick(alongZ(1.0, 1.0)).shouldBeNull()
            index.trianglesTested shouldBe 0L
        }

        "the nearer of two parts behind each other wins, in either list order" {
            val near = triangle(0.0, 0.0, 0.0)
            val far = triangle(0.0, 0.0, 5.0)
            val (m1, t1) = TestScenes.scene(near, far)
            PickIndex(m1, t1).pick(alongZ(2.0, 2.0)) shouldBe 0
            val (m2, t2) = TestScenes.scene(far, near)
            PickIndex(m2, t2).pick(alongZ(2.0, 2.0)) shouldBe 1
        }

        "a ray between parts misses" {
            val (m, t) = TestScenes.scene(triangle(0.0, 0.0, 0.0), triangle(50.0, 0.0, 0.0))
            PickIndex(m, t).pick(alongZ(30.0, 2.0)).shouldBeNull()
        }

        "boxes the ray misses cost no triangle tests" {
            val fan = (0 until 200).map { triangle(it * 0.01, 0.0, 0.0) }.reduce { a, b -> a + b }
            val (m, t) = TestScenes.scene(fan, triangle(100.0, 0.0, 0.0))
            val index = PickIndex(m, t)
            index.pick(alongZ(102.0, 2.0)) shouldBe 1
            index.trianglesTested shouldBe 1L
        }

        "the search stops once the nearest box hit cannot be beaten" {
            val fan = (0 until 200).map { triangle(it * 0.01, 0.0, 0.0) }.reduce { a, b -> a + b }
            val behind = (0 until 300).map { triangle(it * 0.01, 0.0, 50.0) }.reduce { a, b -> a + b }
            val (m, t) = TestScenes.scene(fan, behind)
            val index = PickIndex(m, t)
            index.pick(alongZ(2.0, 2.0)) shouldBe 0
            index.trianglesTested shouldBe 200L
        }

        "a hit exactly on the box edge still counts" {
            val (m, t) = TestScenes.scene(triangle(0.0, 0.0, 0.0))
            PickIndex(m, t).pick(alongZ(10.0, 0.0)) shouldBe 0
            PickIndex(m, t).pick(alongZ(0.0, 5.0)) shouldBe 0
        }

        "a scene at the triangle limit is picked without testing more triangles than exist".config(
            timeout = 120.seconds,
        ) {
            val side = 360
            val coords = DoubleArray(side * side * 2 * 9)
            var o = 0
            for (i in 0 until side) {
                for (j in 0 until side) {
                    val x = i.toDouble()
                    val y = j.toDouble()
                    val tri1 = doubleArrayOf(x, y, 0.0, x + 1, y, 0.0, x, y + 1, 0.0)
                    val tri2 = doubleArrayOf(x + 1, y, 0.0, x + 1, y + 1, 0.0, x, y + 1, 0.0)
                    tri1.copyInto(coords, o)
                    tri2.copyInto(coords, o + 9)
                    o += 18
                }
            }
            (coords.size / 9) shouldBe 259_200
            // the real limit is 130 000: use the first 130 000 triangles
            val limited = coords.copyOf(130_000 * 9)
            val (m, t) = TestScenes.scene(limited)
            val index = PickIndex(m, t)
            index.pick(alongZ(0.25, 0.25)) shouldBe 0
            index.trianglesTested shouldBeLessThan 130_001L
        }
    })
