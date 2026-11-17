package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.Placement
import dev.kstep.geometry.TriangleMesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Outward-wound axis-aligned box [0,a]x[0,b]x[0,c] as 12 triangles. */
internal fun boxMesh(
    a: Double = 10.0,
    b: Double = 20.0,
    c: Double = 30.0,
): TriangleMesh {
    val quads =
        listOf(
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, c, 0.0, b, c, 0.0, b, 0.0),
            doubleArrayOf(a, 0.0, 0.0, a, b, 0.0, a, b, c, a, 0.0, c),
            doubleArrayOf(0.0, 0.0, 0.0, a, 0.0, 0.0, a, 0.0, c, 0.0, 0.0, c),
            doubleArrayOf(0.0, b, 0.0, 0.0, b, c, a, b, c, a, b, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 0.0, b, 0.0, a, b, 0.0, a, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, c, a, 0.0, c, a, b, c, 0.0, b, c),
        )
    val out = ArrayList<Double>()
    for (q in quads) {
        for (i in listOf(0, 1, 2, 0, 2, 3)) for (k in 0 until 3) out += q[3 * i + k]
    }
    return TriangleMesh(out.toDoubleArray())
}

internal fun part(
    name: String,
    mesh: TriangleMesh,
    vararg placements: Placement,
    color: MeshColor = MeshColor.NEUTRAL,
) = MeshExportPart(name, mesh, color, placements.toList().ifEmpty { listOf(Placement.IDENTITY) })

/** Signed volume of a triangle soup (divergence theorem). */
internal fun signedVolume(coords: DoubleArray): Double {
    var v = 0.0
    for (t in 0 until coords.size / 9) {
        val o = 9 * t
        val ax = coords[o]
        val ay = coords[o + 1]
        val az = coords[o + 2]
        val bx = coords[o + 3]
        val by = coords[o + 4]
        val bz = coords[o + 5]
        val cx = coords[o + 6]
        val cy = coords[o + 7]
        val cz = coords[o + 8]
        v += ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)
    }
    return v / 6.0
}

/** Independent binary-STL reader: returns (triangle count, vertex coordinates, normals, attribute bytes). */
internal class StlRead(
    val header: ByteArray,
    val count: Int,
    val coords: DoubleArray,
    val normals: DoubleArray,
    val attributes: List<Int>,
    val length: Int,
)

internal fun readStl(bytes: ByteArray): StlRead {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val header = ByteArray(80)
    b.get(header)
    val n = b.int
    val coords = DoubleArray(n * 9)
    val normals = DoubleArray(n * 3)
    val attrs = ArrayList<Int>()
    for (t in 0 until n) {
        for (k in 0 until 3) normals[3 * t + k] = b.float.toDouble()
        for (k in 0 until 9) coords[9 * t + k] = b.float.toDouble()
        attrs += b.short.toInt()
    }
    return StlRead(header, n, coords, normals, attrs, bytes.size)
}
