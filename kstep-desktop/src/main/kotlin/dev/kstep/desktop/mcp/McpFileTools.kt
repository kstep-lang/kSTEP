package dev.kstep.desktop.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The file tools of ADR-0098: save the open script, list and open Recent scripts, export to the remembered folder. All
 * of them need both permissions (changes and files). Everything that touches a file asks the user first, through the
 * confirmation gate, and runs only if the buffer is still the one that was shown. An agent never names a path, a folder
 * or a file other than a Recent entry it read from the list; the answers contain names, never paths.
 */
internal object McpFileTools {
    private const val PREFIX =
        "[Changes files; requires 'Allow agents to make changes' and 'Allow agents to save and export files'.] "
    private const val REVISION_LENGTH = 71
    private const val NAME_ARG_MAX = 255
    private const val MAX_RECENT_INDEX = 9
    private const val WAIT_MS = 30_000L
    private const val FILE_NAME_CLIP = 80
    private const val FOLDER_CLIP = 60
    private const val SCOPE_CLIP = 120
    private const val BIG_CHANGE = "large change"

    private val expectedRevision =
        McpArg.StringArg(
            "expectedRevision",
            "The revision from get_script that the action is based on.",
            true,
            REVISION_LENGTH,
            REVISION_LENGTH,
        )

    /** The reserved name of the screenshot tool: it answers `NOT_AVAILABLE` and is not listed (ADR-0098 D9). */
    const val SCREENSHOT = "kstep_desktop_screenshot"

    fun definitions(): List<McpToolDef> =
        listOf(
            McpToolDef(
                name = "kstep_desktop_save",
                description =
                    PREFIX + "Saves the open script to its own file after the user has allowed it in a dialog. " +
                        "Nothing is asked when there are no unsaved changes. Fails with STALE_REVISION when the " +
                        "text changed since expectedRevision, DENIED or CONFIRMATION_TIMEOUT when the user does not " +
                        "agree (do not retry at once; ask the user), SAVE_FAILED on a conflict with the disk.",
                args = McpArgSpec(listOf(expectedRevision)),
                acting = true,
                files = true,
                actingCall = ::save,
            ),
            McpToolDef(
                name = "kstep_desktop_list_recent",
                description =
                    PREFIX + "Lists the names of the recently opened scripts (index and file name, never a path). " +
                        "Asks nothing.",
                args = McpArgSpec(),
                acting = true,
                files = true,
                actingCall = ::listRecent,
            ),
            McpToolDef(
                name = "kstep_desktop_open_recent",
                description =
                    PREFIX + "Opens one of the recent scripts, by index or by file name from list_recent, after the " +
                        "user has allowed it. Refused with UNSAVED_CHANGES while the editor has unsaved changes " +
                        "(save first) and with NOT_A_SCRIPT while a folder of notes is open.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.IntArg("index", "Index in list_recent.", false, 0, MAX_RECENT_INDEX, null),
                            McpArg.StringArg("name", "File name from list_recent.", false, 1, NAME_ARG_MAX),
                        ),
                        crossCheck = { a -> a.has("index") != a.has("name") },
                    ),
                acting = true,
                files = true,
                actingCall = ::openRecent,
            ),
            McpToolDef(
                name = "kstep_desktop_export",
                description =
                    PREFIX + "Exports the open, saved script in the format step, glb, stl or 3mf to the folder the " +
                        "user last exported to, as a NEW file (an existing file is never replaced; a free name with " +
                        "-2, -3 is chosen), after the user has allowed it. Without a remembered folder it fails " +
                        "with NOT_AVAILABLE. Fails with UNSAVED_CHANGES while there are unsaved changes.",
                args =
                    McpArgSpec(
                        listOf(
                            McpArg.EnumArg("format", "The export format.", true, McpExportFormat.entries.map { it.id }),
                            McpArg.StringArg(
                                "name",
                                "A file name without a folder (optional).",
                                false,
                                1,
                                NAME_ARG_MAX,
                            ),
                            expectedRevision,
                        ),
                    ),
                acting = true,
                files = true,
                actingCall = ::export,
            ),
        )

    // ---- shared -------------------------------------------------------------------------------------------------

    private fun portOf(ctx: McpActionContext): McpFilePort =
        ctx.host.filePort ?: throw McpToolException(McpToolCodes.UI_BUSY)

    /** Both permissions are judged again inside every block that changes something, so a switch-off wins at once. */
    private fun recheck(ctx: McpActionContext) {
        if (!ctx.allowActions()) throw McpToolException(McpToolCodes.ACTIONS_DISABLED)
        if (!ctx.allowFiles()) throw McpToolException(McpToolCodes.FILES_DISABLED)
    }

    private fun stale(revision: String) =
        McpToolException(McpToolCodes.STALE_REVISION, null, buildJsonObject { put("revision", revision) })

    private fun checkedRevision(a: McpParsedArgs): String {
        val rev = a.string("expectedRevision") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        if (!McpAgentText.isRevision(rev)) throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        return rev
    }

    /** A script file in the editor that is the one the agent read; returns its stamp. */
    private fun scriptStamp(
        target: McpFileTarget,
        expected: String?,
    ): McpFileStamp {
        if (target.kind == EditorKind.NONE) throw McpToolException(McpToolCodes.NO_FILE)
        if (target.kind == EditorKind.NOTE || target.workspaceOpen) throw McpToolException(McpToolCodes.NOT_A_SCRIPT)
        if (target.draftOfferOpen) throw McpToolException(McpToolCodes.DRAFT_OFFER_OPEN)
        val stamp = target.stamp ?: throw McpToolException(McpToolCodes.NO_FILE)
        if (expected != null && stamp.textRevision != expected) throw stale(stamp.textRevision)
        return stamp
    }

    /** Asks the user. Never call this inside a UI block: the dialog needs the UI thread (ADR-0098 D3). */
    private fun ask(
        ctx: McpActionContext,
        action: McpFileAction,
        fileName: String,
        folder: String,
        scope: String,
    ) {
        val outcome =
            ctx.host.confirmations.request { id ->
                McpConfirmRequest(
                    id,
                    action,
                    McpDisplayText.forDialog(fileName, FILE_NAME_CLIP),
                    McpDisplayText.leftClip(folder, FOLDER_CLIP),
                    McpDisplayText.forDialog(scope, SCOPE_CLIP),
                    ctx.clock(),
                )
            }
        when (outcome) {
            McpConfirmOutcome.Allowed -> Unit
            McpConfirmOutcome.Denied -> throw McpToolException(McpToolCodes.DENIED)
            McpConfirmOutcome.TimedOut -> throw McpToolException(McpToolCodes.CONFIRMATION_TIMEOUT)
            McpConfirmOutcome.Busy -> throw McpToolException(McpToolCodes.CONFIRMATION_PENDING)
            is McpConfirmOutcome.Cancelled -> throw McpToolException(outcome.code)
            is McpConfirmOutcome.CoolingDown ->
                throw McpToolException(
                    McpToolCodes.RATE_LIMITED,
                    "the user denied a request a moment ago",
                    buildJsonObject { put("retryAfterMs", outcome.retryAfterMs) },
                )
        }
    }

    // ---- save ---------------------------------------------------------------------------------------------------

    private fun save(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val expected = checkedRevision(a)
        val port = portOf(ctx)
        val target = ctx.host.bridge.onUi { port.target() }
        val stamp = scriptStamp(target, expected)
        if (!stamp.dirty) {
            return buildJsonObject {
                put("saved", false)
                put("reason", "nothing to save")
                put("revision", stamp.textRevision)
            }
        }
        val delta = port.lineDelta(stamp)
        val scope = if (delta == null) BIG_CHANGE else "+${delta.added}/−${delta.removed} lines vs. file on disk"
        ask(ctx, McpFileAction.SAVE, target.fileName.orEmpty(), target.folder.orEmpty(), "Save: $scope")
        ctx.host.bridge.onUiMutating {
            recheck(ctx)
            val now = port.target()
            val current = scriptStamp(now, null)
            if (current != stamp) throw stale(current.textRevision)
            if (!port.beginSave(stamp)) throw stale(current.textRevision)
        }
        return when (val result = port.awaitSave(stamp, WAIT_MS)) {
            is McpSaveResult.Saved ->
                buildJsonObject {
                    put("saved", true)
                    put("name", McpJson.text(target.fileName.orEmpty(), NAME_ARG_MAX))
                    put("revision", result.revision)
                }
            McpSaveResult.Stale -> throw stale(stamp.textRevision)
            McpSaveResult.Failed -> throw McpToolException(McpToolCodes.SAVE_FAILED)
            McpSaveResult.StillSaving -> throw McpToolException(McpToolCodes.SAVE_FAILED, "still saving")
        }
    }

    // ---- list_recent --------------------------------------------------------------------------------------------

    private fun listRecent(
        @Suppress("UNUSED_PARAMETER") a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val port = portOf(ctx)
        val list = ctx.host.bridge.onUi { port.recentScripts() }
        return buildJsonObject {
            put(
                "scripts",
                buildJsonArray {
                    list.forEach {
                        add(
                            buildJsonObject {
                                put("index", it.index)
                                put("name", McpJson.text(it.name, NAME_ARG_MAX))
                            },
                        )
                    }
                },
            )
        }
    }

    // ---- open_recent --------------------------------------------------------------------------------------------

    private fun invalid(reason: String) = McpToolException(McpToolCodes.INVALID_ARGUMENTS, reason)

    private fun openRecent(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val index = a.int("index")
        val name = a.string("name")
        if (name != null &&
            (name.contains('/') || name.contains('\\') || name.contains('\u0000') || name.contains(".."))
        ) {
            throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        }
        val port = portOf(ctx)
        val (target, list) = ctx.host.bridge.onUi { port.target() to port.recentScripts() }
        if (target.workspaceOpen) throw McpToolException(McpToolCodes.NOT_A_SCRIPT, "close the folder first")
        val before = target.stamp
        if (before?.dirty == true) throw McpToolException(McpToolCodes.UNSAVED_CHANGES)
        val chosen =
            if (index != null) {
                list.firstOrNull { it.index == index } ?: throw invalid("no recent script at that index")
            } else {
                val hits = list.filter { it.name == name }
                if (hits.isEmpty()) throw invalid("no recent script with that name")
                if (hits.size > 1) throw invalid("more than one recent script has that name; use the index")
                hits.single()
            }
        if (!port.recentStillValid(chosen.index)) throw McpToolException(McpToolCodes.NOT_FOUND)
        ask(ctx, McpFileAction.OPEN_RECENT, chosen.name, chosen.folder, "Open this script in the editor")
        val result =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                val now = port.target()
                if (now.workspaceOpen) return@onUiMutating McpOpenResult.WorkspaceOpen
                if (now.stamp?.dirty == true) return@onUiMutating McpOpenResult.Unsaved
                if (now.stamp != before) return@onUiMutating McpOpenResult.Stale
                port.openRecent(chosen.index, chosen.name, before)
            }
        return when (result) {
            is McpOpenResult.Opened ->
                buildJsonObject {
                    put("opened", true)
                    put("name", McpJson.text(result.name, NAME_ARG_MAX))
                }
            McpOpenResult.Stale -> throw McpToolException(McpToolCodes.STALE_REVISION)
            McpOpenResult.Unsaved -> throw McpToolException(McpToolCodes.UNSAVED_CHANGES)
            McpOpenResult.Missing -> throw McpToolException(McpToolCodes.NOT_FOUND)
            McpOpenResult.WorkspaceOpen -> throw McpToolException(McpToolCodes.NOT_A_SCRIPT, "close the folder first")
        }
    }

    // ---- export -------------------------------------------------------------------------------------------------

    private fun export(
        a: McpParsedArgs,
        ctx: McpActionContext,
    ): JsonObject {
        val expected = checkedRevision(a)
        val format =
            McpExportFormat.of(a.string("format") ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS))
                ?: throw McpToolException(McpToolCodes.INVALID_ARGUMENTS)
        val name =
            a.string("name")?.let { McpSafeFileName.parse(it) ?: throw McpToolException(McpToolCodes.INVALID_NAME) }
        val port = portOf(ctx)
        val target = ctx.host.bridge.onUi { port.target() }
        val stamp = scriptStamp(target, expected)
        if (stamp.dirty) throw McpToolException(McpToolCodes.UNSAVED_CHANGES)
        val plan =
            when (val p = port.planExport(format, name, stamp)) {
                is McpExportPlan.Ready -> p
                McpExportPlan.NoFolder ->
                    throw McpToolException(McpToolCodes.NOT_AVAILABLE, "export once by hand to choose a folder")
                McpExportPlan.Exists -> throw McpToolException(McpToolCodes.EXISTS)
                McpExportPlan.Stale -> throw stale(stamp.textRevision)
            }
        ask(
            ctx,
            McpFileAction.EXPORT,
            plan.fileName,
            plan.folderDisplay,
            "Export as ${format.id.uppercase()}. New file, never replaces an existing one",
        )
        val started =
            ctx.host.bridge.onUiMutating {
                recheck(ctx)
                val now = port.target()
                val current = scriptStamp(now, null)
                if (current != stamp) throw stale(current.textRevision)
                port.startExport(plan, format, stamp)
            }
        val begun =
            when (started) {
                is McpExportStart.Started -> started
                McpExportStart.Stale -> throw stale(stamp.textRevision)
                McpExportStart.UiBusy -> throw McpToolException(McpToolCodes.UI_BUSY, "the export dialog is in use")
                McpExportStart.Unsaved -> throw McpToolException(McpToolCodes.UNSAVED_CHANGES)
                McpExportStart.NoFile -> throw McpToolException(McpToolCodes.NO_FILE)
                McpExportStart.NotAScript -> throw McpToolException(McpToolCodes.NOT_A_SCRIPT)
            }
        return when (val result = port.awaitExport(begun, WAIT_MS)) {
            is McpExportResult.Done ->
                buildJsonObject {
                    put("exported", true)
                    put("name", McpJson.text(result.fileName, NAME_ARG_MAX))
                    put("format", format.id)
                }
            is McpExportResult.Failed -> throw McpToolException(McpToolCodes.EXPORT_FAILED, result.code)
            McpExportResult.Exists -> throw McpToolException(McpToolCodes.EXISTS)
            is McpExportResult.Running ->
                buildJsonObject {
                    put("exported", false)
                    put("running", true)
                    put("name", McpJson.text(result.fileName, NAME_ARG_MAX))
                    put("format", format.id)
                }
        }
    }
}
