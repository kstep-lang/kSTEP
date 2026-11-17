package dev.kstep.desktop

import dev.kstep.desktop.ui.runDesktopApp

/**
 * Entry point of kSTEP Desktop (ADR-0051): a live preview of a `*.kstep.kts` script. Start with
 * `./gradlew :kstep-desktop:run --args="<script>"`; the optional first argument is the script to open. All UI code
 * lives in the `ui` package.
 */
fun main(args: Array<String>) = runDesktopApp(args)
