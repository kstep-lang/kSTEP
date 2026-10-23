package dev.kstep.tests

import dev.kstep.geometry.LengthUnitState
import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctKernel
import dev.kstep.geometry.OcctUnitException
import dev.kstep.shape.importer.StepImportErrorKind
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Length units of imported files (ADR-0038). The geometry always arrives in millimetres; the file's own unit
 * is reported next to it. MIXED units (several contexts with different factors) are derived from the committed
 * box fixture by adding a second context at test time.
 */
class OcctStepImportUnitsTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun bytes(name: String): ByteArray =
            requireNotNull(OcctStepImportUnitsTest::class.java.getResourceAsStream("/import/$name")) { name }
                .use { it.readBytes() }

        fun success(name: String): dev.kstep.shape.importer.ImportedStepModel {
            val result = StepImporter.read(bytes(name))
            check(result is StepImportResult.Success) { "import failed: $result" }
            return result.model
        }

        // The 20 x 30 x 40 box numbers are the same in every fixture; only the declared unit differs.
        "inch: 20 x 30 x 40 inch arrives as 508 x 762 x 1016 mm, volume 24000 * 25.4^3".config(enabled = available) {
            success("inch-box.step").use { model ->
                val box = model.parts[0].boundingBox!!
                box.maxX shouldBe (508.0 plusOrMinus 1e-6)
                box.maxY shouldBe (762.0 plusOrMinus 1e-6)
                box.maxZ shouldBe (1016.0 plusOrMinus 1e-6)
                model.parts[0].volume!! shouldBe (24000.0 * 25.4 * 25.4 * 25.4 plusOrMinus 1e-2)
                val unit = model.sourceLengthUnit!!
                unit.state shouldBe LengthUnitState.DECLARED
                unit.names shouldBe listOf("INCH")
                unit.factorToMm!! shouldBe (25.4 plusOrMinus 1e-12)
                model.warnings.none { it.contains("length unit") } shouldBe true
            }
        }

        "centimetre and metre scale to mm" {
            if (available) {
                success("cm-box.step").use {
                    it.parts[0].boundingBox!!.maxX shouldBe (200.0 plusOrMinus 1e-6)
                    it.sourceLengthUnit!!.factorToMm!! shouldBe (10.0 plusOrMinus 1e-12)
                }
                success("m-box.step").use {
                    it.parts[0].boundingBox!!.maxX shouldBe (20000.0 plusOrMinus 1e-6)
                    it.sourceLengthUnit!!.factorToMm!! shouldBe (1000.0 plusOrMinus 1e-12)
                }
            }
        }

        "a file without any length unit is read as mm, with state MISSING and a warning".config(enabled = available) {
            success("no-unit-box.step").use { model ->
                model.sourceLengthUnit!!.state shouldBe LengthUnitState.MISSING
                model.sourceLengthUnit!!.factorToMm shouldBe null
                model.parts[0].boundingBox!!.maxX shouldBe (20.0 plusOrMinus 1e-6)
                model.warnings.any { it.contains("declares no length unit") } shouldBe true
            }
        }

        "a millimetre file reports DECLARED with factor 1 and no unit warning".config(enabled = available) {
            success("box-20x30x40.step").use { model ->
                model.sourceLengthUnit!!.state shouldBe LengthUnitState.DECLARED
                model.sourceLengthUnit!!.factorToMm!! shouldBe (1.0 plusOrMinus 1e-12)
                model.warnings.none { it.contains("length unit") } shouldBe true
            }
        }

        "contexts with different length units are reported as MIXED, with a warning".config(enabled = available) {
            // box-20x30x40.step plus a second, otherwise unused, representation context declared in centimetres.
            val text = bytes("box-20x30x40.step").toString(Charsets.ISO_8859_1)
            val extra =
                "#9001=(LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT(.CENTI.,.METRE.));\n" +
                    "#9002=(GEOMETRIC_REPRESENTATION_CONTEXT(3) GLOBAL_UNIT_ASSIGNED_CONTEXT((#9001)) " +
                    "REPRESENTATION_CONTEXT('second','3D'));\n"
            val patched = text.replace("ENDSEC;\nEND-ISO-10303-21;", extra + "ENDSEC;\nEND-ISO-10303-21;")
            (patched != text) shouldBe true
            val result = StepImporter.read(patched.toByteArray(Charsets.ISO_8859_1))
            check(result is StepImportResult.Success) { "import failed: $result" }
            result.model.use { model ->
                model.sourceLengthUnit!!.state shouldBe LengthUnitState.MIXED
                model.warnings.any { it.contains("different length units") } shouldBe true
            }
        }

        "a conversion factor of zero is rejected before transfer as UNIT_UNRESOLVED (KSTEP-I-013)".config(
            enabled = available,
        ) {
            val result = StepImporter.read(bytes("unresolved-unit-box.step"))
            (result as StepImportResult.Failure).kind shouldBe StepImportErrorKind.UNIT_UNRESOLVED
            result.kind.code shouldBe "KSTEP-I-013"
        }

        "a mass or time unit as the last context entry does not reject a millimetre file".config(enabled = available) {
            val text = bytes("box-20x30x40.step").toString(Charsets.ISO_8859_1)
            val extras =
                listOf(
                    "#9100=(MASS_UNIT() NAMED_UNIT(*) SI_UNIT(.KILO.,.GRAM.));\n",
                    "#9100=(NAMED_UNIT(*) SI_UNIT(\$,.SECOND.) TIME_UNIT());\n",
                )
            for (extra in extras) {
                val patched =
                    text
                        .replace(
                            "GLOBAL_UNIT_ASSIGNED_CONTEXT((#352,#353,#354))",
                            "GLOBAL_UNIT_ASSIGNED_CONTEXT((#352,#353,#354,#9100))",
                        ).replace("ENDSEC;\nEND-ISO-10303-21;", extra + "ENDSEC;\nEND-ISO-10303-21;")
                (patched != text) shouldBe true
                val result = StepImporter.read(patched.toByteArray(Charsets.ISO_8859_1))
                check(result is StepImportResult.Success) { "import failed: $result" }
                result.model.use { model ->
                    model.sourceLengthUnit!!.state shouldBe LengthUnitState.DECLARED
                    model.parts[0].boundingBox!!.maxX shouldBe (20.0 plusOrMinus 1e-6)
                }
            }
        }

        "the kernel maps the native unit failure to OcctUnitException".config(enabled = available) {
            shouldThrow<OcctUnitException> { OcctKernel.readStep(bytes("unresolved-unit-box.step")) }
        }
    })
