package dev.kstep.asciidoc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Enforces, as a real automated check rather than a review intention, that
 * `kstep-docs:kstep-asciidoc` never depends on `kstep-cli` -- see
 * docs/adr/ADR-0019-kstep-asciidoc.adoc's Decision 2: `kstep-cli` depends on THIS module to
 * expose the `asciidoc` subcommand, so the reverse edge would be a build cycle if it ever crept
 * in through a single stray import.
 */
class AsciidocModuleBoundaryTest :
    StringSpec({
        "no source file in this module imports dev.kstep.cli" {
            val srcDir = File("src/main/kotlin/dev/kstep/asciidoc")
            srcDir.exists() shouldBe true
            srcDir.isDirectory shouldBe true

            val kotlinFiles = srcDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            // Regression guard for the "scans 0 files and is trivially green" failure mode (see
            // kstep-render's MeshPackageComposeBoundaryTest, the precedent for this pattern).
            (kotlinFiles.size >= 5) shouldBe true

            val offendingLines =
                kotlinFiles.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if (line.trimStart().startsWith("import dev.kstep.cli")) {
                            "${file.path}:${index + 1}: ${line.trim()}"
                        } else {
                            null
                        }
                    }
                }
            offendingLines shouldBe emptyList()
        }
    })
