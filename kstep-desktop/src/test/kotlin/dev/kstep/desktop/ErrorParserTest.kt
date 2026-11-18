package dev.kstep.desktop

import dev.kstep.desktop.run.DesktopError
import dev.kstep.desktop.run.DesktopErrorCodes
import dev.kstep.desktop.run.ErrorParser
import dev.kstep.desktop.run.RenderReport
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.seconds

/** The JSON lines are real output of `kstep render --format glb --output json` (generated on this repo's CLI). */
class ErrorParserTest :
    StringSpec({
        val geometry =
            """{"status":"success","command":"render","outPath":"/tmp/x.glb","format":"glb","content":"geometry","fallback":false,"geometry":{"detected":true,"shapeCount":2,"previewedShapeIndex":0,"instanceCount":2,"distinctShapeCount":2,"triangleCount":12,"meshTriangleCount":24},"occt":{"available":true,"version":"7.9.3"},"rootCount":2,"glb":{"triangleCount":24,"vertexCount":72,"droppedTriangleCount":0,"byteLength":3620}}"""
        val summary =
            """{"status":"success","command":"render","outPath":"/tmp/x.glb","format":"glb","content":"summary","fallback":false,"geometry":{"detected":false,"shapeCount":0},"occt":{"available":true,"version":"7.9.3"},"rootCount":2,"glb":{"triangleCount":0,"vertexCount":0,"droppedTriangleCount":0,"byteLength":1064}}"""
        val notice =
            """{"status":"success","command":"render","outPath":"/tmp/x.glb","format":"glb","content":"notice","fallback":true,"fallbackReason":"occt_unavailable","geometry":{"detected":true,"shapeCount":0},"occt":{"available":false,"reason":"native bridge missing"},"rootCount":0}"""
        val compile =
            """{"status":"error","errorKind":"compilation_error","code":"KSTEP-S-001","diagnostics":[{"severity":"ERROR","message":"Syntax error: Expecting an expression.","line":2,"column":8}],"command":"render"}"""
        val runtime =
            """{"status":"error","errorKind":"runtime_error","code":"KSTEP-S-003","message":"boom at line 2","exceptionClass":"java.lang.IllegalStateException","command":"render"}"""

        "a geometry result" {
            ErrorParser.parseRenderJson(geometry).shouldBeInstanceOf<RenderReport.Geometry>().glbTriangles shouldBe 24
        }

        "a summary result has no geometry" {
            ErrorParser.parseRenderJson(summary) shouldBe RenderReport.NoGeometry
        }

        "a notice result carries the reason and the OCCT state" {
            ErrorParser.parseRenderJson(notice) shouldBe
                RenderReport.Notice("occt_unavailable", false, "native bridge missing")
        }

        "a compilation error gives one error per diagnostic with its position" {
            val failed = ErrorParser.parseRenderJson(compile).shouldBeInstanceOf<RenderReport.Failed>()
            failed.errors shouldBe listOf(DesktopError("KSTEP-S-001", 2, 8, "Syntax error: Expecting an expression."))
        }

        "a runtime error takes the position from the message when there is one" {
            ErrorParser
                .parseRenderJson(runtime)
                .shouldBeInstanceOf<RenderReport.Failed>()
                .errors
                .single()
                .line shouldBe
                null
            val withLine =
                """{"status":"error","errorKind":"runtime_error","code":"KSTEP-S-003","message":"x (a.kstep.kts:12:5)","exceptionClass":"E"}"""
            val e =
                ErrorParser
                    .parseRenderJson(withLine)
                    .shouldBeInstanceOf<RenderReport.Failed>()
                    .errors
                    .single()
            e.line shouldBe 12
            e.column shouldBe 5
        }

        "validation violations become errors without a position" {
            val json =
                """{"status":"error","errorKind":"validation_failed","violations":[{"code":"KSTEP-V-001","entityName":"product","ruleLabel":"R","expressionText":"e","message":"bad"}]}"""
            ErrorParser.parseRenderJson(json).shouldBeInstanceOf<RenderReport.Failed>().errors shouldBe
                listOf(DesktopError("KSTEP-V-001", null, null, "product: bad"))
        }

        "no_model_produced and mesh_export_invalid" {
            val noModel =
                """{"status":"error","errorKind":"no_model_produced","code":"KSTEP-S-002","message":"nothing"}"""
            ErrorParser
                .parseRenderJson(noModel)
                .shouldBeInstanceOf<RenderReport.Failed>()
                .errors
                .single()
                .code shouldBe
                "KSTEP-S-002"
            val tooMany =
                """{"status":"error","errorKind":"mesh_export_invalid","command":"render","message":"mesh export refused: more than 130000 triangles"}"""
            ErrorParser
                .parseRenderJson(tooMany)
                .shouldBeInstanceOf<RenderReport.Failed>()
                .errors
                .single()
                .code shouldBe
                DesktopErrorCodes.TOO_MANY_TRIANGLES
            val other =
                """{"status":"error","errorKind":"mesh_export_invalid","command":"render","message":"bad part"}"""
            ErrorParser
                .parseRenderJson(other)
                .shouldBeInstanceOf<RenderReport.Failed>()
                .errors
                .single()
                .code shouldBe
                DesktopErrorCodes.RESULT_UNREADABLE
            val io = """{"status":"error","errorKind":"io_error","command":"render","message":"disk full"}"""
            ErrorParser
                .parseRenderJson(io)
                .shouldBeInstanceOf<RenderReport.Failed>()
                .errors
                .single()
                .code shouldBe
                DesktopErrorCodes.RESULT_UNREADABLE
        }

        "garbage is Unreadable, never an exception".config(timeout = 30.seconds) {
            ErrorParser.parseRenderJson("hello").shouldBeInstanceOf<RenderReport.Unreadable>()
            ErrorParser.parseRenderJson("[1,2]").shouldBeInstanceOf<RenderReport.Unreadable>()
            ErrorParser.parseRenderJson("""{"status":"weird"}""").shouldBeInstanceOf<RenderReport.Unreadable>()
            ErrorParser
                .parseRenderJson(
                    """{"status":"success","content":"x"}""",
                ).shouldBeInstanceOf<RenderReport.Unreadable>()
            ErrorParser
                .parseRenderJson(
                    """{"status":"error","errorKind":"zzz"}""",
                ).shouldBeInstanceOf<RenderReport.Failed>()
        }

        "messages are cut to 300 characters, stripped of ANSI and control characters" {
            val long = "a".repeat(500)
            val cut = ErrorParser.sanitize(long)
            cut.length shouldBe 301
            cut shouldEndWith "…"
            ErrorParser.sanitize("\u001B[31mred\u001B[0m\u0007 ok\u0000\n") shouldBe "red ok"
            ErrorParser.sanitize("tab\there") shouldBe "tab\there"
        }

        "errors are sorted by line and capped at 20" {
            val errors =
                (1..30).map { DesktopError("C", if (it % 2 == 0) it else null, null, "m$it") } +
                    DesktopError("C", 1, 1, "first")
            val normalized = ErrorParser.normalize(errors)
            normalized.size shouldBe 20
            normalized.first().message shouldBe "first"
            normalized.map { it.line }.takeWhile { it != null }.let { it shouldBe it.sortedBy { n -> n } }
        }

        "only the last stdout line is the document (a println before it is not parsed)" {
            val lines = "hello from the script\n$runtime"
            ErrorParser.parseRenderJson(lines.lines().last()).shouldBeInstanceOf<RenderReport.Failed>()
        }
    })
