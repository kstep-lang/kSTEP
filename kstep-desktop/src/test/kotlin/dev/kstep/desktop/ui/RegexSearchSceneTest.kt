@file:OptIn(InternalComposeUiApi::class)

package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.edit.EditorSession
import dev.kstep.desktop.edit.EditorState
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.Bitmap
import kotlin.time.Duration.Companion.seconds

/** The regex and whole-word switches of the find bar, and the delayed error of an invalid pattern (ADR-0090). */
class RegexSearchSceneTest :
    StringSpec({
        fun SceneClock.click(
            x: Float,
            y: Float,
        ) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            frame()
        }

        /** The x of the first click that reaches each switch, found by sweeping a standalone bar. */
        fun locate(): Map<String, Float> {
            val hits = linkedMapOf<String, Float>()
            val scene = ImageComposeScene(width = 800, height = 100, density = Density(1f))
            val clock = SceneClock(scene)
            try {
                var current = 0f
                scene.setContent {
                    Column {
                        FindBar(
                            bar = EditorBar.Find(false, 1),
                            initialQuery = "",
                            initialReplacement = "",
                            colors = EditorColors.LIGHT,
                            counter = "",
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
                            onMatchCase = { hits.putIfAbsent("case", current) },
                            onWholeWord = { hits.putIfAbsent("word", current) },
                            onRegex = { hits.putIfAbsent("regex", current) },
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
                clock.frame()
                var x = 230f
                while (x < 420f) {
                    current = x
                    clock.click(x, 16f)
                    x += 1f
                }
            } finally {
                scene.close()
            }
            return hits
        }

        "the bar has the three switches in order: case, whole word, regex".config(timeout = 60.seconds) {
            val hits = locate()
            hits.keys.toList() shouldBe listOf("case", "word", "regex")
            (hits.getValue("case") < hits.getValue("word") && hits.getValue("word") < hits.getValue("regex")) shouldBe
                true
        }

        "an invalid pattern shows its error only after 300 ms of scene time".config(timeout = 120.seconds) {
            val x = locate().getValue("regex") + 4f
            val file = TestSupport.script(TestSupport.newDir(), "rx", "f(x) + g(y)\n")
            val session = EditorSession()
            val scene = ImageComposeScene(width = 900, height = 400, density = Density(1f))
            val clock = SceneClock(scene)
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                scene.setContent {
                    ScriptEditor(
                        session,
                        session.view.value,
                        emptyList(),
                        dark = false,
                        jump = null,
                        onFocusChanged = {},
                        modifier = Modifier,
                        findRequest = FindRequest("(", 1),
                    )
                }
                clock.advance(500)

                // the top border row of the query field: grey normally, red when the pattern is invalid
                fun border(): Int {
                    val bmp = Bitmap.makeFromImage(scene.render(clock.nanos))
                    return bmp.getColor(60, 4)
                }
                val red = 0xFFC62828.toInt()
                val before = border()
                (before != red) shouldBe true

                clock.click(x, 16f) // regex on: "(" is an unbalanced pattern
                clock.advance(200)
                withClue("at 200 ms") { (border() != red) shouldBe true }
                clock.advance(300)
                withClue("at 500 ms") { border() shouldBe red }

                clock.click(x, 16f) // regex off again: a literal "("
                clock.advance(100)
                withClue("literal again") { (border() != red) shouldBe true }
            } finally {
                scene.close()
                session.close()
            }
        }
    })
