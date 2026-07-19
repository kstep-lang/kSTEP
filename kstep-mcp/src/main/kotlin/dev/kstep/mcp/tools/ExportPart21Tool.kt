package dev.kstep.mcp.tools

import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.UnknownReference
import dev.kstep.mcp.dto.ExportPart21Args
import dev.kstep.mcp.exportFailedError
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.requireBoundedList
import dev.kstep.mcp.requireBoundedString
import dev.kstep.mcp.unknownReferenceError
import dev.kstep.step21.Part21EncodingException
import dev.kstep.step21.Part21Header
import dev.kstep.step21.Part21LimitExceededException
import dev.kstep.step21.Part21WriteException
import dev.kstep.step21.Part21Writer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private const val TOOL_NAME = "export_part21"

// Matches every other wave's own default — see Part21WriterTest/Part21RoundtripTest/README, not invented here.
private val DEFAULT_SCHEMA_IDENTIFIERS = listOf("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF")

private val INPUT_SCHEMA =
    ToolSchema(
        properties =
            buildJsonObject {
                putJsonObject("root_ids") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("file_name") { put("type", "string") }
                putJsonObject("timestamp") { put("type", "string") }
                putJsonObject("description") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("author") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("organization") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
                putJsonObject("schema_identifiers") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            },
        required = listOf("root_ids", "file_name", "timestamp"),
    )

fun registerExportPart21Tool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Serializes one or more already-built entities (by root_ids — every entity reachable from " +
                "each root, e.g. a next_assembly_usage_occurrence's two product_definitions and their " +
                "formations/products, is included automatically) to ISO 10303-21 (STEP Part 21) physical " +
                "file text. schema_identifiers defaults to the AP242 MIM schema used elsewhere in kSTEP if " +
                "omitted. An unknown root id returns a structured unknown_reference error (every bad root is " +
                "collected, not just the first). A non-ASCII/control character anywhere in a referenced " +
                "entity's string fields — legal at build time, not at export time — returns a structured " +
                "export_failed error.",
        inputSchema = INPUT_SCHEMA,
    ) { request ->
        mcpToolCall(TOOL_NAME) {
            val args = Json.decodeFromJsonElement<ExportPart21Args>(request.arguments ?: JsonObject(emptyMap()))
            requireBoundedList("root_ids", args.rootIds)
            requireBoundedString("file_name", args.fileName)
            requireBoundedString("timestamp", args.timestamp)
            requireBoundedList("description", args.description)
            requireBoundedList("author", args.author)
            requireBoundedList("organization", args.organization)
            args.description.forEach { requireBoundedString("description[]", it) }
            args.author.forEach { requireBoundedString("author[]", it) }
            args.organization.forEach { requireBoundedString("organization[]", it) }
            args.schemaIdentifiers?.let { requireBoundedList("schema_identifiers", it) }
            args.schemaIdentifiers?.forEach { requireBoundedString("schema_identifiers[]", it) }

            val roots = mutableListOf<Any>()
            val unknownRefs = mutableListOf<UnknownReference>()
            for (rootId in args.rootIds) {
                val entry = store.get(rootId)
                if (entry == null) {
                    unknownRefs += UnknownReference("root_ids", rootId, null)
                } else {
                    roots += entry.rawValue
                }
            }
            if (unknownRefs.isNotEmpty()) return@mcpToolCall unknownReferenceError(unknownRefs)

            val header =
                Part21Header(
                    fileName = args.fileName,
                    timestamp = args.timestamp,
                    schemaIdentifiers = args.schemaIdentifiers ?: DEFAULT_SCHEMA_IDENTIFIERS,
                    description = args.description,
                    author = args.author,
                    organization = args.organization,
                )

            try {
                val text = Part21Writer.write(header, roots)
                CallToolResult(
                    content = listOf(TextContent(text = text)),
                    structuredContent =
                        buildJsonObject {
                            put("part21Text", text)
                            put("rootCount", roots.size)
                            putJsonArray("schemaIdentifiers") {
                                header.schemaIdentifiers.forEach { add(JsonPrimitive(it)) }
                            }
                        },
                )
            } catch (e: Part21WriteException) {
                exportFailedError(e.message)
            } catch (e: Part21EncodingException) {
                exportFailedError(e.message)
            } catch (e: Part21LimitExceededException) {
                exportFailedError(e.message)
            }
        }
    }
}
