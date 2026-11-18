package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartTable
import dev.kstep.geometry.ColoredMesh
import dev.kstep.render.mesh.ScreenFrame
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** A picking ray: [origin] and unit [dir], both 3 components. */
class Ray(
    val origin: DoubleArray,
    val dir: DoubleArray,
)

/**
 * The ray through pixel ([px], [py]) of the frame the canvas just drew with. The projection is orthographic, so every
 * ray runs along the frame's view direction; the origin is moved back far enough to lie in front of the whole scene
 * (a scene of radius [sceneRadius] around the world origin).
 */
fun rayFor(
    frame: ScreenFrame,
    px: Double,
    py: Double,
    sceneRadius: Double,
): Ray {
    val p = frame.worldPointAt(px, py)
    val back = 2.0 * sceneRadius + 1.0
    val v = frame.view
    return Ray(doubleArrayOf(p[0] - v[0] * back, p[1] - v[1] * back, p[2] - v[2] * back), v.copyOf())
}

/** Ray/triangle and ray/box tests (pure, never throws; NaN input never hits). */
object TrianglePicker {
    private const val BARYCENTRIC_TOLERANCE = 1e-9

    /** Moeller-Trumbore for triangle [t] of [coords] (9 doubles each). Both faces hit; returns the ray parameter. */
    fun intersect(
        ray: Ray,
        coords: DoubleArray,
        t: Int,
        eps: Double,
    ): Double? {
        val o = t * 9
        val e1x = coords[o + 3] - coords[o]
        val e1y = coords[o + 4] - coords[o + 1]
        val e1z = coords[o + 5] - coords[o + 2]
        val e2x = coords[o + 6] - coords[o]
        val e2y = coords[o + 7] - coords[o + 1]
        val e2z = coords[o + 8] - coords[o + 2]
        val cx = e1y * e2z - e1z * e2y
        val cy = e1z * e2x - e1x * e2z
        val cz = e1x * e2y - e1y * e2x
        val areaTwice = sqrt(cx * cx + cy * cy + cz * cz)
        if (!(areaTwice > eps * eps)) return null // degenerate or NaN
        val d = ray.dir
        val px = d[1] * e2z - d[2] * e2y
        val py = d[2] * e2x - d[0] * e2z
        val pz = d[0] * e2y - d[1] * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (!(abs(det) >= eps)) return null // parallel or NaN
        val inv = 1.0 / det
        val sx = ray.origin[0] - coords[o]
        val sy = ray.origin[1] - coords[o + 1]
        val sz = ray.origin[2] - coords[o + 2]
        val u = (sx * px + sy * py + sz * pz) * inv
        if (!(u >= -BARYCENTRIC_TOLERANCE && u <= 1.0 + BARYCENTRIC_TOLERANCE)) return null
        val qx = sy * e1z - sz * e1y
        val qy = sz * e1x - sx * e1z
        val qz = sx * e1y - sy * e1x
        val v = (d[0] * qx + d[1] * qy + d[2] * qz) * inv
        if (!(v >= -BARYCENTRIC_TOLERANCE && u + v <= 1.0 + BARYCENTRIC_TOLERANCE)) return null
        val dist = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (dist > eps) dist else null
    }

    /** Slab test of [ray] against the box [min]..[max]; the entry parameter (never negative), or `null`. */
    fun slab(
        ray: Ray,
        min: DoubleArray,
        max: DoubleArray,
    ): Double? {
        var tMin = 0.0
        var tMax = Double.POSITIVE_INFINITY
        for (axis in 0 until 3) {
            val o = ray.origin[axis]
            val d = ray.dir[axis]
            if (abs(d) < PARALLEL) {
                if (!(o >= min[axis] && o <= max[axis])) return null
            } else {
                var t1 = (min[axis] - o) / d
                var t2 = (max[axis] - o) / d
                if (t1 > t2) {
                    val tmp = t1
                    t1 = t2
                    t2 = tmp
                }
                if (t1 > tMin) tMin = t1
                if (t2 < tMax) tMax = t2
                if (!(tMin <= tMax)) return null
            }
        }
        return tMin
    }

    private const val PARALLEL = 1e-15
}

/**
 * Part picking over a validated [PartTable]: per-part boxes as a cheap pre-filter, then triangle tests only inside
 * boxes the ray enters, nearest box first, stopping once no remaining box can hold a nearer hit.
 */
class PickIndex(
    private val mesh: ColoredMesh,
    private val table: PartTable,
) {
    private val boxMin: Array<DoubleArray>
    private val boxMax: Array<DoubleArray>

    /** Radius around the world origin that contains the whole scene. */
    val sceneRadius: Double

    private val eps: Double

    /** Triangles tested by all picks so far (diagnostics for tests). */
    var trianglesTested: Long = 0
        private set

    init {
        var extent = 0.0
        var radius = 0.0
        val parts = table.parts
        boxMin = Array(parts.size) { DoubleArray(3) }
        boxMax = Array(parts.size) { DoubleArray(3) }
        val padding = 1e-9
        for ((i, p) in parts.withIndex()) {
            for (axis in 0 until 3) {
                boxMin[i][axis] = p.bboxMin[axis] - padding
                boxMax[i][axis] = p.bboxMax[axis] + padding
                extent = max(extent, p.bboxMax[axis] - p.bboxMin[axis])
                radius = max(radius, max(abs(p.bboxMin[axis]), abs(p.bboxMax[axis])))
            }
        }
        sceneRadius = sqrt(3.0) * radius
        eps = 1e-9 * max(1.0, extent)
    }

    /**
     * Index of the nearest part hit by [ray], or `null`. With a visibility [mode] (one [PartMode] code per part; ignored
     * when its size does not match the table) the nearest OPAQUE part wins over the whole ray, a transparent one is only
     * taken when no opaque part is hit, and a hidden part is never touched (ADR-0055). Without [mode] every part counts.
     */
    fun pick(
        ray: Ray,
        mode: ByteArray? = null,
    ): Int? {
        if (mode == null || mode.size != table.parts.size) return nearest(ray) { true }
        return nearest(ray) { mode[it] == PartMode.VISIBLE } ?: nearest(ray) { mode[it] == PartMode.TRANSPARENT }
    }

    private fun nearest(
        ray: Ray,
        accept: (Int) -> Boolean,
    ): Int? {
        val n = table.parts.size
        if (n == 0) return null
        val enter = DoubleArray(n) { Double.NaN }
        var candidates = 0
        for (i in 0 until n) {
            if (!accept(i)) continue
            val t = TrianglePicker.slab(ray, boxMin[i], boxMax[i])
            if (t != null) {
                enter[i] = t
                candidates++
            }
        }
        if (candidates == 0) return null
        val order = (0 until n).filter { !enter[it].isNaN() }.sortedBy { enter[it] }
        var best = Double.POSITIVE_INFINITY
        var bestPart = -1
        val coords = mesh.mesh.coordinates
        for (i in order) {
            if (enter[i] > best) break
            val part = table.parts[i]
            for (t in part.firstTriangle until part.firstTriangle + part.triangleCount) {
                trianglesTested++
                val hit = TrianglePicker.intersect(ray, coords, t, eps)
                if (hit != null && hit < best) {
                    best = hit
                    bestPart = i
                }
            }
        }
        return if (bestPart >= 0) bestPart else null
    }
}

/** Releases hover points at most once per [intervalMs]; the latest offered point wins. */
class HoverThrottle(
    private val clock: () -> Long,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var pending: Pair<Float, Float>? = null
    private var lastEmit = 0L
    private var emitted = false

    fun offer(
        x: Float,
        y: Float,
    ) {
        pending = x to y
    }

    /** The pending point if the interval since the last release has passed, else `null`. */
    fun due(): Pair<Float, Float>? {
        val p = pending ?: return null
        val now = clock()
        if (emitted && now - lastEmit < intervalMs) return null
        emitted = true
        lastEmit = now
        pending = null
        return p
    }

    fun clear() {
        pending = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 33
    }
}

/** One-entry cache of the last pick, valid for one pixel, one screen mapping and one scene. */
class PickCache {
    private var px = Float.NaN
    private var py = Float.NaN
    private var frame: ScreenFrame? = null
    private var sceneGeneration = -1L
    private var value = -1

    /** Forgets the cached pick, e.g. when the visibility changed: the key knows pixel, mapping and scene only. */
    fun invalidate() {
        frame = null
    }

    /** The part index at the pixel (`-1` for none); [compute] runs only on a cache miss. */
    fun lookup(
        x: Float,
        y: Float,
        screen: ScreenFrame,
        scene: Long,
        compute: () -> Int,
    ): Int {
        val cached = frame
        if (x == px &&
            y == py &&
            scene == sceneGeneration &&
            cached != null &&
            cached.sameMappingAs(screen)
        ) {
            return value
        }
        val result = compute()
        px = x
        py = y
        frame = screen
        sceneGeneration = scene
        value = result
        return result
    }
}
