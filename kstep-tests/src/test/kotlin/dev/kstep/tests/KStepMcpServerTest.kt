package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.core.ap242.NextAssemblyUsageOccurrence
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.EntityStoreEntry
import dev.kstep.mcp.buildServer
import dev.kstep.step21.Part21Reader
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ChannelTransport (the SDK's own in-memory, no-subprocess client/server transport, from
// kotlin-sdk-testing) is `@ExperimentalMcpApi` — see kSTEP M2 Welle 1 plan pitfall #5.
@OptIn(ExperimentalMcpApi::class)
private suspend fun connectedClient(server: Server): Client {
    val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
    server.createSession(serverTransport)
    val client = Client(clientInfo = Implementation(name = "kstep-mcp-test-client", version = "test"))
    client.connect(clientTransport)
    return client
}

private fun CallToolResult.text(): String = content.joinToString("") { (it as? TextContent)?.text ?: "" }

private fun CallToolResult.errorKind(): String? = structuredContent?.get("errorKind")?.jsonPrimitive?.content

@OptIn(ExperimentalMcpApi::class)
class KStepMcpServerTest :
    StringSpec({
        "a 2-part-plus-assembly product structure roundtrips through MCP tool calls, Part-21 export, and parse" {
            val server = buildServer()
            val client = connectedClient(server)

            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "description" to "Mounting bracket"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "BRK-001-F", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "BRK-001-D", "formation_id" to "BRK-001-F"),
                ).isError shouldBe null

            client
                .callTool(
                    "build_product",
                    mapOf("id" to "HSG-001", "name" to "Housing", "description" to "Enclosure housing"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "HSG-001-F", "of_product_id" to "HSG-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "HSG-001-D", "formation_id" to "HSG-001-F"),
                ).isError shouldBe null

            val nauoResult =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-001",
                        "relating_product_definition_id" to "HSG-001-D",
                        "related_product_definition_id" to "BRK-001-D",
                        "reference_designator" to "RD-1",
                    ),
                )
            nauoResult.isError shouldBe null

            val exportResult =
                client.callTool(
                    "export_part21",
                    mapOf(
                        "root_ids" to listOf("NAUO-001"),
                        "file_name" to "assembly.step",
                        "timestamp" to "2026-07-19T12:00:00",
                    ),
                )
            exportResult.isError shouldBe null
            val exportedText = exportResult.text()
            exportedText shouldContain "ISO-10303-21;"
            val schemaIds =
                exportResult.structuredContent!!["schemaIdentifiers"]!!.jsonArray.map { it.jsonPrimitive.content }
            schemaIds shouldBe listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF")

            val parsed = Part21Reader.read(exportedText)
            parsed.isFullySuccessful shouldBe true
            val nauo =
                parsed.instances.values
                    .filterIsInstance<NextAssemblyUsageOccurrence>()
                    .single()
            nauo.id shouldBe "NAUO-001"
            nauo.relatingProductDefinition.id shouldBe "HSG-001-D"
            nauo.relatedProductDefinition.id shouldBe "BRK-001-D"
            nauo.referenceDesignator shouldBe "RD-1"
        }

        "build_product with an empty id returns a structured validation_failed error, not a crash" {
            val server = buildServer()
            val client = connectedClient(server)
            val result = client.callTool("build_product", mapOf("id" to ""))
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            val violation = violations.single().jsonObject
            violation["code"]!!.jsonPrimitive.content shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation["entityName"]!!.jsonPrimitive.content shouldBe "product"
            violation["ruleLabel"]!!.jsonPrimitive.content shouldBe "wr1"
        }

        "nextAssemblyUsageOccurrence with valid references but an empty reference_designator returns one violation" {
            val server = buildServer()
            val client = connectedClient(server)
            client.callTool("build_product", mapOf("id" to "BRK-001")).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "BRK-001-F", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "BRK-001-D", "formation_id" to "BRK-001-F"),
                ).isError shouldBe null

            val result =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "",
                        "relating_product_definition_id" to "BRK-001-D",
                        "related_product_definition_id" to "BRK-001-D",
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            violations
                .single()
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                DslViolationCodes.WHERE_RULE_NOT_SATISFIED
        }

        "nextAssemblyUsageOccurrence with two unknown references returns both in one structured error" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-X",
                        "relating_product_definition_id" to "nope-1",
                        "related_product_definition_id" to "nope-2",
                        "reference_designator" to "A1",
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
            val refs = result.structuredContent!!["references"]!!.jsonArray
            refs shouldHaveSize 2
            refs.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                listOf("nope-1", "nope-2")
        }

        "build_product_definition_formation with an unknown of_product_id returns a structured unknown_reference" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-001", "of_product_id" to "does-not-exist"),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
            val ref =
                result.structuredContent!!["references"]!!
                    .jsonArray
                    .single()
                    .jsonObject
            ref["field"]!!.jsonPrimitive.content shouldBe "of_product_id"
            ref["id"]!!.jsonPrimitive.content shouldBe "does-not-exist"
            ref["expectedEntityType"]!!.jsonPrimitive.content shouldBe "product"
        }

        "a wrong-type reference id is treated as unknown_reference, not a ClassCastException" {
            val server = buildServer()
            val client = connectedClient(server)
            client.callTool("build_product", mapOf("id" to "BRK-001")).isError shouldBe null
            val result = client.callTool("build_product_definition", mapOf("id" to "PD-1", "formation_id" to "BRK-001"))
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
        }

        "build_product with a missing required field returns malformed_input with no leaked stack trace" {
            val server = buildServer()
            val client = connectedClient(server)
            val result = client.callTool("build_product", mapOf("name" to "Bracket"))
            result.isError shouldBe true
            result.errorKind() shouldBe "malformed_input"
            result.text() shouldNotContain Regex("""at dev\.kstep""")
            result.text() shouldNotContain Regex("""\.kt:\d+""")
        }

        "an oversized string field is rejected as malformed_input, not silently truncated" {
            val server = buildServer()
            val client = connectedClient(server)
            val result = client.callTool("build_product", mapOf("id" to "x".repeat(5000)))
            result.isError shouldBe true
            result.errorKind() shouldBe "malformed_input"
        }

        "list_entities reflects what has been built, and an empty store reports count 0" {
            val server = buildServer()
            val client = connectedClient(server)

            val emptyListing = client.callTool("list_entities", emptyMap())
            emptyListing.isError shouldBe null
            emptyListing.structuredContent!!["count"]!!.jsonPrimitive.int shouldBe 0

            client.callTool("build_product", mapOf("id" to "BRK-001")).isError shouldBe null
            client
                .callTool(
                    "build_person_and_organization",
                    mapOf("handle" to "PO-1", "the_person" to "Jane Doe"),
                ).isError shouldBe null

            val listing = client.callTool("list_entities", emptyMap())
            val entities =
                listing.structuredContent!!["entities"]!!.jsonArray.map {
                    it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["entityType"]!!.jsonPrimitive.content
                }
            entities shouldContainExactlyInAnyOrder
                listOf("BRK-001" to "product", "PO-1" to "person_and_organization")
        }

        "get_entity returns a full field dump for a known id and a structured error for an unknown id" {
            val server = buildServer()
            val client = connectedClient(server)
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "description" to "d"),
                ).isError shouldBe null

            val found = client.callTool("get_entity", mapOf("id" to "BRK-001"))
            found.isError shouldBe null
            found.structuredContent!!["id"]!!.jsonPrimitive.content shouldBe "BRK-001"
            found.structuredContent!!["name"]!!.jsonPrimitive.content shouldBe "Bracket"

            val notFound = client.callTool("get_entity", mapOf("id" to "nope"))
            notFound.isError shouldBe true
            notFound.errorKind() shouldBe "unknown_reference"
        }

        "a duplicate id overwrites the store slot without retroactively rewiring already-resolved references" {
            val store = EntityStore()
            val server = buildServer(store)
            val client = connectedClient(server)

            client.callTool("build_product", mapOf("id" to "BRK-001", "name" to "A")).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-1", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client.callTool("build_product", mapOf("id" to "BRK-001", "name" to "B")).isError shouldBe null

            val current = (store.get("BRK-001") as EntityStoreEntry.ProductEntry).value
            current.name shouldBe "B"

            val formation = (store.get("PDF-1") as EntityStoreEntry.ProductDefinitionFormationEntry).value
            formation.ofProduct.name shouldBe "A"
        }

        "reusing an id across two different entity types is rejected, not silently overwritten" {
            val store = EntityStore()
            val server = buildServer(store)
            val client = connectedClient(server)

            client.callTool("build_product", mapOf("id" to "A", "name" to "First")).isError shouldBe null

            val collision =
                client.callTool("build_person_and_organization", mapOf("handle" to "A", "the_person" to "Jane"))
            collision.isError shouldBe true
            collision.errorKind() shouldBe "id_type_mismatch"

            // The original product entity must survive the rejected collision untouched.
            val stillProduct = (store.get("A") as EntityStoreEntry.ProductEntry).value
            stillProduct.name shouldBe "First"
        }

        "the entity store rejects a new id once its capacity cap is reached" {
            val store = EntityStore(maxEntities = 3)
            val server = buildServer(store)
            val client = connectedClient(server)

            client.callTool("build_product", mapOf("id" to "P-1")).isError shouldBe null
            client.callTool("build_product", mapOf("id" to "P-2")).isError shouldBe null
            client.callTool("build_product", mapOf("id" to "P-3")).isError shouldBe null
            val fourth = client.callTool("build_product", mapOf("id" to "P-4"))
            fourth.isError shouldBe true
            fourth.errorKind() shouldBe "store_capacity_exceeded"
        }

        "export_part21 with unknown root ids returns a structured unknown_reference error naming every bad root" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "export_part21",
                    mapOf(
                        "root_ids" to listOf("nope-1", "nope-2"),
                        "file_name" to "x.step",
                        "timestamp" to "t",
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
            val refs = result.structuredContent!!["references"]!!.jsonArray
            refs shouldHaveSize 2
            refs.map { it.jsonObject["id"]!!.jsonPrimitive.content } shouldContainExactlyInAnyOrder
                listOf("nope-1", "nope-2")
        }

        "export_part21 surfaces a non-ASCII field as a structured export_failed error, not a raw exception" {
            val server = buildServer()
            val client = connectedClient(server)
            client.callTool("build_product", mapOf("id" to "BRK-NONASCII", "name" to "Brackét")).isError shouldBe null
            val result =
                client.callTool(
                    "export_part21",
                    mapOf(
                        "root_ids" to listOf("BRK-NONASCII"),
                        "file_name" to "x.step",
                        "timestamp" to "t",
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "export_failed"
            result.text() shouldNotContain Regex("""\.kt:\d+""")
        }

        "concurrent tool calls from two independent sessions against one shared server do not lose writes" {
            val server = buildServer()
            val clientA = connectedClient(server)
            val clientB = connectedClient(server)
            val n = 20

            coroutineScope {
                val jobs =
                    (1..n).flatMap { i ->
                        listOf(
                            async { clientA.callTool("build_product", mapOf("id" to "A-$i")) },
                            async { clientB.callTool("build_product", mapOf("id" to "B-$i")) },
                        )
                    }
                jobs.awaitAll().forEach { it.isError shouldBe null }
            }

            val listing = clientA.callTool("list_entities", emptyMap())
            listing.structuredContent!!["count"]!!.jsonPrimitive.int shouldBe 2 * n
        }
    })
