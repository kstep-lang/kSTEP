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
                    val registered = registeredShortcut(action, ctx)
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
                        ShortcutRouting.route(sc.key, true, sc.shift, false, MenuOs.MAC, ctx) shouldBe
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
