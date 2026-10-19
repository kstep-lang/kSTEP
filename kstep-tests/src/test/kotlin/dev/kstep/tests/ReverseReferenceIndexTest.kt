package dev.kstep.tests

import dev.kstep.express.validation.InstanceDescriber
import dev.kstep.express.validation.InstanceDescription
import dev.kstep.express.validation.OutgoingReference
import dev.kstep.express.validation.ReverseReferenceIndex
import dev.kstep.express.validation.ReverseReferenceIndexLimitExceededException
import dev.kstep.express.validation.UnknownInstanceTypeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private class Node(
    val label: String,
    val next: MutableList<Node> = mutableListOf(),
)

/** Structurally equal instances must still be distinct entities. */
private data class Leaf(
    val value: String,
)

private class Holder(
    val leaf: Leaf,
)

private val describer =
    InstanceDescriber { instance ->
        when (instance) {
            is Node ->
                InstanceDescription(
                    "node",
                    emptyMap(),
                    listOf(OutgoingReference("node", "next", instance.next.toList())),
                )
            is Holder ->
                InstanceDescription(
                    "holder",
                    emptyMap(),
                    listOf(OutgoingReference("holder", "leaf", listOf(instance.leaf))),
                )
            is Leaf -> InstanceDescription("leaf", emptyMap(), emptyList())
            else -> null
        }
    }

private fun chain(length: Int): Node {
    val nodes = (0 until length).map { Node("n$it") }
    for (i in 0 until length - 1) nodes[i].next += nodes[i + 1]
    return nodes.first()
}

class ReverseReferenceIndexTest :
    StringSpec({
        "incoming, usedIn and instances reflect the reference graph" {
            val c = Node("c")
            val a = Node("a", mutableListOf(c))
            val b = Node("b", mutableListOf(c))
            val index = ReverseReferenceIndex.build(listOf(a, b), describer)
            index.instanceCount shouldBe 3
            index.referenceCount shouldBe 2
            index.incoming(c) shouldHaveSize 2
            index.usedIn(c, "node", "next") shouldHaveSize 2
            index.usedIn(c, "node", "other") shouldHaveSize 0
            index.incoming(a) shouldHaveSize 0
            index.contains(c) shouldBe true
            index.entityNameOf(c) shouldBe "node"
            index.instances().map { (it as Node).label } shouldBe listOf("a", "c", "b")
        }

        "the same graph produces the same instance order in every run" {
            fun order(): List<String> {
                val c = Node("c")
                val d = Node("d")
                val a = Node("a", mutableListOf(c, d))
                val b = Node("b", mutableListOf(d))
                return ReverseReferenceIndex.build(listOf(a, b), describer).instances().map { (it as Node).label }
            }
            val first = order()
            repeat(20) { order() shouldBe first }
        }

        "structurally equal data-class instances stay separate entities" {
            val one = Leaf("x")
            val two = Leaf("x")
            (one == two) shouldBe true
            val index = ReverseReferenceIndex.build(listOf(Holder(one), Holder(two)), describer)
            index.instanceCount shouldBe 4
            index.incoming(one) shouldHaveSize 1
            index.incoming(two) shouldHaveSize 1
        }

        "self loops and two- and three-cycles terminate with correct edges" {
            val self = Node("self").also { it.next += it }
            val x = Node("x")
            val y = Node("y", mutableListOf(x))
            x.next += y
            val p = Node("p")
            val q = Node("q")
            val r = Node("r", mutableListOf(p))
            p.next += q
            q.next += r
            val index = ReverseReferenceIndex.build(listOf(self, x, p), describer)
            index.instanceCount shouldBe 6
            index.incoming(self) shouldHaveSize 1
            index.incoming(x) shouldHaveSize 1
            index.incoming(p) shouldHaveSize 1
            index.referenceCount shouldBe 6
        }

        "maxInstances: n instances pass, n+1 throws" {
            val limits = ReverseReferenceIndex.Limits(maxInstances = 5, maxDepth = 100)
            ReverseReferenceIndex.build(listOf(chain(5)), describer, limits).instanceCount shouldBe 5
            shouldThrow<ReverseReferenceIndexLimitExceededException> {
                ReverseReferenceIndex.build(listOf(chain(6)), describer, limits)
            }.message shouldContain "5 instances"
        }

        "maxReferences trips on too many edges" {
            val hub = Node("hub", MutableList(10) { Node("leaf$it") })
            shouldThrow<ReverseReferenceIndexLimitExceededException> {
                ReverseReferenceIndex.build(listOf(hub), describer, ReverseReferenceIndex.Limits(maxReferences = 9))
            }
            ReverseReferenceIndex
                .build(
                    listOf(hub),
                    describer,
                    ReverseReferenceIndex.Limits(maxReferences = 10),
                ).referenceCount shouldBe
                10
        }

        "maxReferencesPerAttribute trips on one oversized attribute" {
            val hub = Node("hub", MutableList(4) { Node("leaf$it") })
            shouldThrow<ReverseReferenceIndexLimitExceededException> {
                ReverseReferenceIndex.build(
                    listOf(hub),
                    describer,
                    ReverseReferenceIndex.Limits(maxReferencesPerAttribute = 3),
                )
            }.message shouldContain "node.next"
        }

        "maxDepth: a chain of 64 instances passes, 65 throws naming the failing link" {
            ReverseReferenceIndex.build(listOf(chain(64)), describer).instanceCount shouldBe 64
            val e =
                shouldThrow<ReverseReferenceIndexLimitExceededException> {
                    ReverseReferenceIndex.build(listOf(chain(65)), describer)
                }
            e.message shouldContain "64 levels"
            e.message shouldContain "node.next"
        }

        "a very long chain does not overflow the stack when the depth limit is raised" {
            val limits =
                ReverseReferenceIndex.Limits(
                    maxInstances = 200_000,
                    maxReferences = 200_000,
                    maxDepth = 150_000,
                )
            ReverseReferenceIndex.build(listOf(chain(100_000)), describer, limits).instanceCount shouldBe 100_000
        }

        "an instance of an unknown type is rejected and the message stays bounded" {
            val bogus = object : Any() {}
            val e = shouldThrow<UnknownInstanceTypeException> { ReverseReferenceIndex.build(listOf(bogus), describer) }
            e.message shouldContain "does not know"
            val longName =
                InstanceDescriber {
                    InstanceDescription(
                        "n".repeat(
                            10_000,
                        ),
                        emptyMap(),
                        listOf(
                            OutgoingReference(
                                "n".repeat(10_000),
                                "a",
                                List(2) {
                                    Any()
                                },
                            ),
                        ),
                    )
                }
            val tooMany =
                shouldThrow<ReverseReferenceIndexLimitExceededException> {
                    ReverseReferenceIndex.build(
                        listOf(Any()),
                        longName,
                        ReverseReferenceIndex.Limits(maxReferencesPerAttribute = 1),
                    )
                }
            (tooMany.message!!.length < 2_000) shouldBe true
        }

        "a built index can be read from 16 threads with identical results" {
            val c = Node("c")
            val index =
                ReverseReferenceIndex.build(
                    listOf(Node("a", mutableListOf(c)), Node("b", mutableListOf(c))),
                    describer,
                )
            val pool = Executors.newFixedThreadPool(16)
            try {
                val results =
                    (1..16).map {
                        pool.submit<Int> {
                            (1..2_000).sumOf {
                                index
                                    .usedIn(
                                        c,
                                        "node",
                                        "next",
                                    ).size
                            }
                        }
                    }
                results.map { it.get(30, TimeUnit.SECONDS) }.toSet() shouldBe setOf(4_000)
            } finally {
                pool.shutdownNow()
            }
        }

        "mutating the roots list or the graph after the build does not change the index" {
            val a = Node("a")
            val roots = mutableListOf<Any>(a)
            val index = ReverseReferenceIndex.build(roots, describer)
            roots += Node("late")
            a.next += Node("later")
            index.instanceCount shouldBe 1
            index.instances() shouldHaveSize 1
        }

        "InstanceReference.toString never dumps instance values" {
            val index =
                ReverseReferenceIndex.build(
                    listOf(Node("secret-label-123", mutableListOf(Node("t")))),
                    describer,
                )
            val reference = index.instances().flatMap { index.incoming(it) }.single()
            reference.toString() shouldNotContain "secret-label-123"
        }
    })
