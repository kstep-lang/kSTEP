package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class ExportFormatNamingTest :
    StringSpec({
        "the formats are STEP, GLB, STL, 3MF in this order with STEP as the default" {
            ExportFormat.ALL.map { it.label } shouldBe listOf("STEP AP242", "GLB", "STL", "3MF")
            ExportFormat.ALL.map { it.extension } shouldBe listOf(".step", ".glb", ".stl", ".3mf")
            ExportFormat.ALL.map { it.workerId } shouldBe listOf("step", "glb", "stl", "3mf")
            ExportFormat.DEFAULT shouldBe ExportFormat.STEP
        }

        "the stem drops the script suffix" {
            ExportNaming.stem("bracket.kstep.kts") shouldBe "bracket"
            ExportNaming.stem("plain.kts") shouldBe "plain"
            ExportNaming.defaultFileName("bracket.kstep.kts", ExportFormat.THREE_MF) shouldBe "bracket.3mf"
        }

        "path separators, control characters and bidi overrides are removed" {
            ExportNaming.stem("../x.kstep.kts") shouldBe "x"
            ExportNaming.stem("a/b\\c.kstep.kts") shouldBe "abc"
            ExportNaming.stem("a\u0000b.kstep.kts") shouldBe "ab"
            ExportNaming.stem("a‮b.kstep.kts") shouldBe "ab"
            ExportNaming.stem("a:b*c?.kstep.kts") shouldBe "abc"
        }

        "names of only dots or spaces and empty names fall back to export" {
            ExportNaming.stem("....kstep.kts") shouldBe "export"
            ExportNaming.stem("   .kstep.kts") shouldBe "export"
            ExportNaming.stem("") shouldBe "export"
            ExportNaming.defaultFileName("", ExportFormat.STEP) shouldBe "export.step"
        }

        "reserved Windows names get a prefix" {
            ExportNaming.defaultFileName("CON.kstep.kts", ExportFormat.STEP) shouldBe "_CON.step"
            ExportNaming.stem("con.txt") shouldBe "_con.txt"
            ExportNaming.withExtension("nul", ExportFormat.STL) shouldBe "_nul.stl"
            ExportNaming.withExtension("LPT1.stl", ExportFormat.STL) shouldBe "_LPT1.stl"
        }

        "a long name is cut on a code point boundary" {
            ExportNaming.stem("x".repeat(300) + ".kstep.kts").length shouldBe ExportNaming.MAX_STEM_CODE_POINTS
            val name = ExportNaming.stem("a".repeat(119) + "😀😀.kstep.kts")
            name.codePointCount(0, name.length) shouldBe ExportNaming.MAX_STEM_CODE_POINTS
            name.all {
                !Character.isSurrogate(it) ||
                    name.codePoints().noneMatch { cp ->
                        cp in 0xD800..0xDFFF
                    }
            } shouldBe
                true
            name shouldNotContain "�"
        }

        "the whole file name never exceeds 255 bytes" {
            val full = ExportNaming.defaultFileName("😀".repeat(100) + ".kstep.kts", ExportFormat.STEP)
            (full.toByteArray(Charsets.UTF_8).size <= ExportNaming.MAX_FILE_NAME_BYTES) shouldBe true
            full.endsWith(".step") shouldBe true
            val typed = ExportNaming.withExtension("😀".repeat(100), ExportFormat.GLB)
            (typed.toByteArray(Charsets.UTF_8).size <= ExportNaming.MAX_FILE_NAME_BYTES) shouldBe true
        }

        "a typed extension is added, replaced when it is another export format, kept when unknown" {
            ExportNaming.withExtension("report", ExportFormat.STEP) shouldBe "report.step"
            ExportNaming.withExtension("report.step", ExportFormat.STEP) shouldBe "report.step"
            ExportNaming.withExtension("report.glb", ExportFormat.STEP) shouldBe "report.step"
            ExportNaming.withExtension("x.STP", ExportFormat.STEP) shouldBe "x.step"
            ExportNaming.withExtension("model.stl", ExportFormat.GLB) shouldBe "model.glb"
            ExportNaming.withExtension("my.v2", ExportFormat.STEP) shouldBe "my.v2.step"
        }
    })
