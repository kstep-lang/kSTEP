package dev.kstep.desktop.menu

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ToolbarModelTest :
    StringSpec({
        fun ctx(
            knowledge: Boolean = false,
            export: Boolean = false,
            convert: Boolean = false,
            visibility: Boolean = true,
            selection: Boolean = false,
            nothingChanged: Boolean = false,
        ) = MenuContext(
            scriptOpen = export,
            knowledgeOpen = knowledge,
            exportEnabled = export,
            visibilityAvailable = visibility,
            hasSelection = selection,
            nothingChanged = nothingChanged,
            convertDialogVisible = convert,
        )

        "every button has its name and an own, expected icon" {
            ToolbarButtonId.entries.map { it.toolbarName } shouldBe
                listOf("Open…", "Export…", "Home", "Tree", "AI Panel", "Show All", "Isolate", "Hide")
            ToolbarButtonId.entries
                .map { it.icon }
                .toSet()
                .size shouldBe ToolbarButtonId.entries.size
            ToolbarButtonId.TREE.icon shouldBe MenuIconId.TREE
            ToolbarButtonId.AI.icon shouldBe MenuIconId.AI_PANEL
            ToolbarButtonId.EXPORT.icon shouldBe MenuIconId.EXPORT
        }

        "an enabled button shows only its name" {
            toolbarTooltip(ToolbarButtonState(ToolbarButtonId.HOME, true, null, null)) shouldBe "Home"
        }

        "a disabled button shows name and reason" {
            val reasons =
                listOf(
                    MenuReasons.KNOWLEDGE_UNAVAILABLE,
                    MenuReasons.OPEN_SCRIPT_FIRST,
                    MenuReasons.CLOSE_CONVERT_FIRST,
                    MenuReasons.SELECT_PART_FIRST,
                    MenuReasons.NOTHING_CHANGED,
                )
            for (reason in reasons) {
                toolbarTooltip(ToolbarButtonState(ToolbarButtonId.HIDE, false, reason, null)) shouldBe "Hide — $reason"
            }
            toolbarTooltip(ToolbarButtonState(ToolbarButtonId.HIDE, false, null, null)) shouldBe "Hide"
        }

        "the opacity tooltip shows the percentage or why there is none" {
            opacityTooltip(1, 3) shouldBe "Opacity of transparent parts: 5 %"
            opacityTooltip(12, 3) shouldBe "Opacity of transparent parts: 60 %"
            opacityTooltip(4, 0) shouldBe "Opacity — No transparent parts"
        }

        "state description of a toggle" {
            stateDescription(true) shouldBe "On"
            stateDescription(false) shouldBe "Off"
        }

        "only Tree and AI are toggles" {
            val c = ctx()
            treeToolbarState(true).selected shouldBe true
            treeToolbarState(false).selected shouldBe false
            aiToolbarState(c, true).selected shouldBe true
            listOf(
                openToolbarState(c),
                exportToolbarState(c),
                homeToolbarState(c),
                showAllToolbarState(c),
                isolateToolbarState(c),
                hideToolbarState(c),
            ).all { it.selected == null } shouldBe true
            treeToolbarState(false).enabled shouldBe true
        }

        "AI is disabled in a knowledge workspace with the reason" {
            val s = aiToolbarState(ctx(knowledge = true), on = false)
            s.enabled shouldBe false
            s.reason shouldBe MenuReasons.KNOWLEDGE_UNAVAILABLE
            aiToolbarState(ctx(), on = false).enabled shouldBe true
        }

        "Open and Home are always enabled" {
            for (k in listOf(true, false)) {
                openToolbarState(ctx(knowledge = k)).enabled shouldBe true
                homeToolbarState(ctx(knowledge = k)).enabled shouldBe true
                openToolbarState(ctx(knowledge = k)).reason shouldBe null
            }
        }

        "Export agrees with the menu in all 8 combinations, and with the old toolbar flag where reachable" {
            for (knowledge in listOf(false, true)) {
                for (export in listOf(false, true)) {
                    for (convert in listOf(false, true)) {
                        val c = ctx(knowledge = knowledge, export = export, convert = convert)
                        val toolbar = exportToolbarState(c)
                        val menu = stateOf(FileAction.EXPORT, c)
                        toolbar.enabled shouldBe menu.enabled
                        toolbar.reason shouldBe menu.reason
                        // exportEnabled implies !knowledgeOpen in the app, so only that subset is reachable.
                        if (!(knowledge && export)) {
                            toolbar.enabled shouldBe (export && !convert)
                        }
                        val expected =
                            when {
                                knowledge -> MenuReasons.KNOWLEDGE_UNAVAILABLE
                                !export -> MenuReasons.OPEN_SCRIPT_FIRST
                                convert -> MenuReasons.CLOSE_CONVERT_FIRST
                                else -> null
                            }
                        toolbar.reason shouldBe expected
                    }
                }
            }
        }

        "F1 regression: an open convert dialog names itself as the reason, not the missing script" {
            val s = exportToolbarState(ctx(knowledge = false, export = true, convert = true))
            s.enabled shouldBe false
            s.reason shouldBe MenuReasons.CLOSE_CONVERT_FIRST
            toolbarTooltip(s) shouldBe "Export… — Close the convert dialog first"
        }

        "the visibility buttons follow the former hand rules while a model is shown" {
            for (selection in listOf(false, true)) {
                for (hidden in listOf(0, 2)) {
                    for (transparent in listOf(0, 3)) {
                        val nothingChanged = hidden == 0 && transparent == 0
                        val c = ctx(selection = selection, nothingChanged = nothingChanged)
                        isolateToolbarState(c).enabled shouldBe selection
                        hideToolbarState(c).enabled shouldBe selection
                        isolateToolbarState(c).reason shouldBe if (selection) null else MenuReasons.SELECT_PART_FIRST
                        showAllToolbarState(c).enabled shouldBe !nothingChanged
                        showAllToolbarState(c).reason shouldBe if (nothingChanged) MenuReasons.NOTHING_CHANGED else null
                    }
                }
            }
        }
    })
