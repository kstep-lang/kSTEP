package dev.kstep.preview

import dev.kstep.geometry.ColoredMesh
import dev.kstep.render.RenderLimits
import dev.kstep.render.gltf.GlbExtras
import dev.kstep.render.gltf.GlbWriteResult
import dev.kstep.render.gltf.GlbWriter
import dev.kstep.render.image.TriangleRasterizer
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.mesh.ProjectedTriangle
import dev.kstep.render.svg.TriangleSvgWriter
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Result of [MeshPreview.render]. [glb] is non-null only for [RenderFormat.GLB]. */
class MeshPreviewResult(
    val bytes: ByteArray,
    val format: RenderFormat,
    val triangleCount: Int,
    val glb: GlbWriteResult?,
)

/**
 * Renders an already-built mesh -- no script, no model -- to SVG, PNG or GLB bytes. Extracted from
 * [PreviewRenderer] (ADR-0030, Welle K) so that `kstep import --render` and `kstep render` share the
 * same encoders; [PreviewRenderer]'s SVG/PNG output is byte-identical to before. Performs no file I/O.
 */
object MeshPreview {
    /** SVG text for already-projected triangles. */
    internal fun svgBytes(
        triangles: List<ProjectedTriangle>,
        width: Int,
        height: Int,
    ): ByteArray = TriangleSvgWriter.render(triangles, width, height).toByteArray(Charsets.UTF_8)

    /** PNG bytes for already-projected triangles. */
    internal fun pngBytes(
        triangles: List<ProjectedTriangle>,
        width: Int,
        height: Int,
    ): ByteArray {
        val image = TriangleRasterizer.render(triangles, width, height)
        val out = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", out)) {
            throw PreviewEncodingException("no ImageIO writer available for 'png'")
        }
        return out.toByteArray()
    }

    /**
     * @param format SVG, PNG or GLB (TEXT and AUTO are not meshes' formats)
     * @throws IllegalArgumentException for an unsupported [format] or an out-of-range image size (SVG/PNG only).
     */
    fun render(
        mesh: ColoredMesh,
        format: RenderFormat,
        width: Int,
        height: Int,
        extras: GlbExtras = GlbExtras.EMPTY,
    ): MeshPreviewResult {
        require(format == RenderFormat.SVG || format == RenderFormat.PNG || format == RenderFormat.GLB) {
            "a mesh can only be rendered as SVG, PNG or GLB, got $format"
        }
        // GLB is vector geometry without an image size: width and height are ignored there.
        require(
            format == RenderFormat.GLB ||
                (
                    width in RenderLimits.MIN_DIMENSION_PX..RenderLimits.MAX_DIMENSION_PX &&
                        height in RenderLimits.MIN_DIMENSION_PX..RenderLimits.MAX_DIMENSION_PX &&
                        width.toLong() * height.toLong() <= RenderLimits.MAX_TOTAL_PIXELS
                ),
        ) { "image size ${width}x$height is out of range" }
        return when (format) {
            RenderFormat.GLB -> {
                val result = GlbWriter.write(mesh, extras)
                MeshPreviewResult(result.bytes, format, mesh.mesh.triangleCount, result)
            }
            else -> {
                val triangles = MeshProjection.project(mesh, width.toDouble(), height.toDouble())
                val bytes =
                    if (format ==
                        RenderFormat.SVG
                    ) {
                        svgBytes(triangles, width, height)
                    } else {
                        pngBytes(triangles, width, height)
                    }
                MeshPreviewResult(bytes, format, triangles.size, null)
            }
        }
    }
}
