package dev.kstep.viewer.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Enforces, as a real automated check rather than a review intention, that
 * `dev.kstep.viewer.mesh` stays free of any Compose import -- see
 * docs/adr/ADR-0010-occt-triangulation-and-viewer.adoc's package-boundary decision. This is what
 * lets [IsometricProjectionTest] test the projection math with no window, and what lets
 * `ViewerRasterTest`/`ViewerOcctPipelineTest` reuse the exact same [IsometricProjection.project]
 * call the real Compose canvas uses.
 */
class MeshPackageComposeBoundaryTest :
    StringSpec({
        "the mesh package contains no androidx.compose or org.jetbrains.compose import" {
            val meshDir = File("src/main/kotlin/dev/kstep/viewer/mesh")
            meshDir.exists() shouldBe true
            meshDir.isDirectory shouldBe true

            val kotlinFiles = meshDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            (kotlinFiles.isNotEmpty()) shouldBe true

            val offendingLines =
                kotlinFiles.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import androidx.compose.") ||
                            trimmed.startsWith("import org.jetbrains.compose.")
                        ) {
                            "${file.path}:${index + 1}: $trimmed"
                        } else {
                            null
                        }
                    }
                }
            offendingLines shouldBe emptyList()
        }
    })
