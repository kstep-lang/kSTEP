package dev.kstep.desktop.ui

import dev.kstep.desktop.model.PartTable
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.VisibilityArrays
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.viewer.ui.CanvasVisibility

/**
 * Everything derived from one [VisibilityState] for one scene, built once per change (never per frame, never per hover):
 * the arrays for picking and the tree rows, and the canvas input.
 */
internal class VisibilityView(
    val arrays: VisibilityArrays,
    val canvas: CanvasVisibility,
) {
    companion object {
        fun of(
            state: VisibilityState,
            scene: VisibilityScene,
            tree: AssemblyTree?,
            table: PartTable,
        ): VisibilityView {
            val arrays = VisibilityArrays.build(state, scene, tree)
            return VisibilityView(arrays, CanvasVisibility(table.triangleToPartArray(), arrays.partMode, arrays.alpha))
        }
    }
}
