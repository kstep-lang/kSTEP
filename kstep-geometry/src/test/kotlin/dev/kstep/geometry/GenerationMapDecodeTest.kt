package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class GenerationMapDecodeTest :
    StringSpec({
        val v0 = ProfileVertex(0)
        val v1 = ProfileVertex(1)
        val e0 = ProfileEdge(0)
        val queries = NamingQueries(listOf(v0, v1), doubleArrayOf(0.0, 0.0, 1.0, 0.0), listOf(e0), DoubleArray(6))

        // 2 vertices: v0 -> {3}, v1 -> {} ; 1 segment: start {0}, end {1, 2}; result has 5 edges
        val payload = intArrayOf(2, 1, 3, 0, 1, 1, 0, 2, 1, 2)

        "a well-formed payload decodes SIDE, START and END images" {
            val map = decodeGenerationMap(payload, queries, 5)
            map.images(v0, GeneratedRole.SIDE) shouldBe listOf(3)
            map.images(v1, GeneratedRole.SIDE) shouldBe emptyList()
            map.images(e0, GeneratedRole.START) shouldBe listOf(0)
            map.images(e0, GeneratedRole.END) shouldBe listOf(1, 2)
            map.sources shouldBe setOf(v0, v1, e0)
        }

        "a role that does not fit the source kind, or an unknown source, is an argument error" {
            val map = decodeGenerationMap(payload, queries, 5)
            shouldThrow<IllegalArgumentException> { map.images(v0, GeneratedRole.START) }
            shouldThrow<IllegalArgumentException> { map.images(e0, GeneratedRole.SIDE) }
            shouldThrow<IllegalArgumentException> { map.images(ProfileVertex(7), GeneratedRole.SIDE) }
        }

        "an empty, truncated or over-long payload is rejected" {
            shouldThrow<IllegalStateException> { decodeGenerationMap(IntArray(0), queries, 5) }
            shouldThrow<IllegalStateException> { decodeGenerationMap(payload.copyOf(payload.size - 1), queries, 5) }
            shouldThrow<IllegalStateException> { decodeGenerationMap(payload + 0, queries, 5) }
        }

        "an image index outside the result's edge range is rejected, also a negative one" {
            shouldThrow<IllegalStateException> { decodeGenerationMap(payload, queries, 3) }
            shouldThrow<IllegalStateException> {
                decodeGenerationMap(intArrayOf(2, 1, -1, 0, 1, 1, 0, 2, 1, 2), queries, 5)
            }
        }

        "counts larger than the result or different from the queries are rejected" {
            shouldThrow<IllegalStateException> { decodeGenerationMap(intArrayOf(1, 0), queries, 5) }
            shouldThrow<IllegalStateException> { decodeGenerationMap(intArrayOf(2, 9, 0), queries, 5) }
            shouldThrow<IllegalStateException> { decodeGenerationMap(intArrayOf(2, 0, 0, 2, 0, 0, 0, 0), queries, 5) }
        }

        "rekeyed maps keep the images under the replacement sources and reject incomplete mappings" {
            val map = decodeGenerationMap(payload, queries, 5)
            val a = PointAt(0.0, 0.0)
            val b = PointAt(1.0, 0.0)
            val c = SegmentAt(0.0, 0.0, 1.0, 0.0)
            val re = map.rekeyed(mapOf(v0 to a, v1 to b, e0 to c))
            re.images(a, GeneratedRole.SIDE) shouldBe listOf(3)
            re.images(c, GeneratedRole.END) shouldBe listOf(1, 2)
            shouldThrow<IllegalArgumentException> { map.rekeyed(mapOf(v0 to a)) }
            shouldThrow<IllegalArgumentException> { map.rekeyed(mapOf(v0 to a, v1 to a, e0 to c)) }
        }

        "the query builder rejects duplicates and an empty list" {
            val b = NamingQueryBuilder()
            shouldThrow<IllegalArgumentException> { b.build() }
            b.vertex(v0, 0.0, 0.0)
            shouldThrow<IllegalArgumentException> { b.vertex(v0, 1.0, 1.0) }
        }
    })
