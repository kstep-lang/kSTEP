package dev.kstep.desktop.pick

import dev.kstep.desktop.pick.TestScenes.triangle
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class PickIndexVisibilityTest :
    StringSpec({
        fun alongZ(
            x: Double,
            y: Double,
        ) = Ray(doubleArrayOf(x, y, -100.0), doubleArrayOf(0.0, 0.0, 1.0))

        val v = PartMode.VISIBLE
        val t = PartMode.TRANSPARENT
        val h = PartMode.HIDDEN

        // part 0 is nearest, part 1 in the middle, part 2 farthest along the ray
        fun index(): PickIndex {
            val (m, table) = TestScenes.scene(triangle(0.0, 0.0, 0.0), triangle(0.0, 0.0, 5.0), triangle(0.0, 0.0, 9.0))
            return PickIndex(m, table)
        }

        "no mode is the same as before" {
            val i = index()
            i.pick(alongZ(2.0, 2.0)) shouldBe 0
            i.pick(alongZ(2.0, 2.0), null) shouldBe 0
            i.pick(alongZ(2.0, 2.0), byteArrayOf(v, v, v)) shouldBe 0
        }

        "a hidden part in front is ignored" {
            index().pick(alongZ(2.0, 2.0), byteArrayOf(h, v, v)) shouldBe 1
        }

        "an opaque part behind a transparent one is chosen" {
            index().pick(alongZ(2.0, 2.0), byteArrayOf(t, v, v)) shouldBe 1
            index().pick(alongZ(2.0, 2.0), byteArrayOf(t, t, v)) shouldBe 2
        }

        "with only transparent parts the nearest of them is chosen" {
            index().pick(alongZ(2.0, 2.0), byteArrayOf(t, t, t)) shouldBe 0
            index().pick(alongZ(2.0, 2.0), byteArrayOf(h, t, t)) shouldBe 1
        }

        "with everything hidden nothing is picked and no triangle is touched" {
            val i = index()
            i.pick(alongZ(2.0, 2.0), byteArrayOf(h, h, h)).shouldBeNull()
            i.trianglesTested shouldBe 0L
        }

        "a mode of the wrong size is ignored without a crash" {
            val i = index()
            i.pick(alongZ(2.0, 2.0), byteArrayOf(h)) shouldBe 0
            i.pick(alongZ(2.0, 2.0), ByteArray(0)) shouldBe 0
            i.pick(alongZ(2.0, 2.0), byteArrayOf(h, h, h, h)) shouldBe 0
        }

        "the cache forgets an entry on invalidate" {
            val mesh = dev.kstep.geometry.TriangleMesh(triangle(0.0, 0.0, 0.0))
            val frame =
                dev.kstep.render.mesh.MeshProjection
                    .projectFramed(mesh, 400.0, 300.0)
                    .frame
                    .shouldNotBeNull()
            val cache = PickCache()
            var calls = 0
            cache.lookup(1f, 1f, frame, 1L) {
                calls++
                3
            } shouldBe 3
            cache.lookup(1f, 1f, frame, 1L) {
                calls++
                4
            } shouldBe 3
            cache.invalidate()
            cache.lookup(1f, 1f, frame, 1L) {
                calls++
                5
            } shouldBe 5
            calls shouldBe 2
        }
    })
