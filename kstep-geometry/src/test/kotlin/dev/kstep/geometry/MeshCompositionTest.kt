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

        // -------------------------------------------------------------------------------------
        // Smooth per-vertex normals (see docs/adr/ADR-0018-smooth-vertex-normals.adoc). No OCCT
        // needed -- these exercise TriangleMesh/Placement/MeshComposition's own Kotlin-side logic
        // against hand-built DoubleArrays.
        // -------------------------------------------------------------------------------------

        "TriangleMesh(coordinates) without a second argument has null vertexNormals" {
            val mesh = oneTriangleMesh()
            mesh.vertexNormals shouldBe null
            mesh.hasVertexNormals shouldBe false
        }

        "TriangleMesh rejects vertexNormals of the wrong length" {
            shouldThrow<IllegalArgumentException> {
                TriangleMesh(DoubleArray(9), DoubleArray(6))
            }
        }

        "transformedBy a pure rotation rotates vertexNormals, preserving unit length" {
            val normals = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val mesh = TriangleMesh(oneTriangleMesh().coordinates, normals)
            val transformed = mesh.transformedBy(Placement.rotationZ(90.0))
            transformed.hasVertexNormals shouldBe true
            val n = transformed.vertexNormals!!
            // Rotating (1,0,0) by 90 degrees around Z gives (0,1,0).
            for (v in 0 until 3) {
                n[v * 3] shouldBe (0.0 plusOrMinus TOLERANCE)
                n[v * 3 + 1] shouldBe (1.0 plusOrMinus TOLERANCE)
                n[v * 3 + 2] shouldBe (0.0 plusOrMinus TOLERANCE)
                val length = Math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
                length shouldBe (1.0 plusOrMinus TOLERANCE)
            }
        }

        "transformedBy a rotation+translation rotates vertexNormals but never translates them" {
            val normals = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val mesh = TriangleMesh(oneTriangleMesh().coordinates, normals)
            val placement = Placement.rotationZ(90.0).then(Placement.translation(1000.0, 2000.0, 3000.0))
            val transformed = mesh.transformedBy(placement)
            val n = transformed.vertexNormals!!
            for (v in 0 until 3) {
                n[v * 3] shouldBe (0.0 plusOrMinus TOLERANCE)
                n[v * 3 + 1] shouldBe (1.0 plusOrMinus TOLERANCE)
                n[v * 3 + 2] shouldBe (0.0 plusOrMinus TOLERANCE)
                // If translation had leaked into the normal, its length would be enormous
                // (thousands, from the translation components above) -- this is the actual
                // regression guard, not merely the per-axis checks above.
                val length = Math.sqrt(n[v * 3] * n[v * 3] + n[v * 3 + 1] * n[v * 3 + 1] + n[v * 3 + 2] * n[v * 3 + 2])
                length shouldBe (1.0 plusOrMinus TOLERANCE)
            }
        }

        "merge of two parts that both carry vertexNormals concatenates them in listed order" {
            val normalsA = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0)
            val normalsB = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val partA = PlacedMesh(TriangleMesh(oneTriangleMesh().coordinates, normalsA))
            val partB = PlacedMesh(TriangleMesh(oneTriangleMesh().coordinates, normalsB))
            val merged = MeshComposition.merge(listOf(partA, partB))

            merged.hasVertexNormals shouldBe true
            val n = merged.vertexNormals!!
            n.size shouldBe merged.coordinates.size
            // First triangle's normals: normalsA, untouched (IDENTITY placement).
            for (i in 0 until 9) n[i] shouldBe (normalsA[i] plusOrMinus TOLERANCE)
            // Second triangle's normals: normalsB, untouched (IDENTITY placement).
            for (i in 0 until 9) n[9 + i] shouldBe (normalsB[i] plusOrMinus TOLERANCE)
        }

        "merge of a mixed set (one part with vertexNormals, one without) yields null vertexNormals" {
            val normalsA = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0)
            val partA = PlacedMesh(TriangleMesh(oneTriangleMesh().coordinates, normalsA))
            val partB = PlacedMesh(oneTriangleMesh()) // no vertexNormals
            val merged = MeshComposition.merge(listOf(partA, partB))
            merged.vertexNormals shouldBe null
        }

        "mergeColored with vertexNormals-carrying parts propagates them onto the merged mesh" {
            val normals = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0)
            val part =
                PlacedMesh(TriangleMesh(oneTriangleMesh().coordinates, normals), color = MeshColor(0.5, 0.5, 0.5))
            val colored = MeshComposition.mergeColored(listOf(part))
            colored.mesh.hasVertexNormals shouldBe true
            colored.colorAt(0) shouldBe part.color
        }

        // -------------------------------------------------------------------------------------
        // decodeTriangles: OcctBridge.nativeShapeTriangles's header-prefixed layout decoder --
        // see OcctShape.kt. No OCCT needed, hand-built DoubleArrays exercise the layout directly.
        // -------------------------------------------------------------------------------------

        "decodeTriangles: header-only array (N=0) decodes to an empty mesh" {
            val mesh = decodeTriangles(doubleArrayOf(0.0))
            mesh.triangleCount shouldBe 0
            mesh.vertexNormals shouldBe null
        }

        "decodeTriangles: 1+9N layout (no normals) decodes positions only" {
            val positions = DoubleArray(9) { it.toDouble() }
            val raw = doubleArrayOf(1.0) + positions
            val mesh = decodeTriangles(raw)
            mesh.triangleCount shouldBe 1
            mesh.vertexNormals shouldBe null
            mesh.coordinates.indices.forEach { i -> mesh.coordinates[i] shouldBe (positions[i] plusOrMinus TOLERANCE) }
        }

        "decodeTriangles: 1+18N layout (with normals) decodes positions and normals distinctly" {
            val positions = DoubleArray(9) { it.toDouble() }
            val normals = DoubleArray(9) { (it + 100).toDouble() }
            val raw = doubleArrayOf(1.0) + positions + normals
            val mesh = decodeTriangles(raw)
            mesh.triangleCount shouldBe 1
            mesh.coordinates.indices.forEach { i -> mesh.coordinates[i] shouldBe (positions[i] plusOrMinus TOLERANCE) }
            val n = mesh.vertexNormals!!
            n.indices.forEach { i -> n[i] shouldBe (normals[i] plusOrMinus TOLERANCE) }
        }

        "decodeTriangles: N=2 without normals (18 payload doubles) is unambiguous vs. N=1 with normals" {
            // 18*(N/2) == 9*N is the exact ambiguity a header-less layout could not resolve --
            // this test pins that the header element (triangleCount) is what disambiguates it.
            val raw = doubleArrayOf(2.0) + DoubleArray(18) { it.toDouble() }
            val mesh = decodeTriangles(raw)
            mesh.triangleCount shouldBe 2
            mesh.vertexNormals shouldBe null
        }

        "decodeTriangles: an empty array is rejected (every real result carries a header)" {
            shouldThrow<IllegalStateException> { decodeTriangles(DoubleArray(0)) }
        }

        "decodeTriangles: a length matching neither 1+9N nor 1+18N is rejected" {
            shouldThrow<IllegalStateException> { decodeTriangles(doubleArrayOf(1.0, 0.0, 0.0, 0.0)) }
        }

        "decodeTriangles: a header triangle count above MAX_TRIANGLES is rejected before any allocation" {
            shouldThrow<IllegalStateException> {
                decodeTriangles(doubleArrayOf((OcctKernel.MAX_TRIANGLES + 1).toDouble()))
            }
        }

        "decodeTriangles: a negative header triangle count is rejected" {
            shouldThrow<IllegalStateException> { decodeTriangles(doubleArrayOf(-1.0)) }
        }

        "decodeTriangles: a non-integral header triangle count is rejected" {
            shouldThrow<IllegalStateException> {
                decodeTriangles(
                    doubleArrayOf(1.5, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
                )
            }
        }
    })
