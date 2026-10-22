package dev.kstep.preview

import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The image-size range applies to SVG and PNG only; GLB carries no image size. Pure Kotlin, no OCCT needed. */
class MeshPreviewImageSizeTest :
    StringSpec({
        val mesh =
            MeshComposition.mergeColored(
                listOf(PlacedMesh(TriangleMesh(doubleArrayOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0)))),
            )

        "an out-of-range size is rejected for SVG and PNG" {
            for (format in listOf(RenderFormat.SVG, RenderFormat.PNG)) {
                shouldThrow<IllegalArgumentException> { MeshPreview.render(mesh, format, 5, 768) }
                    .message shouldContain "out of range"
                shouldThrow<IllegalArgumentException> { MeshPreview.render(mesh, format, 1024, 5000) }
                    .message shouldContain "out of range"
            }
        }

        "GLB ignores the image size, so even absurd values render" {
            val result = MeshPreview.render(mesh, RenderFormat.GLB, 5, 8000)
            result.format shouldBe RenderFormat.GLB
            (result.bytes.size > 0) shouldBe true
        }
    })
