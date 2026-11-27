package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.pick.GroupInfo
import dev.kstep.viewer.ui.ViewerControlsHint
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.seconds

/** The badge and the inspection panel composed headlessly at several viewer widths: they never overlap (ADR-0094). */
class ViewerOverlaySceneTest :
    StringSpec({
        val badgeText = "Showing last good result · 12:34:56"

        fun measure(
            widthDp: Int,
            panelShown: Boolean,
        ): Pair<Rect?, Rect?> {
            var badge: Rect? = null
            var panel: Rect? = null
            val scene = ImageComposeScene(width = widthDp, height = 500, density = Density(1f))
            try {
                scene.setContent {
                    Box(Modifier.width(widthDp.dp).height(500.dp)) {
                        BadgeBox(badgeText, panelShown, probe = { badge = it })
                        if (panelShown) {
                            GroupInspectionPanel(
                                GroupInfo("G", 2, 10L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0)),
                                Color.White,
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(ViewerOverlayLayout.MARGIN_DP.dp)
                                    .onGloballyPositioned { panel = it.boundsInRoot() },
                            )
                        }
                    }
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
            }
            return badge to panel
        }

        "badge and panel never overlap and the badge stays inside the viewer" {
            for (w in listOf(660, 480, 432, 360, 300)) {
                val (badge, panel) = measure(w, panelShown = true)
                check(badge != null && panel != null) { "no bounds at $w dp" }
                check(!badge.overlaps(panel)) { "badge $badge overlaps panel $panel at $w dp" }
                check(badge.left >= 0f && badge.top >= 0f && badge.right <= w && badge.bottom <= 500f) {
                    "badge $badge leaves the viewer at $w dp"
                }
            }
        }

        "the badge is top left while it fits next to the panel and at the bottom when it does not".config(
            timeout = 60.seconds,
        ) {
            val wide = measure(660, panelShown = true).first!!
            val narrow = measure(300, panelShown = true).first!!
            (wide.top < 20f) shouldBe true
            (narrow.bottom > 500f - ViewerOverlayLayout.hintReserveDp(300f) - 1f) shouldBe true
            (narrow.left < 20f) shouldBe true
        }

        "the real controls hint, the badge and the panel never overlap and the hint fits the reserve".config(
            timeout = 120.seconds,
        ) {
            for (w in listOf(410, 360, 300)) {
                var badge: Rect? = null
                var panel: Rect? = null
                var hint: Rect? = null
                val scene = ImageComposeScene(width = w, height = 700, density = Density(1f))
                try {
                    scene.setContent {
                        Box(Modifier.width(w.dp).height(700.dp)) {
                            BadgeBox(badgeText, true, probe = { badge = it })
                            GroupInspectionPanel(
                                GroupInfo("G", 2, 10L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0)),
                                Color.White,
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(ViewerOverlayLayout.MARGIN_DP.dp)
                                    .onGloballyPositioned { panel = it.boundsInRoot() },
                            )
                            // The probe sits outside ViewerControlsHint's own padding, so its bounds are the hint
                            // box plus the 12 dp outer margin, as the layout reserve counts it.
                            ViewerControlsHint(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onGloballyPositioned { hint = it.boundsInRoot() },
                            )
                        }
                    }
                    scene.render()
                    scene.render(400_000_000)
                } finally {
                    scene.close()
                }
                check(badge != null && panel != null && hint != null) { "no bounds at $w dp" }
                check(!badge.overlaps(panel)) { "badge $badge overlaps panel $panel at $w dp" }
                // The painted box is the probe's bounds without the 12 dp outer margin on every side.
                val m = ViewerOverlayLayout.MARGIN_DP
                val painted = Rect(hint.left + m, hint.top + m, hint.right - m, hint.bottom - m)
                check(!badge.overlaps(painted)) { "badge $badge overlaps hint $painted at $w dp" }
                check(!panel.overlaps(painted)) { "panel $panel overlaps hint $painted at $w dp" }
                // Reserve-inequality, not exact pixels: the real font differs per machine.
                val needed = (hint.bottom - hint.top) - m + ViewerOverlayLayout.GAP_DP
                check(needed <= ViewerOverlayLayout.hintReserveDp(w.toFloat())) {
                    "hint needs $needed dp, the reserve is ${ViewerOverlayLayout.hintReserveDp(w.toFloat())} at $w dp"
                }
            }
        }
    })
