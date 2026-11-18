package dev.kstep.desktop.run

import dev.kstep.cli.DesktopPartLimits
import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Outcome of validating the worker's assembly tree (ADR-0054). */
sealed interface AssemblyTreeResult {
    /** No tree in the result: the tree panel is silently off. */
    data object Absent : AssemblyTreeResult

    data class Valid(
        val tree: AssemblyTree,
    ) : AssemblyTreeResult

    /** The tree is present but cannot be trusted: shown as a `KSTEP-D-013` warning, flat selection still works. */
    data class Invalid(
        val reason: String,
    ) : AssemblyTreeResult
}

/**
 * Validates the `tree` of the render worker's JSON against the already validated [PartTable]. Hostile input like the
 * part list: fail closed, never throws, every bound checked before the allocation that depends on it (ADR-0054).
 *
 * The tree must be a real pre-order tree: node 0 is the only root, every other node hangs below a GROUP on the
 * current rightmost path (that excludes cycles and keeps every subtree one contiguous index range), the leaves carry
 * the part indices 0, 1, 2, ... in that order (so each group covers one contiguous part range) and each leaf has the
 * name of its part.
 */
object AssemblyTreeParser {
    fun parse(
        json: JsonObject,
        table: PartTable,
    ): AssemblyTreeResult =
        try {
            parseChecked(json, table)
        } catch (_: RuntimeException) {
            AssemblyTreeResult.Invalid("the assembly tree could not be read")
        }

    private fun parseChecked(
        json: JsonObject,
        table: PartTable,
    ): AssemblyTreeResult {
        val omitted = json["treeOmitted"]
        if (omitted != null && omitted !is JsonNull) {
            return when (((omitted as? JsonObject)?.get("reason") as? JsonPrimitive)?.contentOrNull) {
                "too_many_nodes" -> AssemblyTreeResult.Invalid("more than ${DesktopPartLimits.MAX_TREE_NODES} nodes")
                "too_deep" ->
                    AssemblyTreeResult.Invalid(
                        "nested deeper than ${DesktopPartLimits.MAX_TREE_DEPTH} levels",
                    )
                else -> AssemblyTreeResult.Invalid("the assembly tree was omitted")
            }
        }
        val element = json["tree"]
        if (element == null || element is JsonNull) return AssemblyTreeResult.Absent
        val nodes =
            ((element as? JsonObject)?.get("nodes") as? JsonArray)
                ?: return AssemblyTreeResult.Invalid("tree has no node list")
        if (nodes.isEmpty()) return AssemblyTreeResult.Invalid("the node list is empty")
        if (nodes.size > DesktopPartLimits.MAX_TREE_NODES) {
            return AssemblyTreeResult.Invalid("more than ${DesktopPartLimits.MAX_TREE_NODES} nodes")
        }
        val n = nodes.size
        val names = Array(n) { "" }
        val parent = IntArray(n)
        val partOf = IntArray(n)
        val depth = IntArray(n)
        val children = IntArray(n)
        val pathAt = IntArray(DesktopPartLimits.MAX_TREE_DEPTH + 1)
        var currentDepth = 0
        var nextPart = 0
        for ((i, item) in nodes.withIndex()) {
            val obj = item as? JsonObject ?: return invalid(i, "not an object")
            names[i] = NameSanitizer.cleanName(obj["name"]) ?: return invalid(i, "invalid name")
            val p = integer(obj["parent"]) ?: return invalid(i, "invalid parent")
            val part = integer(obj["part"]) ?: return invalid(i, "invalid part")
            if (i == 0) {
                if (p != -1L) return invalid(i, "the first node must be the root")
            } else {
                if (p < 0 || p >= i) return invalid(i, "parent out of order")
                val pi = p.toInt()
                if (partOf[pi] >= 0) return invalid(i, "a part cannot have children")
                val parentDepth = depth[pi]
                if (parentDepth > currentDepth || pathAt[parentDepth] != pi) {
                    return invalid(i, "node is not in pre-order")
                }
                depth[i] = parentDepth + 1
                if (depth[i] > DesktopPartLimits.MAX_TREE_DEPTH) return invalid(i, "nested too deep")
                children[pi]++
            }
            parent[i] = p.toInt()
            if (part == -1L) {
                partOf[i] = -1
            } else {
                if (part != nextPart.toLong()) return invalid(i, "parts are not listed once in order")
                if (names[i] != table.parts[nextPart].name) return invalid(i, "name differs from its part")
                partOf[i] = nextPart++
            }
            pathAt[depth[i]] = i
            currentDepth = depth[i]
        }
        if (nextPart != table.parts.size) return invalid(-1, "not every part is in the tree")
        for (i in 0 until n) if (partOf[i] < 0 && children[i] == 0) return invalid(i, "a group without content")
        return AssemblyTreeResult.Valid(AssemblyTree(names, parent, partOf))
    }

    private fun invalid(
        index: Int,
        what: String,
    ) = AssemblyTreeResult.Invalid(if (index >= 0) "node ${index + 1}: $what" else what)

    private fun integer(e: JsonElement?): Long? {
        val p = e as? JsonPrimitive ?: return null
        if (p.isString) return null
        return p.intOrNull?.toLong()
    }
}
