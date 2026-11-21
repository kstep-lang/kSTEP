package dev.kstep.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.pick.GroupInfo
import dev.kstep.desktop.pick.GroupInspection
import dev.kstep.desktop.pick.InspectionText
import dev.kstep.desktop.pick.ShowInScriptMessage

private val LABEL = Color(0xFF8A8A8A)
private const val HEX_PREFIX_LENGTH = 1

/**
 * The "Show in Script" action of an inspection panel (ADR-0068): the button, why it is disabled, and the message of the
 * last attempt. [onFind] receives the query of an ambiguous name.
 */
internal class ShowInScriptUi(
    val enabled: Boolean = false,
    val disabledReason: String? = null,
    val onClick: () -> Unit = {},
    val message: ShowInScriptMessage? = null,
    val onFind: (String) -> Unit = {},
)

/**
 * The inspection panel of the selected part (ADR-0053): a quiet overlay in the top right corner of the canvas, only
 * present while a part is selected. Text only, no symbols and no close button (Escape or a click on empty space ends
 * the selection). Name on top, one label and value line each below.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InspectionPanel(
    part: PartInfo,
    background: Color,
    modifier: Modifier = Modifier,
    action: ShowInScriptUi? = null,
) {
    Column(
        modifier
            .width(260.dp)
            .background(background.copy(alpha = 0.92f))
            .border(1.dp, LABEL.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        TooltipArea(
            tooltip = {
                Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                    Text(part.name, color = Color.White, fontSize = 12.sp)
                }
            },
        ) {
            Text(
                part.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Line("Triangles", InspectionText.triangles(part.triangleCount))
        Line("Size", InspectionText.size(part))
        Line("Centroid", InspectionText.point(part.centroid))
        Line("Position", InspectionText.point(part.placement.origin))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Color", color = LABEL, fontSize = 11.sp, modifier = Modifier.width(64.dp))
            Box(Modifier.size(12.dp).background(parseHex(part.colorHex)))
            Text(
                part.colorHex,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        action?.let { ShowInScriptRow(it) }
    }
}

/** The inspection panel of a selected assembly group (ADR-0054): name, part count, triangles, size and centre. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupInspectionPanel(
    info: GroupInfo,
    background: Color,
    modifier: Modifier = Modifier,
    action: ShowInScriptUi? = null,
) {
    Column(
        modifier
            .width(260.dp)
            .background(background.copy(alpha = 0.92f))
            .border(1.dp, LABEL.copy(alpha = 0.5f))
            .padding(10.dp),
    ) {
        TooltipArea(
            tooltip = {
                Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                    Text(info.name, color = Color.White, fontSize = 12.sp)
                }
            },
        ) {
            Text(
                info.name,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Line("Parts", GroupInspection.partsLine(info))
        Line("Triangles", GroupInspection.triangles(info))
        Line("Size", GroupInspection.size(info))
        Line("Center", InspectionText.point(info.center))
        action?.let { ShowInScriptRow(it) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShowInScriptRow(action: ShowInScriptUi) {
    val button: @Composable () -> Unit = {
        TextButton(onClick = action.onClick, enabled = action.enabled) { Text("Show in Script", fontSize = 12.sp) }
    }
    val reason = action.disabledReason
    if (!action.enabled && reason != null) {
        TooltipArea(
            tooltip = {
                Box(Modifier.background(Color.Black.copy(alpha = 0.8f)).padding(6.dp)) {
                    Text(reason, color = Color.White, fontSize = 12.sp)
                }
            },
            modifier = Modifier.padding(top = 2.dp),
        ) { button() }
    } else {
        Box(Modifier.padding(top = 2.dp)) { button() }
    }
    action.message?.let { message ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(message.text, color = LABEL, fontSize = 11.sp, maxLines = 3, modifier = Modifier.weight(1f))
            message.findQuery?.let { query ->
                TextButton(onClick = { action.onFind(query) }) { Text("Find", fontSize = 11.sp) }
            }
        }
    }
}

@Composable
private fun Line(
    label: String,
    value: String,
) {
    Row(Modifier.padding(top = 4.dp)) {
        Text(label, color = LABEL, fontSize = 11.sp, modifier = Modifier.width(64.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 2)
    }
}

private fun parseHex(hex: String): Color {
    val v = hex.substring(HEX_PREFIX_LENGTH).toLongOrNull(16) ?: return Color.Gray
    return Color(0xFF000000 or v)
}
