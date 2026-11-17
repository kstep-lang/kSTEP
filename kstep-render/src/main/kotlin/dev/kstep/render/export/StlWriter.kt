package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary STL writer (ADR-0049). Unit: millimetre by convention (STL has no unit field). The whole scene is
 * ONE merged triangle soup with every placement applied. Colours cannot be carried and are dropped explicitly.
 * Hostile input (non-finite, not float32-representable coordinates) fails closed with an
 * [IllegalArgumentException]; collapsed triangles are dropped and counted. Deterministic: no timestamp.
 */
object StlWriter {
    private const val HEADER_TEXT = "kSTEP binary STL, unit mm"
    private const val RECORD_BYTES = 50

    fun write(scene: MeshExportScene): MeshExportResult {
        MeshExportSupport.validate(scene)
        val maxTriangles = scene.parts.sumOf { it.mesh.triangleCount.toLong() * it.placements.size }.toInt()
        val records = ByteBuffer.allocate(maxTriangles * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        var written = 0
        var dropped = 0
        val f = FloatArray(9)
        for ((pi, part) in scene.parts.withIndex()) {
            val c = part.mesh.coordinates
            for (placement in part.placements) {
                val m = placement.rowMajor3x4()
                for (t in 0 until part.mesh.triangleCount) {
                    for (k in 0 until 3) {
                        val x = c[9 * t + 3 * k]
                        val y = c[9 * t + 3 * k + 1]
                        val z = c[9 * t + 3 * k + 2]
                        val wx = m[0] * x + m[1] * y + m[2] * z + m[3]
                        val wy = m[4] * x + m[5] * y + m[6] * z + m[7]
                        val wz = m[8] * x + m[9] * y + m[10] * z + m[11]
                        require(
                            wx.isFinite() &&
                                wy.isFinite() &&
                                wz.isFinite() &&
                                Math.abs(wx) <= Float.MAX_VALUE &&
                                Math.abs(wy) <= Float.MAX_VALUE &&
                                Math.abs(wz) <= Float.MAX_VALUE,
                        ) {
                            "mesh export refused: coordinate not representable as float32 in part '${scene.nameOf(
                                pi,
                            )}'"
                        }
                        f[3 * k] = wx.toFloat()
                        f[3 * k + 1] = wy.toFloat()
                        f[3 * k + 2] = wz.toFloat()
                    }
                    val ux = f[3].toDouble() - f[0]
                    val uy = f[4].toDouble() - f[1]
                    val uz = f[5].toDouble() - f[2]
                    val vx = f[6].toDouble() - f[0]
                    val vy = f[7].toDouble() - f[1]
                    val vz = f[8].toDouble() - f[2]
                    val nx = uy * vz - uz * vy
                    val ny = uz * vx - ux * vz
                    val nz = ux * vy - uy * vx
                    val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
                    if (!(len > 0.0) || !len.isFinite()) {
                        dropped++
                        continue
                    }
                    records.putFloat((nx / len).toFloat()).putFloat((ny / len).toFloat()).putFloat((nz / len).toFloat())
                    for (v in f) records.putFloat(v)
                    records.putShort(0)
                    written++
                }
            }
        }
        require(written > 0) { "nothing to export" }
        val out = ByteBuffer.allocate(84 + written * RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val header = ByteArray(80)
        HEADER_TEXT.toByteArray(Charsets.US_ASCII).copyInto(header)
        out.put(header)
        out.putInt(written)
        out.put(records.array(), 0, written * RECORD_BYTES)

        val topo = MeshExportSupport.topology(scene)
        val warnings = topo.warnings.toMutableList()
        val colours =
            scene.parts
                .filter { it.color != MeshColor.NEUTRAL }
                .map { it.color }
                .distinct()
                .size
        if (colours > 0) {
            warnings += "kstep render: stl carries no colours; $colours colour(s) dropped (use 3mf or glb)"
        }
        if (scene.buildItemCount > 1) {
            warnings +=
                "kstep render: stl merges ${scene.buildItemCount} placed instance(s) into one mesh; overlapping parts are not unioned."
        }
        if (dropped > 0) warnings += "kstep render: $dropped degenerate triangle(s) dropped from the stl."
        return MeshExportResult(
            bytes = out.array(),
            format = "stl",
            triangleCount = written,
            vertexCount = written * 3,
            droppedTriangleCount = dropped,
            partCount = scene.parts.size,
            buildItemCount = scene.buildItemCount,
            watertight = topo.watertight,
            openEdgeCount = topo.open,
            nonManifoldEdgeCount = topo.nonManifold,
            colorsDropped = colours > 0,
            warnings = warnings,
        )
    }
}
