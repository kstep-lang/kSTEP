package dev.kstep.geometry

import dev.kstep.geometry.occt.OcctBridge
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.ref.Cleaner
import java.lang.ref.Reference
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

private val CLEANER: Cleaner = Cleaner.create()

/**
 * A live, native OCCT `TopoDS_Shape`, referenced from the JVM side only by an opaque [Long]
 * handle into the native bridge's own shape registry (see
 * `src/main/cpp/kstep_occt_bridge.cpp`). Only [OcctKernel] constructs one, via
 * [OcctKernel.makeBox].
 *
 * [AutoCloseable]: native shapes are not garbage-collected JVM memory, so they must be released
 * explicitly -- `use { }` is the intended call pattern (see every usage site in this module's own
 * `OcctBridgeSmokeTest`/`OcctBoxValidationExportTest`). A [Cleaner] registered in the constructor
 * is a *safety net* for a caller that forgets to close, not the primary release path -- it
 * captures **only the raw [Long] handle**, deliberately never `this`: a cleaner action that
 * captured the [OcctShape] instance itself would keep that instance permanently reachable through
 * the cleaner's own internal bookkeeping, so the instance would never become eligible for
 * collection and the cleaner action would never run. [close] unregisters the cleaner action once
 * it has run, so a caller who does use `use { }` correctly never actually depends on the cleaner
 * firing at all.
 */
class OcctShape internal constructor(
    private val handle: Long,
) : AutoCloseable {
    @Volatile
    private var closed = false

    private val cleanable: Cleaner.Cleanable = CLEANER.register(this, ReleaseAction(handle))

    /**
     * Unique B-Rep element counts, computed on first access and cached thereafter.
     *
     * [checkOpen] runs on *every* access, not just the first: caching the computed
     * [ShapeTopology] must never cache away the documented closed-shape check too, or a
     * `topology` access after [close] would silently return a stale value instead of throwing --
     * see [topologyValue]/[volumeValue] below, which hold the actual `by lazy` caching.
     *
     * @throws IllegalStateException if this shape is already [close]d.
     */
    val topology: ShapeTopology
        get() {
            checkOpen()
            return topologyValue
        }

    private val topologyValue: ShapeTopology by lazy {
        val counts =
            try {
                OcctBridge.nativeShapeCounts(handle)
            } finally {
                // Keeps `this` (and therefore the native handle the Cleaner will eventually
                // release) strongly reachable across the native call -- without this, the JIT is
                // free to treat `this` as dead the moment `handle` has been read out as a plain
                // Long, since nothing in the rest of this block dereferences `this` again. A GC
                // during the native call could then let the Cleaner's daemon thread free the
                // native shape (kstep_occt_bridge.cpp's nativeReleaseShape -> `delete`) while
                // this call is still using it -- a use-after-free, not a catchable exception. See
                // the class KDoc above and docs/adr/ADR-0005-occt-jni-bridge.adoc.
                Reference.reachabilityFence(this)
            }
        ShapeTopology(
            solids = counts[0],
            shells = counts[1],
            faces = counts[2],
            edges = counts[3],
            vertices = counts[4],
        )
    }

    /**
     * Volume, via OCCT's `BRepGProp` mass properties, computed on first access and cached
     * thereafter.
     *
     * [checkOpen] runs on *every* access -- see [topology]'s KDoc for why caching must not skip it.
     *
     * @throws IllegalStateException if this shape is already [close]d.
     */
    val volume: Double
        get() {
            checkOpen()
            return volumeValue
        }

    private val volumeValue: Double by lazy {
        try {
            OcctBridge.nativeShapeVolume(handle)
        } finally {
            // See topologyValue's KDoc above -- same use-after-free hazard, same fix.
            Reference.reachabilityFence(this)
        }
    }

    /**
     * Writes this shape to a real STEP (ISO 10303-21) file via OCCT's own `STEPControl_Writer`.
     *
     * Calling this from multiple threads concurrently (on this or any other [OcctShape]) is
     * safe but fully serialized on the native side, because OCCT's STEP-schema selection is
     * process-global state -- see `kstep_occt_bridge.cpp`'s `nativeWriteStep`. This includes
     * safety against a concurrent [close] on the *same* [OcctShape] (from another thread, or via
     * the [Cleaner] safety net): the native side holds its shape registry's lock across the
     * entire handle-lookup-through-`Write()` critical section, so a concurrent release can only
     * ever run strictly before or strictly after this call, never interleaved with it -- see the
     * use-after-free note on `g_mutex`'s declaration in `kstep_occt_bridge.cpp`. If the release
     * wins the race, this call fails with [OcctGeometryException] (wrapping the native side's
     * "unknown handle" `IllegalStateException`) rather than reading freed memory. [topology] and
     * [volume] below get the same native-side protection, but surface it as a plain
     * `IllegalStateException` (they don't wrap native failures the way this method does).
     *
     * @param target destination path; resolved to an absolute, normalized path before writing.
     *   Its parent directory must already exist.
     * @param schema which STEP AP schema OCCT should tag the file with. Defaults to
     *   [StepSchema.AP242DIS], matching the rest of kSTEP's AP242 focus.
     * @return `target`, resolved to the same absolute/normalized path actually written.
     * @throws IllegalStateException if this shape is already [close]d.
     * @throws IllegalArgumentException if `target`'s parent directory does not exist or is not a
     *   directory.
     * @throws OcctGeometryException if OCCT's writer reports a non-success `IFSelect_ReturnStatus`.
     */
    fun writeStepFile(
        target: Path,
        schema: StepSchema = StepSchema.AP242DIS,
    ): Path {
        checkOpen()
        val absolute = target.toAbsolutePath().normalize()
        val parent = absolute.parent
        require(parent != null && Files.isDirectory(parent)) {
            "Parent directory of $absolute does not exist or is not a directory: $parent"
        }
        val status =
            try {
                OcctBridge.nativeWriteStep(handle, absolute.toString(), schema.occtValue)
            } catch (e: RuntimeException) {
                throw OcctGeometryException("OCCT STEP export failed for $absolute", e)
            } finally {
                // See topologyValue's KDoc above -- same use-after-free hazard, same fix.
                Reference.reachabilityFence(this)
            }
        // IFSelect_ReturnStatus: IFSelect_RetVoid=0, IFSelect_RetDone=1 (success), RetError=2,
        // RetFail=3, RetStop=4 -- see OCCT's IFSelect_ReturnStatus.hxx.
        if (status != 1) {
            throw OcctGeometryException(
                "OCCT STEP export to $absolute returned IFSelect_ReturnStatus=$status (expected 1/RetDone)",
            )
        }
        return absolute
    }

    /**
     * Triangulates this shape's surface and returns the result as a [TriangleMesh].
     *
     * DELIBERATELY NOT cached (unlike [topology]/[volume]): the native triangulation is
     * discarded again right after extraction (`BRepTools::Clean`, see
     * [dev.kstep.geometry.occt.OcctBridge.nativeShapeTriangles]'s KDoc) -- a JVM-side cache would
     * just rebuild, for this [OcctShape]'s entire remaining lifetime, the memory `Clean` was
     * meant to give back.
     *
     * @throws IllegalStateException if this shape is already [close]d.
     * @throws IllegalArgumentException if the shape exceeds the native triangle-count guard
     *   ([OcctKernel.MAX_TRIANGLES]).
     * @throws OcctGeometryException if OCCT itself fails to triangulate the shape.
     */
    fun triangulate(): TriangleMesh =
        withHandle { h ->
            val coords =
                try {
                    OcctBridge.nativeShapeTriangles(h)
                } catch (e: IllegalArgumentException) {
                    // Native DoS-/range-check failure must surface as-is, not be reinterpreted as
                    // a geometry failure -- same rule as OcctKernel.fillet's identical ordering.
                    // IllegalArgumentException IS a RuntimeException, so this catch MUST come
                    // before the general RuntimeException catch below.
                    throw e
                } catch (e: RuntimeException) {
                    throw OcctGeometryException("OCCT failed to triangulate shape (handle=$handle)", e)
                }
            TriangleMesh(coords)
        }

    /**
     * Runs [block] with this shape's native handle, with the two invariants every existing native
     * call site in this class already upholds applied centrally: [checkOpen] first, and a
     * [Reference.reachabilityFence] around the call so the [Cleaner] cannot free the native shape
     * mid-call (see [topologyValue]'s KDoc for the full use-after-free rationale).
     *
     * `internal`: only [OcctKernel] (same module) uses this, for operations that consume an
     * existing shape and produce a new one ([OcctKernel.fillet]). It deliberately does NOT expose
     * the raw handle as a property -- a bare `internal val handle` would let a caller read it once
     * and use it later, outside both guarantees above.
     *
     * Note the close() race is still resolved on the native side, not here: a concurrent close()
     * between [checkOpen] and the native call leaves the native lookup to reject an unknown handle
     * with `IllegalStateException` -- never a dereference of freed memory. See
     * `kstep_occt_bridge.cpp`'s `g_mutex` note.
     */
    internal fun <T> withHandle(block: (Long) -> T): T {
        checkOpen()
        return try {
            block(handle)
        } finally {
            Reference.reachabilityFence(this)
        }
    }

    /** Idempotent: a second/later call is a silent no-op, matching [AutoCloseable]'s contract. */
    override fun close() {
        if (closed) return
        closed = true
        cleanable.clean()
    }

    private fun checkOpen() {
        check(!closed) { "OcctShape (handle=$handle) is already closed" }
    }

    /**
     * Captures ONLY [handle] -- never the enclosing [OcctShape] instance -- so the [Cleaner] can
     * actually detect the shape becoming unreachable. See the class KDoc above.
     */
    private class ReleaseAction(
        private val handle: Long,
    ) : Runnable {
        override fun run() {
            try {
                OcctBridge.nativeReleaseShape(handle)
            } catch (e: Throwable) {
                // A Cleaner action must never throw -- the Cleaner thread would otherwise
                // silently stop processing further cleanup actions for every other shape too.
                logger.warn(e) { "Failed to release native OCCT shape handle=$handle" }
            }
        }
    }
}
