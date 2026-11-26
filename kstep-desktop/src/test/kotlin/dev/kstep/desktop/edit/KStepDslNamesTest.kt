package dev.kstep.desktop.edit

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** The highlighted DSL names must match the script sources: complete and without stale entries (ADR-0089). */
class KStepDslNamesTest :
    StringSpec({
        val repo =
            generateSequence(Path.of(System.getProperty("kstep.desktop.fixtures")).toAbsolutePath()) { it.parent }
                .first { Files.isDirectory(it.resolve("kstep-script")) }

        fun sources(module: String): List<String> =
            Files.walk(repo.resolve(module).resolve("src/main")).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.map { Files.readString(it) }.toList()
            }

        val scriptSources = sources("kstep-script")

        /** The text of `class <name> ... {` up to its closing brace (brace counting; the sources have no braces in strings there). */
        fun classBody(name: String): String {
            val text = scriptSources.first { Regex("""\bclass $name\b""").containsMatchIn(it) }
            val start = text.indexOf("{", Regex("""\bclass $name\b""").find(text)!!.range.last)
            var depth = 0
            for (i in start until text.length) {
                if (text[i] == '{') depth++
                if (text[i] == '}' && --depth == 0) return text.substring(start, i)
            }
            error("unbalanced class $name")
        }

        val memberFun = Regex("""^    fun\s+(?:<[^>]*>\s*)?(\w+)\(""", RegexOption.MULTILINE)
        val topLevelFun = Regex("""^fun\s+(?:<[^>]*>\s*)?(\w+)\(""", RegexOption.MULTILINE)

        "every script name is complete against the kstep-script sources" {
            val members =
                listOf("KStepModelBuilder", "ExportBlockBuilder").flatMap { cls ->
                    memberFun.findAll(classBody(cls)).map { it.groupValues[1] }.toList()
                }
            val topLevel = scriptSources.flatMap { src -> topLevelFun.findAll(src).map { it.groupValues[1] }.toList() }
            (members.toSet().size >= 8) shouldBe true
            (topLevel.toSet().size >= 3) shouldBe true
            // validateModel is an extension of KStepModel, not an entry point of a script
            val declared = (members + topLevel).toSet() - "validateModel"
            (declared - KStepDslNames.scriptNames).shouldBeEmpty()
        }

        "no script name is invented" {
            val all = scriptSources.joinToString("\n")
            KStepDslNames.scriptNames
                .filter { !Regex("""\bfun\s+(<[^>]*>\s*)?$it\(""").containsMatchIn(all) }
                .shouldBeEmpty()
        }

        "every library name is declared in the shape or geometry sources" {
            val sources = sources("kstep-shape") + sources("kstep-geometry")
            KStepDslNames.libraryNames
                .filter { name ->
                    val declaration = Regex("""\b(fun|class|object|val|var)\s+(<[^>]*>\s*)?([\w.]+\.)?$name\b""")
                    sources.none { declaration.containsMatchIn(it) }
                }.shouldBeEmpty()
        }

        "no name is a Kotlin keyword" {
            KStepDslNames.names.filter { it in KotlinTokenizer.keywords }.shouldBeEmpty()
        }
    })
