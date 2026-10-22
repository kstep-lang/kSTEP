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
    })
