package dev.kstep.desktop.mcp

import dev.kstep.desktop.ai.settings.defaultConfigDir
import dev.kstep.desktop.settings.PrivateConfigFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.nio.file.Path

/** The MCP server settings (ADR-0095). The server is off by default; nothing here changes without a user action. */
data class McpSettings(
    val enabled: Boolean = false,
    val port: Int = McpPreferencesStore.DEFAULT_PORT,
    /** ADR-0097: agents may change things (editor text, runs, selection, visibility). Off by default. */
    val allowActions: Boolean = false,
)

/**
 * Persists [McpSettings] as `mcp.json` in the desktop config folder. Untrusted on load: a regular file (no symbolic
 * link) of at most 1 KiB, strict UTF-8, `version` the number 1, an optional JSON boolean `enabled` and an optional
 * integer `port` in 1024..65535; any deviation gives the defaults, which means OFF. Never throws.
 */
class McpPreferencesStore(
    private val dir: Path = defaultConfigDir(),
) {
    fun load(): McpSettings =
        try {
            val text = PrivateConfigFile.readStrictUtf8(dir.resolve(FILE_NAME), MAX_BYTES)
            if (text == null) McpSettings() else parse(Json.parseToJsonElement(text) as? JsonObject)
        } catch (_: Exception) {
            McpSettings()
        } catch (_: StackOverflowError) {
            McpSettings()
        }

    private fun parse(obj: JsonObject?): McpSettings {
        val version = (obj?.get("version") as? JsonPrimitive)?.takeIf { !it.isString }?.content
        if (version != "1") return McpSettings()
        val enabled =
            when (val element = obj["enabled"]) {
                null -> false
                is JsonPrimitive -> if (element.isString) null else element.booleanOrNull
                else -> null
            } ?: return McpSettings()
        val port =
            when (val element = obj["port"]) {
                null -> DEFAULT_PORT
                is JsonPrimitive -> if (element.isString) null else element.intOrNull
                else -> null
            }?.takeIf { it in MIN_PORT..MAX_PORT } ?: return McpSettings()
        val allowActions =
            when (val element = obj["allowActions"]) {
                null -> false
                is JsonPrimitive -> if (element.isString) null else element.booleanOrNull
                else -> null
            } ?: return McpSettings()
        // allowFiles is never restored (ADR-0098 D1): only a JSON boolean or nothing is accepted, the value is ignored
        when (val element = obj["allowFiles"]) {
            null -> Unit
            is JsonPrimitive -> if (element.isString || element.booleanOrNull == null) return McpSettings()
            else -> return McpSettings()
        }
        return McpSettings(enabled, port, allowActions)
    }

    /** `false` when the file could not be written (shown as `KSTEP-D-052`); never throws. */
    fun save(settings: McpSettings): Boolean {
        val body =
            buildJsonObject {
                put("version", 1)
                put("enabled", settings.enabled)
                put("port", settings.port.coerceIn(MIN_PORT, MAX_PORT))
                put("allowActions", settings.allowActions)
                // always false: the permission for file actions lasts for one session only (ADR-0098 D1)
                put("allowFiles", false)
            }
        return PrivateConfigFile.writeAtomic(dir, FILE_NAME, "mcp", body.toString())
    }

    companion object {
        const val FILE_NAME = "mcp.json"
        const val MAX_BYTES = 1024L
        const val DEFAULT_PORT = 7391
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
    }
}
