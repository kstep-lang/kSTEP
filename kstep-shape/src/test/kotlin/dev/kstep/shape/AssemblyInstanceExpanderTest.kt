package dev.kstep.shape

import dev.kstep.geometry.TriangleMesh
import dev.kstep.geometry.transformedBy
import dev.kstep.shape.PlacementFixtures.pd
import dev.kstep.shape.PlacementFixtures.usage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private const val EPS = 1e-9

private fun near(
    actual: Vec3,
    expected: Vec3,
) {
    actual.x shouldBe (expected.x plusOrMinus EPS)
    actual.y shouldBe (expected.y plusOrMinus EPS)
    actual.z shouldBe (expected.z plusOrMinus EPS)
}

class AssemblyInstanceExpanderTest :
    StringSpec({
        val quarterTurn = Rotation.about(Vec3.Z, 90.0)

        "a flat assembly yields one instance per shaped usage in usage order; the shape-less root is not drawn" {
            val asm = pd("ASM")
            val a = pd("A")
            val b = pd("B")
            val uA = usage("U-A", asm, a)
            val uB = usage("U-B", asm, b)
            val expansion =
                AssemblyInstanceExpander.expand(
                    listOf(uA, uB),
                    listOf(a, b),
                    listOf(UsagePlacement(uB, RigidPlacement(vec(30.0, 0.0, 0.0), quarterTurn))),
                )
            expansion.rootCount shouldBe 1
            expansion.instances.map { it.productDefinition.id } shouldBe listOf("A-D", "B-D")
            expansion.instances.map { it.path } shouldBe listOf(listOf("U-A"), listOf("U-B"))
            expansion.instances[0].placement.isIdentity shouldBe true
            // Hand calculation: (10,10,0) -> rotate 90 deg about Z (-y, x, z) = (-10,10,0) -> +30 in x.
            near(expansion.instances[1].placement.apply(vec(10.0, 10.0, 0.0)), vec(20.0, 10.0, 0.0))
        }

        "nested placements are composed from the root down (hand-calculated corners)" {
            val top = pd("TOP")
            val sub = pd("SUB")
            val leaf = pd("LEAF")
            val uSub = usage("U-SUB", top, sub)
            val uLeaf = usage("U-LEAF", sub, leaf)
            val expansion =
                AssemblyInstanceExpander.expand(
                    listOf(uSub, uLeaf),
                    listOf(leaf),
                    listOf(
                        UsagePlacement(uSub, RigidPlacement(vec(100.0, 0.0, 0.0), quarterTurn)),
                        UsagePlacement(uLeaf, RigidPlacement(vec(5.0, 0.0, 0.0))),
                    ),
                )
            expansion.instances.size shouldBe 1
            val instance = expansion.instances.single()
            instance.path shouldBe listOf("U-SUB", "U-LEAF")
            // (0,0,0) -> +5 in x -> (5,0,0) -> rotate (-y,x,z) -> (0,5,0) -> +100 in x -> (100,5,0)
            near(instance.placement.apply(Vec3.ZERO), vec(100.0, 5.0, 0.0))
            // (10,20,5) -> (15,20,5) -> (-20,15,5) -> (80,15,5)
            near(instance.placement.apply(vec(10.0, 20.0, 5.0)), vec(80.0, 15.0, 5.0))
        }

        "a part used twice yields two instances of the same definition with their own placements" {
            val asm = pd("ASM")
            val screw = pd("SCR")
            val u1 = usage("U-1", asm, screw)
            val u2 = usage("U-2", asm, screw)
            val expansion =
                AssemblyInstanceExpander.expand(
                    listOf(u1, u2),
                    listOf(screw),
                    listOf(UsagePlacement(u2, RigidPlacement(vec(8.0, 0.0, 0.0)))),
                )
            expansion.instances.size shouldBe 2
            (expansion.instances[0].productDefinition === expansion.instances[1].productDefinition) shouldBe true
            near(expansion.instances[1].placement.translation, vec(8.0, 0.0, 0.0))
        }

        "a shaped root that also has children is drawn once at the identity, then its children" {
            val asm = pd("ASM")
            val a = pd("A")
            val uA = usage("U-A", asm, a)
            val expansion = AssemblyInstanceExpander.expand(listOf(uA), listOf(asm, a), emptyList())
            expansion.instances.map { it.productDefinition.id } shouldBe listOf("ASM-D", "A-D")
            expansion.instances[0].path shouldBe emptyList()
        }

        "a model without any geometric usage expands to nothing" {
            val a = pd("A")
            AssemblyInstanceExpander.expand(emptyList(), listOf(a), emptyList()).instances shouldBe emptyList()
        }

        "a placement on a usage whose child carries no geometry fails closed" {
            val asm = pd("ASM")
            val a = pd("A")
            val b = pd("B")
            val uA = usage("U-A", asm, a)
            val uB = usage("U-B", asm, b)
            val e =
                shouldThrow<ShapeExportException> {
                    AssemblyInstanceExpander.expand(
                        listOf(uA, uB),
                        listOf(a),
                        listOf(UsagePlacement(uB, RigidPlacement(vec(1.0, 0.0, 0.0)))),
                    )
                }
            e.message shouldContain "carries no geometry"
        }

        "a cyclic usage graph is rejected" {
            val a = pd("A")
            val b = pd("B")
            val u1 = usage("U-1", a, b)
            val u2 = usage("U-2", b, a)
            shouldThrow<ShapeExportException> {
                AssemblyInstanceExpander.expand(
                    listOf(u1, u2),
                    listOf(a, b),
                    emptyList(),
                )
            }
        }

        "a chain deeper than MAX_DEPTH is rejected" {
            val chain = (0..AssemblyInstanceExpander.MAX_DEPTH + 1).map { pd("N$it") }
            val usages = chain.zipWithNext().mapIndexed { i, (p, c) -> usage("U-$i", p, c) }
            val e =
                shouldThrow<ShapeExportException> {
                    AssemblyInstanceExpander.expand(usages, listOf(chain.last()), emptyList())
                }
            e.message shouldContain "deeper than"
        }

        "a chain exactly at MAX_DEPTH is accepted" {
            val chain = (0..AssemblyInstanceExpander.MAX_DEPTH).map { pd("M$it") }
            val usages = chain.zipWithNext().mapIndexed { i, (p, c) -> usage("U-$i", p, c) }
            val expansion = AssemblyInstanceExpander.expand(usages, listOf(chain.last()), emptyList())
            expansion.instances.size shouldBe 1
        }

        "a fan-out DAG with 2^30 paths is stopped by the visit counter, not by exhausting memory" {
            val levels = (0..30).map { pd("L$it") }
            val usages =
                levels.zipWithNext().flatMapIndexed { i, (p, c) ->
                    listOf(usage("U-$i-a", p, c), usage("U-$i-b", p, c))
                }
            val e =
                shouldThrow<ShapeExportException> {
                    AssemblyInstanceExpander.expand(usages, listOf(levels.last()), emptyList())
                }
            e.message shouldContain "more than"
        }

        "toGeometryPlacement moves a mesh exactly like RigidPlacement.apply (arbitrary axis)" {
            val placement = RigidPlacement(vec(3.0, -4.0, 5.0), Rotation.about(vec(1.0, 2.0, 3.0), 37.0))
            val point = vec(1.5, -2.0, 7.0)
            val mesh = TriangleMesh(doubleArrayOf(point.x, point.y, point.z, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
            val moved = mesh.transformedBy(placement.toGeometryPlacement()).coordinates
            near(Vec3(moved[0], moved[1], moved[2]), placement.apply(point))
            near(Vec3(moved[3], moved[4], moved[5]), placement.apply(Vec3.ZERO))
        }

        "toGeometryPlacement survives a 30-fold composition of a 12 degree rotation" {
            val step = RigidPlacement(vec(1.0, 0.5, 0.0), Rotation.about(vec(0.3, 0.4, 0.5), 12.0))
            var composed = RigidPlacement.IDENTITY
            repeat(30) { composed = composed.then(step) }
            val point = vec(2.0, 3.0, 4.0)
            val mesh = TriangleMesh(doubleArrayOf(point.x, point.y, point.z, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0))
            val moved = mesh.transformedBy(composed.toGeometryPlacement()).coordinates
            val expected = composed.apply(point)
            moved[0] shouldBe (expected.x plusOrMinus 1e-7)
            moved[1] shouldBe (expected.y plusOrMinus 1e-7)
            moved[2] shouldBe (expected.z plusOrMinus 1e-7)
        }
    })
