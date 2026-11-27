package dev.kstep.desktop.mcp

import dev.kstep.desktop.pick.PartRef
import dev.kstep.desktop.pick.SelectionState
import dev.kstep.desktop.pick.Visibility
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

private val ACTING_CALLS =
    listOf(
        "kstep_desktop_set_script" to """{"text":"x","expectedRevision":"${"sha256:" + "0".repeat(64)}"}""",
        "kstep_desktop_replace_in_script" to
            """{"oldText":"a","newText":"b","expectedRevision":"${"sha256:" + "0".repeat(64)}"}""",
        "kstep_desktop_run" to "{}",
        "kstep_desktop_select" to """{"mode":"clear"}""",
        "kstep_desktop_set_visibility" to """{"action":"show_all"}""",
        "kstep_desktop_show_in_script" to """{"part":"a"}""",
    )

class McpActionToolsTest :
    StringSpec({
        val original = "box(1, 1, 1)\nbox(2, 2, 2)\n"

        fun rev(t: String) = revisionOf(t)

        "by default every action tool answers ACTIONS_DISABLED and never touches the port" {
            withRig(FakePort(text = original), allow = false) { rig ->
                for ((name, args) in ACTING_CALLS) {
                    val r = rig.call(name, args)
                    r.isError shouldBe true
                    r.code shouldBe "ACTIONS_DISABLED"
                }
                rig.port.log shouldBe emptyList()
                rig.port.text shouldBe original
            }
        }

        "the permission is checked before the arguments: invalid arguments under the lock are ACTIONS_DISABLED" {
            withRig(allow = false) { rig ->
                rig.call("kstep_desktop_set_script", """{"bogus":1}""").code shouldBe "ACTIONS_DISABLED"
                rig.call("kstep_desktop_run", """{"waitMs":99999}""").code shouldBe "ACTIONS_DISABLED"
            }
        }

        "the rate limit comes before the lock: the 21st call within a second is RATE_LIMITED" {
            withRig(allow = false) { rig ->
                repeat(20) {
                    rig.call("kstep_desktop_select", """{"mode":"clear"}""", advance = false).code shouldBe
                        "ACTIONS_DISABLED"
                }
                rig.call("kstep_desktop_select", """{"mode":"clear"}""", advance = false).code shouldBe "RATE_LIMITED"
                rig.now += 1001
                rig.call("kstep_desktop_select", """{"mode":"clear"}""", advance = false).code shouldBe
                    "ACTIONS_DISABLED"
            }
        }

        "invalid arguments are INVALID_ARGUMENTS" {
            withRig(FakePort(text = original)) { rig ->
                val ok = rev(original)
                listOf(
                    "kstep_desktop_set_script" to """{"text":"x","expectedRevision":"$ok","extra":1}""",
                    "kstep_desktop_set_script" to """{"text":5,"expectedRevision":"$ok"}""",
                    "kstep_desktop_set_script" to """{"text":"x","expectedRevision":"sha256:ABC"}""",
                    "kstep_desktop_set_script" to """{"text":"x","expectedRevision":"sha256:${"A".repeat(64)}"}""",
                    "kstep_desktop_set_script" to """{"text":"x","expectedRevision":"md5:${"a".repeat(64)}"}""",
                    "kstep_desktop_set_script" to """{"text":"x"}""",
                    "kstep_desktop_run" to """{"mode":"both"}""",
                    "kstep_desktop_run" to """{"waitMs":30001}""",
                    "kstep_desktop_run" to """{"waitMs":-1}""",
                    "kstep_desktop_select" to """{"mode":"clear","names":["a"]}""",
                    "kstep_desktop_select" to """{"mode":"clear","occurrence":0}""",
                    "kstep_desktop_select" to """{"mode":"replace"}""",
                    "kstep_desktop_select" to """{"mode":"replace","names":[]}""",
                    "kstep_desktop_select" to """{"mode":"replace","names":[1]}""",
                    "kstep_desktop_select" to """{"mode":"replace","names":["a"],"occurrence":10000}""",
                    "kstep_desktop_select" to
                        """{"mode":"replace","names":[${(1..101).joinToString(",") { "\"n$it\"" }}]}""",
                    "kstep_desktop_select" to """{"mode":"replace","names":["${"x".repeat(257)}"]}""",
                    "kstep_desktop_select" to """{"mode":"bogus","names":["a"]}""",
                    "kstep_desktop_set_visibility" to """{"action":"show_all","parts":["a"]}""",
                    "kstep_desktop_set_visibility" to """{"action":"explode"}""",
                    "kstep_desktop_show_in_script" to """{"part":""}""",
                ).forEach { (name, args) ->
                    val r = rig.call(name, args)
                    r.isError shouldBe true
                    r.code shouldBe "INVALID_ARGUMENTS"
                }
                rig.port.log shouldBe emptyList()
            }
        }

        "set_script replaces the text, reports the new revision and an agent notice, and does not save" {
            withRig(FakePort(text = original)) { rig ->
                val next = "box(3, 3, 3)\n"
                val r =
                    rig.call(
                        "kstep_desktop_set_script",
                        """{"text":${jsonStr(next)},"expectedRevision":"${rev(original)}"}""",
                    )
                r.isError shouldBe false
                r.body
                    .getValue("changed")
                    .jsonPrimitive.content shouldBe "true"
                r.body
                    .getValue("revision")
                    .jsonPrimitive.content shouldBe rev(next)
                r.body
                    .getValue("dirty")
                    .jsonPrimitive.content shouldBe "true"
                r.body
                    .getValue("saved")
                    .jsonPrimitive.content shouldBe "false"
                rig.port.text shouldBe next
                rig.port.notices shouldContainExactly listOf(AgentNoticeKind.EDITED)
            }
        }

        "set_script normalises line endings and the revision is that of the normalised text" {
            withRig(FakePort(text = original)) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_set_script",
                        """{"text":"a\r\nb\rc","expectedRevision":"${rev(original)}"}""",
                    )
                rig.port.text shouldBe "a\nb\nc"
                r.body
                    .getValue("revision")
                    .jsonPrimitive.content shouldBe rev("a\nb\nc")
            }
        }

        "set_script with an identical text is changed:false without an edit and without a notice" {
            withRig(FakePort(text = original)) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_set_script",
                        """{"text":${jsonStr(original)},"expectedRevision":"${rev(original)}"}""",
                    )
                r.isError shouldBe false
                r.body
                    .getValue("changed")
                    .jsonPrimitive.content shouldBe "false"
                rig.port.log.contains("applyAgentEdit") shouldBe false
                rig.port.notices shouldBe emptyList()
            }
        }

        "a stale revision is refused and names the current one" {
            withRig(FakePort(text = original)) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev("something else")}"}""",
                    )
                r.code shouldBe "STALE_REVISION"
                r.body
                    .getValue("revision")
                    .jsonPrimitive.content shouldBe rev(original)
                rig.port.text shouldBe original
            }
        }

        "the user typing between capture and apply gives STALE_REVISION and nothing changes" {
            val port = FakePort(text = original)
            withRig(port) { rig ->
                port.beforeEdit = { port.text = original + "typed" }
                val r = rig.call("kstep_desktop_set_script", """{"text":"x","expectedRevision":"${rev(original)}"}""")
                r.code shouldBe "STALE_REVISION"
                port.text shouldBe original + "typed"
                port.notices shouldBe emptyList()
            }
        }

        "no file, a note and an open draft offer are refused" {
            withRig(FakePort(kind = EditorKind.NONE, text = null)) { rig ->
                rig.call("kstep_desktop_set_script", """{"text":"x","expectedRevision":"${rev("")}"}""").code shouldBe
                    "NO_FILE"
            }
            withRig(FakePort(kind = EditorKind.NOTE, text = "# note")) { rig ->
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev("# note")}"}""",
                    ).code shouldBe
                    "NOT_A_SCRIPT"
                rig
                    .call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"n","newText":"x","expectedRevision":"${rev("# note")}"}""",
                    ).code shouldBe "NOT_A_SCRIPT"
            }
            withRig(FakePort(text = original, draftOffer = true)) { rig ->
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe
                    "DRAFT_OFFER_OPEN"
                rig
                    .call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"box","newText":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe "DRAFT_OFFER_OPEN"
                rig.port.gate = McpRunGate.DRAFT_OFFER_OPEN
                rig.call("kstep_desktop_run", """{"mode":"unsaved"}""").code shouldBe "DRAFT_OFFER_OPEN"
            }
        }

        "text with forbidden characters is INVALID_TEXT and text over 1 MiB is TEXT_TOO_LARGE" {
            withRig(FakePort(text = original)) { rig ->
                val ok = rev(original)
                rig.call("kstep_desktop_set_script", """{"text":"a‮b","expectedRevision":"$ok"}""").code shouldBe
                    "INVALID_TEXT"
                rig.call("kstep_desktop_set_script", """{"text":"a\u0000b","expectedRevision":"$ok"}""").code shouldBe
                    "INVALID_TEXT"
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"${("é".repeat(511) + "a\\n").repeat(1025)}","expectedRevision":"$ok"}""",
                    ).code shouldBe
                    "TEXT_TOO_LARGE"
                rig.port.text shouldBe original
            }
        }

        "replace_in_script replaces exactly one place" {
            withRig(FakePort(text = original)) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"box(2, 2, 2)","newText":"sphere(2)","expectedRevision":"${rev(original)}"}""",
                    )
                r.isError shouldBe false
                rig.port.text shouldBe "box(1, 1, 1)\nsphere(2)\n"
                r.body
                    .getValue("revision")
                    .jsonPrimitive.content shouldBe rev("box(1, 1, 1)\nsphere(2)\n")
            }
        }

        "replace_in_script is NOT_FOUND for no match and AMBIGUOUS with the count for several" {
            withRig(FakePort(text = original)) { rig ->
                rig
                    .call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"cone","newText":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe "NOT_FOUND"
                val r =
                    rig.call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"box","newText":"x","expectedRevision":"${rev(original)}"}""",
                    )
                r.code shouldBe "AMBIGUOUS"
                r.body
                    .getValue("count")
                    .jsonPrimitive.int shouldBe 2
                rig.port.text shouldBe original
            }
        }

        "replace_in_script with a result over 1 MiB is TEXT_TOO_LARGE" {
            withRig(FakePort(text = "X\nb\n")) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"b","newText":"${(
                            "c".repeat(
                                1023,
                            ) + "\\n"
                        ).repeat(1024)}","expectedRevision":"${rev("X\nb\n")}"}""",
                    )
                r.code shouldBe "TEXT_TOO_LARGE"
            }
        }

        "replace_in_script with a carriage return in oldText finds the line-feed text" {
            withRig(FakePort(text = "a\nb\n")) { rig ->
                val r =
                    rig.call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"a\r\nb","newText":"z","expectedRevision":"${rev("a\nb\n")}"}""",
                    )
                r.isError shouldBe false
                rig.port.text shouldBe "z\n"
            }
        }

        "without a window every action answers UI_BUSY" {
            withRig(FakePort(text = original)) { rig ->
                rig.actionHost.port = null
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe
                    "UI_BUSY"
                rig.call("kstep_desktop_run", "{}").code shouldBe "UI_BUSY"
            }
        }

        "switching the permission off while a call is in the queue wins: nothing is applied" {
            val port = FakePort(text = original)
            withRig(port) { rig ->
                port.onEditor = { rig.allow = false }
                val r = rig.call("kstep_desktop_set_script", """{"text":"x","expectedRevision":"${rev(original)}"}""")
                r.code shouldBe "ACTIONS_DISABLED"
                port.log.contains("applyAgentEdit") shouldBe false
                port.text shouldBe original
            }
        }

        "run starts by default on the buffer when it is dirty and on the file when it is clean" {
            withRig(FakePort(text = original, dirty = true)) { rig ->
                val r = rig.call("kstep_desktop_run")
                r.body
                    .getValue("status")
                    .jsonPrimitive.content shouldBe "started"
                r.body
                    .getValue("mode")
                    .jsonPrimitive.content shouldBe "unsaved"
                rig.port.lastRunMode shouldBe McpRunMode.UNSAVED
                rig.port.notices shouldContainExactly listOf(AgentNoticeKind.RUN_STARTED)
            }
            withRig(FakePort(text = original, dirty = false)) { rig ->
                rig
                    .call("kstep_desktop_run")
                    .body
                    .getValue("mode")
                    .jsonPrimitive.content shouldBe "saved"
                rig
                    .call(
                        "kstep_desktop_run",
                        """{"mode":"unsaved"}""",
                    ).body
                    .getValue("mode")
                    .jsonPrimitive.content shouldBe
                    "unsaved"
            }
        }

        "run maps the gate to codes and does not start" {
            for (
            (gate, code) in
            listOf(
                McpRunGate.NO_FILE to "NO_FILE",
                McpRunGate.NOT_A_SCRIPT to "NOT_A_SCRIPT",
                McpRunGate.DRAFT_OFFER_OPEN to "DRAFT_OFFER_OPEN",
                McpRunGate.UNAVAILABLE to "NO_FILE",
            )
            ) {
                val port = FakePort(text = original).also { it.gate = gate }
                withRig(port) { rig ->
                    rig.call("kstep_desktop_run").code shouldBe code
                    port.log.contains("startRun") shouldBe false
                }
            }
        }

        "run without an active sandbox is SANDBOX_UNAVAILABLE and does not start" {
            val port = FakePort(text = original).also { it.sandboxed = false }
            withRig(port) { rig ->
                rig.call("kstep_desktop_run").code shouldBe "SANDBOX_UNAVAILABLE"
                rig.call("kstep_desktop_run", """{"mode":"saved"}""").code shouldBe "SANDBOX_UNAVAILABLE"
                port.log.contains("startRun") shouldBe false
            }
        }

        "an edit that would start an automatic run without a sandbox is refused and changes nothing" {
            val port =
                FakePort(text = original).also {
                    it.sandboxed = false
                    it.autoPreview = true
                }
            withRig(port) { rig ->
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe "SANDBOX_UNAVAILABLE"
                rig
                    .call(
                        "kstep_desktop_replace_in_script",
                        """{"oldText":"box(1, 1, 1)","newText":"x","expectedRevision":"${rev(original)}"}""",
                    ).code shouldBe "SANDBOX_UNAVAILABLE"
                port.log.contains("applyAgentEdit") shouldBe false
                port.text shouldBe original
            }
        }

        "an edit without a sandbox is allowed while Preview Unsaved Changes is off" {
            val port = FakePort(text = original).also { it.sandboxed = false }
            withRig(port) { rig ->
                rig
                    .call(
                        "kstep_desktop_set_script",
                        """{"text":"x","expectedRevision":"${rev(original)}"}""",
                    ).isError shouldBe false
                port.text shouldBe "x"
            }
        }

        "run twice within a second is RATE_LIMITED with retryAfterMs" {
            withRig(FakePort(text = original)) { rig ->
                rig.call("kstep_desktop_run").isError shouldBe false
                rig.now += 400
                val r = rig.call("kstep_desktop_run", advance = false)
                r.code shouldBe "RATE_LIMITED"
                r.body
                    .getValue("retryAfterMs")
                    .jsonPrimitive.int shouldBe 600
                rig.now += 600
                rig.call("kstep_desktop_run", advance = false).isError shouldBe false
            }
        }

        "run with waitMs reports finished, failed or timeout" {
            withRig(FakePort(text = original)) { rig ->
                rig.port.outcome = McpRunOutcome.Finished
                rig
                    .call(
                        "kstep_desktop_run",
                        """{"waitMs":100}""",
                    ).body
                    .getValue("status")
                    .jsonPrimitive.content shouldBe
                    "finished"
                rig.port.outcome = McpRunOutcome.Failed(3)
                val failed = rig.call("kstep_desktop_run", """{"waitMs":100}""")
                failed.body
                    .getValue("status")
                    .jsonPrimitive.content shouldBe "failed"
                failed.body
                    .getValue("errorCount")
                    .jsonPrimitive.int shouldBe 3
                rig.port.outcome = null
                rig
                    .call(
                        "kstep_desktop_run",
                        """{"waitMs":100}""",
                    ).body
                    .getValue("status")
                    .jsonPrimitive.content shouldBe
                    "timeout"
            }
        }

        "a second call that waits for a run is refused while one is waiting" {
            withRig(FakePort(text = original)) { rig ->
                val ctx = McpActionContext(rig.actionHost, { true }, { rig.now })
                ctx.waiter.tryEnter() shouldBe true
                ctx.waiter.tryEnter() shouldBe false
                ctx.waiter.leave()
                ctx.waiter.tryEnter() shouldBe true
            }
        }

        "select works against the real selection machine" {
            val port = FakePort()
            port.sceneOf("a", "b", "a", "c")
            withRig(port) { rig ->
                rig
                    .call(
                        "kstep_desktop_select",
                        """{"mode":"replace","names":["a"]}""",
                    ).body
                    .getValue("selected")
                    .jsonPrimitive.int shouldBe
                    2
                port.selectionState.parts shouldContainExactly listOf(PartRef("a", 0), PartRef("a", 1))
                rig.call("kstep_desktop_select", """{"mode":"replace","names":["a"],"occurrence":1}""")
                port.selectionState.parts shouldContainExactly listOf(PartRef("a", 1))
                rig
                    .call(
                        "kstep_desktop_select",
                        """{"mode":"add","names":["b","c"]}""",
                    ).body
                    .getValue("selected")
                    .jsonPrimitive.int shouldBe
                    3
                port.selectionState.parts.map { it.name } shouldContainExactly listOf("a", "b", "c")
                rig.call("kstep_desktop_select", """{"mode":"toggle","names":["b"]}""")
                port.selectionState.parts.map { it.name } shouldContainExactly listOf("a", "c")
                rig.call("kstep_desktop_select", """{"mode":"toggle","names":["b"]}""")
                port.selectionState.parts.map { it.name } shouldContainExactly listOf("a", "c", "b")
                rig.call("kstep_desktop_select", """{"mode":"clear"}""")
                port.selectionState shouldBe SelectionState()
            }
        }

        "parallel run calls start only one run" {
            withRig(FakePort(text = original)) { rig ->
                rig.now += 2000
                val n = 4
                val barrier = java.util.concurrent.CyclicBarrier(n)
                val pool =
                    java.util.concurrent.Executors
                        .newFixedThreadPool(n)
                try {
                    val results =
                        (1..n)
                            .map {
                                pool.submit<JsonObject> {
                                    barrier.await()
                                    rig.call("kstep_desktop_run", advance = false)
                                }
                            }.map { it.get() }
                    results.count { !it.isError } shouldBe 1
                    results.filter { it.isError }.all { it.code == "RATE_LIMITED" } shouldBe true
                    rig.port.log.count { it == "startRun" } shouldBe 1
                } finally {
                    pool.shutdownNow()
                }
            }
        }

        "select ignores duplicate names" {
            val port = FakePort()
            port.sceneOf("a", "b")
            withRig(port) { rig ->
                val r = rig.call("kstep_desktop_select", """{"mode":"toggle","names":["a","a"]}""")
                r.body
                    .getValue("selected")
                    .jsonPrimitive.int shouldBe 1
                r.body.containsKey("truncated") shouldBe false
                port.selectionState.parts shouldContainExactly listOf(PartRef("a", 0))
                val r2 = rig.call("kstep_desktop_select", """{"mode":"replace","names":["b","b"]}""")
                r2.body
                    .getValue("selected")
                    .jsonPrimitive.int shouldBe 1
                r2.body.containsKey("truncated") shouldBe false
            }
        }

        "select with an unknown name changes nothing and lists the missing names, masked" {
            val port = FakePort()
            port.sceneOf("a", "b")
            port.selectionState = SelectionState(selected = "a")
            withRig(port) { rig ->
                val r = rig.call("kstep_desktop_select", """{"mode":"replace","names":["b","\u001b[31mzz‮"]}""")
                r.code shouldBe "NOT_FOUND"
                port.selectionState shouldBe SelectionState(selected = "a")
                val missing = r.body.getValue("missing").jsonArray
                missing.size shouldBe 1
                missing[0].jsonPrimitive.content shouldNotContain "\u001b"
                missing[0].jsonPrimitive.content shouldNotContain "‮"
                r.body
                    .getValue("missingTotal")
                    .jsonPrimitive.int shouldBe 1
                port.log.contains("setSelection") shouldBe false
            }
        }

        "select without a model is NOT_FOUND" {
            withRig(FakePort()) { rig ->
                val r = rig.call("kstep_desktop_select", """{"mode":"replace","names":["a"]}""")
                r.code shouldBe "NOT_FOUND"
            }
        }

        "select cuts at 1000 parts and says so" {
            val port = FakePort()
            val names = Array(1200) { "p" }
            port.sceneOf(*names)
            withRig(port) { rig ->
                val r = rig.call("kstep_desktop_select", """{"mode":"replace","names":["p"]}""")
                r.isError shouldBe false
                r.body
                    .getValue("selected")
                    .jsonPrimitive.int shouldBe 1000
                r.body
                    .getValue("truncated")
                    .jsonPrimitive.int shouldBe 1200
            }
        }

        "set_visibility hides, shows, isolates and toggles transparent by name and by selection" {
            val port = FakePort()
            port.sceneOf("a", "b", "c")
            withRig(port) { rig ->
                fun mode(i: Int) = port.visibilityState.leafStates[port.scene!!.visibilityScene.partKey[i]]
                rig
                    .call("kstep_desktop_set_visibility", """{"action":"hide","parts":["a"]}""")
                    .body
                    .getValue("hidden")
                    .jsonPrimitive.int shouldBe 1
                mode(0) shouldBe Visibility.Hidden
                rig
                    .call("kstep_desktop_set_visibility", """{"action":"hide","parts":["a"]}""")
                    .body
                    .getValue("changed")
                    .jsonPrimitive.content shouldBe "false"
                rig.call("kstep_desktop_set_visibility", """{"action":"show","parts":["a"]}""")
                mode(0) shouldBe null
                rig
                    .call("kstep_desktop_set_visibility", """{"action":"isolate","parts":["b"]}""")
                    .body
                    .getValue("transparent")
                    .jsonPrimitive.int shouldBe 2
                mode(1) shouldBe null
                mode(0) shouldBe Visibility.Transparent
                rig.call("kstep_desktop_set_visibility", """{"action":"show_all"}""")
                port.visibilityState.leafStates.isEmpty() shouldBe true
                rig.call("kstep_desktop_set_visibility", """{"action":"toggle_transparent","parts":["c"]}""")
                mode(2) shouldBe Visibility.Transparent
                rig.call("kstep_desktop_set_visibility", """{"action":"toggle_transparent","parts":["c"]}""")
                mode(2) shouldBe null
                port.selectionState = SelectionState(selected = "b")
                rig.call("kstep_desktop_set_visibility", """{"action":"hide"}""")
                mode(1) shouldBe Visibility.Hidden
            }
        }

        "set_visibility without parts and without a selection is INVALID_ARGUMENTS; an unknown name is NOT_FOUND" {
            val port = FakePort()
            port.sceneOf("a")
            withRig(port) { rig ->
                rig.call("kstep_desktop_set_visibility", """{"action":"hide"}""").code shouldBe "INVALID_ARGUMENTS"
                rig.call("kstep_desktop_set_visibility", """{"action":"hide","parts":["zz"]}""").code shouldBe
                    "NOT_FOUND"
                port.log.contains("setVisibility") shouldBe false
            }
        }

        "set_visibility show_all without a model changes nothing" {
            withRig(FakePort()) { rig ->
                rig
                    .call("kstep_desktop_set_visibility", """{"action":"show_all"}""")
                    .body
                    .getValue("changed")
                    .jsonPrimitive.content shouldBe "false"
            }
        }

        "show_in_script maps the targets" {
            for (
            (target, code) in
            listOf(
                McpShowTarget.BufferNotRun to "BUFFER_NOT_RUN",
                McpShowTarget.NotFound to "NOT_FOUND",
                McpShowTarget.NotAScript to "NOT_A_SCRIPT",
                McpShowTarget.NoFile to "NO_FILE",
            )
            ) {
                val port = FakePort().also { it.show = target }
                withRig(port) { rig -> rig.call("kstep_desktop_show_in_script", """{"part":"a"}""").code shouldBe code }
            }
        }

        "show_in_script selects the place, without focus, and answers line and column" {
            val text = "val a = 1\nbox(\"a\")\n"
            val port = FakePort(text = text)
            port.show = McpShowTarget.Ready(text, 3, 2, true, 1)
            withRig(port) { rig ->
                val r = rig.call("kstep_desktop_show_in_script", """{"part":"a"}""")
                r.isError shouldBe false
                r.body
                    .getValue("line")
                    .jsonPrimitive.int shouldBe 2
                port.selectedRange shouldBe (text.indexOf("\"a\"") + 1 to text.indexOf("\"a\"") + 2)
                port.selectInEditorResult = false
                rig.call("kstep_desktop_show_in_script", """{"part":"a"}""").code shouldBe "STALE_REVISION"
            }
        }

        "an answer stays below 1 MiB and never contains the sent text" {
            withRig(FakePort(text = original)) { rig ->
                val secret = "SECRET-TEXT-123"
                val r =
                    rig.call(
                        "kstep_desktop_set_script",
                        """{"text":"$secret","expectedRevision":"${rev(original)}"}""",
                    )
                r.toString() shouldNotContain secret
                (r.toString().length < (1 shl 20)) shouldBe true
            }
        }
    })
