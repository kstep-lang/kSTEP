package dev.kstep.desktop.pick

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class TrianglePickerTest :
    StringSpec({
        val tri = doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0)
        val eps = 1e-9

        fun ray(
            ox: Double,
            oy: Double,
            oz: Double,
            dx: Double,
            dy: Double,
            dz: Double,
        ) = Ray(doubleArrayOf(ox, oy, oz), doubleArrayOf(dx, dy, dz))

        "a ray through the middle hits at the right distance" {
            TrianglePicker
                .intersect(
                    ray(2.0, 2.0, -5.0, 0.0, 0.0, 1.0),
                    tri,
                    0,
                    eps,
                ).shouldNotBeNull()
                .shouldBe(5.0.plusOrMinus(1e-9))
        }

        "edge and corner hits count within the tolerance" {
            TrianglePicker.intersect(ray(5.0, 0.0, -1.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldNotBeNull()
            TrianglePicker.intersect(ray(0.0, 0.0, -1.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldNotBeNull()
            TrianglePicker.intersect(ray(5.0, 5.0, -1.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldNotBeNull()
        }

        "just outside misses" {
            TrianglePicker.intersect(ray(-0.01, 2.0, -1.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(6.0, 6.0, -1.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldBeNull()
        }

        "a ray parallel to the triangle plane misses" {
            TrianglePicker.intersect(ray(-5.0, 2.0, 0.0, 1.0, 0.0, 0.0), tri, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(-5.0, 2.0, 1.0, 1.0, 0.0, 0.0), tri, 0, eps).shouldBeNull()
        }

        "a triangle behind the ray origin or on it is not hit" {
            TrianglePicker.intersect(ray(2.0, 2.0, 5.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(2.0, 2.0, 0.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldBeNull()
        }

        "both sides are hit" {
            TrianglePicker
                .intersect(
                    ray(2.0, 2.0, 5.0, 0.0, 0.0, -1.0),
                    tri,
                    0,
                    eps,
                ).shouldNotBeNull()
                .shouldBe(5.0.plusOrMinus(1e-9))
        }

        "degenerate triangles are skipped" {
            val collinear = doubleArrayOf(0.0, 0.0, 0.0, 5.0, 0.0, 0.0, 10.0, 0.0, 0.0)
            val duplicate = doubleArrayOf(1.0, 1.0, 0.0, 1.0, 1.0, 0.0, 5.0, 5.0, 0.0)
            TrianglePicker.intersect(ray(2.0, 0.0, -5.0, 0.0, 0.0, 1.0), collinear, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(1.0, 1.0, -5.0, 0.0, 0.0, 1.0), duplicate, 0, eps).shouldBeNull()
        }

        "NaN and infinite coordinates never hit" {
            val nan = doubleArrayOf(Double.NaN, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0)
            val inf = doubleArrayOf(0.0, 0.0, 0.0, Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 10.0, 0.0)
            TrianglePicker.intersect(ray(2.0, 2.0, -5.0, 0.0, 0.0, 1.0), nan, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(2.0, 2.0, -5.0, 0.0, 0.0, 1.0), inf, 0, eps).shouldBeNull()
            TrianglePicker.intersect(ray(Double.NaN, 2.0, -5.0, 0.0, 0.0, 1.0), tri, 0, eps).shouldBeNull()
        }

        "the triangle index selects the right nine coordinates" {
            val two = tri + doubleArrayOf(20.0, 0.0, 0.0, 30.0, 0.0, 0.0, 20.0, 10.0, 0.0)
            TrianglePicker.intersect(ray(22.0, 2.0, -5.0, 0.0, 0.0, 1.0), two, 1, eps).shouldNotBeNull()
            TrianglePicker.intersect(ray(22.0, 2.0, -5.0, 0.0, 0.0, 1.0), two, 0, eps).shouldBeNull()
        }

        "the slab test finds boxes in front, behind and beside the ray" {
            val min = doubleArrayOf(0.0, 0.0, 0.0)
            val max = doubleArrayOf(10.0, 10.0, 10.0)
            TrianglePicker
                .slab(
                    ray(5.0, 5.0, -20.0, 0.0, 0.0, 1.0),
                    min,
                    max,
                ).shouldNotBeNull()
                .shouldBe(20.0.plusOrMinus(1e-9))
            TrianglePicker.slab(ray(5.0, 5.0, 20.0, 0.0, 0.0, 1.0), min, max).shouldBeNull()
            TrianglePicker.slab(ray(50.0, 5.0, -20.0, 0.0, 0.0, 1.0), min, max).shouldBeNull()
            TrianglePicker.slab(ray(5.0, 5.0, 5.0, 0.0, 0.0, 1.0), min, max) shouldBe 0.0
        }
    })
