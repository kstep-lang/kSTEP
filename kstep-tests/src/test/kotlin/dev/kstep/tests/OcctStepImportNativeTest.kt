package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.XcafNodeKind
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Native-level coverage of the XCAF read session (ADR-0030). The class name carries `Occt` so the CI
 * skip gate sees these tests.
 */
class OcctStepImportNativeTest :
    StringSpec({
        val workDir = File("build/occt-step-import-native").apply { mkdirs() }
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun boxBytes(
            dx: Double = 20.0,
            dy: Double = 30.0,
            dz: Double = 40.0,
        ): ByteArray {
            val file = File(workDir, "box.step")
            OcctKernel.makeBox(dx, dy, dz).use { it.writeStepFile(file.toPath()) }
            return file.readBytes()
        }

        "a box STEP file reads back as one free part with exact volume and bounding box".config(enabled = available) {
            OcctKernel.readStep(boxBytes()).use { doc ->
                doc.nodes.size shouldBe 1
                val node = doc.nodes[0]
                node.kind shouldBe XcafNodeKind.PART
                node.freeRoot shouldBe true
                doc.stats.freeRootCount shouldBe 1
                doc.partShape(0).use { shape ->
                    shape.volume shouldBe (24000.0 plusOrMinus 1e-6)
                    shape.topology.faces shouldBe 6
                    val box = shape.boundingBox!!
                    box.minX shouldBe (0.0 plusOrMinus 1e-4)
                    box.maxX shouldBe (20.0 plusOrMinus 1e-4)
                    box.maxY shouldBe (30.0 plusOrMinus 1e-4)
                    box.maxZ shouldBe (40.0 plusOrMinus 1e-4)
                }
            }
        }

        "closing twice is harmless and leaves no import session behind".config(enabled = available) {
            val before = OcctBridge.nativeImportSessionCount()
            val bytes = boxBytes()
            repeat(200) {
                val doc = OcctKernel.readStep(bytes)
                doc.close()
                doc.close()
            }
            OcctBridge.nativeImportSessionCount() shouldBe before
        }

        "garbage bytes are rejected with IllegalArgumentException".config(enabled = available) {
            shouldThrow<IllegalArgumentException> { OcctKernel.readStep("this is not step".toByteArray()) }
        }

        "a tiny entity limit is reported natively with the limit: prefix".config(enabled = available) {
            val limits = intArrayOf(10, 10_000, 10_000, 2_000, 250_000, 256)
            val e = shouldThrow<IllegalArgumentException> { OcctBridge.nativeReadStepXcaf(boxBytes(), limits) }
            e.message!!.startsWith("limit:") shouldBe true
        }

        "native hardening: null arrays, bad limits, unknown handles".config(enabled = available) {
            shouldThrow<NullPointerException> {
                @Suppress("CAST_NEVER_SUCCEEDS")
                OcctBridge.nativeReadStepXcaf(null as ByteArray, IntArray(6) { 1 })
            }
            shouldThrow<IllegalArgumentException> { OcctBridge.nativeReadStepXcaf(boxBytes(), IntArray(5) { 1 }) }
            shouldThrow<IllegalArgumentException> { OcctBridge.nativeReadStepXcaf(boxBytes(), IntArray(6) { 0 }) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportNodeInts(-42) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportNodeMatrices(-42) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportNodeName(-42, 0) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportStats(-42) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportUnknownTypes(-42) }
            shouldThrow<IllegalStateException> { OcctBridge.nativeImportPartShape(-42, 0) }
            OcctBridge.nativeReleaseImport(-42)
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeBoundingBox(-42) }
        }
    })
