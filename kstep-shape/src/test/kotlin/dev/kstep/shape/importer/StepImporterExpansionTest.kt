package dev.kstep.shape.importer

import dev.kstep.geometry.RgbColor
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

        val red = RgbColor(1.0, 0.0, 0.0)
        val blue = RgbColor(0.0, 0.0, 1.0)
        val green = RgbColor(0.0, 1.0, 0.0)

        fun expandColoured(
            graph: Graph,
            instance: Map<Int, RgbColor> = emptyMap(),
            part: Map<Int, RgbColor> = emptyMap(),
        ) = StepImporter.expand(
            graph.nodes,
            instanceColorOf = { instance[it.index] },
            partColorOf = { part[it] },
        ) { _, _ -> null }

        "the instance colour wins over the part colour (ADR-0045)" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            val c = g.use(a, p)
            expandColoured(g, mapOf(c to blue), mapOf(0 to red)).occurrences.single().color shouldBe blue
        }

        "without an instance colour the part colour applies" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            g.use(a, p)
            expandColoured(g, part = mapOf(0 to red)).occurrences.single().color shouldBe red
        }

        "only an instance colour is enough" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            val c = g.use(a, p)
            expandColoured(g, mapOf(c to blue)).occurrences.single().color shouldBe blue
        }

        "no colour at all yields null" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            g.use(a, p)
            expandColoured(g).occurrences.single().color shouldBe null
        }

        "two components of one part keep their own colours" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            val c1 = g.use(a, p)
            val c2 = g.use(a, p)
            val result = expandColoured(g, mapOf(c1 to red, c2 to blue), mapOf(0 to green))
            result.occurrences.map { it.color } shouldBe listOf(red, blue)
        }

        "the part colour beats an outer sub-assembly component colour, the own component colour beats both (ADR-0046)" {
            val g = Graph()
            val p = g.part()
            val inner = g.assembly("Inner")
            val outer = g.assembly("Outer", root = true)
            val cOuter = g.use(outer, inner)
            val cLeafColoured = g.use(inner, p)
            g.use(inner, p)
            val result = expandColoured(g, mapOf(cOuter to green, cLeafColoured to red), mapOf(0 to blue))
            result.occurrences.map { it.color } shouldBe listOf(red, blue)
        }

        "a colour on a sub-assembly component reaches every part below it that has no colour of its own (ADR-0046)" {
            val g = Graph()
            val p = g.part()
            val q = g.part()
            val inner = g.assembly("Inner")
            val outer = g.assembly("Outer", root = true)
            val cInner1 = g.use(outer, inner)
            g.use(outer, inner)
            g.use(inner, p)
            g.use(inner, q)
            // only the first of the two occurrences of Inner is coloured; one component object stands for one usage
            val result = expandColoured(g, mapOf(cInner1 to green))
            result.occurrences.map { it.color } shouldBe listOf(green, green, null, null)
        }

        "the inner component colour beats the outer one, hand calculation of the four occurrences (ADR-0046)" {
            val g = Graph()
            val box = g.part()
            val sub = g.assembly("Sub")
            val asm = g.assembly("Asm", root = true)
            val u2 = g.use(asm, sub)
            val u3 = g.use(sub, box)
            g.use(sub, box)
            g.use(asm, sub)
            // u2 is the first Asm component and colours the first Sub occurrence, u3 the first Box component of every Sub
            val expansion =
                StepImporter.expand(
                    g.nodes,
                    instanceColorOf = { mapOf(u2 to blue, u3 to red)[it.index] },
                ) { _, _ -> null }
            // (u2,u3) red, (u2,u4) blue, (second Sub,u3) red, (second Sub,u4) none; u3 belongs to both occurrences of Sub
            expansion.occurrences.map { it.color } shouldBe listOf(red, blue, red, null)
        }

        "a part colour between: own component, then part, then outer components innermost first (ADR-0046)" {
            val g = Graph()
            val box = g.part()
            val sub = g.assembly("Sub")
            val asm = g.assembly("Asm", root = true)
            val u2 = g.use(asm, sub)
            val u3 = g.use(sub, box)
            g.use(sub, box)
            val withPart =
                expandColoured(g, mapOf(u2 to blue, u3 to red), mapOf(0 to green))
            withPart.occurrences.map { it.color } shouldBe listOf(red, green)
        }

        "three levels: the nearest outer colour wins (ADR-0046)" {
            val g = Graph()
            val box = g.part()
            val sub = g.assembly("Sub")
            val mid = g.assembly("Mid")
            val asm = g.assembly("Asm", root = true)
            val cMid = g.use(asm, mid)
            val cSub = g.use(mid, sub)
            g.use(sub, box)
            expandColoured(g, mapOf(cMid to green, cSub to blue)).occurrences.single().color shouldBe blue
            expandColoured(g, mapOf(cMid to green)).occurrences.single().color shouldBe green
        }

        "the default call without colour lambdas leaves the colour null" {
            val g = Graph()
            val p = g.part()
            val a = g.assembly("A", root = true)
            g.use(a, p)
            expand(g).occurrences.single().color shouldBe null
        }
    })
