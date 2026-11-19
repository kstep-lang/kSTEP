package dev.kstep.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kstep.desktop.run.SandboxNotice

/**
 * The notice "Script runs without sandbox" (ADR-0062): a 6 dp amber dot and the text, reason as tooltip. No background,
 * not bold, no glyph, not dismissable and without a setting. Shown only while no sandbox is available.
 */
@Composable
internal fun SandboxMark(
    notice: SandboxNotice,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    WithTip(notice.reason) {
        Row(
            modifier.semantics { contentDescription = notice.contentDescription },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(6.dp).background(MARK_WARNING, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(notice.text, fontSize = 12.sp, color = textColor, softWrap = false, maxLines = 1)
        }
    }
}
