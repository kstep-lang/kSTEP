package dev.kstep.desktop

import dev.kstep.desktop.ai.AiLogging
import dev.kstep.desktop.ui.runDesktopApp

/**
 * Entry point of kSTEP Desktop (ADR-0051): a live preview of a `*.kstep.kts` script. Start with
 * `./gradlew :kstep-desktop:run --args="<script>"`; the optional first argument is a script, a Markdown note or a folder to open (judged like a dropped path, ADR-0083). All UI code
 * lives in the `ui` package.
 */
fun main(args: Array<String>) {
    // Before the first logger exists: Koog logs whole prompts at DEBUG (ADR-0056).
    AiLogging.quiet()
    runDesktopApp(args)
}
