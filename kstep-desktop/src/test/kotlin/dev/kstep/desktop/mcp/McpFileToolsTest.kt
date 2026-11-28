package dev.kstep.desktop.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun FakeFilePort.rev() = revisionOf(text)

private const val SAVE = "kstep_desktop_save"
private const val LIST = "kstep_desktop_list_recent"
private const val OPEN = "kstep_desktop_open_recent"
private const val EXPORT = "kstep_desktop_export"

private val acts = listOf("beginSave", "openRecent", "startExport")

class McpFileToolsTest :
    StringSpec({
        "the file switch is off by default: FILES_DISABLED, also before bad arguments are looked at" {
            withFileRig(files = false) { rig ->
                for ((tool, args) in listOf(SAVE to "{}", LIST to "{}", OPEN to "{}", EXPORT to """{"bogus":1}""")) {
                    val r = rig.call(tool, args)
                    r.isError shouldBe true
                    r.code shouldBe "FILES_DISABLED"
                }
                rig.port.log shouldBe emptyList()
            }
        }

        "ACTIONS_DISABLED comes before FILES_DISABLED and RATE_LIMITED before both" {
            withFileRig(actions = false, files = false) { rig ->
                rig.call(SAVE).code shouldBe "ACTIONS_DISABLED"
                repeat(25) { rig.call(SAVE, advance = false) }
                rig.call(SAVE, advance = false).code shouldBe "RATE_LIMITED"
            }
        }

        "save: deny and timeout never touch the file" {
            withFileRig { rig ->
                rig.denyAll()
                val r = rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""")
                r.code shouldBe "DENIED"
                rig.port.log.filter { it in acts } shouldBe emptyList()
                // a timeout: the fake scheduler fires the 60 s task
                val rig2Port = FakeFilePort()
                withFileRig(rig2Port) { rig2 ->
                    rig2.user = { rig2.scheduler.fire() }
                    rig2.call(SAVE, """{"expectedRevision":"${rig2Port.rev()}"}""").code shouldBe "CONFIRMATION_TIMEOUT"
                    rig2Port.log.filter { it in acts } shouldBe emptyList()
                }
            }
        }

        "save: allow saves, the dialog got the line count and the name" {
            withFileRig { rig ->
                rig.allowAll()
                val r = rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""")
                r.isError shouldBe false
                r.body
                    .getValue("saved")
                    .jsonPrimitive.boolean shouldBe true
                r.body
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "part.kstep.kts"
                rig.port.log shouldContain "beginSave"
                rig.seen.single().scope shouldBe "Save: +2/−1 lines vs. file on disk"
                rig.seen.single().action shouldBe McpFileAction.SAVE
                looksLikePath(r.body) shouldBe false
            }
        }

        "save: a change between the question and the answer is STALE_REVISION and nothing is saved" {
            withFileRig { rig ->
                val rev = rig.port.rev()
                rig.user = {
                    rig.port.text = "box(2)\n"
                    rig.gate.resolve(it.id, McpConfirmDecision.ALLOW)
                }
                rig.call(SAVE, """{"expectedRevision":"$rev"}""").code shouldBe "STALE_REVISION"
                rig.port.log shouldNotContain "beginSave"
            }
        }

        "save: a wrong revision, no file, a note, a draft offer; no changes means no dialog" {
            withFileRig { rig ->
                rig.allowAll()
                rig.call(SAVE, """{"expectedRevision":"sha256:${"0".repeat(64)}"}""").code shouldBe "STALE_REVISION"
                rig.port.kind = EditorKind.NONE
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "NO_FILE"
                rig.port.kind = EditorKind.NOTE
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "NOT_A_SCRIPT"
                rig.port.kind = EditorKind.SCRIPT
                rig.port.draft = true
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "DRAFT_OFFER_OPEN"
                rig.port.draft = false
                rig.port.dirty = false
                val r = rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""")
                r.body
                    .getValue("saved")
                    .jsonPrimitive.boolean shouldBe false
                rig.seen.size shouldBe 0
            }
        }

        "save: a conflict is SAVE_FAILED, a long save is SAVE_FAILED with a reason" {
            withFileRig { rig ->
                rig.allowAll()
                rig.port.saveResult = McpSaveResult.Failed
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "SAVE_FAILED"
                rig.port.saveResult = McpSaveResult.StillSaving
                val r = rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""")
                r.code shouldBe "SAVE_FAILED"
                r.body
                    .getValue("reason")
                    .jsonPrimitive.content shouldBe "still saving"
            }
        }

        "a second request while a dialog is open is CONFIRMATION_PENDING" {
            withFileRig { rig ->
                val first =
                    Thread { rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""") }.apply {
                        isDaemon =
                            true
                    }
                first.start()
                val end = System.nanoTime() + 30_000_000_000L
                while (rig.gate.pending.value == null && System.nanoTime() < end) Thread.sleep(2)
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "CONFIRMATION_PENDING"
                rig.gate.resolve(
                    rig.gate.pending.value!!
                        .id,
                    McpConfirmDecision.DENY,
                )
                first.join(30_000)
            }
        }

        "switching the file permission off while a dialog is open ends it with FILES_DISABLED, not DENIED" {
            withFileRig { rig ->
                rig.user = {
                    rig.allowFiles = false
                    rig.gate.cancelAll(McpToolCodes.FILES_DISABLED)
                }
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "FILES_DISABLED"
                rig.port.log shouldNotContain "beginSave"
            }
        }

        "an answer of Allow that arrives after the switch was turned off still does nothing" {
            withFileRig { rig ->
                rig.user = {
                    rig.allowFiles = false
                    rig.gate.resolve(it.id, McpConfirmDecision.ALLOW)
                }
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "FILES_DISABLED"
                rig.port.log shouldNotContain "beginSave"
            }
        }

        "a denial starts a cool-down: the next request is RATE_LIMITED with retryAfterMs" {
            withFileRig { rig ->
                rig.denyAll()
                rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""").code shouldBe "DENIED"
                val r = rig.call(SAVE, """{"expectedRevision":"${rig.port.rev()}"}""", advance = false)
                r.code shouldBe "RATE_LIMITED"
                (
                    r.body
                        .getValue("retryAfterMs")
                        .jsonPrimitive.int > 0
                ) shouldBe true
            }
        }

        "list_recent gives names and indexes, never a path, without a question" {
            withFileRig { rig ->
                val r = rig.call(LIST)
                val scripts =
                    r.body
                        .getValue("scripts")
                        .jsonArray
                        .map { it.jsonObject }
                scripts.map { it.getValue("name").jsonPrimitive.content } shouldBe listOf("a.kstep.kts", "b.kstep.kts")
                scripts.map { it.getValue("index").jsonPrimitive.int } shouldBe listOf(0, 1)
                looksLikePath(r.body) shouldBe false
                rig.seen.size shouldBe 0
            }
        }

        "open_recent: needs exactly one of index and name, and a good one" {
            withFileRig { rig ->
                rig.allowAll()
                rig.port.dirty = false
                for (args in listOf(
                    "{}",
                    """{"index":0,"name":"a.kstep.kts"}""",
                    """{"index":-1}""",
                    """{"index":10}""",
                    """{"name":"../a.kstep.kts"}""",
                    """{"name":"x/a"}""",
                    """{"name":"x\\a"}""",
                    """{"name":"a..b"}""",
                    """{"name":"nope.kstep.kts"}""",
                    """{"index":5}""",
                )) {
                    rig.call(OPEN, args).code shouldBe "INVALID_ARGUMENTS"
                }
                rig.port.log shouldNotContain "openRecent"
                val dup = FakeFilePort()
                dup.dirty = false
                dup.recents = listOf(McpRecentScript(0, "x.kts", "/a"), McpRecentScript(1, "x.kts", "/b"))
                withFileRig(dup) { r2 ->
                    r2.allowAll()
                    r2.call(OPEN, """{"name":"x.kts"}""").code shouldBe "INVALID_ARGUMENTS"
                }
            }
        }

        "open_recent: unsaved changes are refused before and after the dialog" {
            withFileRig { rig ->
                rig.allowAll()
                rig.call(OPEN, """{"index":0}""").code shouldBe "UNSAVED_CHANGES"
                rig.seen.size shouldBe 0
                rig.port.dirty = false
                rig.user = {
                    rig.port.dirty = true
                    rig.gate.resolve(it.id, McpConfirmDecision.ALLOW)
                }
                rig.call(OPEN, """{"index":0}""").code shouldBe "UNSAVED_CHANGES"
                rig.port.log shouldNotContain "openRecent"
            }
        }

        "open_recent: a folder of notes asks to close it first, a vanished file is NOT_FOUND" {
            withFileRig { rig ->
                rig.allowAll()
                rig.port.dirty = false
                rig.port.workspace = true
                val r = rig.call(OPEN, """{"index":0}""")
                r.code shouldBe "NOT_A_SCRIPT"
                r.body
                    .getValue("reason")
                    .jsonPrimitive.content shouldBe "close the folder first"
                rig.port.workspace = false
                rig.port.recentValid = false
                rig.call(OPEN, """{"index":0}""").code shouldBe "NOT_FOUND"
                rig.seen.size shouldBe 0
            }
        }

        "open_recent: allow opens by index and by name; deny does not" {
            withFileRig { rig ->
                rig.port.dirty = false
                rig.allowAll()
                rig
                    .call(OPEN, """{"index":1}""")
                    .body
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "b.kstep.kts"
                rig
                    .call(OPEN, """{"name":"a.kstep.kts"}""")
                    .body
                    .getValue("opened")
                    .jsonPrimitive.boolean shouldBe true
                rig.seen.map { it.fileName } shouldBe listOf("b.kstep.kts", "a.kstep.kts")
                rig.denyAll()
                val before = rig.port.log.count { it == "openRecent" }
                rig.call(OPEN, """{"index":0}""").code shouldBe "DENIED"
                rig.port.log.count { it == "openRecent" } shouldBe before
            }
        }

        "export: the plan decides first, then the dialog, then the start" {
            withFileRig { rig ->
                rig.port.dirty = false
                rig.allowAll()
                val r = rig.call(EXPORT, """{"format":"step","expectedRevision":"${rig.port.rev()}"}""")
                r.isError shouldBe false
                r.body
                    .getValue("exported")
                    .jsonPrimitive.boolean shouldBe true
                r.body
                    .getValue("name")
                    .jsonPrimitive.content shouldBe "part.step"
                looksLikePath(r.body) shouldBe false
                rig.port.log.filter { it in listOf("planExport", "startExport", "awaitExport") } shouldBe
                    listOf("planExport", "startExport", "awaitExport")
                rig.seen.single().action shouldBe McpFileAction.EXPORT
            }
        }

        "export: unsaved changes, a bad name, no folder, all names taken, deny, stale, busy window" {
            withFileRig { rig ->
                rig.allowAll()
                val args = { """{"format":"stl","expectedRevision":"${rig.port.rev()}"}""" }
                rig.call(EXPORT, args()).code shouldBe "UNSAVED_CHANGES"
                rig.port.dirty = false
                rig
                    .call(
                        EXPORT,
                        """{"format":"stl","name":"../x","expectedRevision":"${rig.port.rev()}"}""",
                    ).code shouldBe
                    "INVALID_NAME"
                rig.port.plan = McpExportPlan.NoFolder
                val none = rig.call(EXPORT, args())
                none.code shouldBe "NOT_AVAILABLE"
                none.body
                    .getValue("reason")
                    .jsonPrimitive.content shouldBe "export once by hand to choose a folder"
                rig.port.plan = McpExportPlan.Exists
                rig.call(EXPORT, args()).code shouldBe "EXISTS"
                rig.seen.size shouldBe 0
                rig.port.plan = McpExportPlan.Ready("part.stl", "/home/u/exports", Any())
                rig.port.startResult = McpExportStart.UiBusy
                rig.call(EXPORT, args()).code shouldBe "UI_BUSY"
                rig.port.startResult = null
                rig.denyAll()
                rig.call(EXPORT, args()).code shouldBe "DENIED"
                rig.port.log shouldNotContain "awaitExport"
            }
        }

        "export: a change between the question and the answer is STALE_REVISION and nothing starts" {
            withFileRig { rig ->
                rig.port.dirty = false
                rig.user = {
                    rig.port.text = "box(9)\n"
                    rig.gate.resolve(it.id, McpConfirmDecision.ALLOW)
                }
                rig.call(EXPORT, """{"format":"glb","expectedRevision":"${revisionOf("box(1)\n")}"}""").code shouldBe
                    "STALE_REVISION"
                rig.port.log shouldNotContain "startExport"
            }
        }

        "export: a long run answers running, a worker failure only gives a code" {
            withFileRig { rig ->
                rig.port.dirty = false
                rig.allowAll()
                val args = { """{"format":"3mf","expectedRevision":"${rig.port.rev()}"}""" }
                rig.port.exportResult = McpExportResult.Running("part.3mf")
                val r = rig.call(EXPORT, args())
                r.body
                    .getValue("running")
                    .jsonPrimitive.boolean shouldBe true
                r.body
                    .getValue("exported")
                    .jsonPrimitive.boolean shouldBe false
                rig.port.exportResult = McpExportResult.Failed("KSTEP-D-022")
                val f = rig.call(EXPORT, args())
                f.code shouldBe "EXPORT_FAILED"
                f.body
                    .getValue("reason")
                    .jsonPrimitive.content shouldBe "KSTEP-D-022"
                rig.port.exportResult = McpExportResult.Exists
                rig.call(EXPORT, args()).code shouldBe "EXISTS"
            }
        }

        "the screenshot tool is reserved: NOT_AVAILABLE at once, not listed, no port touched" {
            withFileRig(files = false, actions = false) { rig ->
                val r = rig.call("kstep_desktop_screenshot")
                r.code shouldBe "NOT_AVAILABLE"
                r.body
                    .getValue("reason")
                    .jsonPrimitive.content shouldBe "viewer capture is not implemented"
                rig.port.log shouldBe emptyList()
                val names =
                    rig.registry
                        .list()
                        .getValue(
                            "tools",
                        ).jsonArray
                        .map {
                            it.jsonObject
                                .getValue("name")
                                .jsonPrimitive.content
                        }
                names shouldNotContain "kstep_desktop_screenshot"
                names shouldContain SAVE
                names shouldContain EXPORT
            }
        }
    })
