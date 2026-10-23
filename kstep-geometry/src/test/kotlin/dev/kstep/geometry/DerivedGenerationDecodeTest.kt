package dev.kstep.geometry

import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The derived generation payload is validated like every other native payload: hostile input never gets through. */
class DerivedGenerationDecodeTest :
    StringSpec({
        // 2 queries: #0 -> edges {3, 1}, faces {0}; #1 -> edges {}, faces {2, 4}. Result: 5 edges, 6 faces.
        val payload = intArrayOf(2, 2, 3, 1, 1, 0, 0, 2, 2, 4)

        "a well-formed payload decodes the edges and faces of every query" {
            val images = decodeDerivedGeneration(payload, 2, 5, 6)
            images.size shouldBe 2
            images[0].edges shouldBe listOf(3, 1)
            images[0].faces shouldBe listOf(0)
            images[1].edges shouldBe emptyList()
            images[1].faces shouldBe listOf(2, 4)
        }

        "an empty, truncated or over-long payload is rejected" {
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(IntArray(0), 2, 5, 6) }
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload.copyOf(payload.size - 1), 2, 5, 6) }
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload + 0, 2, 5, 6) }
        }

        "a wrong query count is rejected" {
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload, 3, 5, 6) }
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload, 1, 5, 6) }
        }

        "edge and face indices must lie inside the result, negatives are rejected" {
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload, 2, 3, 6) } // edge 3 of 3
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(payload, 2, 5, 4) } // face 4 of 4
            shouldThrow<IllegalStateException> {
                decodeDerivedGeneration(intArrayOf(1, 1, -1, 0), 1, 5, 6)
            }
            shouldThrow<IllegalStateException> {
                decodeDerivedGeneration(intArrayOf(1, 0, 1, -2), 1, 5, 6)
            }
        }

        "a count larger than the result, or a negative count, is rejected" {
            shouldThrow<IllegalStateException> {
                decodeDerivedGeneration(
                    intArrayOf(1, 6, 0, 0, 0, 0, 0, 0, 0),
                    1,
                    5,
                    6,
                )
            }
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(intArrayOf(1, -1, 0), 1, 5, 6) }
        }

        "a payload beyond the history limit is rejected before it is read" {
            val huge = IntArray(OcctKernel.MAX_HISTORY_INTS + 1)
            huge[0] = 1
            shouldThrow<IllegalStateException> { decodeDerivedGeneration(huge, 1, 5, 6) }.message!! shouldContain
                "exceeds"
        }

        "derived sources answer images() by role; a role the source does not have is an argument error" {
            val hole = HoleWall
            val section = BooleanSection
            val map =
                GenerationMap(
                    emptyMap(),
                    mapOf(
                        hole to
                            mapOf(
                                GeneratedRole.SECTION to listOf(1, 2),
                                GeneratedRole.RIM_ENTRY to listOf(1),
                                GeneratedRole.RIM_EXIT to emptyList(),
                            ),
                        section to mapOf(GeneratedRole.SECTION to listOf(4)),
                    ),
                )
            map.images(hole, GeneratedRole.RIM_ENTRY) shouldBe listOf(1)
            map.images(hole, GeneratedRole.RIM_EXIT) shouldBe emptyList()
            map.images(section, GeneratedRole.SECTION) shouldBe listOf(4)
            map.sources shouldBe setOf(hole, section)
            val e = shouldThrow<IllegalArgumentException> { map.images(section, GeneratedRole.BOUNDARY) }
            e.message!! shouldContain "SECTION"
            shouldThrow<IllegalArgumentException> { map.images(section, GeneratedRole.SIDE) }
            shouldThrow<IllegalArgumentException> { map.images(BlendedEdges, GeneratedRole.BOUNDARY) }
        }

        "a profile source asked for a derived role is an argument error, not a crash" {
            val v0 = ProfileVertex(0)
            val q = NamingQueries(listOf(v0), doubleArrayOf(0.0, 0.0), emptyList(), DoubleArray(0))
            val map = decodeGenerationMap(intArrayOf(1, 1, 3, 0), q, 5)
            shouldThrow<IllegalArgumentException> { map.images(v0, GeneratedRole.SECTION) }
                .message!! shouldContain "profile source"
        }

        "rekeying keeps derived images under the replacement key" {
            val map = GenerationMap(emptyMap(), mapOf(HoleWall to mapOf(GeneratedRole.SECTION to listOf(7))))
            val re = map.rekeyed(mapOf(HoleWall to BooleanSection))
            re.images(BooleanSection, GeneratedRole.SECTION) shouldBe listOf(7)
        }

        "a role error only fails that role, not the others, and survives rekeying" {
            val err =
                dev.kstep.geometry.select.EdgeNamingException(
                    "ambiguous",
                    null,
                    listOf(HoleWall),
                    emptyList(),
                    emptyMap(),
                    emptyList(),
                )
            val map =
                GenerationMap(
                    emptyMap(),
                    mapOf(
                        HoleWall to mapOf(GeneratedRole.SECTION to listOf(7), GeneratedRole.RIM_ENTRY to emptyList()),
                    ),
                    mapOf(HoleWall to mapOf(GeneratedRole.RIM_ENTRY to err)),
                )
            map.images(HoleWall, GeneratedRole.SECTION) shouldBe listOf(7)
            shouldThrow<dev.kstep.geometry.select.EdgeNamingException> { map.images(HoleWall, GeneratedRole.RIM_ENTRY) }
            val re = map.rekeyed(mapOf(HoleWall to BooleanSection))
            shouldThrow<dev.kstep.geometry.select.EdgeNamingException> {
                re.images(
                    BooleanSection,
                    GeneratedRole.RIM_ENTRY,
                )
            }
        }

        "derived sources have bounded, readable descriptions" {
            val long = OperandEdges(0, Edges.all())
            long.describe().length shouldBe long.describe().take(64).length
            OperandFaces(1, Faces.atMax(dev.kstep.geometry.select.Direction3.Z)).describe() shouldContain
                "OperandFaces(1"
            HoleWall.describe() shouldBe "HoleWall"
        }
    })
