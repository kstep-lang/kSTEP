package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopState
import dev.kstep.desktop.run.LiveRunController
import java.nio.file.Files
import java.nio.file.Path

/** Helpers shared by the desktop tests (test sources only). */
object TestSupport {
    fun fixture(name: String): Path = Path.of(System.getProperty("kstep.desktop.fixtures"), name)

    fun newDir(): Path = Files.createTempDirectory("d1-test").toRealPath()

    fun script(
        dir: Path,
        name: String,
        text: String,
    ): Path = dir.resolve("$name.kstep.kts").also { Files.writeString(it, text) }

    fun copyFixture(
        dir: Path,
        name: String,
        target: String = name,
    ): Path = dir.resolve(target).also { Files.copy(fixture(name), it) }

    /** Polls [condition] until it holds or [timeoutMs] passed; returns whether it held. */
    fun await(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < end) {
            if (condition()) return true
            Thread.sleep(25)
        }
        return condition()
    }

    fun awaitState(
        controller: LiveRunController,
        timeoutMs: Long = 90_000,
        predicate: (DesktopState) -> Boolean,
    ): DesktopState {
        check(await(timeoutMs) { predicate(controller.state.value) }) {
            "state did not reach the expected condition; last state: ${controller.state.value::class.simpleName}"
        }
        return controller.state.value
    }

    /** Live render workers below this JVM. */
    fun liveWorkers(): List<ProcessHandle> =
        ProcessHandle
            .current()
            .descendants()
            .filter { handle ->
                val info = handle.info()
                val text = info.commandLine().orElse(info.arguments().map { it.joinToString(" ") }.orElse(""))
                text.contains("DesktopRenderWorker")
            }.toList()
}
