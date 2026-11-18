package dev.kstep.desktop.pick

import java.security.MessageDigest
import java.util.HexFormat

/**
 * The validated assembly hierarchy of one model (ADR-0054), in pre-order. Immutable; everything derived is computed once
 * in O(n) here. Construct only from a validated tree (see `AssemblyTreeParser`): [parent] of node `i` is smaller than
 * `i` (the root has `-1`), a node with [partOf] `-1` is a group, and the parts of the leaves rise strictly in
 * pre-order, so the leaves of every group cover one contiguous range of part indices.
 */
class AssemblyTree(
    private val names: Array<String>,
    private val parent: IntArray,
    private val partOf: IntArray,
) {
    val size: Int = names.size

    private val depth = IntArray(size)
    private val subtreeEnd = IntArray(size) { it + 1 }
    private val firstPart = IntArray(size) { Int.MAX_VALUE }
    private val lastPart = IntArray(size) { -1 }
    private val leafCount = IntArray(size)
    private val occurrence = IntArray(size)
    private val duplicateSibling = BooleanArray(size)
    private val keys = Array(names.size) { "" }
    private val nodeByKey = HashMap<String, Int>()
    private val leafOfPart: IntArray

    init {
        var maxPart = -1
        for (i in 0 until size) {
            if (parent[i] >= 0) depth[i] = depth[parent[i]] + 1
            if (partOf[i] >= 0) {
                firstPart[i] = partOf[i]
                lastPart[i] = partOf[i]
                leafCount[i] = 1
                if (partOf[i] > maxPart) maxPart = partOf[i]
            }
        }
        for (i in size - 1 downTo 1) {
            val p = parent[i]
            if (subtreeEnd[i] > subtreeEnd[p]) subtreeEnd[p] = subtreeEnd[i]
            if (firstPart[i] < firstPart[p]) firstPart[p] = firstPart[i]
            if (lastPart[i] > lastPart[p]) lastPart[p] = lastPart[i]
            leafCount[p] += leafCount[i]
        }
        leafOfPart = IntArray(maxPart + 1) { -1 }
        for (i in 0 until size) if (partOf[i] >= 0) leafOfPart[partOf[i]] = i

        val counts = HashMap<String, Int>()
        val siblingKey = Array(size) { "" }
        for (i in 0 until size) {
            val k = "${parent[i]}\u0000${names[i]}"
            siblingKey[i] = k
            val seen = counts.getOrDefault(k, 0)
            occurrence[i] = seen
            counts[k] = seen + 1
        }
        for (i in 0 until size) duplicateSibling[i] = counts.getValue(siblingKey[i]) > 1

        // A path key must stay short whatever the depth and name length (hostile input): it is a digest of the parent's
        // key, the name (length-prefixed, so "/" and "#" in names cannot collide) and the occurrence.
        for (i in 0 until size) {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update((if (parent[i] < 0) "" else keys[parent[i]]).toByteArray(Charsets.UTF_8))
            digest.update("/${names[i].length}:${names[i]}#${occurrence[i]}".toByteArray(Charsets.UTF_8))
            keys[i] = HexFormat.of().formatHex(digest.digest(), 0, KEY_BYTES)
            nodeByKey[keys[i]] = i
        }
    }

    fun isGroup(n: Int): Boolean = partOf[n] < 0

    fun name(n: Int): String = names[n]

    fun parentOf(n: Int): Int = parent[n]

    fun depth(n: Int): Int = depth[n]

    /** Exclusive end of the pre-order range of the subtree rooted at [n]; `n + 1` for a leaf. */
    fun subtreeEnd(n: Int): Int = subtreeEnd[n]

    /** The part of leaf [n], `-1` for a group. */
    fun partOf(n: Int): Int = partOf[n]

    fun firstPart(n: Int): Int = firstPart[n]

    fun lastPart(n: Int): Int = lastPart[n]

    /** Number of leaves in the subtree of [n] (`1` for a leaf). */
    fun leafCount(n: Int): Int = leafCount[n]

    /** The leaf of part [part], `-1` if none. */
    fun leafNodeOfPart(part: Int): Int = if (part in leafOfPart.indices) leafOfPart[part] else -1

    /** 0-based position of [n] among siblings of the same name. */
    fun occurrence(n: Int): Int = occurrence[n]

    /** Whether another sibling has the same name, i.e. whether the row shows a `#k` suffix. */
    fun hasDuplicateSiblingName(n: Int): Boolean = duplicateSibling[n]

    /** Stable key of the node: its path of name plus sibling occurrence, independent of the node's index. */
    fun key(n: Int): String = keys[n]

    /** The node with this [key], `-1` if unknown. */
    fun nodeOfKey(key: String): Int = nodeByKey[key] ?: -1

    /** The `#k` label (1-based) of a node with same-named siblings, else empty. */
    fun duplicateSuffix(n: Int): String = if (duplicateSibling[n]) " #${occurrence[n] + 1}" else ""

    /** `Root / Sub / Leaf` with `#k` where siblings share a name, for the tooltip. */
    fun displayPath(n: Int): String {
        val parts = ArrayList<String>()
        var cur = n
        while (cur >= 0) {
            parts += if (duplicateSibling[cur]) "${names[cur]} #${occurrence[cur] + 1}" else names[cur]
            cur = parent[cur]
        }
        return parts.asReversed().joinToString(" / ")
    }

    private companion object {
        const val KEY_BYTES = 16
    }
}
