package dev.kstep.desktop.export

import dev.kstep.desktop.run.DesktopState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ExportNoticeTest :
    StringSpec({
        val file = Path.of("/work/a.kstep.kts")
        val notice = ExportNotice.onExportSucceeded("Exported a.step (1 KB)", Path.of("/out"), file, 5)

        fun running(
            gen: Long,
            f: Path = file,
        ) = DesktopState.Running(f, gen, null, 0)

        fun retain(
            current: Path? = file,
            state: DesktopState = DesktopState.Idle,
            knowledge: Boolean = false,
            dialog: Boolean = false,
        ) = ExportNotice.retain(notice, current, state, knowledge, dialog)

        "it stays while nothing happens" {
            (retain() === notice) shouldBe true
        }

        "another file clears it" {
            retain(current = Path.of("/work/b.kstep.kts")).shouldBeNull()
            retain(current = null).shouldBeNull()
        }

        "a newer run clears it" {
            retain(state = running(6)).shouldBeNull()
        }

        "the run of the export itself (same or older generation) keeps it" {
            (retain(state = running(5)) === notice) shouldBe true
            (retain(state = running(3)) === notice) shouldBe true
        }

        "an open workspace clears it" {
            retain(knowledge = true).shouldBeNull()
        }

        "the next export dialog clears it" {
            retain(dialog = true).shouldBeNull()
        }

        "the same file written differently keeps it" {
            val relative = Path.of("/work/./sub/../a.kstep.kts")
            (retain(current = relative) === notice) shouldBe true
        }

        "a finished or failed run does not clear it" {
            val failed = DesktopState.Error(file, 9, emptyList(), null, java.time.Instant.EPOCH)
            (retain(state = failed) === notice) shouldBe true
        }

        "no notice stays no notice" {
            ExportNotice.retain(null, file, DesktopState.Idle, false, false).shouldBeNull()
        }
    })
