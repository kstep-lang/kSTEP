package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExternalFeatureException
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.Edges
import dev.kstep.sketch.SketchErrorKind
import dev.kstep.sketch.SketchException
import dev.kstep.sketch.SketchOperation
import dev.kstep.sketch.SketchPointRef
import dev.kstep.sketch.sketch
import dev.kstep.sketch.sketchFeature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Welle M (ADR-0032) through BOTH native bridges: radii as unknowns in solids, separate contours as separate
 * bodies, and sketches as steps of a feature history. Hand-computed values are in the ADR's table.
 */
class OcctSketchExtensionsTest :
    StringSpec({
        val both =
            OcctKernel.availability() is OcctAvailability.Available &&
                PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        fun plateFeature(width: Double = 40.0) =
            sketchFeature(mapOf("width" to width), SketchOperation.Extrude(10.0), label = "plate") { p ->
                val frame = rectangle(0.0, 0.0, p["width"], 20.0)
                val centre = point(20.0, 10.0)
                offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
                circle(centre, 3.0)
            }

        fun twoSquares(gap: Double) =
            sketch {
                rectangle(0.0, 0.0, 10.0, 10.0)
                rectangle(10.0 + gap, 0.0, 10.0, 10.0)
            }.solve()

        "two equal radius holes (diameter + equalRadius) extrude to V = 7434.513 with 8 faces".config(enabled = both) {
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val a = point(10.0, 10.0)
                    val b = point(30.0, 10.0)
                    offset(frame.bottomLeft, a, 10.0, 10.0)
                    offset(frame.bottomLeft, b, 30.0, 10.0)
                    val c1 = circle(a, 5.0, fixedRadius = false)
                    val c2 = circle(b, 5.0, fixedRadius = false)
                    diameter(c1, 6.0)
                    equalRadius(c2, c1)
                }.solve()
            solved.extrude(10.0).use {
                it.volume shouldBe (8000.0 - 180.0 * Math.PI plusOrMinus 1e-6)
                it.volume shouldBe (7434.513 plusOrMinus 1e-3)
                it.topology.faces shouldBe 8
            }
        }

        "a tangent circle with a free radius extrudes to V = 6869.027 with 7 faces".config(enabled = both) {
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val a = point(5.0, 4.0)
                    val b = point(35.0, 4.0)
                    offset(frame.bottomLeft, a, 5.0, 4.0)
                    offset(frame.bottomLeft, b, 35.0, 4.0)
                    val guide = line(a, b, construction = true)
                    val m = point(20.0, 10.0)
                    offset(frame.bottomLeft, m, 20.0, 10.0)
                    tangent(guide, circle(m, 5.0, fixedRadius = false))
                }.solve()
            solved.extrude(10.0).use {
                it.volume shouldBe (6869.027 plusOrMinus 1e-3)
                it.topology.faces shouldBe 7
            }
        }

        "two separate squares extrude to two bodies: V = 2000, 2 solids, 12 faces".config(enabled = both) {
            twoSquares(gap = 10.0).extrude(10.0).use {
                it.volume shouldBe (2000.0 plusOrMinus 1e-6)
                it.topology.solids shouldBe 2
                it.topology.faces shouldBe 12
                OcctKernel.solidSummaries(it).forEach { b -> b.volume shouldBe (1000.0 plusOrMinus 1e-6) }
                OcctKernel.solidSummaries(it).size shouldBe 2
            }
        }

        "several outer contours revolve to several bodies".config(enabled = both) {
            val solved =
                sketch {
                    rectangle(10.0, 0.0, 5.0, 5.0)
                    rectangle(30.0, 0.0, 5.0, 5.0)
                }.solve()
            solved.revolve().use { it.topology.solids shouldBe 2 }
        }

        "an island inside a hole of another contour is its own body".config(enabled = both) {
            val solved =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 40.0)
                    val c = point(20.0, 20.0)
                    offset(frame.bottomLeft, c, 20.0, 20.0)
                    circle(c, 15.0)
                    rectangle(15.0, 15.0, 10.0, 10.0)
                }.solve()
            solved.toRegions().size shouldBe 2
            solved.extrude(5.0).use {
                it.topology.solids shouldBe 2
                it.volume shouldBe ((1600.0 - 225.0 * Math.PI + 100.0) * 5.0 plusOrMinus 1e-3)
            }
        }

        "touching contours are rejected as INVALID_PROFILE".config(enabled = both) {
            val e = shouldThrow<SketchException> { twoSquares(gap = 0.0).extrude(10.0) }
            e.kind shouldBe SketchErrorKind.INVALID_PROFILE
            e.message shouldContain "touch"
        }

        "angle(l1, l2, degrees) solves CCW-positive angles: +30, -45 and 180 degrees".config(
            enabled = PlaneGcsSolver.availability() is PlaneGcsAvailability.Available,
        ) {
            fun solvedEnd(
                degrees: Double,
                startX: Double,
                startY: Double,
            ): Pair<Double, Double> {
                var endRef: SketchPointRef? = null
                val solved =
                    sketch {
                        val origin = point(0.0, 0.0, fixed = true)
                        val east = point(10.0, 0.0, fixed = true)
                        val end = point(startX, startY)
                        endRef = end
                        val l1 = line(origin, east)
                        val l2 = line(origin, end)
                        length(l2, 5.0)
                        angle(l1, l2, degrees)
                    }.solve()
                val p = solved.point(endRef!!)
                return p.x to p.y
            }
            val rad30 = Math.toRadians(30.0)
            val (x1, y1) = solvedEnd(30.0, 4.0, 2.0)
            x1 shouldBe (5.0 * Math.cos(rad30) plusOrMinus 1e-6)
            y1 shouldBe (5.0 * Math.sin(rad30) plusOrMinus 1e-6)
            val rad45 = Math.toRadians(-45.0)
            val (x2, y2) = solvedEnd(-45.0, 3.0, -3.0)
            x2 shouldBe (5.0 * Math.cos(rad45) plusOrMinus 1e-6)
            y2 shouldBe (5.0 * Math.sin(rad45) plusOrMinus 1e-6)
            val (x3, y3) = solvedEnd(180.0, -4.0, 0.5)
            x3 shouldBe (-5.0 plusOrMinus 1e-6)
            y3 shouldBe (0.0 plusOrMinus 1e-6)
        }

        "revolving two regions through a sketch feature gives two bodies".config(enabled = both) {
            val feature =
                sketchFeature(emptyMap(), SketchOperation.Revolve(360.0)) {
                    rectangle(10.0, 0.0, 5.0, 5.0)
                    rectangle(30.0, 0.0, 5.0, 5.0)
                }
            FeatureRebuilder.rebuild(FeatureSequence(listOf(feature))).use { it.final.topology.solids shouldBe 2 }
            val direct =
                sketch {
                    rectangle(10.0, 0.0, 5.0, 5.0)
                    rectangle(30.0, 0.0, 5.0, 5.0)
                }.solve()
            FeatureRebuilder.rebuild(FeatureSequence(listOf(direct.revolveFeature()))).use {
                it.final.topology.solids shouldBe 2
            }
        }

        "regions mirrored about the revolve axis make identical overlapping rings and are refused".config(
            enabled = both,
        ) {
            val solved =
                sketch {
                    rectangle(1.0, 0.0, 1.0, 1.0)
                    rectangle(-2.0, 0.0, 1.0, 1.0)
                }.solve()
            solved.toRegions().size shouldBe 2
            val e = shouldThrow<IllegalArgumentException> { solved.revolve() }
            e.message shouldContain "overlap"
        }

        "toRegion refuses several outer contours but toRegions accepts them".config(enabled = both) {
            val solved = twoSquares(gap = 5.0)
            shouldThrow<SketchException> { solved.toRegion() }.kind shouldBe SketchErrorKind.INVALID_PROFILE
            solved.toRegions().size shouldBe 2
        }

        "fillet on a multi-body shape is refused with a clear message".config(enabled = both) {
            twoSquares(gap = 10.0).extrude(10.0).use { shape ->
                val e = shouldThrow<IllegalArgumentException> { OcctKernel.fillet(shape, listOf(0), 1.0) }
                e.message shouldContain "2 solids"
                e.message shouldContain "ADR-0032"
                shouldThrow<IllegalArgumentException> { OcctKernel.chamfer(shape, listOf(0), 1.0) }
            }
        }

        "a bridge joins two islands into one solid through the boolean".config(enabled = both) {
            twoSquares(gap = 10.0).extrude(10.0).use { islands ->
                OcctKernel.makeBox(30.0, 4.0, 10.0).use { bridge ->
                    OcctKernel.translate(bridge, 0.0, 3.0, 0.0).use { moved ->
                        OcctKernel.boolean(BooleanOperation.UNION, islands, moved).use {
                            it.topology.solids shouldBe 1
                            it.volume shouldBe (2000.0 + 10.0 * 4.0 * 10.0 plusOrMinus 1e-6)
                        }
                    }
                }
            }
        }

        "a sketch feature rebuilds with a changed parameter: 40 -> 60".config(enabled = both) {
            val base = plateFeature()
            FeatureRebuilder.rebuild(FeatureSequence(listOf(base))).use {
                it.final.volume shouldBe (7717.257 plusOrMinus 1e-3)
            }
            val wider = base.withParameter("width", 60.0)
            FeatureRebuilder.rebuild(FeatureSequence(listOf(wider))).use {
                it.final.volume shouldBe (11717.257 plusOrMinus 1e-3)
                it.final.topology.faces shouldBe 7
            }
            (base == base.withOperation(SketchOperation.Extrude(11.0))) shouldBe false
            (base == base.withParameter("width", 40.0)) shouldBe true
            shouldThrow<SketchException> { base.withParameter("height", 1.0) }.kind shouldBe
                SketchErrorKind.INVALID_INPUT
        }

        "a failing sketch feature at index 1 closes the shape built before it".config(enabled = both) {
            val broken =
                sketchFeature(emptyMap(), SketchOperation.Extrude(5.0)) {
                    rectangle(0.0, 0.0, 10.0, 10.0, fixCorner = false)
                }
            val e =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(FeatureSequence(listOf(BoxFeature(5.0, 5.0, 5.0), broken)))
                }
            e.featureIndex shouldBe 1
            e.cause.shouldBeInstanceOf<SketchException>()
            e.closedShapes.size shouldBe 1
            shouldThrow<IllegalStateException> { e.closedShapes[0].volume }
        }

        "a non-sketch failure inside an external feature is wrapped and still closes earlier shapes".config(
            enabled = both,
        ) {
            val boom =
                sketchFeature(emptyMap(), SketchOperation.Extrude(5.0)) { throw UnsupportedOperationException("boom") }
            val e =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(FeatureSequence(listOf(BoxFeature(5.0, 5.0, 5.0), boom)))
                }
            e.cause.shouldBeInstanceOf<ExternalFeatureException>()
            e.message shouldContain "boom"
            shouldThrow<IllegalStateException> { e.closedShapes[0].volume }
        }

        "TrackedEdges accepts a sketch feature as the edge source".config(enabled = both) {
            val plain =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0)) {
                    rectangle(0.0, 0.0, 40.0, 20.0)
                }
            val seq =
                FeatureSequence(
                    listOf(
                        plain,
                        TranslateFeature(0, 1.0, 1.0, 0.0),
                        SelectedFilletFeature(1, TrackedEdges(0, Edges.parallelTo(Direction3.Z), expected = 4), 2.0),
                    ),
                )
            FeatureRebuilder.rebuild(seq).use {
                it.final.volume shouldBe (8000.0 - 4.0 * (1.0 - Math.PI / 4.0) * 4.0 * 10.0 plusOrMinus 1e-3)
            }
        }

        "a boolean can use a sketch feature as an operand".config(enabled = both) {
            val seq =
                FeatureSequence(
                    listOf(plateFeature(), BoxFeature(5.0, 5.0, 5.0), BooleanFeature(BooleanOperation.UNION, 0, 1)),
                )
            FeatureRebuilder.rebuild(seq).use { it.final.topology.solids shouldBe 1 }
        }
    })
