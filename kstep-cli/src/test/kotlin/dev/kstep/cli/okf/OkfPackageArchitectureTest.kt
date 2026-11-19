package dev.kstep.cli.okf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

class OkfPackageArchitectureTest :
    StringSpec({
        "the okf package is pure logic: no Compose, no UI, no process, no script evaluation, no network, no log" {
            val dir = File("src/main/kotlin/dev/kstep/cli/okf")
            val files = dir.listFiles { f -> f.name.endsWith(".kt") }!!.toList()
            (files.size >= 6) shouldBe true
            val forbidden =
                listOf(
                    "androidx.compose",
                    "org.jetbrains.compose",
                    "dev.kstep.desktop",
                    "ProcessBuilder",
                    "KStepScriptHost",
                    "PreviewRenderer",
                    "java.net",
                    "KotlinLogging",
                    "println(",
                )
            files
                .flatMap { f -> forbidden.filter { f.readText().contains(it) }.map { "${f.name} uses $it" } }
                .shouldBeEmpty()
        }
    })
