package dev.kstep.desktop.ui

import androidx.compose.runtime.mutableStateOf
import dev.kstep.desktop.menu.MenuOs
import dev.kstep.desktop.pick.PartRef
import dev.kstep.desktop.pick.PickIndex
import dev.kstep.desktop.pick.SelectionNotice
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.TestScenes
import dev.kstep.desktop.pick.TestScenes.triangle
import dev.kstep.render.mesh.Camera
import dev.kstep.render.mesh.MeshProjection
import dev.kstep.render.mesh.ScreenFrame
import dev.kstep.viewer.ui.PickModifiers
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/** The platform rule and the menu requests of the canvas picking (ADR-0092), with real picks. */
class DesktopPickingContextTest :
    StringSpec({
        val (mesh, table) =
            TestScenes.scene(
                triangle(0.0, 0.0, 0.0),
                triangle(40.0, 0.0, 0.0),
                triangle(80.0, 0.0, 0.0),
                names = listOf("A", "B", "C"),
            )
        val index = PickIndex(mesh, table)
        val framed = MeshProjection.projectFramed(mesh, 800.0, 600.0, Camera.ISOMETRIC, null, 0.0, 0.0)
        val frame: ScreenFrame = framed.frame.shouldNotBeNull()

        /** The pixel at the centre of part [p]'s first drawn triangle. */
        fun pixel(p: Int): Pair<Float, Float> {
            val t = framed.triangles.first { table.partOfTriangle(it.sourceIndex) == p }
            return ((t.ax + t.bx + t.cx) / 3.0).toFloat() to ((t.ay + t.by + t.cy) / 3.0).toFloat()
        }

        class Rig(
            os: MenuOs,
        ) {
            val selection = mutableStateOf(SelectionState())
            val menu = mutableStateOf<MenuRequest?>(null)
            val notices = mutableListOf<SelectionNotice>()
            val picking =
                DesktopPicking(
                    index,
                    table,
                    1L,
                    selection,
                    os = os,
                    menu = menu,
                    onNotice = { notices += it },
                )

            fun click(
                p: Int,
                mods: PickModifiers = PickModifiers(),
            ) {
                val (x, y) = pixel(p)
                picking.onClick(x, y, frame, mods)
            }

            fun secondary(p: Int) {
                val (x, y) = pixel(p)
                picking.onSecondaryClick(x, y, frame, PickModifiers())
            }

            val parts get() = selection.value.parts
        }

        "macOS: Cmd+click adds and removes, a plain click replaces" {
            val r = Rig(MenuOs.MAC)
            r.click(0)
            r.click(1, PickModifiers(meta = true))
            r.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
            r.click(0, PickModifiers(meta = true))
            r.parts shouldBe listOf(PartRef("B", 0))
            r.click(2)
            r.parts shouldBe listOf(PartRef("C", 0))
            r.menu.value.shouldBeNull()
        }

        "macOS: Ctrl+click is the context menu, not a toggle" {
            val r = Rig(MenuOs.MAC)
            r.click(0)
            r.click(1, PickModifiers(ctrl = true))
            r.parts shouldBe listOf(PartRef("B", 0))
            val m = r.menu.value.shouldNotBeNull()
            m.overPart shouldBe true
            m.origin shouldBe MenuOrigin.Viewer
        }

        "Windows and Linux: Ctrl+click toggles, Cmd (Meta) does nothing special" {
            for (os in listOf(MenuOs.WINDOWS, MenuOs.LINUX)) {
                val r = Rig(os)
                r.click(0)
                r.click(1, PickModifiers(ctrl = true))
                r.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
                r.click(2, PickModifiers(meta = true))
                r.parts shouldBe listOf(PartRef("C", 0))
                r.menu.value.shouldBeNull()
            }
        }

        "a modifier click on empty space neither clears nor opens anything where it only toggles" {
            val r = Rig(MenuOs.WINDOWS)
            r.click(0)
            r.picking.onClick(1f, 1f, frame, PickModifiers(ctrl = true))
            r.parts shouldBe listOf(PartRef("A", 0))
            r.picking.onClick(1f, 1f, frame, PickModifiers())
            r.parts shouldBe emptyList()
        }

        "a secondary click on an unselected part replaces the selection, on a selected one keeps all" {
            val r = Rig(MenuOs.WINDOWS)
            r.click(0)
            r.click(1, PickModifiers(ctrl = true))
            r.secondary(1)
            r.parts shouldBe listOf(PartRef("A", 0), PartRef("B", 0))
            r.menu.value
                .shouldNotBeNull()
                .overPart shouldBe true
            r.secondary(2)
            r.parts shouldBe listOf(PartRef("C", 0))
        }

        "a secondary click on empty space opens the empty-space menu and selects nothing" {
            val r = Rig(MenuOs.LINUX)
            r.click(0)
            r.picking.onSecondaryClick(1f, 1f, frame, PickModifiers())
            r.parts shouldBe listOf(PartRef("A", 0))
            r.menu.value
                .shouldNotBeNull()
                .overPart shouldBe false
        }

        "the Menu key opens a centred request, over the selection when there is one" {
            val r = Rig(MenuOs.WINDOWS)
            r.picking.onMenuKey() shouldBe true
            r.menu.value.shouldNotBeNull().let {
                it.centered shouldBe true
                it.overPart shouldBe false
            }
            r.click(0)
            r.picking.onMenuKey()
            r.menu.value
                .shouldNotBeNull()
                .overPart shouldBe true
        }

        "Escape ends a multi-selection and is not consumed without one" {
            val r = Rig(MenuOs.WINDOWS)
            r.picking.onEscape() shouldBe false
            r.click(0)
            r.click(1, PickModifiers(ctrl = true))
            r.picking.onEscape() shouldBe true
            r.parts shouldBe emptyList()
            r.picking.onEscape() shouldBe false
        }

        "without a menu state the secondary click and the Menu key are ignored" {
            val sel = mutableStateOf(SelectionState())
            val p = DesktopPicking(index, table, 1L, sel, os = MenuOs.WINDOWS)
            val (x, y) = pixel(0)
            p.onSecondaryClick(x, y, frame, PickModifiers())
            p.onMenuKey() shouldBe false
            sel.value.parts shouldBe emptyList()
        }
    })
