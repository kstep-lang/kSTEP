package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** The stable error codes of a tool result (`isError: true`). The messages are fixed texts, never exception text. */
object McpToolCodes {
    const val NO_FILE = "NO_FILE"
    const val UI_BUSY = "UI_BUSY"

    /** Reserved: `get_script` shortens instead of refusing. */
    const val TOO_LARGE = "TOO_LARGE"
    const val INTERNAL = "INTERNAL"

    val MESSAGES =
        mapOf(
            NO_FILE to "No script or note is open in the editor.",
            UI_BUSY to "The window did not answer in time. Try again.",
            TOO_LARGE to "The result is too large.",
            INTERNAL to "The tool failed.",
        )
}

/** A tool failed with a stable [code]. No message and no stack trace: nothing of it can leak. */
class McpToolException(
    val code: String,
) : RuntimeException(null, null, false, false)

/** The arguments of a call are not valid (JSON-RPC `-32602`). */
internal class McpInvalidParams : RuntimeException(null, null, false, false)

/** A tool: [call] turns the arguments and the snapshot into the structured result. */
internal class McpToolDef(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val call: (args: JsonObject, snapshot: DesktopStateSnapshot, protocolVersion: String) -> JsonObject,
)

/** Limits of the read tools. */
data class McpToolLimits(
    /** The most UTF-8 bytes of editor text one `get_script` call returns. */
    val maxScriptBytes: Int = 1 shl 20,
)

/** The tools of this wave: both only read the published snapshot. */
internal class McpToolRegistry(
    private val host: McpHost,
    private val limits: McpToolLimits = McpToolLimits(),
) {
    private val emptySchema =
        buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(emptyMap()))
            put("additionalProperties", false)
        }

    val all: List<McpToolDef> =
        listOf(
            McpToolDef(
                name = "kstep_desktop_get_status",
                description =
                    "Reads what kSTEP Desktop shows: the open file, whether it has unsaved changes, the state of " +
                        "the live preview and the size of the model. Read only.",
                inputSchema = emptySchema,
            ) { _, snapshot, version -> status(snapshot, version) },
            McpToolDef(
                name = "kstep_desktop_get_script",
                description =
                    "Reads the text in the editor of kSTEP Desktop (a script or a knowledge note), including " +
                        "unsaved changes, with a revision hash. Long text is shortened. Read only.",
                inputSchema = emptySchema,
            ) { _, snapshot, _ -> script(snapshot) },
        )

    private val byName = all.associateBy { it.name }

    fun knows(name: String): Boolean = name in byName

    fun list(): JsonObject =
        buildJsonObject {
            put(
                "tools",
                buildJsonArray {
                    all.forEach { tool ->
                        add(
                            buildJsonObject {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("inputSchema", tool.inputSchema)
                            },
                        )
                    }
                },
            )
        }

    /**
     * The result object of `tools/call`. An unknown tool or arguments that are not an object throw
     * [McpInvalidParams]; a tool failure is a normal result with `isError: true`.
     */
    fun call(
        name: String,
        args: JsonObject?,
        protocolVersion: String,
    ): JsonObject {
        val structured = protocolVersion == McpProtocol.STRUCTURED_VERSION
        val tool = byName[name] ?: throw McpInvalidParams()
        return try {
            val result = tool.call(args ?: JsonObject(emptyMap()), host.snapshot, protocolVersion)
            toolResult(result, isError = false, structured = structured)
        } catch (e: McpToolException) {
            errorResult(e.code, structured)
        } catch (_: Exception) {
            errorResult(McpToolCodes.INTERNAL, structured)
        }
    }

    private fun errorResult(
        code: String,
        structured: Boolean,
    ): JsonObject {
        val message = McpToolCodes.MESSAGES[code] ?: McpToolCodes.MESSAGES.getValue(McpToolCodes.INTERNAL)
        val body = buildJsonObject { put("code", code) }
        return buildJsonObject {
            put("isError", true)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", "$code: $message")
                        },
                    )
                },
            )
            if (structured) put("structuredContent", body)
        }
    }

    private fun toolResult(
        body: JsonObject,
        isError: Boolean,
        structured: Boolean,
    ): JsonObject =
        buildJsonObject {
            put("isError", isError)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", body.toString())
                        },
                    )
                },
            )
            if (structured) put("structuredContent", body)
        }

    private fun status(
        s: DesktopStateSnapshot,
        protocolVersion: String,
    ): JsonObject =
        buildJsonObject {
            put("appVersion", host.appVersion)
            put("protocolVersion", protocolVersion)
            put(
                "file",
                s.filePath?.let { path ->
                    buildJsonObject {
                        put("name", path.fileName?.toString() ?: path.toString())
                        put("path", path.toString())
                    }
                } ?: JsonNull,
            )
            put("dirty", s.dirty)
            put(
                "run",
                when (s.run) {
                    RunKind.IDLE -> "idle"
                    RunKind.RUNNING -> "running"
                    RunKind.OK -> "ok"
                    RunKind.ERROR -> "error"
                    RunKind.UNAVAILABLE -> "unavailable"
                },
            )
            put("waitingHint", s.waitingHint?.let { JsonPrimitive(it) } ?: JsonNull)
            put(
                "previewOrigin",
                when (s.previewOrigin) {
                    PreviewKind.SAVED_FILE -> JsonPrimitive("savedFile")
                    PreviewKind.UNSAVED_BUFFER -> JsonPrimitive("unsavedBuffer")
                    null -> JsonNull
                },
            )
            put("parts", s.parts?.let { JsonPrimitive(it) } ?: JsonNull)
            put("triangles", s.triangles?.let { JsonPrimitive(it) } ?: JsonNull)
            put("draftOfferOpen", s.draftOfferOpen)
            put("workspace", if (s.knowledgeWorkspace) JsonPrimitive("knowledge") else JsonNull)
        }

    private fun script(s: DesktopStateSnapshot): JsonObject {
        val text = s.editorText
        if (s.editorKind == EditorKind.NONE || text == null) throw McpToolException(McpToolCodes.NO_FILE)
        val bytes = text.toByteArray(Charsets.UTF_8)
        val truncated = bytes.size > limits.maxScriptBytes
        val shown = if (truncated) cutAtCodePoint(bytes, limits.maxScriptBytes) else text
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildJsonObject {
            put("kind", if (s.editorKind == EditorKind.NOTE) "note" else "script")
            put("text", shown)
            put("revision", "sha256:" + digest.joinToString("") { "%02x".format(it) })
            put("lines", if (text.isEmpty()) 0 else text.count { it == '\n' } + 1)
            put("dirty", s.dirty)
            put("totalBytes", bytes.size)
            put("truncated", truncated)
            if (s.editorKind == EditorKind.NOTE) put("notice", NOTE_NOTICE)
        }
    }

    /** The first at most [max] bytes of [bytes] as text, cut at a code point boundary (never inside a character). */
    private fun cutAtCodePoint(
        bytes: ByteArray,
        max: Int,
    ): String {
        var end = max
        while (end > 0 && (bytes[end].toInt() and CONTINUATION_MASK) == CONTINUATION) end--
        return String(bytes, 0, end, Charsets.UTF_8)
    }

    companion object {
        const val NOTE_NOTICE = "The editor shows a knowledge note, not a kSTEP script."
        private const val CONTINUATION_MASK = 0xC0
        private const val CONTINUATION = 0x80
    }
}
