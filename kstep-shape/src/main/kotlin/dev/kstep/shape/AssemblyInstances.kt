package dev.kstep.shape

import dev.kstep.generated.ap242v1.ProductDefinition
import dev.kstep.geometry.Placement
import java.util.IdentityHashMap
import kotlin.math.sqrt

// Placement-aware assembly expansion (ADR-0035): turns the geometric usage graph of a model into the
// flat list of drawn instances, each with its placement composed from the root down. Pure (no OCCT
// call), so it runs in every CI job. Reuses AssemblyGeometryGraph so the usage semantics (closure,
// cycle rejection, placement validation) are identical to the export path.

/** One drawn occurrence of a shaped product definition, with its placement in the root frame. */
data class PlacedInstance(
    val productDefinition: ProductDefinition,
    /** Composed from the root down to this instance: `pRoot = placement * pLocal`. */
    val placement: RigidPlacement,
    /** NAUO ids from the root to this instance (empty for a shaped root itself), for messages and tests. */
    val path: List<String>,
)

data class InstanceExpansion(
    val instances: List<PlacedInstance>,
    /** Number of root product definitions of the geometric usage graph. */
    val rootCount: Int,
)

object AssemblyInstanceExpander {
    /** DoS limit on drawn instances. */
    const val MAX_INSTANCES: Int = 4096

    /** DoS limit on visited tree nodes (drawn or not); stops DAG fan-out explosions early. */
    const val MAX_VISITS: Int = 16384

    /** DoS limit on the nesting depth of the assembly tree. */
    const val MAX_DEPTH: Int = 64

    private class Frame(
        val pd: ProductDefinition,
        val placement: RigidPlacement,
        val depth: Int,
        val path: List<String>,
        val children: Iterator<GeometricUsage>,
    )

    /**
     * Expands the assembly structure given by [roots] / [placements] into drawn instances, in
     * deterministic pre-order (root order, then usage order). A model without any geometric usage
     * yields an empty expansion.
     *
     * @throws ShapeExportException (fail closed) for everything [AssemblyGeometryGraph.plan] rejects,
     *   for a nesting deeper than [MAX_DEPTH], for more than [MAX_VISITS] visited nodes or
     *   [MAX_INSTANCES] instances, and for a placement that cannot be composed.
     */
    fun expand(
        roots: List<Any>,
        shaped: Collection<ProductDefinition>,
        placements: List<UsagePlacement>,
    ): InstanceExpansion {
        val plan = AssemblyGeometryGraph.plan(roots, shaped, placements)
        if (plan.usages.isEmpty()) return InstanceExpansion(emptyList(), 0)

        val shapedSet = IdentityHashMap<ProductDefinition, Boolean>()
        for (pd in shaped) shapedSet[pd] = true
        val childrenOf = IdentityHashMap<ProductDefinition, MutableList<GeometricUsage>>()
        val isChild = IdentityHashMap<ProductDefinition, Boolean>()
        for (u in plan.usages) {
            childrenOf.getOrPut(u.parent) { mutableListOf() } += u
            isChild[u.child] = true
        }
        val rootDefinitions = plan.members.filter { !isChild.containsKey(it) }

        val instances = mutableListOf<PlacedInstance>()
        var visits = 0
        for (rootPd in rootDefinitions) {
            val stack = ArrayDeque<Frame>()

            fun enter(
                pd: ProductDefinition,
                placement: RigidPlacement,
                depth: Int,
                path: List<String>,
            ) {
                if (depth > MAX_DEPTH) {
                    throw ShapeExportException("assembly nesting deeper than $MAX_DEPTH levels at '${pd.id}'")
                }
                visits++
                if (visits > MAX_VISITS) {
                    throw ShapeExportException("assembly expansion visits more than $MAX_VISITS nodes")
                }
                if (shapedSet.containsKey(pd)) {
                    instances += PlacedInstance(pd, placement, path)
                    if (instances.size > MAX_INSTANCES) {
                        throw ShapeExportException("assembly expands to more than $MAX_INSTANCES instances")
                    }
                }
                stack.addLast(Frame(pd, placement, depth, path, childrenOf[pd].orEmpty().iterator()))
            }

            enter(rootPd, RigidPlacement.IDENTITY, 0, emptyList())
            while (stack.isNotEmpty()) {
                val frame = stack.last()
                if (!frame.children.hasNext()) {
                    stack.removeLast()
                    continue
                }
                val usage = frame.children.next()
                val composed =
                    try {
                        frame.placement.then(usage.placement)
                    } catch (e: IllegalArgumentException) {
                        throw ShapeExportException(
                            "placement of assembly usage '${usage.usage.id}' cannot be composed: ${e.message}",
                            e,
                        )
                    }
                enter(usage.child, composed, frame.depth + 1, frame.path + usage.usage.id)
            }
        }
        return InstanceExpansion(instances, rootDefinitions.size)
    }
}

/**
 * Converts this placement into the mesh-side [Placement]: columns are the rotation's local X, Y and
 * Z axes, then the translation. The axes are re-orthonormalised first (Gram-Schmidt from Z and X), so
 * the rounding residue of a long composition cannot make [Placement.fromRowMajor3x4] reject it.
 *
 * @throws ShapeExportException if the result is still not a proper rotation (cannot happen for a
 *   valid [Rotation]; kept as a fail-closed guard).
 */
fun RigidPlacement.toGeometryPlacement(): Placement {
    fun unit(v: Vec3): Vec3 {
        val n = sqrt(v dot v)
        return Vec3(v.x / n, v.y / n, v.z / n)
    }
    val z = unit(rotation.axis)
    val x = unit(rotation.refDirection - z * (rotation.refDirection dot z))
    val y = z cross x
    val m =
        doubleArrayOf(
            x.x,
            y.x,
            z.x,
            translation.x,
            x.y,
            y.y,
            z.y,
            translation.y,
            x.z,
            y.z,
            z.z,
            translation.z,
        )
    return try {
        Placement.fromRowMajor3x4(m)
    } catch (e: IllegalArgumentException) {
        throw ShapeExportException("placement cannot be converted to a mesh transform: ${e.message}", e)
    }
}
