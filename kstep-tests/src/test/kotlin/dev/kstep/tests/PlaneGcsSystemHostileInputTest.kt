package dev.kstep.tests

import dev.kstep.constraints.PlaneGcsAvailability
import dev.kstep.constraints.PlaneGcsSolver
import dev.kstep.constraints.PlaneGcsUnavailableException
import dev.kstep.constraints.RadiusConstraint
import dev.kstep.constraints.SketchPoint
import dev.kstep.constraints.SketchScalar
import dev.kstep.constraints.planegcs.PlaneGcsBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Welle M (ADR-0032): the raw `nativeSolveSystem` entry point must reject hostile input with a Java exception,
 * never crash the JVM. Reaches the native method directly, bypassing PlaneGcsSolver's own validation.
 */
class PlaneGcsSystemHostileInputTest :
    StringSpec({
        val available = PlaneGcsSolver.availability() is PlaneGcsAvailability.Available

        // one point (fixed), one scalar, one RADIUS constraint -- the valid baseline each case mutates
        class Req(
            val coords: DoubleArray? = doubleArrayOf(0.0, 0.0, 5.0, 0.0),
            val fixed: IntArray? = intArrayOf(1, 0),
            val scalars: DoubleArray? = doubleArrayOf(1.0),
            val scalarFixed: IntArray? = intArrayOf(0),
            val kinds: IntArray? = intArrayOf(7),
            val points: IntArray? = intArrayOf(-1, -1, -1, -1),
            val cScalars: IntArray? = intArrayOf(0, -1),
            val params: DoubleArray? = doubleArrayOf(3.0),
            val outCoords: DoubleArray? = DoubleArray(4),
            val outScalars: DoubleArray? = DoubleArray(1),
        )

        fun call(r: Req): Int =
            try {
                PlaneGcsBridge.nativeSolveSystem(
                    r.coords!!,
                    r.fixed!!,
                    r.scalars!!,
                    r.scalarFixed!!,
                    r.kinds!!,
                    r.points!!,
                    r.cScalars!!,
                    r.params!!,
                    100,
                    1e-10,
                    r.outCoords!!,
                    r.outScalars!!,
                )
            } catch (e: NullPointerException) {
                throw e
            }

        fun callReflect(r: Req): Throwable {
            val m =
                PlaneGcsBridge::class.java.getDeclaredMethod(
                    "nativeSolveSystem",
                    DoubleArray::class.java,
                    IntArray::class.java,
                    DoubleArray::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                    IntArray::class.java,
                    DoubleArray::class.java,
                    Int::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType,
                    DoubleArray::class.java,
                    DoubleArray::class.java,
                )
            return shouldThrow<java.lang.reflect.InvocationTargetException> {
                m.invoke(
                    PlaneGcsBridge,
                    r.coords,
                    r.fixed,
                    r.scalars,
                    r.scalarFixed,
                    r.kinds,
                    r.points,
                    r.cScalars,
                    r.params,
                    100,
                    1e-10,
                    r.outCoords,
                    r.outScalars,
                )
            }.cause!!
        }

        fun rejected(r: Req) {
            callReflect(r).shouldBeInstanceOf<IllegalArgumentException>()
        }

        "the valid baseline solves".config(enabled = available) {
            val r = Req()
            call(r) shouldBe 0
            r.outScalars!![0] shouldBe 3.0
        }

        "null arrays are rejected with NullPointerException".config(enabled = available) {
            callReflect(Req(scalars = null)).shouldBeInstanceOf<NullPointerException>()
            callReflect(Req(scalarFixed = null)).shouldBeInstanceOf<NullPointerException>()
            callReflect(Req(cScalars = null)).shouldBeInstanceOf<NullPointerException>()
            callReflect(Req(outScalars = null)).shouldBeInstanceOf<NullPointerException>()
        }

        "wrong array lengths are rejected".config(enabled = available) {
            rejected(Req(scalarFixed = intArrayOf(0, 0)))
            rejected(Req(outScalars = DoubleArray(2)))
            rejected(Req(cScalars = intArrayOf(0)))
            rejected(Req(cScalars = intArrayOf(0, -1, -1, -1)))
        }

        "scalar index out of range, or an unused slot that is not -1, is rejected".config(enabled = available) {
            rejected(Req(cScalars = intArrayOf(1, -1)))
            rejected(Req(cScalars = intArrayOf(-1, -1)))
            rejected(Req(cScalars = intArrayOf(0, 0)))
            // an unused scalar slot on an OLD kind (P2P distance, kind 0) must be -1 too
            rejected(Req(kinds = intArrayOf(0), points = intArrayOf(0, 1, -1, -1), cScalars = intArrayOf(0, -1)))
        }

        "bad scalar values are rejected".config(enabled = available) {
            rejected(Req(scalars = doubleArrayOf(Double.NaN)))
            rejected(Req(scalars = doubleArrayOf(-1.0)))
            rejected(Req(scalars = doubleArrayOf(0.0)))
            rejected(Req(scalars = doubleArrayOf(2e9)))
            rejected(Req(scalarFixed = intArrayOf(2)))
            rejected(Req(params = doubleArrayOf(-3.0)))
        }

        "more than 128 scalars are rejected".config(enabled = available) {
            rejected(
                Req(
                    scalars = DoubleArray(129) { 1.0 },
                    scalarFixed = IntArray(129),
                    outScalars = DoubleArray(129),
                ),
            )
        }

        "internal flag outside 0/1 and angle outside [-pi, pi] are rejected".config(enabled = available) {
            rejected(
                Req(
                    scalars = doubleArrayOf(1.0, 2.0),
                    scalarFixed = intArrayOf(0, 0),
                    outScalars = DoubleArray(2),
                    kinds = intArrayOf(11),
                    points = intArrayOf(0, 1, -1, -1),
                    cScalars = intArrayOf(0, 1),
                    params = doubleArrayOf(0.5),
                ),
            )
            rejected(
                Req(
                    coords = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0, 1.0),
                    fixed = intArrayOf(1, 1, 0),
                    outCoords = DoubleArray(6),
                    kinds = intArrayOf(12),
                    points = intArrayOf(0, 1, 0, 2),
                    cScalars = intArrayOf(-1, -1),
                    params = doubleArrayOf(4.0),
                ),
            )
        }

        "a degenerate tangent line is rejected".config(enabled = available) {
            rejected(
                Req(
                    coords = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 3.0, 3.0),
                    fixed = intArrayOf(1, 1, 0),
                    outCoords = DoubleArray(6),
                    kinds = intArrayOf(10),
                    points = intArrayOf(0, 1, 2, -1),
                    cScalars = intArrayOf(0, -1),
                    params = doubleArrayOf(0.0),
                ),
            )
        }

        "the JVM survives all of it and still solves".config(enabled = available) {
            val r =
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    listOf(RadiusConstraint(0, 2.0)),
                    listOf(SketchScalar(1.0)),
                )
            r.scalar(0) shouldBe (2.0 plusOrMinus 1e-7)
        }

        "scalar solving fails predictably when the native bridge is unavailable".config(enabled = !available) {
            shouldThrow<PlaneGcsUnavailableException> {
                PlaneGcsSolver.solve(
                    listOf(SketchPoint(0.0, 0.0, fixed = true)),
                    listOf(RadiusConstraint(0, 2.0)),
                    listOf(SketchScalar(1.0)),
                )
            }
        }
    })
