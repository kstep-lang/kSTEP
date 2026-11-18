package dev.kstep.desktop.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ExportReportParserTest :
    StringSpec({
        fun parse(
            json: String,
            format: ExportFormat = ExportFormat.STEP,
        ) = ExportReportParser.parse(json, format)

        "a STEP export with merged geometry is produced" {
            parse(
                """{"status":"success","outPath":"x","rootCount":1,"shapeCount":2,""" +
                    """"geometry":{"merged":true,"shapeCount":2,"occt":{"available":true}}}""",
            ) shouldBe ExportReport.Produced
        }

        "a STEP export of a script without shapes is a valid structure-only export" {
            parse("""{"status":"success","outPath":"x","rootCount":1,"shapeCount":0}""") shouldBe ExportReport.Produced
        }

        "shapes that were not merged are reported as unavailable, never as a good export" {
            val report =
                parse(
                    """{"status":"success","shapeCount":2,"geometry":{"merged":false,"shapeCount":2,""" +
                        """"occt":{"available":false,"reason":"no bridge"},"reason":"no bridge"}}""",
                )
            report.shouldBeInstanceOf<ExportReport.Unavailable>().reason shouldBe "no bridge"
        }

        "shapes without any geometry object are not trusted either" {
            parse("""{"status":"success","shapeCount":1}""").shouldBeInstanceOf<ExportReport.Unavailable>()
        }

        "a mesh export reports geometry, a summary as no geometry and a notice as unavailable" {
            parse("""{"status":"success","content":"geometry","occt":{"available":true}}""", ExportFormat.GLB) shouldBe
                ExportReport.Produced
            val none = parse("""{"status":"success","content":"summary"}""", ExportFormat.GLB)
            none
                .shouldBeInstanceOf<ExportReport.Failed>()
                .errors
                .single()
                .code shouldBe "KSTEP-D-022"
            parse(
                """{"status":"success","content":"notice","fallbackReason":"occt_unavailable",""" +
                    """"occt":{"available":false,"reason":"no lib"}}""",
                ExportFormat.GLB,
            ).shouldBeInstanceOf<ExportReport.Unavailable>()
        }

        "geometry_unavailable is unavailable without OCCT and no geometry with OCCT" {
            parse(
                """{"status":"error","errorKind":"geometry_unavailable","occt":{"available":false,"reason":"r"}}""",
                ExportFormat.STL,
            ).shouldBeInstanceOf<ExportReport.Unavailable>()
            parse(
                """{"status":"error","errorKind":"geometry_unavailable","fallbackReason":"no_shapes","occt":{"available":true}}""",
                ExportFormat.STL,
            ).shouldBeInstanceOf<ExportReport.Failed>().errors.single().code shouldBe "KSTEP-D-022"
        }

        "geometry_required and a mesh problem are KSTEP-D-022" {
            parse("""{"status":"error","errorKind":"geometry_required"}""")
                .shouldBeInstanceOf<ExportReport.Failed>()
                .errors
                .single()
                .code shouldBe "KSTEP-D-022"
            parse(
                """{"status":"error","errorKind":"mesh_export_invalid","message":"bad triangle count"}""",
                ExportFormat.STL,
            ).shouldBeInstanceOf<ExportReport.Failed>()
                .errors
                .single()
                .code shouldBe "KSTEP-D-022"
        }

        "script errors keep their KSTEP-S codes and lines" {
            val report =
                parse(
                    """{"status":"error","errorKind":"compilation_error","code":"KSTEP-S-001",""" +
                        """"diagnostics":[{"severity":"ERROR","message":"nope","line":3,"column":4}]}""",
                )
            val error = report.shouldBeInstanceOf<ExportReport.Failed>().errors.single()
            error.code shouldBe "KSTEP-S-001"
            error.line shouldBe 3
        }

        "garbage never throws and is unreadable" {
            parse("not json").shouldBeInstanceOf<ExportReport.Unreadable>()
            parse("[1,2]").shouldBeInstanceOf<ExportReport.Unreadable>()
            parse("""{"status":"maybe"}""").shouldBeInstanceOf<ExportReport.Unreadable>()
            parse("""{"status":"error","errorKind":"zzz"}""").shouldBeInstanceOf<ExportReport.Failed>()
        }

        "a hostile message loses its ANSI escapes" {
            val report =
                parse("""{"status":"error","errorKind":"geometry_export_failed","message":"\u001b[31mred\u001b[0m"}""")
            report
                .shouldBeInstanceOf<ExportReport.Failed>()
                .errors
                .single()
                .message shouldBe "red"
        }
    })
