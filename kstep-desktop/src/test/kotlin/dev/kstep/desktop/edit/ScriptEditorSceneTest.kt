package dev.kstep.desktop.edit

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.Severity
import dev.kstep.desktop.ui.EditorJump
import dev.kstep.desktop.ui.ScriptEditor
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** Headless composition of the editor (no window): it renders, and conflict and preview-only banners do not crash. */
class ScriptEditorSceneTest :
    StringSpec({
        fun render(
            session: EditorSession,
            errors: List<DesktopError>,
            jump: EditorJump?,
        ) {
            val scene = ImageComposeScene(width = 700, height = 400)
            try {
                scene.setContent {
                    val view = session.view.value
                    ScriptEditor(
                        session,
                        view,
                        errors,
                        dark = false,
                        jump = jump,
                        onFocusChanged = {},
                        modifier = Modifier,
                    )
                }
                scene.render()
                scene.render(100_000_000)
            } finally {
                scene.close()
            }
        }

        "the editor renders text, marks and a jump target".config(timeout = 60.seconds) {
            val file = TestSupport.script(TestSupport.newDir(), "s", "val a = shape(1) // c\nval b = \"x\"\n")
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Editing } shouldBe true
                render(
                    session,
                    listOf(
                        DesktopError("KSTEP-S-001", 2, 5, "boom"),
                        DesktopError("W", 1, null, "w", Severity.WARNING),
                    ),
                    EditorJump(2, 1),
                )
                session.onTextChanged("val a = 1\n", 9)
                render(session, emptyList(), null)
            } finally {
                session.close()
            }
        }

        "the editor shows the preview-only banner for a file that is too large".config(timeout = 60.seconds) {
            val dir = TestSupport.newDir()
            val file = dir.resolve("big.kstep.kts")
            java.nio.file.Files
                .write(file, ByteArray(EditorText.MAX_BYTES + 1) { 'a'.code.toByte() })
            val session = EditorSession()
            try {
                session.open(file)
                TestSupport.await(30_000) { session.view.value.state is EditorState.Unavailable } shouldBe true
                render(session, emptyList(), null)
            } finally {
                session.close()
            }
        }
    })
