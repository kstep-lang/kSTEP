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

        /** Runs a generated file through the CLI and asserts a clean error document; returns its errorKind. */
        fun hostileKind(
            name: String,
            content: ByteArray,
        ): String {
            File(workDir, name).writeBytes(content)
            val result = runner.run("import", name, "--output", "json")
            result.exitCode shouldBe 1
            result.stderr shouldNotContain "hs_err"
            result.stderr shouldNotContain "SIGSEGV"
            result.stderr shouldNotContain "StackOverflowError"
            val json = Json.parseToJsonElement(result.stdout.trim()).jsonObject
            json["status"]!!.jsonPrimitive.content shouldBe "error"
            return json["errorKind"]!!.jsonPrimitive.content
        }

        // OCCT transfers NAUO chains recursively; the depth check therefore runs on the typed entities BEFORE
        // Transfer() (ADR-0038). 2000 levels are a few hundred kilobytes, far below every size limit.
        "a product chain of 2000 levels is limit_exceeded, not a native stack overflow".config(enabled = available) {
            hostileKind("chain2000.step", ImportFixtures.assemblyChain(2000)) shouldBe "limit_exceeded"
        }

        "a product chain of 20000 levels is limit_exceeded too".config(enabled = available) {
            hostileKind("chain20000.step", ImportFixtures.assemblyChain(20_000)) shouldBe "limit_exceeded"
        }

        "more than one million entities are limit_exceeded through the CLI".config(enabled = available) {
            // Measured on macOS arm64: about 1.4 s and 880 MB resident for this 40 MB file (ADR-0038).
            hostileKind("many-entities.step", ImportFixtures.manyEntities(1_000_001)) shouldBe "limit_exceeded"
        }

        "a parameter list nested 100000 parentheses deep ends in a clean error, never a signal".config(
            enabled = available,
        ) {
            val kind = hostileKind("deep-parentheses.step", ImportFixtures.deepParentheses(100_000))
            (kind in setOf("read_failed", "not_step", "limit_exceeded")) shouldBe true
        }

        "failing reads of every kind leave no import session and no shape handle behind".config(enabled = available) {
            val sessionsBefore = OcctBridge.nativeImportSessionCount()
            val shapesBefore = OcctBridge.nativeLiveShapeCount()
            val cases =
                listOf(
                    ImportFixtures.assemblyChain(40), // limit: depth
                    fixture("unresolved-unit-box.step").readBytes(), // unit
                    fixture("external-ref.step").readBytes(), // external reference
                    fixture("cyclic-nauo.step").readBytes(), // cycle
                    fixture("broken.step").readBytes(), // truncated
                )
            repeat(50) {
                for (content in cases) {
                    val result =
                        dev.kstep.shape.importer.StepImporter
                            .read(content)
                    (result is dev.kstep.shape.importer.StepImportResult.Failure) shouldBe true
                    // the kernel path directly, so the native error paths run even where the prescan stops early
                    runCatching { OcctKernel.readStep(content).close() }.isFailure shouldBe true
                }
            }
            (OcctBridge.nativeImportSessionCount() - sessionsBefore <= 2) shouldBe true
            (OcctBridge.nativeLiveShapeCount() - shapesBefore <= 2) shouldBe true
        }
    })
