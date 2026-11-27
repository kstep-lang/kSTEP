package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.edit.PartSourceResolution
import dev.kstep.desktop.edit.PartSourceResolver
import dev.kstep.desktop.pick.PartRef
import dev.kstep.desktop.pick.SelectionEvent
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.SelectionNotice
import dev.kstep.desktop.pick.VisibilityMachine
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The six tools of ADR-0097 that change the application. Every one of them is behind the permission, is rate limited
 * and works on the text revision the agent has read. They never write a file, never leave the app and never move the
 * camera. They can, however, make the app run a script the agent wrote: that script runs as the user (ADR-0097 D12),
 * therefore a run, and an edit that would start one on its own, is refused unless the user flow sandbox is active. All checks come first, the change comes last, so a failing call changes nothing.
 */
internal object McpActionTools {
    private const val PREFIX = "[Changes the app; requires 'Allow agents to make changes'.] "
    private const val MAX_TEXT_CHARS = 1_048_576
    private const val MAX_OLD_TEXT = 65_536
    private const val MAX_NAMES = 100
    private const val MAX_WAIT_MS = 30_000
    private const val MAX_OCCURRENCE = 9999
    private const val REVISION_LENGTH = 71
    private const val MAX_MISSING = 20

    private val expectedRevision =
        McpArg.StringArg(
            "expectedRevision",
            "The revision from get_script that the change is based on.",
            true,
            REVISION_LENGTH,
            REVISION_LENGTH,
        )

    fun definitions(): List<McpToolDef> =
        listOf(
            McpToolDef(
                name = "kstep_desktop_set_script",
                description =
                    PREFIX + "Replaces the whole editor text. One undo step; the file on disk is not touched and " +
                        "stays unsaved. Fails with STALE_REVISION when the text changed since expectedRevision, and " +
                        "with SANDBOX_UNAVAILABLE when Preview Unsaved Changes would run it without a sandbox.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.StringArg("text", "The new editor text.", true, 0, MAX_TEXT_CHARS),
                            expectedRevision,
                        ),
                    ),
                acting = true,
                actingCall = ::setScript,
            ),
            McpToolDef(
                name = "kstep_desktop_replace_in_script",
                description =
                    PREFIX + "Replaces the one place where oldText occurs in the editor text by newText. Fails with " +
                        "NOT_FOUND or AMBIGUOUS unless oldText occurs exactly once. One undo step; not saved.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.StringArg(
                                "oldText",
                                "The text to replace, exactly once in the editor.",
                                true,
                                1,
                                MAX_OLD_TEXT,
                            ),
                            McpArg.StringArg("newText", "The replacement.", true, 0, MAX_TEXT_CHARS),
                            expectedRevision,
                        ),
                    ),
                acting = true,
                actingCall = ::replaceInScript,
            ),
            McpToolDef(
                name = "kstep_desktop_run",
                description =
                    PREFIX + "Starts a preview run like the menu does (mode saved: the file on disk, unsaved: the " +
                        "editor text; default unsaved when there are unsaved changes). With waitMs the call waits " +
                        "for the end of the run. The script runs as the user, so this is refused with " +
                        "SANDBOX_UNAVAILABLE when no sandbox is active. At most one run per second.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.EnumArg("mode", "Which text to run.", false, listOf("saved", "unsaved")),
                            McpArg.IntArg("waitMs", "Wait this long for the run to end.", false, 0, MAX_WAIT_MS, 0),
                        ),
                    ),
                acting = true,
                actingCall = ::run,
            ),
            McpToolDef(
                name = "kstep_desktop_select",
                description =
                    PREFIX + "Changes the selection of parts in the preview by part name (replace, add, toggle) " +
                        "or clears it. occurrence is the 0-based index of a part among those with the same name, as " +
                        "in get_parts. The camera does not move.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.EnumArg(
                                "mode",
                                "How to change the selection.",
                                true,
                                listOf("replace", "add", "toggle", "clear"),
                            ),
                            McpArg.StringListArg("names", "Part names.", false, 1, MAX_NAMES, 1, McpJson.NAME_MAX),
                            McpArg.IntArg(
                                "occurrence",
                                "Which part of that name, 0-based.",
                                false,
                                0,
                                MAX_OCCURRENCE,
                                null,
                            ),
                        ),
                        crossCheck = { a ->
                            if (a.string("mode") == "clear") !a.has("names") && !a.has("occurrence") else a.has("names")
                        },
                    ),
                acting = true,
                actingCall = ::select,
            ),
            McpToolDef(
                name = "kstep_desktop_set_visibility",
                description =
                    PREFIX + "Hides, shows, isolates or makes transparent parts of the preview (by name, or the " +
                        "current selection when parts is left out), or shows all. hide and show are explicit, not " +
                        "toggles. The camera does not move.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.EnumArg(
                                "action",
                                "What to do.",
                                true,
                                listOf("hide", "show", "isolate", "show_all", "toggle_transparent"),
                            ),
                            McpArg.StringListArg("parts", "Part names.", false, 1, MAX_NAMES, 1, McpJson.NAME_MAX),
                        ),
                        crossCheck = { a -> a.string("action") != "show_all" || !a.has("parts") },
                    ),
                acting = true,
                actingCall = ::setVisibility,
            ),
            McpToolDef(
                name = "kstep_desktop_show_in_script",
                description =
                    PREFIX + "Selects, in the editor, the place in the script where a part is made, like Show in " +
                        "Script does. Needs a script that has been run unchanged. The editor does not take the focus.",
                args = McpArgSpec(listOf(McpArg.StringArg("part", "The part name.", true, 1, McpJson.NAME_MAX))),
                acting = true,
                actingCall = ::showInScript,
            ),
        )

    // ---- shared -------------------------------------------------------------------------------------------------

    private fun portOf(ctx: McpActionContext): McpActionPort =
        ctx.host.port ?: throw McpToolException(McpToolCodes.UI_BUSY)

    /** The permission is judged again inside every block that changes something, so a switch-off wins at once. */
    private fun recheck(ctx: McpActionContext) {
        if (!ctx.allowActions()) throw McpToolException(McpToolCodes.ACTIONS_DISABLED)
    }

    private fun stale(text: String) =
        McpToolException(
            McpToolCodes.STALE_REVISION,
            null,
            buildJsonObject { put("revision", McpAgentText.revisionOf(text)) },
        )

    /** The editor text now, if it is a script without a draft offer and still the one the agent read. */
    private fun captureScript(
        ctx: McpActionContext,
        port: McpActionPort,
        expected: String,
    ): String {
        val c = ctx.host.bridge.onUi { port.editor() }
        val text = checkedText(c)
        if (McpAgentText.revisionOf(text) != expected) throw stale(text)
        return text
    }

    private fun checkedText(c: McpEditorCapture): String {
        if (c.kind == EditorKind.NONE) throw McpToolException(McpToolCodes.NO_FILE)
        if (c.kind == EditorKind.NOTE) throw McpToolException(McpToolCodes.NOT_A_SCRIPT)
        if (c.draftOfferOpen) throw McpToolException(McpToolCodes.DRAFT_OFFER_OPEN)
        return c.text ?: throw McpToolException(McpToolCodes.NO_FILE)
    }

    private fun checkedRevision(a: McpParsedArgs): String {
        val rev = a.string("expectedRevision") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        if (!McpAgentText.isRevision(rev)) throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        return rev
    }

    private fun validText(
        text: String,
        checkChars: Boolean = true,
    ): String =
        when (val v = McpAgentText.validate(text, checkChars)) {
            is McpAgentText.Validation.Ok -> v.text
            McpAgentText.Validation.TooLarge -> throw McpToolException(McpToolCodes.TEXT_TOO_LARGE)
            McpAgentText.Validation.Invalid -> throw McpToolException(McpToolCodes.INVALID_TEXT)
        }

    /** Applies [next] over [current] on the UI thread and builds the answer. */
    private fun apply(
        ctx: McpActionContext,
        port: McpActionPort,
        current: String,
        next: String,
    ): JsonObject {
        val result =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                // With Preview Unsaved Changes on, this edit would start a run by itself (ADR-0097 D12).
                if (port.autoPreviewOn() && !port.runIsSandboxed()) {
                    throw McpToolException(McpToolCodes.SANDBOX_UNAVAILABLE)
                }
                val r = port.applyAgentEdit(current, next)
                if (r == AgentEditResult.APPLIED) port.agentNotice(AgentNoticeKind.EDITED)
                r
            }
        return when (result) {
            AgentEditResult.APPLIED ->
                buildJsonObject {
                    put("changed", true)
                    put("revision", McpAgentText.revisionOf(next))
                    put("dirty", true)
                    put("saved", false)
                }
            AgentEditResult.UNCHANGED ->
                buildJsonObject {
                    put("changed", false)
                    put("revision", McpAgentText.revisionOf(current))
                }
            AgentEditResult.STALE -> throw stale(current)
            AgentEditResult.NOT_EDITING -> throw McpToolException(McpToolCodes.NO_FILE)
            AgentEditResult.DRAFT_OFFER_OPEN -> throw McpToolException(McpToolCodes.DRAFT_OFFER_OPEN)
        }
    }

    // ---- set_script / replace_in_script -------------------------------------------------------------------------

    private fun setScript(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val expected = checkedRevision(a)
        val next = validText(a.string("text") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS))
        val port = portOf(ctx)
        val current = captureScript(ctx, port, expected)
        if (next == current) return unchanged(current)
        return apply(ctx, port, current, next)
    }

    private fun unchanged(current: String): JsonObject =
        buildJsonObject {
            put("changed", false)
            put("revision", McpAgentText.revisionOf(current))
        }

    private fun replaceInScript(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val expected = checkedRevision(a)
        val old = validText(a.string("oldText") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS))
        val new = validText(a.string("newText") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS))
        val port = portOf(ctx)
        val current = captureScript(ctx, port, expected)
        val hits = McpAgentText.findLiteral(current, old)
        if (hits.count == 0) throw McpToolException(McpToolCodes.NOT_FOUND)
        if (hits.count > 1) {
            throw McpToolException(
                McpToolCodes.AMBIGUOUS,
                null,
                buildJsonObject {
                    put("count", hits.count)
                    put("countCapped", hits.capped)
                },
            )
        }
        val result = current.substring(0, hits.first) + new + current.substring(hits.first + old.length)
        val next = validText(result, checkChars = false)
        if (next == current) return unchanged(current)
        return apply(ctx, port, current, next)
    }

    // ---- run ----------------------------------------------------------------------------------------------------

    private class Started(
        val generation: Long,
        val mode: McpRunMode,
        val text: String?,
    )

    private fun spacingGuard(ctx: McpActionContext) {
        ctx.runSpacing.check(ctx.clock())?.let { retry ->
            throw McpToolException(
                McpToolCodes.RATE_LIMITED,
                "a run was started less than a second ago",
                buildJsonObject { put("retryAfterMs", retry) },
            )
        }
    }

    private fun run(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val waitMs = a.int("waitMs") ?: 0
        val requested =
            when (a.string("mode")) {
                "saved" -> McpRunMode.SAVED
                "unsaved" -> McpRunMode.UNSAVED
                else -> null
            }
        spacingGuard(ctx)
        if (waitMs > 0 && !ctx.waiter.tryEnter()) {
            throw McpToolException(McpToolCodes.RATE_LIMITED, "another call is already waiting for a run")
        }
        try {
            val port = portOf(ctx)
            val started =
                ctx.host.bridge.onUiMutating {
                    recheck(ctx)
                    // The check outside is only a fast path; this one runs on the UI thread, so check and mark cannot race.
                    spacingGuard(ctx)
                    val editor = port.editor()
                    val mode = requested ?: if (editor.dirty) McpRunMode.UNSAVED else McpRunMode.SAVED
                    when (port.runGate(mode)) {
                        McpRunGate.ALLOWED -> Unit
                        McpRunGate.NO_FILE -> throw McpToolException(McpToolCodes.NO_FILE)
                        McpRunGate.NOT_A_SCRIPT -> throw McpToolException(McpToolCodes.NOT_A_SCRIPT)
                        McpRunGate.DRAFT_OFFER_OPEN -> throw McpToolException(McpToolCodes.DRAFT_OFFER_OPEN)
                        McpRunGate.UNAVAILABLE ->
                            throw McpToolException(McpToolCodes.NO_FILE, "this kind of run is not available now")
                    }
                    // A run executes the script with the rights of the user: never without the sandbox (ADR-0097 D12).
                    if (!port.runIsSandboxed()) throw McpToolException(McpToolCodes.SANDBOX_UNAVAILABLE)
                    val generation = port.startRun(mode)
                    ctx.runSpacing.mark(ctx.clock())
                    port.agentNotice(AgentNoticeKind.RUN_STARTED)
                    Started(generation, mode, editor.text)
                }
            val outcome = if (waitMs > 0) port.awaitRun(started.generation, waitMs.toLong()) else null
            return buildJsonObject {
                put(
                    "status",
                    when {
                        waitMs == 0 -> "started"
                        outcome == null -> "timeout"
                        outcome is McpRunOutcome.Failed -> "failed"
                        else -> "finished"
                    },
                )
                put("mode", if (started.mode == McpRunMode.SAVED) "saved" else "unsaved")
                put("revision", started.text?.let { JsonPrimitive(McpAgentText.revisionOf(it)) } ?: JsonNull)
                if (outcome is McpRunOutcome.Failed) put("errorCount", outcome.errorCount)
            }
        } finally {
            if (waitMs > 0) ctx.waiter.leave()
        }
    }

    // ---- select -------------------------------------------------------------------------------------------------

    private fun missingError(missing: List<String>): McpToolException =
        McpToolException(
            McpToolCodes.NOT_FOUND,
            null,
            buildJsonObject {
                put(
                    "missing",
                    buildJsonArray { missing.take(MAX_MISSING).forEach { add(McpJson.text(it, McpJson.NAME_MAX)) } },
                )
                put("missingTotal", missing.size)
            },
        )

    private fun noModel(names: List<String>): McpToolException =
        McpToolException(
            McpToolCodes.NOT_FOUND,
            "no model is shown",
            buildJsonObject {
                put(
                    "missing",
                    buildJsonArray { names.take(MAX_MISSING).forEach { add(McpJson.text(it, McpJson.NAME_MAX)) } },
                )
                put("missingTotal", names.size)
            },
        )

    private fun select(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val mode = a.string("mode") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        val names = (a.strings("names") ?: emptyList()).distinct()
        val occurrence = a.int("occurrence")
        val port = portOf(ctx)

        class Done(
            val selected: Int,
            val truncated: Int?,
            val text: String?,
        )
        val done =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                val current = port.selection()
                val editorText = port.editor().text
                if (mode == "clear") {
                    val cleared =
                        SelectionMachine.reduce(current, SelectionEvent.Escape).let {
                            if (it.selected != null || it.selectedGroup != null) {
                                SelectionMachine.reduce(it, SelectionEvent.SelectParts(emptyList(), 0))
                            } else {
                                it
                            }
                        }
                    port.setSelection(cleared)
                    return@onUiMutating Done(0, null, editorText)
                }
                val scene = port.scene() ?: throw noModel(names)
                val table = scene.parts
                val occurrences = table.occurrenceArray()
                val byName = HashMap<String, MutableList<Int>>()
                for (i in table.parts.indices) byName.getOrPut(table.parts[i].name) { ArrayList() } += i
                val missing = ArrayList<String>()
                val picked = ArrayList<Int>()
                for (name in names) {
                    val all = byName[name]
                    if (all == null) {
                        missing += name
                        continue
                    }
                    if (occurrence == null) {
                        picked += all
                    } else {
                        val one = all.firstOrNull { occurrences[it] == occurrence }
                        if (one == null) missing += name else picked += one
                    }
                }
                if (missing.isNotEmpty()) throw missingError(missing)
                val refs = picked.distinct().map { PartRef(table.parts[it].name, occurrences[it]) }
                var notice: SelectionNotice? = null
                val next =
                    when (mode) {
                        "replace" -> {
                            val (s, n) =
                                SelectionMachine.reduceWithNotice(
                                    current,
                                    SelectionEvent.SelectParts(refs, refs.size),
                                )
                            notice = n
                            s
                        }
                        "add" -> {
                            val base = baseRefs(current, scene)
                            val all = (base + refs).distinct()
                            val (s, n) =
                                SelectionMachine.reduceWithNotice(
                                    current,
                                    SelectionEvent.SelectParts(all, all.size),
                                )
                            notice = n
                            s
                        }
                        else -> {
                            var s = current
                            for ((idx, ref) in picked.distinct().zip(refs)) {
                                val group = if (s.selectedGroup != null) baseRefs(s, scene) else emptyList()
                                val (n, nn) =
                                    SelectionMachine.reduceWithNotice(
                                        s,
                                        SelectionEvent.TogglePart(idx, ref.name, ref.occurrence, group, group.size),
                                    )
                                s = n
                                if (nn != null) notice = nn
                            }
                            s
                        }
                    }
                port.setSelection(next)
                val count = SelectionMachine.partIndices(next, scene.tree, table).size
                Done(count, (notice as? SelectionNotice.Truncated)?.total, editorText)
            }
        return buildJsonObject {
            put("selected", done.selected)
            if (done.truncated != null) put("truncated", done.truncated)
            put("revision", done.text?.let { JsonPrimitive(McpAgentText.revisionOf(it)) } ?: JsonNull)
        }
    }

    /** What is selected now as references; a selected group is resolved to its parts. */
    private fun baseRefs(
        s: dev.kstep.desktop.pick.SelectionState,
        scene: McpActionScene,
    ): List<PartRef> {
        val key = s.selectedGroup ?: return s.parts
        val tree = scene.tree ?: return emptyList()
        val node = tree.nodeOfKey(key)
        return SelectionMachine.groupRefs(tree, node, scene.parts)
    }

    // ---- set_visibility -----------------------------------------------------------------------------------------

    private fun setVisibility(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val action = a.string("action") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        val names = a.strings("parts")
        val port = portOf(ctx)

        class Done(
            val changed: Boolean,
            val hidden: Int,
            val transparent: Int,
            val text: String?,
        )
        val done =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                val editorText = port.editor().text
                val scene = port.scene()
                val current = port.visibility()
                if (scene == null) {
                    if (action == "show_all") return@onUiMutating Done(false, 0, 0, editorText)
                    throw noModel(names ?: emptyList())
                }
                val table = scene.parts
                val indices: IntArray =
                    if (action == "show_all") {
                        IntArray(0)
                    } else if (names == null) {
                        SelectionMachine
                            .partIndices(port.selection(), scene.tree, table)
                            .also {
                                if (it.isEmpty()) {
                                    throw McpToolException(McpToolCodes.INVALID_ARGUMENTS, "nothing is selected")
                                }
                            }
                    } else {
                        val wanted = names.toHashSet()
                        val found = HashSet<String>()
                        val list = ArrayList<Int>()
                        for (i in table.parts.indices) {
                            val n = table.parts[i].name
                            if (n in wanted) {
                                found += n
                                list += i
                            }
                        }
                        val missing = names.filter { it !in found }.distinct()
                        if (missing.isNotEmpty()) throw missingError(missing)
                        list.toIntArray()
                    }
                val vs = scene.visibilityScene
                val next =
                    when (action) {
                        "hide" -> VisibilityMachine.hideParts(current, vs, indices)
                        "show" -> VisibilityMachine.showParts(current, vs, indices)
                        "isolate" -> VisibilityMachine.isolateParts(current, vs, indices)
                        "toggle_transparent" -> VisibilityMachine.toggleTransparentParts(current, vs, indices)
                        else -> VisibilityMachine.showAll(current)
                    }
                val changed = next !== current
                if (changed) port.setVisibility(next)
                val counts = VisibilityMachine.counts(next, vs)
                Done(changed, counts.hidden, counts.transparent, editorText)
            }
        return buildJsonObject {
            put("changed", done.changed)
            put("hidden", done.hidden)
            put("transparent", done.transparent)
            put("revision", done.text?.let { JsonPrimitive(McpAgentText.revisionOf(it)) } ?: JsonNull)
        }
    }

    // ---- show_in_script -----------------------------------------------------------------------------------------

    private fun showInScript(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val part = a.string("part") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        val port = portOf(ctx)
        val target = ctx.host.bridge.onUi { port.showInScriptTarget(part) }
        val ready =
            when (target) {
                McpShowTarget.BufferNotRun ->
                    throw McpToolException(McpToolCodes.BUFFER_NOT_RUN, "Run the script first")
                McpShowTarget.NotFound -> throw McpToolException(McpToolCodes.NOT_FOUND)
                McpShowTarget.NotAScript -> throw McpToolException(McpToolCodes.NOT_A_SCRIPT)
                McpShowTarget.NoFile -> throw McpToolException(McpToolCodes.NO_FILE)
                is McpShowTarget.Ready -> target
            }
        val resolution =
            PartSourceResolver.resolve(ready.text, part, ready.sourceLine, ready.usable, ready.sameCount)
        val range: Pair<Int, Int> =
            when (resolution) {
                is PartSourceResolution.Name -> resolution.start to resolution.end
                is PartSourceResolution.Line -> resolution.start to resolution.end
                is PartSourceResolution.Heuristic ->
                    throw McpToolException(McpToolCodes.NOT_FOUND, "source not located")
            }
        val ok =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                port.selectInEditor(range.first, range.second, ready.editorRevision)
            }
        if (!ok) throw McpToolException(McpToolCodes.STALE_REVISION)
        val (line, column) = CaretMapper.lineColumn(ready.text, range.first)
        return buildJsonObject {
            put("line", line)
            put("column", column)
            put("revision", McpAgentText.revisionOf(ready.text))
        }
    }
}
