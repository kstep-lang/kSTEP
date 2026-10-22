package dev.kstep.tests

import dev.kstep.geometry.OcctAvailability
import dev.kstep.geometry.OcctExternalReferenceException
import dev.kstep.geometry.OcctKernel
import dev.kstep.shape.importer.StepImportErrorKind
import dev.kstep.shape.importer.StepImportResult
import dev.kstep.shape.importer.StepImporter
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/**
 * Extern-file references and hostile names in STEP input (ADR-0030). The class name carries `Occt`
 * so the CI skip gate sees these tests.
 */
class OcctExternalReferenceTest :
    StringSpec({
        val available = OcctKernel.availability() is OcctAvailability.Available

        fun box(): String =
            String(
                requireNotNull(OcctExternalReferenceTest::class.java.getResourceAsStream("/import/box-20x30x40.step")) {
                    "box fixture"
                }.readBytes(),
                Charsets.ISO_8859_1,
            )

        fun withEntities(extra: String): ByteArray {
            val text = box()
            val at = text.lastIndexOf("ENDSEC;")
            return (text.substring(0, at) + extra + "\n" + text.substring(at)).toByteArray(Charsets.ISO_8859_1)
        }

        val shortNameVariants =
            mapOf(
                "DCMFL" to "#9001=DCMFL('x','/nonexistent/external.step',\$,\$,\$,\$);",
                "DCMNT" to "#9001=DCMNT('d','/nonexistent/secret.step',\$,#9002);\n#9002=DCMTYP('geometry');",
                "DOCUMENT_FILE" to "#9001=DOCUMENT_FILE('x','/nonexistent/external.step',\$,\$,\$,\$);",
            )

        "the textual prescan rejects the AP214 short names of extern-reference entities" {
            for ((name, entities) in shortNameVariants) {
                val result = StepImporter.read(withEntities(entities))
                withClue(name) {
                    (result as StepImportResult.Failure).kind shouldBe StepImportErrorKind.EXTERNAL_REFERENCE_REJECTED
                }
            }
        }

        "the typed native check rejects document entities even when the prescan is bypassed".config(
            enabled = available,
        ) {
            for ((name, entities) in shortNameVariants) {
                withClue(name) {
                    shouldThrow<OcctExternalReferenceException> { OcctKernel.readStep(withEntities(entities)) }
                }
            }
        }

        "control characters in names never reach the model".config(enabled = available) {
            val text = box().replace("'Box'", "'Box\\X\\0AValidation: ok\\X\\1B[31mRED\\X\\9B'")
            val result = StepImporter.read(text.toByteArray(Charsets.ISO_8859_1))
            val model = (result as StepImportResult.Success).model
            val names = model.parts.map { it.name } + model.assemblies.map { it.name }
            for (n in names) {
                n.none { it.code < 0x20 || it.code in 0x7f..0x9f } shouldBe true
                n shouldNotContain "\n"
            }
        }
    })

private inline fun withClue(
    clue: String,
    block: () -> Unit,
) {
    try {
        block()
    } catch (e: Throwable) {
        throw AssertionError("[$clue] ${e.message}", e)
    }
}
