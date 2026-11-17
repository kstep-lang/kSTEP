package dev.kstep.render.export

import dev.kstep.geometry.MeshColor
import dev.kstep.render.RenderLimits

/** Shared validation and reporting for [StlWriter] and [ThreeMfWriter]. Internal. */
internal object MeshExportSupport {
    class TopologySummary(
        val watertight: Boolean,
        val open: Int,
        val nonManifold: Int,
        val warnings: List<String>,
    )

    /** Rejects oversize scenes and non-finite coordinates BEFORE any large allocation (ADR-0049 D7/D8). */
    fun validate(scene: MeshExportScene) {
        var total = 0L
        for ((i, part) in scene.parts.withIndex()) {
            total += part.mesh.triangleCount.toLong() * part.placements.size
            require(total <= RenderLimits.EXPORT_MAX_TRIANGLES) {
                "mesh export refused: more than ${RenderLimits.EXPORT_MAX_TRIANGLES} triangles (at least $total)"
            }
            val c = part.mesh.coordinates
            for (v in c) {
                require(v.isFinite()) { "mesh export refused: non-finite coordinate in part '${scene.nameOf(i)}'" }
            }
        }
    }

    fun topology(scene: MeshExportScene): TopologySummary {
        var open = 0
        var nonManifold = 0
        val warnings = mutableListOf<String>()
        for ((i, part) in scene.parts.withIndex()) {
            val r = MeshTopologyCheck.check(part.mesh)
            open += r.openEdgeCount
            nonManifold += r.nonManifoldEdgeCount
            if (!r.watertight) {
                warnings +=
                    "kstep render: part '${scene.nameOf(i)}' is not watertight " +
                    "(${r.openEdgeCount} open edge(s), ${r.nonManifoldEdgeCount} non-manifold edge(s))."
            }
        }
        return TopologySummary(open == 0 && nonManifold == 0, open, nonManifold, warnings)
    }

    fun coloredPartCount(scene: MeshExportScene): Int = scene.parts.count { it.color != MeshColor.NEUTRAL }

    /** Decimal text of a 1e-6-quantised value, locale independent, trailing zeros stripped, no "-0". */
    fun fixed6(q: Long): String {
        if (q == 0L) return "0"
        val neg = q < 0
        val abs = if (neg) -q else q
        val whole = abs / 1_000_000L
        val frac = abs % 1_000_000L
        val sb = StringBuilder()
        if (neg) sb.append('-')
        sb.append(whole)
        if (frac != 0L) {
            var f = frac.toString().padStart(6, '0')
            f = f.trimEnd('0')
            sb.append('.').append(f)
        }
        return sb.toString()
    }

    /** Locale-independent decimal text of a double (9 places), no "-0". */
    fun decimal(v: Double): String {
        var s = String.format(java.util.Locale.ROOT, "%.9f", v)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0" || s.isEmpty()) "0" else s
    }
}
