package dev.kstep.desktop.ui

import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.export.ExportController
import dev.kstep.desktop.export.ExportFormat
import dev.kstep.desktop.export.ExportNaming
import dev.kstep.desktop.export.ExportPreferencesStore
import dev.kstep.desktop.export.ExportState
import dev.kstep.desktop.export.Overwrite
import dev.kstep.desktop.mcp.EditorKind
import dev.kstep.desktop.mcp.McpAgentText
import dev.kstep.desktop.mcp.McpExportFormat
import dev.kstep.desktop.mcp.McpExportPlan
import dev.kstep.desktop.mcp.McpExportResult
import dev.kstep.desktop.mcp.McpExportStart
import dev.kstep.desktop.mcp.McpFilePort
import dev.kstep.desktop.mcp.McpFileStamp
import dev.kstep.desktop.mcp.McpFileTarget
import dev.kstep.desktop.mcp.McpLineDelta
import dev.kstep.desktop.mcp.McpOpenResult
import dev.kstep.desktop.mcp.McpRecentScript
import dev.kstep.desktop.mcp.McpSafeFileName
import dev.kstep.desktop.mcp.McpSaveResult
import dev.kstep.desktop.menu.MenuContext
import dev.kstep.desktop.recent.RecentCheck
import dev.kstep.desktop.recent.RecentKind
import dev.kstep.desktop.recent.RecentList
import dev.kstep.desktop.recent.RecentOpenCheck
import dev.kstep.desktop.run.DesktopErrorCodes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/**
 * What the window gives the file tools of the MCP server (ADR-0098). No method takes a path: the agent can only save the
 * file that is open, open an entry of the Recent list it read from this port, and export the open, saved script to the
 * folder the user used before, as a new file. It never writes a file itself: saving goes through the editor session, the
 * export through the export controller and its worker, the opening through the same entry the menu uses. A test checks
 * the source for the calls that would write, copy, delete or capture the screen.
 */
internal class DesktopMcpFilePort(
    private val session: EditorSession,
    private val menuContext: () -> MenuContext,
    private val knowledgeOpen: () -> Boolean,
    private val workspaceActive: () -> Boolean,
    private val recent: () -> RecentList,
    private val openPath: (Path) -> Boolean,
    private val forgetRecent: (Path) -> Unit,
    private val exporter: ExportController,
    private val exportPrefs: ExportPreferencesStore,
    private val exportDialogOpen: () -> Boolean,
    private val converting: () -> Boolean,
) : McpFilePort {
    private class Planned(
        val script: Path,
        val dir: Path,
        val target: Path,
    )

    private fun isScript(): Boolean = menuContext().editorIsScript && !knowledgeOpen()

    private fun idOf(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                path
                    .toAbsolutePath()
                    .normalize()
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    private fun stampOf(editing: EditorState.Editing) =
        McpFileStamp(McpAgentText.revisionOf(editing.text), editing.dirty, idOf(editing.path))

    override fun target(): McpFileTarget {
        val view = session.view.value
        val editing = view.state as? EditorState.Editing
        val kind =
            when {
                editing == null -> EditorKind.NONE
                isScript() -> EditorKind.SCRIPT
                else -> EditorKind.NOTE
            }
        return McpFileTarget(
            kind = kind,
            stamp = editing?.let(::stampOf),
            fileName = editing?.path?.fileName?.toString(),
            folder = editing?.path?.parent?.toString(),
            draftOfferOpen = view.draftOffer != null,
            workspaceOpen = workspaceActive(),
        )
    }

    private fun scriptEntries() = recent().entries.filter { it.kind == RecentKind.SCRIPT }.take(MAX_RECENT)

    override fun recentScripts(): List<McpRecentScript> =
        scriptEntries().mapIndexed { i, e ->
            McpRecentScript(
                i,
                e.path.fileName
                    ?.toString()
                    .orEmpty(),
                e.path.parent
                    ?.toString()
                    .orEmpty(),
            )
        }

    override fun recentStillValid(index: Int): Boolean {
        val entry = scriptEntries().getOrNull(index) ?: return false
        return RecentOpenCheck.checkPath(entry.path, false) is RecentCheck.Ok
    }

    override fun lineDelta(stamp: McpFileStamp): McpLineDelta? {
        val editing = session.view.value.state as? EditorState.Editing ?: return null
        if (stampOf(editing) != stamp) return null
        val disk =
            try {
                if (java.nio.file.Files
                        .size(editing.path) > MAX_DIFF_BYTES
                ) {
                    return null
                }
                String(
                    java.nio.file.Files
                        .readAllBytes(editing.path),
                    Charsets.UTF_8,
                )
            } catch (_: IOException) {
                editing.base
            }
        return LineDiff.count(normalize(disk), normalize(editing.text))
    }

    private fun normalize(s: String) = s.replace("\r\n", "\n").replace('\r', '\n')

    override fun beginSave(stamp: McpFileStamp): Boolean {
        val view = session.view.value
        val editing = view.state as? EditorState.Editing ?: return false
        if (view.draftOffer != null || stampOf(editing) != stamp) return false
        session.save()
        return true
    }

    override fun awaitSave(
        stamp: McpFileStamp,
        timeoutMs: Long,
    ): McpSaveResult {
        val ended =
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    session.view.first { v ->
                        val e = v.state as? EditorState.Editing
                        e == null ||
                            (
                                e.saving == null &&
                                    !e.pendingSave &&
                                    (!e.dirty || e.saveError != null || e.conflict != null)
                            )
                    }
                }
            } ?: return McpSaveResult.StillSaving
        val editing = ended.state as? EditorState.Editing ?: return McpSaveResult.Stale
        if (idOf(editing.path) != stamp.fileId) return McpSaveResult.Stale
        return when {
            editing.saveError != null || editing.conflict != null -> McpSaveResult.Failed
            editing.dirty -> McpSaveResult.Stale
            else -> McpSaveResult.Saved(McpAgentText.revisionOf(editing.text))
        }
    }

    override fun openRecent(
        index: Int,
        expectedName: String,
        stamp: McpFileStamp?,
    ): McpOpenResult {
        val entry = scriptEntries().getOrNull(index) ?: return McpOpenResult.Missing
        if (entry.path.fileName?.toString() != expectedName) return McpOpenResult.Stale
        if (workspaceActive()) return McpOpenResult.WorkspaceOpen
        val now = target().stamp
        if (now?.dirty == true) return McpOpenResult.Unsaved
        if (now != stamp) return McpOpenResult.Stale
        // the same check as the menu makes at click time: a plain file, no link anywhere in the path
        val real =
            when (val check = RecentOpenCheck.checkPath(entry.path, false)) {
                is RecentCheck.Ok -> check.path
                else -> {
                    forgetRecent(entry.path)
                    return McpOpenResult.Missing
                }
            }
        return if (openPath(real)) McpOpenResult.Opened(expectedName) else McpOpenResult.Unsaved
    }

    override fun planExport(
        format: McpExportFormat,
        name: McpSafeFileName?,
        stamp: McpFileStamp,
    ): McpExportPlan {
        val editing = session.view.value.state as? EditorState.Editing ?: return McpExportPlan.Stale
        if (stampOf(editing) != stamp) return McpExportPlan.Stale
        val script = editing.path
        val prefs = exportPrefs.load()
        val remembered = prefs.scripts[ExportPreferencesStore.scriptKey(script)]?.dir ?: prefs.lastDir
        // checked again now: a real folder, reached without a link (the same rule the preferences file is loaded with)
        val dir =
            remembered?.let { exportPrefs.validDir(it.toString(), MAX_DIR_CHARS) }
                ?: return McpExportPlan.NoFolder
        val stem = name?.value ?: ExportNaming.stem(script.fileName.toString())
        val extension = "." + format.extension
        for (n in 1..MAX_SUFFIX) {
            val candidate = if (n == 1) "$stem$extension" else "$stem-$n$extension"
            val path = dir.resolve(candidate).normalize()
            if (path.parent != dir) return McpExportPlan.Exists
            if (!java.nio.file.Files
                    .exists(path, LinkOption.NOFOLLOW_LINKS)
            ) {
                return McpExportPlan.Ready(candidate, dir.toString(), Planned(script, dir, path))
            }
        }
        return McpExportPlan.Exists
    }

    override fun startExport(
        plan: McpExportPlan.Ready,
        format: McpExportFormat,
        stamp: McpFileStamp,
    ): McpExportStart {
        val planned = plan.opaque as? Planned ?: return McpExportStart.Stale
        if (exportDialogOpen() || converting() || exporter.state.value is ExportState.Running) {
            return McpExportStart.UiBusy
        }
        val now = target()
        when {
            now.kind == EditorKind.NONE -> return McpExportStart.NoFile
            now.kind == EditorKind.NOTE || now.workspaceOpen -> return McpExportStart.NotAScript
            now.draftOfferOpen || now.stamp != stamp -> return McpExportStart.Stale
            now.stamp?.dirty == true -> return McpExportStart.Unsaved
        }
        val editing = session.view.value.state as? EditorState.Editing ?: return McpExportStart.NoFile
        if (editing.path.toAbsolutePath().normalize() != planned.script.toAbsolutePath().normalize()) {
            return McpExportStart.Stale
        }
        val generation =
            exporter.startExclusive(planned.script, formatOf(format), planned.target) ?: return McpExportStart.UiBusy
        return McpExportStart.Started(generation, plan.fileName)
    }

    private fun formatOf(f: McpExportFormat): ExportFormat =
        when (f) {
            McpExportFormat.STEP -> ExportFormat.STEP
            McpExportFormat.GLB -> ExportFormat.GLB
            McpExportFormat.STL -> ExportFormat.STL
            McpExportFormat.THREE_MF -> ExportFormat.THREE_MF
        }

    override fun awaitExport(
        start: McpExportStart.Started,
        timeoutMs: Long,
    ): McpExportResult {
        val ended =
            runBlocking {
                withTimeoutOrNull(timeoutMs) {
                    exporter.state.first { s ->
                        s.generation == start.generation && (s is ExportState.Succeeded || s is ExportState.Configuring)
                    }
                }
            } ?: return McpExportResult.Running(start.fileName)
        return when (ended) {
            is ExportState.Succeeded -> McpExportResult.Done(ended.fileName)
            is ExportState.Configuring ->
                when {
                    ended.error != null -> McpExportResult.Failed(ended.error.code)
                    ended.overwrite is Overwrite.NeedsConfirm -> McpExportResult.Exists
                    else -> McpExportResult.Failed(DesktopErrorCodes.EXPORT_FAILED)
                }
            else -> McpExportResult.Failed(DesktopErrorCodes.EXPORT_FAILED)
        }
    }

    private companion object {
        const val MAX_RECENT = 10
        const val MAX_DIFF_BYTES = 1L shl 20
        const val MAX_DIR_CHARS = 4096
        const val MAX_SUFFIX = 99
    }
}

/** A line count of what changed between two texts (ADR-0098); bounded, so a huge change just says "large". */
internal object LineDiff {
    private const val MAX_CELLS = 4_000_000L
    private const val MAX_SHOWN = 9999

    fun count(
        before: String,
        after: String,
    ): McpLineDelta? {
        val a = before.split('\n')
        val b = after.split('\n')
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) {
            endA--
            endB--
        }
        val n = endA - start
        val m = endB - start
        if (n.toLong() * m > MAX_CELLS) return null
        var prev = IntArray(m + 1)
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            for (j in 1..m) {
                cur[j] = if (a[start + i - 1] == b[start + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
            }
            val t = prev
            prev = cur
            cur = t
            cur.fill(0)
        }
        val common = prev[m]
        return McpLineDelta((m - common).coerceAtMost(MAX_SHOWN), (n - common).coerceAtMost(MAX_SHOWN))
    }
}
