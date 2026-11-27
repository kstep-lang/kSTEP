package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

private fun registry(
    host: FakeHost,
    limits: McpToolLimits = McpToolLimits(),
) = McpToolRegistry(host, limits)

private fun McpToolRegistry.get(
    name: String,
    version: String = McpProtocol.STRUCTURED_VERSION,
): JsonObject = call(name, null, version)

private fun JsonObject.structured() = getValue("structuredContent").jsonObject

private const val STATUS = "kstep_desktop_get_status"
private const val SCRIPT = "kstep_desktop_get_script"

class McpToolsTest :
    StringSpec({
        "every tool has a prefixed unique name, a description and an object schema; exactly fourteen" {
            val tools = registry(FakeHost()).all
            tools.size shouldBe 14
            tools.map { it.name }.toSet().size shouldBe 14
            tools.map { it.name } shouldContainExactly
                listOf(
                    "kstep_desktop_get_status",
                    "kstep_desktop_get_script",
                    "kstep_desktop_get_errors",
                    "kstep_desktop_get_parts",
                    "kstep_desktop_get_assembly_tree",
                    "kstep_desktop_get_selection",
                    "kstep_desktop_get_view",
                    "kstep_desktop_find_in_script",
                    "kstep_desktop_set_script",
                    "kstep_desktop_replace_in_script",
                    "kstep_desktop_run",
                    "kstep_desktop_select",
                    "kstep_desktop_set_visibility",
                    "kstep_desktop_show_in_script",
                )
            tools.forEach {
                it.name shouldStartWith "kstep_desktop_"
                it.description.isNotBlank() shouldBe true
                it.inputSchema
                    .getValue("type")
                    .jsonPrimitive.content shouldBe "object"
                it.inputSchema
                    .getValue("additionalProperties")
                    .jsonPrimitive.boolean shouldBe false
                val props = it.inputSchema.getValue("properties").jsonObject
                props.keys shouldBe
                    it.args.args
                        .map { a -> a.name }
                        .toSet()
                props.values.forEach { p ->
                    p.jsonObject.containsKey("type") shouldBe true
                    p.jsonObject.containsKey("description") shouldBe true
                }
                val required =
                    it.inputSchema["required"]?.jsonArray?.map { r -> r.jsonPrimitive.content } ?: emptyList()
                required shouldBe
                    it.args.args
                        .filter { a -> a.required }
                        .map { a -> a.name }
            }
            tools.first { it.name.endsWith("find_in_script") }.inputSchema.getValue("required").jsonArray.map {
                it.jsonPrimitive.content
            } shouldBe listOf("query")
        }

        "get_status reports a model with parts and triangles" {
            val s = registry(FakeHost(snap())).get(STATUS).structured()
            s.getValue("run").jsonPrimitive.content shouldBe "ok"
            s.getValue("parts").jsonPrimitive.int shouldBe 2
            s.getValue("triangles").jsonPrimitive.int shouldBe 24
            s.getValue("previewOrigin").jsonPrimitive.content shouldBe "savedFile"
            s.getValue("appVersion").jsonPrimitive.content shouldBe "9.9.9-test"
            s.getValue("protocolVersion").jsonPrimitive.content shouldBe "2025-06-18"
            s
                .getValue("file")
                .jsonObject
                .getValue("name")
                .jsonPrimitive.content shouldBe "part.kstep.kts"
            s.getValue("workspace") shouldBe JsonNull
            s.getValue("draftOfferOpen").jsonPrimitive.boolean shouldBe false
            s.getValue("snapshotRevision").jsonPrimitive.int shouldBe 7
        }

        "get_status covers every run state" {
            fun run(
                kind: RunKind,
                hint: String? = null,
            ) = registry(
                FakeHost(snap(run = kind, hint = hint, parts = null, triangles = null, origin = null)),
            ).get(STATUS).structured()
            run(RunKind.IDLE)["run"]?.jsonPrimitive?.content shouldBe "idle"
            run(RunKind.RUNNING)["run"]?.jsonPrimitive?.content shouldBe "running"
            run(RunKind.ERROR)["run"]?.jsonPrimitive?.content shouldBe "error"
            run(RunKind.UNAVAILABLE)["run"]?.jsonPrimitive?.content shouldBe "unavailable"
            val waiting = run(RunKind.IDLE, "Pick a block")
            waiting["waitingHint"]?.jsonPrimitive?.content shouldBe "Pick a block"
            waiting["parts"] shouldBe JsonNull
            waiting["triangles"] shouldBe JsonNull
            waiting["previewOrigin"] shouldBe JsonNull
        }

        "get_status reports a workspace, an unsaved buffer preview and an open draft offer" {
            val s =
                registry(
                    FakeHost(snap(knowledge = true, draft = true, origin = PreviewKind.UNSAVED_BUFFER, dirty = true)),
                ).get(STATUS).structured()
            s.getValue("workspace").jsonPrimitive.content shouldBe "knowledge"
            s.getValue("draftOfferOpen").jsonPrimitive.boolean shouldBe true
            s.getValue("previewOrigin").jsonPrimitive.content shouldBe "unsavedBuffer"
            s.getValue("dirty").jsonPrimitive.boolean shouldBe true
        }

        "get_status without a file has a null file" {
            val s = registry(FakeHost()).get(STATUS).structured()
            s.getValue("file") shouldBe JsonNull
        }

        "a path with spaces, quotes and Unicode comes back unchanged" {
            val odd = Path.of("/tmp/ein \"komisches\" Verzeichnis/Träger ✓.kstep.kts")
            val s = registry(FakeHost(snap(path = odd))).get(STATUS).structured()
            s
                .getValue("file")
                .jsonObject
                .getValue("path")
                .jsonPrimitive.content shouldBe odd.toString()
        }

        "get_script without a buffer is NO_FILE" {
            val r = registry(FakeHost()).get(SCRIPT)
            r.getValue("isError").jsonPrimitive.boolean shouldBe true
            r
                .structured()
                .getValue("code")
                .jsonPrimitive.content shouldBe "NO_FILE"
            val kindNone = registry(FakeHost(snap(kind = EditorKind.NONE, text = null))).get(SCRIPT)
            kindNone.getValue("isError").jsonPrimitive.boolean shouldBe true
            kindNone
                .getValue("content")
                .jsonArray[0]
                .jsonObject
                .getValue("text")
                .jsonPrimitive.content shouldStartWith
                "NO_FILE: "
        }

        "get_script of a note says so" {
            val s =
                registry(
                    FakeHost(snap(kind = EditorKind.NOTE, text = "# Note\n", knowledge = true)),
                ).get(SCRIPT).structured()
            s.getValue("kind").jsonPrimitive.content shouldBe "note"
            s.getValue("notice").jsonPrimitive.content shouldBe McpToolRegistry.NOTE_NOTICE
        }

        "get_script is cut at the byte limit without splitting a character and says so" {
            val limit = 16
            val text = "a".repeat(limit - 2) + "😀" + "tail" // the emoji (4 bytes) straddles the limit
            val s = registry(FakeHost(snap(text = text)), McpToolLimits(limit)).get(SCRIPT).structured()
            s.getValue("truncated").jsonPrimitive.boolean shouldBe true
            s.getValue("totalBytes").jsonPrimitive.int shouldBe text.toByteArray().size
            val shown = s.getValue("text").jsonPrimitive.content
            shown shouldBe "a".repeat(limit - 2)
            (shown.toByteArray().size <= limit) shouldBe true
        }

        "get_script of exactly the limit is complete" {
            val text = "x".repeat(16)
            val s = registry(FakeHost(snap(text = text)), McpToolLimits(16)).get(SCRIPT).structured()
            s.getValue("truncated").jsonPrimitive.boolean shouldBe false
            s.getValue("text").jsonPrimitive.content shouldBe text
        }

        "the revision is stable and changes with the text, and it covers the whole text even when cut" {
            fun rev(text: String) =
                registry(
                    FakeHost(snap(text = text)),
                    McpToolLimits(8),
                ).get(SCRIPT).structured().getValue("revision").jsonPrimitive.content
            rev("0123456789ABCDEF") shouldBe rev("0123456789ABCDEF")
            (rev("0123456789ABCDEF") == rev("0123456789ABCDEG")) shouldBe false
            rev("0123456789ABCDEF") shouldStartWith "sha256:"
            rev("").length shouldBe "sha256:".length + 64
        }

        "hostile text comes back identical" {
            val hostile = "a\"b\\c\u0000d</script>e\n\r\tf g 😀 \uD800 ü"
            val clean = hostile.replace("\uD800", "?") // a lone surrogate cannot be sent as UTF-8
            val text =
                registry(
                    FakeHost(snap(text = hostile)),
                ).get(SCRIPT).structured().getValue("text").jsonPrimitive.content
            text shouldBe hostile
            val viaTextContent =
                registry(FakeHost(snap(text = clean)))
                    .get(SCRIPT)
                    .getValue("content")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            kotlinx.serialization.json.Json
                .parseToJsonElement(
                    viaTextContent,
                ).jsonObject
                .getValue("text")
                .jsonPrimitive.content shouldBe
                clean
        }

        "lines counts the lines of the full text" {
            fun lines(t: String) =
                registry(FakeHost(snap(text = t)))
                    .get(SCRIPT)
                    .structured()
                    .getValue("lines")
                    .jsonPrimitive.int
            lines("") shouldBe 0
            lines("one") shouldBe 1
            lines("a\nb\nc") shouldBe 3
        }

        "a failing tool answers with a fixed INTERNAL text, never with the exception" {
            val host = FakeHost(snap(), onRead = { error("secret detail /Users/x") })
            val r = registry(host).get(STATUS)
            r.getValue("isError").jsonPrimitive.boolean shouldBe true
            val text =
                r
                    .getValue("content")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("text")
                    .jsonPrimitive.content
            text shouldBe "INTERNAL: The tool failed."
        }
    })
