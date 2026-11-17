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

/** The OCCT install hint per operating system (ADR-0051): the apt command is wrong on macOS. Pure, for testing. */
object OcctInstallHint {
    private const val LINUX_COMMAND =
        "sudo apt-get install --no-install-recommends libocct-foundation-dev " +
            "libocct-modeling-data-dev libocct-modeling-algorithms-dev libocct-data-exchange-dev"

    /** The lines to show for [osName] (`os.name`); the first line is the introduction, the rest are commands/notes. */
    fun forOs(osName: String?): List<String> {
        val os = osName.orEmpty().lowercase()
        return when {
            os.contains("mac") || os.contains("darwin") ->
                listOf(
                    "Install OCCT with Homebrew, then rebuild:",
                    "brew install opencascade eigen boost",
                    "./gradlew clean :kstep-desktop:run -Pkstep.occt.require=true -Pkstep.planegcs.require=true",
                    "Or use the native archive (ADR-0050): " +
                        "-Pkstep.occt.bridge.library=<abs>/lib/native/macos-aarch64/libkstep_occt_bridge.dylib",
                )
            os.contains("linux") -> listOf("Install the OCCT dev packages, then rebuild:", LINUX_COMMAND)
            else -> listOf("OCCT bridge is only built for Linux x86-64 and macOS arm64")
        }
    }

    fun forCurrentOs(): List<String> = forOs(System.getProperty("os.name"))
}

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
    installHint: List<String> = OcctInstallHint.forCurrentOs(),
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(heading, style = MaterialTheme.typography.titleMedium)
        Text(reason, modifier = Modifier.padding(top = 12.dp))
        if (showInstallInstructions) {
            installHint.forEachIndexed { index, line ->
                if (index == 0 && installHint.size > 1) {
                    Text(line, modifier = Modifier.padding(top = 16.dp))
                } else if (installHint.size == 1) {
                    Text(line, modifier = Modifier.padding(top = 16.dp))
                } else {
                    Text(line, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
