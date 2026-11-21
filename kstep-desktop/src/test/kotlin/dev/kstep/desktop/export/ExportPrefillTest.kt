package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class ExportPrefillTest :
    StringSpec({
        val a = ScriptExportTarget(ExportFormat.STEP, "a.step")
        val b = ScriptExportTarget(ExportFormat.STL, "b.stl")
        val dirScript: Path = Path.of("/tmp/script-dir")
        val dirGlobal: Path = Path.of("/tmp/global-dir")
        val global = ExportPreferences(ExportFormat.GLB, dirGlobal)

        "the script block beats the remembered format" {
            val r = ExportPrefill.resolve(listOf(a, b), ScriptExportPreference(ExportFormat.GLB, null, null, 1), global)
            r.format shouldBe ExportFormat.STEP
            r.selectedScriptTarget shouldBe 0
        }

        "a remembered declared target is marked, also beyond the first" {
            val r =
                ExportPrefill.resolve(
                    listOf(a, b),
                    ScriptExportPreference(ExportFormat.STL, null, "b.stl", 1),
                    global,
                )
            r.selectedScriptTarget shouldBe 1
            r.format shouldBe ExportFormat.STL
        }

        "a target the block no longer declares falls back to the first" {
            val r =
                ExportPrefill.resolve(
                    listOf(a, b),
                    ScriptExportPreference(ExportFormat.STL, null, "gone.stl", 1),
                    global,
                )
            r.selectedScriptTarget shouldBe 0
            r.format shouldBe ExportFormat.STEP
        }

        "without a block the script's format comes before the global one before the default" {
            ExportPrefill.resolve(emptyList(), ScriptExportPreference(ExportFormat.STL), global).format shouldBe
                ExportFormat.STL
            ExportPrefill.resolve(emptyList(), null, global).format shouldBe ExportFormat.GLB
            ExportPrefill.resolve(emptyList(), null, ExportPreferences()).format shouldBe ExportFormat.DEFAULT
            ExportPrefill.resolve(emptyList(), null, global).selectedScriptTarget shouldBe null
        }

        "the folder falls back from the script to the global one; the block never sets a folder" {
            ExportPrefill.resolve(emptyList(), ScriptExportPreference(dir = dirScript), global).dir shouldBe dirScript
            ExportPrefill.resolve(emptyList(), ScriptExportPreference(), global).dir shouldBe dirGlobal
            ExportPrefill.resolve(listOf(a), null, ExportPreferences()).dir shouldBe null
        }
    })
