package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnavailableException
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ShapeTopology
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The end-to-end proof for kSTEP's Geometrie Welle 5a (extrude + fillet feature operations on the
 * OCCT bridge, see docs/adr/ADR-0008-occt-feature-operations.adoc): a real profile extruded into
 * a solid, real edges filleted -- singly, chained, and composed with a Geometrie-Welle-1
 * `makeBox` shape -- and every documented validation/DoS/exception-mapping guard exercised
 * against a real OCCT 7.9.2 install.
 *
 * Every OCCT-dependent case is `.config(enabled = available)`, mirroring `OcctBridgeSmokeTest`;
 * pure Kotlin-side validation cases are deliberately ungated so they hold even without OCCT
 * installed. See `OcctBridgeSmokeTest`'s own KDoc for the `-Pkstep.occt.require=true` rationale,
 * which applies identically here (this suite adds no second copy of that guard case).
 */
class OcctFeatureOperationsTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun rectangleProfile() =
            listOf(
                ProfilePoint(0.0, 0.0),
                ProfilePoint(20.0, 0.0),
                ProfilePoint(20.0, 30.0),
                ProfilePoint(0.0, 30.0),
            )

        // T-1
        "extruding a rectangular profile produces the expected topology and volume".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { solid ->
                solid.topology shouldBe ShapeTopology(solids = 1, shells = 1, faces = 6, edges = 12, vertices = 8)
                solid.volume shouldBe (24_000.0 plusOrMinus 1e-6)
            }
        }

        // T-2
        "an extruded solid exports to a real AP242 STEP file".config(enabled = available) {
            val outDir = File("build/occt-feature-ops-test").apply { mkdirs() }
            val target = File(outDir, "extruded-rectangle.step").toPath()
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { solid ->
                val written = solid.writeStepFile(target)
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

        // T-3
        "an L-shaped profile extrudes correctly, proving more than rectangles work".config(enabled = available) {
            val lProfile =
                listOf(
                    ProfilePoint(0.0, 0.0),
                    ProfilePoint(20.0, 0.0),
                    ProfilePoint(20.0, 10.0),
                    ProfilePoint(10.0, 10.0),
                    ProfilePoint(10.0, 20.0),
                    ProfilePoint(0.0, 20.0),
                )
            OcctKernel.extrudeProfile(lProfile, 5.0).use { solid ->
                solid.topology.faces shouldBe 8
                solid.topology.edges shouldBe 18
                solid.volume shouldBe (1_500.0 plusOrMinus 1e-6)
            }
        }

        // T-4
        "a negative extrusion height still yields a positive volume".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), -40.0).use { solid ->
                solid.topology shouldBe ShapeTopology(solids = 1, shells = 1, faces = 6, edges = 12, vertices = 8)
                solid.volume shouldBe (24_000.0 plusOrMinus 1e-6)
            }
        }

        // T-5
        "filleting a single edge of an extruded solid produces the expected topology and volume".config(
            enabled = available,
        ) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    rounded.topology shouldBe
                        ShapeTopology(solids = 1, shells = 1, faces = 7, edges = 15, vertices = 10)
                    rounded.volume shouldBe (23_785.398163397 plusOrMinus 1e-6)
                }
            }
        }

        // T-6
        "filleting a different edge index addresses a genuinely different edge".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 2, radius = 5.0).use { rounded ->
                    rounded.volume shouldBe (23_892.699081699 plusOrMinus 1e-6)
                }
            }
        }

        // T-7
        "fillets chain: a second fillet on an already-filleted result keeps composing".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { onceRounded ->
                    OcctKernel.fillet(onceRounded, edgeIndex = 2, radius = 3.0).use { twiceRounded ->
                        twiceRounded.topology.faces shouldBe 8
                        twiceRounded.volume shouldBe (23_708.141502221 plusOrMinus 1e-6)
                    }
                }
            }
        }

        // T-8
        "fillet composes with a Geometrie Welle 1 makeBox shape, not just extrudeProfile output".config(
            enabled = available,
        ) {
            OcctKernel.makeBox(20.0, 30.0, 40.0).use { box ->
                OcctKernel.fillet(box, edgeIndex = 0, radius = 5.0).use { rounded ->
                    rounded.topology.faces shouldBe 7
                    rounded.volume shouldBe (23_785.398163397 plusOrMinus 1e-6)
                }
            }
        }

        // Multi-edge happy path for the `fillet(shape, edgeIndices: Collection<Int>, radius)`
        // overload -- until this wave, every SUCCESSFUL fillet call in this suite used the
        // single-edge convenience overload (`edgeIndex: Int`); `edgeIndices` with size > 1 was
        // only ever exercised in REJECTION cases (empty / too-many, both rejected by the size
        // guard before any `Add()` runs), leaving the native multi-edge loop
        // (kstep_occt_bridge.cpp's `for (jsize i = 0; i < indexCount; ++i) filletMaker.Add(...)`)
        // and `edgeIndices.toIntArray()` on the Kotlin side completely untested despite the
        // Collection-typed overload being the one this module's own docs (README.adoc) describe as
        // covered. Values measured against a real OCCT 7.9.2 install while writing this test.
        "filleting multiple edges in one call rounds all of them, not just the first".config(
            enabled = available,
        ) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndices = listOf(0, 2), radius = 5.0).use { rounded ->
                    rounded.topology.faces shouldBe 8
                    rounded.volume shouldBe (23_690.08103757985 plusOrMinus 1e-6)
                }
            }
        }

        // Companion to the two-edge case above: all four vertical edges of the same prism filleted
        // in one call, proving the native loop isn't just correct for a small, fixed count.
        "filleting all four edges of a prism in one call rounds every one of them".config(
            enabled = available,
        ) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndices = listOf(0, 1, 2, 3), radius = 3.0).use { rounded ->
                    rounded.topology.faces shouldBe 10
                    rounded.volume shouldBe (23_778.58401269706 plusOrMinus 1e-6)
                }
            }
        }

        // A duplicate index in `edgeIndices` is neither rejected in Kotlin nor natively -- OCCT
        // silently de-duplicates it, producing exactly the single-edge result. Undocumented
        // behavior before this wave; pinned here now as a deliberate, tested outcome rather than
        // an unverified assumption.
        "a duplicate edge index in a multi-edge fillet call is silently de-duplicated by OCCT".config(
            enabled = available,
        ) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndices = listOf(0, 0), radius = 5.0).use { rounded ->
                    rounded.topology.faces shouldBe 7
                    rounded.volume shouldBe (23_785.398163397447 plusOrMinus 1e-6)
                }
            }
        }

        // T-9
        "a filleted solid exports to STEP with a visible cylindrical fillet surface".config(enabled = available) {
            val outDir = File("build/occt-feature-ops-test").apply { mkdirs() }
            val target = File(outDir, "extruded-filleted.step").toPath()
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    val written = rounded.writeStepFile(target)
                    val text = written.toFile().readText()
                    text.contains("MANIFOLD_SOLID_BREP") shouldBe true
                    text.contains("CYLINDRICAL_SURFACE") shouldBe true
                }
            }
        }

        // T-10
        "the source shape stays usable and independent after fillet".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { rounded ->
                    prism.volume shouldBe (24_000.0 plusOrMinus 1e-6)
                    rounded.volume shouldNotBe prism.volume
                }
                // prism.use{} above has NOT closed yet -- close explicitly here, then re-check the
                // already-produced rounded result is still independently alive afterward.
            }
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                val rounded = OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0)
                prism.close()
                rounded.volume shouldBe (23_785.398163397 plusOrMinus 1e-6)
                rounded.close()
            }
        }

        // T-11
        "a fillet radius too large for the local geometry fails cleanly, not the JVM".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                shouldThrow<OcctGeometryException> { OcctKernel.fillet(prism, edgeIndex = 0, radius = 25.0) }
            }
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        // T-12
        "an unsuitable edge on an already-filleted shape fails cleanly, not the JVM".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                OcctKernel.fillet(prism, edgeIndex = 0, radius = 5.0).use { onceRounded ->
                    shouldThrow<OcctGeometryException> {
                        OcctKernel.fillet(onceRounded, edgeIndex = 1, radius = 5.0)
                    }
                }
            }
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        // T-13
        "an out-of-range edge index is rejected".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                shouldThrow<IllegalArgumentException> { OcctKernel.fillet(prism, edgeIndex = -1, radius = 5.0) }
                shouldThrow<IllegalArgumentException> { OcctKernel.fillet(prism, edgeIndex = 12, radius = 5.0) }
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.fillet(prism, edgeIndex = Int.MAX_VALUE, radius = 5.0)
                }
            }
        }

        // T-14
        (
            "invalid index/radius throws IllegalArgumentException, not OcctGeometryException -- " +
                "a regression guard on fillet's exception-mapping order"
        ).config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { prism ->
                val indexFailure = shouldThrow<IllegalArgumentException> { OcctKernel.fillet(prism, -1, 5.0) }
                indexFailure.shouldNotBeInstanceOfOcctGeometryException()
            }
        }

        // T-15
        "degenerate profiles are rejected before or by OCCT, never silently accepted".config(enabled = available) {
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(listOf(ProfilePoint(0.0, 0.0), ProfilePoint(1.0, 1.0)), 10.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(ProfilePoint(0.0, 0.0), ProfilePoint(5.0, 0.0), ProfilePoint(10.0, 0.0)),
                    10.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(
                        ProfilePoint(0.0, 0.0),
                        ProfilePoint(10.0, 10.0),
                        ProfilePoint(10.0, 0.0),
                        ProfilePoint(0.0, 10.0),
                    ),
                    10.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(
                        ProfilePoint(0.0, 0.0),
                        ProfilePoint(0.0, 0.0),
                        ProfilePoint(10.0, 0.0),
                        ProfilePoint(10.0, 10.0),
                    ),
                    10.0,
                )
            }
        }

        "invalid extrusion profiles are rejected before reaching native code" {
            // Deliberately NOT gated on `available`: pure Kotlin-side validation.
            shouldThrow<IllegalArgumentException> { OcctKernel.extrudeProfile(emptyList(), 10.0) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(listOf(ProfilePoint(0.0, 0.0), ProfilePoint(1.0, 1.0)), 10.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile((0 until 513).map { ProfilePoint(it.toDouble(), 0.0) }, 10.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(ProfilePoint(Double.NaN, 0.0), ProfilePoint(1.0, 0.0), ProfilePoint(1.0, 1.0)),
                    10.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(
                        ProfilePoint(Double.POSITIVE_INFINITY, 0.0),
                        ProfilePoint(1.0, 0.0),
                        ProfilePoint(1.0, 1.0),
                    ),
                    10.0,
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(ProfilePoint(1e9, 0.0), ProfilePoint(1.0, 0.0), ProfilePoint(1.0, 1.0)),
                    10.0,
                )
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.extrudeProfile(rectangleProfile(), 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.extrudeProfile(rectangleProfile(), Double.NaN) }
        }

        // Regression guard for the near-coincident-point gap: an EXACT (`==`) comparison only
        // catches a bit-for-bit duplicate, but `BRepBuilderAPI_MakePolygon` silently drops any
        // point within Precision::Confusion() (1e-7) of its predecessor -- measured on OCCT 7.9.2:
        // a 5-point profile with a d=1e-12..1e-7 "near duplicate" quietly became the 4-point box,
        // with BOTH BRepCheck_Analyzer::IsValid() and Mass() reporting a perfectly valid, non-zero
        // result, so neither mandatory post-check would have caught it. This case is deliberately
        // NOT gated on `available`: OcctKernel.extrudeProfile's own distance check
        // (PROFILE_POINT_COINCIDENCE_TOLERANCE) must reject it before any native call is made.
        (
            "near-coincident consecutive profile points within the OCCT confusion tolerance are rejected, " +
                "not just bit-exact duplicates"
        ) {
            listOf(1e-12, 1e-9, 1e-8, 1e-7).forEach { d ->
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.extrudeProfile(
                        listOf(
                            ProfilePoint(0.0, 0.0),
                            ProfilePoint(d, 0.0),
                            ProfilePoint(20.0, 0.0),
                            ProfilePoint(20.0, 30.0),
                            ProfilePoint(0.0, 30.0),
                        ),
                        40.0,
                    )
                }
            }
            // -0.0 vs 0.0: ordinary subtraction treats them as equal (dx/dy come out exactly 0.0),
            // so this is caught as one instance of the distance check above, not a special case --
            // unlike `ProfilePoint`'s data-class `equals()` or `Double.equals()`, which treat -0.0
            // and 0.0 as UNEQUAL and would miss it.
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfile(
                    listOf(ProfilePoint(0.0, 0.0), ProfilePoint(-0.0, -0.0), ProfilePoint(10.0, 0.0)),
                    10.0,
                )
            }
        }

        // Companion to the rejection case above: a point safely beyond
        // PROFILE_POINT_COINCIDENCE_TOLERANCE is a genuinely different, valid profile and must NOT
        // be rejected -- and, unlike the pure-validation case above, this needs a real OCCT call to
        // prove the fix doesn't just reject everything near the old exact-duplicate check. Mirrors
        // the exact reproduction from the review finding this guards against: d=2e-7 on the same
        // 5-point profile correctly produces the 5-vertex pentagon prism (10 vertices, 15 edges, 7
        // faces), not the 4-point box a d <= 1e-7 "near duplicate" collapses into.
        "a profile point safely beyond the coincidence tolerance extrudes as its own distinct vertex".config(
            enabled = available,
        ) {
            val pentagonWithNearPoint =
                listOf(
                    ProfilePoint(0.0, 0.0),
                    ProfilePoint(2e-7, 0.0),
                    ProfilePoint(20.0, 0.0),
                    ProfilePoint(20.0, 30.0),
                    ProfilePoint(0.0, 30.0),
                )
            OcctKernel.extrudeProfile(pentagonWithNearPoint, 40.0).use { solid ->
                solid.topology shouldBe ShapeTopology(solids = 1, shells = 1, faces = 7, edges = 15, vertices = 10)
            }
        }

        (
            "invalid fillet arguments are rejected before reaching native code, proven by using an " +
                "already-closed shape: if validation ran after checkOpen()/withHandle, these would " +
                "throw IllegalStateException instead"
        ).config(enabled = available) {
            // OcctShape's constructor is `internal` (module-scoped to kstep-geometry) precisely so
            // only OcctKernel can hand one out -- kstep-tests cannot fabricate one without a real
            // native call, so this case (unlike the pure-Kotlin cases above) needs OCCT to obtain
            // a shape reference at all, even though the assertions below prove the validation
            // itself never reaches the native/open-shape layer.
            val closedShape = OcctKernel.makeBox(1.0, 1.0, 1.0)
            closedShape.close()
            shouldThrow<IllegalArgumentException> {
                OcctKernel.fillet(closedShape, emptyList(), 5.0)
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.fillet(closedShape, (0 until 65).toList(), 5.0)
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.fillet(closedShape, 0, 0.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.fillet(closedShape, 0, -1.0) }
            shouldThrow<IllegalArgumentException> { OcctKernel.fillet(closedShape, 0, Double.NaN) }
            shouldThrow<IllegalArgumentException> { OcctKernel.fillet(closedShape, -1, 5.0) }
        }

        // T-17
        (
            "extrudeProfile fails predictably when the native bridge is unavailable -- fillet's " +
                "identical OcctUnavailableException branch cannot be exercised through the public " +
                "API here at all: obtaining any OcctShape to pass to fillet already requires the " +
                "native bridge, so there is no way to reach fillet's own availability() check " +
                "without it; that branch is exercised implicitly by every fillet-gated case above " +
                "when OCCT IS available"
        ).config(enabled = !available) {
            shouldThrow<OcctUnavailableException> { OcctKernel.extrudeProfile(rectangleProfile(), 40.0) }
        }

        // T-18
        "fillet rejects an already-closed shape".config(enabled = available) {
            val prism = OcctKernel.extrudeProfile(rectangleProfile(), 40.0)
            prism.close()
            shouldThrow<IllegalStateException> { OcctKernel.fillet(prism, 0, 5.0) }
        }

        // T-19
        "the native bridge independently enforces its own bounds, reachable via reflection".config(
            enabled = available,
        ) {
            val nativeExtrude =
                OcctBridge::class.java.getDeclaredMethod(
                    "nativeExtrudeProfile",
                    DoubleArray::class.java,
                    java.lang.Double.TYPE,
                )
            val nativeFillet =
                OcctBridge::class.java.getDeclaredMethod(
                    "nativeFilletEdges",
                    java.lang.Long.TYPE,
                    IntArray::class.java,
                    java.lang.Double.TYPE,
                )

            val nullProfileFailure =
                shouldThrow<InvocationTargetException> {
                    nativeExtrude.invoke(OcctBridge, null, 10.0)
                }
            nullProfileFailure.cause shouldNotBe null
            (nullProfileFailure.cause is NullPointerException) shouldBe true

            val handle = OcctBridge.nativeMakeBox(1.0, 1.0, 1.0)
            try {
                val nullIndicesFailure =
                    shouldThrow<InvocationTargetException> {
                        nativeFillet.invoke(OcctBridge, handle, null, 1.0)
                    }
                (nullIndicesFailure.cause is NullPointerException) shouldBe true

                val oddLengthFailure =
                    shouldThrow<InvocationTargetException> {
                        nativeExtrude.invoke(OcctBridge, doubleArrayOf(0.0, 0.0, 1.0), 1.0)
                    }
                (oddLengthFailure.cause is IllegalArgumentException) shouldBe true

                val tooManyIndicesFailure =
                    shouldThrow<InvocationTargetException> {
                        nativeFillet.invoke(OcctBridge, handle, IntArray(513), 1.0)
                    }
                (tooManyIndicesFailure.cause is IllegalArgumentException) shouldBe true
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }

            // The JVM is still alive to run this after four raw native hostile-input calls.
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        // Native-side companion to the pure-Kotlin near-coincident-point regression tests above:
        // OcctKernel.extrudeProfile's own PROFILE_POINT_COINCIDENCE_TOLERANCE check means a caller
        // going through the public API never reaches nativeExtrudeProfile with a near-duplicate
        // point at all -- so, exactly like T-19 above, reflection is used to call the native method
        // directly and prove kstep_occt_bridge.cpp's OWN Precision::SquareConfusion() check (not
        // just the Kotlin-side mirror of it) independently rejects the same reproduction from the
        // review finding this guards against, rather than silently collapsing the polygon.
        (
            "the native bridge independently rejects near-coincident consecutive profile points, not " +
                "just bit-exact duplicates"
        ).config(enabled = available) {
            val nativeExtrude =
                OcctBridge::class.java.getDeclaredMethod(
                    "nativeExtrudeProfile",
                    DoubleArray::class.java,
                    java.lang.Double.TYPE,
                )
            listOf(1e-12, 1e-9, 1e-8, 1e-7).forEach { d ->
                val failure =
                    shouldThrow<InvocationTargetException> {
                        nativeExtrude.invoke(
                            OcctBridge,
                            doubleArrayOf(0.0, 0.0, d, 0.0, 20.0, 0.0, 20.0, 30.0, 0.0, 30.0),
                            40.0,
                        )
                    }
                (failure.cause is IllegalArgumentException) shouldBe true
            }
            // Above the tolerance, the native call succeeds and builds the full 5-vertex prism --
            // proving the fix rejects only what OCCT itself would silently collapse, not a wider
            // band around it.
            val handle =
                nativeExtrude.invoke(
                    OcctBridge,
                    doubleArrayOf(0.0, 0.0, 2e-7, 0.0, 20.0, 0.0, 20.0, 30.0, 0.0, 30.0),
                    40.0,
                ) as Long
            OcctBridge.nativeReleaseShape(handle)
        }

        // T-20
        "MAX_FILLET_INPUT_FACES rejects a too-large input before OCCT does any real work".config(
            enabled = available,
        ) {
            // A convex 250-gon (points on a circle) -- simple/non-self-intersecting by
            // construction, unlike e.g. a zigzag, which would self-cross when closed and fail
            // extrudeProfile's own degenerate-profile check before ever reaching the fillet guard
            // this case exists to prove.
            val bigProfile =
                (0 until 250).map { i ->
                    val angle = 2.0 * Math.PI * i / 250
                    ProfilePoint(100.0 * kotlin.math.cos(angle), 100.0 * kotlin.math.sin(angle))
                }
            OcctKernel.extrudeProfile(bigProfile, 5.0).use { bigSolid ->
                bigSolid.topology.faces shouldBe (250 + 2)
                val start = System.nanoTime()
                val failure =
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.fillet(bigSolid, 0, 1e-6)
                    }
                val elapsedMs = (System.nanoTime() - start) / 1_000_000
                failure.message?.contains("200") shouldBe true
                (elapsedMs < 5_000) shouldBe true
            }
        }

        // Companion to the rejection case above: a shape right AT the MAX_FILLET_INPUT_FACES
        // boundary (200 faces, a 198-point circle) must actually succeed, and quickly -- proving
        // the cap is a real DoS guard, not an accidental blanket rejection. Timing measured for
        // real on this machine as part of this wave (see docs/adr/ADR-0008-occt-feature-operations.adoc's
        // DoS measurement table for the fuller 6..2002-face series this single point corroborates).
        "a fillet input right at the MAX_FILLET_INPUT_FACES boundary succeeds well within budget".config(
            enabled = available,
        ) {
            // A 198-point "gear"/star profile (alternating outer/inner radius), not a smooth
            // circle: a smooth circle's per-vertex turn angle is so shallow (near 180 degrees at
            // 198 points) that BRepFilletAPI_MakeFillet failed to build a valid fillet there at
            // all (measured while writing this test) -- an artifact of that specific profile
            // shape, not of MAX_FILLET_INPUT_FACES. The zigzag below has genuine, sharp per-vertex
            // corners throughout, which fillet at a small radius handles the same way it already
            // does for the rectangle profile's 90-degree corners elsewhere in this suite.
            val boundaryProfile =
                (0 until 198).map { i ->
                    val angle = 2.0 * Math.PI * i / 198
                    val radius = if (i % 2 == 0) 100.0 else 90.0
                    ProfilePoint(radius * kotlin.math.cos(angle), radius * kotlin.math.sin(angle))
                }
            OcctKernel.extrudeProfile(boundaryProfile, 5.0).use { boundarySolid ->
                boundarySolid.topology.faces shouldBe OcctKernel.MAX_FILLET_INPUT_FACES
                val start = System.nanoTime()
                OcctKernel.fillet(boundarySolid, 0, 0.5).use { rounded ->
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    rounded.topology.faces shouldBe (OcctKernel.MAX_FILLET_INPUT_FACES + 1)
                    (elapsedMs < 5_000) shouldBe true
                }
            }
        }

        // T-21
        (
            "concurrent reads of a fillet result racing a close() never observe freed native " +
                "memory -- either the read completes correctly, or IllegalStateException, but the " +
                "JVM itself never crashes"
        ).config(enabled = available) {
            repeat(200) { i ->
                val prism = OcctKernel.extrudeProfile(rectangleProfile(), 40.0)
                val rounded = OcctKernel.fillet(prism, 0, 5.0)
                prism.close()
                val readerError = AtomicReference<Throwable?>(null)
                val ready = CountDownLatch(1)
                val reader =
                    thread(start = true, name = "occt-fillet-uaf-reader-$i") {
                        ready.countDown()
                        try {
                            repeat(20) {
                                try {
                                    rounded.volume shouldBe (23_785.398163397 plusOrMinus 1e-6)
                                } catch (_: IllegalStateException) {
                                    // Expected outcome if close() on the other thread won the race.
                                }
                            }
                        } catch (t: Throwable) {
                            readerError.set(t)
                        }
                    }
                ready.await()
                rounded.close()
                reader.join()
                readerError.get() shouldBe null
            }
            val version = OcctKernel.occtVersion()
            version shouldNotBe ""
        }

        // T-22
        "edge indexing is deterministic across identical rebuilds".config(enabled = available) {
            OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { first ->
                OcctKernel.extrudeProfile(rectangleProfile(), 40.0).use { second ->
                    OcctKernel.fillet(first, 0, 5.0).use { firstRounded ->
                        OcctKernel.fillet(second, 0, 5.0).use { secondRounded ->
                            firstRounded.volume shouldBe secondRounded.volume
                        }
                    }
                }
            }
        }
    })

private fun Throwable.shouldNotBeInstanceOfOcctGeometryException() {
    (this is OcctGeometryException) shouldBe false
}
