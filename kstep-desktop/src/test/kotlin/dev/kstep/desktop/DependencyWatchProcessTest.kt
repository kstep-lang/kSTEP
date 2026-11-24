package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import dev.kstep.desktop.run.WorkerRunner
import dev.kstep.desktop.watch.DependencyWatcher
import dev.kstep.desktop.watch.FileChangeWatcher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.time.Duration.Companion.seconds

private const val MODEL_TAIL =
    """
val appCtx = applicationContext { application = "configuration control" }.getOrThrow()
val prodCtx = productContext { name = "engineering"; frameOfReference = appCtx; disciplineType = "mechanical" }.getOrThrow()
val part = product("BRK-001") { name = "Bracket"; frameOfReference = setOf(prodCtx) }.getOrThrow()
stepFile(fileName = "t.step") { root(part) }
"""

private fun replaceAtomically(
    target: Path,
    text: String,
) {
    val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.writeString(tmp, text)
    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
}

/**
 * The whole chain for the files a script reads (ADR-0080) with real child processes, a real polling watcher and no
 * OCCT: the script has no geometry. Not named after OCCT, so it also runs where the bridge is missing.
 */
class DependencyWatchProcessTest :
    StringSpec({
        "changing an input re-runs the script, a quiet input does not, a deleted input runs once with KSTEP-S-017"
            .config(
                timeout = 400.seconds,
            ) {
                val dir = TestSupport.newDir()
                val session = LiveRunController.createSessionDir(dir)
                val runner = WorkerRunner(session)
                val controller =
                    LiveRunController(
                        runner = { f, g, s -> runner.run(f, g, s) },
                        watcherFactory = { f, onEvent -> FileChangeWatcher(f, onEvent = onEvent) },
                        sessionDir = session,
                        dependencyWatchFactory = { callback -> DependencyWatcher(onChange = callback) },
                    )
                try {
                    val input = dir.resolve("name.txt").also { Files.writeString(it, "one") }
                    val script =
                        TestSupport.script(dir, "dep", "check(readInput(\"name.txt\").isNotBlank())\n$MODEL_TAIL")
                    controller.open(script)
                    val first = TestSupport.awaitState(controller, 120_000) { it is DesktopState.Ok }
                    first.shouldBeInstanceOf<DesktopState.Ok>()

                    // the first poll of a new input only records it (ADR-0080, startup window): give it a moment
                    Thread.sleep(1_000)

                    // an atomic replace of the input gives a new generation, with no touch of the script
                    replaceAtomically(input, "two")
                    val second =
                        TestSupport.awaitState(controller, 60_000) {
                            it is DesktopState.Ok &&
                                it.generation > first.generation
                        }
                    second.generation shouldBe first.generation + 1

                    // two quiet seconds: nothing runs
                    Thread.sleep(2_000)
                    controller.state.value.generation shouldBe second.generation

                    // deleting the input runs once, the script reports the missing input, and then it stays quiet
                    Files.delete(input)
                    val failed =
                        TestSupport.awaitState(controller, 60_000) {
                            it is DesktopState.Error && it.generation > second.generation
                        }
                    failed
                        .shouldBeInstanceOf<DesktopState.Error>()
                        .errors
                        .first()
                        .code shouldBe "KSTEP-S-017"
                    Thread.sleep(2_500)
                    controller.state.value.generation shouldBe failed.generation

                    // the input returns: one more run, successful again
                    Files.writeString(input, "three")
                    TestSupport.awaitState(
                        controller,
                        60_000,
                    ) { it is DesktopState.Ok && it.generation > failed.generation }
                    (DesktopErrorCodes.DEPENDENCIES_IGNORED) shouldBe "KSTEP-D-037"
                } finally {
                    controller.close()
                    dir.toFile().deleteRecursively()
                }
            }
    })
