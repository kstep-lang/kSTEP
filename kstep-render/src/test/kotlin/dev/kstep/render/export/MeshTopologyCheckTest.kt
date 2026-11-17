package dev.kstep.render.export

import dev.kstep.geometry.TriangleMesh
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class MeshTopologyCheckTest :
    StringSpec({
        fun without(
            m: TriangleMesh,
            tri: Int,
        ) = TriangleMesh(m.coordinates.filterIndexed { i, _ -> i / 9 != tri }.toDoubleArray())

        "a box is watertight" {
            val r = MeshTopologyCheck.check(boxMesh())
            r.watertight shouldBe true
            r.openEdgeCount shouldBe 0
            r.nonManifoldEdgeCount shouldBe 0
        }

        "a box missing one triangle has exactly three open edges" {
            val r = MeshTopologyCheck.check(without(boxMesh(), 0))
            r.openEdgeCount shouldBe 3
            r.watertight shouldBe false
        }

        "a flipped triangle is reported as inconsistent winding" {
            val c = boxMesh().coordinates.copyOf()
            for (k in 0 until 3) {
                val t = c[3 + k]
                c[3 + k] = c[6 + k]
                c[6 + k] = t
            }
            val r = MeshTopologyCheck.check(TriangleMesh(c))
            (r.nonManifoldEdgeCount > 0) shouldBe true
            r.watertight shouldBe false
        }

        "a duplicated triangle is non-manifold" {
            val m = boxMesh()
            val r = MeshTopologyCheck.check(TriangleMesh(m.coordinates + m.coordinates.copyOfRange(0, 9)))
            (r.nonManifoldEdgeCount > 0) shouldBe true
        }

        "1e-9 vertex jitter is absorbed by the 1e-6 welding, 1e-3 jitter is not" {
            fun jittered(amount: Double): TriangleMesh {
                val c = boxMesh().coordinates.copyOf()
                // move every second triangle's first vertex slightly
                for (t in 0 until c.size / 9 step 2) c[9 * t] += amount
                return TriangleMesh(c)
            }
            MeshTopologyCheck.check(jittered(1e-9)).watertight shouldBe true
            MeshTopologyCheck.check(jittered(1e-3)).watertight shouldBe false
        }

        "touching boxes are each watertight when checked per part" {
            MeshTopologyCheck.check(boxMesh()).watertight shouldBe true
            val scene =
                MeshExportScene(
                    "t",
                    listOf(
                        part("a", boxMesh(), dev.kstep.geometry.Placement.IDENTITY),
                        part(
                            "b",
                            boxMesh(),
                            dev.kstep.geometry.Placement
                                .translation(10.0, 0.0, 0.0),
                        ),
                    ),
                )
            StlWriter.write(scene).watertight shouldBe true
        }
    })
