package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.LineIndex
import dev.kstep.desktop.edit.SafeRegex
import dev.kstep.desktop.edit.SearchBarText
import dev.kstep.desktop.edit.SearchPattern
import dev.kstep.desktop.edit.TextSearch
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.GroupInspection
import dev.kstep.desktop.pick.MultiInspection
import dev.kstep.desktop.pick.SelectionMachine
import dev.kstep.desktop.pick.Visibility
import dev.kstep.desktop.run.NameSanitizer
import dev.kstep.desktop.run.Severity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** JSON helpers for untrusted text and for numbers that may not be finite (ADR-0096 D6). */
internal object McpJson {
    const val NAME_MAX = 256
    const val MESSAGE_MAX = 1000

    /** `NaN` and the infinities are not JSON: they become `null`. */
    fun num(d: Double): JsonElement = if (d.isFinite()) JsonPrimitive(d) else JsonNull

    fun vec(a: DoubleArray?): JsonElement = if (a == null || a.size < 3) JsonNull else JsonArray(List(3) { num(a[it]) })

    /** `max - min` per axis. */
    fun extent(
        min: DoubleArray,
        max: DoubleArray,
    ): JsonElement = JsonArray(List(3) { num(max[it] - min[it]) })

    /** Display text: ANSI removed, control and bidi characters replaced (the same cleaning as the error bar). */
    fun clean(s: String): String = NameSanitizer.cleanText(s)

    /** At most [max] UTF-16 units, never inside a surrogate pair, `…` appended when cut. */
    fun clip(
        s: String,
        max: Int,
    ): String {
        if (s.length <= max) return s
        var end = max
        if (end > 0 && s[end - 1].isHighSurrogate() && s[end].isLowSurrogate()) end--
        return s.substring(0, end) + "…"
    }

    fun text(
        s: String,
        max: Int,
    ): JsonElement = JsonPrimitive(clip(clean(s), max))

    fun intOrNull(i: Int?): JsonElement = if (i == null) JsonNull else JsonPrimitive(i)

    /** `#rrggbb` only. */
    fun color(s: String): JsonElement =
        if (s.length == COLOR_LENGTH && s[0] == '#' && (1 until COLOR_LENGTH).all { HEX_DIGITS.indexOf(s[it]) >= 0 }) {
            JsonPrimitive(s)
        } else {
            JsonNull
        }

    private const val COLOR_LENGTH = 7
    private const val HEX_DIGITS = "0123456789abcdefABCDEF"
}

/** The six read tools of ADR-0096. Pure functions of one snapshot; nothing here touches the UI. */
internal object McpReadTools {
    private const val MAX_ERRORS = 200
    private const val MAX_PARTS = 4096
    private const val MAX_NODES = 8192

    // The parser accepts nodes at depth 0..64, i.e. 65 levels (the root is level 1); see AssemblyTreeParser.
    private const val MAX_DEPTH = 65
    private const val MAX_MATCHES = 200
    private const val DEFAULT_MATCHES = 50
    private const val CONTEXT_CHARS = 200
    private const val CONTEXT_BEFORE = 60
    private const val NANOS_PER_MS = 1_000_000L

    fun definitions(limits: McpToolLimits): List<McpToolDef> =
        listOf(
            McpToolDef(
                name = "kstep_desktop_get_errors",
                description =
                    "Lists the problems of the last preview run as the error bar shows them (code, message, line, " +
                        "column, severity), where the run's text came from, and whether the editor text changed " +
                        "since. Use it after editing to see whether the script evaluates. Columns count UTF-16 " +
                        "units. Read only.",
                args =
                    McpArgSpec(
                        listOf(McpArg.IntArg("limit", "Most problems to return.", false, 1, MAX_ERRORS, MAX_ERRORS)),
                    ),
            ) { a, s, v -> errors(a, s, v.structured(), limits) },
            McpToolDef(
                name = "kstep_desktop_get_parts",
                description =
                    "Lists the parts of the model shown in the preview: name, which part of that name " +
                        "(occurrence), triangles, size and position in mm, color, script line, and whether the " +
                        "part is hidden, transparent or selected. Use offset to read on when truncated is true. " +
                        "Read only.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.IntArg("offset", "Index of the first part to return.", false, 0, MAX_PARTS - 1, 0),
                            McpArg.IntArg("limit", "Most parts to return.", false, 1, MAX_PARTS, MAX_PARTS),
                        ),
                    ),
            ) { a, s, v -> parts(a, s, v.structured(), limits) },
            McpToolDef(
                name = "kstep_desktop_get_assembly_tree",
                description =
                    "Reads the assembly hierarchy of the model shown in the preview: groups and parts as a nested " +
                        "tree (pre-order). A part node has partIndex, which is the index in get_parts. Read only.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.IntArg(
                                "maxDepth",
                                "Most levels to return, the root is level 1.",
                                false,
                                1,
                                MAX_DEPTH,
                                MAX_DEPTH,
                            ),
                            McpArg.IntArg("maxNodes", "Most nodes to return.", false, 1, MAX_NODES, MAX_NODES),
                        ),
                    ),
            ) { a, s, v -> tree(a, s, v.structured(), limits) },
            McpToolDef(
                name = "kstep_desktop_get_selection",
                description =
                    "Reads what is selected in the preview: one part, several parts or an assembly group, with " +
                        "size, box and the names. A hover is not a selection. Read only.",
                args = McpArgSpec(),
            ) { _, s, _ -> selection(s) },
            McpToolDef(
                name = "kstep_desktop_get_view",
                description =
                    "Reads how the preview is set: how many parts are hidden or transparent, the opacity of " +
                        "transparent parts, and the settings soft wrap and run unsaved. Isolate is not a state of " +
                        "its own: it makes the other parts transparent. Read only.",
                args = McpArgSpec(),
            ) { _, s, _ -> view(s) },
            McpToolDef(
                name = "kstep_desktop_find_in_script",
                description =
                    "Searches the editor text like the find bar does (a literal, or the safe regex subset) and " +
                        "returns line, column (1-based, UTF-16 units), length and the line around each match. " +
                        "Read only.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.StringArg("query", "What to search for.", true, 1, TextSearch.MAX_QUERY_CHARS),
                            McpArg.BoolArg("regex", "Treat query as a pattern of the safe regex subset."),
                            McpArg.BoolArg("wholeWord", "Match whole words only."),
                            McpArg.BoolArg("matchCase", "Match upper and lower case exactly."),
                            McpArg.IntArg("limit", "Most matches to return.", false, 1, MAX_MATCHES, DEFAULT_MATCHES),
                        ),
                    ),
            ) { a, s, v -> find(a, s, v.structured(), limits) },
        )

    private fun String.structured() = this == McpProtocol.STRUCTURED_VERSION

    private fun runName(r: RunKind) =
        when (r) {
            RunKind.IDLE -> "idle"
            RunKind.RUNNING -> "running"
            RunKind.OK -> "ok"
            RunKind.ERROR -> "error"
            RunKind.UNAVAILABLE -> "unavailable"
        }

    private fun originName(o: PreviewKind?): JsonElement =
        when (o) {
            PreviewKind.SAVED_FILE -> JsonPrimitive("savedFile")
            PreviewKind.UNSAVED_BUFFER -> JsonPrimitive("unsavedBuffer")
            null -> JsonNull
        }

    // ---- get_errors -------------------------------------------------------------------------------------------

    private fun errors(
        a: McpParsedArgs,
        s: DesktopStateSnapshot,
        structured: Boolean,
        limits: McpToolLimits,
    ): JsonObject {
        val limit = a.int("limit") ?: MAX_ERRORS
        val report = s.runReport
        val all = report?.errors ?: emptyList()
        val entries =
            all.take(limit).map { e ->
                buildJsonObject {
                    put("code", McpJson.text(e.code, McpJson.MESSAGE_MAX))
                    put("message", McpJson.text(e.message, McpJson.MESSAGE_MAX))
                    put("line", McpJson.intOrNull(e.line))
                    put("column", McpJson.intOrNull(e.column))
                    put(
                        "severity",
                        if ((report?.severity ?: Severity.ERROR) ==
                            Severity.WARNING
                        ) {
                            "warning"
                        } else {
                            "error"
                        },
                    )
                }
            }
        val edited: Boolean? =
            when {
                report == null || s.editorKind == EditorKind.NONE || s.editorText == null -> null
                report.origin == PreviewKind.UNSAVED_BUFFER -> report.bufferText?.let { it != s.editorText }
                else -> s.dirty
            }
        return McpResponseBudget.fit(entries.size, structured, limits.maxResponseBytes) { n, cut ->
            buildJsonObject {
                put("snapshotRevision", s.revision)
                put("run", runName(s.run))
                put("origin", originName(report?.origin ?: s.previewOrigin))
                put("editedSinceRun", edited?.let { JsonPrimitive(it) } ?: JsonNull)
                put("errors", JsonArray(entries.subList(0, n)))
                put("total", all.size)
                put("truncated", cut || all.size > limit)
            }
        }
    }

    // ---- get_parts --------------------------------------------------------------------------------------------

    private fun parts(
        a: McpParsedArgs,
        s: DesktopStateSnapshot,
        structured: Boolean,
        limits: McpToolLimits,
    ): JsonObject {
        val offset = a.int("offset") ?: 0
        val limit = a.int("limit") ?: MAX_PARTS
        val scene = s.scene
        if (scene == null) {
            return buildJsonObject {
                put("snapshotRevision", s.revision)
                put("model", JsonNull)
                put("parts", JsonArray(emptyList()))
                put("total", 0)
                put("offset", offset)
                put("truncated", false)
            }
        }
        val table = scene.parts
        val total = table.parts.size
        val occurrences = table.occurrenceArray()
        val keys = scene.visibilityScene.partKey
        val selected = BooleanArray(total)
        s.selection?.let { sel ->
            for (i in SelectionMachine.partIndices(sel, scene.tree, table)) {
                if (i in
                    selected.indices
                ) {
                    selected[i] = true
                }
            }
        }
        val first = minOf(offset, total)
        val last = minOf(total, first + limit)
        val entries =
            (first until last).map { i ->
                val p = table.parts[i]
                val mode = s.visibility.leafStates[keys[i]]
                buildJsonObject {
                    put("index", i)
                    put("name", McpJson.text(p.name, McpJson.NAME_MAX))
                    put("occurrence", occurrences[i])
                    put("triangles", p.triangleCount)
                    put("size", McpJson.extent(p.bboxMin, p.bboxMax))
                    put("centroid", McpJson.vec(p.centroid))
                    put("position", McpJson.vec(p.placement.origin))
                    put("color", McpJson.color(p.colorHex))
                    put("sourceLine", McpJson.intOrNull(p.sourceLine))
                    put("hidden", mode == Visibility.Hidden)
                    put("transparent", mode == Visibility.Transparent)
                    put("selected", selected[i])
                }
            }
        val modelInfo =
            buildJsonObject {
                put("generation", scene.generation)
                put("partCount", total)
            }
        return McpResponseBudget.fit(entries.size, structured, limits.maxResponseBytes) { n, cut ->
            buildJsonObject {
                put("snapshotRevision", s.revision)
                put("model", modelInfo)
                put("parts", JsonArray(entries.subList(0, n)))
                put("total", total)
                put("offset", offset)
                put("truncated", cut || last < total)
            }
        }
    }

    // ---- get_assembly_tree ------------------------------------------------------------------------------------

    private fun tree(
        a: McpParsedArgs,
        s: DesktopStateSnapshot,
        structured: Boolean,
        limits: McpToolLimits,
    ): JsonObject {
        val maxDepth = a.int("maxDepth") ?: MAX_DEPTH
        val maxNodes = a.int("maxNodes") ?: MAX_NODES
        val scene = s.scene
        val tree = scene?.tree
        if (scene == null || tree == null) {
            return buildJsonObject {
                put("snapshotRevision", s.revision)
                put("tree", JsonNull)
                put("reason", if (scene == null) "noModel" else "noTree")
                put("totalNodes", 0)
                put("emittedNodes", 0)
                put("truncated", false)
                put("depthLimited", false)
            }
        }
        // Pre-order nodes within the depth limit; a prefix of them is always a valid subtree.
        val included = ArrayList<Int>()
        val omittedChildren = IntArray(tree.size)
        for (n in 0 until tree.size) {
            if (tree.depth(n) < maxDepth) {
                if (included.size < maxNodes) included += n
            } else if (tree.depth(n) == maxDepth) {
                omittedChildren[tree.parentOf(n)]++
            }
        }
        val depthLimited = tree.size > included.size && omittedChildren.any { it > 0 }
        val keptByDepth = (0 until tree.size).count { tree.depth(it) < maxDepth }
        return McpResponseBudget.fit(included.size, structured, limits.maxResponseBytes) { n, cut ->
            buildJsonObject {
                put("snapshotRevision", s.revision)
                put("tree", assemble(tree, included, n, omittedChildren))
                put("totalNodes", tree.size)
                put("emittedNodes", n)
                put("truncated", cut || included.size < keptByDepth)
                put("depthLimited", depthLimited)
            }
        }
    }

    /** The first [n] of the pre-order nodes [included] as nested objects, built from the back, without recursion. */
    private fun assemble(
        tree: AssemblyTree,
        included: List<Int>,
        n: Int,
        omitted: IntArray,
    ): JsonElement {
        val position = HashMap<Int, Int>(n * 2)
        for (k in 0 until n) position[included[k]] = k
        val children = arrayOfNulls<ArrayList<JsonObject>>(n)
        var root: JsonObject? = null
        for (k in n - 1 downTo 0) {
            val node = included[k]
            val kids = children[k]?.also { it.reverse() } ?: emptyList()
            val obj =
                buildJsonObject {
                    put("name", McpJson.text(tree.name(node), McpJson.NAME_MAX))
                    put("kind", if (tree.isGroup(node)) "group" else "part")
                    put("occurrence", tree.occurrence(node))
                    if (tree.hasDuplicateSiblingName(node)) put("duplicateSuffix", tree.duplicateSuffix(node).trim())
                    if (tree.isGroup(
                            node,
                        )
                    ) {
                        put("partCount", tree.leafCount(node))
                    } else {
                        put("partIndex", tree.partOf(node))
                    }
                    if (omitted[node] > 0) put("childrenOmitted", omitted[node])
                    put("children", JsonArray(kids))
                }
            val parentPos = position[tree.parentOf(node)]
            if (parentPos != null) {
                (children[parentPos] ?: ArrayList<JsonObject>().also { children[parentPos] = it }).add(obj)
            } else {
                root = obj
            }
        }
        return root ?: JsonNull
    }

    // ---- get_selection ----------------------------------------------------------------------------------------

    private fun selection(s: DesktopStateSnapshot): JsonObject {
        val body = selectionBody(s)
        return buildJsonObject {
            put("snapshotRevision", s.revision)
            put("selection", body ?: JsonNull)
        }
    }

    private fun namesOf(
        s: DesktopStateSnapshot,
        indices: List<Int>,
        total: Int,
    ): Pair<JsonArray, Int> {
        val scene = s.scene ?: return JsonArray(emptyList()) to 0
        val occurrences = scene.parts.occurrenceArray()
        val shown =
            indices.take(MultiInspection.NAMES_SHOWN).map { i ->
                buildJsonObject {
                    put("name", McpJson.text(scene.parts.parts[i].name, McpJson.NAME_MAX))
                    put("occurrence", occurrences[i])
                }
            }
        return JsonArray(shown) to maxOf(0, total - shown.size)
    }

    private fun selectionBody(s: DesktopStateSnapshot): JsonObject? {
        val scene = s.scene ?: return null
        val sel = s.selection ?: return null
        val table = scene.parts
        val tree = scene.tree
        if (sel.selectedGroup != null) {
            if (tree == null) return null
            val node = tree.nodeOfKey(sel.selectedGroup).takeIf { it >= 0 } ?: return null
            val info = GroupInspection.of(tree, node, table) ?: return null
            val indices = (tree.firstPart(node)..tree.lastPart(node)).take(MultiInspection.NAMES_SHOWN)
            val (names, more) = namesOf(s, indices, info.partCount)
            return buildJsonObject {
                put("kind", "group")
                put("name", McpJson.text(info.name + tree.duplicateSuffix(node), McpJson.NAME_MAX))
                put("path", McpJson.text(tree.displayPath(node), McpJson.MESSAGE_MAX))
                put("partCount", info.partCount)
                put("triangles", info.triangleCount)
                put("size", McpJson.extent(info.bboxMin, info.bboxMax))
                put("bboxMin", McpJson.vec(info.bboxMin))
                put("bboxMax", McpJson.vec(info.bboxMax))
                put("center", McpJson.vec(info.center))
                put("names", names)
                put("moreNames", more)
            }
        }
        val indices = SelectionMachine.partIndices(sel, tree, table)
        if (indices.isEmpty()) return null
        if (indices.size == 1) {
            val i = indices[0]
            val p: PartInfo = table.parts[i]
            return buildJsonObject {
                put("kind", "part")
                put("index", i)
                put("name", McpJson.text(p.name, McpJson.NAME_MAX))
                put("occurrence", table.occurrenceArray()[i])
                put("triangles", p.triangleCount)
                put("size", McpJson.extent(p.bboxMin, p.bboxMax))
                put("bboxMin", McpJson.vec(p.bboxMin))
                put("bboxMax", McpJson.vec(p.bboxMax))
                put("centroid", McpJson.vec(p.centroid))
                put("position", McpJson.vec(p.placement.origin))
                put("color", McpJson.color(p.colorHex))
                put("sourceLine", McpJson.intOrNull(p.sourceLine))
            }
        }
        val info = MultiInspection.of(indices, table) ?: return null
        val (names, more) = namesOf(s, indices.toList(), info.partCount)
        return buildJsonObject {
            put("kind", "parts")
            put("partCount", info.partCount)
            put("triangles", info.triangleCount)
            put("size", McpJson.extent(info.bboxMin, info.bboxMax))
            put("bboxMin", McpJson.vec(info.bboxMin))
            put("bboxMax", McpJson.vec(info.bboxMax))
            put("names", names)
            put("moreNames", more)
        }
    }

    // ---- get_view ---------------------------------------------------------------------------------------------

    private fun view(s: DesktopStateSnapshot): JsonObject {
        val scene = s.scene
        var hidden = 0
        var transparent = 0
        if (scene != null) {
            for (key in scene.visibilityScene.partKey) {
                when (s.visibility.leafStates[key]) {
                    Visibility.Hidden -> hidden++
                    Visibility.Transparent -> transparent++
                    else -> Unit
                }
            }
        }
        return buildJsonObject {
            put("snapshotRevision", s.revision)
            put("model", scene != null)
            put("partCount", McpJson.intOrNull(scene?.parts?.parts?.size))
            put("hiddenCount", hidden)
            put("transparentCount", transparent)
            put("opacityPercent", s.visibility.alphaStep * OPACITY_STEP_PERCENT)
            put("previewOrigin", originName(s.previewOrigin))
            put(
                "settings",
                buildJsonObject {
                    put("softWrap", s.viewSettings.softWrap)
                    put("previewUnsaved", s.viewSettings.previewUnsaved)
                },
            )
        }
    }

    private const val OPACITY_STEP_PERCENT = 5

    // ---- find_in_script ---------------------------------------------------------------------------------------

    private fun find(
        a: McpParsedArgs,
        s: DesktopStateSnapshot,
        structured: Boolean,
        limits: McpToolLimits,
    ): JsonObject {
        val text = s.editorText
        if (s.editorKind == EditorKind.NONE || text == null) throw McpToolException(McpToolCodes.NO_FILE)
        if (text.length > SafeRegex.MAX_TEXT_CHARS) throw McpToolException(McpToolCodes.TOO_LARGE)
        val query = a.string("query") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        val limit = a.int("limit") ?: DEFAULT_MATCHES
        val pattern =
            when (val c = SearchPattern.compile(query, a.bool("regex"), a.bool("matchCase"), a.bool("wholeWord"))) {
                is SearchPattern.Compile.Ok -> c.pattern
                is SearchPattern.Compile.Rejected ->
                    throw McpToolException(
                        McpToolCodes.INVALID_PATTERN,
                        SearchBarText.patternError(c.reason, c.escapeChar),
                    )
            }
        val deadline = System.nanoTime() + limits.searchTimeoutMs * NANOS_PER_MS
        var timedOut = false
        val result =
            TextSearch.find(text, pattern, limit + 1, {
                (System.nanoTime() < deadline).also { if (!it) timedOut = true }
            })
        if (timedOut) throw McpToolException(McpToolCodes.SEARCH_TIMEOUT)
        if (result.limit == TextSearch.Limit.TEXT_TOO_LARGE) throw McpToolException(McpToolCodes.TOO_LARGE)

        val index = LineIndex(text)
        val shown = minOf(result.count, limit)
        val entries =
            (0 until shown).map { i ->
                val start = result.starts[i]
                val line = index.lineOf(start)
                val lineStart = index.lineStart(line)
                var from = maxOf(lineStart, start - CONTEXT_BEFORE)
                if (from > lineStart && text[from].isLowSurrogate() && text[from - 1].isHighSurrogate()) from--
                val lineEnd = index.lineEnd(line)
                var to = minOf(lineEnd, from + CONTEXT_CHARS)
                if (to < lineEnd && to > from && text[to - 1].isHighSurrogate() && text[to].isLowSurrogate()) to--
                buildJsonObject {
                    put("line", line)
                    put("column", start - lineStart + 1)
                    put("length", result.lengthOf(i))
                    put("text", McpJson.clean(text.substring(from, to)))
                    put("contextStartColumn", from - lineStart + 1)
                }
            }
        val stoppedBy =
            when {
                result.limit == TextSearch.Limit.STEP_BUDGET -> "stepBudget"
                result.count > limit -> "limit"
                else -> null
            }
        return McpResponseBudget.fit(entries.size, structured, limits.maxResponseBytes) { n, cut ->
            buildJsonObject {
                put("snapshotRevision", s.revision)
                put("kind", if (s.editorKind == EditorKind.NOTE) "note" else "script")
                put("matches", JsonArray(entries.subList(0, n)))
                put("truncated", cut || stoppedBy != null)
                put(
                    "stoppedBy",
                    if (cut &&
                        stoppedBy == null
                    ) {
                        JsonPrimitive("responseSize")
                    } else {
                        stoppedBy?.let { JsonPrimitive(it) }
                            ?: JsonNull
                    },
                )
            }
        }
    }
}
