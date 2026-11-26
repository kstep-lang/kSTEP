package dev.kstep.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/** The CLI zip must stay free of Compose and desktop code (ADR-0083, scripts/release/verify-dist.sh). */
class DesktopLaunchBoundaryTest :
    StringSpec({
        "DesktopLaunch.kt imports nothing from androidx, compose or the desktop module" {
            val file = Path.of("src/main/kotlin/dev/kstep/cli/DesktopLaunch.kt").toAbsolutePath()
            Files
                .readAllLines(file)
                .filter { it.startsWith("import ") }
                .filter {
                    it.contains("androidx") ||
                        it.contains("org.jetbrains.compose") ||
                        it.contains("dev.kstep.desktop")
                }.shouldBeEmpty()
        }

        "the process start never goes through a shell" {
            val text = Files.readString(Path.of("src/main/kotlin/dev/kstep/cli/DesktopLaunch.kt"))
            listOf("sh -c", "Runtime.getRuntime", "bash -c").filter { text.contains(it) }.shouldBeEmpty()
        }
    })
