package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Which File entry is enabled when (ADR-0072); the texts are pinned because the toolbar shows the same ones. */
class StateOfTest :
    StringSpec({
        val bools = listOf(false, true)
        val contexts =
            bools.flatMap { script ->
                bools.flatMap { knowledge ->
                    bools.map { export -> MenuContext(script, knowledge, export) }
                }
            }

        "a disabled entry registers no native shortcut, so its key press reaches the key handler" {
            for (ctx in contexts) {
                for (action in FileAction.entries) {
                    val registered = registeredShortcut(action, ctx, MenuOs.MAC)
                    if (stateOf(action, ctx).enabled) {
                        registered shouldBe action.shortcut
                    } else {
                        registered shouldBe null
                    }
                }
            }
            // every disabled entry that owns a shortcut is routed to a reason by the key handler
            for (ctx in contexts) {
                for (action in FileAction.entries) {
                    val sc = action.shortcut ?: continue
                    if (!stateOf(action, ctx).enabled) {
                        ShortcutRouting.route(sc.key, true, sc.shift, sc.alt, MenuOs.MAC, ctx) shouldBe
                            ShortcutRouting.Route.ShowReason(checkNotNull(stateOf(action, ctx).reason))
                    }
                }
            }
        }

        "the reason texts are the ones the toolbar always had" {
            MenuReasons.KNOWLEDGE_UNAVAILABLE shouldBe "Not available in a knowledge workspace yet (planned)"
            MenuReasons.OPEN_SCRIPT_FIRST shouldBe "Open a script first"
        }

        "an action is enabled exactly when it has no reason, in all eight contexts" {
            contexts.size shouldBe 8
            for (ctx in contexts) {
                for (action in FileAction.entries) {
                    val state = stateOf(action, ctx)
                    state.enabled shouldBe (state.reason == null)
                }
            }
        }

        "Open, Open Folder and Quit are always enabled" {
            for (ctx in contexts) {
                for (a in listOf(FileAction.OPEN, FileAction.OPEN_FOLDER, FileAction.QUIT)) {
                    stateOf(a, ctx) shouldBe ActionState(true, null)
                }
            }
        }

        "Close Workspace needs an open folder" {
            for (ctx in contexts) {
                stateOf(FileAction.CLOSE_WORKSPACE, ctx) shouldBe
                    if (ctx.knowledgeOpen) ActionState(true, null) else ActionState(false, MenuReasons.NO_FOLDER_OPEN)
            }
        }

        "Save needs an open script or note" {
            for (ctx in contexts) {
                stateOf(FileAction.SAVE, ctx) shouldBe
                    if (ctx.scriptOpen) ActionState(true, null) else ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
            }
        }

        "Export follows the toolbar: workspace first, then missing script" {
            for (ctx in contexts) {
                stateOf(FileAction.EXPORT, ctx) shouldBe
                    when {
                        ctx.knowledgeOpen -> ActionState(false, MenuReasons.KNOWLEDGE_UNAVAILABLE)
                        !ctx.exportEnabled -> ActionState(false, MenuReasons.OPEN_SCRIPT_FIRST)
                        else -> ActionState(true, null)
                    }
            }
        }
    })

/** File > Convert (ADR-0078): enabled unless a conversion runs or the export dialog is open. */
class ConvertMenuStateTest :
    StringSpec({
        val base = MenuContext(scriptOpen = false, knowledgeOpen = false, exportEnabled = false)

        "Convert is enabled by default, with a workspace open and with no script open" {
            stateOf(FileAction.CONVERT, base) shouldBe ActionState(true, null)
            stateOf(FileAction.CONVERT, base.copy(knowledgeOpen = true)) shouldBe ActionState(true, null)
            stateOf(FileAction.CONVERT, base.copy(scriptOpen = true, exportEnabled = true)) shouldBe
                ActionState(true, null)
        }

        "a running conversion disables it with its reason" {
            stateOf(FileAction.CONVERT, base.copy(convertRunning = true)) shouldBe
                ActionState(false, "A conversion is running")
        }

        "an open export dialog disables it with its reason" {
            stateOf(FileAction.CONVERT, base.copy(exportDialogVisible = true)) shouldBe
                ActionState(false, "Close the export dialog first")
        }

        "with both set the running conversion wins" {
            stateOf(FileAction.CONVERT, base.copy(convertRunning = true, exportDialogVisible = true)).reason shouldBe
                "A conversion is running"
        }

        "Convert has no shortcut, so nothing is registered and the overview has no row for it" {
            FileAction.CONVERT.shortcut shouldBe null
            registeredShortcut(FileAction.CONVERT, base, MenuOs.MAC) shouldBe null
            for (os in MenuOs.entries) {
                shortcutOverview(os).flatMap { it.rows }.none { it.label == FileAction.CONVERT.label } shouldBe true
            }
        }
    })
