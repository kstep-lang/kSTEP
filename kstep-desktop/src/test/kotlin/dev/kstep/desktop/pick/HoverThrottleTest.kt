package dev.kstep.desktop.pick

import dev.kstep.render.mesh.MeshProjection
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class HoverThrottleTest :
    StringSpec({
        "the first point passes at once, later ones only after the interval, the latest point wins" {
            var now = 1_000L
            val throttle = HoverThrottle({ now })
            throttle.offer(1f, 1f)
            throttle.due() shouldBe (1f to 1f)
            throttle.offer(2f, 2f)
            throttle.offer(3f, 3f)
            throttle.due().shouldBeNull()
            now += 32
            throttle.due().shouldBeNull()
            now += 1
            throttle.due() shouldBe (3f to 3f)
            throttle.due().shouldBeNull()
        }

        "clear drops a pending point" {
            var now = 0L
            val throttle = HoverThrottle({ now })
            throttle.offer(1f, 1f)
            throttle.due().shouldNotBeNull()
            throttle.offer(2f, 2f)
            throttle.clear()
            now += 100
            throttle.due().shouldBeNull()
        }
    })

class PickCacheTest :
    StringSpec({
        val triangles = TestScenes.triangle(0.0, 0.0, 0.0)
        val mesh = dev.kstep.geometry.TriangleMesh(triangles)
        val frame = MeshProjection.projectFramed(mesh, 400.0, 300.0).frame.shouldNotBeNull()
        val sameFrame = MeshProjection.projectFramed(mesh, 400.0, 300.0).frame.shouldNotBeNull()
        val movedFrame = MeshProjection.projectFramed(mesh, 400.0, 300.0, panX = 9.0).frame.shouldNotBeNull()

        "an unchanged pixel, mapping and scene is answered from the cache" {
            val cache = PickCache()
            var computed = 0
            val compute = {
                computed++
                7
            }
            cache.lookup(1f, 2f, frame, 1, compute) shouldBe 7
            cache.lookup(1f, 2f, sameFrame, 1, compute) shouldBe 7
            computed shouldBe 1
        }

        "another pixel, a moved camera or a new scene recomputes" {
            val cache = PickCache()
            var computed = 0
            val compute = {
                computed++
                computed
            }
            cache.lookup(1f, 2f, frame, 1, compute)
            cache.lookup(1f, 3f, frame, 1, compute)
            cache.lookup(1f, 3f, movedFrame, 1, compute)
            cache.lookup(1f, 3f, movedFrame, 2, compute)
            computed shouldBe 4
        }
    })
