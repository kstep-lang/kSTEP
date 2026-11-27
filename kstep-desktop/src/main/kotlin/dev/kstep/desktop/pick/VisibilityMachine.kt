package dev.kstep.desktop.pick

import kotlin.math.roundToInt

/**
 * Pure transitions of the part visibility (ADR-0055). Everything works on part index ranges: the tree guarantees that a
 * group is a contiguous range, and without a tree single parts still work. An operation that changes nothing returns the
 * very same instance, so callers (and tests) can tell "unchanged" with `===`. Ranges are clipped to the scene; the empty
 * or foreign range (`-1..-1`) is a no-op.
 */
object VisibilityMachine {
    private const val DEFAULT_ALPHA = 0.25f
    private const val MIN_ALPHA = 0.05f
    private const val MAX_ALPHA = 0.6f

    private fun clip(
        scene: VisibilityScene,
        range: IntRange,
    ): IntRange = maxOf(range.first, 0)..minOf(range.last, scene.size - 1)

    fun effective(
        s: VisibilityState,
        scene: VisibilityScene,
        part: Int,
    ): Visibility =
        if (part in
            0 until scene.size
        ) {
            s.leafStates[scene.partKey[part]] ?: Visibility.Visible
        } else {
            Visibility.Visible
        }

    fun rangeState(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): TreeVisibility {
        val r = clip(scene, range)
        if (r.isEmpty()) return TreeVisibility.Visible
        val first = effective(s, scene, r.first)
        for (i in r.first + 1..r.last) if (effective(s, scene, i) != first) return TreeVisibility.Mixed
        return first.toTree()
    }

    private fun Visibility.toTree(): TreeVisibility =
        when (this) {
            Visibility.Visible -> TreeVisibility.Visible
            Visibility.Transparent -> TreeVisibility.Transparent
            Visibility.Hidden -> TreeVisibility.Hidden
        }

    private fun setRange(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
        target: Visibility,
    ): VisibilityState {
        val r = clip(scene, range)
        if (r.isEmpty()) return s
        return setEach(s, scene, r.last - r.first + 1, { r.first + it }, target)
    }

    /** Sets [count] parts (the i-th is `at(i)`, out-of-scene indices are skipped) to [target]; `===` when unchanged. */
    private inline fun setEach(
        s: VisibilityState,
        scene: VisibilityScene,
        count: Int,
        at: (Int) -> Int,
        target: Visibility,
    ): VisibilityState {
        var map: HashMap<PartKey, Visibility>? = null
        for (n in 0 until count) {
            val i = at(n)
            if (i !in 0 until scene.size) continue
            val key = scene.partKey[i]
            val current = (map ?: s.leafStates)[key] ?: Visibility.Visible
            if (current == target) continue
            if (map == null) map = HashMap(s.leafStates)
            if (target == Visibility.Visible) map.remove(key) else map[key] = target
        }
        return if (map == null) s else s.copy(leafStates = map)
    }

    /** Visible, then Transparent, then Hidden, then Visible again; a mixed range becomes Visible. */
    fun cycle(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): VisibilityState =
        setRange(
            s,
            scene,
            range,
            when (rangeState(s, scene, range)) {
                TreeVisibility.Visible -> Visibility.Transparent
                TreeVisibility.Transparent -> Visibility.Hidden
                TreeVisibility.Hidden, TreeVisibility.Mixed -> Visibility.Visible
            },
        )

    fun toggleTransparent(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): VisibilityState =
        setRange(
            s,
            scene,
            range,
            if (rangeState(s, scene, range) ==
                TreeVisibility.Transparent
            ) {
                Visibility.Visible
            } else {
                Visibility.Transparent
            },
        )

    fun toggleHidden(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): VisibilityState =
        setRange(
            s,
            scene,
            range,
            if (rangeState(s, scene, range) == TreeVisibility.Hidden) Visibility.Visible else Visibility.Hidden,
        )

    fun hide(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): VisibilityState = setRange(s, scene, range, Visibility.Hidden)

    /** [hide] for an arbitrary set of part indices (a multi-selection); indices outside the scene are skipped. */
    fun hideParts(
        s: VisibilityState,
        scene: VisibilityScene,
        parts: IntArray,
    ): VisibilityState = setEach(s, scene, parts.size, { parts[it] }, Visibility.Hidden)

    /** Whether any part of [scene] is hidden or transparent, i.e. whether "Show All" would change anything. */
    fun anyNotVisible(
        s: VisibilityState,
        scene: VisibilityScene,
    ): Boolean = s.leafStates.keys.any { scene.contains(it) }

    /** Every part visible again; the opacity stays. */
    fun showAll(s: VisibilityState): VisibilityState =
        if (s.leafStates.isEmpty()) s else s.copy(leafStates = emptyMap())

    /** The range becomes Visible; everything else Transparent, except parts that are already Hidden. */
    fun isolate(
        s: VisibilityState,
        scene: VisibilityScene,
        range: IntRange,
    ): VisibilityState {
        val r = clip(scene, range)
        if (r.isEmpty()) return s
        return isolateWhere(s, scene) { it in r }
    }

    /** [isolate] for an arbitrary set of part indices; no valid index is a no-op. */
    fun isolateParts(
        s: VisibilityState,
        scene: VisibilityScene,
        parts: IntArray,
    ): VisibilityState {
        val keep = BooleanArray(scene.size)
        var any = false
        for (p in parts) {
            if (p in keep.indices) {
                keep[p] = true
                any = true
            }
        }
        if (!any) return s
        return isolateWhere(s, scene) { keep[it] }
    }

    private inline fun isolateWhere(
        s: VisibilityState,
        scene: VisibilityScene,
        isKept: (Int) -> Boolean,
    ): VisibilityState {
        val map = HashMap<PartKey, Visibility>()
        for (i in 0 until scene.size) {
            if (isKept(i)) continue
            val key = scene.partKey[i]
            map[key] = if (s.leafStates[key] == Visibility.Hidden) Visibility.Hidden else Visibility.Transparent
        }
        return if (map == s.leafStates) s else s.copy(leafStates = map)
    }

    /** Opacity of transparent parts: clamped to 5..60 %, rounded to 5 % steps; NaN and infinity give 25 %. */
    fun withAlpha(
        s: VisibilityState,
        a: Float,
    ): VisibilityState {
        val value = if (a.isNaN() || a.isInfinite()) DEFAULT_ALPHA else a.coerceIn(MIN_ALPHA, MAX_ALPHA)
        val step =
            (value / VisibilityState.ALPHA_UNIT).roundToInt().coerceIn(
                VisibilityState.MIN_STEP,
                VisibilityState.MAX_STEP,
            )
        return if (step == s.alphaStep) s else s.copy(alphaStep = step)
    }

    /**
     * After a re-run of the same file: keys of vanished parts are dropped, the rest stays. With no valid scene
     * ([scene] `null`, e.g. an invalid part list) nothing is dropped.
     */
    fun onRerun(
        s: VisibilityState,
        scene: VisibilityScene?,
    ): VisibilityState {
        if (scene == null) return s
        val kept = s.leafStates.filterKeys { scene.contains(it) }
        return if (kept.size == s.leafStates.size) s else s.copy(leafStates = kept)
    }

    fun counts(
        s: VisibilityState,
        scene: VisibilityScene,
    ): VisibilityCounts {
        var hidden = 0
        var transparent = 0
        for ((key, v) in s.leafStates) {
            if (!scene.contains(key)) continue
            if (v == Visibility.Hidden) {
                hidden++
            } else if (v == Visibility.Transparent) {
                transparent++
            }
        }
        return VisibilityCounts(hidden, transparent)
    }
}
