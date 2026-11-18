package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.nio.file.Files
import java.nio.file.Path

/** Every highlighted DSL name must still exist in the script, shape or geometry sources (no stale names). */
class KStepDslNamesTest :
    StringSpec({
        "every DSL name is still declared in the script, shape or geometry sources" {
            val repo =
                generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                    .first { Files.isDirectory(it.resolve("kstep-script")) }
            val sources =
                listOf("kstep-script", "kstep-shape", "kstep-geometry").flatMap { module ->
                    Files.walk(repo.resolve(module).resolve("src/main")).use { stream ->
                        stream.filter { it.toString().endsWith(".kt") }.map { Files.readString(it) }.toList()
                    }
                }
            KStepDslNames.names
                .filter { name ->
                    val declaration = Regex("""\b(fun|class|object|val|var)\s+(<[^>]*>\s*)?([\w.]+\.)?$name\b""")
                    sources.none { declaration.containsMatchIn(it) }
                }.shouldBeEmpty()
        }
    })
