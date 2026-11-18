package dev.kstep.desktop.okf

import dev.kstep.desktop.TestSupport
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.RunResult
import dev.kstep.desktop.run.WorkerRunner
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import kotlin.time.Duration.Companion.seconds

/** A block of a note through the real supervised worker (real child processes, no OCCT needed). */
class KnowledgeWorkerProcessTest :
    StringSpec({
        fun controller(
            sessionTimeoutMs: Long,
            root: java.nio.file.Path,
            note: String,
        ): Pair<KnowledgeWorkspaceController, java.nio.file.Path> {
            val session = BlockSessionFile.createSessionDir()
            val runner = WorkerRunner(session, timeoutMillis = sessionTimeoutMs)
            val c =
                KnowledgeWorkspaceController(
                    workerRun = { f, g, s -> runner.run(f, g, s) },
                    sessionDir = session,
                    scheduler = { _, _ -> AutoCloseable { } },
                )
            Files.writeString(root.resolve("n.md"), note)
            c.openFolder(root)
            TestSupport.await(30_000) { c.workspace.value is WorkspaceState.Open } shouldBe true
            c.trustFolder()
            return c to session
        }

        "a syntax error in a block is reported on the line of the note".config(timeout = 240.seconds) {
            val root = TestSupport.newDir()
            val (c, session) = controller(90_000, root, "intro\n\nmore\n```kstep\nval ok = 1\nval = = 2\n```\n")
            try {
                val md = root.resolve("n.md")
                c.onFileOpened(md)
                val failure = c.runBlock(md, 1) {}.shouldBeInstanceOf<RunResult.Failure>()
                val error = failure.errors.first()
                error.code.startsWith("KSTEP-S") shouldBe true
                (error.line != null && error.line!! in 5..6) shouldBe true
                error.message.contains("block-1.kstep.kts") shouldBe false
                Files.exists(session.resolve("block-1.kstep.kts")) shouldBe false
            } finally {
                c.close()
                root.toFile().deleteRecursively()
            }
        }

        "an endless block is stopped with KSTEP-D-001, leaves no worker and no session file".config(
            timeout = 240.seconds,
        ) {
            val root = TestSupport.newDir()
            val (c, session) = controller(5_000, root, "```kstep\nwhile (true) {}\n```\n")
            try {
                val md = root.resolve("n.md")
                c.onFileOpened(md)
                val failure = c.runBlock(md, 1) {}.shouldBeInstanceOf<RunResult.Failure>()
                failure.errors.single().code shouldBe DesktopErrorCodes.TIMEOUT
                TestSupport.await(30_000) { TestSupport.liveWorkers().isEmpty() } shouldBe true
                Files.exists(session.resolve("block-1.kstep.kts")) shouldBe false
            } finally {
                c.close()
                root.toFile().deleteRecursively()
            }
        }
    })
