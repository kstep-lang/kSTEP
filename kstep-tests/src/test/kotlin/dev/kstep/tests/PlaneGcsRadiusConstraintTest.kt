package dev.kstep.tests

import dev.kstep.constraints.AngleConstraint
import dev.kstep.constraints.DistanceConstraint
import dev.kstep.constraints.EqualScalarConstraint
import dev.kstep.constraints.HorizontalConstraint
import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PointOnCircleConstraint
import dev.kstep.constraints.RadiusConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SketchScalar
import dev.kstep.constraints.TangentCircleCircleConstraint
import dev.kstep.constraints.TangentLineCircleConstraint
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Welle M (ADR-0032): scalar unknowns (radii) and the constraints built on them, through the real
 * PlaneGCS bridge. PlaneGCS only, no OCCT.
 */
class PlaneGcsRadiusConstraintTest :
    StringSpec({
        val available = PlaneGcsSolver.availability() is PlaneGcsAvailability.Available
        val tol = 1e-7

        "a free radius with a radius constraint solves to the value".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    listOf(RadiusConstraint(0, 3.0)),
                    listOf(SketchScalar(5.0)),
                )
            r.solved shouldBe true
            r.scalar(0) shouldBe (3.0 plusOrMinus tol)
        }

        "equal scalars propagate a fixed radius".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    listOf(EqualScalarConstraint(0, 1)),
                    listOf(SketchScalar(5.0, fixed = true), SketchScalar(2.0)),
                )
            r.scalar(1) shouldBe (5.0 plusOrMinus tol)
        }

        "a line is tangent to a circle: the radius follows from the centre".config(enabled = available) {
            // Line y = 4 (fixed), centre (20, 10) fixed, radius free -> r = 6
            val r =
                PlaneGcsSolver.solve(
                    listOf(
                        SketchPoint(5.0, 4.0, fixed = true),
                        SketchPoint(35.0, 4.0, fixed = true),
                        SketchPoint(20.0, 10.0, fixed = true),
                    ),
                    listOf(TangentLineCircleConstraint(0, 1, 2, 0)),
                    listOf(SketchScalar(5.0)),
                )
            r.solved shouldBe true
            r.scalar(0) shouldBe (6.0 plusOrMinus tol)
        }

        "a line is tangent to a circle: with a fixed radius the centre height follows".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(
                        SketchPoint(5.0, 4.0, fixed = true),
                        SketchPoint(35.0, 4.0, fixed = true),
                        SketchPoint(20.0, 8.0),
                    ),
                    listOf(
                        TangentLineCircleConstraint(0, 1, 2, 0),
                        DistanceConstraint(0, 2, Math.hypot(15.0, 3.0)),
                    ),
                    listOf(SketchScalar(3.0, fixed = true)),
                )
            r.solved shouldBe true
            r.points[2].y shouldBe (7.0 plusOrMinus tol)
        }

        "external and internal circle tangency pick the side from the start values".config(enabled = available) {
            fun solveB(
                internal: Boolean,
                startX: Double,
            ) = PlaneGcsSolver.solve(
                listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(startX, 0.0)),
                listOf(
                    HorizontalConstraint(0, 1),
                    TangentCircleCircleConstraint(0, 0, 1, 1, internal),
                ),
                listOf(SketchScalar(5.0, fixed = true), SketchScalar(3.0, fixed = true)),
            )
            solveB(internal = false, startX = 7.0).points[1].x shouldBe (8.0 plusOrMinus tol)
            solveB(internal = true, startX = 3.0).points[1].x shouldBe (2.0 plusOrMinus tol)
        }

        "a point on a circle with a free radius adopts the distance".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true), SketchPoint(4.0, 3.0, fixed = true)),
                    listOf(PointOnCircleConstraint(1, 0, 0)),
                    listOf(SketchScalar(1.0)),
                )
            r.scalar(0) shouldBe (5.0 plusOrMinus tol)
        }

        "an angle of 30 degrees between two lines".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(
                        SketchPoint(0.0, 0.0, fixed = true),
                        SketchPoint(10.0, 0.0, fixed = true),
                        SketchPoint(8.0, 4.0),
                    ),
                    listOf(
                        AngleConstraint(0, 1, 0, 2, Math.toRadians(30.0)),
                        DistanceConstraint(0, 2, 10.0),
                    ),
                )
            r.solved shouldBe true
            r.points[2].x shouldBe (8.660254037844386 plusOrMinus tol)
            r.points[2].y shouldBe (5.0 plusOrMinus tol)
        }

        "a radius that would be negative is rejected before the native call".config(enabled = available) {
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0)),
                    listOf(RadiusConstraint(0, -1.0)),
                    listOf(SketchScalar(1.0)),
                )
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0)),
                    listOf(RadiusConstraint(0, 1.0)),
                    listOf(SketchScalar(-1.0)),
                )
            }
        }

        "scalar validation: indices, duplicates and degenerate geometry".config(enabled = available) {
            val pts = listOf(SketchPoint(0.0, 0.0), SketchPoint(1.0, 1.0), SketchPoint(2.0, 0.0))
            val sc = listOf(SketchScalar(1.0), SketchScalar(2.0))
            shouldThrow<IllegalArgumentException> { PlaneGcsSolver.solve(pts, listOf(RadiusConstraint(2, 1.0)), sc) }
            shouldThrow<IllegalArgumentException> { PlaneGcsSolver.solve(pts, listOf(EqualScalarConstraint(0, 0)), sc) }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(pts, listOf(TangentCircleCircleConstraint(0, 0, 1, 0, false)), sc)
            }
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0), SketchPoint(0.0, 0.0)),
                    listOf(PointOnCircleConstraint(0, 1, 0)),
                    sc,
                )
            }
        }

        "a fixed-only system is rejected; free scalars alone are enough".config(enabled = available) {
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    emptyList(),
                    listOf(SketchScalar(1.0, fixed = true)),
                )
            }
            val r =
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    listOf(RadiusConstraint(0, 2.0)),
                    listOf(SketchScalar(1.0)),
                )
            r.scalar(0) shouldBe (2.0 plusOrMinus tol)
        }

        "the solver rejects more scalars than the cap".config(enabled = available) {
            shouldThrow<IllegalArgumentException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0)),
                    emptyList(),
                    List(PlaneGcsSolver.MAX_SCALARS + 1) { SketchScalar(1.0) },
                )
            }
        }
    })
