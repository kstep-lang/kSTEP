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
    })
