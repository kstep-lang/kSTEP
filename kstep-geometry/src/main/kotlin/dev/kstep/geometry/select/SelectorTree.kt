package dev.kstep.geometry.select

/**
 * Bounds the size of selector trees across ALL three hierarchies ([EdgeSelector], [FaceSelector], [VertexSelector])
 * and their cross-nesting (`ofFace`, `atVertex`, `ofEdge`, `within`). Iterative on purpose: a hostile deep tree must
 * be rejected before anything recurses over it (`equals`, `hashCode`, `toString`, the evaluator).
 *
 * Callers invoke [validate] BEFORE hashing or comparing a selector, e.g. before storing it in a `Set`.
 */
internal object SelectorTree {
    const val MAX_DEPTH: Int = 32
    const val MAX_NODES: Int = 256

    /**
     * @param root an [EdgeSelector], [FaceSelector] or [VertexSelector].
     * @throws IllegalArgumentException if the tree is deeper than [MAX_DEPTH] or has more than [MAX_NODES] nodes
     *   (counted over the whole cross-nested tree), or [root] is not a selector.
     */
    fun validate(root: Any) {
        var nodes = 0
        val stack = ArrayDeque<Pair<Any, Int>>()
        stack.addLast(root to 1)
        while (stack.isNotEmpty()) {
            val (node, depth) = stack.removeLast()
            nodes++
            require(depth <= MAX_DEPTH) { "selector nesting exceeds $MAX_DEPTH levels" }
            require(nodes <= MAX_NODES) { "selector has more than $MAX_NODES nodes" }
            for (child in children(node)) stack.addLast(child to depth + 1)
        }
    }

    private fun children(node: Any): List<Any> =
        when (node) {
            is AndSelector -> listOf(node.left, node.right)
            is OrSelector -> listOf(node.left, node.right)
            is MinusSelector -> listOf(node.left, node.right)
            is EdgesOfFaceSelector -> listOf(node.face)
            is EdgesAtVertexSelector -> listOf(node.vertex)
            is NearestEdgeSelector -> listOfNotNull(node.within)
            is FaceAndSelector -> listOf(node.left, node.right)
            is FaceOrSelector -> listOf(node.left, node.right)
            is FaceMinusSelector -> listOf(node.left, node.right)
            is NearestFaceSelector -> listOfNotNull(node.within)
            is VertexAndSelector -> listOf(node.left, node.right)
            is VertexOrSelector -> listOf(node.left, node.right)
            is VertexMinusSelector -> listOf(node.left, node.right)
            is VerticesOfEdgeSelector -> listOf(node.edge)
            is NearestVertexSelector -> listOfNotNull(node.within)
            is EdgeSelector, is FaceSelector, is VertexSelector -> emptyList()
            else -> throw IllegalArgumentException("not a selector: ${node::class.simpleName}")
        }
}
