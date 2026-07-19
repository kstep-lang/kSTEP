package dev.kstep.mcp

import dev.kstep.core.DslViolation
import io.github.oshai.kotlinlogging.KotlinLogging
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

const val MAX_STRING_FIELD_LENGTH = 4096
const val MAX_LIST_ITEMS = 64

private val logger = KotlinLogging.logger {}

/** Thrown for a decoded-but-still-invalid tool argument (oversized field/list) — routed into the same
 * malformed-input [CallToolResult] shape as a [SerializationException] by [mcpToolCall], since both are
 * "the caller's JSON didn't satisfy the tool's real contract", not a validation-rule or reference problem.
 */
class ToolInputException(
    message: String,
) : RuntimeException(message)

fun requireBoundedString(
    field: String,
    value: String,
) {
    if (value.length > MAX_STRING_FIELD_LENGTH) {
        throw ToolInputException(
            "field '$field' exceeds the maximum allowed length of $MAX_STRING_FIELD_LENGTH characters",
        )
    }
}

fun requireBoundedList(
    field: String,
    items: List<*>,
) {
    if (items.size > MAX_LIST_ITEMS) {
        throw ToolInputException("field '$field' exceeds the maximum allowed item count of $MAX_LIST_ITEMS")
    }
}

// Matches a JVM stack-frame-looking fragment ("at dev.kstep...(File.kt:42)" or a bare "File.kt:42")
// so a future kotlinx-serialization version change to its exception message text can't leak internal
// paths/line numbers to the untrusted MCP caller — defense-in-depth on top of never forwarding raw
// exception messages for anything but the two hand-authored cases (SerializationException, the
// Part21*Exception family) that are already known-safe by construction.
private val STACK_FRAME_PATTERN = Regex("""\bat [\w.$]+\([^)]*:\d+\)|\b[\w.$]+\.kt:\d+\b""")

// kotlinx.serialization's MissingFieldException/UnknownKeyException message text embeds the
// fully-qualified internal DTO class name verbatim (e.g. "type with serial name
// 'dev.kstep.mcp.dto.BuildProductArgs'") — an internal package/class-naming detail, not
// something the caller's own tool-input contract exposes anywhere else. Collapsed to just the
// simple class name so the error stays actionable without leaking internal package structure.
private val QUALIFIED_INTERNAL_CLASS_NAME_PATTERN = Regex("""\bdev\.kstep(?:\.\w+)*\.(\w+)\b""")

private fun sanitizeMessage(message: String?): String {
    val withoutStackFrames = STACK_FRAME_PATTERN.replace(message ?: "unknown error", "[omitted]")
    return QUALIFIED_INTERNAL_CLASS_NAME_PATTERN.replace(withoutStackFrames) { it.groupValues[1] }
}

data class UnknownReference(
    val field: String,
    val id: String,
    val expectedEntityType: String?,
)

/**
 * Runs [block], routing every exception the tool-specific logic doesn't itself catch into one of
 * this file's structured [CallToolResult] error shapes instead of letting it reach the SDK's own
 * `Server.handleCallTool` catch — which interpolates a raw `e.message` into the response. Handling
 * it here first is what keeps that raw message from ever reaching the untrusted caller.
 */
suspend fun mcpToolCall(
    toolName: String,
    block: suspend () -> CallToolResult,
): CallToolResult {
    val result =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            malformedInputError(toolName, e.message ?: "invalid arguments")
        } catch (e: ToolInputException) {
            malformedInputError(toolName, e.message ?: "invalid arguments")
        } catch (e: Exception) {
            internalError(e::class.simpleName)
        }
    logToolOutcome(toolName, result)
    return result
}

// Reuses the errorKind this file's own *Error() builders already put into structuredContent,
// rather than a second parallel taxonomy. internal_error (an unexpected, unhandled exception) is
// ERROR -- an operator-actionable anomaly; every other structured error is WARN -- caller-driven
// and expected, but worth seeing; a clean result is DEBUG. Never reads e.message directly: only
// the already-sanitized fields (toolName, errorKind) that the CallToolResult itself carries, so
// nothing reaches the log that the response wasn't already safe to send to the caller.
private fun logToolOutcome(
    toolName: String,
    result: CallToolResult,
) {
    if (result.isError != true) {
        logger.debug { "tool call succeeded: tool=$toolName" }
        return
    }
    val errorKind =
        result.structuredContent
            ?.get("errorKind")
            ?.jsonPrimitive
            ?.content
    if (errorKind == "internal_error") {
        logger.error { "tool call failed: tool=$toolName errorKind=$errorKind" }
    } else {
        logger.warn { "tool call failed: tool=$toolName errorKind=$errorKind" }
    }
}

fun malformedInputError(
    toolName: String,
    message: String,
): CallToolResult {
    val sanitized = sanitizeMessage(message)
    return CallToolResult(
        content = listOf(TextContent(text = "Malformed input for tool '$toolName': $sanitized")),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "malformed_input")
                put("message", sanitized)
            },
    )
}

fun unknownReferenceError(references: List<UnknownReference>): CallToolResult {
    val summary =
        references.joinToString("; ") { ref ->
            "field '${ref.field}': unknown ${ref.expectedEntityType ?: "entity"} id '${ref.id}'"
        }
    return CallToolResult(
        content = listOf(TextContent(text = "Unknown or wrong-type reference(s): $summary")),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "unknown_reference")
                put(
                    "references",
                    buildJsonArray {
                        for (ref in references) {
                            add(
                                buildJsonObject {
                                    put("field", ref.field)
                                    put("id", ref.id)
                                    put("expectedEntityType", ref.expectedEntityType)
                                },
                            )
                        }
                    },
                )
            },
    )
}

fun validationFailedError(violations: List<DslViolation>): CallToolResult {
    val summary = violations.joinToString("; ") { it.message }
    return CallToolResult(
        content = listOf(TextContent(text = summary)),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "validation_failed")
                put(
                    "violations",
                    buildJsonArray {
                        for (violation in violations) {
                            add(
                                buildJsonObject {
                                    put("code", violation.code)
                                    put("entityName", violation.entityName)
                                    put("ruleLabel", violation.ruleLabel)
                                    put("expressionText", violation.expressionText)
                                    put("message", violation.message)
                                },
                            )
                        }
                    },
                )
            },
    )
}

fun storeCapacityExceededError(
    currentSize: Int,
    maxEntities: Int,
): CallToolResult =
    CallToolResult(
        content =
            listOf(
                TextContent(
                    text = "Entity store capacity exceeded ($currentSize/$maxEntities entities already stored)",
                ),
            ),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "store_capacity_exceeded")
                put("currentSize", currentSize)
                put("maxEntities", maxEntities)
            },
    )

fun typeMismatchError(
    id: String,
    existingEntityType: String,
    newEntityType: String,
): CallToolResult =
    CallToolResult(
        content =
            listOf(
                TextContent(
                    text =
                        "id '$id' is already in use by a '$existingEntityType' entity; cannot store a " +
                            "'$newEntityType' under the same id",
                ),
            ),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "id_type_mismatch")
                put("id", id)
                put("existingEntityType", existingEntityType)
                put("newEntityType", newEntityType)
            },
    )

fun exportFailedError(message: String?): CallToolResult {
    val sanitized = sanitizeMessage(message)
    return CallToolResult(
        content = listOf(TextContent(text = "Part-21 export failed: $sanitized")),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "export_failed")
                put("message", sanitized)
            },
    )
}

fun internalError(exceptionClassName: String?): CallToolResult =
    CallToolResult(
        content =
            listOf(
                TextContent(
                    text =
                        "Internal error while executing tool" + (exceptionClassName?.let { " ($it)" } ?: ""),
                ),
            ),
        isError = true,
        structuredContent =
            buildJsonObject {
                put("errorKind", "internal_error")
                put("message", exceptionClassName ?: "unknown")
            },
    )

/** Stores [entry] under [id], mapping an [EntityStore.PutOutcome.CapacityExceeded] to its structured error
 * shape and otherwise handing the newly-stored entity to [onStored] to build the tool's success result.
 */
fun storeOrCapacityError(
    store: EntityStore,
    id: String,
    entry: EntityStoreEntry,
    onStored: () -> CallToolResult,
): CallToolResult =
    when (val outcome = store.put(id, entry)) {
        is EntityStore.PutOutcome.CapacityExceeded ->
            storeCapacityExceededError(
                outcome.currentSize,
                outcome.maxEntities,
            )
        is EntityStore.PutOutcome.TypeMismatch ->
            typeMismatchError(
                outcome.id,
                outcome.existingEntityType,
                outcome.newEntityType,
            )
        EntityStore.PutOutcome.Ok -> onStored()
    }
