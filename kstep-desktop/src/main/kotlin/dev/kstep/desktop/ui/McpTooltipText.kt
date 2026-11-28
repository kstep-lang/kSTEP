package dev.kstep.desktop.ui

import dev.kstep.desktop.mcp.McpActivity
import dev.kstep.desktop.mcp.McpCallOutcome
import dev.kstep.desktop.mcp.McpPhase
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The text of the MCP indicator, free of Compose so that it can be tested alone (ADR-0099). */
internal object McpTooltipText {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun phaseWord(phase: McpPhase): String =
        when (phase) {
            McpPhase.Ready -> "ready"
            McpPhase.Busy -> "working"
            McpPhase.Failed -> "last call failed"
        }

    /** The line of permissions, `null` when agents may only read. */
    fun permissions(
        editable: Boolean,
        files: Boolean,
    ): String? =
        when {
            files -> "Agents may make changes and save, export and open files (each asks you)"
            editable -> "Agents may make changes"
            else -> null
        }

    fun of(
        port: Int,
        editable: Boolean,
        files: Boolean,
        activity: McpActivity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        buildList {
            add("MCP server running on 127.0.0.1:$port — ${phaseWord(activity.phase)}")
            permissions(editable, files)?.let(::add)
            if (activity.running > 0) add("${activity.running} running")
            activity.recent.forEach { call ->
                val time = CLOCK.format(Instant.ofEpochMilli(call.startedAtMs).atZone(zone))
                val result =
                    when (call.outcome) {
                        null -> "running"
                        McpCallOutcome.Ok -> "ok"
                        McpCallOutcome.Failed -> "failed"
                        McpCallOutcome.Declined -> "declined"
                    }
                add("$time  ${call.label}  $result")
            }
            add("Click for View › MCP Server…")
        }.joinToString("\n")

    fun description(
        editable: Boolean,
        files: Boolean,
        phase: McpPhase,
    ): String =
        "MCP server, ${phaseWord(phase)}" + (permissions(editable, files)?.let { ", $it" } ?: "") +
            ". Opens the MCP server settings."
}
