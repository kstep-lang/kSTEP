package dev.kstep.desktop.pick

import dev.kstep.cli.DesktopPartLimits
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.TestScenes.triangle
import dev.kstep.desktop.run.AssemblyTreeParser
import dev.kstep.desktop.run.AssemblyTreeResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The assembly tree of the worker is hostile input (ADR-0054): valid trees pass, every tampering is rejected. */
class AssemblyTreeParserTest :
    StringSpec({
        val (_, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(30.0, 0.0, 0.0),
                triangle(60.0, 0.0, 0.0),
                names = listOf("A", "B", "C"),
            )

        fun node(
            name: JsonElement,
            parent: JsonElement,
            part: JsonElement,
        ) = buildJsonObject {
            put("name", name)
            put("parent", parent)
            put("part", part)
        }

        fun n(
            name: String,
            parent: Int,
            part: Int,
        ) = node(JsonPrimitive(name), JsonPrimitive(parent), JsonPrimitive(part))

        fun json(vararg nodes: JsonElement): JsonObject =
            buildJsonObject { put("tree", buildJsonObject { put("nodes", JsonArray(nodes.toList())) }) }

        fun parse(
            j: JsonObject,
            t: PartTable = table,
        ) = AssemblyTreeParser.parse(j, t)

        fun invalid(r: AssemblyTreeResult) = r.shouldBeInstanceOf<AssemblyTreeResult.Invalid>()

        // Top > [A, Sub > [B, C]]
        val good = arrayOf(n("Top", -1, -1), n("A", 0, 0), n("Sub", 0, -1), n("B", 2, 1), n("C", 2, 2))

        "a valid tree is accepted" {
            val tree = parse(json(*good)).shouldBeInstanceOf<AssemblyTreeResult.Valid>().tree
            tree.size shouldBe 5
            tree.firstPart(2) shouldBe 1
            tree.lastPart(2) shouldBe 2
        }

        "a missing or null tree is silently absent" {
            parse(JsonObject(emptyMap())) shouldBe AssemblyTreeResult.Absent
            parse(buildJsonObject { put("tree", JsonNull) }) shouldBe AssemblyTreeResult.Absent
        }

        "treeOmitted makes the tree invalid so nothing fails silently" {
            val j =
                buildJsonObject {
                    put("tree", JsonNull)
                    put(
                        "treeOmitted",
                        buildJsonObject {
                            put("reason", JsonPrimitive("too_many_nodes"))
                            put("count", JsonPrimitive(9000))
                        },
                    )
                }
            invalid(parse(j))
        }

        "tree and nodes of the wrong JSON type are invalid" {
            invalid(parse(buildJsonObject { put("tree", JsonPrimitive("x")) }))
            invalid(parse(buildJsonObject { put("tree", buildJsonObject { put("nodes", JsonPrimitive(1)) }) }))
            invalid(parse(json()))
            invalid(parse(json(JsonPrimitive("a"))))
        }

        "a parent index that is not smaller than the node index (a cycle) is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 1, 0), n("B", 0, 1), n("C", 0, 2))))
            invalid(parse(json(n("Top", -1, -1), n("A", 2, 0), n("Sub", 0, -1), n("B", 2, 1), n("C", 2, 2))))
        }

        "a leaf as parent is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("B", 1, 1), n("C", 0, 2))))
        }

        "a second root is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("Other", -1, -1), n("A", 1, 0), n("B", 1, 1), n("C", 1, 2))))
            invalid(parse(json(n("A", -1, 0), n("B", -1, 1), n("C", -1, 2))))
        }

        "the first node must be the root" {
            invalid(parse(json(n("Top", 0, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, 2))))
        }

        "a node that skips back to a closed branch is not pre-order and invalid" {
            // Sub is closed by Other, so C may no longer hang below it
            invalid(
                parse(
                    json(
                        n("Top", -1, -1),
                        n("Sub", 0, -1),
                        n("A", 1, 0),
                        n("Other", 0, -1),
                        n("B", 3, 1),
                        n("C", 1, 2),
                    ),
                ),
            )
        }

        "a duplicate, missing, out-of-range or unordered part index is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("A", 0, 0), n("C", 0, 2))))
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("B", 0, 1))))
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, 3))))
            invalid(parse(json(n("Top", -1, -1), n("B", 0, 1), n("A", 0, 0), n("C", 0, 2))))
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, -2))))
        }

        "a leaf name that differs from its part is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("Renamed", 0, 1), n("C", 0, 2))))
        }

        "a group without a child is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("Empty", 0, -1), n("B", 0, 1), n("C", 0, 2))))
        }

        "a tree where a part is not listed is invalid" {
            invalid(parse(json(n("Top", -1, -1), n("A", 0, 0), n("B", 0, 1))))
        }

        "control characters, ANSI escapes and bidi marks in a group name are cleaned" {
            val dirty = "Su\u001B[31mb\u001B[0m\u0007‮"
            val tree =
                parse(json(n("Top", -1, -1), n(dirty, 0, -1), n("A", 1, 0), n("B", 1, 1), n("C", 0, 2)))
                    .shouldBeInstanceOf<AssemblyTreeResult.Valid>()
                    .tree
            tree.name(1) shouldBe "Sub��"
        }

        "an empty, invisible or too long name is invalid" {
            invalid(parse(json(n("", -1, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, 2))))
            invalid(parse(json(n("‮\u0007 ", -1, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, 2))))
            val long = "x".repeat(DesktopPartLimits.MAX_NAME_CHARS + 1)
            invalid(parse(json(n(long, -1, -1), n("A", 0, 0), n("B", 0, 1), n("C", 0, 2))))
        }

        "numbers of the wrong type are invalid" {
            fun withParent(p: JsonElement) =
                json(n("Top", -1, -1), node(JsonPrimitive("A"), p, JsonPrimitive(0)), n("B", 0, 1), n("C", 0, 2))
            invalid(parse(withParent(JsonPrimitive("0"))))
            invalid(parse(withParent(JsonPrimitive(0.5))))
            invalid(parse(withParent(JsonNull)))
            invalid(parse(withParent(JsonPrimitive(3_000_000_000L))))
            invalid(parse(withParent(JsonPrimitive(Long.MAX_VALUE))))
            invalid(
                parse(
                    json(
                        n("Top", -1, -1),
                        node(JsonPrimitive("A"), JsonPrimitive(0), JsonPrimitive("0")),
                        n("B", 0, 1),
                        n("C", 0, 2),
                    ),
                ),
            )
        }

        "the depth limit holds: 64 levels are fine, 65 are not" {
            fun chain(levels: Int): Pair<JsonObject, PartTable> {
                val (_, one) = TestScenes.scene(triangle(0.0, 0.0, 0.0), names = listOf("Leaf"))
                val nodes = ArrayList<JsonElement>()
                nodes += n("g0", -1, -1)
                for (i in 1 until levels) nodes += n("g$i", i - 1, -1)
                nodes += n("Leaf", levels - 1, 0)
                return json(*nodes.toTypedArray()) to one
            }
            val (ok, t1) = chain(DesktopPartLimits.MAX_TREE_DEPTH) // groups g0..g63, leaf at depth 64
            parse(ok, t1).shouldBeInstanceOf<AssemblyTreeResult.Valid>()
            val (deep, t2) = chain(DesktopPartLimits.MAX_TREE_DEPTH + 1)
            invalid(parse(deep, t2))
        }

        "more nodes than the limit are invalid without being read" {
            val nodes = ArrayList<JsonElement>()
            nodes += n("Top", -1, -1)
            repeat(DesktopPartLimits.MAX_TREE_NODES) { nodes += n("g", 0, -1) }
            invalid(parse(json(*nodes.toTypedArray())))
        }

        "the parser never throws on arbitrary garbage" {
            val garbage =
                listOf<JsonElement>(
                    JsonNull,
                    JsonPrimitive(1),
                    JsonArray(listOf(JsonNull)),
                    buildJsonObject { put("nodes", JsonNull) },
                    buildJsonObject { put("nodes", JsonArray(listOf(JsonArray(emptyList())))) },
                )
            for (g in garbage) {
                parse(buildJsonObject { put("tree", g) })
                parse(buildJsonObject { put("treeOmitted", g) })
            }
        }
    })
