package dev.kstep.render.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Enforces, as a real automated check rather than a review intention, that
 * `dev.kstep.render.mesh` stays free of any Compose import -- see
 * docs/adr/ADR-0011-headless-preview-rendering.adoc's package-boundary decision (inherited from
 * ADR-0010's original `kstep-viewer` rule). This is what lets [IsometricProjectionTest] test the
 * projection math with no window, and what lets `TriangleRasterizerTest`/`RenderOcctPipelineTest`
 * reuse the exact same [MeshProjection.project] call the real Compose canvas (in
 * `kstep-viewer`) uses.
 *
 * The boundary this test enforces now covers this whole module (`kstep-render`), not just this
 * one sub-package -- `kstep-render`'s `build.gradle.kts` carries no Compose dependency at all, so
 * a Compose import anywhere in this module would already fail to compile; this test's own scope
 * stays the `mesh` package specifically because that is the package `kstep-viewer`'s real Compose
 * canvas (`ui/ViewerCanvas.kt`) imports from -- the one place a copy-pasted Compose import would
 * actually be plausible to introduce by accident.
 */
class MeshPackageComposeBoundaryTest :
    StringSpec({
        "the mesh package contains no androidx.compose or org.jetbrains.compose import" {
            val meshDir = File("src/main/kotlin/dev/kstep/render/mesh")
            meshDir.exists() shouldBe true
            meshDir.isDirectory shouldBe true

            val kotlinFiles = meshDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
            // Regression guard for the "scans 0 files and is trivially green" failure mode: after
            // the kstep-viewer -> kstep-render package move, a wrong `meshDir` path here would
            // silently pass with an empty file list instead of failing loudly. Vec3.kt,
            // ProjectedTriangle.kt, MeshProjection.kt (renamed from IsometricProjection.kt in
            // kSTEP's viewer-camera-interaction wave) and, since that same wave, Camera.kt are the
            // (at least) four files this package holds; this asserts the scan actually found them.
            (kotlinFiles.size >= 4) shouldBe true

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
