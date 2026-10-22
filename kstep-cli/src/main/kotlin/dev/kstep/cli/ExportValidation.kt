package dev.kstep.cli

import dev.kstep.core.ap242.ModelValidationResult
import dev.kstep.script.KStepModel
import dev.kstep.script.KStepScriptOutcomeCodes
import dev.kstep.script.ModelValidationOutcome
import dev.kstep.script.validateModel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.system.exitProcess

// `kstep export --validate` (ADR-0024). Plain println like the rest of the CLI's own result
// rendering (see Main.kt). Kept out of printExportError on purpose: that function is shared with
// `render` and takes a KStepScriptOutcome, which this opt-in check deliberately does not extend.

/**
 * Runs the opt-in population-level validation. Returns the summary of a clean run; on findings or
 * when no verdict could be reached it prints the structured error and exits with status 1
 * (fail closed) -- always before any output file is touched.
 */
internal fun validateForExport(
    model: KStepModel,
    jsonOutput: Boolean,
): ModelValidationResult =
    when (val outcome = model.validateModel()) {
        is ModelValidationOutcome.Checked -> {
            if (!outcome.result.isValid) printValidationFindings(jsonOutput, outcome.result)
            outcome.result
        }
        is ModelValidationOutcome.Unavailable -> printValidationUnavailable(jsonOutput, outcome)
    }

private fun printValidationFindings(
    jsonOutput: Boolean,
    result: ModelValidationResult,
): Nothing {
    if (jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "model_validation_failed")
                put("command", "export")
                put("instanceCount", result.instanceCount)
                put("indeterminateCount", result.indeterminateCount)
                put("suppressedFindingCount", result.suppressedFindingCount)
                put(
                    "findings",
                    buildJsonArray {
                        for (finding in result.findings) {
                            add(
                                buildJsonObject {
                                    put("entityType", finding.entityName)
                                    put(
                                        "violations",
                                        buildJsonArray {
                                            for (v in finding.violations) {
                                                add(
                                                    buildJsonObject {
                                                        put("code", v.code)
                                                        put("ruleLabel", v.ruleLabel)
                                                        put("expressionText", v.expressionText)
                                                        put("message", v.message)
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
                put("assumptions", assumptionsJson(result))
            }.toString(),
        )
    } else {
        val text =
            buildString {
                append(
                    "Model validation failed: ${result.findings.size} instance(s) violate population-level constraints:",
                )
                for (finding in result.findings) {
                    for (v in finding.violations) {
                        val rule = v.ruleLabel?.let { " ($it)" } ?: ""
                        append("\n  [${v.code}] ${finding.entityName}$rule: ${v.message}")
                    }
                }
                append(
                    "\nChecked ${result.instanceCount} instance(s); indeterminate: ${result.indeterminateCount}; " +
                        "suppressed: ${result.suppressedFindingCount}; assumptions applied: ${result.assumptions.size}",
                )
                append("\nNothing was written.")
            }
        println(text)
    }
    exitProcess(1)
}

private fun printValidationUnavailable(
    jsonOutput: Boolean,
    outcome: ModelValidationOutcome.Unavailable,
): Nothing {
    if (jsonOutput) {
        println(
            buildJsonObject {
                put("status", "error")
                put("errorKind", "model_validation_unavailable")
                put("code", KStepScriptOutcomeCodes.MODEL_VALIDATION_UNAVAILABLE)
                put("reason", outcome.kind.name)
                put("message", outcome.message)
                put("command", "export")
            }.toString(),
        )
    } else {
        val reason =
            outcome.kind.name
                .lowercase()
                .replace('_', ' ')
        println("Model validation could not be completed ($reason): ${outcome.message}. Nothing was written.")
    }
    exitProcess(1)
}

private fun assumptionsJson(result: ModelValidationResult): JsonElement =
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
    }

/** Success-path JSON object for the `"validation"` key. */
internal fun validationSummaryJson(result: ModelValidationResult): JsonElement =
    buildJsonObject {
        put("performed", true)
        put("valid", true)
        put("instanceCount", result.instanceCount)
        put("indeterminateCount", result.indeterminateCount)
        put("suppressedFindingCount", result.suppressedFindingCount)
        put("assumptionCount", result.assumptions.size)
    }

/** Success-path text lines (after the unchanged `Exported ...` line). */
internal fun validationSummaryText(result: ModelValidationResult): String =
    buildString {
        append(
            "Model validation passed (${result.instanceCount} instance(s) checked, " +
                "indeterminate: ${result.indeterminateCount}, suppressed: ${result.suppressedFindingCount})",
        )
        if (result.indeterminateCount + result.suppressedFindingCount > 0) {
            append(
                "\nNote: ${result.indeterminateCount + result.suppressedFindingCount} rule(s) could not be decided " +
                    "(indeterminate or suppressed); they are neither findings nor passes.",
            )
        }
    }
