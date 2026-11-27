package dev.kstep.desktop.mcp

import dev.kstep.desktop.edit.CaretMapper
import dev.kstep.desktop.pick.PartRef
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.Visibility
import dev.kstep.desktop.pick.VisibilityScene
import dev.kstep.desktop.pick.VisibilityState
import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.NameSanitizer
import dev.kstep.desktop.run.Severity
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val V = McpProtocol.STRUCTURED_VERSION
private const val OLD = "2025-03-26"
private const val ERRORS = "kstep_desktop_get_errors"
private const val PARTS = "kstep_desktop_get_parts"
private const val TREE = "kstep_desktop_get_assembly_tree"
private const val SELECTION = "kstep_desktop_get_selection"
private const val VIEW = "kstep_desktop_get_view"
private const val FIND = "kstep_desktop_find_in_script"

private fun call(
    s: DesktopStateSnapshot,
    name: String,
    args: String = "{}",
    limits: McpToolLimits = McpToolLimits(),
    version: String = V,
): JsonObject = McpToolRegistry(FakeHost(s), limits).call(name, Json.parseToJsonElement(args).jsonObject, version)

private fun JsonObject.sc() = getValue("structuredContent").jsonObject

private fun JsonObject.isErr() = getValue("isError").jsonPrimitive.boolean

private fun JsonObject.code() = sc().getValue("code").jsonPrimitive.content

private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content

private fun JsonObject.num(k: String) = getValue(k).jsonPrimitive.int

private fun JsonObject.arr(k: String) = getValue(k).jsonArray

private fun report(
    errors: List<DesktopError>,
    severity: Severity = Severity.ERROR,
    origin: PreviewKind = PreviewKind.SAVED_FILE,
    buffer: String? = null,
) = McpRunReport(errors, severity, origin, buffer)

private fun scene3() = sceneOf("a", "b", "c")

class McpReadToolsTest :
    StringSpec({
        // ---- get_errors ----
        "get_errors lists the errors with position and severity" {
            val r =
                report(listOf(DesktopError("KSTEP-S-001", 3, 5, "boom"), DesktopError("KSTEP-S-002", null, null, "x")))
            val s = call(snap(run = RunKind.ERROR, runReport = r), ERRORS).sc()
            s.str("run") shouldBe "error"
            s.num("total") shouldBe 2
            s.getValue("truncated").jsonPrimitive.boolean shouldBe false
            val e0 = s.arr("errors")[0].jsonObject
            e0.str("code") shouldBe "KSTEP-S-001"
            e0.num("line") shouldBe 3
            e0.num("column") shouldBe 5
            e0.str("severity") shouldBe "error"
            s.arr("errors")[1].jsonObject.getValue("line") shouldBe JsonNull
            s.num("snapshotRevision") shouldBe 7
        }

        "get_errors reports warnings of an ok run" {
            val r = report(listOf(DesktopError("KSTEP-D-012", null, null, "part list invalid")), Severity.WARNING)
            val s = call(snap(runReport = r), ERRORS).sc()
            s.str("run") shouldBe "ok"
            s.arr("errors")[0].jsonObject.str("severity") shouldBe "warning"
        }

        "get_errors without a run is empty and without editedSinceRun" {
            val s = call(snap(run = RunKind.IDLE, origin = null), ERRORS).sc()
            s.arr("errors").size shouldBe 0
            s.getValue("editedSinceRun") shouldBe JsonNull
            s.getValue("origin") shouldBe JsonNull
            call(snap(kind = EditorKind.NONE, text = null, runReport = report(errorList(1))), ERRORS)
                .sc()
                .getValue("editedSinceRun") shouldBe JsonNull
        }

        "get_errors tells whether the text changed since the run" {
            fun edited(s: DesktopStateSnapshot) =
                call(s, ERRORS)
                    .sc()
                    .getValue("editedSinceRun")
                    .jsonPrimitive.boolean
            val buffer = report(errorList(1), origin = PreviewKind.UNSAVED_BUFFER, buffer = "box(1)")
            edited(snap(text = "box(1)", runReport = buffer)) shouldBe false
            edited(snap(text = "box(2)", runReport = buffer)) shouldBe true
            val saved = report(errorList(1))
            edited(snap(dirty = false, runReport = saved)) shouldBe false
            edited(snap(dirty = true, runReport = saved)) shouldBe true
        }

        "get_errors cleans a message exactly like the error bar" {
            val raw = "bad \u001B[31mred\u001B[0m ‮evil\u0007 tab\there\nnext"
            val s = call(snap(runReport = report(listOf(DesktopError("C\u0007", 1, 1, raw)))), ERRORS).sc()
            val e = s.arr("errors")[0].jsonObject
            e.str("message") shouldBe NameSanitizer.cleanText(raw)
            e.str("code") shouldBe NameSanitizer.cleanText("C\u0007")
            e.str("message") shouldNotContain "‮"
            e.str("message") shouldNotContain "\u001B"
        }

        "get_errors honours the limit and says total and truncated" {
            val s = call(snap(runReport = report(errorList(250))), ERRORS, """{"limit":200}""").sc()
            s.arr("errors").size shouldBe 200
            s.num("total") shouldBe 250
            s.getValue("truncated").jsonPrimitive.boolean shouldBe true
            call(snap(runReport = report(errorList(250))), ERRORS, """{"limit":3}""").sc().arr("errors").size shouldBe 3
        }

        // ---- get_parts ----
        "get_parts lists the parts with fields" {
            val s = call(snap(scene = scene3()), PARTS).sc()
            s.getValue("model").jsonObject.num("partCount") shouldBe 3
            s.num("total") shouldBe 3
            val p1 = s.arr("parts")[1].jsonObject
            p1.str("name") shouldBe "b"
            p1.num("index") shouldBe 1
            p1.num("triangles") shouldBe 12
            p1.str("color") shouldBe "#336699"
            p1.num("sourceLine") shouldBe 2
            p1.arr("size").map { it.jsonPrimitive.content } shouldBe listOf("2.0", "3.0", "4.0")
            p1.arr("position")[0].jsonPrimitive.content shouldBe "1.0"
            p1.getValue("hidden").jsonPrimitive.boolean shouldBe false
        }

        "get_parts counts occurrences of equal names" {
            val s = call(snap(scene = sceneOf("bolt", "nut", "bolt")), PARTS).sc()
            s.arr("parts").map { it.jsonObject.num("occurrence") } shouldBe listOf(0, 0, 1)
        }

        "get_parts reads hidden, transparent and selected" {
            val scene = scene3()
            val keys = VisibilityScene.of(scene.parts, null).partKey
            val vis = VisibilityState(mapOf(keys[0] to Visibility.Hidden, keys[1] to Visibility.Transparent))
            val sel = SelectionState(selected = "c", occurrence = 0)
            val s = call(snap(scene = scene, visibility = vis, selection = sel), PARTS).sc()
            val p = s.arr("parts").map { it.jsonObject }
            p[0].getValue("hidden").jsonPrimitive.boolean shouldBe true
            p[1].getValue("transparent").jsonPrimitive.boolean shouldBe true
            p.map { it.getValue("selected").jsonPrimitive.boolean } shouldBe listOf(false, false, true)
            val multi = SelectionState(selected = "a", more = listOf(PartRef("c", 0)))
            call(snap(scene = scene, selection = multi), PARTS)
                .sc()
                .arr("parts")
                .map {
                    it.jsonObject
                        .getValue("selected")
                        .jsonPrimitive.boolean
                } shouldBe listOf(true, false, true)
            val tree = treeOf(Triple("root", -1, -1), Triple("a", 0, 0), Triple("b", 0, 1), Triple("c", 0, 2))
            val withTree = McpScene(scene.parts, tree, 3)
            val group = SelectionState(selectedGroup = tree.key(0))
            call(snap(scene = withTree, selection = group), PARTS)
                .sc()
                .arr("parts")
                .all {
                    it.jsonObject
                        .getValue("selected")
                        .jsonPrimitive.boolean
                } shouldBe true
        }

        "get_parts without a model is empty, not an error" {
            val r = call(snap(), PARTS)
            r.isErr() shouldBe false
            r.sc().getValue("model") shouldBe JsonNull
            r.sc().num("total") shouldBe 0
        }

        "get_parts pages with offset and limit" {
            val scene = sceneOf(*Array(10) { "p$it" })
            val s = call(snap(scene = scene), PARTS, """{"offset":4,"limit":3}""").sc()
            s.arr("parts").map { it.jsonObject.str("name") } shouldBe listOf("p4", "p5", "p6")
            s.getValue("truncated").jsonPrimitive.boolean shouldBe true
            s.num("offset") shouldBe 4
            val end = call(snap(scene = scene), PARTS, """{"offset":8}""").sc()
            end.arr("parts").size shouldBe 2
            end.getValue("truncated").jsonPrimitive.boolean shouldBe false
        }

        "get_parts keeps 4096 long names below one MiB and says it was cut" {
            val parts = List(4096) { partInfo("p$it" + "x".repeat(250), it) }
            val scene = McpScene(partTable(parts), null, 1)
            val r = call(snap(scene = scene), PARTS)
            val body = r.sc()
            body.getValue("truncated").jsonPrimitive.boolean shouldBe true
            (r.toString().toByteArray().size <= (1 shl 20)) shouldBe true
            (body.arr("parts").size in 1..4095) shouldBe true
            body.num("total") shouldBe 4096
            // the old protocol has the body only once, so more fits
            val old = call(snap(scene = scene), PARTS, version = OLD)
            (old.toString().toByteArray().size <= (1 shl 20)) shouldBe true
        }

        "get_parts writes null for numbers that are not finite and never invalid JSON" {
            val nan = partInfo("n", 0)
            nan.bboxMin[0] = Double.NaN
            nan.centroid[1] = Double.POSITIVE_INFINITY
            val r = call(snap(scene = McpScene(partTable(listOf(nan)), null, 1)), PARTS)
            r.toString() shouldNotContain "NaN"
            r.toString() shouldNotContain "Infinity"
            Json.parseToJsonElement(r.toString())
            val p = r.sc().arr("parts")[0].jsonObject
            p.arr("size")[0] shouldBe JsonNull
            p.arr("centroid")[1] shouldBe JsonNull
        }

        "names with control and bidi characters come out cleaned" {
            val s = call(snap(scene = sceneOf("a‮b⁦c\u0007d\te")), PARTS).sc()
            val name = s.arr("parts")[0].jsonObject.str("name")
            name shouldBe NameSanitizer.cleanText("a‮b⁦c\u0007d\te")
            name.none { it == '‮' || it == '⁦' || it.code < 0x20 } shouldBe true
        }

        "a color that is not #rrggbb is null" {
            val odd = partInfo("n", 0, color = "red; drop")
            val r = call(snap(scene = McpScene(partTable(listOf(odd)), null, 1)), PARTS).sc()
            r.arr("parts")[0].jsonObject.getValue("color") shouldBe JsonNull
        }

        // ---- get_assembly_tree ----
        "get_assembly_tree nests groups and parts" {
            val tree =
                treeOf(
                    Triple("root", -1, -1),
                    Triple("grp", 0, -1),
                    Triple("a", 1, 0),
                    Triple("a", 1, 1),
                    Triple("c", 0, 2),
                )
            val scene = McpScene(partTable("a", "a", "c"), tree, 1)
            val s = call(snap(scene = scene), TREE).sc()
            val root = s.getValue("tree").jsonObject
            root.str("kind") shouldBe "group"
            root.num("partCount") shouldBe 3
            val grp = root.arr("children")[0].jsonObject
            grp.num("partCount") shouldBe 2
            val kids = grp.arr("children").map { it.jsonObject }
            kids.map { it.str("kind") } shouldBe listOf("part", "part")
            kids.map { it.num("partIndex") } shouldBe listOf(0, 1)
            kids.map { it.str("duplicateSuffix") } shouldBe listOf("#1", "#2")
            s.num("totalNodes") shouldBe 5
            s.num("emittedNodes") shouldBe 5
            s.getValue("truncated").jsonPrimitive.boolean shouldBe false
        }

        "a deep tree is read without recursion and can be limited by depth" {
            val nodes = ArrayList<Triple<String, Int, Int>>()
            for (i in 0 until 63) nodes += Triple("g$i", i - 1, -1)
            nodes += Triple("leaf", 62, 0)
            val tree = treeOf(*nodes.toTypedArray())
            val scene = McpScene(partTable("leaf"), tree, 1)
            val full = call(snap(scene = scene), TREE).sc()
            full.num("emittedNodes") shouldBe 64
            full.getValue("depthLimited").jsonPrimitive.boolean shouldBe false
            val cut = call(snap(scene = scene), TREE, """{"maxDepth":3}""").sc()
            cut.num("emittedNodes") shouldBe 3
            cut.getValue("depthLimited").jsonPrimitive.boolean shouldBe true
            val level3 =
                cut
                    .getValue("tree")
                    .jsonObject
                    .arr("children")[0]
                    .jsonObject
                    .arr("children")[0]
                    .jsonObject
            level3.num("childrenOmitted") shouldBe 1
            level3.arr("children").size shouldBe 0
        }

        "a tree with a leaf at the maximum parser depth is returned in full by default" {
            val nodes = ArrayList<Triple<String, Int, Int>>()
            for (i in 0 until 64) nodes += Triple("g$i", i - 1, -1)
            nodes += Triple("leaf", 63, 0)
            val scene = McpScene(partTable("leaf"), treeOf(*nodes.toTypedArray()), 1)
            val full = call(snap(scene = scene), TREE).sc()
            full.num("totalNodes") shouldBe 65
            full.num("emittedNodes") shouldBe 65
            full.getValue("depthLimited").jsonPrimitive.boolean shouldBe false
            call(snap(scene = scene), TREE, """{"maxDepth":65}""").sc().num("emittedNodes") shouldBe 65
        }

        "get_assembly_tree with maxNodes returns a valid prefix" {
            val nodes = mutableListOf(Triple("root", -1, -1))
            for (i in 0 until 20) nodes += Triple("p$i", 0, i)
            val scene = McpScene(partTable(*Array(20) { "p$it" }), treeOf(*nodes.toTypedArray()), 1)
            val s = call(snap(scene = scene), TREE, """{"maxNodes":10}""").sc()
            s.num("emittedNodes") shouldBe 10
            s.getValue("truncated").jsonPrimitive.boolean shouldBe true
            s
                .getValue("tree")
                .jsonObject
                .arr("children")
                .size shouldBe 9
        }

        "a huge tree stays below one MiB or says it was cut" {
            val n = 8191
            val nodes = mutableListOf(Triple("root", -1, -1))
            for (i in 0 until n) nodes += Triple("p$i" + "y".repeat(240), 0, i)
            val scene = McpScene(partTable(*Array(n) { "p$it" }), treeOf(*nodes.toTypedArray()), 1)
            val r = call(snap(scene = scene), TREE)
            (r.toString().toByteArray().size <= (1 shl 20)) shouldBe true
            r
                .sc()
                .getValue("truncated")
                .jsonPrimitive.boolean shouldBe true
        }

        "get_assembly_tree without a tree or a model says why" {
            call(snap(scene = scene3()), TREE).sc().also {
                it.getValue("tree") shouldBe JsonNull
                it.str("reason") shouldBe "noTree"
            }
            call(snap(), TREE).sc().str("reason") shouldBe "noModel"
        }

        // ---- get_selection ----
        "get_selection is null when nothing is selected, also for a hover alone" {
            call(snap(scene = scene3()), SELECTION).sc().getValue("selection") shouldBe JsonNull
            val hoverOnly = SelectionState(hovered = 1)
            call(
                snap(
                    scene = scene3(),
                    selection = McpViewInput.of(hoverOnly, VisibilityState(), false, false).selection,
                ),
                SELECTION,
            ).sc()
                .getValue("selection") shouldBe JsonNull
        }

        "get_selection of one part" {
            val sel = SelectionState(selected = "b")
            val s = call(snap(scene = scene3(), selection = sel), SELECTION).sc().getValue("selection").jsonObject
            s.str("kind") shouldBe "part"
            s.str("name") shouldBe "b"
            s.num("index") shouldBe 1
            s.arr("size").size shouldBe 3
        }

        "get_selection of several parts shows five names and counts the rest" {
            val scene = sceneOf(*Array(7) { "p$it" })
            val sel = SelectionState(selected = "p0", more = (1..6).map { PartRef("p$it", 0) })
            val s = call(snap(scene = scene, selection = sel), SELECTION).sc().getValue("selection").jsonObject
            s.str("kind") shouldBe "parts"
            s.num("partCount") shouldBe 7
            s.arr("names").size shouldBe 5
            s.num("moreNames") shouldBe 2
            s.getValue("triangles").jsonPrimitive.content shouldBe "84"
        }

        "get_selection of a group" {
            val tree =
                treeOf(
                    Triple("root", -1, -1),
                    Triple("grp", 0, -1),
                    Triple("a", 1, 0),
                    Triple("b", 1, 1),
                    Triple("c", 0, 2),
                )
            val scene = McpScene(partTable("a", "b", "c"), tree, 1)
            val sel = SelectionState(selectedGroup = tree.key(1))
            val s = call(snap(scene = scene, selection = sel), SELECTION).sc().getValue("selection").jsonObject
            s.str("kind") shouldBe "group"
            s.str("path") shouldBe "root / grp"
            s.num("partCount") shouldBe 2
            s.arr("center").size shouldBe 3
            s.arr("names").size shouldBe 2
        }

        "get_selection of something no longer in the model is null" {
            val sel = SelectionState(selected = "gone")
            call(snap(scene = scene3(), selection = sel), SELECTION).sc().getValue("selection") shouldBe JsonNull
            val tree = treeOf(Triple("root", -1, -1), Triple("a", 0, 0))
            val old = SelectionState(selectedGroup = "deadbeef")
            call(snap(scene = McpScene(partTable("a"), tree, 1), selection = old), SELECTION)
                .sc()
                .getValue("selection") shouldBe JsonNull
        }

        // ---- get_view ----
        "get_view counts hidden and transparent parts and reads settings" {
            val scene = scene3()
            val keys = VisibilityScene.of(scene.parts, null).partKey
            val vis =
                VisibilityState(
                    mapOf(
                        keys[0] to Visibility.Hidden,
                        keys[1] to Visibility.Hidden,
                        keys[2] to Visibility.Transparent,
                        "stale" to Visibility.Hidden,
                    ),
                    alphaStep = 7,
                )
            val s = call(snap(scene = scene, visibility = vis, settings = McpViewSettings(softWrap = true)), VIEW).sc()
            s.num("hiddenCount") shouldBe 2
            s.num("transparentCount") shouldBe 1
            s.num("opacityPercent") shouldBe 35
            s.num("partCount") shouldBe 3
            s.getValue("model").jsonPrimitive.boolean shouldBe true
            s
                .getValue("settings")
                .jsonObject
                .getValue("softWrap")
                .jsonPrimitive.boolean shouldBe true
            s
                .getValue("settings")
                .jsonObject
                .getValue("previewUnsaved")
                .jsonPrimitive.boolean shouldBe false
        }

        "get_view without a model" {
            val s = call(snap(origin = null), VIEW).sc()
            s.getValue("model").jsonPrimitive.boolean shouldBe false
            s.getValue("partCount") shouldBe JsonNull
            s.num("hiddenCount") shouldBe 0
        }

        // ---- find_in_script ----
        "find literal respects match case and whole word" {
            val text = "box(1)\nBox(2)\nboxes(3)\n"

            fun n(args: String) = call(snap(text = text), FIND, args).sc().arr("matches").size
            n("""{"query":"box"}""") shouldBe 3
            n("""{"query":"box","matchCase":true}""") shouldBe 2
            n("""{"query":"box","wholeWord":true}""") shouldBe 2
        }

        "find with a regex" {
            val s =
                call(
                    snap(text = "box(12)\nbox(x)\nbox(3, 4)\n"),
                    FIND,
                    """{"query":"box\\(\\d+","regex":true}""",
                ).sc()
            s.arr("matches").map { it.jsonObject.num("line") } shouldBe listOf(1, 3)
            s.arr("matches")[0].jsonObject.num("length") shouldBe 6
        }

        "find gives 1-based lines and UTF-16 columns, also behind a surrogate pair" {
            val text = "ä\n😀 box(1)\n"
            val s = call(snap(text = text), FIND, """{"query":"box"}""").sc()
            val m = s.arr("matches")[0].jsonObject
            m.num("line") shouldBe 2
            val start = text.indexOf("box")
            val (line, col) = CaretMapper.lineColumn(text, start)
            m.num("line") shouldBe line
            m.num("column") shouldBe col
            m.num("column") shouldBe 4
            m.str("text") shouldBe "😀 box(1)"
            m.num("contextStartColumn") shouldBe 1
        }

        "find clips the context to 200 units without splitting a surrogate pair" {
            val text = "😀".repeat(300) + "box" + "x".repeat(500)
            val m = call(snap(text = text), FIND, """{"query":"box"}""").sc().arr("matches")[0].jsonObject
            val ctx = m.str("text")
            (ctx.length <= 200) shouldBe true
            ctx.first().isLowSurrogate() shouldBe false
            ctx.last().isHighSurrogate() shouldBe false
            ctx.contains("box") shouldBe true
            val plain = "x".repeat(500) + "box" + "y".repeat(500)
            val p = call(snap(text = plain), FIND, """{"query":"box"}""").sc().arr("matches")[0].jsonObject
            p.str("text").length shouldBe 200
            p.num("contextStartColumn") shouldBe 441
        }

        "find stops at the limit and says so" {
            val s = call(snap(text = "a ".repeat(10)), FIND, """{"query":"a","limit":3}""").sc()
            s.arr("matches").size shouldBe 3
            s.getValue("truncated").jsonPrimitive.boolean shouldBe true
            s.str("stoppedBy") shouldBe "limit"
            val all = call(snap(text = "a ".repeat(3)), FIND, """{"query":"a","limit":3}""").sc()
            all.getValue("truncated").jsonPrimitive.boolean shouldBe false
            all.getValue("stoppedBy") shouldBe JsonNull
        }

        "find ends on regexes that explode in other engines" {
            val text = "a".repeat(1 shl 20)
            for (q in listOf("(a*)*b", "(a|a)*b", "(x+x+)+y")) {
                val t0 = System.nanoTime()
                val r = call(snap(text = text), FIND, """{"query":"$q","regex":true}""")
                ((System.nanoTime() - t0) / 1_000_000_000L < 20) shouldBe true
                if (r.isErr()) r.code() shouldBe "SEARCH_TIMEOUT" else Json.parseToJsonElement(r.toString())
            }
        }

        "find refuses invalid patterns with a fixed text that does not repeat the pattern" {
            for (q in listOf("(abc", "a(?=b)", "\\\\1")) {
                val r = call(snap(), FIND, """{"query":"$q","regex":true}""")
                r.isErr() shouldBe true
                r.code() shouldBe "INVALID_PATTERN"
                r.toString() shouldNotContain "abc"
                r.toString() shouldNotContain "(?=b)"
            }
        }

        "find matches Unicode without case" {
            val s = call(snap(text = "Straße ÄRGER 😀x"), FIND, """{"query":"ärger"}""").sc()
            s.arr("matches").size shouldBe 1
            call(snap(text = "a 😀 b"), FIND, """{"query":"😀"}""").sc().arr("matches").size shouldBe 1
        }

        "find refuses text above one MiB and a missing editor" {
            call(snap(text = "a".repeat((1 shl 20) + 1)), FIND, """{"query":"a"}""").code() shouldBe "TOO_LARGE"
            call(snap(kind = EditorKind.NONE, text = null), FIND, """{"query":"a"}""").code() shouldBe "NO_FILE"
        }

        "find in a note says so" {
            call(snap(kind = EditorKind.NOTE, text = "hello"), FIND, """{"query":"ell"}""").sc().str("kind") shouldBe
                "note"
        }

        "find that runs out of time is SEARCH_TIMEOUT, not an empty result" {
            val r = call(snap(text = "a".repeat(20_000)), FIND, """{"query":"b"}""", McpToolLimits(searchTimeoutMs = 0))
            r.isErr() shouldBe true
            r.code() shouldBe "SEARCH_TIMEOUT"
        }

        // ---- argument validation ----
        "invalid arguments are refused with a fixed code for every tool" {
            val bad =
                listOf(
                    FIND to """{"query":5}""",
                    FIND to """{"query":"a","regex":"true"}""",
                    FIND to """{"query":"a","limit":1.5}""",
                    FIND to """{"query":"a","limit":"3"}""",
                    FIND to """{"query":"a","limit":0}""",
                    FIND to """{"query":"a","limit":201}""",
                    FIND to """{"query":"a","limit":1e1}""",
                    FIND to """{"query":null}""",
                    FIND to """{"query":"a","extraField":1}""",
                    FIND to """{}""",
                    FIND to """{"query":""}""",
                    FIND to """{"query":"${"x".repeat(257)}"}""",
                    PARTS to """{"offset":-1}""",
                    PARTS to """{"limit":4097}""",
                    ERRORS to """{"limit":0}""",
                    TREE to """{"maxDepth":66}""",
                    VIEW to """{"unknown":true}""",
                    SELECTION to """{"x":1}""",
                    "kstep_desktop_get_status" to """{"x":1}""",
                    "kstep_desktop_get_script" to """{"x":1}""",
                    FIND to ("{" + (1..17).joinToString(",") { "\"k$it\":1" } + "}"),
                )
            for ((tool, args) in bad) {
                val r = call(snap(), tool, args)
                r.isErr() shouldBe true
                r.code() shouldBe "INVALID_ARGUMENTS"
            }
        }

        "a refused argument is not repeated in the answer" {
            val r = call(snap(), FIND, """{"query":"SECRETVALUE","secretField":"SECRETVALUE","limit":"SECRETVALUE"}""")
            r.toString() shouldNotContain "SECRETVALUE"
            r.toString() shouldNotContain "secretField"
        }

        "arguments of the right type work, with defaults" {
            val r =
                call(
                    snap(text = "abc"),
                    FIND,
                    """{"query":"b","regex":false,"wholeWord":false,"matchCase":true,"limit":5}""",
                )
            r.isErr() shouldBe false
            r.sc().arr("matches").size shouldBe 1
        }

        // ---- consistency and revision ----
        "every call reads the snapshot exactly once" {
            var reads = 0
            val host = FakeHost(snap(scene = scene3(), runReport = report(errorList(2))), onRead = { reads++ })
            val reg = McpToolRegistry(host)
            for (t in reg.all) {
                reads = 0
                val args = if (t.name == FIND) """{"query":"box"}""" else "{}"
                reg.call(t.name, Json.parseToJsonElement(args).jsonObject, V)
                reads shouldBe 1
            }
        }

        "snapshotRevision is in the answer of every tool" {
            val s = snap(scene = scene3(), revision = 42)
            val reg = McpToolRegistry(FakeHost(s))
            for (t in reg.all) {
                val args = if (t.name == FIND) """{"query":"box"}""" else "{}"
                val r = reg.call(t.name, Json.parseToJsonElement(args).jsonObject, V)
                r.sc().num("snapshotRevision") shouldBe 42
            }
        }

        "the old protocol has the body only as text" {
            val r = call(snap(scene = scene3()), PARTS, version = OLD)
            r.containsKey("structuredContent") shouldBe false
            Json.parseToJsonElement(r.arr("content")[0].jsonObject.str("text")).jsonObject.num("total") shouldBe 3
            JsonArray(emptyList()).size shouldBe 0
        }
    })
