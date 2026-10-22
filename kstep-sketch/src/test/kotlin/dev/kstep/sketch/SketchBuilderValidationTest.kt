package dev.kstep.sketch

import dev.kstep.constraints.PlaneGcsSolver
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

private fun kindOf(block: () -> Unit): SketchErrorKind = shouldThrow<SketchException> { block() }.kind

class SketchBuilderValidationTest :
    StringSpec({
        "a reference from another builder is rejected" {
            var foreign: SketchPointRef? = null
            sketch { foreign = point(0.0, 0.0) }
            kindOf {
                sketch {
                    val mine = point(1.0, 1.0)
                    line(mine, foreign!!)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    val b = point(1.0, 0.0)
                    distance(a, foreign!!, 1.0)
                    horizontal(a, b)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "a foreign line reference is rejected too" {
            var foreignLine: SketchLineRef? = null
            sketch {
                foreignLine = line(point(0.0, 0.0), point(1.0, 0.0))
            }
            kindOf {
                sketch {
                    val l = line(point(0.0, 0.0), point(0.0, 1.0))
                    parallel(l, foreignLine!!)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "a point reference from another sketch is rejected by SolvedSketch.point" {
            var a: SketchPointRef? = null
            var own: SketchPointRef? = null
            sketch { a = point(1.0, 2.0) }
            val solvedB = sketch { own = point(5.0, 6.0) }.solve(requireFullyConstrained = false)
            solvedB.point(own!!).x shouldBe 5.0
            shouldThrow<SketchException> { solvedB.point(a!!) }.kind shouldBe SketchErrorKind.INVALID_INPUT
        }

        "non-finite and out-of-range coordinates are rejected" {
            kindOf { sketch { point(Double.NaN, 0.0) } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { point(0.0, Double.POSITIVE_INFINITY) } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { point(2e7, 0.0) } } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "dimensions must be finite and positive" {
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    val b = point(1.0, 0.0)
                    distance(a, b, 0.0)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    val b = point(1.0, 0.0)
                    distance(a, b, -3.0)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    val b = point(1.0, 0.0)
                    distance(a, b, Double.NaN)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { rectangle(0.0, 0.0, 0.0, 5.0) } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { circle(point(0.0, 0.0), 0.0) } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { circle(point(0.0, 0.0), Double.NaN) } } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "point names are limited to 64 printable ASCII characters" {
            sketch { point(0.0, 0.0, name = "a".repeat(64)) }
            kindOf { sketch { point(0.0, 0.0, name = "a".repeat(65)) } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { point(0.0, 0.0, name = "bad\u0007name") } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { point(0.0, 0.0, name = "umlautä") } } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf { sketch { point(0.0, 0.0, name = "line\nbreak") } } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "more than 64 points are rejected with LIMIT_EXCEEDED" {
            sketch { repeat(PlaneGcsSolver.MAX_POINTS) { point(it.toDouble(), 0.0) } }
            kindOf { sketch { repeat(PlaneGcsSolver.MAX_POINTS + 1) { point(it.toDouble(), 0.0) } } } shouldBe
                SketchErrorKind.LIMIT_EXCEEDED
        }

        "more than 128 constraints are rejected with LIMIT_EXCEEDED" {
            val build: SketchBuilder.(Int) -> Unit = { n ->
                val a = point(0.0, 0.0)
                val b = point(1.0, 0.0)
                repeat(n) { horizontal(a, b) }
            }
            sketch { build(PlaneGcsSolver.MAX_CONSTRAINTS) }
            kindOf { sketch { build(PlaneGcsSolver.MAX_CONSTRAINTS + 1) } } shouldBe SketchErrorKind.LIMIT_EXCEEDED
        }

        "more than 128 segments are rejected with LIMIT_EXCEEDED" {
            kindOf {
                sketch {
                    val c = point(0.0, 0.0)
                    repeat(SketchBuilder.MAX_SEGMENTS + 1) { circle(c, 1.0) }
                }
            } shouldBe SketchErrorKind.LIMIT_EXCEEDED
        }

        "degenerate arguments are rejected" {
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    line(a, a)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    horizontal(a, a)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
            kindOf {
                sketch {
                    val a = point(0.0, 0.0)
                    val b = point(1.0, 0.0)
                    arc(a, b, b, 1.0)
                }
            } shouldBe SketchErrorKind.INVALID_INPUT
        }

        "an arc adds two implicit radius constraints" {
            val s =
                sketch {
                    val c = point(0.0, 0.0, fixed = true)
                    val a = point(5.0, 0.0)
                    val b = point(0.0, 5.0)
                    arc(c, a, b, 5.0)
                }
            s.constraintCount shouldBe 2
            s.pointCount shouldBe 3
        }

        "a rectangle with a fixed corner has 4 points, 6 constraints and no freedom" {
            val s = sketch { rectangle(0.0, 0.0, 40.0, 20.0) }
            s.pointCount shouldBe 4
            s.constraintCount shouldBe 6
            val analysis = s.analyzeStart()
            analysis.degreesOfFreedom shouldBe 0
            analysis.redundantConstraints shouldBe emptyList()
        }

        "a rectangle with a free corner keeps two translational degrees of freedom" {
            val analysis = sketch { rectangle(0.0, 0.0, 40.0, 20.0, fixCorner = false) }.analyzeStart()
            analysis.degreesOfFreedom shouldBe 2
        }

        "offset adds a construction point only when both offsets are non-zero" {
            fun count(
                dx: Double,
                dy: Double,
            ): Int =
                sketch {
                    val from = point(0.0, 0.0, fixed = true)
                    val to = point(dx, dy)
                    offset(from, to, dx, dy)
                }.pointCount
            count(5.0, 3.0) shouldBe 3
            count(5.0, 0.0) shouldBe 2
            count(0.0, 3.0) shouldBe 2
            count(0.0, 0.0) shouldBe 2
        }

        "offset fully constrains its target point (no redundancy)" {
            for ((dx, dy) in listOf(5.0 to 3.0, 5.0 to 0.0, 0.0 to 3.0, -5.0 to -3.0, 0.0 to 0.0)) {
                val analysis =
                    sketch {
                        val from = point(0.0, 0.0, fixed = true)
                        val to = point(dx, dy)
                        offset(from, to, dx, dy)
                    }.analyzeStart()
                analysis.degreesOfFreedom shouldBe 0
                analysis.redundantConstraints shouldBe emptyList()
            }
        }

        "constraints between two distinct fixed points solve without a free point" {
            val s =
                sketch {
                    val a = point(0.0, 0.0, fixed = true)
                    val b = point(3.0, 4.0, fixed = true)
                    line(a, b)
                }
            s.solve().degreesOfFreedom shouldBe 0
        }

        "the exception message of a limit error names the limit" {
            shouldThrow<SketchException> { sketch { repeat(65) { point(0.0, 0.0) } } }.message!! shouldContain "64"
        }
    })
