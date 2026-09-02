package dev.kstep.viewer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private const val OCCT_INSTALL_COMMAND =
    "sudo apt-get install --no-install-recommends libocct-foundation-dev " +
        "libocct-modeling-data-dev libocct-modeling-algorithms-dev libocct-data-exchange-dev"

private const val DEFAULT_HEADING = "kSTEP Viewer cannot show a shape: the native OCCT bridge is unavailable."

/**
 * Shown instead of a blank canvas whenever the viewer has no shape to draw -- never leave the
 * viewer window an unexplained empty white area (Atkinson's rule: every state is a state the
 * user can read, not a silent gap).
 *
 * Two distinct callers, two distinct headings: [heading]/[showInstallInstructions] default to the
 * original OCCT-bridge-unavailable wording (`Main.kt`'s `OcctAvailability.Unavailable` branch),
 * but the SAME composable also covers the "OCCT is available, but building/triangulating this
 * particular demo shape failed anyway" case (`Main.kt`'s `OcctAvailability.Available` branch) --
 * that case passes its own [heading] and `showInstallInstructions = false`, since telling the user
 * to install OCCT dev packages that are, in that case, already installed would be actively wrong.
 */
@Composable
fun UnavailableNotice(
    reason: String,
    modifier: Modifier = Modifier,
    heading: String = DEFAULT_HEADING,
    showInstallInstructions: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Text(reason, modifier = Modifier.padding(top = 12.dp))
        if (showInstallInstructions) {
            Text(
                "Install the OCCT dev packages, then rebuild:",
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(OCCT_INSTALL_COMMAND, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
