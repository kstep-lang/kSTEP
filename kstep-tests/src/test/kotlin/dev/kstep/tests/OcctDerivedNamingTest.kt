package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.geometry.BlendedEdges
import dev.kstep.geometry.BooleanOperation
import dev.kstep.geometry.BooleanSection
import dev.kstep.geometry.GeneratedRole
import dev.kstep.geometry.HoleWall
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctShape
import dev.kstep.geometry.OperandEdges
import dev.kstep.geometry.OperandFaces
import dev.kstep.geometry.feature.BooleanFeature
import dev.kstep.geometry.feature.BoxFeature
import dev.kstep.geometry.feature.FeatureHistory
import dev.kstep.geometry.feature.FeatureRebuildException
import dev.kstep.geometry.feature.FeatureRebuilder
import dev.kstep.geometry.feature.FeatureSequence
import dev.kstep.geometry.feature.GeneratedEdges
import dev.kstep.geometry.feature.HoleFeature
import dev.kstep.geometry.feature.SelectEdges
import dev.kstep.geometry.feature.SelectedChamferFeature
import dev.kstep.geometry.feature.SelectedFilletFeature
import dev.kstep.geometry.feature.TrackedEdges
import dev.kstep.geometry.feature.TranslateFeature
import dev.kstep.geometry.occt.OcctBridge
import dev.kstep.geometry.select.Direction3
import dev.kstep.geometry.select.EdgeKind
import dev.kstep.geometry.select.EdgeNamingException
import dev.kstep.geometry.select.Edges
import dev.kstep.geometry.select.Faces
import dev.kstep.geometry.select.and
import dev.kstep.sketch.SketchOperation
import dev.kstep.sketch.sketchFeature
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.math.PI

// Allowed growth of the global native shape counter during the leak test (a real leak adds ~100).
private const val LEAK_SLACK = 10

/**
 * Welle N2 (ADR-0034): naming of the edges that fillet, chamfer, boolean and hole generate. Hand-computed values
 * (see the ADR): a 40x40x10 plate with a through hole R = 4 and the entry rim rounded at r = 1 loses
 * 2 pi (R + r (10 - 3 pi) / (12 - 3 pi)) r^2 (1 - pi / 4) = 5.694718 of V = 16000 - 160 pi, giving 15491.650 with
 * 8 faces; the same on a 60x40x10 plate gives 23491.650. Two 20x20x10 boxes offset by (10, 10, 0) fuse to
 * V = 7000 with 10 faces; rounding the two section edges at r = 2 adds 2 (1 - pi / 4) 4 10 = 17.168.
 */
class OcctDerivedNamingTest :
    StringSpec({
        val occt = OcctKernel.availability() is OcctAvailability.Available
        val both = occt && PlaneGcsSolver.availability() is PlaneGcsAvailability.Available
        val z = Direction3.Z
        val rim = listOf<dev.kstep.geometry.GenerationSource>(HoleWall)

        // Hole from z = 11 (above the plate), downwards. Through: depth 12 leaves at z = -1. Blind: depth 6 ends at z = 5.
        fun holed(
            width: Double = 40.0,
            depth: Double = 12.0,
        ) = listOf(
            BoxFeature(width, 40.0, 10.0),
            HoleFeature(0, 8.0, depth, width / 2.0, 20.0, 11.0),
        )

        fun rimFillet(
            width: Double = 40.0,
            radius: Double = 1.0,
            role: GeneratedRole = GeneratedRole.RIM_ENTRY,
            depth: Double = 12.0,
        ) = FeatureSequence(
            holed(width, depth) + SelectedFilletFeature(1, GeneratedEdges(1, rim, role, expected = 1), radius),
        )

        fun twoBoxes(op: BooleanOperation = BooleanOperation.UNION) =
            listOf(
                BoxFeature(20.0, 20.0, 10.0),
                BoxFeature(20.0, 20.0, 10.0),
                TranslateFeature(1, 10.0, 10.0, 0.0),
                BooleanFeature(op, 0, 2),
            )

        fun withShape(
            seq: FeatureSequence,
            check: (dev.kstep.geometry.feature.FeatureRebuildResult) -> Unit,
        ) = FeatureRebuilder.rebuild(seq).use(check)

        fun xyOf(
            shape: OcctShape,
            edges: List<Int>,
        ) = edges
            .map { shape.edges[it] }
            .map { Math.round(it.start.x * 1000) / 1000.0 to Math.round(it.start.y * 1000) / 1000.0 }
            .toSet()

        "Spike pin G1: a fillet and a chamfer report the blend face they created, as BOUNDARY edges".config(
            enabled = occt,
        ) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { box ->
                val one = OcctKernel.selectEdges(box, Edges.parallelTo(z) and Edges.atMin(Direction3(1.0, 1.0, 0.0)))
                one.size shouldBe 1
                listOf(true, false).forEach { fillet ->
                    val named =
                        if (fillet) {
                            OcctKernel.filletNamed(box, one, 2.0, listOf(BlendedEdges))
                        } else {
                            OcctKernel.chamferNamed(box, one, 2.0, listOf(BlendedEdges))
                        }
                    named.use {
                        it.shape.topology.faces shouldBe 7
                        val boundary =
                            OcctKernel.generatedEdges(it, listOf(BlendedEdges), GeneratedRole.BOUNDARY, expected = 4)
                        val kinds = boundary.map { e -> it.shape.edges[e].kind }
                        kinds.count { k -> k == EdgeKind.LINE } shouldBe (if (fillet) 2 else 4)
                        // the history of the same maker: the blended edge is not "kept"
                        it.history.operands
                            .single()
                            .size shouldBe 12
                    }
                }
            }
        }

        "Spike pin G2/G3: boolean faces generate section edges, boolean edges generate none".config(enabled = occt) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { a ->
                OcctKernel.makeBox(20.0, 20.0, 10.0).use { b0 ->
                    OcctKernel.translate(b0, 10.0, 10.0, 0.0).use { b ->
                        BooleanOperation.entries.forEach { op ->
                            OcctKernel.booleanNamed(op, a, b, listOf(BooleanSection)).use { named ->
                                val section =
                                    OcctKernel.generatedEdges(named, listOf(BooleanSection), GeneratedRole.SECTION, 2)
                                xyOf(named.shape, section) shouldBe setOf(20.0 to 10.0, 10.0 to 20.0)
                                section.forEach { e -> named.shape.edges[e].kind shouldBe EdgeKind.LINE }
                            }
                        }
                        // G3: an OperandEdges source is not offered for a boolean (OCCT reports vertices for edges)
                        shouldThrow<IllegalArgumentException> {
                            OcctKernel.booleanNamed(
                                BooleanOperation.UNION,
                                a,
                                b,
                                listOf(OperandEdges(0, Edges.all())),
                            )
                        }.message!! shouldContain "does not fit"
                    }
                }
            }
        }

        "named and plain builds produce identical edge and face indices (one construction path)".config(
            enabled = occt,
        ) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { a ->
                OcctKernel.makeBox(20.0, 20.0, 10.0).use { b0 ->
                    OcctKernel.translate(b0, 10.0, 10.0, 0.0).use { b ->
                        val edges = OcctKernel.selectEdges(a, Edges.parallelTo(z), 4)
                        OcctKernel.filletNamed(a, edges, 2.0, listOf(BlendedEdges)).use { named ->
                            OcctKernel.fillet(a, edges, 2.0).use { plain ->
                                named.shape.edges shouldBe plain.edges
                                named.shape.faces shouldBe plain.faces
                            }
                        }
                        OcctKernel.chamferNamed(a, edges, 2.0, listOf(BlendedEdges)).use { named ->
                            OcctKernel.chamfer(a, edges, 2.0).use { plain ->
                                named.shape.edges shouldBe plain.edges
                                named.shape.faces shouldBe plain.faces
                            }
                        }
                        BooleanOperation.entries.forEach { op ->
                            OcctKernel.booleanNamed(op, a, b, listOf(BooleanSection)).use { named ->
                                OcctKernel.boolean(op, a, b).use { plain ->
                                    named.shape.edges shouldBe plain.edges
                                    named.shape.faces shouldBe plain.faces
                                }
                            }
                        }
                    }
                }
            }
        }

        "a through hole: HoleWall has SECTION = both rims, RIM_ENTRY is the ring at z = 10, RIM_EXIT at z = 0".config(
            enabled = occt,
        ) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { plate ->
                OcctKernel.holeNamed(plate, 8.0, 12.0, 20.0, 20.0, 11.0, sources = rim).use { named ->
                    val section = OcctKernel.generatedEdges(named, rim, GeneratedRole.SECTION, expected = 2)
                    section.forEach { named.shape.edges[it].kind shouldBe EdgeKind.CIRCLE }
                    val entry = OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_ENTRY, expected = 1).single()
                    val exit = OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_EXIT, expected = 1).single()
                    named.shape.edges[entry]
                        .mid.z shouldBe (10.0 plusOrMinus 1e-6)
                    named.shape.edges[exit]
                        .mid.z shouldBe (0.0 plusOrMinus 1e-6)
                    named.shape.edges[entry].radius!! shouldBe (4.0 plusOrMinus 1e-9)
                }
            }
        }

        "RIM_ENTRY follows the drill direction, not the world Z axis".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { plate ->
                // drilling UP from below: the ring at z = 0 is the entry
                OcctKernel.holeNamed(plate, 8.0, 12.0, 20.0, 20.0, -1.0, 0.0, 0.0, 1.0, rim).use { named ->
                    val entry = OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_ENTRY, expected = 1).single()
                    val exit = OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_EXIT, expected = 1).single()
                    named.shape.edges[entry]
                        .mid.z shouldBe (0.0 plusOrMinus 1e-6)
                    named.shape.edges[exit]
                        .mid.z shouldBe (10.0 plusOrMinus 1e-6)
                }
            }
        }

        "a hole breaking out through a side face: RIM roles are a naming error, SECTION still works".config(
            enabled = occt,
        ) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { plate ->
                OcctKernel.holeNamed(plate, 8.0, 12.0, 2.0, 20.0, 11.0, sources = rim).use { named ->
                    OcctKernel.generatedEdges(named, rim, GeneratedRole.SECTION).isNotEmpty() shouldBe true
                    for (role in listOf(GeneratedRole.RIM_ENTRY, GeneratedRole.RIM_EXIT)) {
                        val e = shouldThrow<EdgeNamingException> { OcctKernel.generatedEdges(named, rim, role) }
                        e.message!! shouldContain "breaks out through a side face"
                        e.unresolved shouldBe rim
                    }
                }
            }
        }

        "an off-centre cross drill through a rod (curved side breakout, no straight edge): RIM roles fail".config(
            enabled = occt,
        ) {
            OcctKernel.makeCylinder(10.0, 40.0, 0.0, 20.0, 10.0, 1.0, 0.0, 0.0).use { rod ->
                OcctKernel.holeNamed(rod, 8.0, 30.0, 20.0, 28.0, 25.0, sources = rim).use { named ->
                    OcctKernel.generatedEdges(named, rim, GeneratedRole.SECTION).isNotEmpty() shouldBe true
                    for (role in listOf(GeneratedRole.RIM_ENTRY, GeneratedRole.RIM_EXIT)) {
                        val e = shouldThrow<EdgeNamingException> { OcctKernel.generatedEdges(named, rim, role) }
                        e.message!! shouldContain "breaks out through a side face"
                        e.unresolved shouldBe rim
                    }
                }
            }
        }

        "grazing and large drills through a rod: the whole breakout outline is never taken for a rim".config(
            enabled = occt,
        ) {
            OcctKernel.makeCylinder(10.0, 40.0, 0.0, 20.0, 10.0, 1.0, 0.0, 0.0).use { rod ->
                for ((d, y) in listOf(8.0 to 32.0, 8.0 to 33.5, 30.0 to 30.0, 30.0 to 35.0)) {
                    OcctKernel.holeNamed(rod, d, 30.0, 20.0, y, 25.0, sources = rim).use { named ->
                        OcctKernel.generatedEdges(named, rim, GeneratedRole.SECTION).isNotEmpty() shouldBe true
                        for (role in listOf(GeneratedRole.RIM_ENTRY, GeneratedRole.RIM_EXIT)) {
                            val e = shouldThrow<EdgeNamingException> { OcctKernel.generatedEdges(named, rim, role) }
                            e.message!! shouldContain "breaks out through a side face"
                        }
                    }
                }
            }
        }

        "multi-body: a grazing breakout on a rod is never a rim although another body adds a ring".config(
            enabled = occt,
        ) {
            fun drill(
                body: OcctShape,
                y: Double,
            ) = OcctKernel.holeNamed(body, 8.0, 40.0, 30.0, y, 25.0, sources = rim)
            OcctKernel.makeCylinder(10.0, 40.0, 0.0, 20.0, 10.0, 1.0, 0.0, 0.0).use { rod ->
                OcctKernel.makeBox(40.0, 60.0, 5.0).use { plateBox ->
                    OcctKernel.translate(plateBox, 0.0, 0.0, -10.0).use { plate ->
                        OcctKernel.makeBox(5.0, 20.0, 15.0).use { connBox ->
                            OcctKernel.translate(connBox, 0.0, 10.0, -5.0).use { conn ->
                                OcctKernel.union(rod, conn).use { rc ->
                                    OcctKernel.union(rc, plate).use { body ->
                                        for (y in listOf(32.0, 33.5)) {
                                            drill(body, y).use { named ->
                                                OcctKernel
                                                    .generatedEdges(named, rim, GeneratedRole.SECTION)
                                                    .isNotEmpty() shouldBe true
                                                for (role in listOf(GeneratedRole.RIM_ENTRY, GeneratedRole.RIM_EXIT)) {
                                                    shouldThrow<EdgeNamingException> {
                                                        OcctKernel.generatedEdges(named, rim, role)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        "a centred cross drill through a rod keeps working (entry and exit rings)".config(enabled = occt) {
            OcctKernel.makeCylinder(10.0, 40.0, 0.0, 20.0, 10.0, 1.0, 0.0, 0.0).use { rod ->
                OcctKernel.holeNamed(rod, 8.0, 30.0, 20.0, 20.0, 25.0, sources = rim).use { named ->
                    OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_ENTRY).isNotEmpty() shouldBe true
                    OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_EXIT).isNotEmpty() shouldBe true
                }
            }
        }

        "SECTION on HoleWall works even when the rims are ambiguous (hole across a narrow slot)".config(
            enabled = occt,
        ) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { plate ->
                OcctKernel.makeBox(4.0, 31.0, 12.0).use { slotBox ->
                    OcctKernel.translate(slotBox, 18.0, -1.0, -1.0).use { slot ->
                        OcctKernel.boolean(BooleanOperation.CUT, plate, slot).use { slotted ->
                            OcctKernel.holeNamed(slotted, 8.0, 12.0, 20.0, 20.0, 11.0, sources = rim).use { named ->
                                OcctKernel.generatedEdges(named, rim, GeneratedRole.SECTION).isNotEmpty() shouldBe true
                                shouldThrow<EdgeNamingException> {
                                    OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_ENTRY)
                                }
                            }
                        }
                    }
                }
            }
        }

        "a blind hole has an entry ring and no exit ring (naming error, no guess)".config(enabled = occt) {
            OcctKernel.makeBox(40.0, 40.0, 10.0).use { plate ->
                OcctKernel.holeNamed(plate, 8.0, 6.0, 20.0, 20.0, 11.0, sources = rim).use { named ->
                    OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_ENTRY, expected = 1)
                    val e =
                        shouldThrow<EdgeNamingException> {
                            OcctKernel.generatedEdges(named, rim, GeneratedRole.RIM_EXIT)
                        }
                    e.unresolved shouldBe rim
                    e.message!! shouldContain "no RIM_EXIT image"
                    e.generatedCandidates["HoleWall"] shouldBe emptyList()
                }
            }
        }

        "verification (a): hole rim fillet r=1 on a 40x40x10 plate -> V = 15491.650, 8 faces; width 60 -> 23491.650"
            .config(
                enabled = occt,
            ) {
                withShape(rimFillet(40.0)) { r ->
                    r.final.volume shouldBe (15491.650 plusOrMinus 0.01)
                    r.final.topology.faces shouldBe 8
                }
                withShape(rimFillet(60.0)) { r ->
                    r.final.volume shouldBe (23491.650 plusOrMinus 0.01)
                    r.final.topology.faces shouldBe 8
                }
                // before and after the width change the name hits the ring at z = 10 around the hole axis
                listOf(40.0, 60.0).forEach { w ->
                    OcctKernel.makeBox(w, 40.0, 10.0).use { plate ->
                        OcctKernel.holeNamed(plate, 8.0, 12.0, w / 2.0, 20.0, 11.0, sources = rim).use { named ->
                            val ring =
                                named.shape.edges[
                                    OcctKernel
                                        .generatedEdges(
                                            named,
                                            rim,
                                            GeneratedRole.RIM_ENTRY,
                                        ).single(),
                                ]
                            ring.mid.z shouldBe (10.0 plusOrMinus 1e-6)
                            ring.circleCenter!!.x shouldBe (w / 2.0 plusOrMinus 1e-6)
                            ring.circleCenter!!.z shouldBe (10.0 plusOrMinus 1e-6)
                        }
                    }
                }
            }

        "verification (a-chamfer): chamfer d=1 on the entry rim -> V = 15483.732, 8 faces".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    holed() +
                        SelectedChamferFeature(1, GeneratedEdges(1, rim, GeneratedRole.RIM_ENTRY, expected = 1), 1.0),
                )
            withShape(seq) { r ->
                r.final.volume shouldBe (15483.732 plusOrMinus 0.01)
                r.final.topology.faces shouldBe 8
            }
        }

        "verification (b): fuse of two offset boxes is V = 7000 with 10 faces; SECTION fillet r=2 -> 7017.168, 12 faces"
            .config(
                enabled = occt,
            ) {
                withShape(FeatureSequence(twoBoxes())) { r ->
                    r.final.volume shouldBe (7000.0 plusOrMinus 1e-6)
                    r.final.topology.faces shouldBe 10
                }
                val seq =
                    FeatureSequence(
                        twoBoxes() +
                            SelectedFilletFeature(
                                3,
                                GeneratedEdges(3, listOf(BooleanSection), GeneratedRole.SECTION, expected = 2),
                                2.0,
                            ),
                    )
                withShape(seq) { r ->
                    r.final.volume shouldBe (7017.168 plusOrMinus 0.01)
                    r.final.topology.faces shouldBe 12
                }
            }

        "OperandFaces names the section edges of single faces of either operand".config(enabled = occt) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { a ->
                OcctKernel.makeBox(20.0, 20.0, 10.0).use { b0 ->
                    OcctKernel.translate(b0, 10.0, 10.0, 0.0).use { b ->
                        val onA = OperandFaces(0, Faces.normal(Direction3.X))
                        val onB = OperandFaces(1, Faces.normal(Direction3.NEG_X))
                        OcctKernel.booleanNamed(BooleanOperation.UNION, a, b, listOf(onA, onB)).use { named ->
                            val ea = OcctKernel.generatedEdges(named, listOf(onA), GeneratedRole.SECTION, expected = 1)
                            val eb = OcctKernel.generatedEdges(named, listOf(onB), GeneratedRole.SECTION, expected = 1)
                            xyOf(named.shape, ea) shouldBe setOf(20.0 to 10.0)
                            xyOf(named.shape, eb) shouldBe setOf(10.0 to 20.0)
                        }
                    }
                }
            }
        }

        "BlendedEdges/OperandEdges name the outline of the blend face of a rounded vertical edge".config(
            enabled = occt,
        ) {
            val onlyCorner = Edges.parallelTo(z) and Edges.atMin(Direction3(1.0, 1.0, 0.0))
            val seq =
                FeatureSequence(
                    listOf(
                        BoxFeature(20.0, 20.0, 10.0),
                        SelectedFilletFeature(0, SelectEdges(onlyCorner, expected = 1), 2.0),
                        SelectedChamferFeature(
                            1,
                            GeneratedEdges(
                                1,
                                listOf(BlendedEdges),
                                GeneratedRole.BOUNDARY,
                                within = Edges.atMax(z),
                                expected = 1,
                            ),
                            1.0,
                        ),
                    ),
                )
            withShape(seq) { r ->
                r.shapes[1].volume shouldBe (4000.0 - (1.0 - PI / 4.0) * 4.0 * 10.0 plusOrMinus 1e-6)
                // OCCT propagates the chamfer along the tangent chain line(18) + arc + line(18) of the top outline:
                // 0.5 * 1 * (18 + 18) + (pi / 2) * (5 / 3) * 0.5 = 19.309 removed from 3991.416
                r.final.volume shouldBe (3972.107 plusOrMinus 0.01)
                r.final.topology.faces shouldBe 10
            }
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { box ->
                val edge = OcctKernel.selectEdges(box, onlyCorner, 1)
                val viaOperand = listOf(OperandEdges(0, onlyCorner))
                OcctKernel.filletNamed(box, edge, 2.0, viaOperand).use { named ->
                    OcctKernel.generatedEdges(named, viaOperand, GeneratedRole.BOUNDARY, expected = 4) shouldBe
                        OcctKernel.generatedEdges(
                            named,
                            viaOperand,
                            GeneratedRole.BOUNDARY,
                        )
                }
            }
        }

        "an OperandEdges source that picks an edge the operation did not round has no image".config(enabled = occt) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { box ->
                val rounded = Edges.parallelTo(z) and Edges.atMin(Direction3(1.0, 1.0, 0.0))
                val other = Edges.parallelTo(z) and Edges.atMax(Direction3(1.0, 1.0, 0.0))
                val source = OperandEdges(0, other)
                val edge = OcctKernel.selectEdges(box, rounded, 1)
                OcctKernel.filletNamed(box, edge, 2.0, listOf(source)).use { named ->
                    val e =
                        shouldThrow<EdgeNamingException> {
                            OcctKernel.generatedEdges(named, listOf(source), GeneratedRole.BOUNDARY)
                        }
                    e.unresolved shouldBe listOf(source)
                    e.generatedCandidates.size shouldBe 1
                }
            }
        }

        "the kernel refuses sources that do not fit the operation before any native call".config(enabled = occt) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { box ->
                OcctKernel.makeBox(20.0, 20.0, 10.0).use { other ->
                    val one = listOf(0)

                    fun fillet(sources: List<dev.kstep.geometry.GenerationSource>) =
                        OcctKernel.filletNamed(box, one, 1.0, sources)
                    shouldThrow<IllegalArgumentException> { fillet(emptyList()) }
                    shouldThrow<IllegalArgumentException> { fillet(listOf(BlendedEdges, BlendedEdges)) }
                    shouldThrow<IllegalArgumentException> { fillet(listOf(dev.kstep.geometry.ProfileVertex(0))) }
                        .message!! shouldContain "profile source"
                    shouldThrow<IllegalArgumentException> { fillet(listOf(OperandEdges(1, Edges.all()))) }
                    shouldThrow<IllegalArgumentException> { fillet(listOf(HoleWall)) }
                    shouldThrow<IllegalArgumentException> { fillet(listOf(BooleanSection)) }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.booleanNamed(BooleanOperation.UNION, box, other, listOf(HoleWall))
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.booleanNamed(
                            BooleanOperation.UNION,
                            box,
                            other,
                            listOf(OperandFaces(2, Faces.atMax(z))),
                        )
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.holeNamed(box, 2.0, 5.0, 10.0, 10.0, 11.0, sources = listOf(BlendedEdges))
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.holeNamed(box, -2.0, 5.0, 10.0, 10.0, 11.0, sources = rim)
                    }
                    shouldThrow<IllegalArgumentException> {
                        OcctKernel.filletNamed(
                            box,
                            one,
                            1.0,
                            listOf(OperandEdges(0, Edges.atMax(z)), OperandEdges(0, Edges.atMax(z))),
                        )
                    }
                }
            }
        }

        "tracking: a hole-rim name survives a later translate and still hits the ring".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    holed() +
                        listOf(
                            TranslateFeature(1, 5.0, 3.0, 2.0),
                            SelectedFilletFeature(
                                2,
                                GeneratedEdges(1, rim, GeneratedRole.RIM_ENTRY, expected = 1),
                                1.0,
                            ),
                        ),
                )
            withShape(seq) { r ->
                r.final.volume shouldBe (15491.650 plusOrMinus 0.01)
                r.final.topology.faces shouldBe 8
            }
        }

        "a derived generator that is also on a tracked path keeps both history and generation map".config(
            enabled = occt,
        ) {
            val seq =
                FeatureSequence(
                    holed() +
                        listOf(
                            SelectedFilletFeature(
                                1,
                                GeneratedEdges(1, rim, GeneratedRole.RIM_ENTRY, expected = 1),
                                1.0,
                            ),
                            SelectedChamferFeature(
                                2,
                                TrackedEdges(0, Edges.atMax(z) and Edges.ofType(EdgeKind.LINE), expected = 4),
                                1.0,
                            ),
                        ),
                )
            withShape(seq) { r ->
                // plate 40x40x10 with hole and rim fillet, top outer edges chamfered 1: 78.667 more removed
                r.final.volume shouldBe (15412.984 plusOrMinus 0.01)
            }
        }

        "a hole boolean with SECTION sources also works when the plate is a sketch extrusion".config(enabled = both) {
            fun plate(width: Double) =
                sketchFeature(mapOf("width" to width), SketchOperation.Extrude(10.0), label = "plate") { p ->
                    rectangle(0.0, 0.0, p["width"], 20.0)
                }

            fun seq(width: Double) =
                FeatureSequence(
                    listOf(
                        plate(width),
                        HoleFeature(0, 8.0, 12.0, width / 2.0, 10.0, 11.0),
                        SelectedFilletFeature(1, GeneratedEdges(1, rim, GeneratedRole.RIM_ENTRY, expected = 1), 1.0),
                    ),
                )
            // 8000 - 160 pi - 5.694718, and the same at width 60 with 12000
            withShape(seq(40.0)) { r ->
                r.final.volume shouldBe (7491.650 plusOrMinus 0.01)
                r.final.topology.faces shouldBe 8
            }
            withShape(seq(60.0)) { r ->
                r.final.volume shouldBe (11491.650 plusOrMinus 0.01)
            }
        }

        "undo and redo of a hole and its rim fillet restore the volumes".config(enabled = occt) {
            val h = FeatureHistory()
            h.add(BoxFeature(40.0, 40.0, 10.0))
            h.add(HoleFeature(0, 8.0, 12.0, 20.0, 20.0, 11.0))
            h.add(SelectedFilletFeature(1, GeneratedEdges(1, rim, GeneratedRole.RIM_ENTRY, expected = 1), 1.0))

            fun volume() = h.rebuild().use { it.final.volume }
            volume() shouldBe (15491.650 plusOrMinus 0.01)
            h.undo()
            h.undo()
            volume() shouldBe (16000.0 plusOrMinus 1e-6)
            h.redo()
            h.redo()
            volume() shouldBe (15491.650 plusOrMinus 0.01)
            h.update(2) { (it as SelectedFilletFeature).copy(radius = 0.5) }
            (h.rebuild().use { it.final.volume }) shouldBe (16000.0 - 160.0 * PI - 1.3 plusOrMinus 0.5)
        }

        "a failing RIM_EXIT on a blind hole is a naming failure at the consuming feature".config(enabled = occt) {
            val e =
                shouldThrow<FeatureRebuildException> {
                    FeatureRebuilder.rebuild(rimFillet(role = GeneratedRole.RIM_EXIT, depth = 6.0))
                }
            e.featureIndex shouldBe 2
            e.namingFailure!!.unresolved shouldBe rim
        }

        "a wrong expected count lists the generated candidates".config(enabled = occt) {
            val seq =
                FeatureSequence(
                    holed() +
                        SelectedFilletFeature(
                            1,
                            GeneratedEdges(1, rim, GeneratedRole.SECTION, expected = 1),
                            1.0,
                        ),
                )
            val e = shouldThrow<FeatureRebuildException> { FeatureRebuilder.rebuild(seq) }
            val naming = e.namingFailure!!
            naming.generatedCandidates["HoleWall"]!!.size shouldBe 2
            naming.message!! shouldContain "expected 1 edge(s) but 2"
        }

        "an empty OperandFaces selector lists the candidate faces (at most 32)".config(enabled = occt) {
            OcctKernel.makeBox(20.0, 20.0, 10.0).use { a ->
                OcctKernel.makeBox(20.0, 20.0, 10.0).use { b ->
                    val source = OperandFaces(0, Faces.normal(Direction3(1.0, 1.0, 0.0)))
                    val e =
                        shouldThrow<EdgeNamingException> {
                            OcctKernel.booleanNamed(BooleanOperation.UNION, a, b, listOf(source))
                        }
                    e.unresolved shouldBe listOf(source)
                    e.message!! shouldContain "face 0: PLANE"
                    e.message!!.lines().count { it.trim().startsWith("face ") } shouldBeLessThanOrEqual 32
                    e.candidates.size shouldBeLessThanOrEqual 32
                }
            }
        }

        "a failed derived build leaves no shape behind (handle hygiene over 100 failures)".config(enabled = occt) {
            fun failOnce() {
                runCatching { FeatureRebuilder.rebuild(rimFillet(role = GeneratedRole.RIM_EXIT, depth = 6.0)) }
                runCatching {
                    OcctKernel.makeBox(20.0, 20.0, 10.0).use { a ->
                        OcctKernel.booleanNamed(
                            BooleanOperation.UNION,
                            a,
                            a,
                            listOf(OperandFaces(0, Faces.normal(Direction3(1.0, 1.0, 0.0)))),
                        )
                    }
                }
            }
            failOnce()
            System.gc()
            val before = OcctBridge.nativeLiveShapeCount()
            repeat(100) { failOnce() }
            System.gc()
            // The counter is global and the Cleaner frees shapes of earlier tests asynchronously, so no exact
            // equality: a leak would raise it by at least one shape per failure (100), hence the slack.
            OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + LEAK_SLACK
        }

        "hostile native queries are rejected before a shape is built or registered".config(enabled = occt) {
            val boxA = OcctBridge.nativeMakeBox(20.0, 20.0, 10.0)
            val boxB = OcctBridge.nativeMakeBox(20.0, 20.0, 10.0)
            try {
                val before = OcctBridge.nativeLiveShapeCount()

                fun fillet(queries: IntArray) =
                    OcctBridge.nativeFilletEdgesNamed(
                        boxA,
                        intArrayOf(0),
                        1.0,
                        queries,
                        arrayOfNulls(1),
                        arrayOfNulls(1),
                    )

                fun boolean(queries: IntArray) =
                    OcctBridge.nativeBooleanOpNamed(0, boxA, boxB, queries, arrayOfNulls(1), arrayOfNulls(1))
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf()) }
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(0, 0)) }
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(1, 0, 0)) } // slot 1 of a unary operation
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(0, 2, 0)) } // unknown kind
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(0, 0, -1)) }
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(0, 0, 12)) } // 12 edges: 0..11
                shouldThrow<IllegalArgumentException> { fillet(intArrayOf(0, 1, 6)) } // 6 faces: 0..5
                shouldThrow<IllegalArgumentException> { fillet(IntArray(3 * (OcctKernel.MAX_DERIVED_QUERIES + 1))) }
                shouldThrow<IllegalArgumentException> {
                    OcctBridge.nativeFilletEdgesNamed(
                        boxA,
                        intArrayOf(0),
                        1.0,
                        intArrayOf(0, 0, 0),
                        arrayOfNulls(1),
                        arrayOfNulls(0),
                    )
                }
                shouldThrow<IllegalArgumentException> { boolean(intArrayOf(2, 0, 0)) }
                shouldThrow<IllegalArgumentException> { boolean(intArrayOf(1, 1, 99)) }
                shouldThrow<IllegalArgumentException> {
                    OcctBridge.nativeChamferEdgesNamed(
                        boxA,
                        intArrayOf(0),
                        1.0,
                        intArrayOf(0, 0, 99),
                        arrayOfNulls(1),
                        arrayOfNulls(1),
                    )
                }
                System.gc()
                OcctBridge.nativeLiveShapeCount() shouldBeLessThanOrEqual before + LEAK_SLACK
            } finally {
                OcctBridge.nativeReleaseShape(boxA)
                OcctBridge.nativeReleaseShape(boxB)
            }
        }

        "each derived naming entry point fails predictably when the native bridge is unavailable".config(
            enabled = !occt,
        ) {
            shouldThrow<dev.kstep.geometry.OcctUnavailableException> {
                OcctKernel
                    .makeBox(
                        20.0,
                        20.0,
                        10.0,
                    ).use { OcctKernel.filletNamed(it, listOf(0), 1.0, listOf(BlendedEdges)) }
            }
        }
    })
