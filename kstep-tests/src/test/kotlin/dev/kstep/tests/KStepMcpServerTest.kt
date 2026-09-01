package dev.kstep.tests

import dev.kstep.core.DslViolationCodes
import dev.kstep.generated.ap242v1.NextAssemblyUsageOccurrence
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

/**
 * Builds a shared `product_context` handle ("PC") and `product_definition_context` handle
 * ("PDC") on [this] client's server — kSTEP M2 Welle 10: every `build_product`/
 * `build_product_definition` call now needs a real context, `kstep-core` no longer invents one
 * (see `docs/adr/ADR-0004`). Every test in this file that builds a product/product_definition
 * calls this once up front and reuses the same two handles across every product it builds —
 * the AP242 `frame_of_reference` is context data, not per-product identity, so sharing it is
 * the realistic usage pattern, not a test shortcut.
 */
private suspend fun Client.setupContexts(): Pair<String, String> {
    callTool("build_application_context", mapOf("handle" to "AC", "application" to "config control")).isError shouldBe
        null
    callTool(
        "build_product_context",
        mapOf(
            "handle" to "PC",
            "name" to "engineering",
            "frame_of_reference_handle" to "AC",
            "discipline_type" to "mechanical",
        ),
    ).isError shouldBe null
    callTool(
        "build_product_definition_context",
        mapOf(
            "handle" to "PDC",
            "name" to "engineering",
            "frame_of_reference_handle" to "AC",
            "life_cycle_stage" to "design",
        ),
    ).isError shouldBe null
    return "PC" to "PDC"
}

@OptIn(ExperimentalMcpApi::class)
class KStepMcpServerTest :
    StringSpec({
        "a 2-part-plus-assembly product structure roundtrips through MCP tool calls, Part-21 export, and parse" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            client
                .callTool(
                    "build_product",
                    mapOf(
                        "id" to "BRK-001",
                        "name" to "Bracket",
                        "description" to "Mounting bracket",
                        "frame_of_reference_handles" to listOf(pc),
                    ),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "BRK-001-F", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "BRK-001-D", "formation_id" to "BRK-001-F", "frame_of_reference_handle" to pdc),
                ).isError shouldBe null

            client
                .callTool(
                    "build_product",
                    mapOf(
                        "id" to "HSG-001",
                        "name" to "Housing",
                        "description" to "Enclosure housing",
                        "frame_of_reference_handles" to listOf(pc),
                    ),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "HSG-001-F", "of_product_id" to "HSG-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "HSG-001-D", "formation_id" to "HSG-001-F", "frame_of_reference_handle" to pdc),
                ).isError shouldBe null

            val nauoResult =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-001",
                        "name" to "bracket usage",
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
            exportResult.structuredContent!!["rootCount"]!!.jsonPrimitive.int shouldBe 1
            // The full Part-21 text lives only in `content` — `structuredContent` must not carry
            // a second copy of it (see kSTEP M2 Welle 10 security review: doubling a multi-root
            // export's full text into structuredContent as well doubled the tool call's memory
            // footprint for no caller-facing benefit).
            exportResult.structuredContent!!.containsKey("part21Text") shouldBe false

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
            val (pc, _) = client.setupContexts()
            val result =
                client.callTool(
                    "build_product",
                    mapOf("id" to "", "name" to "Bracket", "frame_of_reference_handles" to listOf(pc)),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            val violation = violations.single().jsonObject
            violation["code"]!!.jsonPrimitive.content shouldBe DslViolationCodes.WHERE_RULE_NOT_SATISFIED
            violation["entityName"]!!.jsonPrimitive.content shouldBe "product"
            violation["ruleLabel"]!!.jsonPrimitive.content shouldBe "kstep_wr1"
        }

        "build_product with an empty frame_of_reference_handles list returns validation_failed (KSTEP-A-001)" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "frame_of_reference_handles" to emptyList<String>()),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            violations
                .single()
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                DslViolationCodes.AGGREGATION_BOUND_VIOLATED
        }

        "build_product with an unknown frame_of_reference_handles entry returns unknown_reference, no build attempted" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "frame_of_reference_handles" to listOf("nope")),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
        }

        (
            "build_product with frame_of_reference_handles omitted entirely returns validation_failed " +
                "(KSTEP-M-001), distinct from an explicitly-empty list"
        ) {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool("build_product", mapOf("id" to "BRK-001", "name" to "Bracket"))
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            violations
                .single()
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                DslViolationCodes.MISSING_MANDATORY_REFERENCE
        }

        (
            "build_person with an explicitly-empty middle_names/prefix_titles/suffix_titles list returns " +
                "validation_failed (KSTEP-A-001) for each, distinct from omitting the field entirely"
        ) {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_person",
                    mapOf(
                        "id" to "P1",
                        "last_name" to "Doe",
                        "middle_names" to emptyList<String>(),
                        "prefix_titles" to emptyList<String>(),
                        "suffix_titles" to emptyList<String>(),
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 3
            violations.map { it.jsonObject["code"]!!.jsonPrimitive.content }.shouldContainExactlyInAnyOrder(
                List(3) { DslViolationCodes.AGGREGATION_BOUND_VIOLATED },
            )
        }

        "build_person with middle_names/prefix_titles/suffix_titles omitted entirely succeeds — genuinely OPTIONAL" {
            val server = buildServer()
            val client = connectedClient(server)
            client
                .callTool("build_person", mapOf("id" to "P1", "last_name" to "Doe"))
                .isError shouldBe null
        }

        "nextAssemblyUsageOccurrence with valid references and no reference_designator succeeds — genuinely OPTIONAL" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "BRK-001-F", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition",
                    mapOf("id" to "BRK-001-D", "formation_id" to "BRK-001-F", "frame_of_reference_handle" to pdc),
                ).isError shouldBe null

            val result =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-1",
                        "name" to "bracket usage",
                        "relating_product_definition_id" to "BRK-001-D",
                        "related_product_definition_id" to "BRK-001-D",
                    ),
                )
            result.isError shouldBe null
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
            val (pc, _) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null
            val result =
                client.callTool(
                    "build_product_definition",
                    mapOf("id" to "PD-1", "formation_id" to "BRK-001", "frame_of_reference_handle" to "does-not-exist"),
                )
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

        "an oversized frame_of_reference_handles list is rejected as malformed_input, not silently truncated" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool(
                    "build_product",
                    mapOf(
                        "id" to "BRK-001",
                        "name" to "Bracket",
                        "frame_of_reference_handles" to List(100) { "h-$it" },
                    ),
                )
            result.isError shouldBe true
            result.errorKind() shouldBe "malformed_input"
        }

        "build_approval_status builds and stores a Valid instance under its handle" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool("build_approval_status", mapOf("handle" to "AS-1", "name" to "approved"))
            result.isError shouldBe null
            result.structuredContent!!["handle"]!!.jsonPrimitive.content shouldBe "AS-1"
            result.structuredContent!!["name"]!!.jsonPrimitive.content shouldBe "approved"
            result.structuredContent!!["entityType"]!!.jsonPrimitive.content shouldBe "approval_status"
        }

        "build_approval_status with name omitted returns validation_failed (KSTEP-M-002)" {
            val server = buildServer()
            val client = connectedClient(server)
            val result = client.callTool("build_approval_status", mapOf("handle" to "AS-1"))
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            violations
                .single()
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
        }

        "build_approval builds a Valid instance referencing an already-built approval_status by status_handle" {
            val server = buildServer()
            val client = connectedClient(server)
            client
                .callTool("build_approval_status", mapOf("handle" to "AS-1", "name" to "approved"))
                .isError shouldBe null

            val result =
                client.callTool("build_approval", mapOf("handle" to "A-1", "status_handle" to "AS-1", "level" to "3"))
            result.isError shouldBe null
            result.structuredContent!!["handle"]!!.jsonPrimitive.content shouldBe "A-1"
            result.structuredContent!!["status_handle"]!!.jsonPrimitive.content shouldBe "AS-1"
            result.structuredContent!!["level"]!!.jsonPrimitive.content shouldBe "3"
            result.structuredContent!!["entityType"]!!.jsonPrimitive.content shouldBe "approval"
        }

        "build_approval with an unknown status_handle returns unknown_reference, no build attempted" {
            val server = buildServer()
            val client = connectedClient(server)
            val result =
                client.callTool("build_approval", mapOf("handle" to "A-1", "status_handle" to "nope", "level" to "3"))
            result.isError shouldBe true
            result.errorKind() shouldBe "unknown_reference"
        }

        "build_approval with level omitted returns validation_failed (KSTEP-M-002)" {
            val server = buildServer()
            val client = connectedClient(server)
            client
                .callTool("build_approval_status", mapOf("handle" to "AS-1", "name" to "approved"))
                .isError shouldBe null

            val result = client.callTool("build_approval", mapOf("handle" to "A-1", "status_handle" to "AS-1"))
            result.isError shouldBe true
            result.errorKind() shouldBe "validation_failed"
            val violations = result.structuredContent!!["violations"]!!.jsonArray
            violations shouldHaveSize 1
            violations
                .single()
                .jsonObject["code"]!!
                .jsonPrimitive.content shouldBe
                DslViolationCodes.MISSING_MANDATORY_ATTRIBUTE
        }

        "list_entities reflects what has been built, and an empty store reports count 0" {
            val server = buildServer()
            val client = connectedClient(server)

            val emptyListing = client.callTool("list_entities", emptyMap())
            emptyListing.isError shouldBe null
            emptyListing.structuredContent!!["count"]!!.jsonPrimitive.int shouldBe 0

            val (pc, _) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "Bracket", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null
            client.callTool("build_person", mapOf("id" to "PERSON-1", "last_name" to "Doe")).isError shouldBe null
            client.callTool("build_organization", mapOf("handle" to "ORG-1", "name" to "Acme")).isError shouldBe null
            client
                .callTool(
                    "build_person_and_organization",
                    mapOf("handle" to "PO-1", "the_person_id" to "PERSON-1", "the_organization_handle" to "ORG-1"),
                ).isError shouldBe null

            val listing = client.callTool("list_entities", emptyMap())
            val entities =
                listing.structuredContent!!["entities"]!!.jsonArray.map {
                    it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["entityType"]!!.jsonPrimitive.content
                }
            entities shouldContainExactlyInAnyOrder
                listOf(
                    "AC" to "application_context",
                    "PC" to "product_context",
                    "PDC" to "product_definition_context",
                    "BRK-001" to "product",
                    "PERSON-1" to "person",
                    "ORG-1" to "organization",
                    "PO-1" to "person_and_organization",
                )
        }

        "get_entity returns a full field dump for a known id and a structured error for an unknown id" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, _) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf(
                        "id" to "BRK-001",
                        "name" to "Bracket",
                        "description" to "d",
                        "frame_of_reference_handles" to listOf(pc),
                    ),
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
            val (pc, _) = client.setupContexts()

            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "A", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-1", "of_product_id" to "BRK-001"),
                ).isError shouldBe null
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "BRK-001", "name" to "B", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null

            val current = (store.get("BRK-001") as EntityStoreEntry.ProductEntry).value
            current.name shouldBe "B"

            val formation = (store.get("PDF-1") as EntityStoreEntry.ProductDefinitionFormationEntry).value
            formation.ofProduct.name shouldBe "A"
        }

        "reusing an id across two different entity types is rejected, not silently overwritten" {
            val store = EntityStore()
            val server = buildServer(store)
            val client = connectedClient(server)
            val (pc, _) = client.setupContexts()

            client
                .callTool(
                    "build_product",
                    mapOf("id" to "A", "name" to "First", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null

            val collision = client.callTool("build_organization", mapOf("handle" to "A", "name" to "Acme"))
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

            client.callTool("build_person", mapOf("id" to "P-1", "last_name" to "N1")).isError shouldBe null
            client.callTool("build_person", mapOf("id" to "P-2", "last_name" to "N2")).isError shouldBe null
            client.callTool("build_person", mapOf("id" to "P-3", "last_name" to "N3")).isError shouldBe null
            val fourth = client.callTool("build_person", mapOf("id" to "P-4", "last_name" to "N4"))
            fourth.isError shouldBe true
            fourth.errorKind() shouldBe "store_capacity_exceeded"
        }

        (
            "the entity store rejects a put that would exceed its character budget, independent of " +
                "the entity-count cap"
        ) {
            val store = EntityStore(maxTotalCharacters = 10L)
            val server = buildServer(store)
            val client = connectedClient(server)

            // "P-1" (3) + "ABCDE" (5) = 8 own characters — under the 10-character budget.
            client.callTool("build_person", mapOf("id" to "P-1", "last_name" to "ABCDE")).isError shouldBe null

            // "P-2" (3) + "ABCDEFGHIJ" (10) = 13 more own characters; 8 + 13 = 21 > 10.
            val second = client.callTool("build_person", mapOf("id" to "P-2", "last_name" to "ABCDEFGHIJ"))
            second.isError shouldBe true
            second.errorKind() shouldBe "store_character_budget_exceeded"

            // The rejected put must not have been partially applied.
            (store.get("P-2")) shouldBe null
        }

        (
            "a same-id overwrite re-measures the character budget from the new entry, not double-counted " +
                "against the old one"
        ) {
            val store = EntityStore(maxTotalCharacters = 10L)
            val server = buildServer(store)
            val client = connectedClient(server)

            // "P-1" (3) + "AB" (2) = 5 own characters.
            client.callTool("build_person", mapOf("id" to "P-1", "last_name" to "AB")).isError shouldBe null

            // Overwriting the SAME id with "P-1" (3) + "ABCDEFG" (7) = 10 own characters must
            // succeed: a buggy implementation that added the new size on top of the old one
            // instead of replacing it (5 + 10 = 15 > 10) would wrongly reject this.
            client
                .callTool("build_person", mapOf("id" to "P-1", "last_name" to "ABCDEFG"))
                .isError shouldBe null

            // The store's running total must now be exactly 10 (not 15, and not back down to
            // 0) — one more character tips it over the budget.
            val third = client.callTool("build_person", mapOf("id" to "P-2", "last_name" to "A"))
            third.isError shouldBe true
            third.errorKind() shouldBe "store_character_budget_exceeded"
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
            val (pc, _) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf(
                        "id" to "BRK-NONASCII",
                        "name" to "Brackét",
                        "frame_of_reference_handles" to listOf(pc),
                    ),
                ).isError shouldBe null
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

        "a second NAUO with the same (reference_designator, relating_product_definition) is rejected" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            suspend fun buildProductDefinition(
                id: String,
                pdfId: String,
                productId: String,
            ) {
                client
                    .callTool(
                        "build_product",
                        mapOf("id" to productId, "name" to "N", "frame_of_reference_handles" to listOf(pc)),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition_formation",
                        mapOf("id" to pdfId, "of_product_id" to productId),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition",
                        mapOf("id" to id, "formation_id" to pdfId, "frame_of_reference_handle" to pdc),
                    ).isError shouldBe null
            }
            buildProductDefinition("PD-RELATING", "PDF-1", "P-1")
            buildProductDefinition("PD-RELATED-A", "PDF-2", "P-2")
            buildProductDefinition("PD-RELATED-B", "PDF-3", "P-3")

            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-1",
                        "name" to "usage 1",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED-A",
                        "reference_designator" to "RD-1",
                    ),
                ).isError shouldBe null

            val conflict =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-2",
                        "name" to "usage 2",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED-B",
                        "reference_designator" to "RD-1",
                    ),
                )
            conflict.isError shouldBe true
            conflict.errorKind() shouldBe "unique_constraint_violated"
            conflict.structuredContent!!["conflictingId"]!!.jsonPrimitive.content shouldBe "NAUO-1"
            conflict.structuredContent!!["ruleLabel"]!!.jsonPrimitive.content shouldBe "UR1"
            conflict.structuredContent!!["entityType"]!!.jsonPrimitive.content shouldBe
                "next_assembly_usage_occurrence"
        }

        "two NAUOs that both leave reference_designator unset never conflict under UR1" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            suspend fun buildProductDefinition(
                id: String,
                pdfId: String,
                productId: String,
            ) {
                client
                    .callTool(
                        "build_product",
                        mapOf("id" to productId, "name" to "N", "frame_of_reference_handles" to listOf(pc)),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition_formation",
                        mapOf("id" to pdfId, "of_product_id" to productId),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition",
                        mapOf("id" to id, "formation_id" to pdfId, "frame_of_reference_handle" to pdc),
                    ).isError shouldBe null
            }
            buildProductDefinition("PD-RELATING", "PDF-1", "P-1")
            buildProductDefinition("PD-RELATED", "PDF-2", "P-2")

            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-1",
                        "name" to "usage 1",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED",
                    ),
                ).isError shouldBe null
            val second =
                client.callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-2",
                        "name" to "usage 2",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED",
                    ),
                )
            second.isError shouldBe null
        }

        "two NAUOs differing only in reference_designator both succeed" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            suspend fun buildProductDefinition(
                id: String,
                pdfId: String,
                productId: String,
            ) {
                client
                    .callTool(
                        "build_product",
                        mapOf("id" to productId, "name" to "N", "frame_of_reference_handles" to listOf(pc)),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition_formation",
                        mapOf("id" to pdfId, "of_product_id" to productId),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition",
                        mapOf("id" to id, "formation_id" to pdfId, "frame_of_reference_handle" to pdc),
                    ).isError shouldBe null
            }
            buildProductDefinition("PD-RELATING", "PDF-1", "P-1")
            buildProductDefinition("PD-RELATED", "PDF-2", "P-2")

            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-1",
                        "name" to "usage 1",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED",
                        "reference_designator" to "RD-1",
                    ),
                ).isError shouldBe null
            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-2",
                        "name" to "usage 2",
                        "relating_product_definition_id" to "PD-RELATING",
                        "related_product_definition_id" to "PD-RELATED",
                        "reference_designator" to "RD-2",
                    ),
                ).isError shouldBe null
        }

        "two NAUOs differing only in relating_product_definition both succeed" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            suspend fun buildProductDefinition(
                id: String,
                pdfId: String,
                productId: String,
            ) {
                client
                    .callTool(
                        "build_product",
                        mapOf("id" to productId, "name" to "N", "frame_of_reference_handles" to listOf(pc)),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition_formation",
                        mapOf("id" to pdfId, "of_product_id" to productId),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition",
                        mapOf("id" to id, "formation_id" to pdfId, "frame_of_reference_handle" to pdc),
                    ).isError shouldBe null
            }
            buildProductDefinition("PD-RELATING-A", "PDF-1", "P-1")
            buildProductDefinition("PD-RELATING-B", "PDF-2", "P-2")
            buildProductDefinition("PD-RELATED", "PDF-3", "P-3")

            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-1",
                        "name" to "usage 1",
                        "relating_product_definition_id" to "PD-RELATING-A",
                        "related_product_definition_id" to "PD-RELATED",
                        "reference_designator" to "RD-1",
                    ),
                ).isError shouldBe null
            client
                .callTool(
                    "build_next_assembly_usage_occurrence",
                    mapOf(
                        "id" to "NAUO-2",
                        "name" to "usage 2",
                        "relating_product_definition_id" to "PD-RELATING-B",
                        "related_product_definition_id" to "PD-RELATED",
                        "reference_designator" to "RD-1",
                    ),
                ).isError shouldBe null
        }

        "rebuilding the same NAUO id with unchanged fields succeeds, not a false self-conflict" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, pdc) = client.setupContexts()

            suspend fun buildProductDefinition(
                id: String,
                pdfId: String,
                productId: String,
            ) {
                client
                    .callTool(
                        "build_product",
                        mapOf("id" to productId, "name" to "N", "frame_of_reference_handles" to listOf(pc)),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition_formation",
                        mapOf("id" to pdfId, "of_product_id" to productId),
                    ).isError shouldBe null
                client
                    .callTool(
                        "build_product_definition",
                        mapOf("id" to id, "formation_id" to pdfId, "frame_of_reference_handle" to pdc),
                    ).isError shouldBe null
            }
            buildProductDefinition("PD-RELATING", "PDF-1", "P-1")
            buildProductDefinition("PD-RELATED", "PDF-2", "P-2")

            val args =
                mapOf(
                    "id" to "NAUO-1",
                    "name" to "usage 1",
                    "relating_product_definition_id" to "PD-RELATING",
                    "related_product_definition_id" to "PD-RELATED",
                    "reference_designator" to "RD-1",
                )
            client.callTool("build_next_assembly_usage_occurrence", args).isError shouldBe null
            val rebuilt = client.callTool("build_next_assembly_usage_occurrence", args)
            rebuilt.isError shouldBe null
            rebuilt.errorKind() shouldBe null
        }

        // See README Roadmap, "M2 Welle 8 — codegen reconciliation" divergence-inventory table,
        // product_definition_formation / UNIQUE UR1 row: this test is the proof cited there for
        // "resolved by construction", contrasted with NAUO's real UNIQUE UR1 enforcement above
        // (whose fields exclude the store key and so do need a scan). Do not "helpfully" add a
        // putIfNoConflict-style scan for this entity — it would be dead code, since no
        // tool-reachable input can ever produce a conflict here.
        "product_definition_formation's UNIQUE UR1 needs no enforcement: store id-keying already guarantees it" {
            val server = buildServer()
            val client = connectedClient(server)
            val (pc, _) = client.setupContexts()
            client
                .callTool(
                    "build_product",
                    mapOf("id" to "SHARED-PRODUCT", "name" to "Shared", "frame_of_reference_handles" to listOf(pc)),
                ).isError shouldBe null

            // Two different ids referencing the SAME of_product both succeed — no
            // unique_constraint_violated is ever returned for product_definition_formation
            // because none is implemented for it: the composite key (id, of_product) can never
            // collide once id alone already differs, which the store already guarantees.
            val first =
                client.callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-A", "of_product_id" to "SHARED-PRODUCT"),
                )
            first.isError shouldBe null
            first.errorKind() shouldBe null
            val second =
                client.callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-B", "of_product_id" to "SHARED-PRODUCT"),
                )
            second.isError shouldBe null
            second.errorKind() shouldBe null

            // Rebuilding under the SAME id succeeds via EntityStore's pre-existing overwrite
            // semantics — not a new UNIQUE-checking code path. Asserting errorKind() is null
            // (an overwrite), not merely isError, is the explicit record that this is NOT a
            // unique_constraint_violated path.
            val rebuilt =
                client.callTool(
                    "build_product_definition_formation",
                    mapOf("id" to "PDF-A", "of_product_id" to "SHARED-PRODUCT"),
                )
            rebuilt.isError shouldBe null
            rebuilt.errorKind() shouldBe null
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
                            async { clientA.callTool("build_person", mapOf("id" to "A-$i", "last_name" to "N-$i")) },
                            async { clientB.callTool("build_person", mapOf("id" to "B-$i", "last_name" to "N-$i")) },
                        )
                    }
                jobs.awaitAll().forEach { it.isError shouldBe null }
            }

            val listing = clientA.callTool("list_entities", emptyMap())
            listing.structuredContent!!["count"]!!.jsonPrimitive.int shouldBe 2 * n
        }
    })
