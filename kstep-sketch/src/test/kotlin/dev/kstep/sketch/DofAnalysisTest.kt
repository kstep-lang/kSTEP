package dev.kstep.sketch

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class DofAnalysisTest :
    StringSpec({
        "rectangle plus an offset circle centre: dof 0, redundant 0" {
            val analysis =
                sketch {
                    val frame = rectangle(0.0, 0.0, 40.0, 20.0)
                    val centre = point(20.0, 10.0)
                    offset(frame.bottomLeft, centre, 20.0, 10.0)
                    circle(centre, 3.0)
                }.analyzeStart()
            analysis.degreesOfFreedom shouldBe 0
            analysis.redundantConstraints shouldBe emptyList()
            analysis.unknowns shouldBe analysis.rank
        }

        "dropping one dimension leaves one degree of freedom and names the movable point" {
            val s =
                sketch {
                    val p0 = point(0.0, 0.0, fixed = true, name = "origin")
                    val p1 = point(10.0, 0.0, name = "right")
                    val p2 = point(10.0, 5.0, name = "corner")
                    val p3 = point(0.0, 5.0, name = "left")
                    val l0 = line(p0, p1)
                    val l1 = line(p1, p2)
                    val l2 = line(p2, p3)
                    val l3 = line(p3, p0)
                    horizontal(l0)
                    vertical(l1)
                    horizontal(l2)
                    vertical(l3)
                    length(l0, 10.0)
                    // no height
                }
            val analysis = s.analyzeStart()
            analysis.degreesOfFreedom shouldBe 1
            analysis.movablePoints.map { s.pointName(it) } shouldContainExactly listOf("corner", "left")
            val e = shouldThrow<SketchException> { s.solve() }
            e.kind shouldBe SketchErrorKind.UNDER_CONSTRAINED
            e.degreesOfFreedom shouldBe 1
            e.details shouldContainExactly listOf("corner", "left")
        }

        "a repeated distance is redundant and is named" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0)
                    horizontal(a, b)
                    distance(a, b, 10.0)
                    distance(a, b, 10.0)
                }
            val analysis = s.analyzeStart()
            analysis.redundantConstraints shouldBe listOf(2)
            val e = shouldThrow<SketchException> { s.solve() }
            e.kind shouldBe SketchErrorKind.OVER_CONSTRAINED
            e.details.single() shouldContain "distance(p0, p1)"
        }

        "contradicting dimensions on the same pair are over-constrained" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0)
                    distance(a, b, 10.0)
                    distance(a, b, 12.0)
                    horizontal(a, b)
                }
            shouldThrow<SketchException> { s.solve() }.kind shouldBe SketchErrorKind.OVER_CONSTRAINED
        }

        "a constraint between two fixed points carries no information and counts as redundant" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(3.0, 4.0, fixed = true)
                    distance(a, b, 5.0)
                }
            s.analyzeStart().redundantConstraints shouldBe listOf(0)
            shouldThrow<SketchException> { s.solve() }.kind shouldBe SketchErrorKind.OVER_CONSTRAINED
        }

        "free points without any constraint are under-constrained and the solver is never needed" {
            val s = sketch { point(1.0, 2.0, name = "loose") }
            val e = shouldThrow<SketchException> { s.solve() }
            e.kind shouldBe SketchErrorKind.UNDER_CONSTRAINED
            e.degreesOfFreedom shouldBe 2
            e.details shouldContain "loose"
        }

        "coincident constrains both coordinates, parallel and perpendicular one each" {
            val coincident =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(0.1, 0.1)
                    coincident(a, b)
                }
            coincident.analyzeStart().degreesOfFreedom shouldBe 0

            val perpendicular =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0, fixed = true)
                    val c = point(0.0, 0.0, fixed = true)
                    val d = point(0.1, 10.0)
                    perpendicular(line(a, b), line(c, d))
                }
            perpendicular.analyzeStart().degreesOfFreedom shouldBe 1 // d may still slide along the perpendicular
        }

        "point on line removes one degree of freedom" {
            val analysis =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0, fixed = true)
                    val p = point(5.0, 1.0)
                    pointOnLine(p, line(a, b))
                }.analyzeStart()
            analysis.degreesOfFreedom shouldBe 1
        }

        "parallel constrains the direction of a free line end" {
            val analysis =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(10.0, 0.0, fixed = true)
                    val c = point(0.0, 5.0, fixed = true)
                    val d = point(10.0, 5.2)
                    parallel(line(a, b), line(c, d))
                }.analyzeStart()
            analysis.degreesOfFreedom shouldBe 1
        }

        "the residual functions vanish on a satisfied configuration" {
            val coords = doubleArrayOf(0.0, 0.0, 3.0, 4.0)
            DofAnalysis.residual(DistanceData(0, 1, 5.0, "d"), coords)[0] shouldBe 0.0
            DofAnalysis.residual(HorizontalData(0, 1, "h"), doubleArrayOf(0.0, 2.0, 5.0, 2.0))[0] shouldBe 0.0
            DofAnalysis.residual(VerticalData(0, 1, "v"), doubleArrayOf(2.0, 0.0, 2.0, 5.0))[0] shouldBe 0.0
            DofAnalysis.residual(CoincidentData(0, 1, "c"), doubleArrayOf(1.0, 2.0, 1.0, 2.0)).toList() shouldBe
                listOf(0.0, 0.0)
        }

        "jacobian rows agree with finite differences" {
            val coords = doubleArrayOf(0.3, 0.1, 4.2, 0.4, 1.0, 2.5, 3.0, 3.9)
            val constraints =
                listOf(
                    DistanceData(0, 1, 4.0, "d"),
                    PointOnLineData(2, 0, 1, "pol"),
                    ParallelData(0, 1, 2, 3, "par"),
                    PerpendicularData(0, 1, 2, 3, "perp"),
                )
            for (c in constraints) {
                val rows = DofAnalysis.jacobianRows(c, coords)
                rows.size shouldBe 1
                for (slot in coords.indices) {
                    val h = 1e-6
                    val plus = coords.copyOf().also { it[slot] += h }
                    val minus = coords.copyOf().also { it[slot] -= h }
                    val numeric = (DofAnalysis.residual(c, plus)[0] - DofAnalysis.residual(c, minus)[0]) / (2 * h)
                    val analytic = rows[0][slot] ?: 0.0
                    (Math.abs(numeric - analytic) < 1e-5) shouldBe true
                }
            }
        }
    })
