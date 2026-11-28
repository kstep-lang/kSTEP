package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON-RPC 2.0 envelopes. Error messages are fixed texts, never exception text. */
internal object JsonRpc {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    fun result(
        id: JsonElement,
        result: JsonObject,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    fun error(
        id: JsonElement?,
        code: Int,
        message: String,
    ): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put(
                "error",
                buildJsonObject {
                    put("code", code)
                    put("message", message)
                },
            )
        }

    /** The error code of a response object, or `null` for a result. */
    fun errorCode(response: JsonObject): Int? =
        ((response["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content?.toIntOrNull()
}

/**
 * The MCP methods of this server (ADR-0095): stateless, no session id. `handle` returns `null` for a notification (the
 * HTTP layer answers 202 without a body).
 */
internal class McpProtocol(
    private val tools: McpToolRegistry,
    private val serverVersion: String,
) {
    /** The tool names and methods this server knows; the only names the activity display ever shows (ADR-0099). */
    val knownTools: Set<String> = tools.all.map { it.name }.toSet()
    val knownMethods: Set<String> get() = KNOWN_METHODS

    /** [headerVersion] is the already validated `MCP-Protocol-Version` header, or `null` when it was not sent. */
    fun handle(
        request: JsonObject,
        headerVersion: String?,
    ): JsonObject? {
        if ((request["jsonrpc"] as? JsonPrimitive)?.takeIf { it.isString }?.content != "2.0") {
            return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request")
        }
        val method = (request["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // a response from the client (no method) is accepted and ignored; anything else is malformed
            return if ("result" in request ||
                "error" in request
            ) {
                null
            } else {
                JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request")
            }
        }
        if ("id" !in request) return null // a notification: never answered
        val id = request["id"]
        val validId = id is JsonPrimitive && id !is JsonNull
        if (!validId) return JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "Invalid request")
        val params = request["params"]
        if (params != null && params !is JsonObject) return JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params")
        return try {
            JsonRpc.result(id, dispatch(method, params as JsonObject?, headerVersion))
        } catch (_: McpInvalidParams) {
            JsonRpc.error(id, JsonRpc.INVALID_PARAMS, "Invalid params")
        } catch (_: MethodNotFound) {
            JsonRpc.error(id, JsonRpc.METHOD_NOT_FOUND, "Method not found")
        } catch (_: Exception) {
            JsonRpc.error(id, JsonRpc.INTERNAL_ERROR, "Internal error")
        }
    }

    private class MethodNotFound : RuntimeException(null, null, false, false)

    private fun dispatch(
        method: String,
        params: JsonObject?,
        headerVersion: String?,
    ): JsonObject =
        when (method) {
            "initialize" -> initialize(params)
            "ping" -> JsonObject(emptyMap())
            "tools/list" -> tools.list()
            "tools/call" -> {
                val name = (params?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
                val args = params?.get("arguments")
                if (name == null || (args != null && args !is JsonObject)) throw McpInvalidParams()
                tools.call(name, args as JsonObject?, headerVersion ?: DEFAULT_VERSION)
            }
            else -> throw MethodNotFound()
        }

    private fun initialize(params: JsonObject?): JsonObject {
        val wanted = (params?.get("protocolVersion") as? JsonPrimitive)?.takeIf { it.isString }?.content
        val version = if (wanted != null && wanted in SUPPORTED_VERSIONS) wanted else DEFAULT_VERSION
        return buildJsonObject {
            put("protocolVersion", version)
            put(
                "capabilities",
                buildJsonObject {
                    put("tools", buildJsonObject { put("listChanged", false) })
                },
            )
            put(
                "serverInfo",
                buildJsonObject {
                    put("name", SERVER_NAME)
                    put("version", serverVersion)
                },
            )
        }
    }

    companion object {
        val KNOWN_METHODS = setOf("initialize", "ping", "tools/list", "tools/call")
        const val SERVER_NAME = "kstep-desktop"
        const val DEFAULT_VERSION = "2025-03-26"
        const val STRUCTURED_VERSION = "2025-06-18"
        val SUPPORTED_VERSIONS = listOf(STRUCTURED_VERSION, DEFAULT_VERSION)
    }
}
