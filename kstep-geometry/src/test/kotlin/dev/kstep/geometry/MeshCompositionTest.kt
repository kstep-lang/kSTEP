package dev.kstep.geometry

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

private const val TOLERANCE = 1e-9

/** A single, arbitrary, non-degenerate triangle -- deliberately not axis-aligned, so a
 *  transform bug (a swapped rotation axis, a sign error) is unlikely to accidentally cancel
 *  out and pass anyway. */
private fun oneTriangleMesh(): TriangleMesh = TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0))

private fun twoTriangleMesh(): TriangleMesh =
    TriangleMesh(
        doubleArrayOf(
            0.0,
            0.0,
            0.0,
            1.0,
            0.0,
            0.0,
            0.0,
            1.0,
            0.0,
            2.0,
            0.0,
            0.0,
            3.0,
            0.0,
            0.0,
            2.0,
            1.0,
            0.0,
        ),
    )

class MeshCompositionTest :
    StringSpec({
        "transformedBy(IDENTITY) is a coordinate no-op" {
            val mesh = twoTriangleMesh()
            val transformed = mesh.transformedBy(Placement.IDENTITY)
            transformed.coordinates.size shouldBe mesh.coordinates.size
            transformed.coordinates.indices.forEach { i ->
                transformed.coordinates[i] shouldBe (mesh.coordinates[i] plusOrMinus TOLERANCE)
            }
        }

        "transformedBy translates every vertex by the same offset" {
            val mesh = oneTriangleMesh()
            val transformed = mesh.transformedBy(Placement.translation(10.0, 20.0, 30.0))
            val expected = doubleArrayOf(10.0, 20.0, 30.0, 11.0, 20.0, 30.0, 10.0, 21.0, 30.0)
            transformed.coordinates.indices.forEach { i ->
                transformed.coordinates[i] shouldBe (expected[i] plusOrMinus TOLERANCE)
            }
        }

        "merge(emptyList()) returns an empty mesh" {
            val merged = MeshComposition.merge(emptyList())
            merged.triangleCount shouldBe 0
            merged.coordinates.size shouldBe 0
        }

        "merge of a single part at IDENTITY is a coordinate no-op" {
            val mesh = twoTriangleMesh()
            val merged = MeshComposition.merge(listOf(PlacedMesh(mesh)))
            merged.coordinates.size shouldBe mesh.coordinates.size
            merged.coordinates.indices.forEach { i ->
                merged.coordinates[i] shouldBe (mesh.coordinates[i] plusOrMinus TOLERANCE)
            }
        }

        "merge's triangle count is the sum of every part's triangle count" {
            val merged =
                MeshComposition.merge(
                    listOf(
                        PlacedMesh(oneTriangleMesh()),
                        PlacedMesh(twoTriangleMesh(), Placement.translation(100.0, 0.0, 0.0)),
                        PlacedMesh(oneTriangleMesh(), Placement.rotationZ(45.0)),
                    ),
                )
            merged.triangleCount shouldBe 4
        }

        "merge places each part's coordinates transformed, in listed order" {
            val partA = oneTriangleMesh()
            val partB = oneTriangleMesh()
            val placementB = Placement.translation(5.0, 0.0, 0.0)
            val merged = MeshComposition.merge(listOf(PlacedMesh(partA), PlacedMesh(partB, placementB)))

            // First 9 coordinates: partA untouched (IDENTITY).
            partA.coordinates.indices.forEach { i ->
                merged.coordinates[i] shouldBe (partA.coordinates[i] plusOrMinus TOLERANCE)
            }
            // Next 9 coordinates: partB shifted by +5 on X, unchanged on Y/Z.
            val expectedB = partB.transformedBy(placementB)
            expectedB.coordinates.indices.forEach { i ->
                merged.coordinates[9 + i] shouldBe (expectedB.coordinates[i] plusOrMinus TOLERANCE)
            }
        }

        "merge rejects a triangle count above MAX_TRIANGLES before allocating" {
            // Fabricates a single mesh whose OWN triangle count already exceeds the guard, so
            // this test does not need to actually allocate OcctKernel.MAX_TRIANGLES-many
            // triangles worth of parts to exercise the check.
            val hugeMesh = TriangleMesh(DoubleArray((OcctKernel.MAX_TRIANGLES + 1) * 9))
            shouldThrow<IllegalArgumentException> {
                MeshComposition.merge(listOf(PlacedMesh(hugeMesh)))
            }
        }
    })
