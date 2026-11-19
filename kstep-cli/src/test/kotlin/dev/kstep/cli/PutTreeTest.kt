package dev.kstep.cli

import dev.kstep.geometry.MeshColor
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.Placement
import dev.kstep.preview.PreviewContentKind
import dev.kstep.preview.PreviewOutcome
import dev.kstep.preview.RenderFormat
import dev.kstep.preview.RenderedAssemblyTree
import dev.kstep.preview.RenderedExportTarget
import dev.kstep.preview.RenderedPart
import dev.kstep.preview.RenderedTreeNode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The assembly tree the desktop worker adds to the render result (ADR-0054), and its absence from the public JSON. */
class PutTreeTest :
    StringSpec({
        fun node(
            name: String,
            parent: Int,
            part: Int,
        ) = RenderedTreeNode(name, parent, part)

        fun render(nodes: List<RenderedTreeNode>): JsonObject = buildJsonObject { putTree(RenderedAssemblyTree(nodes)) }

        fun part(name: String) = RenderedPart(name, 0, 1, Placement.IDENTITY, MeshColor.NEUTRAL)

        fun rendered(
            parts: List<RenderedPart>?,
            tree: RenderedAssemblyTree?,
            exportTargets: List<RenderedExportTarget> = emptyList(),
        ) = PreviewOutcome.Rendered(
            bytes = ByteArray(0),
            format = RenderFormat.GLB,
            contentKind = PreviewContentKind.GEOMETRY,
            fallbackReason = null,
            noticeLines = emptyList(),
            occt = OcctAvailability.Unavailable("test"),
            model = null,
            geometry = null,
            glb = null,
            warnings = emptyList(),
            parts = parts,
            tree = tree,
            exportTargets = exportTargets,
        )

        "nodes are written with name, parent and part" {
            val json = render(listOf(node("Top", -1, -1), node("Bolt", 0, 0)))
            val nodes = json["tree"]!!.jsonObject["nodes"]!!.jsonArray
            nodes.size shouldBe 2
            nodes[1].jsonObject["name"]!!.jsonPrimitive.content shouldBe "Bolt"
            nodes[1].jsonObject["parent"]!!.jsonPrimitive.int shouldBe 0
            nodes[1].jsonObject["part"]!!.jsonPrimitive.int shouldBe 0
            json.containsKey("treeOmitted") shouldBe false
        }

        "a long name is cut to the limit" {
            val json = render(listOf(node("a".repeat(1000), -1, 0)))
            json["tree"]!!
                .jsonObject["nodes"]!!
                .jsonArray[0]
                .jsonObject["name"]!!
                .jsonPrimitive.content.length shouldBe DesktopPartLimits.MAX_NAME_CHARS
        }

        "more nodes than the limit are replaced by treeOmitted" {
            val nodes = listOf(node("Top", -1, -1)) + List(DesktopPartLimits.MAX_TREE_NODES) { node("g", 0, 0) }
            val json = render(nodes)
            json["tree"] shouldBe JsonNull
            val omitted = json["treeOmitted"]!!.jsonObject
            omitted["reason"]!!.jsonPrimitive.content shouldBe "too_many_nodes"
            omitted["count"]!!.jsonPrimitive.int shouldBe nodes.size
        }

        "exactly the node limit is still written" {
            val nodes = listOf(node("Top", -1, -1)) + List(DesktopPartLimits.MAX_TREE_NODES - 1) { node("g", 0, 0) }
            render(nodes)["tree"]!!.jsonObject["nodes"]!!.jsonArray.size shouldBe DesktopPartLimits.MAX_TREE_NODES
        }

        "a tree deeper than the limit is replaced by treeOmitted, exactly the limit is written" {
            fun chain(depth: Int) = List(depth + 1) { node("n$it", it - 1, if (it == depth) 0 else -1) }
            render(chain(DesktopPartLimits.MAX_TREE_DEPTH + 1))["treeOmitted"]!!
                .jsonObject["reason"]!!
                .jsonPrimitive.content shouldBe "too_deep"
            render(chain(DesktopPartLimits.MAX_TREE_DEPTH)).containsKey("treeOmitted") shouldBe false
        }

        "the desktop result carries the tree, the public JSON carries neither tree nor parts" {
            val outcome =
                rendered(listOf(part("Bolt")), RenderedAssemblyTree(listOf(node("Top", -1, -1), node("Bolt", 0, 0))))
            val desktop = successJson("o.glb", outcome, null, includeParts = true)
            desktop.containsKey("parts") shouldBe true
            desktop.containsKey("tree") shouldBe true
            val public = successJson("o.glb", outcome, null)
            public.containsKey("parts") shouldBe false
            public.containsKey("tree") shouldBe false
            // everything but the two additive keys is byte for byte what it was
            val stripped = JsonObject(desktop.filterKeys { it != "parts" && it != "tree" })
            stripped.toString() shouldBe public.toString()
        }

        "without a part list there is no tree either" {
            val outcome = rendered(null, RenderedAssemblyTree(listOf(node("Top", -1, 0))))
            successJson("o.glb", outcome, null, includeParts = true).containsKey("tree") shouldBe false
        }

        "with more parts than the limit the tree is left out together with the parts" {
            val outcome =
                rendered(
                    List(DesktopPartLimits.MAX_PARTS + 1) { part("p$it") },
                    RenderedAssemblyTree(listOf(node("Top", -1, 0))),
                )
            val json = successJson("o.glb", outcome, null, includeParts = true)
            json["parts"] shouldBe JsonNull
            json.containsKey("tree") shouldBe false
        }

        "declared export targets reach only the desktop result, and only when there are some" {
            val targets = listOf(RenderedExportTarget("step", "bolt.step"), RenderedExportTarget("glb", "bolt.glb"))
            val withTargets = rendered(listOf(part("Bolt")), null, targets)
            val without = rendered(listOf(part("Bolt")), null)
            val desktop = successJson("o.glb", withTargets, null, includeParts = true)
            val array = desktop["exportTargets"]!!.jsonArray
            array.size shouldBe 2
            array[0].jsonObject["format"]!!.jsonPrimitive.content shouldBe "step"
            array[1].jsonObject["fileName"]!!.jsonPrimitive.content shouldBe "bolt.glb"
            // the public JSON is byte for byte what it is without a block
            successJson("o.glb", withTargets, null).toString() shouldBe successJson("o.glb", without, null).toString()
            successJson("o.glb", withTargets, null).containsKey("exportTargets") shouldBe false
            // no block: no field even for the desktop
            successJson("o.glb", without, null, includeParts = true).containsKey("exportTargets") shouldBe false
        }
    })
