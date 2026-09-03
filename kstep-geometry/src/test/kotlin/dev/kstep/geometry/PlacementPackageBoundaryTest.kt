package dev.kstep.geometry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Enforces, as a real automated check rather than a review intention, that `Placement.kt`/
 * `MeshComposition.kt` (`kstep-geometry`'s multi-shape-composition wave, see
 * `docs/adr/ADR-0013-multi-shape-composition-and-fill-light.adoc`) import nothing from
 * `dev.kstep.render` or Compose. Mirrors `dev.kstep.render.mesh`'s
 * `MeshPackageComposeBoundaryTest` -- this module has no Compose dependency at all (so a Compose
 * import here would already fail to compile), but a `dev.kstep.render` import specifically would
 * compile fine (nothing stops `kstep-geometry` depending on `kstep-render`) while silently
 * reversing this wave's whole point: `Vec3` is `internal` to `kstep-render`'s `mesh` package
 * precisely so `kstep-geometry` cannot reach for it and has its own, module-local vector
 * arithmetic instead (see `Placement`'s own KDoc).
 */
class PlacementPackageBoundaryTest :
    StringSpec({
        "Placement.kt and MeshComposition.kt import nothing from dev.kstep.render or Compose" {
            val geometryDir = File("src/main/kotlin/dev/kstep/geometry")
            geometryDir.exists() shouldBe true
            geometryDir.isDirectory shouldBe true

            val targetFiles = listOf(File(geometryDir, "Placement.kt"), File(geometryDir, "MeshComposition.kt"))
            targetFiles.forEach { file -> file.exists() shouldBe true }

            val offendingLines =
                targetFiles.flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        val trimmed = line.trimStart()
                        if (trimmed.startsWith("import dev.kstep.render.") ||
                            trimmed.startsWith("import androidx.compose.") ||
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
