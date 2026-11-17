package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.run.DesktopModel
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.ErrorBarText
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.geometry.TriangleMesh
import dev.kstep.viewer.camera.ViewerCameraState
import dev.kstep.viewer.ui.ShapeCanvas
import dev.kstep.viewer.ui.UnavailableNotice
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path

private val DIM = Color(0xFF8A8A8A)
private val WARN = Color(0xFFF9A825)
private const val PROGRESS_DELAY_MS = 300L
private const val ERROR_BAR_MAX_FRACTION = 0.4f
private const val SCRIPT_FILTER_SUFFIX = ".kstep.kts"

/** The whole window content: toolbar, canvas with badge and progress line, error bar (ADR-0051). */
@Composable
fun DesktopApp(
    controller: LiveRunController,
    initialFile: Path?,
    window: Frame,
) {
    val dark = isSystemInDarkTheme()
    val state by controller.state.collectAsState()
    val openCount by controller.openCount.collectAsState()
    val camera = remember { mutableStateOf(ViewerCameraState.HOME) }
    var fitReference by remember(openCount) { mutableStateOf<TriangleMesh?>(null) }

    val display: DesktopModel? = if (state is DesktopState.NativeUnavailable) null else state.lastGood
    val displayMesh = display?.mesh?.mesh

    // The camera and the fit scale belong to the OPEN file: a re-run keeps both, so the view never jumps.
    LaunchedEffect(openCount) { camera.value = ViewerCameraState.HOME }
    LaunchedEffect(displayMesh, openCount) {
        if (fitReference == null && displayMesh != null) fitReference = displayMesh
    }
    LaunchedEffect(Unit) { initialFile?.let(controller::open) }

    var showProgress by remember { mutableStateOf(false) }
    val running = state as? DesktopState.Running
    LaunchedEffect(running?.generation) {
        showProgress = false
        if (running != null) {
            delay(PROGRESS_DELAY_MS)
            showProgress = true
        }
    }

    val openFile: () -> Unit = { chooseScript(window)?.let(controller::open) }
    val home: () -> Unit = {
        camera.value = ViewerCameraState.HOME
        fitReference = displayMesh
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(
            Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val command = event.isMetaPressed || event.isCtrlPressed
                when {
                    command && event.key == Key.O -> {
                        openFile()
                        true
                    }
                    command && event.key == Key.R -> {
                        controller.forceRun()
                        true
                    }
                    !command && event.key == Key.H -> {
                        home()
                        true
                    }
                    else -> false
                }
            },
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val errorBarMax = maxHeight * ERROR_BAR_MAX_FRACTION
                Column(Modifier.fillMaxSize()) {
                    Toolbar(state, onOpen = openFile, onHome = home)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Canvas(state, display, camera, fitReference, openFile)
                        Badge(state)
                        if (showProgress) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter))
                        }
                    }
                    (state as? DesktopState.Error)?.let { error ->
                        ErrorBar(error.errors, dark, maxHeight = errorBarMax)
                    }
                }
            }
        }
    }
}

@Composable
private fun Toolbar(
    state: DesktopState,
    onOpen: () -> Unit,
    onHome: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpen) { Text("Open", fontSize = 13.sp) }
        TextButton(onClick = onHome) { Text("Home", fontSize = 13.sp) }
        Text(
            state.file?.fileName?.toString() ?: "No file",
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusText(state), fontSize = 12.sp, color = DIM)
        }
    }
}

private fun statusText(state: DesktopState): String =
    when (state) {
        DesktopState.Idle -> ""
        is DesktopState.Running -> "● Running…"
        is DesktopState.Ok ->
            listOfNotNull(
                ErrorBarText.statusOk(state.model.durationMs, state.model.triangles),
                ErrorBarText.triangleWarning(state.model.triangles),
                state.model.notes.firstOrNull(),
            ).joinToString("  ·  ")
        is DesktopState.Error -> "● Error"
        is DesktopState.NativeUnavailable -> "● Geometry unavailable"
    }

@Composable
private fun Canvas(
    state: DesktopState,
    display: DesktopModel?,
    camera: androidx.compose.runtime.MutableState<ViewerCameraState>,
    fitReference: TriangleMesh?,
    onOpen: () -> Unit,
) {
    val modifier = Modifier.fillMaxSize()
    when {
        state is DesktopState.Idle ->
            Column(
                modifier,
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(onClick = onOpen) { Text("Open a .kstep.kts script") }
                Text(
                    if (System
                            .getProperty("os.name")
                            .orEmpty()
                            .lowercase()
                            .contains("mac")
                    ) {
                        "⌘O"
                    } else {
                        "Ctrl+O"
                    },
                    color = DIM,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        state is DesktopState.NativeUnavailable ->
            UnavailableNotice(
                state.reason,
                modifier,
                heading = "kSTEP Desktop cannot show geometry: the native OCCT bridge is unavailable.",
            )
        display == null ->
            Box(modifier, contentAlignment = Alignment.Center) {
                Text(if (state is DesktopState.Running) "Running the script…" else "No result yet", color = DIM)
            }
        display.mesh == null ->
            UnavailableNotice(
                "Script has no shape(...)",
                modifier,
                heading = "kSTEP Desktop has no geometry to show.",
                showInstallInstructions = false,
            )
        else -> ShapeCanvas(display.mesh, modifier, camera, fitReference)
    }
}

/** Marks a canvas that shows an outdated result: a yellow dot and the time of the last good run (Zhuo). */
@Composable
private fun Badge(state: DesktopState) {
    val error = state as? DesktopState.Error ?: return
    val lastGood = error.lastGood ?: return
    Box(Modifier.padding(12.dp)) {
        Row(
            Modifier.background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(RectangleShape).background(WARN))
            Text(
                ErrorBarText.lastGoodBadge(lastGood.finishedAt),
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/** A native file dialog for a `*.kstep.kts` script; `null` if cancelled. */
private fun chooseScript(parent: Frame): Path? {
    val dialog = FileDialog(parent, "Open a kSTEP script", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(SCRIPT_FILTER_SUFFIX) }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return Path.of(dialog.directory ?: "", name)
}
