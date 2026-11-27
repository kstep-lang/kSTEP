package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One argument of a tool (ADR-0096 D4): the input schema and the validation come from this one description. */
internal sealed class McpArg(
    val name: String,
    val description: String,
    val required: Boolean,
) {
    /** The JSON schema of this property. */
    abstract fun schema(): JsonObject

    /** The valid value of [v], or `null` when it does not fit. Never throws. */
    abstract fun read(v: JsonElement): Any?

    /** The value that applies when the caller leaves the argument out, or `null`. */
    open fun default(): Any? = null

    class StringArg(
        name: String,
        description: String,
        required: Boolean,
        val minLength: Int,
        val maxLength: Int,
    ) : McpArg(name, description, required) {
        override fun schema(): JsonObject =
            buildJsonObject {
                put("type", "string")
                put("description", description)
                put("minLength", minLength)
                put("maxLength", maxLength)
            }

        override fun read(v: JsonElement): Any? {
            val p = v as? JsonPrimitive ?: return null
            if (!p.isString) return null
            return p.content.takeIf { it.length in minLength..maxLength }
        }
    }

    class BoolArg(
        name: String,
        description: String,
        val default: Boolean = false,
    ) : McpArg(name, description, false) {
        override fun schema(): JsonObject =
            buildJsonObject {
                put("type", "boolean")
                put("description", description)
                put("default", default)
            }

        override fun read(v: JsonElement): Any? {
            val p = v as? JsonPrimitive ?: return null
            return if (p.isString) null else p.booleanOrNull
        }

        override fun default(): Any = default
    }

    class IntArg(
        name: String,
        description: String,
        required: Boolean,
        val min: Int,
        val max: Int,
        val default: Int?,
    ) : McpArg(name, description, required) {
        override fun schema(): JsonObject =
            buildJsonObject {
                put("type", "integer")
                put("description", description)
                put("minimum", min)
                put("maximum", max)
                if (default != null) put("default", default)
            }

        override fun read(v: JsonElement): Any? {
            val p = v as? JsonPrimitive ?: return null
            if (p.isString || !isPlainInteger(p.content)) return null
            return p.content
                .toLongOrNull()
                ?.takeIf { it in min..max }
                ?.toInt()
        }

        override fun default(): Any? = default

        /** `-?` and 1..10 ASCII digits: no `1.0`, no `1e3`, no sign `+`, no spaces. */
        private fun isPlainInteger(c: String): Boolean {
            val digits = if (c.startsWith("-")) c.length - 1 else c.length
            val from = c.length - digits
            return digits in 1..MAX_DIGITS && (from until c.length).all { c[it] in '0'..'9' }
        }

        private companion object {
            const val MAX_DIGITS = 10
        }
    }
}

/** The arguments a tool accepts. Anything else is refused (ADR-0096 D4). */
internal class McpArgSpec(
    val args: List<McpArg> = emptyList(),
) {
    /** The `inputSchema` of the tool. */
    fun schema(): JsonObject =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject { args.forEach { put(it.name, it.schema()) } },
            )
            val required = args.filter { it.required }
            if (required.isNotEmpty()) {
                put(
                    "required",
                    buildJsonArray { required.forEach { add(JsonPrimitive(it.name)) } },
                )
            }
            put("additionalProperties", false)
        }

    /** Validates [json]; throws [McpToolException] (`INVALID_ARGUMENTS`) without naming the value or the field. */
    fun parse(json: JsonObject): McpParsedArgs {
        if (json.size > MAX_KEYS) throw invalid()
        val byName = args.associateBy { it.name }
        val values = HashMap<String, Any>()
        for ((key, value) in json) {
            val spec = byName[key] ?: throw invalid()
            values[key] = spec.read(value) ?: throw invalid()
        }
        for (spec in args) {
            if (spec.name in values) continue
            if (spec.required) throw invalid()
            spec.default()?.let { values[spec.name] = it }
        }
        return McpParsedArgs(values)
    }

    private fun invalid() = McpToolException(McpToolCodes.INVALID_ARGUMENTS)

    companion object {
        const val MAX_KEYS = 16
    }
}

/** The validated arguments of one call. */
internal class McpParsedArgs(
    private val values: Map<String, Any>,
) {
    fun string(name: String): String? = values[name] as String?

    fun bool(name: String): Boolean = values[name] as Boolean? ?: false

    fun int(name: String): Int? = values[name] as Int?
}
