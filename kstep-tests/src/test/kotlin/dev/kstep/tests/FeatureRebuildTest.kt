package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctGeometryException
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.FilletFeature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The end-to-end proof for kSTEP's parametric-history foundation wave (Geometrie Welle 5b, Teil 1
 * -- see docs/adr/ADR-0015-parametric-feature-history-foundation.adoc): a [FeatureSequence]
 * replayed against the real, unmodified [OcctKernel] via [FeatureRebuilder.rebuild], proving the
 * actual "change a parameter, rebuild, get a new measured result" claim this foundation exists to
 * support -- not just that the plumbing compiles.
 *
 * Every OCCT-dependent case is `.config(enabled = available)`, mirroring
 * `OcctFeatureOperationsTest`; the pure Kotlin-side structural-validation cases (T-6..T-9) are
 * deliberately ungated, since [FeatureRebuilder.rebuild] validates a [FeatureSequence]'s shape
 * before any native call is made.
 */
class FeatureRebuildTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun rectangleProfile() =
            listOf(
                ProfilePoint(0.0, 0.0),
                ProfilePoint(20.0, 0.0),
                ProfilePoint(20.0, 30.0),
                ProfilePoint(0.0, 30.0),
            )

        fun extrudeThenFillet(radius: Double) =
            FeatureSequence(
                listOf(
                    ExtrudeFeature(rectangleProfile(), 40.0),
                    FilletFeature(baseIndex = 0, edgeIndices = listOf(0), radius = radius),
                ),
            )

        // T-1: regression-pins against OcctFeatureOperationsTest's own already-measured T-1/T-5
        // values -- proves rebuild() reproduces OcctKernel's direct-call results exactly, not just
        // "some" volume.
        "rebuilding an extrude-then-fillet sequence reproduces the directly-measured OCCT result".config(
            enabled = available,
        ) {
            FeatureRebuilder.rebuild(extrudeThenFillet(radius = 5.0)).use { result ->
                result.shapes[0].volume shouldBe (24_000.0 plusOrMinus 1e-6)
                result.final.volume shouldBe (23_785.398163397 plusOrMinus 1e-6)
            }
        }

        // T-2, the core proof this whole wave exists for: change one Feature's value, rebuild, get
        // a genuinely different result -- while an EARLIER, untouched feature in the same sequence
        // stays bit-for-bit identical across both rebuilds.
        "changing a fillet radius and rebuilding changes the result while earlier features stay identical".config(
            enabled = available,
        ) {
            val firstVolume: Double
            FeatureRebuilder.rebuild(extrudeThenFillet(radius = 5.0)).use { firstResult ->
                firstResult.shapes[0].volume shouldBe (24_000.0 plusOrMinus 1e-6)
                firstVolume = firstResult.final.volume
            }
            FeatureRebuilder.rebuild(extrudeThenFillet(radius = 8.0)).use { secondResult ->
                secondResult.shapes[0].volume shouldBe (24_000.0 plusOrMinus 1e-6)
                secondResult.final.volume shouldNotBe firstVolume
            }
        }

        // T-3: chained fillets (a FilletFeature based on an earlier FilletFeature's result), cross-
        // checked against OcctFeatureOperationsTest's own already-measured T-7 chain value to prove
        // replay reproduces eager direct calls exactly, not just "some" chained result.
        "chained fillets in a sequence reproduce the directly-measured chained OCCT result".config(
            enabled = available,
        ) {
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rectangleProfile(), 40.0),
                        FilletFeature(baseIndex = 0, edgeIndices = listOf(0), radius = 5.0),
                        FilletFeature(baseIndex = 1, edgeIndices = listOf(2), radius = 3.0),
                    ),
                )
            FeatureRebuilder.rebuild(sequence).use { result ->
                result.final.volume shouldBe (23_708.141502221 plusOrMinus 1e-6)
            }
        }

        // T-4: a radius that succeeds gets changed to one OcctFeatureOperationsTest's own T-11
        // already verified fails geometrically (too large for this local geometry) -- rebuild()
        // must wrap that as a FeatureRebuildException naming the failing position, not let it
        // escape as a bare OcctGeometryException. Also proves the "every shape built so far is
        // closed before rebuild() throws" guarantee for THIS failure -- not just for T-10's
        // fully-successful case -- by asserting that the earlier-built extrude shape
        // (`closedShapes[0]`, the one shape built before the fillet at index 1 failed) is actually
        // closed, not merely documented as closed: a closed OcctShape throws IllegalStateException
        // on any access besides close(), exactly like T-10's own closure check on a successful
        // result.
        "a fillet radius too large for the geometry fails as a FeatureRebuildException naming the position".config(
            enabled = available,
        ) {
            FeatureRebuilder.rebuild(extrudeThenFillet(radius = 5.0)).close()

            val failure =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(extrudeThenFillet(radius = 25.0))
                }
            failure.featureIndex shouldBe 1
            (failure.cause is OcctGeometryException) shouldBe true
            failure.closedShapes.size shouldBe 1
            shouldThrow<IllegalStateException> { failure.closedShapes[0].volume }
        }

        // T-5: companion to T-4 -- a structurally invalid radius (rejected by OcctKernel.fillet's
        // own IllegalArgumentException guard, never reaching OCCT at all) is wrapped exactly the
        // same way, proving FeatureRebuilder's deliberate choice to treat both exception classes
        // uniformly (see FeatureRebuilder.rebuild's KDoc). Same closedShapes closure check as T-4,
        // for the OTHER wrapped exception class -- proving the cleanup-on-failure guarantee holds
        // on both catch paths, not just one of them.
        (
            "a structurally invalid fillet radius fails as a FeatureRebuildException with an " +
                "IllegalArgumentException cause"
        ).config(
            enabled = available,
        ) {
            val failure =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(extrudeThenFillet(radius = -1.0))
                }
            failure.featureIndex shouldBe 1
            (failure.cause is IllegalArgumentException) shouldBe true
            failure.closedShapes.size shouldBe 1
            shouldThrow<IllegalStateException> { failure.closedShapes[0].volume }
        }

        // T-6..T-9: pure Kotlin-side structural validation -- deliberately NOT gated on `available`,
        // exactly like OcctKernel.extrudeProfile's own ungated validation cases in
        // OcctFeatureOperationsTest, since none of these reach a native call at all.
        "an empty sequence is rejected before reaching native code" {
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(FeatureSequence(emptyList())) }
        }

        "a sequence longer than MAX_SEQUENCE_LENGTH is rejected before reaching native code" {
            val tooLong = List(FeatureRebuilder.MAX_SEQUENCE_LENGTH + 1) { BoxFeature(1.0, 1.0, 1.0) }
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(FeatureSequence(tooLong)) }
        }

        "a fillet baseIndex referring to its own position is rejected before reaching native code" {
            val sequence =
                FeatureSequence(
                    listOf(
                        BoxFeature(1.0, 1.0, 1.0),
                        FilletFeature(baseIndex = 1, edgeIndices = listOf(0), radius = 0.1),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(sequence) }
        }

        "a fillet baseIndex referring to a later position is rejected before reaching native code" {
            val sequence =
                FeatureSequence(
                    listOf(
                        FilletFeature(baseIndex = 1, edgeIndices = listOf(0), radius = 0.1),
                        BoxFeature(1.0, 1.0, 1.0),
                    ),
                )
            shouldThrow<IllegalArgumentException> { FeatureRebuilder.rebuild(sequence) }
        }

        // T-10: FeatureRebuildResult.close() must close EVERY shape it built, not just `final` --
        // otherwise the intermediate extrude result would leak a native handle on every rebuild.
        "FeatureRebuildResult.close() closes every shape it built, not just the final one".config(
            enabled = available,
        ) {
            val result = FeatureRebuilder.rebuild(extrudeThenFillet(radius = 5.0))
            result.close()
            shouldThrow<IllegalStateException> { result.shapes[0].volume }
        }
    })
