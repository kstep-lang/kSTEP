package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.GenerationSources
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.PointAt
import dev.kstep.geometry.ProfileEdge
import dev.kstep.geometry.ProfileLoop
import dev.kstep.geometry.ProfilePoint
import dev.kstep.geometry.ProfileRegion
import dev.kstep.geometry.ProfileSegment
import dev.kstep.geometry.ProfileVertex
import dev.kstep.geometry.RegionEdge
import dev.kstep.geometry.RegionVertex
import dev.kstep.geometry.RevolveAxis
import dev.kstep.geometry.SegmentAt
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.ExtrudeFeature
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.RevolveFeature
import dev.kstep.geometry.feature.SelectedChamferFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.and
import dev.kstep.sketch.SketchCorner
import dev.kstep.sketch.SketchEdgeOf
import dev.kstep.sketch.SketchErrorKind
import dev.kstep.sketch.SketchException
import dev.kstep.sketch.SketchOperation
import dev.kstep.sketch.SketchSources
import dev.kstep.sketch.sketchFeature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.abs

// Allowed growth of the global native shape counter during the leak test (a real leak adds ~100).
private const val LEAK_SLACK = 10

/**
 * Welle N (ADR-0033): persistent naming of the edges a sweep generates. Hand-computed values:
 * a 40x20x10 plate has V = 8000; rounding its four vertical edges at r = 2 removes 4 * (1 - pi/4) * 2^2 * 10 =
 * 34.336 -> 7965.664 with 10 faces. At width 60: 12000 - 34.336 = 11965.664, still 10 faces.
 */
class OcctGeneratedNamingTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val both = occt && PlaneGcsSolver.availability() is PlaneGcsAvailability.Available
        val rect =
            listOf(ProfilePoint(0.0, 0.0), ProfilePoint(40.0, 0.0), ProfilePoint(40.0, 20.0), ProfilePoint(0.0, 20.0))
        val cornerSources = GenerationSources.profileVertices(4)
        val z = Direction3.Z

        fun plate(width: Double = 40.0) =
            sketchFeature(mapOf("width" to width), SketchOperation.Extrude(10.0), label = "plate") { p ->
                rectangle(0.0, 0.0, p["width"], 20.0)
            }

        fun loopOf(vararg xy: Double) =
            ProfileLoop(
                (0 until xy.size / 2).map { i ->
                    val j = (i + 1) % (xy.size / 2)
                    ProfileSegment.Line(ProfilePoint(xy[2 * i], xy[2 * i + 1]), ProfilePoint(xy[2 * j], xy[2 * j + 1]))
                },
            )

        "extruded rectangle: SIDE of the four corners are the four edges parallel to Z".config(enabled = occt) {
            OcctKernel.extrudeProfileNamed(rect, 10.0, cornerSources).use { named ->
                val edges = OcctKernel.generatedEdges(named, cornerSources, expected = 4)
                edges shouldBe OcctKernel.selectEdges(named.shape, Edges.parallelTo(z))
                cornerSources.forEachIndexed { i, s ->
                    val e = named.shape.edges[OcctKernel.generatedEdges(named, listOf(s)).single()]
                    e.start.x shouldBe (rect[i].x plusOrMinus 1e-9)
                    e.start.y shouldBe (rect[i].y plusOrMinus 1e-9)
                }
            }
        }

        "START and END of a profile edge are its copies at z = 0 and z = 10".config(enabled = occt) {
            val sources = listOf(ProfileEdge(0))
            OcctKernel.extrudeProfileNamed(rect, 10.0, sources).use { named ->
                val start = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.START).single()]
                val end = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.END).single()]
                start.start.z shouldBe (0.0 plusOrMinus 1e-9)
                start.end.z shouldBe (0.0 plusOrMinus 1e-9)
                end.start.z shouldBe (10.0 plusOrMinus 1e-9)
                end.end.z shouldBe (10.0 plusOrMinus 1e-9)
            }
        }

        "with negative height START stays at z = 0 and END lies at z = -10".config(enabled = occt) {
            val sources = listOf(ProfileEdge(2))
            OcctKernel.extrudeProfileNamed(rect, -10.0, sources).use { named ->
                val start = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.START).single()]
                val end = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.END).single()]
                start.start.z shouldBe (0.0 plusOrMinus 1e-9)
                end.start.z shouldBe (-10.0 plusOrMinus 1e-9)
            }
        }

        "a role that does not fit the source kind is an argument error".config(enabled = occt) {
            OcctKernel.extrudeProfileNamed(rect, 10.0, listOf(ProfileVertex(0), ProfileEdge(1))).use { named ->
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.generatedEdges(named, listOf(ProfileVertex(0)), GeneratedRole.START)
                }.message!! shouldContain "does not fit"
                shouldThrow<IllegalArgumentException> {
                    OcctKernel.generatedEdges(named, listOf(ProfileEdge(1)), GeneratedRole.SIDE)
                }
            }
        }

        "a clockwise region names the same logical corners (the normalisation is undone)".config(enabled = occt) {
            // clockwise: (0,0) -> (0,20) -> (40,20) -> (40,0)
            val cw = ProfileRegion(loopOf(0.0, 0.0, 0.0, 20.0, 40.0, 20.0, 40.0, 0.0))
            val sources = GenerationSources.loopVertices(cw, 0)
            OcctKernel.extrudeRegionNamed(cw, 10.0, sources).use { named ->
                named.shape.volume shouldBe (8000.0 plusOrMinus 1e-6)
                val expected = listOf(0.0 to 0.0, 0.0 to 20.0, 40.0 to 20.0, 40.0 to 0.0)
                sources.forEachIndexed { i, s ->
                    val e = named.shape.edges[OcctKernel.generatedEdges(named, listOf(s)).single()]
                    e.start.x shouldBe (expected[i].first plusOrMinus 1e-9)
                    e.start.y shouldBe (expected[i].second plusOrMinus 1e-9)
                }
            }
        }

        "a rectangular hole: its four corners name four inner vertical edges, distinct from the outer ones".config(
            enabled = occt,
        ) {
            val region =
                ProfileRegion(
                    loopOf(0.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0),
                    listOf(loopOf(15.0, 5.0, 25.0, 5.0, 25.0, 15.0, 15.0, 15.0)),
                )
            val inner = GenerationSources.loopVertices(region, 1)
            val outer = GenerationSources.loopVertices(region, 0)
            OcctKernel.extrudeRegionNamed(region, 10.0, inner + outer).use { named ->
                val innerEdges = OcctKernel.generatedEdges(named, inner, expected = 4)
                val outerEdges = OcctKernel.generatedEdges(named, outer, expected = 4)
                innerEdges.intersect(outerEdges.toSet()) shouldBe emptySet()
                innerEdges.forEach {
                    named.shape.edges[it]
                        .start.x shouldBe (20.0 plusOrMinus 5.0 + 1e-9)
                }
                named.shape.volume shouldBe (8000.0 - 1000.0 plusOrMinus 1e-6)
            }
        }

        "RegionEdge of an arc segment finds its START and END copies".config(enabled = occt) {
            val arc =
                ProfileSegment.Arc(
                    ProfilePoint(10.0, 0.0),
                    ProfilePoint(
                        10.0 + 5.0 / 1.4142135623730951,
                        5.0 / 1.4142135623730951 * 0 + 2.0,
                    ),
                    ProfilePoint(10.0, 4.0),
                )
            val region =
                ProfileRegion(
                    ProfileLoop(
                        listOf(
                            ProfileSegment.Line(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0)),
                            arc,
                            ProfileSegment.Line(ProfilePoint(10.0, 4.0), ProfilePoint(0.0, 4.0)),
                            ProfileSegment.Line(ProfilePoint(0.0, 4.0), ProfilePoint(0.0, 0.0)),
                        ),
                    ),
                )
            val sources = listOf(RegionEdge(0, 1))
            OcctKernel.extrudeRegionNamed(region, 3.0, sources).use { named ->
                val s = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.START).single()]
                val e = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.END).single()]
                s.kind.name shouldBe "CIRCLE"
                e.kind.name shouldBe "CIRCLE"
                s.start.z shouldBe (0.0 plusOrMinus 1e-9)
                e.start.z shouldBe (3.0 plusOrMinus 1e-9)
            }
        }

        "partial revolve: a corner names the arc swept from it, an edge its START and END copies".config(
            enabled = occt,
        ) {
            val profile =
                listOf(
                    ProfilePoint(10.0, 0.0),
                    ProfilePoint(20.0, 0.0),
                    ProfilePoint(20.0, 5.0),
                    ProfilePoint(10.0, 5.0),
                )
            val sources = listOf(ProfileVertex(0), ProfileEdge(0))
            OcctKernel.revolveProfileNamed(profile, 90.0, RevolveAxis.Y, sources).use { named ->
                val arc = named.shape.edges[OcctKernel.generatedEdges(named, listOf(ProfileVertex(0))).single()]
                arc.kind.name shouldBe "CIRCLE"
                arc.radius!! shouldBe (10.0 plusOrMinus 1e-9)
                val start =
                    named.shape.edges[
                        OcctKernel
                            .generatedEdges(
                                named,
                                listOf(ProfileEdge(0)),
                                GeneratedRole.START,
                            ).single(),
                    ]
                val end =
                    named.shape.edges[
                        OcctKernel
                            .generatedEdges(
                                named,
                                listOf(ProfileEdge(0)),
                                GeneratedRole.END,
                            ).single(),
                    ]
                start.start.z shouldBe (0.0 plusOrMinus 1e-9)
                abs(end.start.x) shouldBe (0.0 plusOrMinus 1e-9)
                abs(end.end.x) shouldBe (0.0 plusOrMinus 1e-9)
            }
        }

        "full revolve: a vertex on the axis has no usable image and fails with candidates".config(enabled = occt) {
            val triangle = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
            val sources = GenerationSources.profileVertices(3)
            OcctKernel.revolveProfileNamed(triangle, 360.0, RevolveAxis.Y, sources).use { named ->
                val e = shouldThrow<EdgeNamingException> { OcctKernel.generatedEdges(named, sources) }
                e.unresolved shouldBe listOf(ProfileVertex(0), ProfileVertex(2))
                e.generatedCandidates.keys.size shouldBe 3
                e.candidates.isNotEmpty() shouldBe true
                e.message!! shouldContain "ProfileVertex(0)"
                OcctKernel.generatedEdges(named, listOf(ProfileVertex(1))).size shouldBe 1
            }
        }

        "the named and the plain build return identical edge lists (one construction path)".config(enabled = occt) {
            val region =
                ProfileRegion(
                    loopOf(0.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0),
                    listOf(loopOf(15.0, 5.0, 25.0, 5.0, 25.0, 15.0, 15.0, 15.0)),
                )
            OcctKernel.extrudeRegion(region, 10.0).use { plain ->
                OcctKernel.extrudeRegionNamed(region, 10.0, GenerationSources.loopVertices(region, 0)).use { named ->
                    named.shape.edges shouldBe plain.edges
                    named.shape.faces shouldBe plain.faces
                }
            }
            OcctKernel.extrudeProfile(rect, 10.0).use { plain ->
                OcctKernel.extrudeProfileNamed(rect, 10.0, cornerSources).use { named ->
                    named.shape.edges shouldBe plain.edges
                }
            }
            val ring =
                listOf(
                    ProfilePoint(10.0, 0.0),
                    ProfilePoint(20.0, 0.0),
                    ProfilePoint(20.0, 5.0),
                    ProfilePoint(10.0, 5.0),
                )
            OcctKernel.revolveProfile(ring, 90.0).use { plain ->
                OcctKernel.revolveProfileNamed(ring, 90.0, RevolveAxis.Y, listOf(ProfileVertex(0))).use { named ->
                    named.shape.edges shouldBe plain.edges
                }
            }
        }

        "kernel overloads: fillet and chamfer on generated corners (V = 7965.664 / 7948.0)".config(enabled = occt) {
            OcctKernel.extrudeProfileNamed(rect, 10.0, cornerSources).use { named ->
                OcctKernel.fillet(named, cornerSources, 2.0, expected = 4).use {
                    it.volume shouldBe
                        (7965.664 plusOrMinus 1e-3)
                }
                // chamfer d = 2 removes 4 * (2*2/2) * 10 = 80
                OcctKernel.chamfer(named, cornerSources, 2.0, expected = 4).use {
                    it.volume shouldBe
                        (7920.0 plusOrMinus 1e-6)
                }
            }
        }

        "named sweep arguments are validated before any native call".config(enabled = occt) {
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfileNamed(
                    rect,
                    10.0,
                    listOf(ProfileVertex(4)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfileNamed(rect, 10.0, listOf(ProfileVertex(0), ProfileVertex(0)))
            }
            shouldThrow<IllegalArgumentException> { OcctKernel.extrudeProfileNamed(rect, 10.0, emptyList()) }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfileNamed(
                    rect,
                    10.0,
                    listOf(RegionVertex(0, 0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfileNamed(
                    rect,
                    10.0,
                    List(OcctKernel.MAX_NAMING_SOURCES + 1) { PointAt(it.toDouble(), 0.0) },
                )
            }
        }

        "PointAt and SegmentAt match by coordinates; a point that is no vertex fails".config(enabled = occt) {
            val sources = listOf(PointAt(40.0, 20.0), SegmentAt(0.0, 20.0, 0.0, 0.0))
            OcctKernel.extrudeProfileNamed(rect, 10.0, sources).use { named ->
                named.shape.edges[OcctKernel.generatedEdges(named, listOf(sources[0])).single()]
                    .start.x shouldBe
                    (40.0 plusOrMinus 1e-9)
                OcctKernel.generatedEdges(named, listOf(sources[1]), GeneratedRole.END).size shouldBe 1
            }
            shouldThrow<IllegalArgumentException> {
                OcctKernel.extrudeProfileNamed(rect, 10.0, listOf(PointAt(5.0, 5.0)))
            }.message!! shouldContain "matches no vertex"
        }

        "a failed named build leaves no shape behind (handle hygiene over 100 failures)".config(enabled = occt) {
            fun failOnce() {
                runCatching { OcctKernel.extrudeProfileNamed(rect, 10.0, listOf(PointAt(5.0, 5.0))) }
                runCatching {
                    FeatureRebuilder.rebuild(
                        FeatureSequence(
                            listOf(
                                ExtrudeFeature(rect, 10.0),
                                SelectedFilletFeature(
                                    0,
                                    GeneratedEdges(0, listOf(ProfileVertex(0), ProfileVertex(1)), expected = 4),
                                    2.0,
                                ),
                            ),
                        ),
                    )
                }
            }
            failOnce()
            System.gc()
            val before = OcctBridge.nativeLiveShapeCount()
            repeat(100) { failOnce() }
            System.gc()
            // The shapes that failed rebuilds closed themselves; nothing may accumulate. The counter is global
            // and the Cleaner frees shapes of earlier tests asynchronously, so it may sink between the two
            // measurements; a leak would raise it by about one shape per failure (100), hence the slack.
            OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + LEAK_SLACK
        }

        "verification: sketch plate 40x20x10, GeneratedEdges fillet r=2 -> V = 7965.664, 10 faces; width 60 -> 11965.664"
            .config(
                enabled = both,
            ) {
                fun sequence(width: Double) =
                    FeatureSequence(
                        listOf(
                            plate(width),
                            SelectedFilletFeature(
                                0,
                                GeneratedEdges(0, SketchSources.corners("p0", "p1", "p2", "p3"), expected = 4),
                                2.0,
                            ),
                        ),
                    )
                FeatureRebuilder.rebuild(sequence(40.0)).use { r ->
                    r.final.volume shouldBe (7965.664 plusOrMinus 1e-3)
                    r.final.topology.faces shouldBe 10
                }
                FeatureRebuilder.rebuild(sequence(60.0)).use { r ->
                    r.final.volume shouldBe (11965.664 plusOrMinus 1e-3)
                    r.final.topology.faces shouldBe 10
                }
                // the resolved edges are the four vertical ones at x in {0, 60}
                OcctKernel.extrudeProfile(rect, 10.0).close()
                val wide = plate(60.0).buildNamedShape(SketchSources.corners("p0", "p1", "p2", "p3"))
                wide.use { named ->
                    val edges =
                        OcctKernel.generatedEdges(
                            named,
                            SketchSources.corners("p0", "p1", "p2", "p3"),
                            expected = 4,
                        )
                    edges shouldBe OcctKernel.selectEdges(named.shape, Edges.parallelTo(z))
                    edges
                        .map {
                            named.shape.edges[it]
                                .start.x
                                .let { x -> if (abs(x) < 1e-9) 0.0 else x }
                        }.toSet() shouldBe
                        setOf(0.0, 60.0)
                }
            }

        "the sketch edge between two named corners yields START and END copies".config(enabled = both) {
            val sources = listOf(SketchEdgeOf("p1", "p2"))
            plate().buildNamedShape(sources).use { named ->
                val start = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.START).single()]
                val end = named.shape.edges[OcctKernel.generatedEdges(named, sources, GeneratedRole.END).single()]
                start.start.z shouldBe (0.0 plusOrMinus 1e-9)
                end.start.z shouldBe (10.0 plusOrMinus 1e-9)
                start.start.x shouldBe (40.0 plusOrMinus 1e-9)
            }
        }

        "sketch naming errors list the available point names".config(enabled = both) {
            val unknown = shouldThrow<SketchException> { plate().buildNamedShape(listOf(SketchCorner("nope"))) }
            unknown.kind shouldBe SketchErrorKind.INVALID_INPUT
            unknown.message!! shouldContain "p0"
            val missingEdge = shouldThrow<SketchException> { plate().buildNamedShape(listOf(SketchEdgeOf("p0", "p2"))) }
            missingEdge.message!! shouldContain "no edge between"
        }

        "a sketch circle centre and a construction point are not corners; a duplicate name is ambiguous".config(
            enabled = both,
        ) {
            val withCircle =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0), label = "plate") { _ ->
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val centre = point(20.0, 10.0, name = "centre")
                    offset(frame.bottomLeft, centre, dx = 20.0, dy = 10.0)
                    circle(centre, 3.0)
                }
            shouldThrow<SketchException> {
                withCircle.buildNamedShape(
                    listOf(SketchCorner("centre")),
                )
            }.message!! shouldContain
                "not a corner"
            val dup =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0), label = "dup") { _ ->
                    val a = point(0.0, 0.0, fixed = true, name = "a")
                    val b = point(40.0, 0.0, name = "a")
                    val c = point(40.0, 20.0, name = "c")
                    horizontal(a, b)
                    distance(a, b, 40.0)
                    vertical(b, c)
                    distance(b, c, 20.0)
                    line(a, b)
                    line(b, c)
                    line(c, a)
                }
            shouldThrow<SketchException> { dup.buildNamedShape(listOf(SketchCorner("a"))) }.message!! shouldContain
                "not unique"
        }

        "rectangle(names = ...) gives stable corner names even after an earlier point".config(enabled = both) {
            val feature =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0), label = "named") { _ ->
                    point(100.0, 100.0, fixed = true, name = "anchor")
                    rectangle(0.0, 0.0, 40.0, 20.0, names = listOf("bl", "br", "tr", "tl"))
                }
            val sources = SketchSources.corners("bl", "br", "tr", "tl")
            // the profile is not closed with an extra isolated point -- so use a feature without it
            val plain =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0), label = "named") { _ ->
                    rectangle(0.0, 0.0, 40.0, 20.0, names = listOf("bl", "br", "tr", "tl"))
                }
            plain.buildNamedShape(sources).use { OcctKernel.generatedEdges(it, sources, expected = 4).size shouldBe 4 }
            feature.label shouldBe "named"
        }

        "multi-body sketches cannot name edges yet".config(enabled = both) {
            val two =
                sketchFeature(emptyMap(), SketchOperation.Extrude(10.0), label = "two") { _ ->
                    rectangle(0.0, 0.0, 10.0, 10.0)
                    rectangle(30.0, 0.0, 10.0, 10.0)
                }
            shouldThrow<SketchException> { two.buildNamedShape(listOf(SketchCorner("p0"))) }.message!! shouldContain
                "single contour"
        }

        "path through an operation: GeneratedEdges hits exactly the outer corners although the pocket adds look-alikes"
            .config(
                enabled = occt,
            ) {
                val square =
                    listOf(
                        ProfilePoint(0.0, 0.0),
                        ProfilePoint(40.0, 0.0),
                        ProfilePoint(40.0, 40.0),
                        ProfilePoint(0.0, 40.0),
                    )
                val sequence =
                    FeatureSequence(
                        listOf(
                            ExtrudeFeature(square, 10.0),
                            BoxFeature(10.0, 10.0, 20.0),
                            TranslateFeature(1, 15.0, 15.0, -5.0),
                            BooleanFeature(BooleanOperation.CUT, 0, 2),
                            SelectedFilletFeature(
                                3,
                                GeneratedEdges(0, GenerationSources.profileVertices(4), expected = 4),
                                2.0,
                            ),
                        ),
                    )
                // V = 16000 - 1000 (pocket) - 4 * (1 - pi/4) * 2^2 * 10 (outer fillets) = 14965.664
                FeatureRebuilder.rebuild(sequence).use { r ->
                    r.final.volume shouldBe (14965.664 plusOrMinus 1e-3)
                    r.final.topology.faces shouldBe 14
                }
            }

        "a GeneratedEdges consumer may also follow a chamfer and a within filter narrows the result".config(
            enabled = occt,
        ) {
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 10.0),
                        SelectedChamferFeature(
                            0,
                            GeneratedEdges(
                                0,
                                cornerSources,
                                expected = 2,
                                within = Edges.parallelTo(z) and Edges.atMax(Direction3.X),
                            ),
                            2.0,
                        ),
                    ),
                )
            // two vertical edges at x = 40, chamfer d = 2 removes 2 * 2 * 10 = 40
            FeatureRebuilder.rebuild(sequence).use { it.final.volume shouldBe (7960.0 plusOrMinus 1e-6) }
        }

        "a wrong expected count is a structured naming failure with candidates".config(enabled = occt) {
            val sequence =
                FeatureSequence(
                    listOf(
                        ExtrudeFeature(rect, 10.0),
                        SelectedFilletFeature(0, GeneratedEdges(0, cornerSources, expected = 3), 2.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.featureIndex shouldBe 1
            val naming = e.namingFailure!!
            naming.featureIndex shouldBe 1
            naming.matched.size shouldBe 4
            naming.candidates.isNotEmpty() shouldBe true
            naming.message!! shouldContain "expected 3"
            e.closedShapes.size shouldBe 1
        }

        "a source with no image in the generating feature fails at the consumer".config(enabled = occt) {
            val triangle = listOf(ProfilePoint(0.0, 0.0), ProfilePoint(10.0, 0.0), ProfilePoint(0.0, 10.0))
            val sequence =
                FeatureSequence(
                    listOf(
                        RevolveFeature(triangle, 360.0, RevolveAxis.Y),
                        SelectedFilletFeature(0, GeneratedEdges(0, GenerationSources.profileVertices(3)), 1.0),
                    ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(sequence) }
            e.namingFailure!!.unresolved.size shouldBe 2
        }

        "each naming entry point fails predictably when the native bridge is unavailable".config(enabled = !occt) {
            shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                OcctKernel.extrudeProfileNamed(rect, 10.0, cornerSources)
            }
            shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                OcctKernel.revolveProfileNamed(rect, 90.0, RevolveAxis.Y, cornerSources)
            }
            val region = ProfileRegion(loopOf(0.0, 0.0, 40.0, 0.0, 40.0, 20.0, 0.0, 20.0))
            shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                OcctKernel.extrudeRegionNamed(region, 10.0, listOf(RegionVertex(0, 0)))
            }
            shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                OcctKernel.revolveRegionNamed(region, 90.0, RevolveAxis.Y, listOf(RegionVertex(0, 0)))
            }
        }
    })
