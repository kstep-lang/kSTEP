package dev.kstep.preview

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** The assembly hierarchy of the desktop worker (ADR-0054), built from artificial instance chains, no OCCT needed. */
class AssemblyTreeBuilderTest :
    StringSpec({
        class Usage(
            val label: String,
        )

        fun step(
            usage: Usage,
            name: String,
        ) = ChainStep(usage, name)

        fun chain(
            root: Any,
            rootName: String,
            steps: List<ChainStep>,
            leafName: String,
            span: Int,
        ) = InstanceChain(root, rootName, steps, leafName, span)

        /** "name:parent:span" per node. */
        fun RawAssemblyTree.dump() = (0 until size).map { "${names[it]}:${parents[it]}:${span[it]}" }

        fun RenderedAssemblyTree.dump() = nodes.map { "${it.name}:${it.parent}:${it.part}" }

        val root = Any()

        "no instances give no tree" {
            buildAssemblyTree(emptyList()).shouldBeNull()
        }

        "a shaped root is one leaf" {
            val tree = buildAssemblyTree(listOf(chain(root, "Solo", emptyList(), "Solo", 0)))!!
            tree.dump() shouldBe listOf("Solo:-1:0")
        }

        "one level: the root with two parts" {
            val a = Usage("a")
            val b = Usage("b")
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "Asm", listOf(step(a, "Bolt")), "Bolt", 0),
                        chain(root, "Asm", listOf(step(b, "Nut")), "Nut", 1),
                    ),
                )!!
            tree.dump() shouldBe listOf("Asm:-1:-1", "Bolt:0:0", "Nut:0:1")
        }

        "nesting: a sub-assembly becomes one group with its parts" {
            val sub = Usage("sub")
            val x = Usage("x")
            val y = Usage("y")
            val z = Usage("z")
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "Top", listOf(step(sub, "Sub"), step(x, "X")), "X", 0),
                        chain(root, "Top", listOf(step(sub, "Sub"), step(y, "Y")), "Y", 1),
                        chain(root, "Top", listOf(step(z, "Z")), "Z", 2),
                    ),
                )!!
            tree.dump() shouldBe listOf("Top:-1:-1", "Sub:0:-1", "X:1:0", "Y:1:1", "Z:0:2")
        }

        "a repeated sub-assembly gives two groups, not one merged group" {
            // the same child usage objects hang under both occurrences of Sub (u1, u2 are the two parent usages)
            val u1 = Usage("u1")
            val u2 = Usage("u2")
            val x = Usage("x")
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "Top", listOf(step(u1, "Sub"), step(x, "X")), "X", 0),
                        chain(root, "Top", listOf(step(u2, "Sub"), step(x, "X")), "X", 1),
                    ),
                )!!
            tree.dump() shouldBe listOf("Top:-1:-1", "Sub:0:-1", "X:1:0", "Sub:0:-1", "X:3:1")
        }

        "a shaped intermediate assembly becomes a group whose first child is its own geometry" {
            val frame = Usage("frame")
            val screw = Usage("screw")
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "Top", listOf(step(frame, "Frame")), "Frame", 0),
                        chain(root, "Top", listOf(step(frame, "Frame"), step(screw, "Screw")), "Screw", 1),
                    ),
                )!!
            tree.dump() shouldBe listOf("Top:-1:-1", "Frame:0:-1", "Frame:1:0", "Screw:1:1")
        }

        "two roots get a synthetic Assembly root" {
            val r2 = Any()
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "A", emptyList(), "A", 0),
                        chain(r2, "B", emptyList(), "B", 1),
                    ),
                )!!
            tree.dump() shouldBe listOf("Assembly:-1:-1", "A:0:0", "B:0:1")
        }

        "re-indexing renumbers the parts and prunes lost leaves and empty groups" {
            val sub = Usage("sub")
            val x = Usage("x")
            val y = Usage("y")
            val z = Usage("z")
            val raw =
                buildAssemblyTree(
                    listOf(
                        chain(root, "Top", listOf(step(sub, "Sub"), step(x, "X")), "X", 0),
                        chain(root, "Top", listOf(step(sub, "Sub"), step(y, "Y")), "Y", 1),
                        chain(root, "Top", listOf(step(z, "Z")), "Z", 2),
                    ),
                )!!
            // span 0 and 1 vanished (all triangles dropped): Sub is empty and goes, Z becomes part 0
            raw.reindexed(intArrayOf(-1, -1, 0))!!.dump() shouldBe listOf("Top:-1:-1", "Z:0:0")
            // only span 1 vanished
            raw.reindexed(intArrayOf(0, -1, 1))!!.dump() shouldBe listOf("Top:-1:-1", "Sub:0:-1", "X:1:0", "Z:0:1")
            // everything vanished
            raw.reindexed(intArrayOf(-1, -1, -1)).shouldBeNull()
        }

        "the span indices of the tree stay in pre-order" {
            val a = Usage("a")
            val b = Usage("b")
            val c = Usage("c")
            val tree =
                buildAssemblyTree(
                    listOf(
                        chain(root, "T", listOf(step(a, "G"), step(b, "P")), "P", 0),
                        chain(root, "T", listOf(step(a, "G"), step(c, "Q")), "Q", 1),
                    ),
                )!!
            val spans = (0 until tree.size).map { tree.span[it] }.filter { it >= 0 }
            spans shouldBe listOf(0, 1)
        }

        "depth 64 stays within the limit, a chain one deeper does not parse as shallow" {
            fun deep(levels: Int): RawAssemblyTree {
                val steps = (1..levels).map { step(Usage("u$it"), "n$it") }
                return buildAssemblyTree(listOf(chain(root, "Root", steps, "n$levels", 0)))!!
            }
            val tree64 = deep(64)
            tree64.size shouldBe 65
            var depth = 0
            var cur = tree64.size - 1
            while (tree64.parents[cur] >= 0) {
                cur = tree64.parents[cur]
                depth++
            }
            depth shouldBe 64
        }

        "mapSpansToGlbIndexed reports the new part index per span" {
            fun span(
                first: Int,
                count: Int,
            ) = PartSpan("p", first, count, dev.kstep.geometry.Placement.IDENTITY, dev.kstep.geometry.MeshColor.NEUTRAL)
            val spans = listOf(span(0, 2), span(2, 2), span(4, 1))
            val (parts, partOfSpan) = mapSpansToGlbIndexed(spans, intArrayOf(0, 1, 4))
            parts.size shouldBe 2
            partOfSpan.toList() shouldBe listOf(0, -1, 1)
        }
    })
