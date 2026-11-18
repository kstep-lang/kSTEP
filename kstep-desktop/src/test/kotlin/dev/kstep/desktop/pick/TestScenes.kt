package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.run.PartListParser
import dev.kstep.desktop.run.PartListResult
import dev.kstep.geometry.ColoredMesh
import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.MeshComposition
import dev.kstep.geometry.PlacedMesh
import dev.kstep.geometry.TriangleMesh
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Scenes for the picking tests: a coloured mesh made of parts plus the matching, validated part table. */
object TestScenes {
    val IDENTITY_PLACEMENT: List<Double> = listOf(1.0, 0.0, 0.0, 5.0, 0.0, 1.0, 0.0, 6.0, 0.0, 0.0, 1.0, 7.0)

    /** Colour of part [i]: channel values that survive the 8 bit round trip exactly. */
    fun colorOf(i: Int) = MeshColor((10 * (i + 1)) / 255.0, 0.0, 0.0)

    fun hexOf(i: Int) = "#%02X0000".format(10 * (i + 1))

    fun mesh(vararg partCoordinates: DoubleArray): ColoredMesh =
        MeshComposition.mergeColored(
            partCoordinates.mapIndexed { i, c -> PlacedMesh(TriangleMesh(c), color = colorOf(i)) },
        )

    fun part(
        name: JsonElement = JsonPrimitive("A"),
        first: JsonElement = JsonPrimitive(0),
        count: JsonElement = JsonPrimitive(1),
        placement: JsonElement = JsonArray(IDENTITY_PLACEMENT.map { JsonPrimitive(it) }),
        color: JsonElement = JsonPrimitive("#0A0000"),
    ): JsonObject =
        buildJsonObject {
            put("name", name)
            put("firstTriangle", first)
            put("triangleCount", count)
            put("placement", placement)
            put("color", color)
        }

    /** The worker's result object with a `parts` array of [parts]. */
    fun result(vararg parts: JsonElement): JsonObject = buildJsonObject { put("parts", JsonArray(parts.toList())) }

    /** The honest part list for [partCoordinates], one part per entry, named `P0`, `P1`, ... */
    fun honestJson(
        vararg partCoordinates: DoubleArray,
        names: List<String> = partCoordinates.indices.map { "P$it" },
    ): JsonObject {
        var first = 0
        val parts =
            partCoordinates.mapIndexed { i, c ->
                val count = c.size / 9
                part(
                    JsonPrimitive(names[i]),
                    JsonPrimitive(first),
                    JsonPrimitive(count),
                    color = JsonPrimitive(hexOf(i)),
                ).also { first += count }
            }
        return result(*parts.toTypedArray())
    }

    fun scene(
        vararg partCoordinates: DoubleArray,
        names: List<String> = partCoordinates.indices.map { "P$it" },
    ): Pair<ColoredMesh, PartTable> {
        val mesh = mesh(*partCoordinates)
        val parsed = PartListParser.parse(honestJson(*partCoordinates, names = names), mesh)
        return mesh to parsed.shouldBeInstanceOf<PartListResult.Valid>().table
    }

    /** A triangle in the plane z = [z]: (x,y), (x+10,y), (x,y+10). */
    fun triangle(
        x: Double,
        y: Double,
        z: Double,
    ) = doubleArrayOf(x, y, z, x + 10.0, y, z, x, y + 10.0, z)
}
