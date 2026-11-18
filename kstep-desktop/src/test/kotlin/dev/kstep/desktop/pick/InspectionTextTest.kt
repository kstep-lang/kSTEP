package dev.kstep.desktop.pick

import dev.kstep.desktop.model.PartInfo
import dev.kstep.desktop.model.PartPlacement
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class InspectionTextTest :
    StringSpec({
        fun info(
            min: DoubleArray,
            max: DoubleArray,
        ) = PartInfo("x", 0, 1, PartPlacement(DoubleArray(3), DoubleArray(9)), "#000000", min, max, DoubleArray(3))

        "numbers have two decimals and a dot, whatever the default locale is" {
            val saved = Locale.getDefault()
            try {
                Locale.setDefault(Locale.GERMANY)
                InspectionText.number(1234.5) shouldBe "1234.50"
                InspectionText.number(0.005) shouldBe "0.01"
                InspectionText.point(doubleArrayOf(1.0, 2.5, -3.0)) shouldBe "(1.00, 2.50, -3.00) mm"
            } finally {
                Locale.setDefault(saved)
            }
        }

        "a negative zero is shown as zero" {
            InspectionText.number(-0.0) shouldBe "0.00"
            InspectionText.number(-0.001) shouldBe "0.00"
        }

        "the size is width x depth x height in millimetres" {
            InspectionText.size(info(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(10.0, 20.5, 3.0))) shouldBe
                "10.00 × 20.50 × 3.00 mm"
        }

        "triangle counts get a thousands separator" {
            InspectionText.triangles(0) shouldBe "0"
            InspectionText.triangles(999) shouldBe "999"
            InspectionText.triangles(1000) shouldBe "1,000"
            InspectionText.triangles(130_000) shouldBe "130,000"
            InspectionText.triangles(1_234_567) shouldBe "1,234,567"
        }
    })
