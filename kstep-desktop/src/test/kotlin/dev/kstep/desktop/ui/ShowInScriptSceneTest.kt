package dev.kstep.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import dev.kstep.desktop.pick.AssemblyTree
import dev.kstep.desktop.pick.GroupInfo
import dev.kstep.desktop.pick.MultiInfo
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.ShowInScriptMessage
import dev.kstep.desktop.pick.TestScenes
import dev.kstep.desktop.pick.TreeViewLogic
import dev.kstep.desktop.pick.VisibilityCounts
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.math.abs
import kotlin.time.Duration.Companion.seconds

/** Headless checks of the "Show in Script" button, its message with Find, and the tree double click (ADR-0068). */
class ShowInScriptSceneTest :
    StringSpec({
        fun click(
            scene: ImageComposeScene,
            x: Float,
            y: Float,
        ) {
            scene.sendPointerEvent(PointerEventType.Move, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Press, Offset(x, y))
            scene.sendPointerEvent(PointerEventType.Release, Offset(x, y))
            scene.render()
        }

        fun panelScene(
            action: ShowInScriptUi,
            body: (ImageComposeScene) -> Unit,
        ) {
            val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f))
            try {
                scene.setContent {
                    Box(Modifier.fillMaxSize()) {
                        GroupInspectionPanel(
                            GroupInfo("G", 2, 10L, doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0)),
                            Color.White,
                            action = action,
                        )
                    }
                }
                scene.render()
                body(scene)
            } finally {
                scene.close()
            }
        }

        "an enabled button calls back, a disabled one does not".config(timeout = 60.seconds) {
            var enabledCalls = 0
            panelScene(ShowInScriptUi(enabled = true, onClick = { enabledCalls++ })) { scene ->
                for (y in 60..220 step 8) click(scene, 60f, y.toFloat())
            }
            (enabledCalls >= 1) shouldBe true

            var disabledCalls = 0
            panelScene(
                ShowInScriptUi(
                    enabled = false,
                    disabledReason = "No script for this model",
                    onClick = { disabledCalls++ },
                ),
            ) { scene ->
                for (y in 60..220 step 8) click(scene, 60f, y.toFloat())
            }
            disabledCalls shouldBe 0
        }

        "an ambiguous message offers Find and passes the query on".config(timeout = 60.seconds) {
            val queries = mutableListOf<String>()
            val action =
                ShowInScriptUi(
                    enabled = true,
                    message = ShowInScriptMessage("“A” appears more than once", "\"A\""),
                    onFind = { queries += it },
                )
            panelScene(action) { scene ->
                for (y in 60..260 step 6) {
                    for (x in listOf(230f, 245f, 255f)) click(scene, x, y.toFloat())
                }
            }
            queries shouldContain "\"A\""
        }

        // ADR-0094: the button sits right below the title, the message is the last line and reserves no height.
        val doubles = doubleArrayOf(0.0, 0.0, 0.0)
        val part =
            PartInfo(
                "P",
                0,
                10,
                PartPlacement(doubles, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)),
                "#FF8800",
                doubles,
                doubleArrayOf(1.0, 1.0, 1.0),
                doubles,
            )
        val group = GroupInfo("G", 2, 10L, doubles, doubleArrayOf(1.0, 1.0, 1.0))
        val multi = MultiInfo(2, 10L, doubles, doubleArrayOf(1.0, 1.0, 1.0))

        fun measurePanel(
            kind: String,
            withMessage: Boolean,
            names: Int = 1,
        ): Map<String, Rect> {
            val bounds = HashMap<String, Rect>()
            val action =
                ShowInScriptUi(
                    enabled = true,
                    message = if (withMessage) ShowInScriptMessage("“A” appears more than once", "\"A\"") else null,
                )
            val probe: (String, Rect) -> Unit = { tag, rect -> bounds[tag] = rect }
            val scene = ImageComposeScene(width = 300, height = 600, density = Density(1f))
            try {
                scene.setContent {
                    Box(Modifier.fillMaxSize()) {
                        when (kind) {
                            "part" -> InspectionPanel(part, Color.White, action = action, probe = probe)
                            "group" -> GroupInspectionPanel(group, Color.White, action = action, probe = probe)
                            else ->
                                MultiInspectionPanel(
                                    multi,
                                    List(names) { "Name $it" },
                                    Color.White,
                                    action = action,
                                    probe = probe,
                                )
                        }
                    }
                }
                scene.render()
                scene.render(400_000_000)
            } finally {
                scene.close()
            }
            return bounds
        }

        val cases = listOf("part" to 1, "group" to 1, "multi" to 1, "multi" to 5)

        "the button keeps the same distance from the panel top with and without a message, for every panel" {
            val offsets =
                cases.flatMap { (kind, names) ->
                    listOf(false, true).map { msg ->
                        val b = measurePanel(kind, msg, names)
                        b.getValue(TAG_INSPECT_SHOW_IN_SCRIPT).top - b.getValue(TAG_INSPECT_PANEL).top
                    }
                }
            offsets.forEach { abs(it - offsets.first()) shouldBeLessThan 0.5f }
        }

        "the message adds exactly its own height: no empty space is reserved".config(timeout = 60.seconds) {
            for ((kind, names) in cases) {
                val without = measurePanel(kind, false, names)
                val with = measurePanel(kind, true, names)
                without.containsKey(TAG_INSPECT_MESSAGE) shouldBe false
                val message = with.getValue(TAG_INSPECT_MESSAGE)
                val h0 = without.getValue(TAG_INSPECT_PANEL).height
                val h1 = with.getValue(TAG_INSPECT_PANEL).height
                (h1 > h0) shouldBe true
                // the message row plus its 6 dp of top padding, nothing else
                abs((h1 - h0) - (message.height + 6f)) shouldBeLessThan 0.5f
            }
        }

        "the message is the last line, below the info lines and the button".config(timeout = 60.seconds) {
            for ((kind, names) in cases) {
                val b = measurePanel(kind, true, names)
                val panel = b.getValue(TAG_INSPECT_PANEL)
                val button = b.getValue(TAG_INSPECT_SHOW_IN_SCRIPT)
                val message = b.getValue(TAG_INSPECT_MESSAGE)
                (message.top >= button.bottom + 30f) shouldBe true
                abs(panel.bottom - 10f - message.bottom) shouldBeLessThan 0.5f
            }
        }

        // 0 Top (group), 1 Sub (group), 2 X [0], 3 Y [1]
        val tree =
            AssemblyTree(
                arrayOf("Top", "Sub", "X", "Y"),
                intArrayOf(-1, 0, 1, 1),
                intArrayOf(-1, -1, 0, 1),
            )
        val (_, table) = TestScenes.scene(TestScenes.triangle(0.0, 0.0, 0.0), TestScenes.triangle(20.0, 0.0, 0.0))

        fun treeScene(
            names: MutableList<String>,
            toggles: MutableList<Int>,
            body: (ImageComposeScene) -> Unit,
        ) {
            val scene = ImageComposeScene(width = 300, height = 300, density = Density(1f))
            val selection = mutableStateOf(SelectionState())
            try {
                scene.setContent {
                    AssemblyTreePanel(
                        tree = tree,
                        table = table,
                        view = TreeViewLogic.initial(tree),
                        onView = { toggles += 1 },
                        selection = selection,
                        dispatch = {},
                        dark = false,
                        background = Color.White,
                        nodeVisibility = null,
                        counts = VisibilityCounts(0, 0),
                        onCycleNode = {},
                        onCycleSelected = {},
                        onShowInScript = { name, _ -> names += name },
                    )
                }
                scene.render()
                body(scene)
            } finally {
                scene.close()
            }
        }

        "a double click on a part asks for its name, on a group it only folds".config(timeout = 90.seconds) {
            val partNames = mutableListOf<String>()
            val groupNames = mutableListOf<String>()
            // one fresh scene per candidate row, so earlier clicks cannot influence later ones
            for (y in 10..150 step 12) {
                treeScene(partNames, mutableListOf()) { scene ->
                    click(scene, 110f, y.toFloat())
                    click(scene, 110f, y.toFloat())
                }
            }
            partNames shouldContain "X"
            partNames shouldContain "Y"
            partNames shouldNotContain "Top"
            partNames shouldNotContain "Sub"
            groupNames shouldBe emptyList()
        }

        "a single click on a part does not ask for its name".config(timeout = 60.seconds) {
            val names = mutableListOf<String>()
            for (y in 10..150 step 12) {
                treeScene(names, mutableListOf()) { scene -> click(scene, 110f, y.toFloat()) }
            }
            names shouldBe emptyList()
            tree.name(2) shouldNotBe ""
        }
    })
