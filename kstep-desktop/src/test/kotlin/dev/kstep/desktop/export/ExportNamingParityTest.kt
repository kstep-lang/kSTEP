package dev.kstep.desktop.export

import dev.kstep.script.ScriptExportFormat
import dev.kstep.script.ScriptExportNames
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * `kstep-script` must not depend on this module, so the rules for a declared export name exist twice
 * (`ScriptExportNames.validate`, `ExportNaming.isValidDeclaredName`). The same table runs through both (ADR-0063).
 */
class ExportNamingParityTest :
    StringSpec({
        val cases =
            listOf(
                "a.step",
                "A.STEP",
                "part.stp",
                "a.glb",
                "a.stl",
                "a.3mf",
                "a b.step",
                "\u00E4.step",
                "x.v2.step",
                "",
                ".step",
                "a.step.",
                "a.step ",
                " a.step",
                ".hidden.step",
                "..",
                ".",
                "../a.step",
                "a/b.step",
                "a\\b.step",
                "a:b.step",
                "a*.step",
                "a?.step",
                "a\".step",
                "a<b>.step",
                "a|b.step",
                "a\u0000b.step",
                "a\u0007b.step",
                "a\u202Eb.step",
                "a\u200Fb.step",
                "a\u2028b.step",
                "CON.step",
                "con.step",
                "NUL.glb",
                "COM1.stl",
                "LPT9.3mf",
                "AUX",
                "PRN.step",
                "a\u0308.step",
                "a".repeat(115) + ".step",
                "a".repeat(116) + ".step",
                "a".repeat(120) + ".step",
                "\u00E4".repeat(112) + ".step",
                "\uD83D\uDE00".repeat(60) + ".step",
            )
        val pairs =
            ScriptExportFormat.entries.zip(ExportFormat.ALL).also {
                it.forEach { (script, desktop) -> script.id shouldBe desktop.workerId }
            }
        for ((scriptFormat, desktopFormat) in pairs) {
            "both rule sets agree for ${scriptFormat.id}" {
                val extra = scriptFormat.extensions.flatMap { listOf("n$it", "N${it.uppercase()}", it) }
                for (name in cases + extra) {
                    val script =
                        try {
                            ScriptExportNames.validate(scriptFormat, name)
                            true
                        } catch (_: IllegalArgumentException) {
                            false
                        }
                    val desktop = ExportNaming.isValidDeclaredName(name, desktopFormat)
                    if (script !=
                        desktop
                    ) {
                        error(
                            "rules differ for '${name.take(
                                20,
                            )}' (${scriptFormat.id}): script=$script desktop=$desktop",
                        )
                    }
                }
            }
        }
    })
