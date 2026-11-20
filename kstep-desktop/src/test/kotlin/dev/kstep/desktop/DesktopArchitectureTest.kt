package dev.kstep.desktop

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/** Structure rules of ADR-0051, checked on the sources: scripts are never evaluated in the desktop JVM. */
class DesktopArchitectureTest :
    StringSpec({
        val root =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .map { it.resolve("src/main/kotlin") }
                .first { Files.isDirectory(it) }

        fun sources(): List<Pair<Path, String>> =
            Files.walk(root).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.map { it to Files.readString(it) }.toList()
            }

        "the desktop JVM never evaluates a script itself" {
            val forbidden = listOf("KStepScriptHost", "PreviewRenderer", "KStepScript", "runRender(")
            sources()
                .flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }

        "Compose is only used in the ui package" {
            sources()
                .filter { (path, _) ->
                    !root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .contains("/ui/")
                }.filter { (_, text) -> text.contains("androidx.compose") || text.contains("org.jetbrains.compose") }
                .map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the pick package is pure logic: no Compose and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/pick/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") || text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the export package is pure logic: no Compose and no UI package" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/export/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") || text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "worker processes are only started through WorkerEnvironment" {
            sources()
                .filter { (path, text) ->
                    !path.toString().endsWith("WorkerEnvironment.kt") &&
                        text.contains("ProcessBuilder")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the editor package is independent of the UI and of the watcher internals" {
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/edit/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("FileChangeWatcher") ||
                        text.contains("androidx.compose")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "Koog and the AI module's internals are not used directly in the desktop sources" {
            sources()
                .filter { (_, text) -> text.contains("import ai.koog") || text.contains("java.net.http") }
                .map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "only AiController touches the key store and the settings file" {
            sources()
                .filter { (path, text) ->
                    !path.toString().endsWith("AiController.kt") &&
                        !path.toString().contains("/ui/") &&
                        (text.contains("KeychainBackend") || text.contains("SettingsStore") || text.contains("Secret."))
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }

        "the okf package is pure logic: no Compose, no UI, no process start, no script evaluation, no network" {
            val forbidden =
                listOf(
                    "dev.kstep.desktop.ui",
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "ProcessBuilder",
                    "KStepScriptHost",
                    "PreviewRenderer",
                    "java.net.http",
                )
            sources()
                .filter { (path, _) ->
                    root
                        .relativize(path)
                        .toString()
                        .replace('\\', '/')
                        .startsWith("dev/kstep/desktop/okf/")
                }.flatMap { (path, text) ->
                    forbidden.filter { text.contains(it) }.map { "${root.relativize(path)} uses $it" }
                }.shouldBeEmpty()
        }
        "the recent and drop packages are pure logic: no Compose, no UI, no process start, no script evaluation" {
            sources()
                .filter { (path, _) ->
                    val rel = root.relativize(path).toString().replace('\\', '/')
                    rel.startsWith("dev/kstep/desktop/recent/") || rel.startsWith("dev/kstep/desktop/drop/")
                }.filter { (_, text) ->
                    text.contains("dev.kstep.desktop.ui") ||
                        text.contains("androidx.compose") ||
                        text.contains("org.jetbrains.compose") ||
                        text.contains("ProcessBuilder")
                }.map { root.relativize(it.first).toString() }
                .shouldBeEmpty()
        }
    })
