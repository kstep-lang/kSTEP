package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.occt.OcctBridge
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.lang.reflect.InvocationTargetException

/**
 * Welle J (ADR-0029), the T-19 analogue: every new `native*` function is called DIRECTLY via
 * reflection with hostile input that OcctKernel's own Kotlin validation would never let through,
 * proving the native side re-validates independently and that nothing crashes the JVM.
 */
class OcctGeometryOperationsNativeHostileInputTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available
        val dbl = java.lang.Double.TYPE
        val lng = java.lang.Long.TYPE
        val int = Integer.TYPE

        fun method(
            name: String,
            vararg types: Class<*>,
        ) = OcctBridge::class.java.getDeclaredMethod(name, *types)

        fun cause(block: () -> Any?): Throwable? = shouldThrow<InvocationTargetException> { block() }.cause

        "hostile inputs to every new native function yield documented exceptions, never a crash".config(
            enabled = available,
        ) {
            val handle = OcctBridge.nativeMakeBox(1.0, 1.0, 1.0)
            try {
                val transform = method("nativeTransformShape", lng, DoubleArray::class.java)
                val identity = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0)
                (cause { transform.invoke(OcctBridge, handle, null) } is NullPointerException) shouldBe true
                (cause { transform.invoke(OcctBridge, handle, DoubleArray(11)) } is IllegalArgumentException) shouldBe
                    true
                val scaled = identity.copyOf().also { it[0] = 2.0 }
                (cause { transform.invoke(OcctBridge, handle, scaled) } is IllegalArgumentException) shouldBe true
                val mirrored = identity.copyOf().also { it[0] = -1.0 }
                (cause { transform.invoke(OcctBridge, handle, mirrored) } is IllegalArgumentException) shouldBe true
                val nan = identity.copyOf().also { it[3] = Double.NaN }
                (cause { transform.invoke(OcctBridge, handle, nan) } is IllegalArgumentException) shouldBe true
                val far = identity.copyOf().also { it[3] = 1e12 }
                (cause { transform.invoke(OcctBridge, handle, far) } is IllegalArgumentException) shouldBe true
                (cause { transform.invoke(OcctBridge, 987654321L, identity) } is IllegalStateException) shouldBe true

                val cylinder = method("nativeMakeCylinder", dbl, dbl, dbl, dbl, dbl, dbl, dbl, dbl)
                (
                    cause {
                        cylinder.invoke(OcctBridge, Double.NaN, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (
                    cause {
                        cylinder.invoke(
                            OcctBridge,
                            1.0,
                            1e12,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            1.0,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (
                    cause {
                        cylinder.invoke(
                            OcctBridge,
                            1.0,
                            1.0,
                            1e12,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            1.0,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (
                    cause {
                        cylinder.invoke(
                            OcctBridge,
                            1.0,
                            1.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                            0.0,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true

                val boolOp = method("nativeBooleanOp", int, lng, lng)
                (cause { boolOp.invoke(OcctBridge, 7, handle, handle) } is IllegalArgumentException) shouldBe true
                (cause { boolOp.invoke(OcctBridge, -1, handle, handle) } is IllegalArgumentException) shouldBe true
                (cause { boolOp.invoke(OcctBridge, 0, handle, 987654321L) } is IllegalStateException) shouldBe true
                (cause { boolOp.invoke(OcctBridge, 0, 987654321L, handle) } is IllegalStateException) shouldBe true

                val revolve = method("nativeRevolveProfile", DoubleArray::class.java, dbl, int)
                val tri = doubleArrayOf(1.0, 0.0, 2.0, 0.0, 1.0, 1.0)
                (cause { revolve.invoke(OcctBridge, null, 90.0, 1) } is NullPointerException) shouldBe true
                (
                    cause {
                        revolve.invoke(
                            OcctBridge,
                            doubleArrayOf(1.0, 0.0, 2.0),
                            90.0,
                            1,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (cause { revolve.invoke(OcctBridge, tri, Double.NaN, 1) } is IllegalArgumentException) shouldBe true
                (cause { revolve.invoke(OcctBridge, tri, 0.0, 1) } is IllegalArgumentException) shouldBe true
                (cause { revolve.invoke(OcctBridge, tri, 361.0, 1) } is IllegalArgumentException) shouldBe true
                (cause { revolve.invoke(OcctBridge, tri, 90.0, 5) } is IllegalArgumentException) shouldBe true
                val crossing = doubleArrayOf(-1.0, 0.0, 1.0, 0.0, 1.0, 1.0)
                (cause { revolve.invoke(OcctBridge, crossing, 90.0, 1) } is IllegalArgumentException) shouldBe true

                val chamfer = method("nativeChamferEdges", lng, IntArray::class.java, dbl)
                (cause { chamfer.invoke(OcctBridge, handle, null, 0.1) } is NullPointerException) shouldBe true
                (cause { chamfer.invoke(OcctBridge, handle, IntArray(0), 0.1) } is IllegalArgumentException) shouldBe
                    true
                (cause { chamfer.invoke(OcctBridge, handle, IntArray(513), 0.1) } is IllegalArgumentException) shouldBe
                    true
                (cause { chamfer.invoke(OcctBridge, handle, intArrayOf(0), 1e12) } is IllegalArgumentException) shouldBe
                    true
                (
                    cause {
                        chamfer.invoke(
                            OcctBridge,
                            handle,
                            intArrayOf(0),
                            Double.NaN,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (cause { chamfer.invoke(OcctBridge, handle, intArrayOf(-1), 0.1) } is IllegalArgumentException) shouldBe
                    true
                (
                    cause {
                        chamfer.invoke(
                            OcctBridge,
                            handle,
                            intArrayOf(9999),
                            0.1,
                        )
                    } is IllegalArgumentException
                ) shouldBe
                    true
                (cause { chamfer.invoke(OcctBridge, 987654321L, intArrayOf(0), 0.1) } is IllegalStateException) shouldBe
                    true
            } finally {
                OcctBridge.nativeReleaseShape(handle)
            }
            OcctKernel.occtVersion() shouldNotBe ""
        }
    })
