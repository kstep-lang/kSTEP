package dev.kstep.shape.importer

import dev.kstep.geometry.StepImportLimits
import dev.kstep.geometry.XcafNode
import dev.kstep.geometry.XcafNodeKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The Kotlin-side expansion guards of [StepImporter.expand] (depth, occurrence cap, non-rigid placements),
 * driven by hand-built [XcafNode] lists so no OCCT is needed: these run in every CI job.
 */
class StepImporterExpansionTest :
    StringSpec({
        val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)

        /** Incremental builder; node indices are positions in the list. */
        class Graph {
            val nodes = mutableListOf<XcafNode>()

            fun part(name: String = "P"): Int = add(XcafNodeKind.PART, name, null, null, root = false, hasShape = true)

            fun assembly(
                name: String,
                root: Boolean = false,
            ): Int = add(XcafNodeKind.ASSEMBLY, name, null, null, root, hasShape = false)

            fun use(
                parent: Int,
                child: Int,
                rigid: Boolean = true,
                matrix: DoubleArray = identity,
            ): Int = add(XcafNodeKind.COMPONENT, "c${nodes.size}", parent, child, false, false, rigid, matrix)

            private fun add(
                kind: XcafNodeKind,
                name: String,
                parent: Int?,
                referred: Int?,
                root: Boolean,
                hasShape: Boolean,
                rigid: Boolean = true,
                matrix: DoubleArray = identity,
            ): Int {
                val index = nodes.size
                nodes.add(XcafNode(index, kind, name, parent, referred, root, rigid, matrix, hasShape))
                return index
            }
        }

        fun expand(graph: Graph) = StepImporter.expand(graph.nodes) { _, _ -> null }

        /** A chain of [levels] assemblies, the last one holding one part: the part sits at depth [levels]. */
        fun chain(levels: Int): Graph {
            val g = Graph()
            val part = g.part()
            val assemblies = (0 until levels).map { g.assembly("A$it", root = it == 0) }
            for (i in 0 until levels - 1) g.use(assemblies[i], assemblies[i + 1])
            g.use(assemblies.last(), part)
            return g
        }

        "a part at exactly the maximum depth still expands" {
            val result = expand(chain(StepImportLimits.MAX_ASSEMBLY_DEPTH))
            result.occurrences.size shouldBe 1
            result.occurrences[0].path.size shouldBe StepImportLimits.MAX_ASSEMBLY_DEPTH
        }

        "a part one level deeper than the maximum is LimitExceeded" {
            val e =
                shouldThrow<StepImporter.LimitExceeded> { expand(chain(StepImportLimits.MAX_ASSEMBLY_DEPTH + 1)) }
            e.message shouldContain "${StepImportLimits.MAX_ASSEMBLY_DEPTH} levels"
        }

        "a self-referencing assembly is LimitExceeded and terminates" {
            val g = Graph()
            val a = g.assembly("Loop", root = true)
            g.use(a, a)
            g.use(a, a)
            shouldThrow<StepImporter.LimitExceeded> { expand(g) }
        }

        "a two-assembly cycle is LimitExceeded" {
            val g = Graph()
            val a = g.assembly("A", root = true)
            val b = g.assembly("B")
            g.use(a, b)
            g.use(b, a)
            shouldThrow<StepImporter.LimitExceeded> { expand(g) }
        }

        "a DAG expanding to more part occurrences than the cap is LimitExceeded" {
            // 14 layers, each assembly uses the next layer twice: 2^14 = 16384 > 10 000 occurrences.
            val g = Graph()
            val part = g.part()
            val layers = (0..14).map { g.assembly("L$it", root = it == 0) }
            for (i in 0 until 14) {
                g.use(layers[i], layers[i + 1])
                g.use(layers[i], layers[i + 1])
            }
            g.use(layers[14], part)
            val e = shouldThrow<StepImporter.LimitExceeded> { expand(g) }
            e.message shouldContain "${StepImportLimits.MAX_EXPANDED_OCCURRENCES} part occurrences"
        }

        "exactly the maximum number of occurrences is accepted, one more is not" {
            fun flat(count: Int): Graph {
                val g = Graph()
                val part = g.part()
                val asm = g.assembly("Flat", root = true)
                repeat(count) { g.use(asm, part) }
                return g
            }
            StepImporter.expand(flat(5).nodes, maxOccurrences = 5) { _, _ -> null }.occurrences.size shouldBe 5
            shouldThrow<StepImporter.LimitExceeded> {
                StepImporter.expand(flat(6).nodes, maxOccurrences = 5) { _, _ -> null }
            }
        }

        "a non-rigid component yields a non-rigid, unplaced occurrence and sets nonRigidSeen" {
            val g = Graph()
            val part = g.part()
            val asm = g.assembly("Top", root = true)
            val scaled = identity.copyOf().also { it[0] = 2.0 }
            g.use(asm, part, rigid = false, matrix = scaled)
            g.use(asm, part)
            val result = expand(g)
            result.nonRigidSeen shouldBe true
            result.occurrences.size shouldBe 2
            result.occurrences[0].global.rigid shouldBe false
            result.occurrences[0].global.placement shouldBe null
            result.occurrences[1].global.rigid shouldBe true
        }

        "a rigid-flagged component with a non-orthonormal matrix is also treated as non-rigid" {
            val g = Graph()
            val part = g.part()
            val asm = g.assembly("Top", root = true)
            val bad = identity.copyOf().also { it[0] = 3.0 }
            g.use(asm, part, rigid = true, matrix = bad)
            val result = expand(g)
            result.nonRigidSeen shouldBe true
            result.occurrences
                .single()
                .global.placement shouldBe null
        }

        "a non-rigid link anywhere in the chain makes the whole occurrence non-rigid" {
            val g = Graph()
            val part = g.part()
            val outer = g.assembly("Outer", root = true)
            val inner = g.assembly("Inner")
            g.use(outer, inner, rigid = false)
            g.use(inner, part)
            val result = expand(g)
            result.occurrences
                .single()
                .global.rigid shouldBe false
            result.occurrences
                .single()
                .path.size shouldBe 2
        }

        "the total volume is the sum over rigid occurrences and null as soon as one is not placed" {
            val parts = listOf(ImportedPart(0, "Block", null, null, 1000.0, null, null))
            val matrix = identity
            val rigid = ImportedPlacement(dev.kstep.geometry.Placement.IDENTITY, matrix, true)
            val loose = ImportedPlacement(null, matrix, false)

            fun occ(global: ImportedPlacement) = ImportedOccurrence(emptyList(), 0, global, null)
            StepImporter.totalVolume(listOf(occ(rigid), occ(rigid)), parts) shouldBe 2000.0
            StepImporter.totalVolume(listOf(occ(rigid), occ(loose)), parts) shouldBe null
            StepImporter.totalVolume(emptyList(), parts) shouldBe null
            StepImporter.totalVolume(
                listOf(occ(rigid)),
                listOf(ImportedPart(0, "Hollow", null, null, null, null, null)),
            ) shouldBe null
        }
    })
