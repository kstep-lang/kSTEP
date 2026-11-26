package dev.kstep.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import dev.kstep.desktop.edit.EditorState
import dev.kstep.desktop.edit.EditorView
import dev.kstep.desktop.run.AutoPreviewAction
import dev.kstep.desktop.run.AutoPreviewConditions
import dev.kstep.desktop.run.AutoPreviewScheduler
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.PreviewOrigin
import dev.kstep.desktop.run.shouldSchedulePreview
import kotlinx.coroutines.delay
import java.nio.file.Path

private const val TICK_MS = 100L
private const val IDLE_TICK_MS = 250L
private const val MIN_WAIT_MS = 20L
private const val NANOS_PER_MILLI = 1_000_000L

private fun nowMs(): Long = System.nanoTime() / NANOS_PER_MILLI // monotonic, not the wall clock

/**
 * Feeds the pure [AutoPreviewScheduler] (ADR-0087) and carries out what it decides. All logic is in the scheduler; this
 * only reports facts and calls [onRun] / [onTooLarge]. While [enabled] is false it reports nothing and calls nothing.
 */
@Composable
internal fun AutoPreviewDriver(
    enabled: Boolean,
    state: DesktopState,
    editorView: EditorView,
    windowFocused: Boolean,
    isScript: Boolean,
    onRun: (Path, String) -> Unit,
    onTooLarge: (Path) -> Unit,
) {
    val scheduler = remember { AutoPreviewScheduler() }
    val editing = editorView.state as? EditorState.Editing
    val path = state.file
    val conditions =
        AutoPreviewConditions(
            fileOpen = path != null && editing != null && editing.path == path,
            isScript = isScript,
            draftOfferOpen = editorView.draftOffer != null,
            fileRunActive = (state as? DesktopState.Running)?.origin == PreviewOrigin.SavedFile,
        )
    val active = shouldSchedulePreview(enabled, conditions)
    // declared in this order on purpose: effects of one composition run in order
    LaunchedEffect(path) { scheduler.fileChanged(nowMs()) }
    LaunchedEffect(active) { scheduler.enabledChanged(active, nowMs()) }
    LaunchedEffect(conditions) { scheduler.conditionsChanged(conditions, nowMs()) }
    LaunchedEffect(windowFocused) { scheduler.focusChanged(windowFocused, nowMs()) }
    // the text the shown picture was made from: the buffer of a buffer run (manual or automatic), else the saved text
    val base = editing?.base
    LaunchedEffect(state.generation, state.file) {
        when (state) {
            is DesktopState.Running ->
                when (val origin = state.origin) {
                    is PreviewOrigin.Buffer -> scheduler.baselineChanged(origin.text)
                    PreviewOrigin.SavedFile -> scheduler.baselineChanged(base)
                }
            is DesktopState.Ok -> if (state.model.origin == PreviewOrigin.SavedFile) scheduler.baselineChanged(base)
            is DesktopState.Error -> if (state.origin == PreviewOrigin.SavedFile) scheduler.baselineChanged(base)
            else -> Unit
        }
    }
    LaunchedEffect(active, editorView, path) { if (active) scheduler.textChanged(editorView.text, nowMs()) }

    val currentPath = rememberUpdatedState(path)
    val runCallback = rememberUpdatedState(onRun)
    val tooLargeCallback = rememberUpdatedState(onTooLarge)
    LaunchedEffect(Unit) {
        while (true) {
            val now = nowMs()
            val file = currentPath.value
            if (file != null) {
                when (val action = scheduler.tick(now)) {
                    is AutoPreviewAction.RunNow -> runCallback.value(file, action.text)
                    AutoPreviewAction.ShowTooLarge -> tooLargeCallback.value(file)
                    null -> Unit
                }
            }
            val due = scheduler.nextDueAt()
            delay(if (due == null) IDLE_TICK_MS else (due - now).coerceIn(MIN_WAIT_MS, TICK_MS))
        }
    }
}
