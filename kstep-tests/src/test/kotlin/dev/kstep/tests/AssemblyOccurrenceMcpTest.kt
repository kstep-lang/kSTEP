package dev.kstep.tests

import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.buildServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalMcpApi::class)
private suspend fun connect(server: Server): Client {
    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
    server.createSession(serverTransport)
    val client = Client(clientInfo = Implementation(name = "kstep-mcp-occurrence-test", version = "test"))
    client.connect(clientTransport)
    return client
}

private fun CallToolResult.text(): String = content.joinToString("") { (it as? TextContent)?.text ?: "" }

private fun CallToolResult.errorKind(): String? = structuredContent?.get("errorKind")?.jsonPrimitive?.content

private fun CallToolResult.field(name: String): String? =
    structuredContent
        ?.get(name)
        ?.takeUnless { it is JsonNull }
        ?.jsonPrimitive
        ?.content

private fun CallToolResult.violationLabels(): List<String> =
    structuredContent!!["violations"]!!.jsonArray.map { it.jsonObject["ruleLabel"]!!.jsonPrimitive.content }

/** Shared contexts plus a single product/formation, from which any number of definitions are cheap to build. */
private class Fixture(
    val client: Client,
) {
    suspend fun init() {
        client.callTool("build_application_context", mapOf("handle" to "AC", "application" to "cc")).isError shouldBe
            null
        client
            .callTool(
                "build_product_context",
                mapOf(
                    "handle" to "PC",
                    "name" to "e",
                    "frame_of_reference_handle" to "AC",
                    "discipline_type" to "m",
                ),
            ).isError shouldBe null
        client
            .callTool(
                "build_product_definition_context",
                mapOf(
                    "handle" to "PDC",
                    "name" to "e",
                    "frame_of_reference_handle" to "AC",
                    "life_cycle_stage" to "d",
                ),
            ).isError shouldBe null
        client
            .callTool("build_product", mapOf("id" to "P", "name" to "p", "frame_of_reference_handles" to listOf("PC")))
            .isError shouldBe null
        client
            .callTool(
                "build_product_definition_formation",
                mapOf("id" to "F", "of_product_id" to "P"),
            ).isError shouldBe
            null
    }

    suspend fun pd(id: String) {
        client
            .callTool(
                "build_product_definition",
                mapOf("id" to id, "formation_id" to "F", "frame_of_reference_handle" to "PDC"),
            ).isError shouldBe null
    }

    suspend fun pdo(
        id: String,
        definitionId: String? = null,
    ): CallToolResult =
        client.callTool(
            "build_product_definition_occurrence",
            buildMap {
                put("id", id)
                definitionId?.let { put("definition_id", it) }
            },
        )

    suspend fun usage(
        id: String,
        relating: String,
        related: String,
        referenceDesignator: String? = null,
    ): CallToolResult =
        client.callTool(
            "build_next_assembly_usage_occurrence",
            buildMap {
                put("id", id)
                put("name", "usage $id")
                put("relating_product_definition_id", relating)
                put("related_product_definition_id", related)
                referenceDesignator?.let { put("reference_designator", it) }
            },
        )
}

@OptIn(ExperimentalMcpApi::class)
class AssemblyOccurrenceMcpTest :
    StringSpec({
        suspend fun fixture(server: Server = buildServer()): Fixture = Fixture(connect(server)).also { it.init() }

        "the server registers twenty tools, including the four new build tools" {
            val client = connect(buildServer())
            val names = client.listTools().tools.map { it.name }
            names.size shouldBe 20
            names shouldContainAll
                listOf(
                    "build_product_definition_occurrence",
                    "build_name_attribute",
                    "build_description_attribute",
                    "build_id_attribute",
                )
        }

        "a usage can relate onto a product_definition_occurrence and reports its occurrence id" {
            val f = fixture()
            f.pd("TOP")
            f.pd("CHILD")
            f.pdo("OCC-1", "CHILD").isError shouldBe null

            val result = f.usage("U1", "TOP", "OCC-1")
            result.isError shouldBe null
            result.field("related_entity_type") shouldBe "product_definition_occurrence"
            result.field("product_definition_occurrence_id") shouldBe "OCC-1"

            f.pd("PLAIN")
            val plain = f.usage("U2", "TOP", "PLAIN")
            plain.field("related_entity_type") shouldBe "product_definition"
            plain.field("product_definition_occurrence_id") shouldBe null
        }

        "an unknown definition for an occurrence and an unknown usage end are structured unknown_reference errors" {
            val f = fixture()
            f.pdo("O", "missing").errorKind() shouldBe "unknown_reference"
            f.usage("U", "nope", "nada").errorKind() shouldBe "unknown_reference"
            f.usage("U", "nope", "nada").text() shouldContain "product_definition | product_definition_occurrence"
        }

        "UR2: two usages of one relating definition whose related occurrences share an id are rejected" {
            val f = fixture()
            f.pd("TOP")
            f.pdo("OCC").isError shouldBe null
            f.usage("U1", "TOP", "OCC").isError shouldBe null
            // Rebuilding OCC creates a distinct instance with the same id: UR2 compares the derived id by value.
            f.pdo("OCC").isError shouldBe null

            val conflict = f.usage("U2", "TOP", "OCC")
            conflict.isError shouldBe true
            conflict.errorKind() shouldBe "unique_constraint_violated"
            conflict.field("ruleLabel") shouldBe "UR2"
            conflict.field("conflictingId") shouldBe "U1"
            conflict.text() shouldContain "product_definition_occurrence_id='OCC'"
            // nothing was written for the rejected usage
            f.client.callTool("get_entity", mapOf("id" to "U2")).errorKind() shouldBe "unknown_reference"
        }

        "UR2 does not apply across different relating definitions or to a plain product_definition" {
            val f = fixture()
            f.pd("TOP1")
            f.pd("TOP2")
            f.pdo("OCC").isError shouldBe null
            f.usage("U1", "TOP1", "OCC").isError shouldBe null
            f.pdo("OCC").isError shouldBe null
            f.usage("U2", "TOP2", "OCC").isError shouldBe null // same occurrence id, other relating

            f.pd("A")
            f.pd("B")
            f.usage("U3", "TOP1", "A").isError shouldBe null
            f.usage("U4", "TOP1", "B").isError shouldBe null // derived id undetermined on both: never equal
        }

        "rebuilding the same usage id does not conflict with its own previous version" {
            val f = fixture()
            f.pd("TOP")
            f.pdo("OCC").isError shouldBe null
            f.usage("U1", "TOP", "OCC", "RD").isError shouldBe null
            f.usage("U1", "TOP", "OCC", "RD").isError shouldBe null
        }

        "all violated rules are reported together, in the order UR1, UR2" {
            val f = fixture()
            f.pd("TOP")
            f.pdo("OCC").isError shouldBe null
            f.usage("U1", "TOP", "OCC", "RD").isError shouldBe null
            f.pdo("OCC").isError shouldBe null

            val conflict = f.usage("U2", "TOP", "OCC", "RD")
            conflict.errorKind() shouldBe "unique_constraint_violated"
            conflict.violationLabels() shouldContainExactly listOf("UR1", "UR2")
            conflict.field("ruleLabel") shouldBe "UR1"
        }

        "WR1: a usage that would close a cycle is rejected with the cycle path, and nothing is written" {
            val f = fixture()
            f.pd("A")
            f.pd("B")
            f.pd("C")
            f.usage("U1", "A", "B").isError shouldBe null
            f.usage("U2", "B", "C").isError shouldBe null

            val cyclic = f.usage("U3", "C", "A")
            cyclic.isError shouldBe true
            cyclic.errorKind() shouldBe "acyclic_relationship_violated"
            cyclic.field("ruleLabel") shouldBe "WR1"
            cyclic.structuredContent!!["path"]!!.jsonArray.map { it.jsonPrimitive.content } shouldContainExactly
                listOf("C", "B", "A")
            f.client.callTool("get_entity", mapOf("id" to "U3")).errorKind() shouldBe "unknown_reference"

            val selfUsage = f.usage("U4", "A", "A")
            selfUsage.errorKind() shouldBe "acyclic_relationship_violated"
        }

        "WR1: a usage rebuilt under its own id is checked without its previous version" {
            val f = fixture()
            f.pd("A")
            f.pd("B")
            f.usage("U1", "A", "B").isError shouldBe null
            // Reversing U1 would be a two-node cycle against its own old version -- but that version is replaced.
            f.usage("U1", "B", "A").isError shouldBe null
            // ...whereas a second, different usage in the old direction is the cycle.
            f.usage("U2", "A", "B").errorKind() shouldBe "acyclic_relationship_violated"
        }

        "WR1: a cycle through a product_definition_occurrence is rejected" {
            val f = fixture()
            f.pd("A")
            f.pdo("O", "A").isError shouldBe null
            f.usage("U1", "A", "O").isError shouldBe null
            f.usage("U2", "O", "A").errorKind() shouldBe "acyclic_relationship_violated"
        }

        "WR1: a structure deeper than the safety limit returns limit_exceeded and stores nothing" {
            val f = fixture(buildServer(EntityStore(maxEntities = 2_000)))
            val chain = 260
            for (i in 0..chain) f.pd("D$i")
            var firstFailure = -1
            for (i in 0 until chain) {
                val result = f.usage("U$i", "D$i", "D${i + 1}")
                if (result.isError == true) {
                    result.errorKind() shouldBe "limit_exceeded"
                    firstFailure = i
                    break
                }
            }
            // The walk up from D_i visits i+1 definitions; the depth limit is 256, so U_256 is the first refused.
            firstFailure shouldBe 256
            f.client.callTool("get_entity", mapOf("id" to "U256")).errorKind() shouldBe "unknown_reference"
        }

        "concurrent conflicting usages: exactly one wins UR1 (repeated)" {
            repeat(15) { round ->
                val f = fixture()
                f.pd("TOP")
                val contenders = 16
                for (i in 0 until contenders) f.pd("X$i")
                val results =
                    coroutineScope {
                        (0 until contenders)
                            .map { i -> async(Dispatchers.Default) { f.usage("U$i", "TOP", "X$i", "RD") } }
                            .awaitAll()
                    }
                withClue("round $round") {
                    results.count { it.isError != true } shouldBe 1
                    results
                        .filter {
                            it.isError == true
                        }.all { it.errorKind() == "unique_constraint_violated" } shouldBe
                        true
                }
            }
        }

        "concurrent opposite usages A->B and B->A: exactly one is stored (repeated)" {
            repeat(25) { round ->
                val f = fixture()
                f.pd("A")
                f.pd("B")
                val results =
                    coroutineScope {
                        listOf(
                            async(Dispatchers.Default) { f.usage("U1", "A", "B") },
                            async(Dispatchers.Default) { f.usage("U2", "B", "A") },
                        ).awaitAll()
                    }
                withClue("round $round") {
                    results.count { it.isError != true } shouldBe 1
                    results.single { it.isError == true }.errorKind() shouldBe "acyclic_relationship_violated"
                }
            }
        }

        "the attribute tools attach values and the derived value shows up in get_entity" {
            val f = fixture()
            f.pd("TOP")
            val first =
                f.client.callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N1", "attribute_value" to "Housing", "named_item_id" to "TOP"),
                )
            first.isError shouldBe null
            first.field("entityType") shouldBe "name_attribute"
            f.client.callTool("get_entity", mapOf("id" to "TOP")).field("derived_name") shouldBe "Housing"

            // a second one makes the value undetermined and violates WR1 of product_definition
            f.client
                .callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N2", "attribute_value" to "Casing", "named_item_id" to "TOP"),
                ).isError shouldBe null
            f.client.callTool("get_entity", mapOf("id" to "TOP")).field("derived_name") shouldBe null
            val validation = f.client.callTool("validate_model", emptyMap())
            validation.field("valid") shouldBe "false"
            validation.text() shouldContain "WR1"
        }

        "description and id attribute tools work on person_and_organization and application_context" {
            val f = fixture()
            f.client.callTool("build_person", mapOf("id" to "PER", "last_name" to "Doe")).isError shouldBe null
            f.client.callTool("build_organization", mapOf("handle" to "ORG", "name" to "ACME")).isError shouldBe null
            f.client
                .callTool(
                    "build_person_and_organization",
                    mapOf("handle" to "PO", "the_person_id" to "PER", "the_organization_handle" to "ORG"),
                ).isError shouldBe null
            f.client
                .callTool(
                    "build_description_attribute",
                    mapOf("handle" to "D1", "attribute_value" to "Lead", "described_item_id" to "PO"),
                ).isError shouldBe null
            f.client
                .callTool(
                    "build_id_attribute",
                    mapOf("handle" to "I1", "attribute_value" to "ctx-1", "identified_item_id" to "AC"),
                ).isError shouldBe null
            f.client.callTool("get_entity", mapOf("id" to "PO")).field("derived_description") shouldBe "Lead"
            f.client.callTool("get_entity", mapOf("id" to "AC")).field("derived_id") shouldBe "ctx-1"
        }

        "an attribute tool rejects an unknown or wrong-type item and a missing attribute_value" {
            val f = fixture()
            f.pd("TOP")
            f.client
                .callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N", "attribute_value" to "x", "named_item_id" to "ghost"),
                ).errorKind() shouldBe "unknown_reference"
            // PC is a product_context: not a member of name_attribute_select
            f.client
                .callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N", "attribute_value" to "x", "named_item_id" to "PC"),
                ).errorKind() shouldBe "unknown_reference"
            // application_context is no member of name_attribute_select either, only of id/description
            f.client
                .callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N", "attribute_value" to "x", "named_item_id" to "AC"),
                ).errorKind() shouldBe "unknown_reference"
            val missing =
                f.client.callTool("build_name_attribute", mapOf("handle" to "N", "named_item_id" to "TOP"))
            missing.errorKind() shouldBe "validation_failed"
            missing.structuredContent.toString() shouldContain "KSTEP-M-002"
        }

        "an exported model with occurrences and attributes parses back in STRICT mode" {
            val f = fixture()
            f.pd("TOP")
            f.pd("CHILD")
            f.pdo("OCC", "CHILD").isError shouldBe null
            f.usage("U1", "TOP", "OCC").isError shouldBe null
            f.client
                .callTool(
                    "build_name_attribute",
                    mapOf("handle" to "N", "attribute_value" to "Top", "named_item_id" to "TOP"),
                ).isError shouldBe null
            val export =
                f.client.callTool(
                    "export_part21",
                    mapOf(
                        "root_ids" to listOf("U1", "N"),
                        "file_name" to "o.step",
                        "timestamp" to "2026-10-19T00:00:00",
                    ),
                )
            export.isError shouldBe null
            val text = export.text()
            text shouldContain "PRODUCT_DEFINITION_OCCURRENCE('OCC'"
            text shouldNotContain "twelve"
            dev.kstep.step21.Part21Reader.read(text, validateModel = true).let {
                it.isFullySuccessful shouldBe true
                it.isModelValid shouldBe true
            }
        }

        "get_entity reports the ends of a usage by id even after one of them was rebuilt" {
            val f = fixture()
            f.pd("TOP")
            f.pd("CHILD")
            f.usage("U1", "TOP", "CHILD").isError shouldBe null
            f.pd("CHILD") // overwrites the store entry; the usage still points at the previous instance
            val described = f.client.callTool("get_entity", mapOf("id" to "U1"))
            described.field("related_product_definition_id") shouldBe "CHILD"
            described.field("relating_product_definition_id") shouldBe "TOP"
            described.field("related_entity_type") shouldBe "product_definition"
        }

        "list_entities names the new entity types" {
            val f = fixture()
            f.pd("TOP")
            f.pdo("OCC", "TOP").isError shouldBe null
            val listed = f.client.callTool("list_entities", emptyMap())
            listed.structuredContent!!["entities"]!!
                .jsonArray
                .map { it.jsonObject["entityType"]!!.jsonPrimitive.content }
                .shouldContain("product_definition_occurrence")
            JsonObject(emptyMap()).size shouldBe 0
        }
    })

private inline fun <T> withClue(
    clue: String,
    block: () -> T,
): T = io.kotest.assertions.withClue(clue, block)
