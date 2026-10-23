package dev.kstep.geometry

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Pins the hand-synchronized enum codes shared with `kstep_occt_bridge.cpp`. */
class NativeCodeContractTest :
    StringSpec({
        "BooleanOperation native codes match nativeBooleanOp" {
            BooleanOperation.UNION.nativeCode shouldBe 0
            BooleanOperation.CUT.nativeCode shouldBe 1
            BooleanOperation.COMMON.nativeCode shouldBe 2
        }

        "RevolveAxis native codes match nativeRevolveProfile" {
            RevolveAxis.X.nativeCode shouldBe 0
            RevolveAxis.Y.nativeCode shouldBe 1
        }

        "derived naming constants match the native bridge source" {
            val cpp = java.io.File("src/main/cpp/kstep_occt_bridge.cpp").readText()

            fun constant(name: String) =
                Regex("""constexpr\s+\w+\s+$name\s*=\s*(\d+)""").find(cpp)!!.groupValues[1].toInt()
            constant("kMaxDerivedQueries") shouldBe OcctKernel.MAX_DERIVED_QUERIES
            constant("kDerivedKindEdge") shouldBe DERIVED_KIND_EDGE
            constant("kDerivedKindFace") shouldBe DERIVED_KIND_FACE
            DERIVED_KIND_EDGE shouldBe 0
            DERIVED_KIND_FACE shouldBe 1
            constant("kMaxHistoryInts") shouldBe OcctKernel.MAX_HISTORY_INTS
            OcctKernel.MAX_DERIVED_QUERIES shouldBe 4096
            OcctKernel.MAX_NAMING_SOURCES shouldBe 1024
        }
    })
