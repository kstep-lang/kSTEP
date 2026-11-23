package dev.kstep.desktop.ui

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.dp
import dev.kstep.desktop.menu.MenuIconId
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Raster guard of ADR-0073: 16 grid, stroke 1.0, usable 1.5..14.5, axis-parallel segments on a half pixel. */
class KstepMenuIconsTest :
    StringSpec({
        fun paths(id: MenuIconId): List<VectorPath> =
            KstepMenuIcons
                .vectorFor(id)
                .root
                .filterIsInstance<VectorPath>()

        fun isHalf(v: Float) =
            abs(v * 2f - Math.round(v * 2f)) < 1e-4f && abs(v - Math.floor(v.toDouble()) - 0.5) < 1e-4

        fun inArea(v: Float) = v >= 1.5f - 1e-4f && v <= 14.5f + 1e-4f

        for (id in MenuIconId.entries) {
            "$id is a 16 by 16 stroke-only vector" {
                val v = KstepMenuIcons.vectorFor(id)
                v.viewportWidth shouldBe 16f
                v.viewportHeight shouldBe 16f
                v.defaultWidth shouldBe 16.dp
                v.defaultHeight shouldBe 16.dp
                paths(id).shouldNotBeEmpty()
                for (p in paths(id)) {
                    p.pathData.shouldNotBeEmpty()
                    p.fill shouldBe null
                    p.strokeLineWidth shouldBe 1f
                }
            }

            "$id keeps to the usable area and puts axis-parallel segments on half pixels" {
                for (p in paths(id)) {
                    var x = 0f
                    var y = 0f
                    var startX = 0f
                    var startY = 0f

                    fun check(
                        nx: Float,
                        ny: Float,
                        fromMove: Boolean,
                    ) {
                        inArea(nx) shouldBe true
                        inArea(ny) shouldBe true
                        if (!fromMove) {
                            if (nx == x) isHalf(nx) shouldBe true
                            if (ny == y) isHalf(ny) shouldBe true
                        }
                        x = nx
                        y = ny
                    }
                    for (n in p.pathData) {
                        when (n) {
                            is PathNode.MoveTo -> {
                                check(n.x, n.y, true)
                                startX = n.x
                                startY = n.y
                            }
                            is PathNode.LineTo -> check(n.x, n.y, false)
                            is PathNode.HorizontalTo -> check(n.x, y, false)
                            is PathNode.VerticalTo -> check(x, n.y, false)
                            is PathNode.ArcTo -> check(n.arcStartX, n.arcStartY, true)
                            PathNode.Close -> {
                                x = startX
                                y = startY
                            }
                            else -> error("unexpected path node $n")
                        }
                    }
                }
            }
        }
    })
