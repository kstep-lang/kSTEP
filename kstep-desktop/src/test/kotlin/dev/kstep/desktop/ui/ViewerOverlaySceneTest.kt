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
            (narrow.bottom > 500f - 20f) shouldBe true
            (narrow.left < 20f) shouldBe true
        }
    })
