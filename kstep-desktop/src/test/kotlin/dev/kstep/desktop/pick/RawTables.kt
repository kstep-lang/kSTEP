package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import dev.kstep.desktop.model.PartTable

/**
 * Part tables built without a mesh, so a test can use as many parts as it wants and names the part-list parser would
 * refuse (control and bidirectional characters). One triangle per part; bounding boxes are 10 mm cubes at x = 20 * i.
 */
object RawTables {
    fun table(names: List<String>): PartTable =
        PartTable(
            names.mapIndexed { i, name ->
                PartInfo(
                    name = name,
                    firstTriangle = i,
                    triangleCount = 1,
                    placement =
                        PartPlacement(
                            doubleArrayOf(0.0, 0.0, 0.0),
                            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
                        ),
                    colorHex = "#808080",
                    bboxMin = doubleArrayOf(20.0 * i, 0.0, 0.0),
                    bboxMax = doubleArrayOf(20.0 * i + 10.0, 10.0, 10.0),
                    centroid = doubleArrayOf(20.0 * i + 5.0, 5.0, 5.0),
                )
            },
            IntArray(names.size) { it },
        )

    fun table(count: Int): PartTable = table(List(count) { "P$it" })

    /** A flat tree: root `Top` with one leaf per part of [table]. */
    fun flatTree(table: PartTable): AssemblyTree {
        val n = table.parts.size
        return AssemblyTree(
            Array(n + 1) { if (it == 0) "Top" else table.parts[it - 1].name },
            IntArray(n + 1) { if (it == 0) -1 else 0 },
            IntArray(n + 1) { if (it == 0) -1 else it - 1 },
        )
    }
}
