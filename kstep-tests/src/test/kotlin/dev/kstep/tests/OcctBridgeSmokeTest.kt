package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.ShapeTopology
import dev.kstep.geometry.StepSchema
import dev.kstep.geometry.occt.OcctBridge
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private val logger = KotlinLogging.logger {}

/**
 * The end-to-end proof for kSTEP's Geometrie Welle 1 (OCCT JNI bridge, see
 * docs/adr/ADR-0005-occt-jni-bridge.adoc): a real box built through OCCT, its real B-Rep topology
 * and volume read back, and a real AP242 STEP file written to disk.
 *
 * Every OCCT-dependent test case below is `.config(enabled = available)`, so this suite is green
 * on a machine without the OCCT dev packages installed (`OcctKernel.availability()` reports
 * [OcctAvailability.Unavailable] and those cases are skipped, not failed) -- EXCEPT the very
 * first test case, which is the deliberate guard against the whole suite silently degrading into
 * a no-op: when the build is run with `-Pkstep.occt.require=true` (see
 * `kstep-tests/build.gradle.kts` and README 'Building'), that first case turns any
 * [OcctAvailability.Unavailable] into a hard test failure instead of a silent skip.
 */
class OcctBridgeSmokeTest :
    StringSpec({
        val availability = OcctKernel.availability()
        val available = availability is OcctAvailability.Available
        val required = System.getProperty("kstep.occt.require") == "true"

        "the OCCT bridge is available when the build requires it" {
            if (required) {
                availability.shouldBeInstanceOf<OcctAvailability.Available>()
            } else {
                val detail =
                    when (availability) {
                        is OcctAvailability.Available ->
                            "available: OCCT ${availability.occtVersion} (${availability.librarySource})"
                        is OcctAvailability.Unavailable ->
                            "unavailable: ${availability.reason}"
                    }
                logger.info { "OCCT bridge status: $detail (kstep.occt.require=false, not enforced)" }
            }
        }

        "OCCT reports a 7.x version".config(enabled = available) {
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
            Regex("^7\\.\\d+\\.\\d+").containsMatchIn(version) shouldBe true
        }

        "a 20x30x40 box has the expected unique B-Rep topology".config(enabled = available) {
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                box.topology shouldBe ShapeTopology(solids = 1, shells = 1, faces = 6, edges = 12, vertices = 8)
            }
        }

        "the box has the expected volume".config(enabled = available) {
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                box.volume shouldBe (24_000.0 plusOrMinus 1e-6)
            }
        }

        "the box exports to a real, non-empty AP242 STEP file".config(enabled = available) {
            val outDir = File("build/occt-bridge-smoke-test").apply { mkdirs() }
            val target = File(outDir, "box-ap242.step").toPath()
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                val written = box.writeStepFile(target)
                val writtenFile = written.toFile()
                writtenFile.exists() shouldBe true
                (writtenFile.length() > 1000) shouldBe true
                val text = writtenFile.readText()
                text.trimStart().startsWith("ISO-10303-21;") shouldBe true
                text.contains("AP242") shouldBe true
                text.contains("MANIFOLD_SOLID_BREP") shouldBe true
                text.trimEnd().endsWith("END-ISO-10303-21;") shouldBe true
            }
        }

        "the STEP schema is selectable".config(enabled = available) {
            val outDir = File("build/occt-bridge-smoke-test").apply { mkdirs() }
            val target = File(outDir, "box-ap203.step").toPath()
            OcctKernel.makeBox(10.0, 10.0, 10.0).use { box ->
                val written = box.writeStepFile(target, StepSchema.AP203)
                val text = written.toFile().readText()
                // OCCT's STEPControl_Writer tags a STEP file's FILE_SCHEMA entity with the
                // underlying EXPRESS schema identifier, not with the AP number itself -- AP203's
                // EXPRESS schema is named "CONFIG_CONTROL_DESIGN". The literal substring "AP203"
                // never appears anywhere in an AP203 file OCCT writes, per the EXPRESS schema
                // naming above -- this assertion previously asserted the opposite, which was a
                // bug fixed before this comment. Confirmed against a real OCCT 7.9.2 install on
                // 2026-09-02 (see docs/adr/ADR-0005-occt-jni-bridge.adoc's "Environment note"):
                // this case ran for real, gated on `available`, and the written file's
                // FILE_SCHEMA entity is exactly `CONFIG_CONTROL_DESIGN` with zero occurrences of
                // "AP242". AP242's own
                // FILE_SCHEMA entity, by contrast, happens to literally contain "AP242" as a
                // substring of its EXPRESS schema identifier
                // ("AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF") -- that's what the other
                // `text.contains("AP242")` assertions in this file (over an AP242-written file)
                // are actually matching.
                text.contains("CONFIG_CONTROL_DESIGN") shouldBe true
                text.contains("AP242") shouldBe false
            }
        }

        "a closed shape rejects further use".config(enabled = available) {
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            box.close()
            shouldThrow<IllegalStateException> { box.topology }
        }

        (
            "a closed shape rejects a REPEAT topology/volume access when the lazily-cached backing " +
                "value was already computed before close()"
        ).config(enabled = available) {
            // Guards against the checkOpen()-vs-caching regression that motivated splitting
            // OcctShape.topology/volume into a checkOpen()-on-every-access getter over a
            // separately `by lazy`-cached backing property: each box below reads
            // `topology`/`volume` ONCE before close() (forcing the `by lazy` to compute and
            // cache its value), then again AFTER close(). If checkOpen() ever moved back
            // inside the lazy initializer, that second access would hit the already-cached
            // value and skip the initializer -- and with it checkOpen() -- entirely, so the
            // exception below would silently not be thrown. "a closed shape rejects further
            // use" above reads `topology` for the very first time only after close(), so its
            // lazy is never computed there and it cannot distinguish the two checkOpen()
            // placements -- this case is the one that actually catches the regression.
            OcctKernel.makeBox(1.0, 1.0, 1.0).let { box ->
                box.topology
                box.close()
                shouldThrow<IllegalStateException> { box.topology }
            }
            OcctKernel.makeBox(1.0, 1.0, 1.0).let { box ->
                box.volume
                box.close()
                shouldThrow<IllegalStateException> { box.volume }
            }
        }

        "a closed shape rejects writeStepFile".config(enabled = available) {
            val outDir = File("build/occt-bridge-smoke-test").apply { mkdirs() }
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            box.close()
            shouldThrow<IllegalStateException> {
                box.writeStepFile(File(outDir, "closed-shape-should-not-write.step").toPath())
            }
        }

        "close() is idempotent".config(enabled = available) {
            val box = OcctKernel.makeBox(1.0, 1.0, 1.0)
            box.close()
            box.close()
        }

        "an unknown native handle is rejected without crashing the JVM".config(enabled = available) {
            shouldThrow<IllegalStateException> { OcctBridge.nativeShapeCounts(999_999L) }
            // The JVM is still alive to run this: OCCT still answers a completely normal request
            // right afterward, in the same test, on the same thread.
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        "makeBox fails predictably when the native bridge is unavailable".config(enabled = !available) {
            // The only test case in this suite with real signal on a machine without the OCCT dev
            // packages installed (i.e. this one, absent `-Pkstep.occt.require=true`) -- every
            // other OCCT-dependent case above is skipped here via `enabled = available`. Without
            // this case, replacing OcctKernel.makeBox's `throw OcctUnavailableException(...)`
            // guard with e.g. a raw UnsatisfiedLinkError, or removing the guard altogether, would
            // leave `./gradlew check` fully green on exactly this kind of machine.
            shouldThrow<OcctUnavailableException> { OcctKernel.makeBox(1.0, 1.0, 1.0) }
        }

        "occtVersion fails predictably when the native bridge is unavailable".config(enabled = !available) {
            // Same rationale as "makeBox fails predictably..." above, for OcctKernel.occtVersion's
            // own `is OcctAvailability.Unavailable -> throw OcctUnavailableException(...)` branch
            // -- a distinct code path from makeBox's guard, so the case above does not exercise
            // it. Without this case, replacing that throw with e.g. an empty string would leave
            // `./gradlew check` fully green on exactly this kind of machine.
            shouldThrow<OcctUnavailableException> { OcctKernel.occtVersion() }
        }

        "invalid box dimensions are rejected before reaching native code" {
            // Deliberately NOT gated on `available`: this is pure Kotlin-side validation in
            // OcctKernel.makeBox, so it must hold even when the native bridge itself is absent.
            shouldThrow<IllegalArgumentException> { OcctKernel.makeBox(0.0, 1.0, 1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeBox(-1.0, 1.0, 1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeBox(Double.NaN, 1.0, 1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeBox(Double.POSITIVE_INFINITY, 1.0, 1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.makeBox(1e9, 1.0, 1.0) }
        }

        (
            "nativeWriteStep rejects a null path/schema jstring with a Java NullPointerException " +
                "instead of crashing the JVM"
        ).config(enabled = available) {
            // Regression test for a use-after-free/null-deref security finding: OcctBridge is a
            // public object precisely so out-of-module callers (this test included) can reach the
            // raw native methods directly with hostile input a well-behaved OcctKernel/OcctShape
            // caller could never construct on its own -- see OcctBridge.kt's KDoc. A native method
            // has no Kotlin-generated bytecode body, so Kotlin's own compile-time non-null checks
            // on the *declared* `String` parameter types do NOT run here; only an explicit native-
            // side null check (kstep_occt_bridge.cpp's nativeWriteStep) stands between a null
            // jstring and calling GetStringUTFChars(env, nullptr), which is undefined behavior.
            //
            // A plain Kotlin call site (even `null as String`) can't smuggle an actual null value
            // past a declared non-null `String` parameter -- `as` to a non-null target type
            // itself throws immediately on a null receiver, which is Kotlin's OWN null-safety,
            // not the native/JNI check this test exists to exercise. java.lang.reflect.Method
            // sidesteps Kotlin entirely and calls the JVM method directly with a real null
            // argument -- exactly what a plain Java caller (or reflection from any other JVM
            // language) could already do without this test's help.
            val nativeWriteStep =
                OcctBridge::class.java.getDeclaredMethod(
                    "nativeWriteStep",
                    java.lang.Long.TYPE,
                    String::class.java,
                    String::class.java,
                )
            val handle = OcctBridge.nativeMakeBox(1.0, 1.0, 1.0)
            try {
                val nullPathFailure =
                    shouldThrow<InvocationTargetException> {
                        nativeWriteStep.invoke(OcctBridge, handle, null, "AP242DIS")
                    }
                nullPathFailure.cause.shouldBeInstanceOf<NullPointerException>()

                val nullSchemaFailure =
                    shouldThrow<InvocationTargetException> {
                        nativeWriteStep.invoke(OcctBridge, handle, "/tmp/kstep-x.step", null)
                    }
                nullSchemaFailure.cause.shouldBeInstanceOf<NullPointerException>()

                // The JVM is still alive to run this: OCCT still answers a completely normal
                // request right afterward, in the same test, on the same thread.
                val version = OcctKernel.occtVersion()
                version shouldNotBe ""
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
        }

        (
            "concurrent reads racing a close() never observe freed native memory -- either the " +
                "read completes with a correct value, or it is rejected with " +
                "IllegalStateException, but the JVM itself never crashes"
        ).config(enabled = available) {
            // Regression test for a use-after-free security finding: kstep_occt_bridge.cpp used to
            // release its shape-registry lock (g_mutex) before returning the raw TopoDS_Shape*
            // from a lookup, so a concurrent nativeReleaseShape() on another thread could `delete`
            // the shape while a reader was still dereferencing it -- heap corruption / SIGSEGV,
            // not a catchable Java exception. The fix holds g_mutex across the *entire*
            // lookup-through-native-call critical section for every reader, and across
            // erase-then-delete for release, making the two mutually exclusive (see the
            // use-after-free note on g_mutex's declaration in kstep_occt_bridge.cpp).
            //
            // This test cannot deterministically prove the race is gone -- a JVM crash from a
            // regression would kill the whole test process rather than fail a single assertion --
            // but running many shapes through a tight read/close race is the standard way to make
            // a reintroduced race likely to surface as a crash during CI, rather than staying
            // latent until production.
            repeat(200) { i ->
                val box = OcctKernel.makeBox(1.0, 2.0, 3.0)
                val readerError = AtomicReference<Throwable?>(null)
                val ready = CountDownLatch(1)
                val reader =
                    thread(start = true, name = "occt-uaf-reader-$i") {
                        ready.countDown()
                        try {
                            repeat(50) {
                                try {
                                    box.volume shouldBe (6.0 plusOrMinus 1e-6)
                                } catch (_: IllegalStateException) {
                                    // Expected outcome if close() on the other thread won the
                                    // race -- a clean Java exception, not memory corruption.
                                }
                            }
                        } catch (t: Throwable) {
                            readerError.set(t)
                        }
                    }
                ready.await()
                box.close()
                reader.join()
                readerError.get() shouldBe null
            }
            // The JVM is still alive to run this after 200 racing shapes: OCCT still answers a
            // completely normal request right afterward.
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        "writing to a non-existent directory fails cleanly".config(enabled = available) {
            OcctKernel.makeBox(1.0, 1.0, 1.0).use { box ->
                shouldThrow<IllegalArgumentException> {
                    box.writeStepFile(File("build/occt-bridge-smoke-test/does-not-exist-dir/x.step").toPath())
                }
            }
        }
    })
