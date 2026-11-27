package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.run.DesktopError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Headless layout and click checks of the error bar entries and of the find and go-to bars (ADR-0067). */
class EditorBarsSceneTest :
    StringSpec({
        val density = 1f

        "an entry with a position is clickable, one without is not".config(timeout = 60.seconds) {
            val clicked = mutableListOf<DesktopError>()
            val withLine = DesktopError("E1", 3, 4, "boom")
            val without = DesktopError("E2", null, null, "bang")
            val scene = ImageComposeScene(width = 800, height = 400, density = Density(density))
            try {
                scene.setContent {
                    Column(Modifier.fillMaxSize()) {
                        ErrorBar(
                            listOf(withLine, without),
                            dark = false,
                            maxHeight = 200.dp,
                            initiallyExpanded = true,
                            selected = withLine,
                            onSelect = { clicked += it },
                        )
                    }
                }
                scene.render()
                // the first entry is at the top (y about 8), the second right below it
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, Offset(60f, 10f))
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, Offset(60f, 10f))
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, Offset(60f, 10f))
                scene.render()
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, Offset(60f, 34f))
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press, Offset(60f, 34f))
                scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release, Offset(60f, 34f))
                scene.render()
            } finally {
                scene.close()
            }
            clicked shouldBe listOf(withLine)
        }

        "a marked entry keeps the bar height" {
            var plain = 0
            var marked = 0
            val e = DesktopError("E1", 1, 1, "boom")
            for (sel in listOf<DesktopError?>(null, e)) {
                val scene = ImageComposeScene(width = 800, height = 300)
                try {
                    scene.setContent {
                        ErrorBar(
                            listOf(e),
                            dark = true,
                            maxHeight = 200.dp,
                            selected = sel,
                            modifier =
                                Modifier.onSizeChanged {
                                    if (sel ==
                                        null
                                    ) {
                                        plain = it.height
                                    } else {
                                        marked = it.height
                                    }
                                },
                        )
                    }
                    scene.render()
                } finally {
                    scene.close()
                }
            }
            marked shouldBe plain
        }

        "the find bar is one 32 dp line, two with the replace row" {
            val heights = mutableListOf<Int>()
            for (withReplace in listOf(false, true)) {
                val scene = ImageComposeScene(width = 800, height = 300, density = Density(1f))
                try {
                    scene.setContent {
                        Column(Modifier.onSizeChanged { heights += it.height }) {
                            FindBar(
                                bar = EditorBar.Find(withReplace, 1),
                                initialQuery = "",
                                initialReplacement = "",
                                colors = EditorColors.LIGHT,
                                counter = "No matches",
                                matchCase = false,
                                wholeWord = false,
                                regex = false,
                                queryInvalid = false,
                                canReplace = true,
                                replaceAllEnabled = true,
                                replaceAllHint = null,
                                notice = null,
                                onQuery = {},
                                onReplacement = {},
                                onMatchCase = {},
                                onWholeWord = {},
                                onRegex = {},
                                onPrevious = {},
                                onNext = {},
                                onReplace = {},
                                onReplaceAll = {},
                                onClose = {},
                                onQueryFocus = {},
                                onReplaceFocus = {},
                            )
                        }
                    }
                    scene.render()
                } finally {
                    scene.close()
                }
            }
            heights shouldBe listOf(32, 64)
        }

        "the go-to bar stays 32 dp with and without a message" {
            val heights = mutableListOf<Int>()
            val scene = ImageComposeScene(width = 800, height = 300, density = Density(1f))
            try {
                for (input in listOf("", "abc", "9999")) {
                    var h = 0
                    scene.setContent {
                        Column(Modifier.onSizeChanged { h = it.height }) {
                            GoToBar(EditorBar.GoTo(1), 50, EditorColors.DARK, {}, input, {}, {})
                        }
                    }
                    scene.render()
                    heights += h
                }
            } finally {
                scene.close()
            }
            heights shouldBe listOf(32, 32, 32)
        }
    })
