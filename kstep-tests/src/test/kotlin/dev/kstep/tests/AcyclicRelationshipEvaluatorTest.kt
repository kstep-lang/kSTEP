package dev.kstep.tests

import dev.kstep.express.validation.AcyclicEvaluationLimitExceededException
import dev.kstep.express.validation.AcyclicRelationshipEvaluator
import dev.kstep.express.validation.AcyclicRelationshipEvaluator.Limits
import dev.kstep.express.validation.AcyclicRelationshipEvaluator.Outcome
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.IdentityHashMap

/** A node of a synthetic assembly graph; identity-compared like the real instances. */
private class AsmNode(
    val name: String,
) {
    override fun toString() = name
}

/** `child -> parents`, i.e. for every relation `r`, `r.related = child` and `r.relating = parent`. */
private class AsmGraph {
    val parents = IdentityHashMap<AsmNode, MutableList<AsmNode>>()

    fun edge(
        child: AsmNode,
        parent: AsmNode,
    ) = apply { parents.getOrPut(child) { mutableListOf() }.add(parent) }

    fun walk(
        relating: AsmNode,
        related: AsmNode,
        limits: Limits = Limits(),
        complete: (Any) -> Boolean = { true },
    ): Outcome =
        AcyclicRelationshipEvaluator.walk(
            relating,
            related,
            { node -> parents[node as AsmNode].orEmpty() },
            complete,
            limits,
        )
}

private fun Outcome.pathNames(): List<String> = (this as Outcome.Cycle).path.map { it.toString() }

class AcyclicRelationshipEvaluatorTest :
    StringSpec({
        val a = AsmNode("A")
        val b = AsmNode("B")
        val c = AsmNode("C")
        val p1 = AsmNode("P1")
        val p2 = AsmNode("P2")
        val root = AsmNode("R")

        "a tree above the relating end that never reaches the related end is acyclic" {
            val g = AsmGraph().edge(a, p1).edge(p1, p2)
            g.walk(relating = a, related = b) shouldBe Outcome.Acyclic
        }

        "a relation without any other usage is acyclic" {
            AsmGraph().walk(relating = a, related = b) shouldBe Outcome.Acyclic
        }

        "a diamond (shared ancestor reached twice) is a DAG, not a cycle, and is explored once" {
            val g =
                AsmGraph()
                    .edge(a, p1)
                    .edge(a, p2)
                    .edge(p1, root)
                    .edge(p2, root)
            g.walk(relating = a, related = b) shouldBe Outcome.Acyclic
        }

        "a relation of a definition with itself is a cycle of length one" {
            AsmGraph().walk(relating = a, related = a).pathNames() shouldContainExactly listOf("A", "A")
        }

        "reaching the related end upwards closes a two-node cycle" {
            // existing: A is used in B (B is parent of A) -- new relation: related = B uses relating = A
            val g = AsmGraph().edge(a, b)
            g.walk(relating = a, related = b).pathNames() shouldContainExactly listOf("A", "B")
        }

        "reaching the related end through an intermediate closes a three-node cycle" {
            val g = AsmGraph().edge(a, p1).edge(p1, b)
            g.walk(relating = a, related = b).pathNames() shouldContainExactly listOf("A", "P1", "B")
        }

        "a cycle above the relating end is found even though the related end is not on it" {
            // A -> P1 -> P2 -> P1: the ancestors loop; EXPRESS walks the whole ancestor closure.
            val g = AsmGraph().edge(a, p1).edge(p1, p2).edge(p2, p1)
            val cycle = g.walk(relating = a, related = b)
            cycle.shouldBeInstanceOf<Outcome.Cycle>()
            cycle.pathNames() shouldContainExactly listOf("P1", "P2", "P1")
        }

        "a finished node is never explored a second time (black nodes are skipped)" {
            val g =
                AsmGraph()
                    .edge(a, p1)
                    .edge(a, p2)
                    .edge(p1, c)
                    .edge(p2, c)
            var calls = 0
            val outcome =
                AcyclicRelationshipEvaluator.walk(a, b, { node ->
                    if (node === c) calls++
                    g.parents[node as AsmNode].orEmpty()
                })
            outcome shouldBe Outcome.Acyclic
            calls shouldBe 1
        }

        "a pathological chain of 10000 definitions is walked iteratively without a stack overflow" {
            val nodes = (0..10_000).map { AsmNode("n$it") }
            val g = AsmGraph()
            for (i in 0 until nodes.size - 1) g.edge(nodes[i], nodes[i + 1])
            g.walk(nodes.first(), b, Limits(maxDepth = 20_000)) shouldBe Outcome.Acyclic
            // ...and a cycle at its far end is still found
            g.edge(nodes.last(), nodes.first())
            g.walk(nodes.first(), b, Limits(maxDepth = 20_000)).shouldBeInstanceOf<Outcome.Cycle>()
        }

        "the depth limit is exact: maxDepth nodes pass, one more is a structured limit exception" {
            val chain = (1..6).map { AsmNode("c$it") }
            val g = AsmGraph()
            for (i in 0 until chain.size - 1) g.edge(chain[i], chain[i + 1])
            // walking up from chain[1] visits 5 nodes (c2..c6); from chain[0] six
            g.walk(chain[1], b, Limits(maxDepth = 5)) shouldBe Outcome.Acyclic
            shouldThrow<AcyclicEvaluationLimitExceededException> { g.walk(chain[0], b, Limits(maxDepth = 5)) }
                .message!! shouldContain "depth"
        }

        "the visited-node limit throws instead of returning a verdict" {
            val g = AsmGraph()
            val wide = (1..50).map { AsmNode("w$it") }
            wide.forEach { g.edge(a, it) }
            shouldThrow<AcyclicEvaluationLimitExceededException> { g.walk(a, b, Limits(maxVisited = 10)) }
                .message!! shouldContain "visited"
        }

        "walks sharing a budget are bounded together, not only one by one" {
            val g = AsmGraph().edge(a, p1).edge(p1, p2)
            val budget = AcyclicRelationshipEvaluator.Budget(maxTotalVisited = 7)

            fun run() =
                AcyclicRelationshipEvaluator.walk(a, b, { n -> g.parents[n as AsmNode].orEmpty() }, budget = budget)
            run() shouldBe Outcome.Acyclic // 3 nodes
            run() shouldBe Outcome.Acyclic // 6 in total
            shouldThrow<AcyclicEvaluationLimitExceededException> { run() }.message!! shouldContain "in total"
        }

        "a visited node with invisible referrers makes an otherwise acyclic walk indeterminate, never acyclic" {
            val g = AsmGraph().edge(a, p1)
            val outcome = g.walk(a, b, complete = { it !== p1 })
            outcome.shouldBeInstanceOf<Outcome.Indeterminate>()
        }

        "a cycle is still reported when the population is also incomplete" {
            val g = AsmGraph().edge(a, p1).edge(p1, b)
            g.walk(a, b, complete = { false }).shouldBeInstanceOf<Outcome.Cycle>()
        }
    })
