package dev.kstep.mcp.tools

import dev.kstep.core.ap242.Ap242ModelValidator
import dev.kstep.express.semantic.SemanticModelException
import dev.kstep.express.validation.InstanceGraphException
import dev.kstep.express.validation.UnsupportedInverseException
import dev.kstep.express.validation.UnsupportedUniqueRuleException
import dev.kstep.express.validation.UnsupportedWhereExpressionException
import dev.kstep.express.validation.WhereRuleEvaluationException
import dev.kstep.mcp.EntityStore
import dev.kstep.mcp.mcpToolCall
import dev.kstep.mcp.modelValidationFailedError
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val TOOL_NAME = "validate_model"

fun registerValidateModelTool(
    server: Server,
    store: EntityStore,
) {
    server.addTool(
        name = TOOL_NAME,
        description =
            "Validates the whole model built so far on population level — the constraints that depend on " +
                "which entities reference which (SIZEOF(USEDIN(...)) WHERE rules, INVERSE bounds, UNIQUE " +
                "rules and the acyclic-assembly rule, e.g. an application_context that no product_context / " +
                "product_definition_context refers to, two name_attribute entities on one product_definition, " +
                "or a usage cycle). " +
                "Takes no parameters. The population is every stored entity plus everything reachable from " +
                "them: an overwritten entity that another entity still references counts as a real " +
                "referrer. Builders never report these, because a single entity cannot know who will refer " +
                "to it later. Returns the violations per entity (store handle where one exists) and the " +
                "assumptions made: USEDIN over entities the AP242 V1 excerpt does not model " +
                "(configuration_design) is evaluated as the empty set.",
        inputSchema = ToolSchema(required = emptyList()),
    ) { _ ->
        mcpToolCall(TOOL_NAME) {
            val snapshot = store.consistentSnapshot()
            val handles = HashMap<Any, String>()
            for ((id, entry) in snapshot.entries.sortedBy { it.key }) {
                handles.putIfAbsent(
                    IdentityKey(entry.rawValue),
                    id,
                )
            }
            val result =
                try {
                    Ap242ModelValidator.validate(snapshot.values.map { it.rawValue })
                } catch (e: InstanceGraphException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                } catch (e: UnsupportedWhereExpressionException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                } catch (e: WhereRuleEvaluationException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                } catch (e: UnsupportedInverseException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                } catch (e: UnsupportedUniqueRuleException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                } catch (e: SemanticModelException) {
                    return@mcpToolCall modelValidationFailedError(e.message)
                }
            val text =
                buildString {
                    append(
                        if (result.isValid) {
                            "Model is consistent (${result.instanceCount} instance(s) checked)."
                        } else {
                            "${result.findings.size} instance(s) violate population-level constraints:"
                        },
                    )
                    for (finding in result.findings) {
                        val handle = handles[IdentityKey(finding.instance)]
                        for (violation in finding.violations) {
                            append(
                                "\n- ${handle ?: "(unnamed ${finding.entityName})"}: [${violation.code}] ${violation.message}",
                            )
                        }
                    }
                    if (result.assumptions.isNotEmpty()) {
                        append("\nAssumptions applied: ${result.assumptions.size}")
                    }
                }
            CallToolResult(
                content = listOf(TextContent(text = text)),
                structuredContent =
                    buildJsonObject {
                        put("valid", result.isValid)
                        put("instanceCount", result.instanceCount)
                        put("indeterminateCount", result.indeterminateCount)
                        put(
                            "findings",
                            buildJsonArray {
                                for (finding in result.findings) {
                                    add(
                                        buildJsonObject {
                                            val handle = handles[IdentityKey(finding.instance)]
                                            put("id", handle?.let { JsonPrimitive(it) } ?: JsonNull)
                                            put("entityType", finding.entityName)
                                            put(
                                                "violations",
                                                buildJsonArray {
                                                    for (violation in finding.violations) {
                                                        add(
                                                            buildJsonObject {
                                                                put("code", violation.code)
                                                                put("message", violation.message)
                                                            },
                                                        )
                                                    }
                                                },
                                            )
                                        },
                                    )
                                }
                            },
                        )
                        put(
                            "assumptions",
                            buildJsonArray {
                                for ((assumption, count) in result.assumptions.groupingBy { it }.eachCount()) {
                                    add(
                                        buildJsonObject {
                                            put("count", count)
                                            put("entityType", assumption.entityName)
                                            put("rule", assumption.ruleLabel?.let { JsonPrimitive(it) } ?: JsonNull)
                                            put("role", assumption.roleText)
                                            put("reason", assumption.reason)
                                        },
                                    )
                                }
                            },
                        )
                    },
            )
        }
    }
}

// Identity-keyed wrapper: the generated entities are data classes, so a plain HashMap would merge
// two structurally equal but distinct instances.
private class IdentityKey(
    private val value: Any,
) {
    override fun equals(other: Any?): Boolean = other is IdentityKey && other.value === value

    override fun hashCode(): Int = System.identityHashCode(value)
}
