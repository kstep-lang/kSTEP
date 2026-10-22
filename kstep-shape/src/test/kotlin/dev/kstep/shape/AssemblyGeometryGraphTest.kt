package dev.kstep.shape

import dev.kstep.shape.PlacementFixtures.pd
import dev.kstep.shape.PlacementFixtures.usage
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class AssemblyGeometryGraphTest :
    StringSpec({
        val asm = pd("ASM")
        val a = pd("A")
        val b = pd("B")
        val uA = usage("U-A", asm, a)
        val uB = usage("U-B", asm, b)

        "a shape-less root and shaped children: every usage is geometric, default placement is the identity" {
            val plan = AssemblyGeometryGraph.plan(listOf(uA, uB), listOf(a, b), emptyList())
            plan.usages.map { it.usage.id } shouldBe listOf("U-A", "U-B")
            plan.usages.all { it.placement.isIdentity } shouldBe true
            plan.members.map { it.id } shouldBe listOf("ASM-D", "A-D", "B-D")
        }

        "a usage whose child has no geometry gets no placement chain" {
            val plan = AssemblyGeometryGraph.plan(listOf(uA, uB), listOf(a), emptyList())
            plan.usages.map { it.usage.id } shouldBe listOf("U-A")
            plan.members.map { it.id } shouldBe listOf("ASM-D", "A-D")
        }

        "no shapes and no usage placement means an empty plan (pre-ADR-0028 behaviour)" {
            AssemblyGeometryGraph.plan(listOf(uA), emptyList(), emptyList()).usages shouldBe emptyList()
            AssemblyGeometryGraph.plan(listOf(asm), listOf(asm), emptyList()).usages shouldBe emptyList()
        }

        "geometry propagates upwards through nested assemblies" {
            val sub = pd("SUB")
            val leaf = pd("LEAF")
            val top = usage("U-TOP", asm, sub)
            val low = usage("U-LOW", sub, leaf)
            val plan = AssemblyGeometryGraph.plan(listOf(top, low), listOf(leaf), emptyList())
            plan.usages.map { it.usage.id } shouldBe listOf("U-TOP", "U-LOW")
            plan.members.map { it.id } shouldBe listOf("ASM-D", "SUB-D", "LEAF-D")
        }

        "one part used twice yields two usages and one member entry" {
            val u1 = usage("U-1", asm, a)
            val u2 = usage("U-2", asm, a)
            val p1 = RigidPlacement(Vec3(1.0, 0.0, 0.0))
            val p2 = RigidPlacement(Vec3(2.0, 0.0, 0.0))
            val plan =
                AssemblyGeometryGraph.plan(
                    listOf(u1, u2),
                    listOf(a),
                    listOf(UsagePlacement(u1, p1), UsagePlacement(u2, p2)),
                )
            plan.usages.map { it.placement } shouldBe listOf(p1, p2)
            plan.members.map { it.id } shouldBe listOf("ASM-D", "A-D")
        }

        "a part contained in two different parents (DAG) is supported" {
            val p2 = pd("ASM2")
            val shared = pd("SHARED")
            val u1 = usage("U-1", asm, shared)
            val u2 = usage("U-2", p2, shared)
            val uTop = usage("U-3", asm, p2)
            val plan = AssemblyGeometryGraph.plan(listOf(u1, u2, uTop), listOf(shared), emptyList())
            plan.usages.size shouldBe 3
        }

        "the order is deterministic across calls" {
            val first = AssemblyGeometryGraph.plan(listOf(uB, uA), listOf(a, b), emptyList())
            val second = AssemblyGeometryGraph.plan(listOf(uB, uA), listOf(b, a), emptyList())
            first.usages.map { it.usage.id } shouldBe listOf("U-B", "U-A")
            second.usages.map { it.usage.id } shouldBe first.usages.map { it.usage.id }
            second.members.map { it.id } shouldBe first.members.map { it.id }
        }

        "a duplicated placement is rejected" {
            val p = RigidPlacement()
            val ex =
                shouldThrow<ShapeExportException> {
                    AssemblyGeometryGraph.plan(
                        listOf(uA),
                        listOf(a),
                        listOf(UsagePlacement(uA, p), UsagePlacement(uA, p)),
                    )
                }
            ex.message!! shouldContain "placed more than once"
        }

        "a placement on a usage that is not a root is rejected" {
            val stray = usage("U-STRAY", asm, a)
            val ex =
                shouldThrow<ShapeExportException> {
                    AssemblyGeometryGraph.plan(listOf(uA), listOf(a), listOf(UsagePlacement(stray, RigidPlacement())))
                }
            ex.message!! shouldContain "not registered as a root"
        }

        "a placement on a usage whose child carries no geometry is rejected" {
            val ex =
                shouldThrow<ShapeExportException> {
                    AssemblyGeometryGraph.plan(listOf(uA, uB), listOf(a), listOf(UsagePlacement(uB, RigidPlacement())))
                }
            ex.message!! shouldContain "carries no geometry"
        }

        "a cycle in the geometric usage graph is rejected instead of looping" {
            val x = pd("X")
            val y = pd("Y")
            val xy = usage("U-XY", x, y)
            val yx = usage("U-YX", y, x)
            val ex =
                shouldThrow<ShapeExportException> { AssemblyGeometryGraph.plan(listOf(xy, yx), listOf(x), emptyList()) }
            ex.message!! shouldContain "cycle"
        }

        "a self-usage is a cycle" {
            val x = pd("SELF")
            val ex =
                shouldThrow<ShapeExportException> {
                    AssemblyGeometryGraph.plan(
                        listOf(usage("U-S", x, x)),
                        listOf(x),
                        emptyList(),
                    )
                }
            ex.message!! shouldContain "cycle"
        }

        "more than MAX_GEOMETRIC_USAGES geometric usages are rejected" {
            val leaf = pd("LEAF")
            val many = (0..AssemblyGeometryGraph.MAX_GEOMETRIC_USAGES).map { usage("U-$it", asm, leaf) }
            val ex = shouldThrow<ShapeExportException> { AssemblyGeometryGraph.plan(many, listOf(leaf), emptyList()) }
            ex.message!! shouldContain "too many geometric assembly usages"
        }
    })
