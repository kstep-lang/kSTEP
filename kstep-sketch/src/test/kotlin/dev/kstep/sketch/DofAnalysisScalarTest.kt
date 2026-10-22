package dev.kstep.sketch

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs

/** Welle M (ADR-0032): analytic Jacobians of the radius/tangent/angle constraints against finite differences. */
class DofAnalysisScalarTest :
    StringSpec({
        // state: 4 points (8 values) followed by 2 scalars
        val state = doubleArrayOf(1.0, 2.0, 7.0, 3.5, 4.0, 9.0, 11.0, -1.0, 2.5, 4.0)
        val pointCount = 4

        fun checkJacobian(c: SketchConstraintData) {
            val rows = DofAnalysis.jacobianRows(c, state, pointCount)
            val base = DofAnalysis.residual(c, state, pointCount)
            rows.size shouldBe base.size
            for (slot in state.indices) {
                val h = 1e-6
                val plus = state.copyOf().also { it[slot] += h }
                val minus = state.copyOf().also { it[slot] -= h }
                val fp = DofAnalysis.residual(c, plus, pointCount)
                val fm = DofAnalysis.residual(c, minus, pointCount)
                rows.indices.forEach { r ->
                    val numeric = (fp[r] - fm[r]) / (2 * h)
                    val analytic = rows[r][slot] ?: 0.0
                    abs(analytic - numeric) shouldBe (0.0 plusOrMinus 1e-5)
                }
            }
        }

        "radius constraints differentiate correctly" {
            checkJacobian(RadiusData(0, 3.0, "r"))
            checkJacobian(EqualRadiusData(0, 1, "eq"))
        }

        "point-on-circle differentiates correctly" { checkJacobian(PointOnCircleData(1, 0, 1, "poc")) }

        "line-circle tangent differentiates correctly" { checkJacobian(TangentLineData(0, 1, 2, 0, "tan")) }

        "circle-circle tangents (external and internal) differentiate correctly" {
            checkJacobian(TangentCirclesData(0, 0, 2, 1, internal = false, label = "ext"))
            checkJacobian(TangentCirclesData(0, 0, 2, 1, internal = true, label = "int"))
        }

        "angle differentiates correctly" { checkJacobian(AngleData(0, 1, 2, 3, 0.4, "angle")) }

        "a free radius leaves one degree of freedom and names the radius" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    circle(a, 2.0, fixedRadius = false, name = "hole")
                }
            val dof = s.analyzeStart()
            dof.degreesOfFreedom shouldBe 1
            dof.movableScalars shouldBe listOf(0)
        }

        "a radius constraint on a fixed circle is redundant" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    radius(circle(a, 2.0), 2.0)
                }
            s.analyzeStart().redundantConstraints shouldBe listOf(0)
        }

        "equal radius of two fixed circles with different values is flagged" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0, fixed = true)
                    equalRadius(circle(a, 2.0), circle(b, 3.0))
                }
            s.analyzeStart().redundantConstraints shouldBe listOf(0)
        }

        "an angle together with a perpendicular is redundant" {
            val s =
                sketch {
                    val o = point(0.0, 0.0, fixed = true)
                    val x = point(10.0, 0.0, fixed = true)
                    val y = point(0.0, 10.0)
                    val l1 = line(o, x)
                    val l2 = line(o, y)
                    perpendicular(l1, l2)
                    angle(l1, l2, 90.0)
                }
            s.analyzeStart().redundantConstraints shouldContain 1
        }

        "concentric internal tangent circles give a zero row, reported as redundant" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(0.0, 0.0, fixed = true, name = "b")
                    val c1 = circle(a, 5.0, fixedRadius = true)
                    val c2 = circle(b, 3.0, fixedRadius = true)
                    tangent(c1, c2, internal = true)
                }
            s.analyzeStart().redundantConstraints shouldBe listOf(0)
        }
    })
