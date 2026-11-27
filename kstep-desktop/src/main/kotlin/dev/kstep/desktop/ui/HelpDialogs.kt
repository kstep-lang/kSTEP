package dev.kstep.desktop.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.menu.AboutInfo
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.menu.keysText
import dev.kstep.desktop.menu.shortcutOverview
import dev.kstep.desktop.menu.shortcutsMaxHeightDp
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException

private val logger = KotlinLogging.logger {}

private val SHORTCUTS_MIN_WIDTH = 280.dp
private val SHORTCUTS_MAX_WIDTH = 420.dp
private val SCROLLBAR_GAP = 8.dp

/** The Help dialog with every shortcut, built from the menu model (ADR-0076). Esc and Close close it. */
@Composable
internal fun ShortcutsDialog(
    os: MenuOs,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val groups = remember(os) { shortcutOverview(os) }
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.onPreviewKeyEvent { closeOnEscape(it, onClose) },
        title = { Text("Keyboard Shortcuts") },
        text = {
            val windowHeightPx = LocalWindowInfo.current.containerSize.height
            val density = LocalDensity.current
            val maxHeight = shortcutsMaxHeightDp(windowHeightPx / density.density).dp
            val scroll = rememberScrollState()
            // The Box has a bounded height, so the scrollbar has a length (D3d-3).
            Box(Modifier.widthIn(min = SHORTCUTS_MIN_WIDTH, max = SHORTCUTS_MAX_WIDTH).heightIn(max = maxHeight)) {
                Column(Modifier.verticalScroll(scroll).padding(end = SCROLLBAR_GAP + 8.dp)) {
                    for (group in groups) {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        for (row in group.rows) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.label, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(
                                    keysText(row),
                                    fontSize = 13.sp,
                                    color = DIM,
                                    softWrap = false,
                                    modifier = Modifier.padding(start = SCROLLBAR_GAP),
                                )
                            }
                        }
                        group.footnote?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = DIM,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                VerticalScrollbar(
                    rememberScrollbarAdapter(scroll),
                    Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text("Close") } },
    )
}

/** The About dialog: name, version, a note and a link to the website; the link is the fixed one of [info]. */
@Composable
internal fun AboutDialog(
    info: AboutInfo,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    var browserFailed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.onPreviewKeyEvent { closeOnEscape(it, onClose) },
        title = { Text(info.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Version ${info.version}", fontSize = 13.sp)
                Text(info.note, fontSize = 13.sp, color = DIM)
                TextButton(
                    onClick = {
                        scope.launch {
                            // Desktop.browse can hang on Linux without a handler: never on the UI thread.
                            val ok = withContext(Dispatchers.IO) { openUrl(info.url) }
                            browserFailed = !ok
                        }
                    },
                ) { Text(info.linkLabel, fontSize = 13.sp) }
                if (browserFailed) {
                    Text(
                        "Could not open the browser: ${info.url}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text("Close") } },
    )
}

private fun closeOnEscape(
    event: androidx.compose.ui.input.key.KeyEvent,
    onClose: () -> Unit,
): Boolean {
    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
        onClose()
        return true
    }
    return false
}

/** Opens [url] in the default browser; false (and a log line, without any user path) if that is not possible. */
internal fun openUrl(url: String): Boolean =
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            logger.warn { "No default browser is available to open $url." }
            false
        }
    } catch (e: IOException) {
        logger.warn(e) { "Could not open $url in the browser." }
        false
    } catch (e: UnsupportedOperationException) {
        logger.warn(e) { "Could not open $url in the browser." }
        false
    } catch (e: SecurityException) {
        logger.warn(e) { "Could not open $url in the browser." }
        false
    } catch (e: URISyntaxException) {
        logger.warn(e) { "Could not open $url in the browser." }
        false
    }
