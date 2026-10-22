package dev.kstep.geometry.select

import kotlin.math.abs
import kotlin.math.sqrt

/** Synthetic edge/face geometry of an axis-aligned box [0,dx] x [0,dy] x [0,dz]: 12 edges, 6 faces. */
internal class BoxFixture(
    val dx: Double,
    val dy: Double,
    val dz: Double,
) {
    val edges: List<EdgeInfo>
    val faces: List<FaceInfo>

    init {
        data class Raw(
            val a: Point3,
            val b: Point3,
        )
        val raws = ArrayList<Raw>()
        for (y in listOf(0.0, dy)) for (z in listOf(0.0, dz)) raws.add(Raw(Point3(0.0, y, z), Point3(dx, y, z)))
        for (x in listOf(0.0, dx)) for (z in listOf(0.0, dz)) raws.add(Raw(Point3(x, 0.0, z), Point3(x, dy, z)))
        for (x in listOf(0.0, dx)) for (y in listOf(0.0, dy)) raws.add(Raw(Point3(x, y, 0.0), Point3(x, y, dz)))

        class FacePlane(
            val axis: Int,
            val value: Double,
            val normal: Direction3,
        )
        val planes =
            listOf(
                FacePlane(2, dz, Direction3.Z),
                FacePlane(2, 0.0, Direction3.NEG_Z),
                FacePlane(0, 0.0, Direction3.NEG_X),
                FacePlane(0, dx, Direction3.X),
                FacePlane(1, 0.0, Direction3.NEG_Y),
                FacePlane(1, dy, Direction3.Y),
            )

        fun coord(
            p: Point3,
            axis: Int,
        ) = when (axis) {
            0 -> p.x
            1 -> p.y
            else -> p.z
        }
        val faceEdgeIndices =
            planes.map { pl ->
                raws.indices.filter { i ->
                    coord(raws[i].a, pl.axis) == pl.value &&
                        coord(raws[i].b, pl.axis) == pl.value
                }
            }
        edges =
            raws.mapIndexed { i, r ->
                val v = doubleArrayOf(r.b.x - r.a.x, r.b.y - r.a.y, r.b.z - r.a.z)
                val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                EdgeInfo(
                    index = i,
                    kind = EdgeKind.LINE,
                    length = len,
                    start = r.a,
                    end = r.b,
                    mid = Point3((r.a.x + r.b.x) / 2, (r.a.y + r.b.y) / 2, (r.a.z + r.b.z) / 2),
                    direction = Direction3(v[0] / len, v[1] / len, v[2] / len),
                    circleCenter = null,
                    radius = null,
                    seam = false,
                    degenerated = false,
                    faceIndices = faceEdgeIndices.indices.filter { f -> i in faceEdgeIndices[f] },
                )
            }
        faces =
            planes.mapIndexed { f, pl ->
                val c = doubleArrayOf(dx / 2, dy / 2, dz / 2)
                c[pl.axis] = pl.value
                FaceInfo(f, FaceKind.PLANE, 1.0, Point3(c[0], c[1], c[2]), pl.normal, null, null, faceEdgeIndices[f])
            }
    }

    fun zEdges() = edges.filter { abs(it.direction!!.z) > 0.5 }.map { it.index }
}

/** Replaces edge [index] with a seam copy (for seam-exclusion tests). */
internal fun List<EdgeInfo>.withSeam(index: Int): List<EdgeInfo> =
    map {
        if (it.index ==
            index
        ) {
            it.copy(seam = true)
        } else {
            it
        }
    }
