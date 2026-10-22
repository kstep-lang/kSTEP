package dev.kstep.geometry

import dev.kstep.geometry.occt.OcctBridge
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.ref.Cleaner
import java.lang.ref.Reference

private val logger = KotlinLogging.logger {}

private val IMPORT_CLEANER: Cleaner = Cleaner.create()

/**
 * A STEP file read into OCCT's XCAF model: the product structure ([nodes]) plus access to each
 * part's B-Rep. Obtain one from [OcctKernel.readStep]; the caller must [close] it.
 *
 * The structure is read eagerly in the constructor; part geometry is fetched on demand through
 * [partShape], which returns a fresh, independently closeable [OcctShape]. A [Cleaner] is the safety
 * net for a forgotten [close], capturing only the native handle (same pattern as [OcctShape]).
 */
class OcctStepDocument internal constructor(
    private val handle: Long,
) : AutoCloseable {
    @Volatile
    private var closed = false

    private val cleanable: Cleaner.Cleanable = IMPORT_CLEANER.register(this, ReleaseImportAction(handle))

    val nodes: List<XcafNode>
    val stats: StepReadStats

    init {
        try {
            val ints = OcctBridge.nativeImportNodeInts(handle)
            val matrices = OcctBridge.nativeImportNodeMatrices(handle)
            val rawStats = OcctBridge.nativeImportStats(handle)
            val unknown = OcctBridge.nativeImportUnknownTypes(handle)
            val nodeCount = rawStats[3]
            check(ints.size == nodeCount * 6 && matrices.size == nodeCount * 12) {
                "inconsistent native import payload (nodes=$nodeCount, ints=${ints.size}, matrices=${matrices.size})"
            }
            nodes =
                List(nodeCount) { i ->
                    val kindCode = ints[i * 6]
                    check(kindCode in 0..2) { "unknown native node kind $kindCode" }
                    val parent = ints[i * 6 + 1]
                    val referred = ints[i * 6 + 2]
                    check(parent < nodeCount && referred < nodeCount) { "native node index out of range" }
                    XcafNode(
                        index = i,
                        kind = XcafNodeKind.entries[kindCode],
                        name = sanitizeImportText(OcctBridge.nativeImportNodeName(handle, i)),
                        parentIndex = parent.takeIf { it >= 0 },
                        referredIndex = referred.takeIf { it >= 0 },
                        freeRoot = ints[i * 6 + 3] == 1,
                        rigid = ints[i * 6 + 4] == 1,
                        localMatrix = matrices.copyOfRange(i * 12, i * 12 + 12),
                        hasShape = ints[i * 6 + 5] >= 0,
                    )
                }
            stats =
                StepReadStats(
                    entityCount = rawStats[0],
                    unknownEntityCount = rawStats[1],
                    unknownEntityTypes = if (unknown.isEmpty()) emptyList() else unknown.split('\n'),
                    freeRootCount = rawStats[2],
                )
        } finally {
            Reference.reachabilityFence(this)
        }
    }

    /**
     * A new [OcctShape] holding a copy of the geometry of the PART node [nodeIndex] (in the part's
     * own coordinates); the caller closes it.
     *
     * @throws IllegalStateException if this document is closed.
     * @throws IllegalArgumentException if [nodeIndex] is out of range, not a PART, or has no geometry.
     */
    fun partShape(nodeIndex: Int): OcctShape {
        check(!closed) { "OcctStepDocument (handle=$handle) is already closed" }
        return try {
            OcctShape(OcctBridge.nativeImportPartShape(handle, nodeIndex))
        } finally {
            Reference.reachabilityFence(this)
        }
    }

    /** Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        cleanable.clean()
    }

    private class ReleaseImportAction(
        private val handle: Long,
    ) : Runnable {
        override fun run() {
            try {
                OcctBridge.nativeReleaseImport(handle)
            } catch (e: Throwable) {
                logger.warn(e) { "Failed to release native STEP import session handle=$handle" }
            }
        }
    }
}
