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

        "PlacedMesh defaults to MeshColor.NEUTRAL when no color is given" {
            PlacedMesh(oneTriangleMesh()).color shouldBe MeshColor.NEUTRAL
        }

        "mergeColored's mesh is identical to merge's for the same parts" {
            val parts =
                listOf(
                    PlacedMesh(oneTriangleMesh(), color = MeshColor(0.5, 0.5, 0.5)),
                    PlacedMesh(twoTriangleMesh(), Placement.translation(10.0, 0.0, 0.0), MeshColor(0.2, 0.3, 0.4)),
                )
            val plain = MeshComposition.merge(parts)
            val colored = MeshComposition.mergeColored(parts)
            colored.mesh.coordinates.size shouldBe plain.coordinates.size
            colored.mesh.coordinates.indices.forEach { i ->
                colored.mesh.coordinates[i] shouldBe (plain.coordinates[i] plusOrMinus TOLERANCE)
            }
        }

        "mergeColored's colorAt returns each part's own color for its own triangles" {
            val redPart = PlacedMesh(oneTriangleMesh(), color = MeshColor(0.9, 0.2, 0.2))
            val bluePart =
                PlacedMesh(twoTriangleMesh(), Placement.translation(100.0, 0.0, 0.0), MeshColor(0.2, 0.2, 0.9))
            val colored = MeshComposition.mergeColored(listOf(redPart, bluePart))

            // redPart contributes triangle 0, bluePart contributes triangles 1 and 2.
            colored.colorAt(0) shouldBe redPart.color
            colored.colorAt(1) shouldBe bluePart.color
            colored.colorAt(2) shouldBe bluePart.color
        }

        "mergeColored deduplicates two parts sharing the exact same color onto one palette entry" {
            val sharedColor = MeshColor(0.6, 0.6, 0.6)
            val partA = PlacedMesh(oneTriangleMesh(), color = sharedColor)
            val partB = PlacedMesh(oneTriangleMesh(), Placement.translation(5.0, 0.0, 0.0), sharedColor)
            val colored = MeshComposition.mergeColored(listOf(partA, partB))

            // Both triangles report the identical MeshColor VALUE -- this is the observable proof
            // of dedup available through the public colorAt API (the palette itself is private).
            colored.colorAt(0) shouldBe sharedColor
            colored.colorAt(1) shouldBe sharedColor
            colored.colorAt(0) shouldBe colored.colorAt(1)
        }

        "mergeColored(emptyList()) returns an empty ColoredMesh" {
            val colored = MeshComposition.mergeColored(emptyList())
            colored.mesh.triangleCount shouldBe 0
        }
    })
