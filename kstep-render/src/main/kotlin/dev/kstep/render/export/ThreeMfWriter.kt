package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 3MF (3D Manufacturing Format, core spec 1.x) writer (ADR-0049). Unit millimetre. One `<object>` per
 * [MeshExportPart] (mesh in LOCAL coordinates, vertices welded on the 1e-6 mm grid), one `<build><item>` per
 * placement with a 3MF row-vector transform. Colours become `<basematerials>`; alpha is carried
 * (`#RRGGBBAA`), though many slicers ignore it. Hand-written XML (no java.xml dependency in the runtime
 * image). Deterministic for one JVM: fixed entry order/timestamps/deflate level, locale-free numbers.
 */
object ThreeMfWriter {
    private const val MAX_ABS_MM = 1e9
    private const val CORE_NS = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"

    private data class Key(
        val x: Long,
        val y: Long,
        val z: Long,
    )

    fun write(
        scene: MeshExportScene,
        toolName: String = "kSTEP",
    ): MeshExportResult {
        MeshExportSupport.validate(scene)
        val colours = LinkedHashMap<MeshColor, Int>()
        for (p in scene.parts) if (p.color != MeshColor.NEUTRAL) colours.getOrPut(p.color) { colours.size }

        val xml = StringBuilder(1 shl 16)
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"$CORE_NS\">\n")
        xml.append(" <metadata name=\"Title\">").append(xmlEscape(scene.title)).append("</metadata>\n")
        xml.append(" <metadata name=\"Application\">").append(xmlEscape(toolName)).append("</metadata>\n")
        xml.append(" <resources>\n")
        if (colours.isNotEmpty()) {
            xml.append("  <basematerials id=\"1\">\n")
            for ((color, i) in colours) {
                xml
                    .append(
                        "   <base name=\"c",
                    ).append(i + 1)
                    .append("\" displaycolor=\"")
                    .append(hex(color))
                    .append("\"/>\n")
            }
            xml.append("  </basematerials>\n")
        }
        var totalTriangles = 0
        var totalVertices = 0
        var dropped = 0
        for ((pi, part) in scene.parts.withIndex()) {
            val c = part.mesh.coordinates
            for (v in c) {
                require(Math.abs(v) <= MAX_ABS_MM) {
                    "mesh export refused: coordinate beyond +-1e9 mm in part '${scene.nameOf(pi)}'"
                }
            }
            val index = HashMap<Key, Int>()
            val vertexText = StringBuilder()
            val triText = StringBuilder()
            val n = part.mesh.triangleCount
            val ids = IntArray(3)
            val q = LongArray(9)
            for (t in 0 until n) {
                for (k in 0 until 3) {
                    val qx = MeshTopologyCheck.quantise(c[9 * t + 3 * k])
                    val qy = MeshTopologyCheck.quantise(c[9 * t + 3 * k + 1])
                    val qz = MeshTopologyCheck.quantise(c[9 * t + 3 * k + 2])
                    q[3 * k] = qx
                    q[3 * k + 1] = qy
                    q[3 * k + 2] = qz
                    val key = Key(qx, qy, qz)
                    var id = index[key]
                    if (id == null) {
                        id = index.size
                        index[key] = id
                        vertexText
                            .append("    <vertex x=\"")
                            .append(MeshExportSupport.fixed6(qx))
                            .append("\" y=\"")
                            .append(MeshExportSupport.fixed6(qy))
                            .append("\" z=\"")
                            .append(MeshExportSupport.fixed6(qz))
                            .append("\"/>\n")
                    }
                    ids[k] = id
                }
                if (ids[0] == ids[1] || ids[1] == ids[2] || ids[0] == ids[2] || zeroArea(q)) {
                    dropped++
                    continue
                }
                triText
                    .append("    <triangle v1=\"")
                    .append(ids[0])
                    .append("\" v2=\"")
                    .append(ids[1])
                    .append("\" v3=\"")
                    .append(ids[2])
                    .append("\"/>\n")
                totalTriangles++
            }
            totalVertices += index.size
            xml
                .append("  <object id=\"")
                .append(pi + 2)
                .append("\" type=\"model\" name=\"")
                .append(xmlEscape(scene.nameOf(pi)))
                .append('"')
            if (part.color != MeshColor.NEUTRAL) {
                xml.append(" pid=\"1\" pindex=\"").append(colours.getValue(part.color)).append('"')
            }
            xml
                .append(">\n   <mesh>\n    <vertices>\n")
                .append(vertexText)
                .append("    </vertices>\n    <triangles>\n")
                .append(triText)
                .append("    </triangles>\n   </mesh>\n  </object>\n")
        }
        require(totalTriangles > 0) { "nothing to export" }
        xml.append(" </resources>\n <build>\n")
        for ((pi, part) in scene.parts.withIndex()) {
            for (placement in part.placements) {
                val m = placement.rowMajor3x4()
                require(
                    m.all { it.isFinite() && Math.abs(it) <= MAX_ABS_MM },
                ) { "mesh export refused: invalid placement" }
                xml.append("  <item objectid=\"").append(pi + 2).append('"')
                if (!isIdentity(m)) {
                    // 3MF multiplies row vectors: the matrix is the transposed rotation, translation last.
                    val t =
                        doubleArrayOf(
                            m[0],
                            m[4],
                            m[8],
                            m[1],
                            m[5],
                            m[9],
                            m[2],
                            m[6],
                            m[10],
                            m[3],
                            m[7],
                            m[11],
                        )
                    xml
                        .append(
                            " transform=\"",
                        ).append(t.joinToString(" ") { MeshExportSupport.decimal(it) })
                        .append('"')
                }
                xml.append("/>\n")
            }
        }
        xml.append(" </build>\n</model>\n")

        val bytes = zip(xml.toString())
        val topo = MeshExportSupport.topology(scene)
        val warnings = topo.warnings.toMutableList()
        if (dropped > 0) warnings += "kstep render: $dropped degenerate triangle(s) dropped from the 3mf."
        return MeshExportResult(
            bytes = bytes,
            format = "3mf",
            triangleCount = totalTriangles,
            vertexCount = totalVertices,
            droppedTriangleCount = dropped,
            partCount = scene.parts.size,
            buildItemCount = scene.buildItemCount,
            watertight = topo.watertight,
            openEdgeCount = topo.open,
            nonManifoldEdgeCount = topo.nonManifold,
            colorsDropped = false,
            warnings = warnings,
        )
    }

    /** Collinear on the welding grid (zero cross product). */
    private fun zeroArea(q: LongArray): Boolean {
        val ux = (q[3] - q[0]).toDouble()
        val uy = (q[4] - q[1]).toDouble()
        val uz = (q[5] - q[2]).toDouble()
        val vx = (q[6] - q[0]).toDouble()
        val vy = (q[7] - q[1]).toDouble()
        val vz = (q[8] - q[2]).toDouble()
        return uy * vz - uz * vy == 0.0 && uz * vx - ux * vz == 0.0 && ux * vy - uy * vx == 0.0
    }

    private fun isIdentity(m: DoubleArray): Boolean =
        m[0] == 1.0 &&
            m[1] == 0.0 &&
            m[2] == 0.0 &&
            m[3] == 0.0 &&
            m[4] == 0.0 &&
            m[5] == 1.0 &&
            m[6] == 0.0 &&
            m[7] == 0.0 &&
            m[8] == 0.0 &&
            m[9] == 0.0 &&
            m[10] == 1.0 &&
            m[11] == 0.0

    private fun hex(color: MeshColor): String {
        fun ch(v: Double) = Math.round(v * 255.0).toInt().coerceIn(0, 255)
        val base = String.format(java.util.Locale.ROOT, "#%02X%02X%02X", ch(color.r), ch(color.g), ch(color.b))
        return if (color.a < 1.0) base + String.format(java.util.Locale.ROOT, "%02X", ch(color.a)) else base
    }

    /** Escapes the five XML specials and drops characters illegal in XML 1.0. */
    internal fun xmlEscape(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val legal =
                cp == 0x9 ||
                    cp == 0xA ||
                    cp == 0xD ||
                    cp in 0x20..0xD7FF ||
                    cp in 0xE000..0xFFFD ||
                    cp in 0x10000..0x10FFFF
            if (!legal) continue
            when (cp) {
                '&'.code -> sb.append("&amp;")
                '<'.code -> sb.append("&lt;")
                '>'.code -> sb.append("&gt;")
                '"'.code -> sb.append("&quot;")
                '\''.code -> sb.append("&apos;")
                else -> sb.appendCodePoint(cp)
            }
        }
        return sb.toString()
    }

    private fun zip(model: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setLevel(Deflater.BEST_COMPRESSION)

            fun entry(
                name: String,
                text: String,
            ) {
                val e = ZipEntry(name)
                e.setTimeLocal(LocalDateTime.of(1980, 1, 1, 0, 0, 0))
                z.putNextEntry(e)
                z.write(text.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            entry(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                    " <Default Extension=\"rels\" " +
                    "ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                    " <Default Extension=\"model\" " +
                    "ContentType=\"application/vnd.ms-package.3dmanufacturing-3dmodel+xml\"/>\n" +
                    "</Types>\n",
            )
            entry(
                "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                    " <Relationship Target=\"/3D/3dmodel.model\" Id=\"rel0\" " +
                    "Type=\"http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel\"/>\n" +
                    "</Relationships>\n",
            )
            entry("3D/3dmodel.model", model)
        }
        return out.toByteArray()
    }
}
