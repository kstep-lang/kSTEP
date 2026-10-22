package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Hostile and malformed STEP input against `kstep import` (ADR-0030). Files are fed through a
 * SUBPROCESS so that a native crash would show up as a signal exit code instead of killing the test JVM.
 * The class name carries `Occt` so the CI skip gate sees these tests.
 */
class OcctStepImportHostileInputTest :
    StringSpec({
        val workDir = File("build/step-import-hostile").apply { mkdirs() }
        val runner = CliProcessRunner(workDir)
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun fixture(name: String): File {
            val target = File(workDir, name)
            val input =
                requireNotNull(OcctStepImportHostileInputTest::class.java.getResourceAsStream("/import/$name")) { name }
            input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
            return target
        }

        "a truncated file ends in a clean result: exit 0 or 1, never a signal".config(enabled = available) {
            fixture("broken.step")
            val result = runner.run("import", "broken.step", "--output", "json")
            (result.exitCode == 0 || result.exitCode == 1) shouldBe true
            result.stderr shouldNotContain "SIGSEGV"
            result.stderr shouldNotContain "hs_err"
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            (json["status"]!!.jsonPrimitive.content in setOf("success", "error")) shouldBe true
        }

        // OCCT itself rejects the usage cycle while reading, so the Kotlin depth guard is never reached through
        // this file; that guard is covered without OCCT by StepImporterExpansionTest in kstep-shape.
        "a cyclic NAUO pair is rejected as read_failed (KSTEP-I-009), without a crash".config(enabled = available) {
            fixture("cyclic-nauo.step")
            val result = runner.run("import", "cyclic-nauo.step", "--output", "json")
            result.exitCode shouldBe 1
            result.stderr shouldNotContain "hs_err"
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["command"]!!.jsonPrimitive.content shouldBe "import"
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            json["errorKind"]!!.jsonPrimitive.content shouldBe "read_failed"
        }

        "native hardening: a wrong limits array and unknown handles are IllegalArgument/IllegalState".config(
            enabled = available,
        ) {
            shouldThrow<IllegalArgumentException> { OcctBridge.nativeReadStepXcaf(ByteArray(10), IntArray(3)) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportPartShape(123456789L, 0) }
            OcctBridge.nativeReleaseImport(123456789L) // idempotent, silent
        }

        "partShape on a non-part node and out-of-range indices are IllegalArgumentException".config(
            enabled = available,
        ) {
            val bytes = fixture("nested-occt-reference.step").readBytes()
            OcctKernel.readStep(bytes).use { doc ->
                val component = doc.nodes.first { it.kind == dev.kstep.geometry.XcafNodeKind.COMPONENT }
                shouldThrow<IllegalArgumentException> { doc.partShape(component.index) }
                shouldThrow<IllegalArgumentException> { doc.partShape(-1) }
                shouldThrow<IllegalArgumentException> { doc.partShape(doc.nodes.size) }
            }
        }

        "closed documents refuse partShape; closing twice is harmless".config(enabled = available) {
            val bytes = fixture("box-20x30x40.step").readBytes()
            val doc = OcctKernel.readStep(bytes)
            doc.close()
            doc.close()
            shouldThrow<IllegalStateException> { doc.partShape(0) }
        }

        "200 imports leave neither import sessions behind".config(enabled = available) {
            val bytes = fixture("box-20x30x40.step").readBytes()
            val before = OcctBridge.nativeImportSessionCount()
            repeat(200) { OcctKernel.readStep(bytes).use { doc -> doc.partShape(0).close() } }
            OcctBridge.nativeImportSessionCount() shouldBe before
        }
    })
